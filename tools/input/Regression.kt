package io.github.gblandro.p5m
import android.content.Context
import android.hardware.input.InputManager
import android.view.*
import androidx.lifecycle.*
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.lib.ControllerState

fun main() {
    val manager = InputManager()
    val owner = object: LifecycleOwner { override val lifecycle = Lifecycle() }
    fun device(id: Int, name: String) = InputDevice(id, name, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
    manager.add(device(8, "Device 0xTouch"))
    manager.add(device(55, "DualSense Wireless Controller"))
    val input = StreamInput(Context(manager), Preferences())
    val sent = mutableListOf<ControllerState>()
    input.controllerStateChangedCallback = { sent.add(it) }
    var gesturesReset = 0
    input.gamepadChangedCallback = { gesturesReset++ }
    input.observe(owner)
    owner.lifecycle.send(Lifecycle.Event.ON_RESUME)
    check(input.preferredGamepadId == 55)
    input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
    input.onGenericMotionEvent(MotionEvent(55, mapOf(MotionEvent.AXIS_X to 0.7f, MotionEvent.AXIS_RTRIGGER to 0.9f)))
    input.touchControllerState = ControllerState(buttons = ControllerState.BUTTON_TOUCHPAD)
    check(sent.last().buttons != 0U && sent.last().leftX != 0.toShort() && sent.last().r2State > 0U)
    manager.remove(8)
    check(gesturesReset == 0 && sent.last().leftX != 0.toShort())
    StickPrefs.offset = 1000
    manager.remove(55)
    check(sent.last().buttons == 0U && sent.last().leftX == 0.toShort() && sent.last().r2State == 0.toUByte())
    check(gesturesReset == 1)
    manager.add(device(9, "Device 0xTouch"))
    input.onGenericMotionEvent(MotionEvent(9, mapOf(MotionEvent.AXIS_X to 0.9f)))
    check(sent.last().leftX == 0.toShort())
    manager.add(device(76, "DualSense Wireless Controller"))
    check(input.preferredGamepadId == 76)
    input.onGenericMotionEvent(MotionEvent(76, mapOf(MotionEvent.AXIS_X to -0.8f)))
    check(sent.last().leftX < 0)
    manager.add(device(9, "Device 0xTouch"))
    input.onGenericMotionEvent(MotionEvent(9, mapOf(MotionEvent.AXIS_X to 0.9f)))
    check(sent.last().leftX < 0)
    input.onGenericMotionEvent(MotionEvent(55, mapOf(MotionEvent.AXIS_X to 0.9f)))
    check(sent.last().leftX < 0)
    println("PASS: reconnection releases held buttons/triggers/touch and accepts the new Android ID; Touch cannot overwrite axes")

    val count = sent.size
    input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2))
    check(sent.size == count + 1 && sent.last().l2State == UByte.MAX_VALUE)
    input.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2))
    check(sent.last().l2State == 0.toUByte())
    println("PASS: key-only triggers publish both press and release without waiting for another input")

    owner.lifecycle.send(Lifecycle.Event.ON_PAUSE)
    check(manager.listeners.isEmpty())
    check(sent.last().leftX == 0.toShort())
    manager.remove(76)
    manager.add(device(99, "DualSense Wireless Controller"))
    owner.lifecycle.send(Lifecycle.Event.ON_RESUME)
    owner.lifecycle.send(Lifecycle.Event.ON_RESUME)
    check(manager.listeners.size == 1 && input.preferredGamepadId == 99)
    input.onGenericMotionEvent(MotionEvent(99, mapOf(MotionEvent.AXIS_HAT_Y to -1f)))
    check(sent.last().buttons and ControllerState.BUTTON_DPAD_UP != 0U)
    manager.add(device(100, "Second gamepad"))
    check(input.preferredGamepadId == 99)
    println("PASS: reconnect while paused is recovered on resume; registration is idempotent and the second gamepad does not steal focus")
}
