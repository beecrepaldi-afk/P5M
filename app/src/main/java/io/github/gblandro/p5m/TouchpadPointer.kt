// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent

/**
 * O touchpad do controle, que o Android entrega como ponteiro.
 *
 * O driver do kernel expõe o touchpad do DualSense como um dispositivo à parte,
 * do lado do gamepad, e o clique físico sai de lá como botão primário de mouse.
 * Não existe tecla de gamepad para ele: por isso o mapeamento antigo, que
 * escutava `KEYCODE_BUTTON_*`, nunca pegou nada.
 *
 * A parte que dói é o resto do que esse dispositivo manda. Deslizar o dedo vira
 * movimento de ponteiro, e no modo janela isso acorda o cursor do sistema por
 * cima do jogo e chega aos controles de toque do chiaki-ng como se fosse dedo
 * na tela. Foi assim que uma passada de dedo deixou os botões todos errados: o
 * estado de toque do controle passou a ser escrito por quem não devia.
 *
 * Então esta classe engole **tudo** que vem do ponteiro do controle, e não só o
 * clique. O que sobra é o clique, traduzido para o botão do touchpad do
 * PlayStation.
 *
 * O que ela não engole é o ponteiro do headset. O raio da mão também chega como
 * mouse, e engoli-lo tiraria o único jeito de tocar na interface do modo
 * janela. A separação é pelo dispositivo: só entra aqui o ponteiro de um
 * aparelho que também se declara gamepad.
 */
class TouchpadPointer(private val send: (Boolean) -> Unit)
{
	private var down = false
	private var anotado = false

	fun handle(event: MotionEvent): Boolean
	{
		if(!doControle(event))
			return false
		anotar(event)

		when(event.actionMasked)
		{
			MotionEvent.ACTION_BUTTON_PRESS -> press(true)
			MotionEvent.ACTION_BUTTON_RELEASE -> press(false)
			// Sem ACTION_BUTTON_*, o clique chega como toque com botão apertado.
			MotionEvent.ACTION_DOWN -> if(event.buttonState != 0) press(true)
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if(down) press(false)
		}
		return true
	}

	/** Solta o botão ao sair da tela, senão ele fica preso do lado do console. */
	fun release()
	{
		if(down)
			press(false)
	}

	private fun press(pressed: Boolean)
	{
		if(pressed == down)
			return
		down = pressed
		send(pressed)
		Log.i(TAG, "Touchpad click: ${if(pressed) "down" else "up"}")
	}

	private fun doControle(event: MotionEvent): Boolean
	{
		val fonte = event.source
		val ponteiro = fonte and InputDevice.SOURCE_CLASS_POINTER == InputDevice.SOURCE_CLASS_POINTER ||
				fonte and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD
		if(!ponteiro)
			return false
		val device = event.device ?: return false
		return device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
				device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
	}

	/** Uma linha na primeira vez, para o diário dizer por onde o clique entra. */
	private fun anotar(event: MotionEvent)
	{
		if(anotado)
			return
		anotado = true
		Log.i(TAG, "Controller pointer from '${event.device?.name}' " +
				"source=0x${event.source.toString(16)} " +
				"device sources=0x${event.device?.sources?.toString(16)}")
	}

	companion object
	{
		private const val TAG = "P5MVR"
	}
}
