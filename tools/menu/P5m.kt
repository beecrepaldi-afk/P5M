package io.github.gblandro.p5m
class StickCalibrationActivity:android.app.Activity()
class BancoDeEnsaioActivity:android.app.Activity()
object Trace { fun log(a:android.app.Activity,s:String) {} }
class GamepadMonitor(a:android.app.Activity,private val reset:()->Unit) {
    fun acceptsAxes(id:Int)=id==62
    fun start() {}
    fun stop() { reset() }
}
