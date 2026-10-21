package com.andrerinas.openheadunit.aap.bluetooth

import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import org.junit.Assert.*
import org.junit.Test

class BluetoothPairingRequestsTest {
    @Test fun retriesReplaceOldQueuedRequestsAndCloseRetiresAllWork() {
        val tasks = ArrayDeque<() -> Unit>()
        val seen = mutableListOf<String>()
        val inbox = BluetoothPairingRequests({ 0 }, { tasks.add(it); true }) { request, current ->
            if (current()) seen += request.address
        }
        repeat(100) { inbox.offer("phone-$it", 2) }
        assertEquals(1, tasks.size)
        tasks.removeFirst()(); assertEquals(listOf("phone-99"), seen)
        inbox.offer("late", 2); inbox.close(); tasks.removeFirst()()
        inbox.offer("closed", 2)
        assertEquals(1, seen.size); assertTrue(tasks.isEmpty())
    }

    @Test fun aRetryDuringBinderWaitInvalidatesTheFirstReplyAndOnlyQueriesTheLatest() {
        val tasks = ArrayDeque<() -> Unit>()
        var clock = 0L
        var queries = 0
        val replies = mutableListOf<Pair<MessageStatus, Boolean>>()
        lateinit var inbox: BluetoothPairingRequests
        val session = BluetoothPairingSession(object : BluetoothPairingSession.Port {
            override fun now() = clock
            override fun bond(address: String): BluetoothPairingSession.Bond {
                queries++
                if (queries == 1) {
                    // Simulate the receive thread enqueueing retries during a slow Binder call.
                    clock += 120_001
                    repeat(30) { inbox.offer(address, 2) }
                }
                return BluetoothPairingSession.Bond.BONDED
            }
            override fun reply(status: MessageStatus, alreadyPaired: Boolean) { replies += status to alreadyPaired }
            override fun authentication(value: String) = Unit
        })
        inbox = BluetoothPairingRequests({ clock }, { tasks.add(it); true }) { request, current ->
            session.request(request.address, request.method, request.receivedAt, current)
        }
        inbox.offer("12:34:56:78:9A:BC", 2)
        tasks.removeFirst()(); assertTrue(replies.isEmpty()); assertEquals(1, tasks.size)
        tasks.removeFirst()()
        assertEquals(2, queries); assertEquals(listOf(MessageStatus.STATUS_SUCCESS to true), replies)
    }
}
