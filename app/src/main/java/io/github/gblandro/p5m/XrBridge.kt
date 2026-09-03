// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface

/**
 * Ponte fina para a sessao OpenXR nativa.
 *
 * O ponto que importa: [createVideoSurface] devolve um [Surface] que pertence ao
 * compositor do Horizon OS. Entregue esse Surface ao chiaki e o MediaCodec passa
 * a decodificar direto na camada de composicao -- sem textura intermediaria,
 * sem passo de GPU do app, sem copia.
 */
class XrBridge(private val activity: Activity)
{
	private var nativePtr: Long = 0L
	private var videoSurface: Surface? = null
	private var surfaceTexture: SurfaceTexture? = null

	val isValid get() = nativePtr != 0L

	val isPassthroughSupported: Boolean
		get() = nativePtr != 0L && nativeIsPassthroughSupported(nativePtr)

	/** Cria instancia, sistema e sessao OpenXR. Retorna false se o runtime nao atender. */
	fun create(): Boolean
	{
		if(nativePtr != 0L)
			return true
		nativePtr = nativeCreate(activity)
		return nativePtr != 0L
	}

	fun createVideoSurface(width: Int, height: Int): Surface?
	{
		if(nativePtr == 0L)
			return null
		videoSurface?.let { return it }
		val surface = nativeCreateVideoSurface(nativePtr, width, height)
		videoSurface = surface
		return surface
	}

	/** Motivo da última falha vindo do lado nativo, ou string vazia. */
	val lastError: String
		get() = if(nativePtr != 0L) nativeLastError(nativePtr) else "session not created"

	fun start()
	{
		if(nativePtr != 0L)
			nativeStart(nativePtr)
	}

	fun stop()
	{
		if(nativePtr != 0L)
			nativeStop(nativePtr)
	}

	/**
	 * @param radius distancia da tela em metros
	 * @param centralAngle arco horizontal em radianos
	 * @param yawOffset deslocamento horizontal em radianos
	 * @param heightOffset altura em metros relativa ao ponto de recentragem
	 */
	fun setScreenParams(radius: Float, centralAngle: Float, yawOffset: Float,
			heightOffset: Float, curvature: Float)
	{
		if(nativePtr != 0L)
			nativeSetScreenParams(nativePtr, radius, centralAngle, yawOffset, heightOffset,
					curvature)
	}

	/**
	 * Tratamento de imagem da camada de vídeo.
	 *
	 * @param sharpness nitidez em quatro degraus: 0 nenhuma, 1 leve, 2 média,
	 *        3 forte. No caminho direto o degrau vira combinação de bits do
	 *        compositor; ver `Sharpness`, no lado nativo.
	 * @param sharpenAmount o mesmo degrau como intensidade, para o caminho com
	 *        shader. Viaja junto, e não é recalculado lá, para que a tabela de
	 *        `StreamQualityPrefs` seja a única.
	 * @param passthroughOpacity abaixo de 1.0 escurece o passthrough
	 */
	fun setQuality(sharpness: Int, sharpenAmount: Float, passthroughOpacity: Float,
			cinemaBrightness: Float = 0f, cinemaContrast: Float = 1f,
			cinemaSaturation: Float = 1f, videoBrightness: Float = 1f,
			frameExtrapolation: Boolean = false)
	{
		if(nativePtr != 0L)
			nativeSetQuality(nativePtr, sharpness, sharpenAmount, passthroughOpacity,
					cinemaBrightness, cinemaContrast, cinemaSaturation, videoBrightness,
					frameExtrapolation)
	}

	/**
	 * Casa a taxa do painel com o framerate da fonte e devolve a taxa aplicada,
	 * ou 0 se o runtime não expuser a extensão.
	 */
	fun selectDisplayRefreshRate(sourceFps: Int): Float =
		if(nativePtr != 0L) nativeSelectDisplayRefreshRate(nativePtr, sourceFps) else 0f

	/** @param shape 0 = cilindro (tela curva), 1 = quad (tela plana). */
	fun setLayerShape(shape: Int)
	{
		if(nativePtr != 0L)
			nativeSetLayerShape(nativePtr, shape)
	}

