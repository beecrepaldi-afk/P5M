package com.metallic.chiaki.lib

class Session(val read: (ByteArray) -> Int)
{
	fun hapticsRead(out: ByteArray): Int = read(out)
}
