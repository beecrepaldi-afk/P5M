package android.graphics.drawable
open class Drawable
class LayerDrawable(items:Array<Drawable>):Drawable()
class GradientDrawable:Drawable() {
    var cornerRadius=0f
    fun setColor(c:Int) {}
    fun setStroke(w:Int,c:Int) {}
}
