package android.os
class Looper { companion object { fun getMainLooper()=Looper() } }
object SystemClock { fun uptimeMillis()=0L }
class Handler(l:Looper) {
    fun postDelayed(r:Runnable,ms:Long) { pending=r }
    fun removeCallbacks(r:Runnable) { if(pending===r) pending=null }
    companion object { var pending:Runnable?=null }
}
