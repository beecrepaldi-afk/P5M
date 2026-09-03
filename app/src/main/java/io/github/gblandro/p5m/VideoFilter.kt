// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.graphics.SurfaceTexture

/**
 * Ponte para o conversor de tons nativo, usado fora do OpenXR.
 *
 * É o mesmo `ToneMapper` do modo imersivo, e é de propósito: dois shaders que
 * deveriam concordar acabam divergindo, e a nitidez "média" de um modo
 * deixaria de ser a nitidez média do outro.
 *
 * Todos os métodos precisam ser chamados na thread que tem o contexto GL — no
 * modo janela, a thread da [SharpVideoView].
 */
class VideoFilter
{
	private var ptr = 0L

	/** Cria o programa GL e prende a SurfaceTexture à textura externa. */
	fun init(surfaceTexture: SurfaceTexture): Boolean
	{
		if(ptr != 0L)
			return true
		ptr = nativeCreate()
		if(ptr == 0L)
			return false
		if(!nativeInit(ptr, surfaceTexture))
		{
			destroy()
			return false
		}
		return true
	}

	/** Desenha o quadro mais recente no framebuffer padrão. */
	fun draw(width: Int, height: Int, pq: Boolean, sharpen: Float): Boolean =
		ptr != 0L && nativeDraw(ptr, width, height, pq, sharpen)

	fun destroy()
	{
		if(ptr == 0L)
			return
		nativeDestroy(ptr)
		ptr = 0L
	}

	private external fun nativeCreate(): Long
	private external fun nativeInit(ptr: Long, surfaceTexture: SurfaceTexture): Boolean
	private external fun nativeDraw(ptr: Long, width: Int, height: Int, pq: Boolean, sharpen: Float): Boolean
	private external fun nativeDestroy(ptr: Long)

	companion object
	{
		init { System.loadLibrary("p5m-vr") }
	}
}
