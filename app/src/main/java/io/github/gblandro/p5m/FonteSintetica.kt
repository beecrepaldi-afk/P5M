// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * Uma fonte de vídeo de 60 fps que não precisa de console, de rede nem de
 * arquivo.
 *
 * Serve para responder a única pergunta que interessa antes de escolher
 * qualquer protocolo: **o nosso caminho de vídeo é bom sozinho?** Enquanto a
 * fonte for um PS5 do outro lado de um Wi-Fi, todo número medido carrega junto
 * a rede, o encoder do console e o Remote Play, e não dá para saber de quem é
 * a culpa de um engasgo. Aqui não há nada disso: o quadro nasce dentro do
 * aparelho, com cadência de relógio. Qualquer irregularidade medida é nossa.
 *
 * O truque é codificar **uma vez** e reproduzir em laço. Codificar ao vivo
 * gastaria CPU e GPU justamente no instante que se quer medir — a medição
 * criaria o defeito que ela procura. Então a preparação gera alguns segundos
 * de vídeo, guarda só as unidades de acesso comprimidas (poucos megabytes) e
 * joga fora o resto.
 *
 * O padrão é uma barra que atravessa a tela: movimento de verdade, para o
 * codificador ter o que fazer, e movimento que o olho acompanha, para dar para
 * julgar judder olhando além de medindo.
 */
