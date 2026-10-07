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
}
