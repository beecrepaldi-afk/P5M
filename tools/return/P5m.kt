package io.github.gblandro.p5m

class LauncherActivity: android.app.Activity()
class PsnRemoteActivity: android.app.Activity()
object Trace
{
	val lines = mutableListOf<String>()
	fun log(activity: android.app.Activity, text: String) { lines.add(text) }
}
