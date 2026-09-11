package android.content

open class Intent
{
	private val extras = mutableMapOf<String, String>()
	var component: ComponentName? = null
	var flags = 0

	fun putExtra(key: String, value: String): Intent { extras[key] = value; return this }
	fun getStringExtra(key: String): String? = extras[key]
	private val ints = mutableMapOf<String, Int>()
	fun putExtra(key: String, value: Int): Intent { ints[key] = value; return this }
	fun getIntExtra(key: String, default: Int): Int = ints[key] ?: default
	fun addFlags(value: Int): Intent { flags = flags or value; return this }

	companion object
	{
		const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
		const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
		const val FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000
		const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
	}
}

class ComponentName(val packageName: String, val className: String)
{
	constructor(context: android.app.Activity, className: String): this("io.github.gblandro.p5m", className)
	val shortClassName: String get() = className.substringAfterLast('.')
}
