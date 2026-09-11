package tests
import android.view.*
import android.os.Handler
import io.github.gblandro.p5m.MenuController
fun main() {
    val a=android.app.Activity()
    val root=a.window.decorView
    // O callback nao faz navegacao direcional: no Android essa etapa e do
    // ViewRoot, pelo qual a chamada sintetica anterior nao passava.
    val original=object:Window.Callback {
        override fun dispatchKeyEvent(e:KeyEvent)=false
        override fun dispatchGenericMotionEvent(e:MotionEvent)=false
        override fun onWindowFocusChanged(f:Boolean) {}
    }
    a.window.callback=original
    val playCategory=View().also { it.isClickable=true; root.add(it) }
    val pictureCategory=View().also { it.isClickable=true; root.add(it) }
    val playButton=View().also { it.isClickable=true; root.add(it) }
    playCategory.neighbors[View.FOCUS_DOWN]=pictureCategory
    pictureCategory.neighbors[View.FOCUS_UP]=playCategory
    playCategory.neighbors[View.FOCUS_RIGHT]=playButton
    MenuController.attach(a)
    val control=a.window.callback!!
    fun key(code:Int,action:Int,repeat:Int=0,flags:Int=0) = control.dispatchKeyEvent(
        KeyEvent(0,0,action,code,repeat,flags=flags))
    fun tap(code:Int) { key(code,0); key(code,1) }
    root.focused=root // Containers podem ter foco sem serem uma acao.
    tap(KeyEvent.KEYCODE_DPAD_DOWN)
    check(root.focused===pictureCategory) { "focus trapped in Play/category container" }
    tap(KeyEvent.KEYCODE_DPAD_UP)
    tap(KeyEvent.KEYCODE_DPAD_RIGHT)
    check(root.focused===playButton)
    key(KeyEvent.KEYCODE_BUTTON_A,0)
    key(KeyEvent.KEYCODE_BUTTON_A,0,repeat=2)
    key(KeyEvent.KEYCODE_BUTTON_A,1)
    check(playButton.clicks==1) { "Cross must click once" }
    println("PASS: leave Play category, enter content and activate once")
    key(KeyEvent.KEYCODE_BUTTON_A,0)
    key(KeyEvent.KEYCODE_BUTTON_A,1,flags=32)
    key(KeyEvent.KEYCODE_BUTTON_A,0)
    control.onWindowFocusChanged(false)
    key(KeyEvent.KEYCODE_BUTTON_A,1)
    check(playButton.clicks==1)
    tap(KeyEvent.KEYCODE_BUTTON_B)
    check(a.backs==1)
    println("PASS: canceled/held confirmation does not leak across window focus")
    playCategory.requestFocusFromTouch()
    control.dispatchGenericMotionEvent(MotionEvent(62,0f,1f))
    check(root.focused===pictureCategory)
    check(Handler.pending!=null)
    control.dispatchGenericMotionEvent(MotionEvent(8,0f,0f))
    check(Handler.pending!=null) { "Touch neutral canceled DualSense repeat" }
    control.dispatchGenericMotionEvent(MotionEvent(62,0f,0f))
    check(Handler.pending==null)
    MenuController.detach(a)
    check(a.window.callback===original)
    println("PASS: Quest axes do not cancel DualSense; detach restores callback")
}
