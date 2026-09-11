// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window

/** Traduz o gamepad em navegacao Android, inclusive nos menus herdados.
 * Nao instala nas activities de jogo: ali os mesmos botoes pertencem ao PS5.
 */
class MenuController private constructor(private val activity: Activity,
		private val original: Window.Callback): Window.Callback by original
{
	private val root = activity.window.decorView
	private val handler = Handler(Looper.getMainLooper())
	private var direction = 0
	private var announced = false
	private var backDown = false
	private var pressedItem: View? = null
	private val gamepads = GamepadMonitor(activity) { cancelInput() }
	private var marked: View? = null
	private var foreground: Drawable? = null
	private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, next ->
		mark(next)
	}
	private val repeatMove = object: Runnable {
		override fun run() {
			if(direction == 0 || !activity.hasWindowFocus()) { direction = 0; return }
			move(direction)
			handler.postDelayed(this, 140)
		}
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		if(!hasFocus) {
			cancelInput()
		}
		original.onWindowFocusChanged(hasFocus)
	}

	private fun prepare(view: View) {
		// Os cards herdados sao clicaveis pelo ponteiro, mas nem todos entram
		// na busca de foco. Nao cria paradas nos rotulos sem acao.
		if(view.isClickable && view.isEnabled) view.isFocusable = true
		if(view is ViewGroup) for(i in 0 until view.childCount) prepare(view.getChildAt(i))
	}

	private fun ensureFocus() {
		if(!announced) {
			announced = true
			Trace.log(activity, "Menu control: ${activity.javaClass.simpleName}, gamepad navigation active")
		}
		prepare(root)
		val focused = root.findFocus()
		if(focused == null || !focused.isShown || !focused.isEnabled ||
			(!focused.isClickable && focused is ViewGroup && hasAction(focused))) {
			val targets = ArrayList<View>()
			root.addFocusables(targets, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
			targets.firstOrNull { it.isShown && it.isEnabled && it.isClickable }
				?.requestFocusFromTouch()
		}
		mark(root.findFocus())
	}

	private fun hasAction(group: ViewGroup): Boolean = (0 until group.childCount).any {
		val child = group.getChildAt(it)
		child.isShown && child.isEnabled &&
			(child.isClickable || (child is ViewGroup && hasAction(child)))
	}

	private fun mark(next: View?) {
		if(marked === next) return
		marked?.foreground = foreground
		marked = next
		foreground = next?.foreground
		if(next != null) {
			val density = activity.resources.displayMetrics.density
			val outline = GradientDrawable().apply {
				setColor(Color.TRANSPARENT)
				cornerRadius = 12 * density
				setStroke((3 * density).toInt(), Color.rgb(121, 169, 255))
			}
			next.foreground = foreground?.let {
				android.graphics.drawable.LayerDrawable(arrayOf(it, outline))
			} ?: outline
			next.requestRectangleOnScreen(Rect(0, 0, next.width, next.height), false)
		}
	}

	private fun move(key: Int) {
		ensureFocus()
		val way = when(key) {
			KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
			KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
			else -> return
		}
		// O evento sintetico entregue ao callback nao percorre o ViewRoot que
		// normalmente executa a busca direcional. No Quest o foco ficava no
		// primeiro item (Play). Faz a busca e a saida do modo de toque aqui.
		val next = root.findFocus()?.focusSearch(way)
		if(next != null && next.isShown && next.isEnabled) next.requestFocusFromTouch()
	}

	private fun cancelInput() {
		pressedItem?.isPressed = false
		pressedItem = null
		backDown = false
		direction = 0
		handler.removeCallbacks(repeatMove)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		val sources = event.device?.sources ?: event.source
		val gamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
		if(!gamepad) return original.dispatchKeyEvent(event)
		ensureFocus()
		if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
			// BACK sintetico ainda podia ser consumido pelo decor/AppCompat.
			// Usa a acao da activity, uma vez ao soltar, como a seta do sistema.
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDown = true
			if(event.action == KeyEvent.ACTION_UP) {
				val voltar = backDown && !event.isCanceled
				backDown = false
				if(voltar) {
					Trace.log(activity, "Menu control: Circle back from ${activity.javaClass.simpleName}")
					activity.onBackPressed()
				}
			}
			return true
		}
		when(event.keyCode) {
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
				if(event.action == KeyEvent.ACTION_DOWN) move(event.keyCode)
				return true
			}
			KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
				// Confirma ao soltar o mesmo item. Segurar Cross ou mudar de
				// tela/foco com ele pressionado nao dispara outro clique.
				if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
					pressedItem = root.findFocus()?.takeIf { it.isClickable && it.isEnabled }
					pressedItem?.isPressed = true
				}
				if(event.action == KeyEvent.ACTION_UP) {
					val item = pressedItem
					pressedItem = null
					item?.isPressed = false
					if(!event.isCanceled && item != null && item === root.findFocus() &&
						item.isShown && item.isEnabled) item.performClick()
				}
				return true
			}
			else -> return original.dispatchKeyEvent(event)
		}
	}

	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
		// Calibracao precisa receber o analogico cru, inclusive enquanto mede.
		if(activity is StickCalibrationActivity ||
			!event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
			event.action != MotionEvent.ACTION_MOVE)
			return original.dispatchGenericMotionEvent(event)
		// Os Touch tambem publicam joystick, inclusive zeros. Eles nao podem
		// interromper a repeticao do DualSense nem escolher a direcao por ele.
		if(!gamepads.acceptsAxes(event.deviceId)) return true
		val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
		val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
		val x = if(kotlin.math.abs(hatX) > 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
		val y = if(kotlin.math.abs(hatY) > 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
		val next = when {
			kotlin.math.abs(y) >= kotlin.math.abs(x) && y < -0.6f -> KeyEvent.KEYCODE_DPAD_UP
			kotlin.math.abs(y) >= kotlin.math.abs(x) && y > 0.6f -> KeyEvent.KEYCODE_DPAD_DOWN
			x < -0.6f -> KeyEvent.KEYCODE_DPAD_LEFT
			x > 0.6f -> KeyEvent.KEYCODE_DPAD_RIGHT
			else -> 0
		}
		if(next != direction) {
			handler.removeCallbacks(repeatMove)
			direction = next
			if(next != 0) {
				move(next)
				handler.postDelayed(repeatMove, 380)
			}
		}
		return true
	}

	private fun close() {
		gamepads.stop()
		cancelInput()
		root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
		mark(null)
		if(activity.window.callback === this) activity.window.callback = original
	}

	companion object {
		fun attach(activity: Activity) {
			if(activity is com.metallic.chiaki.stream.StreamActivity ||
				activity is com.metallic.chiaki.stream.VrStreamActivity ||
				activity is BancoDeEnsaioActivity) return
			val callback = activity.window.callback ?: return
			if(callback is MenuController) return
			val controller = MenuController(activity, callback)
			activity.window.callback = controller
			controller.gamepads.start()
			controller.root.viewTreeObserver.addOnGlobalFocusChangeListener(controller.focusListener)
		}
		fun detach(activity: Activity) {
			(activity.window.callback as? MenuController)?.close()
		}
	}
}
