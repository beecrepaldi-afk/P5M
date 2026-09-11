package android.view
import android.graphics.Rect
import android.graphics.drawable.Drawable
open class View {
    var isClickable=false; var isEnabled=true; var isFocusable=false
    var isShown=true; var isPressed=false
    var foreground:Drawable?=null
    val width=100; val height=50
    var root:View?=null
    var focused:View?=null
    var clicks=0
    val neighbors=mutableMapOf<Int,View>()
    val viewTreeObserver=ViewTreeObserver()
    fun findFocus():View? = (root?:this).focused
    fun addFocusables(out:ArrayList<View>,d:Int,m:Int) {
        if(isFocusable && isShown && isEnabled) out.add(this)
        if(this is ViewGroup) children.forEach { it.addFocusables(out,d,m) }
    }
    fun requestFocusFromTouch():Boolean { (root?:this).focused=this; return true }
    fun focusSearch(d:Int)=neighbors[d]
    fun performClick():Boolean { clicks++; return true }
    fun requestRectangleOnScreen(r:Rect,i:Boolean)=true
    companion object {
        const val FOCUS_FORWARD=2; const val FOCUSABLES_ALL=0
        const val FOCUS_UP=33; const val FOCUS_DOWN=130; const val FOCUS_LEFT=17; const val FOCUS_RIGHT=66
    }
}
class ViewGroup:View() {
    val children=mutableListOf<View>()
    val childCount get()=children.size
    fun getChildAt(i:Int)=children[i]
    fun add(v:View) { children.add(v); v.root=this }
}
class ViewTreeObserver {
    fun interface OnGlobalFocusChangeListener { fun change(a:View?,b:View?) }
    fun addOnGlobalFocusChangeListener(l:OnGlobalFocusChangeListener) {}
    fun removeOnGlobalFocusChangeListener(l:OnGlobalFocusChangeListener) {}
}
class Window {
    val decorView=ViewGroup()
    var callback:Callback?=null
    interface Callback {
        fun dispatchKeyEvent(e:KeyEvent):Boolean
        fun dispatchGenericMotionEvent(e:MotionEvent):Boolean
        fun onWindowFocusChanged(f:Boolean)
    }
}
class InputDevice(val sources:Int) {
    companion object { const val SOURCE_GAMEPAD=0x401; const val SOURCE_JOYSTICK=0x1000010 }
}
class KeyEvent(val downTime:Long,val eventTime:Long,val action:Int,val keyCode:Int,
    val repeatCount:Int,val metaState:Int=0,val deviceId:Int=62,val scanCode:Int=0,
    val flags:Int=0,val source:Int=InputDevice.SOURCE_GAMEPAD) {
    val device=InputDevice(source)
    val isCanceled get()=flags==32
    companion object {
        const val ACTION_DOWN=0; const val ACTION_UP=1
        const val KEYCODE_BUTTON_A=96; const val KEYCODE_BUTTON_B=97
        const val KEYCODE_DPAD_UP=19; const val KEYCODE_DPAD_DOWN=20
        const val KEYCODE_DPAD_LEFT=21; const val KEYCODE_DPAD_RIGHT=22; const val KEYCODE_DPAD_CENTER=23
    }
}
class MotionEvent(val deviceId:Int,val x:Float,val y:Float) {
    val action=ACTION_MOVE
    fun isFromSource(s:Int)=s==InputDevice.SOURCE_JOYSTICK
    fun getAxisValue(a:Int)=when(a) { AXIS_X->x; AXIS_Y->y; else->0f }
    companion object { const val ACTION_MOVE=2; const val AXIS_X=0; const val AXIS_Y=1; const val AXIS_HAT_X=15; const val AXIS_HAT_Y=16 }
}
