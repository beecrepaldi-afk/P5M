package android.hardware.input
import android.os.Handler
import android.view.InputDevice
class InputManager {
    val devices = linkedMapOf<Int, InputDevice>()
    val inputDeviceIds get() = devices.keys.toIntArray()
    val listeners = mutableSetOf<InputDeviceListener>()
    fun getInputDevice(id: Int) = devices[id]
    interface InputDeviceListener { fun onInputDeviceAdded(deviceId: Int); fun onInputDeviceChanged(deviceId: Int); fun onInputDeviceRemoved(deviceId: Int) }
    fun registerInputDeviceListener(listener: InputDeviceListener, handler: Handler) { listeners.add(listener) }
    fun unregisterInputDeviceListener(listener: InputDeviceListener) { listeners.remove(listener) }
    fun add(device: InputDevice) { devices[device.id] = device; listeners.toList().forEach { it.onInputDeviceAdded(device.id) } }
    fun remove(id: Int) { devices.remove(id); listeners.toList().forEach { it.onInputDeviceRemoved(id) } }
}