	/**
	 * Libera (ou segura) a submissão da camada de vídeo ao compositor.
	 *
	 * Só deve ser ligada depois que o MediaCodec já entregou frame na Surface:
	 * antes disso a swapchain não tem conteúdo nenhum para o compositor
	 * resolver.
	 */
	fun setVideoLayerEnabled(enabled: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetVideoLayerEnabled(nativePtr, enabled)
	}

	/**
	 * Escolhe o caminho do vídeo e conta ao shader se a fonte é PQ.
	 *
	 * Tem de vir antes de [createVideoSurface]: os dois caminhos criam
	 * swapchains de tipos diferentes.
	 */
	fun setRenderPath(path: Int, sourceIsPq: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetRenderPath(nativePtr, path, sourceIsPq)
	}

	/**
	 * Cria a Surface do caminho com shader.
	 *
	 * A SurfaceTexture nasce desligada de qualquer contexto GL: quem a liga é a
	 * thread do frame loop, dona do contexto onde a textura externa vive.
	 * Devolve null se o lado nativo recusar.
	 */
	fun createToneMappedSurface(): Surface?
	{
		if(nativePtr == 0L)
			return null
		// SurfaceTexture(singleBufferMode) ja nasce sem contexto GL: quem a liga e
		// a thread do frame loop, dona do contexto onde a textura externa vive.
		// Chamar detachFromGLContext aqui lancava RuntimeException -- desanexar o
		// que nunca esteve anexado.
		val texture = SurfaceTexture(false)
		if(!nativeAttachSurfaceTexture(nativePtr, texture))
		{
			texture.release()
			return null
		}
		surfaceTexture = texture
		val surface = Surface(texture)
		videoSurface = surface
		return surface
	}

	/**
	 * Força dos alto-falantes virtuais, de 0 (passagem exata) a 1.
	 *
	 * Não recebe o ponteiro da sessão porque o espacializador é um só no
	 * processo: quem o chama é a thread de áudio do chiaki, que não sabe nada
	 * da sessão OpenXR. Ver `Spatializer`, no lado nativo.
	 */
	fun setSpatialAudio(strength: Float) = nativeSetSpatialAudio(strength)

	/**
	 * Contadores do compositor e do térmico.
	 *
	 * Vem como array de floats em pares "tem valor / valor", porque um contador
	 * que este runtime não oferece não é zero: zero quadro descartado e não
	 * saber quantos foram são coisas diferentes, e mostrar zero nos dois casos
	 * mentiria na metade deles.
	 *
	 * Layout: [temDescarte, descartes, temGpu, gpu%, temGpuApp, msApp,
	 * temGpuComp, msComp, temTermico, folga, inclinação, temLatência, ms,
	 * temCpu, cpu%].
	 */
	fun readPerformance(): FloatArray? =
		if(nativePtr != 0L) nativeReadPerformance(nativePtr) else null

	/**
	 * Quantos pixels o compositor gostaria de ter na camada de vídeo, no
	 * tamanho e na distância em que ela está agora. `null` se não houver valor.
	 *
	 * Comparado com o que a fonte entrega, diz se a tela desperdiça detalhe
	 * (recomendação menor que a fonte) ou pede mais do que existe (maior).
	 */
	fun recommendedResolution(): Pair<Int, Int>?
	{
		if(nativePtr == 0L)
			return null
		val packed = nativeRecommendedResolution(nativePtr)
		val width = (packed ushr 32).toInt()
		val height = (packed and 0xffffffffL).toInt()
		return if(width > 0 && height > 0) Pair(width, height) else null
	}

	/** Declara o gamut do conteúdo: BT.2020 com 10 bits, Rec.709 com 8. */
	fun setWideColor(wide: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetWideColor(nativePtr, wide)
	}

	/** Corrige o espelhamento vertical da imagem, no compositor. */
	fun setVerticalFlip(enabled: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetVerticalFlip(nativePtr, enabled)
	}

