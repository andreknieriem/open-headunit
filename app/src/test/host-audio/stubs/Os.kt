package android.os
object Build { object VERSION { @JvmField var SDK_INT=33 }; object VERSION_CODES { const val JELLY_BEAN_MR1=17;const val JELLY_BEAN_MR2=18;const val LOLLIPOP=21;const val M=23;const val N=24;const val O=26 } }
object SystemClock { @Volatile var offsetMs=0L; @Volatile var hook:(()->Unit)?=null;fun elapsedRealtime():Long {hook?.invoke();return System.nanoTime()/1000000+offsetMs};fun elapsedRealtimeNanos()=System.nanoTime()+offsetMs*1000000 }
object Process { const val THREAD_PRIORITY_URGENT_AUDIO=-19;const val THREAD_PRIORITY_AUDIO=-16;const val THREAD_PRIORITY_BACKGROUND=10; @Volatile var hook: ((String)->Unit)?=null;fun setThreadPriority(x:Int){hook?.invoke(Thread.currentThread().name)} }
class Looper { companion object { private val main = Looper(); fun getMainLooper() = main } }
class Handler(val looper: Looper) {
    companion object {
        val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val delayed = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var beforePost: (() -> Unit)? = null
        fun runAll() { while (true) { (tasks.poll() ?: break).run() } }
        fun reset() { tasks.clear(); delayed.clear(); beforePost = null }
    }
    fun post(task: Runnable): Boolean { beforePost?.invoke(); tasks.offer(task); return true }
    fun postDelayed(task: Runnable, delay: Long): Boolean { delayed.offer(task); return true }
}
open class HandlerThread(name:String):Thread(name) { val looper=Looper();open fun onLooperPrepared(){};override fun run(){onLooperPrepared()};fun quitSafely()=true;fun quit()=true }
