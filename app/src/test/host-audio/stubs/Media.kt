package android.media
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
class AudioManager {
    companion object {
        const val STREAM_MUSIC=3; const val STREAM_NOTIFICATION=5; const val STREAM_VOICE_CALL=0
        const val AUDIO_SESSION_ID_GENERATE=0
        const val AUDIOFOCUS_GAIN=1; const val AUDIOFOCUS_GAIN_TRANSIENT=2
        const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK=3
        const val AUDIOFOCUS_LOSS=-1
        const val AUDIOFOCUS_REQUEST_GRANTED=1; const val AUDIOFOCUS_REQUEST_FAILED=0
    }
    fun interface OnAudioFocusChangeListener { fun onAudioFocusChange(change: Int) }
    val focus = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    val requestThreads = CopyOnWriteArrayList<String>()
    var onRequest: (() -> Unit)? = null
    var onAbandon: (() -> Unit)? = null
    private fun grant(listener: OnAudioFocusChangeListener, gain: Int): Int {
        if (gain == AUDIOFOCUS_GAIN) {
            focus.filter { it !== listener }.forEach { old ->
                focus.remove(old)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    (old as OnAudioFocusChangeListener).onAudioFocusChange(AUDIOFOCUS_LOSS)
                }
            }
        }
        focus.add(listener); return 1
    }
    fun getStreamMaxVolume(stream: Int): Int = if (stream in 0..9) 15 else throw IllegalArgumentException()
    fun requestAudioFocus(request: AudioFocusRequest): Int {
        requestThreads.add(Thread.currentThread().name); onRequest?.invoke(); return grant(request.listener, request.gain)
    }
    fun requestAudioFocus(listener: OnAudioFocusChangeListener, stream: Int, gain: Int): Int {
        requestThreads.add(Thread.currentThread().name); onRequest?.invoke(); return grant(listener, gain)
    }
    fun abandonAudioFocusRequest(request: AudioFocusRequest): Int { focus.remove(request.listener); onAbandon?.invoke(); return 1 }
    fun abandonAudioFocus(listener: OnAudioFocusChangeListener?): Int { if (listener != null) focus.remove(listener); onAbandon?.invoke(); return 1 }
}
class AudioFocusRequest(val listener: AudioManager.OnAudioFocusChangeListener, val gain: Int) {
    class Builder(private val gain: Int) {
        private var listener: AudioManager.OnAudioFocusChangeListener? = null
        fun setAudioAttributes(attrs: AudioAttributes) = this
        fun setWillPauseWhenDucked(pause: Boolean) = this
        fun setOnAudioFocusChangeListener(listener: AudioManager.OnAudioFocusChangeListener) = apply { this.listener = listener }
        fun build() = AudioFocusRequest(requireNotNull(listener), gain)
    }
}
class AudioAttributes(val legacyStream: Int = AudioManager.STREAM_MUSIC) {
    class Builder {
        private var legacyStream = AudioManager.STREAM_MUSIC
        fun setUsage(x: Int) = this
        fun setContentType(x: Int) = this
        fun setLegacyStreamType(x: Int) = apply { legacyStream = x }
        fun build() = AudioAttributes(legacyStream)
    }
    companion object {
        const val USAGE_NOTIFICATION=5;const val USAGE_VOICE_COMMUNICATION=2;const val USAGE_MEDIA=1
        const val CONTENT_TYPE_SONIFICATION=4;const val CONTENT_TYPE_SPEECH=1;const val CONTENT_TYPE_MUSIC=2
    }
}
class AudioFormat {class Builder{fun setSampleRate(x:Int)=this;fun setChannelMask(x:Int)=this;fun setEncoding(x:Int)=this;fun build()=AudioFormat()};companion object{const val CHANNEL_OUT_STEREO=12;const val CHANNEL_OUT_MONO=4;const val ENCODING_PCM_16BIT=2;const val ENCODING_PCM_8BIT=3}}
open class AudioTrack(val stream:Int,rate:Int,channels:Int,encoding:Int,bytes:Int,mode:Int) {
 companion object {const val STATE_INITIALIZED=1;const val MODE_STREAM=1;const val PERFORMANCE_MODE_LOW_LATENCY=1;const val WRITE_NON_BLOCKING=1;const val PLAYSTATE_PLAYING=3;@Volatile var failMinimum=false;@Volatile var minimumBytes=3840;@Volatile var minimumQueries=0;@Volatile var initialGrantFrames=0;@Volatile var blockWrites=false;val created=CopyOnWriteArrayList<AudioTrack>();@Volatile var nextReplies:List<Int> = emptyList();fun getMinBufferSize(rate:Int,ch:Int,fmt:Int):Int {minimumQueries++;return if(failMinimum)-2 else minimumBytes}}
 val state=STATE_INITIALIZED;val audioSessionId=1;var playState=0;var playbackHeadPosition=0;var advancePlayback=true;var writeCalls=0;protected val nativeFrameCount get()=bufferCapacityInFrames;@Volatile var bufferCapacityInFrames=maxOf(bytes/4,initialGrantFrames);@Volatile var bufferSizeInFrames=bufferCapacityInFrames;@Volatile var underrunCount=0;var closed=false
 val bufferRequests=CopyOnWriteArrayList<Int>()
 var startThreshold=0
 var byteBufferWriteCalls=0
 var lastWriteMode=-1
 var acceptedFrames=0
 var flushCalls=0
 var deferFlush=false
 var writeHook: ((ShortArray, Int, Int) -> Int?)? = null
 var bufferHook: ((Int) -> Unit)? = null
 val byteReplies=ConcurrentLinkedQueue<Int>()
 val samples=CopyOnWriteArrayList<Short>();val replies=ConcurrentLinkedQueue<Int>()
 init {replies.addAll(nextReplies);nextReplies=emptyList();created.add(this)}
 class Builder {
  private var bytes=76800
  private var attributes=AudioAttributes()
  fun setAudioAttributes(x:AudioAttributes)=apply{attributes=x}
  fun setAudioFormat(x:AudioFormat)=this
  fun setTransferMode(x:Int)=this
  fun setBufferSizeInBytes(x:Int)=apply{bytes=x}
  fun setPerformanceMode(x:Int)=this
  fun build()=AudioTrack(attributes.legacyStream,48000,12,2,bytes,1)
 }
 fun setVolume(x:Float){};fun setStereoVolume(l:Float,r:Float){};fun setBufferSizeInFrames(x:Int):Int {check(android.os.Build.VERSION.SDK_INT>=24);bufferRequests.add(x);bufferHook?.invoke(x);bufferSizeInFrames=if(bufferCapacityInFrames<16)bufferCapacityInFrames else x.coerceIn(16,bufferCapacityInFrames);return bufferSizeInFrames};fun setStartThresholdInFrames(x:Int):Int {check(android.os.Build.VERSION.SDK_INT>=31);startThreshold=x;return x};fun play(){playState=3};fun pause(){playState=2};fun flush(){flushCalls++;if(!deferFlush){playbackHeadPosition=0;acceptedFrames=0}};fun stop(){playState=1};fun release(){closed=true}
 fun write(a:ShortArray,offset:Int,count:Int,mode:Int=0):Int {writeCalls++;lastWriteMode=mode;val n=writeHook?.invoke(a,offset,count) ?: if(blockWrites)0 else replies.poll()?:count;if(n>0){samples.addAll(a.slice(offset until offset+n));acceptedFrames+=n/2;if(advancePlayback)playbackHeadPosition+=n/2;Thread.sleep(1)};return n}
 fun write(a:ByteBuffer,count:Int,mode:Int):Int {
  check(android.os.Build.VERSION.SDK_INT>=21)
  check(a.isDirect && a.order()==java.nio.ByteOrder.nativeOrder())
  byteBufferWriteCalls++;lastWriteMode=mode
  val freeBytes=(bufferSizeInFrames-(acceptedFrames-playbackHeadPosition)).coerceAtLeast(0)*4
  val n=if(blockWrites)0 else byteReplies.poll()?:minOf(count,freeBytes)
  if(n>0){repeat(n/2){samples.add(a.short)};acceptedFrames+=n/4;if(advancePlayback)playbackHeadPosition+=n/4;Thread.sleep(1)}
  return n
 }
 fun write(a:ByteArray,offset:Int,count:Int,mode:Int=0):Int {Thread.sleep(1);return count}
}
class MediaFormat {private val map=mutableMapOf<String,Any>();fun setInteger(k:String,v:Int){map[k]=v};fun getInteger(k:String)=map[k] as Int;fun setByteBuffer(k:String,v:ByteBuffer){map[k]=v};fun containsKey(k:String)=map.containsKey(k);companion object {const val KEY_IS_ADTS="is-adts";const val KEY_AAC_PROFILE="aac-profile";const val KEY_MAX_INPUT_SIZE="max-input-size";const val KEY_SAMPLE_RATE="sample-rate";const val KEY_CHANNEL_COUNT="channel-count";fun createAudioFormat(mime:String,rate:Int,ch:Int)=MediaFormat().apply{setInteger(KEY_SAMPLE_RATE,rate);setInteger(KEY_CHANNEL_COUNT,ch)}} }
object MediaCodecInfo {object CodecProfileLevel{const val AACObjectLC=2}}
class MediaCodec {
 companion object {const val INFO_OUTPUT_FORMAT_CHANGED=-2;const val INFO_OUTPUT_BUFFERS_CHANGED=-3;const val BUFFER_FLAG_CODEC_CONFIG=2;@Volatile var provideInputs=true;@Volatile var startHook:(()->Unit)?=null;val created=CopyOnWriteArrayList<MediaCodec>();fun createDecoderByType(mime:String)=MediaCodec().also{created.add(it)}}
 class CodecException:Exception();class BufferInfo{var presentationTimeUs=0L;var size=0;var offset=0;var flags=0}
 abstract class Callback {abstract fun onInputBufferAvailable(codec:MediaCodec,index:Int);abstract fun onOutputBufferAvailable(codec:MediaCodec,index:Int,info:BufferInfo);abstract fun onError(codec:MediaCodec,e:CodecException);abstract fun onOutputFormatChanged(codec:MediaCodec,format:MediaFormat)}
 @Volatile private var formatPending=false
 val syncOutputs=ConcurrentLinkedQueue<ByteArray>()
 @JvmField var callback:Callback?=null;var outputFormat=MediaFormat();val inputBuffers=Array(128){ByteBuffer.allocate(16384)};val outputBuffers=Array(1){ByteBuffer.allocate(16384)};var currentOutput=ByteBuffer.allocate(0);@Volatile var stopHook:(()->Unit)?=null;@Volatile var queued=0
 fun setCallback(c:Callback){callback=c};fun setCallback(c:Callback,h:android.os.Handler){callback=c};fun configure(f:MediaFormat,s:Any?,c:Any?,flags:Int){outputFormat=f};fun start(){startHook?.invoke();formatPending=callback==null;callback?.onOutputFormatChanged(this,outputFormat);if(provideInputs)repeat(128){callback?.onInputBufferAvailable(this,it)}}
 fun stop(){stopHook?.invoke()};fun release(){};fun getInputBuffer(i:Int)=inputBuffers[i];fun getOutputBuffer(i:Int)=currentOutput;fun releaseOutputBuffer(i:Int,render:Boolean){};fun dequeueInputBuffer(timeout:Long)=0;fun dequeueOutputBuffer(info:BufferInfo,timeout:Long):Int {
 if(formatPending){formatPending=false;return INFO_OUTPUT_FORMAT_CHANGED}
 val bytes=syncOutputs.poll() ?: return -1
 outputBuffers[0]=ByteBuffer.wrap(bytes);info.offset=0;info.size=bytes.size;info.flags=0;info.presentationTimeUs=0
 return 0
};fun queueInputBuffer(i:Int,o:Int,s:Int,pts:Long,flags:Int){queued++}
 fun emit(bytes:ByteArray){currentOutput=ByteBuffer.wrap(bytes);callback!!.onOutputBufferAvailable(this,0,BufferInfo().apply{size=bytes.size})}
}
