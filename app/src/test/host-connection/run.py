#!/usr/bin/env python3
"""Run real manager/transport lifecycle methods against JVM thread and I/O doubles.

Latch-controlled cases cover late entry, retirement before worker startup, in-flight
handshake cancellation, and cleanup completion before reconnection. Network, Android
codecs, and Handler scheduling are not device simulations. Methods are extracted from
production so the tests exercise the whole entry/teardown paths, not copies of guards.
"""
from pathlib import Path
import os, subprocess, sys, hashlib, json, shutil
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[4]
if not (ROOT/'app/src/main/java').is_dir():
    raise SystemExit('Pass the extracted source/ directory as the first argument.')
OUT=ROOT/'build/host-connection'
OUT.mkdir(parents=True,exist_ok=True)
base=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
comm=(base/'connection/CommManager.kt').read_text()
transport=(base/'aap/AapTransport.kt').read_text()
def member(source, name):
    start=source.index(name); b=source.index('{',start); depth=0
    for end in range(b,len(source)):
        if source[end]=='{':depth+=1
        elif source[end]=='}':
            depth-=1
            if depth==0:return source[start:end+1]
    raise ValueError(name)
methods={}
def extract(source,name):
    s=member(source,name);methods[name]=hashlib.sha256(s.encode()).hexdigest();return s
manager_head=r'''package lifecycle
import android.hardware.usb.*
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.*
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.*

enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART }
sealed class ConnectionState {
 class Disconnected(val isClean:Boolean=false,val isUserExit:Boolean=false, val reason:DisconnectReason=DisconnectReason.CONNECTION_ENDED, val restartEndpoint:Pair<String,Int>?=null):ConnectionState()
 object Connecting:ConnectionState();object Connected:ConnectionState();object StartingTransport:ConnectionState()
 object HandshakeComplete:ConnectionState();object TransportStarted:ConnectionState()
 data class Error(val message:String):ConnectionState()
}
class QueuedDispatcher:CoroutineDispatcher(){
 val tasks=ConcurrentLinkedQueue<Runnable>()
 override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){tasks.add(block)}
 fun drain(){while(true){val next=tasks.poll()?:return;next.run()}}
}
class CommManager {
 val context=Context();val settings=Settings();val audioDecoder=AudioDecoder();val videoDecoder=VideoDecoder()
 private val transportLifecycleLock=Any()
 private var disconnectRequested=false
 private var connectionAttempt:Any?=null
 private var physicalConnectionReached=true
 private var outgoingEndpoint:Pair<String,Int>?=null
 @Volatile private var _transport:AapTransport?=null
 @Volatile private var _connection:ProjectionConnection?=ProjectionConnection()
 private val _backgroundNotification=Unit
 private val aapSslContext=Ssl()
 val queue=QueuedDispatcher()
 private var _scope=CoroutineScope(SupervisorJob()+queue)
 private fun newDisconnectScope()=CoroutineScope(SupervisorJob()+queue)
 private val _connectionState=MutableStateFlow<ConnectionState>(ConnectionState.Connected)
 @Volatile private var _disconnectJob:Job?=null
 private var onAaMediaMetadata:((Any)->Unit)?=null
 private var onAaPlaybackStatus:((Any)->Unit)?=null
 private var onAudioFocusStateChanged:((Boolean)->Unit)?=null
 private var onUpdateUiConfigReplyReceived:(()->Unit)?=null
 private var onSessionFailure:((String)->Unit)?={failures.add(it)}
 val failures=mutableListOf<String>()
 private var sessionReachedHandshake=false
 private var silentPeerFailures=0
 private var lastAttemptedEndpoint:String?=null
 private val keyStates=mutableMapOf<Int,Int>()
 private var btMediaLinkCached:Boolean?=null
 private var btMediaLinkCheckedAt:Long?=null
 private fun noteHandshakeOutcome(silent:Boolean){}
 private fun settleSessionClaim(formed:Boolean){}
 private fun noteSessionEnded(renderedAnyFrame:Boolean, settingsRestart:Boolean=false){}
 val connectionState get()=_connectionState
 val state get()=_connectionState.value
 fun owner()=checkNotNull(_transport)
 fun nullableOwner()=_transport
 fun network()=_connection
 fun requested()=synchronized(transportLifecycleLock){disconnectRequested}
 fun cleanups(){queue.drain()}
 fun close(){disconnect(honorKillOnDisconnect=false);cleanups();_scope.cancel()}
 suspend fun connectNext(){connectIp("fixture",5277, null)}
 suspend fun connectSelf(){connectIp("127.0.0.1",5277,null)}
 suspend fun connectSaved(state:ConnectionState.Disconnected){connectIp("fixture",5277,state)}
 suspend fun awaitDisconnectComplete(){_disconnectJob?.join()}
 val isUsbSession=false
 suspend fun connectDevice(d:UsbDevice){connectUsb(d)}
 suspend fun awaitCleanup(){_disconnectJob?.join()}
 companion object { const val ERROR_HANDSHAKE_PEER_SILENT="Handshake failed: the peer never responded" }
'''
# Keep Save permission and deadline semantics from the same production state as the methods.
old_state=manager_head[manager_head.index(' class Disconnected('):manager_head.index(' object Connecting:')]
manager_head=manager_head.replace(old_state, extract(comm, 'class Disconnected(')+'\n')
for name in ['SettingsRestartRecovery.kt', 'SameEndpointConnectPolicy.kt']:
    source=(base/'connection'/name).read_text().replace('package com.andrerinas.openheadunit.connection', 'package lifecycle')
    (OUT/name).write_text(source)
# These endpoint predicates must be the real ones when testing the terminal route snapshot.
for declaration in ['val isWirelessSession:', 'val isLoopbackSession:']:
    start = comm.index(declaration)
    manager_head += comm[start:comm.index('\n\n', start)] + '\n'
