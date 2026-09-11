package android.app
open class Activity {
    val window=android.view.Window()
    val resources=Resources()
    var backs=0
    fun hasWindowFocus()=true
    fun onBackPressed() { backs++ }
}
class Resources { val displayMetrics=Metrics() }
class Metrics { val density=1f }
