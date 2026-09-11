package android.content
import android.hardware.input.InputManager
import android.view.WindowManager
class Context(val inputManager: InputManager) {
    fun getSystemService(name: String): Any = if(name == INPUT_SERVICE) inputManager else WindowManager()
    companion object { const val INPUT_SERVICE = "input"; const val WINDOW_SERVICE = "window"; const val SENSOR_SERVICE = "sensor" }
}
