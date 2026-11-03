package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class P2pPersistentGroupPurgeTest {
    @Test
    fun `timeout retires a request before late group information arrives`() {
        val f = Fixture()
        f.start()
        f.expire()
        val verdict = f.done.single()
        f.emit()
        f.emit()
        f.expire()
        assertTrue(f.deleted.isEmpty())
        assertEquals(listOf(verdict), f.done)
    }

    @Test
    fun `duplicate group information starts only one batch before and after completion`() {
        val f = Fixture()
        f.start()
        f.emit()
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        f.callbacks[0].onSuccess()
        assertTrue(f.done.isEmpty())
        f.callbacks[1].onSuccess()
        f.emit()
        f.expire()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(1, f.done.size)
    }

    @Test
    fun `duplicate success and failure cannot answer for another deletion`() {
        for (firstSucceeds in listOf(true, false)) {
            val f = Fixture()
            f.start()
            f.emit()
            val first = f.callbacks[0]
            if (firstSucceeds) first.onSuccess() else first.onFailure(WifiP2pManager.BUSY)
            first.onSuccess()
            first.onFailure(WifiP2pManager.ERROR)
            assertTrue(f.done.isEmpty())
            f.callbacks[1].onSuccess()
            assertEquals(1, f.done.size)
        }
    }

    @Test
    fun `synchronous success then throw counts only once and preserves other cleanup`() {
        val f = Fixture()
        f.onDelete = { id, listener ->
            if (id == 0) {
                listener.onSuccess()
                listener.onFailure(WifiP2pManager.ERROR)
                throw IllegalStateException("after callback")
            }
        }
        f.start()
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        assertTrue(f.done.isEmpty())
        f.callbacks[1].onSuccess()
        assertEquals(1, f.done.size)
    }

    @Test
    fun `throw followed by late callbacks does not consume another deletion answer`() {
        val f = Fixture()
        f.onDelete = { id, _ -> if (id == 0) throw IllegalStateException("before callback") }
        f.start()
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        f.callbacks[0].onSuccess()
        f.callbacks[0].onFailure(WifiP2pManager.BUSY)
        assertTrue(f.done.isEmpty())
        f.callbacks[1].onSuccess()
        assertEquals(1, f.done.size)
    }

    @Test
    fun `request exception retires retained information listener`() {
        val f = Fixture()
        f.onRequest = { throw IllegalStateException("request refused") }
        f.start()
        val verdict = f.done.single()
        f.emit()
        f.expire()
        assertTrue(f.deleted.isEmpty())
        assertEquals(listOf(verdict), f.done)
    }

    @Test
    fun `request callback then throw retires pending deletion answers`() {
        val f = Fixture()
        f.onRequest = {
            f.emit()
            throw IllegalStateException("after information")
        }
        f.start()
        val verdict = f.done.single()
        f.callbacks.forEach { it.onSuccess() }
        f.emit()
        f.expire()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(listOf(verdict), f.done)
    }

    @Test
    fun `reentrant timeout stops dispatching remaining deletes and ignores late answers`() {
        val f = Fixture()
        f.onDelete = { _, listener ->
            f.expire()
            listener.onSuccess()
            throw IllegalStateException("after timeout")
        }
        f.start()
        f.emit()
        val verdict = f.done.single()
        f.emit()
        f.callbacks.single().onFailure(WifiP2pManager.BUSY)
        assertEquals(listOf(0), f.deleted)
        assertEquals(listOf(verdict), f.done)
    }

    @Test
    fun `synchronous reentrant information and completion preserve the entire batch`() {
        val f = Fixture()
        f.onRequest = { f.emit() }
        f.onDelete = { _, listener ->
            f.emit()
            assertTrue(f.done.isEmpty())
            listener.onSuccess()
        }
        f.onDone = {
            f.emit()
            f.expire()
        }
        f.start()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(1, f.done.size)
    }

    @Test
    fun `mixed metadata deletes only owned readable nonnegative IDs including zero`() {
        val f = Fixture(listOf(
            group(null), group(0), group(7, false, "SeLf"),
            group(9, false, "other"), group(10, false, null), group(-1), group(-2),
        ))
        f.onDelete = { _, listener -> listener.onSuccess() }
        f.start()
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(1, f.done.size)
    }

    @Test
    fun `timeout preserves its verdict when outstanding deletions answer later`() {
        val f = Fixture()
        f.start()
        f.emit()
        f.callbacks[0].onSuccess()
        f.expire()
        val verdict = f.done.single()
        f.callbacks.forEach {
            it.onSuccess()
            it.onFailure(WifiP2pManager.ERROR)
        }
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(listOf(verdict), f.done)
    }

    @Test
    fun `platform invocation can wait for a callback on another thread without holding a lock`() {
        val f = Fixture()
        val callbacksReturned = mutableListOf<Boolean>()
        f.onDelete = { _, listener ->
            val callback = Thread { listener.onSuccess() }.apply {
                isDaemon = true
                start()
            }
            callback.join(3000)
            callbacksReturned.add(!callback.isAlive)
        }
        f.start()
        f.emit()
        assertEquals(listOf(0, 7), f.deleted)
        assertEquals(listOf(true, true), callbacksReturned)
        assertEquals(1, f.done.size)
    }

    private class Fixture(groups: List<WifiP2pGroup> = listOf(group(0), group(7))) {
        val deleted = mutableListOf<Int>()
        val callbacks = mutableListOf<WifiP2pManager.ActionListener>()
        val done = mutableListOf<String>()
        var onRequest: () -> Unit = {}
        var onDelete: (Int, WifiP2pManager.ActionListener) -> Unit = { _, _ -> }
        var onDone: () -> Unit = {}
        private lateinit var timeout: Runnable
        private lateinit var info: Any
        private val infoClass = Class.forName("android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener")
        private val groupListClass = Class.forName("android.net.wifi.p2p.WifiP2pGroupList")
        private val groupList = mock(groupListClass, Answer { call ->
            if (call.method.name == "getGroupList") groups else RETURNS_DEFAULTS.answer(call)
        })
        private val handler = mock(Handler::class.java, Answer { call ->
            if (call.method.name == "postDelayed") {
                timeout = call.arguments[0] as Runnable
                true
            } else RETURNS_DEFAULTS.answer(call)
        })
        private val manager = mock(WifiP2pManager::class.java, Answer { call ->
            when (call.method.name) {
                "requestPersistentGroupInfo" -> {
                    info = call.arguments[1]
                    onRequest()
                    null
                }
                "deletePersistentGroup" -> {
                    val id = call.arguments[1] as Int
                    val listener = call.arguments[2] as WifiP2pManager.ActionListener
                    deleted.add(id)
                    callbacks.add(listener)
                    onDelete(id, listener)
                    null
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })

        fun start() {
            P2pPersistentGroupPurge.purge(
                manager, mock(WifiP2pManager.Channel::class.java), "self", handler, isCurrent = { true },
            ) {
                done.add(it)
                onDone()
            }
        }

        fun emit() {
            infoClass.getMethod("onPersistentGroupInfoAvailable", groupListClass).invoke(info, groupList)
        }

        fun expire() = timeout.run()
    }

    companion object {
        private fun group(id: Int?, isOwner: Boolean = true, address: String? = "self"): WifiP2pGroup =
            mock(WifiP2pGroup::class.java, Answer { call ->
                when (call.method.name) {
                    "getNetworkId" -> id ?: throw IllegalStateException("metadata unavailable")
                    "isGroupOwner" -> isOwner
                    "getOwner" -> address?.let { WifiP2pDevice().apply { deviceAddress = it } }
                    else -> RETURNS_DEFAULTS.answer(call)
                }
            })
    }
}
