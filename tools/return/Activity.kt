package android.app

open class Activity
{
	val started = mutableListOf<android.content.Intent>()
	var taskId = 0
	var manager: ActivityManager? = null
	fun startActivity(intent: android.content.Intent) { started.add(intent) }
	@Suppress("UNCHECKED_CAST")
	fun <T> getSystemService(type: Class<T>): T? = manager as T?
}

class ActivityManager(val appTasks: List<AppTask>, private val refuseMove: Boolean = false)
{
	val movedTasks = mutableListOf<Int>()
	fun moveTaskToFront(taskId: Int, flags: Int)
	{
		if(refuseMove)
			throw SecurityException("REORDER_TASKS missing")
		movedTasks.add(taskId)
	}

	open class RecentTaskInfo(val taskId: Int, val baseActivity: android.content.ComponentName?,
		val topActivity: android.content.ComponentName?, val numActivities: Int = 1)

	class AppTask(private val info: RecentTaskInfo, private val refuse: Boolean = false)
	{
		var movedToFront = 0
		val taskInfo: RecentTaskInfo get() = info
		fun moveToFront()
		{
			if(refuse)
				throw IllegalArgumentException("Unable to find task ID ${info.taskId}")
			movedToFront++
		}
	}
}
