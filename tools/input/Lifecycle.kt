package androidx.lifecycle
interface LifecycleObserver
interface LifecycleOwner { val lifecycle: Lifecycle }
@Retention(AnnotationRetention.RUNTIME) annotation class OnLifecycleEvent(val value: Lifecycle.Event)
class Lifecycle {
    enum class Event { ON_RESUME, ON_PAUSE }
    private val observers = mutableListOf<LifecycleObserver>()
    fun addObserver(observer: LifecycleObserver) { observers.add(observer) }
    fun send(event: Event) {
        observers.forEach { observer -> observer.javaClass.declaredMethods.filter {
            it.getAnnotation(OnLifecycleEvent::class.java)?.value == event
        }.forEach { it.isAccessible = true; it.invoke(observer) } }
    }
}
