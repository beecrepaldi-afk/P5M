// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Profundidade do 3D emulado vinda de uma rede neural, e não das pistas da
 * imagem.
 *
 * O modelo é o ZipDepth (MIT, Fabio Tosi), na exportação para o LiteRT feita
 * pelo Moonlight XR (GPL-3.0): 512x288, fp16, todo no delegate de GPU. Lá ele
 * mede 15 a 16 ms por mapa no Quest 3 com o stream rodando.
 *
 * Esta thread só roda o modelo. Quem captura o quadro, normaliza a saída e
 * sobe o mapa é o nativo (profundidade_neural.cpp e a ToneMapper); aqui é
 * esperar um quadro, rodar e devolver, em laço. Se o modelo não carregar no
 * delegate de GPU, a thread desiste e o 3D segue com as pistas, como antes --
 * na CPU o mesmo modelo leva perto de 200 ms e disputaria núcleo com o
 * decodificador.
 */
object ProfundidadeNeural
{
	private const val MODELO = "zipdepth_512x288_fp16.tflite"
	private const val LARGURA = 512
	private const val ALTURA = 288
	private const val CACHE_DE_KERNELS = "depth-kernels"
	private const val JANELA_NS = 10_000_000_000L

	private val lock = Any()
	@Volatile private var rodando = false
	private var thread: Thread? = null

	init { System.loadLibrary("p5m-vr") }

	fun start(context: Context)
	{
		synchronized(lock)
		{
			if(rodando)
				return
			rodando = true
		}
		val app = context.applicationContext
		thread = Thread({ laco(app) }, "p5m-neural-depth").also { it.start() }
	}

	/** Para e espera a thread: depois disto a ToneMapper volta às pistas. */
	fun stop()
	{
		synchronized(lock)
		{
			rodando = false
			// Acorda quem estiver esperando quadro: sem isto o join esperaria o
			// próximo quadro capturado, que com a rede desligada nunca vem.
			nativeDesligar()
		}
		thread?.join(1000)
		thread = null
	}

	private fun laco(context: Context)
	{
		// Um pouco acima do normal, para o modelo não ficar atrás de tudo num
		// sistema ocupado. BACKGROUND não: nesse SoC esse cpuset é só de
		// núcleos pequenos, e o mapa sairia mais lento, não mais educado.
		Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
		var interpretador: Interpreter? = null
		var delegado: GpuDelegate? = null
		try
		{
			val inicio = SystemClock.elapsedRealtime()
			val modelo = carregar(context)
			val opcoes = GpuDelegateFactory.Options().apply {
				// fp16 na conta, que é o que o modelo já carrega
				setPrecisionLossAllowed(true)
				setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
				// Os kernels compilados ficam em cache: a primeira sessão paga a
				// compilação (alguns segundos), as seguintes não.
				val dir = File(context.cacheDir, CACHE_DE_KERNELS)
				if(dir.isDirectory || dir.mkdirs())
					setSerializationParams(dir.absolutePath, "$MODELO-${modelo.capacity()}")
			}
			delegado = GpuDelegate(opcoes)
			interpretador = Interpreter(modelo, Interpreter.Options().addDelegate(delegado))

			val formaEntrada = interpretador.getInputTensor(0).shape()
			val formaSaida = interpretador.getOutputTensor(0).shape()
			if(!formaEntrada.contentEquals(intArrayOf(1, ALTURA, LARGURA, 3))
					|| formaSaida.filter { it != 1 } != listOf(ALTURA, LARGURA))
			{
				Trace.log(context, "Neural depth: model shape ${formaEntrada.joinToString("x")} -> "
						+ "${formaSaida.joinToString("x")} is not ${LARGURA}x$ALTURA; 3D stays on the image cues")
				return
			}

			val entrada = ByteBuffer.allocateDirect(LARGURA * ALTURA * 3 * 4).order(ByteOrder.nativeOrder())
			val saida = ByteBuffer.allocateDirect(LARGURA * ALTURA * 4).order(ByteOrder.nativeOrder())
			// Uma rodada antes de valer: a primeira é a que monta o grafo na GPU
			// e pode passar de um segundo, e ela não deve contar como mapa.
			interpretador.run(entrada, saida)
			Trace.log(context, "Neural depth: ZipDepth ${LARGURA}x$ALTURA on the GPU delegate, "
					+ "ready in ${SystemClock.elapsedRealtime() - inicio} ms")

			synchronized(lock)
			{
				if(!rodando)
					return
				nativeLigar()
			}

			var mapas = 0
			var somaNs = 0L
			var piorNs = 0L
			var janela = System.nanoTime()
			while(rodando)
			{
				entrada.rewind()
				if(!nativeEsperarEntrada(entrada))
					break
				entrada.rewind()
				saida.rewind()
				val t0 = System.nanoTime()
				val ok = try
				{
					interpretador.run(entrada, saida)
					true
				}
				catch(e: Exception)
				{
					Trace.log(context, "Neural depth: inference failed: ${e.message}")
					false
				}
				val dt = System.nanoTime() - t0
				nativePublicarSaida(saida, ok)

				mapas++
				somaNs += dt
				if(dt > piorNs)
					piorNs = dt
				val agora = System.nanoTime()
				if(agora - janela >= JANELA_NS)
				{
					val segundos = (agora - janela) / 1e9
					Trace.log(context, "Neural depth 10s: $mapas maps (%.1f/s), inference %.1f ms avg, %.1f ms worst"
							.format(mapas / segundos, somaNs / 1e6 / mapas, piorNs / 1e6))
					mapas = 0
					somaNs = 0
					piorNs = 0
					janela = agora
				}
			}
		}
		catch(e: Throwable)
		{
			// Delegate recusado, asset ausente, biblioteca que não carregou:
			// qualquer um deles deixa o 3D exatamente como era antes da rede.
			Trace.log(context, "Neural depth unavailable (${e.javaClass.simpleName}: ${e.message}); "
					+ "3D stays on the image cues")
		}
		finally
		{
			nativeDesligar()
			try { interpretador?.close() } catch(_: Throwable) { }
			try { delegado?.close() } catch(_: Throwable) { }
		}
	}

	private fun carregar(context: Context): MappedByteBuffer
	{
		// Mapeado direto do APK, sem cópia: o asset vai sem compressão
		// (noCompress no build.gradle) justamente para isso funcionar.
		context.assets.openFd(MODELO).use { fd ->
			FileInputStream(fd.fileDescriptor).use { stream ->
				return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
			}
		}
	}

	private external fun nativeLigar()
	private external fun nativeDesligar()
	private external fun nativeEsperarEntrada(entrada: ByteBuffer): Boolean
	private external fun nativePublicarSaida(saida: ByteBuffer, ok: Boolean)
}
