package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
import com.andrerinas.openheadunit.connection.wifi.direct.RetainedNativeGroup
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionOperation
import org.junit.Test
import org.mockito.Mockito.*

class HelperSettingsRefreshTest {
    @Test fun `Save refresh neither recreates a joining group nor races pending creation`() {
        mockStatic(Looper::class.java).use { loopers ->
            val main = mock(Looper::class.java)
            loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
            loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
            for (groupExists in listOf(false, true)) for (creating in listOf(false, true)) {
                val manager = mock(WifiDirectManager::class.java, CALLS_REAL_METHODS)
                val platform = mock(WifiP2pManager::class.java)
                val channel = mock(WifiP2pManager.Channel::class.java)
                val owner = Any()
                fun set(name: String, value: Any) = WifiDirectManager::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(manager, value)
                set("manager", platform); set("channel", channel); set("isGroupCreatingOrCreated", creating)
                set("groupOwnership", owner)
                val operation = ConnectionOperation(0L, null)
                val comm = mock(CommManager::class.java)
                set("operation", operation); set("commManager", comm)
                `when`(comm.isCurrent(operation)).thenReturn(true)
                org.mockito.kotlin.doAnswer { invocation ->
                    @Suppress("UNCHECKED_CAST")
                    (invocation.arguments[0] as () -> Any?)()
                }.`when`(comm).withAppConnectionLifecycleLock<Any?>(org.mockito.kotlin.any())
                check(!RetainedNativeGroup.deferUntilReady(owner) { error("unexpected pending group removal") })
                doNothing().`when`(manager).makeVisible(operation)
                doAnswer {
                    it.getArgument<WifiP2pManager.GroupInfoListener>(1)
                        .onGroupInfoAvailable(if (groupExists) mock(WifiP2pGroup::class.java) else null)
                    null
                }.`when`(platform).requestGroupInfo(eq(channel), any())
                try {
                    manager.refreshHelperGroup(operation)
                    verify(manager, times(if (!groupExists && !creating) 1 else 0)).makeVisible(operation)
                    verify(platform, never()).removeGroup(any(), any())
                } finally {
                    RetainedNativeGroup.cancelWaiting(owner)
                }
            }
        }
    }
}
