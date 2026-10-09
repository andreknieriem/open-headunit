package com.andrerinas.openheadunit.aap
import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
class LateHandshakeRetryTest {
 @Test fun `late handshake leaves the disconnected owner and reconnect timer intact`() = runBlocking {
  val manager=mock(CommManager::class.java, CALLS_REAL_METHODS)
  val ended=CommManager.ConnectionState.Disconnected()
  val flow=MutableStateFlow<CommManager.ConnectionState>(ended)
  CommManager::class.java.getDeclaredField("_connectionState").apply{isAccessible=true}.set(manager,flow)
  CommManager::class.java.getDeclaredField("connectionState").apply{isAccessible=true}.set(manager,flow)
  val resume=CompletableDeferred<Unit>()
  var retried=0
  val retry=AutomaticReconnect(this,{flow.value},{false},{resume.await()})
  retry.schedule(ended,2000){retried++}
  yield()
  manager.startHandshake()
  assertSame(ended, flow.value)
  assertFalse(manager.isConnected)
  resume.complete(Unit)
  yield()
  // We expect the interrupted session to retain recovery when no newer attempt started.
  assertEquals("Late stale Error removed recovery although no new connection owns it",1,retried)
 }

 @Test fun `quit inside handshake retains the first terminal and one cleanup job`() = runBlocking {
  for (throwsAfterQuit in listOf(false, true)) {
   val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
   val transport = mock(AapTransport::class.java)
   val connection = mock(com.andrerinas.openheadunit.connection.projection.ProjectionConnection::class.java)
   val flow = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Connected)
   val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
   val queued = object : CoroutineDispatcher() {
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
   }
   val cleanupScope = CoroutineScope(SupervisorJob() + queued)
   fun field(name: String, value: Any?) {
    CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
   }
   field("transportLifecycleLock", Any())
   field("_connectionState", flow)
   field("connectionState", flow)
   field("_transport", transport)
   field("_connection", connection)
   field("_scope", cleanupScope)
   field("settings", mock(com.andrerinas.openheadunit.utils.Settings::class.java))
   var terminal: CommManager.ConnectionState? = null
   val quit = CommManager::class.java.getDeclaredMethod("transportedQuited", AapTransport::class.java, Boolean::class.javaPrimitiveType)
    .apply { isAccessible = true }
   `when`(transport.startHandshake(connection)).thenAnswer {
    quit.invoke(manager, transport, false)
    terminal = flow.value
    if (throwsAfterQuit) throw IllegalStateException("failure after quit")
    false
   }
   try {
    manager.startHandshake()
    assertNotNull(terminal)
    assertSame("Handshake reporting must not replace the terminal owner", terminal, flow.value)
    assertEquals("Only the winning terminal queues cleanup", 1, tasks.size)
   } finally { cleanupScope.cancel() }
  }
 }
}
