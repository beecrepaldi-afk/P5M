// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/** O ID Android pertence a uma conexao, nao ao controle fisico.
 * Compartilhado pelos dois modos; observa mudancas sem varrer a cada eixo.
 */
class GamepadMonitor(context: Context, private val reset: () -> Unit): InputManager.InputDeviceListener
{
	private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
	private var connected = emptySet<Int>()
	var preferredDeviceId: Int? = null
		private set
	private var started = false
	private var hadNamedGamepad = false

	init { refresh(false) }

	fun acceptsAxes(deviceId: Int) = preferredDeviceId?.let { it == deviceId } ?: !hadNamedGamepad

	fun start() {
		if(started) return
		started = true
		manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
		refresh(true)
	}

	fun stop() {
		if(!started) return
		started = false
		manager.unregisterInputDeviceListener(this)
		// Perder o foco pode impedir os ACTION_UP, mesmo sem desligar o HID.
		reset()
	}

	override fun onInputDeviceAdded(deviceId: Int) = refresh(true)
	override fun onInputDeviceChanged(deviceId: Int) = refresh(true)
	override fun onInputDeviceRemoved(deviceId: Int) = refresh(true)

	private fun refresh(notify: Boolean) {
		val devices = manager.inputDeviceIds.map { manager.getInputDevice(it) }.filterNotNull()
			.filter { it.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
				it.supportsSource(InputDevice.SOURCE_JOYSTICK) }
		val ids = devices.map { it.id }.toSet()
		// Os Touch anunciam eixos mesmo parados. Um HID nomeado ganha deles;
		// manter o atual evita trocar de jogador quando outro controle acorda.
		val named = devices.filter { !it.name.startsWith("Device 0x") }.map { it.id }
		val next = preferredDeviceId?.takeIf { it in named } ?: named.firstOrNull()
		val changed = next != preferredDeviceId ||
			(preferredDeviceId == null && connected.any { it !in ids })
		preferredDeviceId = next
		if(next != null) hadNamedGamepad = true
		connected = ids
		if(notify && changed) {
			android.util.Log.i("P5MVR", "Gamepad connection changed: axes id=$next; releasing held input")
			reset()
		}
	}
}
