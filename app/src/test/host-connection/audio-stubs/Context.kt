package android.content
class Intent {
    var action:String?=null
    constructor(a:String){action=a}
    constructor(c:Context,k:Class<*>){}
    fun setPackage(p:String)=this
}
open class Context {
    companion object { const val AUDIO_SERVICE="audio";const val ACTIVITY_SERVICE="activity" }
    val packageName="review"
    val broadcasts=mutableListOf<String?>()
    var stops=0
    fun sendBroadcast(i:Intent){broadcasts.add(i.action)}
    fun stopService(i:Intent):Boolean{stops++;return true}
    fun getSystemService(n:String):Any=if(n==AUDIO_SERVICE) android.media.AudioManager() else android.app.ActivityManager()
}
