// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * O caminho de vídeo inteiro, sem console, sem rede e sem chiaki.
 *
 * Esta activity existe para responder uma pergunta que nenhuma sessão real
 * responde: **quanto do que se sente jogando é nosso, e quanto é da rede?**
 * Enquanto a fonte for um PS5 do outro lado de um Wi-Fi, todo engasgo medido
 * tem dois pais possíveis e nenhum jeito de separá-los. Aqui a fonte é uma
 * régua — [FonteSintetica] gera 60 quadros por segundo com cadência de relógio,
 * dentro do próprio aparelho. Se o número sair ruim, o defeito é nosso; se sair
 * bom, o caminho está provado e o que sobra é a rede.
 *
 * E há uma segunda razão, que não é de diagnóstico. Este arquivo, o
 * [MedidorDeQuadros], a [FonteSintetica] e a camada nativa que eles acionam
 * **não têm uma única referência ao chiaki**. Juntos são o projeto novo
 * descrito em `docs/O-QUE-E-NOSSO.md`, rodando aqui dentro porque aqui é barato
 * testar. O dia em que forem embora, saem inteiros e sem cortes.
 */
class BancoDeEnsaioActivity: Activity()
{
	private var xr: XrBridge? = null
	// Volátil: quem preenche é a thread de preparo, quem lê é o ciclo de vida na
	// thread principal.
	@Volatile private var fonte: FonteSintetica? = null
	private val handler = Handler(Looper.getMainLooper())

	override fun onCreate(state: Bundle?)
	{
		super.onCreate(state)

		val versao = runCatching {
			packageManager.getPackageInfo(packageName, 0).versionName
		}.getOrNull() ?: "?"
		Trace.beginSession(this, "bench $versao")
		Trace.log(this, "Test bench: synthetic source, no console and no network")

		val prefs = ScreenPrefs(this)

		val bridge = XrBridge(this)
		if(!bridge.create())
		{
			// Nunca encerrar em silêncio: sem PC, uma activity que fecha sozinha
			// é uma janela em branco sem pista nenhuma.
			desistir("The OpenXR session could not be created.\n${bridge.lastError}")
			return
		}
		xr = bridge

		// Caminho direto e SDR: é o caminho que se quer medir. Com shader a
		// medida incluiria uma passada de GPU nossa, que é outra pergunta.
		bridge.setRenderPath(0, false)
		val destino = bridge.createVideoSurface(LARGURA, ALTURA)
		if(destino == null)
		{
			desistir("The video swapchain could not be created.\n${bridge.lastError}")
			return
		}

		bridge.setLayerShape(prefs.layerShape)
		bridge.setVerticalFlip(prefs.verticalFlip)
		bridge.setWideColor(false)
		bridge.setScreenParams(prefs.radius, prefs.centralAngle, 0f,
				prefs.heightOffset, prefs.curvature)
		val cinema = ScreenPrefs.CINEMA_GRADE[prefs.cinema]
		bridge.setQuality(0, 0f, 1f, cinema[0], cinema[1], cinema[2], 1f, false)
		bridge.setPassthrough(prefs.passthroughEnabled && bridge.isPassthroughSupported)
		bridge.selectDisplayRefreshRate(FPS)

		bridge.start()
		bridge.recenter()

		// A preparação codifica alguns segundos de vídeo e leva alguns segundos.
		// Na thread principal isso seria um ANR; aqui a sessão OpenXR já está
		// rodando, então o usuário vê o passthrough enquanto espera.
		thread(name = "ensaio-preparo") {
			val f = FonteSintetica(LARGURA, ALTURA, FPS)
			if(!f.preparar())
			{
				handler.post { desistir("Could not generate the source.\n${f.ultimoErro}") }
				return@thread
			}
			Trace.log(this, "Bench loop: ${f.quadrosPreparados} frames")
			if(!f.iniciar(destino))
			{
				handler.post { desistir("Could not play back the source.\n${f.ultimoErro}") }
				return@thread
			}
			fonte = f
			// A camada só pode ser submetida depois de a Surface ter conteúdo:
			// antes disso o compositor recebe uma textura vazia. Mesma razão e
			// mesma espera do caminho real.
			handler.postDelayed({
				xr?.setVideoLayerEnabled(true)
				Trace.log(this, "Video layer released; measuring")
			}, ATRASO_CAMADA_MS)
		}
	}

	private fun desistir(motivo: String)
	{
		Log.e(TAG, motivo)
		Trace.log(this, "Bench aborted: $motivo")
		finish()
	}

	override fun onResume()
	{
		super.onResume()
		Trace.log(this, "Bench: onResume")
		xr?.start()
		fonte?.pausarEntrega(false)
	}

	override fun onPause()
	{
		super.onPause()
		Trace.log(this, "Bench: onPause")
		// Fechar a entrega antes de parar a sessão, pela mesma razão do caminho
		// real: ninguém pode estar escrevendo na Surface do swapchain quando o
		// xrEndSession acontecer.
		fonte?.pausarEntrega(true)
		xr?.stop()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		Trace.log(this, "Bench: onDestroy")
		// Nesta ordem: a fonte para e fecha a janela de medição enquanto a
		// sessão nativa ainda existe. Destruir a ponte primeiro puxaria a
		// Surface debaixo do decodificador.
		fonte?.parar()
		fonte = null
		xr?.destroy()
		xr = null
		Trace.captureNativeLines(this)
	}

	companion object
	{
		private const val TAG = "P5MVR"
		private const val LARGURA = 1920
		private const val ALTURA = 1080
		private const val FPS = 60
		private const val ATRASO_CAMADA_MS = 500L
	}
}