manager=manager_head+'\n'.join(extract(comm,n) for n in [
 'suspend fun startHandshake()', 'private inline fun withLiveTransport(', 'suspend fun startReading()',
 'private fun transportedQuited(', 'fun applyAudioSettings()', 'fun disconnect(', 'fun cancelPendingSettingsRestart()', 'private fun doDisconnect(',
 'private suspend fun connectIp(', 'private suspend fun connectUsb(', 'fun disconnectForLinkLoss(', 'fun destroy()'
])+'\n}\n'
transport_head=r'''
class AapTransport(
 audioDecoder:AudioDecoder,private val videoDecoder:VideoDecoder,manager:AudioManager,settings:Settings,
 notification:Any,context:Context,externalSsl:Ssl,
 onAaMediaMetadata:((Any)->Unit)?,onAaPlaybackStatus:((Any)->Unit)?
){
 private val lifecycleLock=Any()
 @Volatile private var closing=false
 private var handshakeStarted=false
 private val handshakeFinished=CountDownLatch(1)
 private val terminated=CountDownLatch(1)
 private var retiringWorkers:List<Thread> = emptyList()
 val aapAudio=AapAudio(audioDecoder,settings)
 val aapVideo=AapVideo();val ssl=externalSsl
 var onQuit:((Boolean)->Unit)?=null
 var onAudioFocusStateChanged:((Boolean)->Unit)?=null
 var onUpdateUiConfigReplyReceived:(()->Unit)?=null
 var wasUserExit=false
 enum class HandshakeFailure{NONE,PEER_SILENT,OTHER}
 var lastHandshakeFailure=HandshakeFailure.NONE
 private var connection:ProjectionConnection?=null
 private var sendThread:HandlerThread?=null
 private var pollThread:HandlerThread?=null
 private var videoThread:HandlerThread?=null
 private var sendHandler:Handler?=null
 private var pollHandler:Handler?=null
 private var videoHandler:Handler?=null
 private var aapRead:AapRead?=null
 private val micSessions=MicSessions()
 private fun retireMicrophone(){micSessions.close(shutdown=true)}
 private fun resetMicrophone(){}
 private val focusCycleGainRunnable=Runnable{}
 private val unrepairedCheckRunnable=Runnable{}
 private val videoBufferPool=ConcurrentLinkedQueue<ByteArray>()
 private val videoBacklog=java.util.concurrent.atomic.AtomicInteger()
 private val videoShedTotal=java.util.concurrent.atomic.AtomicLong()
 private var lastMessageReceivedMs=0L
 private val linkGapMonitor=Monitor();private val videoGapMonitor=Monitor();private val audioGapMonitor=Monitor()
 private val startedAudioChannels=HashSet<Int>();private var audioTimingActive=false
 private var nextReadTimingMs=0L;private var nextSendTimingMs=0L
 private val uplinkStallMonitor=Monitor();private val inboundRateMonitor=Monitor()
 private val bluetoothLinkMonitor=BluetoothMonitor()
 private val sendHandlerCallback=Any();private val pollHandlerCallback=Any()
 // CALLBACK MEMBERS
 init {
  // CALLBACK PUBLICATION
 }
 private fun triggerFocusCycleRecovery(escalatable:Boolean,wireCorruption:Boolean){}
 private fun onKeyframeRepairedPicture(){}
 private fun send(message:AapMessage){}
 private fun endFocusCycle(){}
 private fun handshake(conn:ProjectionConnection):Boolean {
  handshakeHook?.invoke()
  forcedFailure?.let { lastHandshakeFailure=it;return false }
  // Network I/O is doubled; a closed connection fails, an open connection succeeds.
  lastHandshakeFailure=if(conn.closed) HandshakeFailure.OTHER else HandshakeFailure.NONE
  if(!conn.closed) ssl.beginSession()
  return !conn.closed
 }
 private val micRecorder=Unit; private val settings=Settings();private val context=Context()
 private val onAaMediaMetadata:((Any)->Unit)?=null;private val onAaPlaybackStatus:((Any)->Unit)?=null
 fun installReader(reader:AapRead){aapRead=reader}
 fun postPoll(task:()->Unit){check(checkNotNull(pollHandler).post(Runnable{task()}))}
 fun pollSnapshot()=checkNotNull(pollThread)
 fun liveWorkers()=listOfNotNull(sendThread,pollThread,videoThread).count{it.isAlive}
 companion object {const val MSG_POLL=1;@Volatile var handshakeHook:(()->Unit)?=null;var forcedFailure:HandshakeFailure?=null}
'''
callback_start=transport.index("    private val decoderErrorCallback:")
callback_end=transport.index("    init {",callback_start)
transport_head=transport_head.replace("// CALLBACK MEMBERS",transport[callback_start:callback_end])
transport_head=transport_head.replace("// CALLBACK PUBLICATION",member(transport[callback_end:],"synchronized(videoDecoder)"))
transport_fixture=transport_head+'\n'.join(extract(transport,n) for n in [
 'internal fun startHandshake(connection:', 'internal fun startReading()', 'internal fun stop(', 'internal fun quit(',
 'private fun resetSessionObservations()', 'internal fun awaitTermination()', 'private inline fun awaitUninterruptibly(', 'private inline fun cleanupStep('
])+'\n}\n'
bulk=(base/'aap/AapReadMultipleMessages.kt').read_text()
process=extract(bulk,'private fun processBulk()')
support=r'''
object AppLog {
 @Volatile var hook:((String)->Unit)?=null
 fun i(s:String){hook?.invoke(s)};fun e(s:String){};fun e(s:String,e:Throwable){};fun w(s:String){}
}
class Settings{
 var useAacAudio=false;var killOnDisconnect=false;var useLibusb=false
 fun saveLastConnection(type:Int,ip:String="",usbDevice:String=""){}
 companion object{const val CONNECTION_TYPE_WIFI=1;const val CONNECTION_TYPE_USB=2}
}
class AudioDecoder { var session=0; fun captureCleanup():()->Unit = {} }
class AapAudio(decoder:AudioDecoder,private val settings:Settings){
 val sessionConfig=AudioConfig();private val savedAac=settings.useAacAudio
 var closed=false
 fun needsSessionRestart()=savedAac!=settings.useAacAudio
 fun restartAudio(){};fun releaseAllFocus(){closed=true}
 fun postProtocolFocusChange(stream:Int,request:Int,callback:AudioManager.OnAudioFocusChangeListener){}
}
class AudioConfig{val enabled=true;val staticFocus=false;val focusMode=0}
class VideoDecoder{var framesRenderedThisSession=0L;var onDecoderError:((String)->Unit)?=null;var onKeyframeStarved:(()->Unit)?=null;var onFrameDropped:(()->Unit)?=null;var onKeyframeObserved:(()->Unit)?=null;fun stop(s:String){}}
class AapVideo{fun release(){}}
open class AapRead{@Volatile protected var isStopped=false;fun stop(){isStopped=true};companion object{var creations=0};object Factory{
 fun create(connection:ProjectionConnection,transport:AapTransport,mic:Any,audio:AapAudio,video:AapVideo,
 settings:Settings,context:Context,metadata:((Any)->Unit)?,playback:((Any)->Unit)?):AapRead{creations++;return AapRead()}
}}
open class ProjectionConnection{@Volatile var closed=false;open fun connect()=true;open fun disconnect(){closed=true}}
class SocketProjectionConnection(private val ip:String,port:Int,context:Context):ProjectionConnection(){
 val isLoopbackPeer get()=ip=="127.0.0.1"
 init { created.add(this);constructionHook?.invoke() }
 override fun connect():Boolean = connectionHook?.invoke(this) ?: super.connect()
 companion object { @Volatile var constructionHook:(()->Unit)?=null;@Volatile var connectionHook:((ProjectionConnection)->Boolean)?=null;val created=ConcurrentLinkedQueue<ProjectionConnection>() }
}
class Ssl{
 @Volatile var generation=0
 val decrypts=ConcurrentLinkedQueue<Pair<Int,Int>>()
 fun beginSession(){generation++}
 fun observeDecrypt(id:Int){decrypts.add(generation to id)}
 fun release(){}
} // Exact semantic of AapSslContext.release: no-op, NOT a TLS failure claim.
class Monitor{fun reset(){}}
class BluetoothMonitor{fun onSessionStart(){}}
class MicSessions{fun close(shutdown:Boolean){}}
class AapMessage(val channel:Int,val type:Int,val data:Any)
object Channel{const val ID_CTR=0}
object LegacyOptimizer{fun setHighPriority(){}}
object HeadUnitScreenConfig{fun unlockResolution(){}}
object BluetoothHelper{fun isA2dpMediaLinkActive(c:Context)=false}
object PlaybackFocusPolicy{fun shouldAcquirePermanent(mode:Int,staticAudioFocus:Boolean,audioSinkEnabled:Boolean,btMediaLinkActive:Boolean)=false}
object ConnectionIssue{const val HEADUNIT_SERVER_NOT_ANSWERING=0}
object ConnectionIssues{fun clear(c:Context,issue:Int){}}
object ConnectionStageTracker{fun endAttempt(){};fun clear(){};val stage=MutableStateFlow(ConnectionStage.NONE)}
object ConnectionStage{const val NONE=0;const val USB_ATTACHED=1;const val USB_SWITCHING=2}
object TeardownGuard{fun runThenClose(teardown:()->Unit,close:()->Unit,onError:(String,Exception)->Unit){try{teardown()}catch(e:Exception){onError("teardown",e)}finally{close()}}}
fun await(latch:CountDownLatch){check(latch.await(5,TimeUnit.SECONDS)){"probe barrier timed out"}}

fun lateHandshakeEntry()=runBlocking{
 val c=CommManager();c.settings.killOnDisconnect=true
 c.startHandshake();c.startReading();check(c.state is ConnectionState.TransportStarted)
 val old=c.owner()
 c.settings.useAacAudio=true;c.applyAudioSettings()
 check(c.state is ConnectionState.Disconnected && c.context.broadcasts.isEmpty())
 c.startHandshake()
 check(c.state is ConnectionState.Disconnected && c.failures.isEmpty())
 old.quit(false)
 check(c.context.broadcasts.isEmpty() && Handler.delayed.isEmpty())
 check(!com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy)
 c.cleanups();c.close()
 println("PASS late handshake entry cannot reopen the no-kill disconnect decision")
}
fun cancelledBeforeStartup()=runBlocking{
 val c=CommManager()
 val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
 AppLog.hook={s->if(s.startsWith("Start Aap transport handshake for")){entered.countDown();await(proceed)}}
 val errors=ConcurrentLinkedQueue<Throwable>()
 val starter=Thread{try{runBlocking{c.startHandshake()}}catch(t:Throwable){errors.add(t)}}
 starter.start();await(entered)
 val retired=c.owner()
 try {
  c.settings.useAacAudio=true;c.applyAudioSettings();c.cleanups();c.awaitCleanup()
  check(retired.onQuit==null && retired.aapAudio.closed && retired.liveWorkers()==0)
 } finally {proceed.countDown();starter.join(5000);AppLog.hook=null}
 check(!starter.isAlive && errors.isEmpty()){errors.toString()}
 check(c.state is ConnectionState.Disconnected && retired.liveWorkers()==0)
 val readers=AapRead.creations;retired.startReading();check(AapRead.creations==readers)
 retired.quit();retired.awaitTermination();c.close()
 println("PASS retirement before startup prevents worker and read-loop resurrection")
}
fun oldQuitOutlivesCleanup(settingsSave:Boolean)=runBlocking{
 val c=CommManager();c.startHandshake();c.startReading();val old=c.owner()
 if(settingsSave){c.settings.useAacAudio=true;c.applyAudioSettings()}
 val atTail=CountDownLatch(1);val proceed=CountDownLatch(1)
 Handler.removeHook={if(Thread.currentThread().name=="old-quit"){Handler.removeHook=null;atTail.countDown();await(proceed)}}
 val oldQuit=Thread({old.quit(false)},"old-quit");oldQuit.start();await(atTail)
 check(c.state is ConnectionState.Disconnected)
 val cleanupStarted=CountDownLatch(1);val cleanupDone=CountDownLatch(1)
 val cleanup=Thread{cleanupStarted.countDown();c.cleanups();cleanupDone.countDown()}
 cleanup.start();await(cleanupStarted)
 try {
  check(!cleanupDone.await(100,TimeUnit.MILLISECONDS)){"cleanup finished while old quit was paused"}
 } finally {proceed.countDown();oldQuit.join(5000);cleanup.join(5000)}
 check(!oldQuit.isAlive && !cleanup.isAlive && cleanupDone.count==0L)
 c.awaitCleanup();c.connectNext();c.startHandshake();c.startReading();val fresh=c.owner()
 check(fresh!==old && c.state is ConnectionState.TransportStarted)
 check(c.videoDecoder.onDecoderError!=null && c.videoDecoder.onKeyframeStarved!=null &&
       c.videoDecoder.onFrameDropped!=null && c.videoDecoder.onKeyframeObserved!=null)
 c.close()
 println("PASS reconnect waits for old quit body (${if(settingsSave) "settings + EOF" else "natural EOF"})")
}
fun cancelDuringHandshake()=runBlocking{
 val c=CommManager();val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
 val finished=CountDownLatch(1);val errors=ConcurrentLinkedQueue<Throwable>()
 AapTransport.handshakeHook={entered.countDown();await(proceed)}
 val starter=Thread{try{runBlocking{c.startHandshake()}}catch(t:Throwable){errors.add(t)}}
 starter.start();await(entered);val old=c.owner()
 c.settings.useAacAudio=true;c.applyAudioSettings()
 val cleanup=Thread{c.cleanups();finished.countDown()};cleanup.start()
 try{check(!finished.await(100,TimeUnit.MILLISECONDS)){"cleanup raced active handshake"}}
 finally{proceed.countDown();starter.join(5000);cleanup.join(5000);AapTransport.handshakeHook=null}
 check(!starter.isAlive && !cleanup.isAlive && errors.isEmpty()){errors.toString()}
 check(finished.count==0L && old.liveWorkers()==0 && c.state is ConnectionState.Disconnected)
 c.close();println("PASS cleanup waits for active handshake before shared TLS can be reused")
}
fun handshakeFailure()=runBlocking{
 val c=CommManager();AapTransport.handshakeHook={error("injected handshake failure")}
 try{c.startHandshake();c.cleanups();check(c.state is ConnectionState.Disconnected && c.failures.size==1)}
 finally{AapTransport.handshakeHook=null;c.close()}
 println("PASS handshake failure still reports and tears down its own session")
}
fun controls()=runBlocking{
 val c=CommManager();c.startHandshake();c.startReading();val old=c.owner()
 c.settings.killOnDisconnect=true;c.settings.useAacAudio=true;c.applyAudioSettings();old.quit(false)
 check(c.state is ConnectionState.Disconnected && c.context.broadcasts.isEmpty() && Handler.delayed.isEmpty())
 c.cleanups();c.connectNext();c.startHandshake();c.startReading()
 check(c.state is ConnectionState.TransportStarted && c.videoDecoder.onDecoderError!=null)
 c.settings.killOnDisconnect=false;c.close()
 println("PASS ordinary settings disconnect and reconnect keep callbacks and no-kill policy")
}
fun providedMain(){
 controls();lateHandshakeEntry();cancelledBeforeStartup();oldQuitOutlivesCleanup(false)
 oldQuitOutlivesCleanup(true);cancelDuringHandshake();handshakeFailure()
 println("ALL TRANSPORT LIFECYCLE CHECKS PASSED")
}
'''
extra=r'''
// Complete production processBulk is injected below. Envelope/TLS/protocol endpoints are doubles.
class BulkReaderFixture(private val ssl:Ssl,private val deliver:(AapMessage,Int)->Unit):AapRead(){
 private val fifo=java.nio.ByteBuffer.allocate(256)
 private val recvHeader=AapMessageIncoming.EncryptedHeader()
 private val msgBuffer=ByteArray(256)
 private val skipBuffer=ByteArray(4)
 fun readBatch(bytes:ByteArray){fifo.put(bytes);processBulk()}
 private fun shouldDropForFaultInjection(channel:Int,flags:Int,encLen:Int)=false
 private fun deliverFragment(message:AapMessage,total:Int)=deliver(message,total)
 private fun auditFragment(channel:Int,flags:Int,length:Int,total:Int){}
 private val handler=object {fun handle(message:AapMessage)=deliver(message,0)}
 // BULK METHOD
}
object Utils{fun bytesToInt(b:ByteArray,offset:Int,short:Boolean):Int{
 var n=0;for(i in offset until offset+(if(short)2 else 4))n=(n shl 8) or (b[i].toInt() and 255);return n
}}
object AapMessageFraming{fun carriesTotalLength(flags:Int)=flags and 1 !=0 && flags and 2==0}
object AapMessageIncoming{
 class EncryptedHeader{
  val buf=ByteArray(4);var chan=0;var flags=0;var enc_len=0
  fun decode(){chan=buf[0].toInt() and 255;flags=buf[1].toInt() and 255;enc_len=Utils.bytesToInt(buf,2,true)}
  companion object{const val SIZE=4}
 }
 fun decrypt(h:EncryptedHeader,offset:Int,b:ByteArray,ssl:Ssl):AapMessage?{
  val id=Utils.bytesToInt(b,offset,true);ssl.observeDecrypt(id);return AapMessage(h.chan,id,Unit)
 }
}
fun peerBatch()=byteArrayOf(0,0x0b,0,2,0,15, 0,0x0b,0,2,0,11)

fun latePhysicalPublication()=runBlocking{
 for(replace in listOf(false,true)){
  val c=CommManager();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
  val inConstructor=CountDownLatch(1);val resumeConstructor=CountDownLatch(1)
  val errors=ConcurrentLinkedQueue<Throwable>()
  SocketProjectionConnection.created.clear()
  SocketProjectionConnection.constructionHook={inConstructor.countDown();await(resumeConstructor)}
  val opener=Thread{try{runBlocking{c.connectNext()}}catch(t:Throwable){errors.add(t)}}
  opener.start();await(inConstructor);val late=checkNotNull(SocketProjectionConnection.created.peek())
  check(c.state is ConnectionState.Connecting && c.network()==null)
  c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups();c.awaitCleanup()
  check(c.state is ConnectionState.Disconnected && c.network()==null && c.requested())
  SocketProjectionConnection.constructionHook=null
  if(replace){c.connectNext();c.startHandshake();c.startReading()}
  val fresh=c.network()
  resumeConstructor.countDown();opener.join(5000)
  check(!opener.isAlive && errors.isEmpty()){errors.toString()}
  check(late.closed && c.network()===fresh)
  if(replace) check(c.state is ConnectionState.TransportStarted && fresh!=null && !fresh.closed)
  else{
   check(c.state is ConnectionState.Disconnected && c.nullableOwner()==null)
   c.startHandshake();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
   check(c.state is ConnectionState.Disconnected && c.network()==null)
  }
  c.close()
 }
 println("PASS cancelled physical candidates close without reviving state or touching a replacement")
}

fun workerQuitBulkTail()=runBlocking{
 val c=CommManager();c.startHandshake();c.startReading();val old=c.owner();val oldPoll=old.pollSnapshot()
 val returnedFromQuit=CountDownLatch(1);val resumeBulk=CountDownLatch(1);val bulkFinished=CountDownLatch(1)
 val errors=ConcurrentLinkedQueue<Throwable>()
 val reader=BulkReaderFixture(old.ssl){message,_->
  if(message.type==15){
   // Mirrors AapControl.byebyeRequest's synchronous call from the poll/read stack.
   old.quit(clean=true)
   returnedFromQuit.countDown();await(resumeBulk)
  }
 }
 old.installReader(reader)
 old.postPoll{
  try{reader.readBatch(peerBatch())}catch(t:Throwable){errors.add(t)}finally{bulkFinished.countDown()}
 }
 await(returnedFromQuit)
 check(c.state is ConnectionState.Disconnected && oldPoll.isAlive)
 val cleanupDone=CountDownLatch(1)
 val cleanup=Thread{try{c.cleanups()}catch(t:Throwable){errors.add(t)}finally{cleanupDone.countDown()}}
 cleanup.start()
 try{check(!cleanupDone.await(100,TimeUnit.MILLISECONDS)){"cleanup passed while old read was still active"}}
 finally{resumeBulk.countDown();await(bulkFinished);oldPoll.join(5000);cleanup.join(5000)}
 check(!oldPoll.isAlive && !cleanup.isAlive && errors.isEmpty()){errors.toString()}
 c.awaitCleanup();c.connectNext();c.startHandshake();c.startReading();val fresh=c.owner()
 check(fresh!==old && c.state is ConnectionState.TransportStarted && fresh.ssl===old.ssl)
 check(old.ssl.decrypts.toList()==listOf(1 to 15)){old.ssl.decrypts.toString()}
 println("PASS worker-originated quit stops bulk tail and reconnect waits for the whole Poll stack")
 c.close()
}

fun workerQuitNoTailControl()=runBlocking{
 val c=CommManager();c.startHandshake();c.startReading();val old=c.owner();val worker=old.pollSnapshot()
 val done=CountDownLatch(1)
 old.postPoll{old.quit();done.countDown()};await(done);worker.join(5000)
 c.cleanups();c.awaitCleanup();check(!worker.isAlive)
 c.connectNext();c.startHandshake();c.startReading();check(c.state is ConnectionState.TransportStarted)
 c.close();println("PASS ordinary worker-originated EOF shutdown without a buffered tail")
}

fun failedHandshakeControls()=runBlocking{
 for(reason in listOf(AapTransport.HandshakeFailure.OTHER,AapTransport.HandshakeFailure.PEER_SILENT)){
  val c=CommManager();AapTransport.forcedFailure=reason
  try{
   c.startHandshake();val old=c.owner();check(c.state is ConnectionState.Disconnected)
   check(c.failures==listOf(if(reason==AapTransport.HandshakeFailure.PEER_SILENT)"peer_silent" else "handshake_failed"))
   c.cleanups();check(old.liveWorkers()==0 && old.aapAudio.closed)
  }finally{AapTransport.forcedFailure=null;c.close()}
 }
 println("PASS first false handshake results report exactly once (ordinary / peer_silent) and retire workers")
}
fun partialStartupExceptionControl()=runBlocking{
 val c=CommManager()
 HandlerThread.creationHook={name->if(name.endsWith("::Poll"))throw IllegalStateException("injected partial startup")}
 try{
  c.startHandshake();val old=c.owner()
  check(c.state is ConnectionState.Disconnected && c.failures==listOf("handshake_failed"))
  c.cleanups();old.awaitTermination();check(old.liveWorkers()==0 && old.aapAudio.closed)
 }finally{HandlerThread.creationHook=null;c.close()}
 println("PASS partial worker creation exception signals handshake latch and cleans previously created workers")
}
fun interruptedTerminationControl()=runBlocking{
 val c=CommManager();val conn=checkNotNull(c.network())
 val entered=CountDownLatch(1);val proceed=CountDownLatch(1);val done=CountDownLatch(1)
 val errors=ConcurrentLinkedQueue<Throwable>();var interruptPreserved=false
 AapTransport.handshakeHook={entered.countDown();await(proceed)}
 val starter=Thread{try{runBlocking{c.startHandshake()}}catch(t:Throwable){errors.add(t)}}
 starter.start();await(entered)
 c.disconnect(sendByeBye=false,honorKillOnDisconnect=false)
 val cleaner=Thread{try{c.cleanups();interruptPreserved=Thread.currentThread().isInterrupted}catch(t:Throwable){errors.add(t)}finally{done.countDown()}}
 cleaner.start()
 try{
  val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
  while(!conn.closed && System.nanoTime()<deadline)Thread.sleep(1)
  check(conn.closed);cleaner.interrupt()
  check(!done.await(100,TimeUnit.MILLISECONDS))
 }finally{proceed.countDown();starter.join(5000);cleaner.join(5000);AapTransport.handshakeHook=null}
 check(!starter.isAlive && !cleaner.isAlive && errors.isEmpty()){errors.toString()}
 check(interruptPreserved && c.failures.isEmpty() && c.state is ConnectionState.Disconnected)
 c.close();println("PASS interrupt does not bypass handshake/termination barrier; interrupt status restored; no retired failure report")
}
fun main(){
 providedMain()
 failedHandshakeControls();partialStartupExceptionControl();interruptedTerminationControl()
 workerQuitNoTailControl()
 latePhysicalPublication()
 workerQuitBulkTail()
 lateRegisteredResults();destroyAfterCandidateRegistration();destroyDuringCandidateConstruction();
 usbOpenCancellation()
 println("ALL EXTENDED TRANSPORT LIFECYCLE CHECKS PASSED")
}
'''.replace('// BULK METHOD',process)
extra += r'''
fun lateRegisteredResults()=runBlocking {
 for (outcome in listOf("success","false","throw")) for(replace in listOf(false,true)) {
  val c=CommManager();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
  val connecting=CountDownLatch(1);val resume=CountDownLatch(1);val errors=ConcurrentLinkedQueue<Throwable>()
  SocketProjectionConnection.created.clear()
  SocketProjectionConnection.connectionHook={connecting.countDown();await(resume);when(outcome){"throw"->throw IllegalStateException("late failure");"false"->false;else->true}}
  val opener=Thread {try {runBlocking {c.connectNext()}} catch(t:Throwable){errors.add(t)}}
  opener.start();await(connecting);val old=checkNotNull(c.network())
  c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups();c.awaitCleanup()
  check(old.closed && c.state is ConnectionState.Disconnected)
  check(!(c.state as ConnectionState.Disconnected).hadPhysicalConnection)
  SocketProjectionConnection.connectionHook=null
  if(replace){c.connectNext();c.startHandshake();c.startReading()}
  val fresh=c.network();val freshOwner=c.nullableOwner();val expected=c.state
  resume.countDown();opener.join(5000)
  check(!opener.isAlive && errors.isEmpty()){errors.toString()}
  check(c.network()===fresh && c.nullableOwner()===freshOwner && c.state==expected)
  check(c.failures.isEmpty() && old.closed)
  if(fresh!=null)check(!fresh.closed)
  c.close()
 }
 println("PASS additional registered physical results: late success/false/exception preserve cancellation and replacement (6 schedules)")
}
fun destroyAfterCandidateRegistration()=runBlocking {
 val c=CommManager();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
 val entered=CountDownLatch(1);val resume=CountDownLatch(1);val errors=ConcurrentLinkedQueue<Throwable>()
 SocketProjectionConnection.connectionHook={entered.countDown();await(resume);true}
 val opener=Thread{try{runBlocking{c.connectNext()}}catch(t:Throwable){errors.add(t)}}
 opener.start();await(entered);val old=checkNotNull(c.network())
 c.destroy();check(c.state is ConnectionState.Disconnected && c.requested() && old.closed)
 SocketProjectionConnection.connectionHook=null;resume.countDown();opener.join(5000)
 check(!opener.isAlive && errors.isEmpty()){errors.toString()}
 check(c.state is ConnectionState.Disconnected && c.network()==null && old.closed)
 c.close();println("PASS destroy control: registered physical candidate is cancelled and late success cannot publish")
}
fun destroyDuringCandidateConstruction()=runBlocking {
 val c=CommManager();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
 val constructing=CountDownLatch(1);val resume=CountDownLatch(1);val errors=ConcurrentLinkedQueue<Throwable>()
 SocketProjectionConnection.created.clear()
 SocketProjectionConnection.constructionHook={constructing.countDown();await(resume)}
 val opener=Thread {try {runBlocking {c.connectNext()}} catch(t:Throwable){errors.add(t)}}
 opener.start();await(constructing);val old=checkNotNull(SocketProjectionConnection.created.peek())
 check(c.state is ConnectionState.Connecting && c.network()==null && !c.requested())
 // AapService's stop path only calls disconnect() for isConnected, which excludes Connecting.
 // onDestroy then calls this exact full production destroy() method.
 c.destroy()
 check(c.state is ConnectionState.Disconnected && c.requested())
 SocketProjectionConnection.constructionHook=null;resume.countDown();opener.join(5000)
 check(!opener.isAlive && errors.isEmpty()){errors.toString()}
 check(c.state is ConnectionState.Disconnected && c.network()==null && old.closed && c.requested())
 c.connectNext();c.startHandshake();c.startReading();check(c.state is ConnectionState.TransportStarted)
 println("PASS destroy before candidate publication cancels the old attempt and closes its late candidate")
 c.close()
}
'''
extra += r'''
object UsbDeviceCompat{fun getUniqueName(d:UsbDevice)="usb-fixture";fun usbManager(c:Context)=c.getSystemService(Context.USB_SERVICE) as UsbManager}
object UsbBridge{ @Volatile var afterConnect:(()->Unit)?=null }
open class StandardUsbProjectionConnection(m:UsbManager,d:UsbDevice):ProjectionConnection(){
 private val actual=com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection(m,d)
 override fun connect():Boolean {val r=runBlocking{actual.connect()};UsbBridge.afterConnect?.invoke();return r}
 override fun disconnect(){super.disconnect();actual.disconnect()}
}
class LibusbProjectionConnection(m:UsbManager,d:UsbDevice):StandardUsbProjectionConnection(m,d)
fun usbOpenCancellation()=runBlocking {
 for(race in listOf(false,true)) {
  val c=CommManager();c.disconnect(sendByeBye=false,honorKillOnDisconnect=false);c.cleanups()
  val opened=CountDownLatch(1);val resumeOpen=CountDownLatch(1);val resultReady=CountDownLatch(1);val resumeResult=CountDownLatch(1)
  val errors=ConcurrentLinkedQueue<Throwable>();val handle=UsbDeviceConnection()
  c.context.usb.openHook={opened.countDown();await(resumeOpen);handle}
  UsbBridge.afterConnect={resultReady.countDown();await(resumeResult)}
  val opener=Thread {try{runBlocking{c.connectDevice(UsbDevice())}}catch(t:Throwable){errors.add(t)}}
  opener.start();await(opened)
  if(!race){resumeOpen.countDown();await(resultReady)}
  c.disconnect(sendByeBye=false,honorKillOnDisconnect=false)
  val cleaner=Thread{try{c.cleanups()}catch(t:Throwable){errors.add(t)}}
  cleaner.start()
  if(race){
   val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
   while(cleaner.state!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.sleep(1)
   check(cleaner.state==Thread.State.BLOCKED){"cleanup did not block on USB stateLock"}
   resumeOpen.countDown();await(resultReady)
  }
  cleaner.join(5000);check(!cleaner.isAlive)
  resumeResult.countDown();opener.join(5000);UsbBridge.afterConnect=null
  check(!opener.isAlive && errors.isEmpty()){errors.toString()}
  check(c.state is ConnectionState.Disconnected && c.network()==null)
  if(race){
   check(handle.closes.get()>0){"unexpected close count: ${handle.closes.get()}"}
   check(handle.releases.get()>0)
   println("PASS real StandardUsb + manager: cancelled in-flight open explicitly closes its late handle; closeCount=${handle.closes.get()}, releaseCount=${handle.releases.get()}, manager=Disconnected")
  }else{
   check(handle.closes.get()>0)
   println("PASS StandardUsb control: cancellation after open publication explicitly closes handle")
  }
  c.close()
 }
}
'''
# Execute the service's actual action branches with the real extracted manager methods.
service=(base/'aap/AapService.kt').read_text()
actions=['ACTION_STOP_WIRELESS', 'ACTION_CANCEL_WIRELESS', 'ACTION_DISCONNECT']
action_bodies=[extract(service, '            '+name) for name in actions]
extra += r'''
class SettingsActionFixture(val commManager:CommManager) {
 private val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
 private val intent:android.content.Intent?=null
 private val EXTRA_USB_ATTEMPT="usb";private val START_STICKY=1
 private var wirelessRearmPendingForSettings=false
 private var usbCheckPendingForSettings=false;private var bluetoothLaunchPendingForSettings=false
 private class Wifi {fun stop(){};fun stopForUser(){}}
 private class Usb {fun isSwitchingToProjection()=false;fun stopForUser(){}}
 private val wifiLauncherManager=Wifi();private val usbLauncherManager=Usb()
 fun action(action:String):Int {when(action) {
''' + '\n'.join(body.replace(name, '"'+name+'"',1).replace('CommManager.ConnectionState', 'ConnectionState') for name,body in zip(actions,action_bodies)) + r'''
 };return START_STICKY}
}
fun settingsActionCancellation()=runBlocking {
 for(action in listOf("ACTION_STOP_WIRELESS","ACTION_CANCEL_WIRELESS","ACTION_DISCONNECT")) {
  for(cancelAfterCleanup in listOf(false,true)) {
   val c=CommManager()
   c.disconnect(isUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART)
   val saved=c.state as ConnectionState.Disconnected
   if(cancelAfterCleanup){c.cleanups();c.awaitCleanup()}
   SettingsActionFixture(c).action(action)
   check(!saved.acceptsSettingsRestart(c.state))
   c.cleanups();c.awaitCleanup()
   SocketProjectionConnection.created.clear()
   c.connectSaved(saved)
   check(SocketProjectionConnection.created.isEmpty()){"cancelled Save opened a socket: $action"}
   check(c.state===saved);c.close()
  }
 }
 println("PASS service stop/cancel/disconnect revoke pending Save before and after teardown")
}
'''
extra += r"""
fun terminalRouteSnapshots()=runBlocking {
 for(loopback in listOf(false,true)) for(route in listOf("disconnect","transport","link-loss","destroy")) {
  val c=CommManager()
  if(loopback)c.connectSelf() else c.connectNext()
  check(c.state is ConnectionState.Connected && c.isLoopbackSession==loopback)
  when(route) {
   "disconnect" -> c.disconnect(isUserExit=false,honorKillOnDisconnect=false)
   "transport" -> { c.startHandshake();checkNotNull(c.owner().onQuit).invoke(false) }
   "link-loss" -> c.disconnectForLinkLoss(1)
   "destroy" -> c.destroy()
  }
  val ended=c.state as ConnectionState.Disconnected
  check(ended.hadPhysicalConnection && ended.wasLoopbackSession==loopback){"lost retiring route: $route"}
  c.cleanups()
  check(c.network()==null && !c.isLoopbackSession)
  check(ended.hadPhysicalConnection && ended.wasLoopbackSession==loopback){"cleanup changed route snapshot: $route"}
  c.close()
 }
 println("PASS real disconnect, transport quit, link loss and destroy preserve terminal loopback snapshot after cleanup")
}
"""
extra += r"""
fun failedAttemptDoesNotInheritPhysicalSuccess()=runBlocking {
 for(outcome in listOf("false","throw")) {
  val c=CommManager();c.connectNext()
  c.disconnect(honorKillOnDisconnect=false);c.cleanups();c.awaitCleanup()
  check((c.state as ConnectionState.Disconnected).hadPhysicalConnection)
  SocketProjectionConnection.connectionHook={if(outcome=="throw")throw IllegalStateException("open failed") else false}
  try { c.connectNext() } finally { SocketProjectionConnection.connectionHook=null }
  val ended=c.state as ConnectionState.Disconnected
  check(!ended.hadPhysicalConnection){"failed open inherited the previous success"}
  c.cleanups();check(c.state===ended);c.close()
 }
 println("PASS new failed attempts do not inherit physical success from the previous connection")
}
"""
extra=extra.replace('fun main(){', 'fun main(){\n failedAttemptDoesNotInheritPhysicalSuccess();terminalRouteSnapshots();settingsActionCancellation()')
(OUT/'Probe.kt').write_text(manager+transport_fixture+support+extra)
(OUT/'Os.kt').write_text(r'''package android.os
import java.util.concurrent.*
object Build{object VERSION{var SDK_INT=33};object VERSION_CODES{const val LOLLIPOP=21}}
object Process{const val THREAD_PRIORITY_DISPLAY=-4;const val THREAD_PRIORITY_AUDIO=-16}
object SystemClock{fun sleep(ms:Long){Thread.sleep(ms)};fun elapsedRealtime()=System.nanoTime()/1000000}
class Looper{
 private val queue=LinkedBlockingQueue<Runnable>();@Volatile private var closing=false
 private val wake=Runnable{}
 @Synchronized fun offer(task:Runnable):Boolean{if(closing)return false;queue.add(task);return true}
 @Synchronized fun stop(){closing=true;queue.clear();queue.add(wake)}
 fun remove(task:Runnable){queue.remove(task)}
 fun loop(){while(true){val task=queue.take();if(closing)return;task.run()}}
 companion object{private val main=Looper();fun getMainLooper()=main}
}
class Handler(val looper:Looper,val callback:Any?=null){
 companion object{val delayed=ConcurrentLinkedQueue<Runnable>();@Volatile var removeHook:(()->Unit)?=null}
 fun post(task:Runnable)=looper.offer(task)
 fun postDelayed(task:Runnable,ms:Long):Boolean{delayed.add(task);return true}
 fun removeCallbacks(task:Runnable){removeHook?.invoke();looper.remove(task)}
 // The provided fixture has no actual poll Handler.Callback; bulk dispatch is posted explicitly.
 fun sendEmptyMessage(what:Int)=true
}
class HandlerThread(name:String,priority:Int):Thread(name){
 companion object{@Volatile var creationHook:((String)->Unit)?=null}
 val looper=Looper();init{creationHook?.invoke(name);isDaemon=true}
 override fun run(){looper.loop()}
 fun quit():Boolean{looper.stop();return true}
}
''')
(OUT/'Content.kt').write_text(r'''package android.content
import android.media.AudioManager
class Intent{
 fun getBooleanExtra(name:String,default:Boolean)=default
 var action:String?=null
 constructor(a:String){action=a};constructor(c:Context,k:Class<*>){}
 fun setPackage(s:String)=this
}
class Context{
 var usb=android.hardware.usb.UsbManager();val packageName="lifecycle";val broadcasts=mutableListOf<String?>();var stops=0
 fun sendBroadcast(i:Intent){broadcasts.add(i.action)};fun stopService(i:Intent):Boolean{stops++;return true}
 fun getSystemService(s:String):Any=when(s){"usb"->usb;"audio"->AudioManager();else->android.app.ActivityManager()}
 companion object{const val ACTIVITY_SERVICE="activity";const val USB_SERVICE="usb"}
}
''')
(OUT/'App.kt').write_text('package android.app\nobject Application{const val AUDIO_SERVICE="audio"}\nclass ActivityManager{val appTasks=emptyList<AppTask>();class AppTask{fun finishAndRemoveTask(){}}}\n')
(OUT/'Media.kt').write_text('package android.media\nclass AudioManager{fun interface OnAudioFocusChangeListener{fun onAudioFocusChange(i:Int)};companion object{const val STREAM_MUSIC=3;const val AUDIOFOCUS_GAIN=1}}\n')
(OUT/'Service.kt').write_text('package com.andrerinas.openheadunit.aap\nclass AapService{companion object{const val ACTION_STOP_SERVICE="stop";var killProcessOnDestroy=false}}\n')
(OUT/'Proto.kt').write_text(r'''package com.andrerinas.openheadunit.aap.protocol.proto
object Control{
 enum class ByeByeReason{USER_SELECTION}
 class ByeByeRequest{class Builder{fun setReason(r:ByeByeReason)=this;fun build()=ByeByeRequest()};companion object{fun newBuilder()=Builder()}}
 object ControlMsgType{const val MESSAGE_BYEBYE_REQUEST_VALUE=1}
}
''')
# Full, byte-for-byte production connection files; Android APIs only are doubled.
meta={}
for name in ['ProjectionConnection.kt','AbstractUsbProjectionConnection.kt','StandardUsbProjectionConnection.kt']:
 p=ROOT/'app/src/main/java/com/andrerinas/openheadunit/connection/projection'/name
 shutil.copy2(p,OUT/name);meta[str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
(OUT/'Usb.kt').write_text('''package android.hardware.usb
import java.util.concurrent.atomic.AtomicInteger
class UsbDevice{val interfaceCount=1;fun getInterface(i:Int)=UsbInterface()}
class UsbInterface
class UsbEndpoint
class UsbManager{var openHook:((UsbDevice)->UsbDeviceConnection?)?=null;fun hasPermission(d:UsbDevice)=true;fun openDevice(d:UsbDevice)=openHook?.invoke(d)?:UsbDeviceConnection()}
class UsbDeviceConnection{
 val closes=AtomicInteger();val releases=AtomicInteger()
 fun close(){closes.incrementAndGet()}
 fun releaseInterface(i:UsbInterface?):Boolean{releases.incrementAndGet();return true}
 fun claimInterface(i:UsbInterface?,force:Boolean)=true
 fun bulkTransfer(e:UsbEndpoint,b:ByteArray,n:Int,t:Int)=-1
}
''')
(OUT/'UsbCompat.kt').write_text('''package com.andrerinas.openheadunit.connection.usb
import android.hardware.usb.*
class UsbDeviceCompat(d:UsbDevice){companion object{fun usbManager(c:android.content.Context)=c.getSystemService(android.content.Context.USB_SERVICE) as UsbManager;fun getUniqueName(d:UsbDevice)="usb-fixture";fun selectEndpoints(i:UsbInterface)=UsbEndpoint() to UsbEndpoint()}}
''')
(OUT/'UsbLog.kt').write_text('''package com.andrerinas.openheadunit.utils
object AppLog{fun i(s:String){};fun w(s:String){};fun e(s:String){};fun e(t:Throwable){}}
''')
(OUT/'full-production-file-sha256.json').write_text(json.dumps(meta,indent=2))

(OUT/'extracted-method-sha256.json').write_text(json.dumps(methods,indent=2))
# Prefer the project's exact cached toolchain. Otherwise use the installed Kotlin distribution.
cache=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home()/".gradle")))/"caches/modules-2/files-2.1"
def cached(group,artifact,version="*"):
    found=sorted((cache/group/artifact).glob(version+"/*/*.jar"))
    if not found: raise FileNotFoundError(artifact)
    return found[0]
