// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Superfície de vídeo com uma passada de shader no meio.
 *
 * Existe só para o modo janela. Ali não há camada nossa no compositor — quem
 * compõe é o Horizon OS, e o app é um painel comum —, então nenhum dos bits de
 * `XR_FB_composition_layer_settings` está ao alcance. Se a imagem tem de ser
 * tratada, o tratamento é nosso: o MediaCodec passa a escrever numa
 * SurfaceTexture, e daí a imagem chega à janela pelo mesmo conversor de tons do
 * modo imersivo.
 *
 * Custa uma passada de GPU e uma cópia a mais. Por isso a [WindowVideo] só usa
 * esta view quando há de fato o que fazer: com a nitidez desligada e sem
 * conversão de tons, o caminho continua sendo o SurfaceView direto do chiaki,
 * sem nada no meio.
 */
class SharpVideoView(
	context: Context,
	private val pq: Boolean,
	private val sharpen: Float,
	private val onSurfaceReady: (Surface) -> Unit
): GLSurfaceView(context)
{
	private val filter = VideoFilter()
	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null
	private var viewportWidth = 0
	private var viewportHeight = 0

	private inner class VideoRenderer: Renderer
	{
		override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
		{
			// Pode ser a segunda vez: o contexto GL se perde quando a janela vai
			// para trás, e ao voltar tudo que vivia nele já não existe. Soltar
			// antes de recriar evita ficar com uma textura órfã por sessão.
			releaseOnGlThread()

			// SurfaceTexture(singleBufferMode) já nasce sem contexto GL. Quem a
			// liga é o filtro, aqui, na thread dona do contexto onde a textura
			// externa vive.
			val texture = SurfaceTexture(false)
			if(!filter.init(texture))
			{
				Log.e(TAG, "Video filter unavailable; the window would have no picture")
				texture.release()
				return
			}
			// Um quadro novo não acorda a view sozinho: o modo é WHEN_DIRTY, e
			// sem este pedido o desenho só aconteceria por acaso, quando outra
			// coisa invalidasse a superfície.
			texture.setOnFrameAvailableListener { requestRender() }

			surfaceTexture = texture
			val created = Surface(texture)
			surface = created

			// De volta à thread principal: quem recebe é a sessão do chiaki, que
			// não é feita para ser tocada daqui.
			post { onSurfaceReady(created) }
		}

		override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
		{
			viewportWidth = width
			viewportHeight = height
		}

		override fun onDrawFrame(gl: GL10?)
		{
			if(surface == null || viewportWidth == 0)
				return
			filter.draw(viewportWidth, viewportHeight, pq, sharpen)
		}
	}

	init
	{
		setEGLContextClientVersion(3)
		setRenderer(VideoRenderer())
		// Só desenha quando há quadro novo. Em RENDERMODE_CONTINUOUSLY a view
		// redesenharia na taxa do painel mesmo sem nada ter chegado do console,
		// gastando GPU para repetir a mesma imagem.
		renderMode = RENDERMODE_WHEN_DIRTY
	}

	/** Solta tudo. Chamada na thread do GL, onde a textura externa vive. */
	private fun releaseOnGlThread()
	{
		filter.destroy()
		surface?.release()
		surface = null
		surfaceTexture?.release()
		surfaceTexture = null
	}

	fun release()
	{
		// Na fila do GL, e não aqui: soltar a textura externa fora da thread
		// dona do contexto é o mesmo erro que já custou uma sessão inteira no
		// caminho imersivo.
		queueEvent { releaseOnGlThread() }
	}

	companion object
	{
		private const val TAG = "P5MVR"
	}
}
