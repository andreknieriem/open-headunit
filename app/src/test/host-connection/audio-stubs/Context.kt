package android.content
class Intent {
    var action:String?=null
    constructor(a:String){action=a}
    constructor(c:Context,k:Class<*>){}
    fun setPackage(p:String)=this
    private val extras=mutableMapOf<String,Long>()
    fun putExtra(name:String,value:Long)=apply{extras[name]=value}
    fun getLongExtra(name:String,default:Long)=extras[name]?:default
}
open class Context {
    companion object { const val AUDIO_SERVICE="audio";const val ACTIVITY_SERVICE="activity";const val USB_SERVICE="usb";const val MODE_PRIVATE=0 }
    private val preferences=mutableMapOf<String,SharedPreferences>()
    val contentResolver=Any()
    fun createDeviceProtectedStorageContext()=this
    fun getSharedPreferences(name:String,mode:Int)=preferences.getOrPut(name){SharedPreferences()}
    val usb=android.hardware.usb.UsbManager()
    val packageName="review"
    val broadcasts=mutableListOf<String?>()
    var stops=0
    fun sendBroadcast(i:Intent){broadcasts.add(i.action)}
    fun stopService(i:Intent):Boolean{stops++;return true}
    fun getSystemService(n:String):Any=when(n){AUDIO_SERVICE->android.media.AudioManager();USB_SERVICE->usb;else->android.app.ActivityManager()}
}

/** Android storage double: the extracted USB hold owns all persistence decisions. */
class SharedPreferences {
    private val values=mutableMapOf<String,Any>()
    fun getBoolean(key:String,fallback:Boolean)=values[key] as? Boolean ?: fallback
    fun getInt(key:String,fallback:Int)=values[key] as? Int ?: fallback
    fun getLong(key:String,fallback:Long)=values[key] as? Long ?: fallback
    fun contains(key:String)=values.containsKey(key)
    fun edit()=Editor()
    inner class Editor {
        private val pending=mutableMapOf<String,Any>()
        private val removed=mutableSetOf<String>()
        private var cleared=false
        fun clear()=apply{cleared=true}
        fun remove(key:String)=apply{pending.remove(key);removed.add(key)}
        fun putBoolean(key:String,value:Boolean)=apply{removed.remove(key);pending[key]=value}
        fun putInt(key:String,value:Int)=apply{removed.remove(key);pending[key]=value}
        fun putLong(key:String,value:Long)=apply{removed.remove(key);pending[key]=value}
        fun commit():Boolean{if(cleared)values.clear();removed.forEach{values.remove(it)};values.putAll(pending);return true}
    }
}