try:
    stdlib=cached("org.jetbrains.kotlin","kotlin-stdlib","1.9.22")
    annotations=cached("org.jetbrains","annotations","13.0")
    compile_jars=[cached("org.jetbrains.kotlin","kotlin-compiler-embeddable","1.9.22"),stdlib,
        cached("org.jetbrains.kotlin","kotlin-reflect","1.6.10"),
        cached("org.jetbrains.kotlin","kotlin-script-runtime","1.9.22"),
        cached("org.jetbrains.intellij.deps","trove4j"),annotations]
    runtime_jars=[stdlib,cached("org.jetbrains.kotlinx","kotlinx-coroutines-core-jvm","1.7.3"),annotations]
    toolchain="Gradle cache: Kotlin 1.9.22 / kotlinx-coroutines 1.7.3"
except FileNotFoundError:
    home=os.environ.get("KOTLIN_HOME")
    executable=shutil.which("kotlinc")
    if not home and not executable:
        raise SystemExit("Run Gradle tests to cache Kotlin 1.9.22, or set KOTLIN_HOME to a Kotlin distribution.")
    lib=(Path(home) if home else Path(executable).resolve().parents[1])/"lib"
    compile_jars=[lib/f for f in ["kotlin-compiler.jar","kotlin-stdlib.jar","kotlin-reflect.jar",
        "kotlin-script-runtime.jar","trove4j.jar","annotations-13.0.jar"]]
    runtime_jars=[lib/f for f in ["kotlin-stdlib.jar","kotlinx-coroutines-core-jvm.jar","annotations-13.0.jar"]]
    toolchain="Installed Kotlin distribution: "+str(lib.parent)
    for jar in compile_jars+runtime_jars:
        if not jar.is_file(): raise SystemExit("Missing dependency: "+str(jar))
print("TOOLCHAIN:",toolchain,flush=True)
(OUT/"toolchain.json").write_text(json.dumps({"description":toolchain,"jars":[
    {"path":str(j),"sha256":hashlib.sha256(j.read_bytes()).hexdigest()} for j in dict.fromkeys(compile_jars+runtime_jars)
]},indent=2))
cp=os.pathsep.join(map(str,runtime_jars));compiler=os.pathsep.join(map(str,compile_jars))
subprocess.run(["java","-cp",compiler,"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib","-no-reflect","-nowarn",
    "-classpath",cp,"-d",str(OUT/"probe.jar"),*map(str,sorted(OUT.glob("*.kt")))],check=True,timeout=45)
subprocess.run(["java","-cp",str(OUT/"probe.jar")+os.pathsep+cp,"lifecycle.ProbeKt"],check=True,timeout=35)

# Exercise real playback ownership as well when both independently mergeable changes are present.
subprocess.run(["python3", str(Path(__file__).with_name("audio-integration.py"))], check=True)

# Exercise actual Self launch jobs and the service cancel action on a queued Main dispatcher.
subprocess.run(["python3", str(Path(__file__).with_name("self-launch.py"))], check=True)
