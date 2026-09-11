package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper)
