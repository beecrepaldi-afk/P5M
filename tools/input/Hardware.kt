package android.hardware
class Sensor(val type: Int) { companion object { const val TYPE_ACCELEROMETER=1; const val TYPE_GYROSCOPE=2; const val TYPE_ROTATION_VECTOR=3 } }
class SensorEvent(val sensor: Sensor, val values: FloatArray)
interface SensorEventListener { fun onSensorChanged(event: SensorEvent); fun onAccuracyChanged(sensor: Sensor, accuracy: Int) }
class SensorManager {
    fun getDefaultSensor(type: Int): Sensor? = null
    fun registerListener(listener: SensorEventListener, sensor: Sensor, period: Int) {}
    fun unregisterListener(listener: SensorEventListener) {}
    companion object { const val GRAVITY_EARTH = 9.81f; fun getQuaternionFromVector(q: FloatArray, values: FloatArray) {} }
}