	/**
	 * Diz se a imagem carrega os dois olhos: 0 mono, 1 lado a lado, 2 uma sobre
	 * a outra.
	 *
	 * Vale para as duas origens de estereo, e e a mesma chave de proposito: um
	 * video que ja veio lado a lado do console, e a saida do nosso proprio
	 * sintetizador, que escreve os dois olhos na mesma textura. Do lado da
	 * submissao os dois sao a mesma coisa.
	 */
	fun setStereoMode(mode: Int)
	{
		if(nativePtr != 0L)
			nativeSetStereoMode(nativePtr, mode)
	}

	/**
	 * Forca e convergencia do olho sintetizado.
	 *
	 * Pode mudar em jogo: nao mexe no tamanho do alvo nem no numero de camadas,
	 * so em dois uniformes do shader.
	 */
	fun setStereoTuning(strength: Float, convergence: Float)
	{
		if(nativePtr != 0L)
			nativeSetStereoTuning(nativePtr, strength, convergence)
	}

	/** Sobe o painel de ajuda ja desenhado. O bitmap precisa ser ARGB_8888. */
	fun setHudBitmap(bitmap: Bitmap)
	{
		if(nativePtr != 0L)
			nativeSetHudBitmap(nativePtr, bitmap)
	}

	fun setHudVisible(visible: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetHudVisible(nativePtr, visible)
	}

	fun setPassthrough(enabled: Boolean)
	{
		if(nativePtr != 0L)
			nativeSetPassthrough(nativePtr, enabled)
	}

	/** Traz a tela para a frente da direcao em que a cabeca olha agora. */
	fun recenter()
	{
		if(nativePtr != 0L)
			nativeRecenter(nativePtr)
	}

	fun destroy()
	{
		if(nativePtr == 0L)
			return
		nativeDestroy(nativePtr)
		nativePtr = 0L
		videoSurface = null
		// Depois do destroy nativo: o lado C++ solta a ASurfaceTexture, e
		// liberar antes deixaria ele com referencia para objeto morto.
		surfaceTexture?.release()
		surfaceTexture = null
	}

	private external fun nativeCreate(activity: Activity): Long
	private external fun nativeCreateVideoSurface(ptr: Long, width: Int, height: Int): Surface?
	private external fun nativeLastError(ptr: Long): String
	private external fun nativeStart(ptr: Long)
	private external fun nativeStop(ptr: Long)
	private external fun nativeSetScreenParams(ptr: Long, radius: Float, centralAngle: Float, yawOffset: Float, heightOffset: Float, curvature: Float)
	private external fun nativeSetQuality(ptr: Long, sharpness: Int, sharpenAmount: Float,
			passthroughOpacity: Float, cinemaBrightness: Float, cinemaContrast: Float,
			cinemaSaturation: Float, videoBrightness: Float, frameExtrapolation: Boolean)
	private external fun nativeSelectDisplayRefreshRate(ptr: Long, sourceFps: Int): Float
	private external fun nativeSetLayerShape(ptr: Long, shape: Int)
	private external fun nativeSetVideoLayerEnabled(ptr: Long, enabled: Boolean)
	private external fun nativeSetVerticalFlip(ptr: Long, enabled: Boolean)
	private external fun nativeSetStereoMode(ptr: Long, mode: Int)
	private external fun nativeSetStereoTuning(ptr: Long, strength: Float, convergence: Float)
	private external fun nativeSetWideColor(ptr: Long, wide: Boolean)
	private external fun nativeSetSpatialAudio(strength: Float)
	private external fun nativeReadPerformance(ptr: Long): FloatArray?
	private external fun nativeRecommendedResolution(ptr: Long): Long
	private external fun nativeSetRenderPath(ptr: Long, path: Int, sourceIsPq: Boolean)
	private external fun nativeAttachSurfaceTexture(ptr: Long, texture: SurfaceTexture): Boolean
	private external fun nativeSetHudBitmap(ptr: Long, bitmap: Bitmap)
	private external fun nativeSetHudVisible(ptr: Long, visible: Boolean)
	private external fun nativeSetPassthrough(ptr: Long, enabled: Boolean)
	private external fun nativeIsPassthroughSupported(ptr: Long): Boolean
	private external fun nativeRecenter(ptr: Long)
	private external fun nativeDestroy(ptr: Long)

	companion object
	{
		init
		{
			System.loadLibrary("p5m-vr")
		}
	}
}