class FonteSintetica(
	private val largura: Int = 1920,
	private val altura: Int = 1080,
	private val fps: Int = 60,
	private val segundos: Int = 2,
	/**
	 * Cenario do framegen: 12 s em tres partes, cada uma com a verdade de
	 * quadro real/repeticao escrita no pts (bit 0 = repeticao, bits 1-2 =
	 * parte), que a camada nativa confere contra o que ela classificou.
	 *  0. Camera andando a 30 fps (cada imagem duas vezes no stream de 60).
	 *  1. Parado a 30 fps, so com ruido pequeno -- o caso da tremulacao.
	 *  2. Camera andando com quedas: imagens de 2, 2 e 3 tempos (~27 fps), e
	 *     o movimento pelo relogio, como num jogo de verdade.
	 */
	private val cenario: Boolean = false)
{
	// Por quadro do laco, no cenario: repeticao da imagem anterior, parte, e
	// o instante (em quadros de 60) que a imagem mostra.
	private var repete = BooleanArray(0)
	private var parte = IntArray(0)
	private var instante = IntArray(0)

	private fun montarCenario(total: Int)
	{
		repete = BooleanArray(total)
		parte = IntArray(total)
		instante = IntArray(total)
		val terco = total / 3
		var ultimaTroca = 0
		var padrao = 0
		var restante = 0
		for(n in 0 until total)
		{
			val p = minOf(n / terco, 2)
			parte[n] = p
			when(p)
			{
				0, 1 ->
				{
					repete[n] = n % 2 == 1
					instante[n] = if(repete[n]) instante[n - 1] else n
				}
				else ->
				{
					if(restante == 0)
					{
						restante = intArrayOf(2, 2, 3)[padrao % 3]
						padrao++
						ultimaTroca = n
					}
					repete[n] = n != ultimaTroca
					instante[n] = ultimaTroca
					restante--
				}
			}
		}
	}

	/** O pts do quadro n do laco: contador, com a verdade nos bits baixos. */
	private fun pts(n: Long): Long
	{
		if(!cenario || repete.isEmpty())
			return n
		val i = (n % repete.size).toInt()
		return n * 8 + parte[i] * 2 + (if(repete[i]) 1 else 0)
	}

	private val quadros = ArrayList<ByteArray>()
	private var configuracao: ByteArray? = null
	private var decodificador: MediaCodec? = null
	private var alimentador: Thread? = null
	private var coletor: Thread? = null
	@Volatile private var rodando = false
	@Volatile private var entregando = true
	private val medidor = MedidorDeQuadros("Bench 10s")

	var ultimoErro: String = ""
		private set

	/** Quantas unidades de acesso o laço tem, depois de [preparar]. */
	val quadrosPreparados: Int get() = quadros.size

	/**
	 * Codifica o laço. Demora alguns segundos e **não pode rodar na thread
	 * principal**.
	 */
	fun preparar(): Boolean
	{
		val total = fps * segundos
		if(cenario)
			montarCenario(total)
		val formato = MediaFormat.createVideoFormat(MIME, largura, altura).apply {
			setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
			setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
			setInteger(MediaFormat.KEY_FRAME_RATE, fps)
			// Um único quadro-chave, no índice zero. O laço volta para lá, então
			// é o suficiente -- e mais quadros-chave só engordariam o laço sem
			// mudar nada do que se quer medir.
			setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, segundos)
		}

		val codificador = try
		{
			MediaCodec.createEncoderByType(MIME).also {
				it.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
				it.start()
			}
		}
		catch(e: Exception)
		{
			ultimoErro = "Could not open the $MIME encoder: " +
					"${e::class.java.simpleName}: ${e.message}"
			Log.e(TAG, ultimoErro, e)
			return false
		}

		val info = MediaCodec.BufferInfo()
		var entrada = 0
		var fimEnviado = false
		val comeco = System.nanoTime()
		// Trava de segurança. Um codificador que trava sem devolver erro deixaria
		// o laço girando para sempre numa thread de fundo, e o sintoma seria o
		// app parado numa tela de "preparando" sem uma linha que explicasse.
		val limite = comeco + 60_000_000_000L
		try
		{
			while(quadros.size < total)
			{
				if(System.nanoTime() > limite)
				{
					ultimoErro = "The encoder stopped responding: " +
							"${quadros.size} of $total frames in 60 s."
					Log.e(TAG, ultimoErro)
					return false
				}
				if(entrada < total)
				{
					val i = codificador.dequeueInputBuffer(TIMEOUT_US)
					if(i >= 0)
					{
						desenhar(codificador, i, entrada)
						// O tamanho é a capacidade do buffer, e não a conta de
						// largura por altura vezes um e meio: com `rowStride`
						// maior que a largura -- que é o normal -- a conta erra
						// para menos e o codificador lê metade do quadro.
						val tamanho = codificador.getInputBuffer(i)?.capacity() ?: 0
						codificador.queueInputBuffer(i, 0, tamanho,
								entrada * 1_000_000L / fps, 0)
						entrada++
					}
				}
				else if(!fimEnviado)
				{
					// Um buffer vazio marcado com fim de fluxo faz o codificador
					// soltar o que ainda segurava. Tentado a cada volta até
					// entrar: uma única tentativa que falhasse deixaria a
					// preparação esperando quadros que nunca sairiam.
					val f = codificador.dequeueInputBuffer(TIMEOUT_US)
					if(f >= 0)
					{
						codificador.queueInputBuffer(f, 0, 0, 0,
								MediaCodec.BUFFER_FLAG_END_OF_STREAM)
						fimEnviado = true
					}
				}

				val o = codificador.dequeueOutputBuffer(info, TIMEOUT_US)
				if(o >= 0)
				{
					val buf = codificador.getOutputBuffer(o)
					if(buf != null && info.size > 0)
					{
						val bytes = ByteArray(info.size)
						buf.position(info.offset)
						buf.get(bytes, 0, info.size)
						if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
							configuracao = bytes
						else
							quadros.add(bytes)
					}
					codificador.releaseOutputBuffer(o, false)
					if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
						break
				}
			}
		}
		catch(e: Exception)
		{
			ultimoErro = "Failed to generate the loop: ${e::class.java.simpleName}: ${e.message}"
			Log.e(TAG, ultimoErro, e)
			return false
		}
		finally
		{
			runCatching { codificador.stop() }
			runCatching { codificador.release() }
		}

		if(quadros.isEmpty())
		{
			ultimoErro = "The encoder returned no frames at all."
			return false
		}

		val bytes = quadros.sumOf { it.size }
		Log.i(TAG, "Loop ready: ${quadros.size} frames, ${bytes / 1024} KiB, " +
				"generated in ${(System.nanoTime() - comeco) / 1_000_000} ms")
		return true
	}

	/**
	 * Preenche um buffer de entrada com o quadro `n`.
	 *
	 * O croma inteiro vai a 128 -- cinza neutro -- e por isso pode ser preenchido
	 * byte a byte sem olhar o arranjo do plano: em 128 o semi-planar e o planar
	 * dão a mesma coisa. O luma respeita o `rowStride`, que é o único lugar onde
	 * o arranjo real importa.
	 */
	private fun desenhar(codificador: MediaCodec, indice: Int, n: Int)
	{
		if(cenario)
		{
			desenharCenario(codificador, indice, n)
			return
		}
		val imagem = codificador.getInputImage(indice) ?: return
		val planos = imagem.planes

		val linha = ByteArray(largura)
		val fundo = (16 + (n * 3) % 40).toByte()
		java.util.Arrays.fill(linha, fundo)
		// A barra varre a tela em um segundo, de ponta a ponta.
		val x = ((n % fps).toLong() * largura / fps).toInt()
		for(i in x until minOf(x + LARGURA_BARRA, largura))
			linha[i] = 235.toByte()

		val y = planos[0].buffer
		val passo = planos[0].rowStride
		for(l in 0 until altura)
		{
			y.position(l * passo)
			y.put(linha, 0, largura)
		}

		for(p in 1 until planos.size)
			preencher(planos[p].buffer, 128.toByte())
	}

	/**
	 * Tabuleiro que anda na horizontal (600 px/s), textura para a previsao ter
	 * o que seguir, ou parado com um ruido pequeno que muda a cada imagem nova.
	 * Repeticao desenha exatamente a imagem anterior.
	 */
	private fun desenharCenario(codificador: MediaCodec, indice: Int, n: Int)
	{
		val imagem = codificador.getInputImage(indice) ?: return
		val planos = imagem.planes
		val t = instante[n]
		val parado = parte[n] == 1
		val desloc = if(parado) 0 else t * 10
		val bloco = 48
		val linhas = Array(2) { paridade ->
			ByteArray(largura) { x ->
				val c = ((x + desloc) / bloco + paridade) % 2
				(if(c == 0) 40 else 170).toByte()
			}
		}
		// Ruido: alguns pontos numa regiao, sorteados pelo instante (igual
		// na repeticao, diferente na imagem nova).
		val ruido = java.util.Random(t.toLong() * 7919)
		val y = planos[0].buffer
		val passo = planos[0].rowStride
		for(l in 0 until altura)
		{
			y.position(l * passo)
			y.put(linhas[(l / bloco) % 2], 0, largura)
		}
		if(parado)
		{
			// Denso o bastante para a grade de comparacao (64x36 pontos) ver:
			// no jogo parado a diferenca medida foi 0,1-0,5.
			for(l in 0 until altura)
				for(x in 0 until largura)
				{
					val i = l * passo + x
					y.put(i, (y.get(i) + ruido.nextInt(3) - 1).toByte())
				}
		}
		for(p in 1 until planos.size)
			preencher(planos[p].buffer, 128.toByte())
	}

	private fun preencher(buf: ByteBuffer, valor: Byte)
	{
		buf.position(0)
		val bloco = ByteArray(minOf(buf.capacity(), 4096))
		java.util.Arrays.fill(bloco, valor)
		while(buf.remaining() > 0)
			buf.put(bloco, 0, minOf(bloco.size, buf.remaining()))
	}

	/** Abre o decodificador contra [destino] e começa a reproduzir o laço. */
	fun iniciar(destino: Surface): Boolean
	{
		if(quadros.isEmpty())
		{
			ultimoErro = "Nothing prepared to play back."
			return false
		}

		val formato = MediaFormat.createVideoFormat(MIME, largura, altura).apply {
			// Nem todo codificador entrega a configuração num buffer à parte;
			// alguns a deixam em banda, no primeiro quadro-chave. Nesse caso
			// seguir sem csd-0 é o certo -- exigi-la seria falhar por um detalhe
			// de implementação do codificador, e não por defeito nenhum.
			val csd = configuracao
			if(csd != null)
				setByteBuffer("csd-0", ByteBuffer.wrap(csd))
			else
				Log.w(TAG, "No csd-0: relying on in-band SPS/PPS")
			// O mesmo pedido do caminho real: sem isto o decodificador acumula
			// quadros para render melhor, que é exatamente o oposto do que se
			// quer aqui.
			setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
		}

		var criado: MediaCodec? = null
		val codec = try
		{
			MediaCodec.createDecoderByType(MIME).also {
				criado = it
				it.configure(formato, destino, null, 0)
				it.start()
			}
		}
		catch(e: Exception)
		{
			// Criado e recusado no configure/start ainda segura um decodificador
			// de hardware, e o Quest tem poucos.
			criado?.release()
			ultimoErro = "Could not open the decoder: " +
					"${e::class.java.simpleName}: ${e.message}"
			Log.e(TAG, ultimoErro, e)
			return false
		}

		decodificador = codec
		rodando = true

		coletor = thread(name = "ensaio-saida") {
			val info = MediaCodec.BufferInfo()
			while(rodando)
			{
				try
				{
					val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
					if(o >= 0)
					{
						val entregar = entregando && info.size > 0
						codec.releaseOutputBuffer(o, entregar)
						medidor.saida(info.presentationTimeUs, entregar)
					}
				}
				catch(e: IllegalStateException)
				{
					// O codec foi parado debaixo desta thread. Não é defeito:
					// é o encerramento chegando primeiro.
					break
				}
			}
		}

		alimentador = thread(name = "ensaio-entrada") {
			val inicio = System.nanoTime()
			var n = 0L
			val periodo = 1_000_000_000L / fps
			while(rodando)
			{
				// Cadência de relógio, e não de sono acumulado: o alvo é
				// calculado a partir do início, então um atraso numa entrega não
				// empurra todas as seguintes. É essa diferença que faz da fonte
				// uma régua em vez de mais uma fonte irregular.
				val alvo = inicio + n * periodo
				val faltam = alvo - System.nanoTime()
				if(faltam > 0)
					LockSupport.parkNanos(faltam)

				try
				{
					val i = codec.dequeueInputBuffer(TIMEOUT_US)
					if(i >= 0)
					{
						val au = quadros[(n % quadros.size).toInt()]
						val buf = codec.getInputBuffer(i)
						if(buf != null && au.size <= buf.capacity())
						{
							buf.clear()
							buf.put(au)
							// PTS como contador, um por quadro, igual ao caminho
							// real. Carimbo de tempo de verdade faria o codec
							// pacear por conta própria, e o que se quer medir é
							// justamente o tempo dele.
							val p = pts(n)
							medidor.entrada(p)
							codec.queueInputBuffer(i, 0, au.size, p, 0)
						}
					}
				}
				catch(e: IllegalStateException)
				{
					break
				}
				n++
			}
		}

		Log.i(TAG, "Bench running: ${quadros.size} frames looping at $fps fps")
		return true
	}

	/**
	 * Fecha ou abre a entrega na Surface, sem tocar no decodificador.
	 *
	 * É o mesmo portão do `patches/0017`, aqui em código nosso: a especificação
	 * do swapchain-Surface exige que ninguém escreva nela quando a sessão
	 * OpenXR termina, e derrubar o decodificador para conseguir isso já custou
	 * um SIGSEGV neste projeto.
	 */
	fun pausarEntrega(pausada: Boolean)
	{
		entregando = !pausada
	}

	fun parar()
	{
		if(!rodando)
			return
		rodando = false
		alimentador?.join(500)
		coletor?.join(500)
		alimentador = null
		coletor = null
		decodificador?.let {
			runCatching { it.stop() }
			runCatching { it.release() }
		}
		decodificador = null
		// Fecha a janela na marra: um ensaio de oito segundos não deixaria
		// número nenhum, e ensaio curto é o que se faz quando algo está errado.
		medidor.encerrar()
	}

	companion object
	{
		private const val TAG = "P5MVR"
		// H.264 e não HEVC: o que se mede aqui é o caminho, não o codec, e o
		// H.264 é o que todo aparelho codifica sem discussão.
		private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
		private const val BITRATE = 25_000_000
		private const val TIMEOUT_US = 10_000L
		private const val LARGURA_BARRA = 80
	}
}
