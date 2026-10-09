package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import com.andrerinas.openheadunit.connection.wifi.direct.WifiDirectManager
import org.junit.Test
import org.mockito.Mockito.*

class HelperSettingsRefreshTest {
    @Test fun `Save refresh neither recreates a joining group nor races pending creation`() {
        for (groupExists in listOf(false, true)) for (creating in listOf(false, true)) {
            val manager = mock(WifiDirectManager::class.java, CALLS_REAL_METHODS)
            val platform = mock(WifiP2pManager::class.java)
            val channel = mock(WifiP2pManager.Channel::class.java)
            fun set(name: String, value: Any) = WifiDirectManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            set("manager", platform); set("channel", channel); set("isGroupCreatingOrCreated", creating)
            doNothing().`when`(manager).makeVisible()
            doAnswer {
                it.getArgument<WifiP2pManager.GroupInfoListener>(1)
                    .onGroupInfoAvailable(if (groupExists) mock(WifiP2pGroup::class.java) else null)
                null
            }.`when`(platform).requestGroupInfo(eq(channel), any())
            manager.refreshHelperGroup()
            verify(manager, times(if (!groupExists && !creating) 1 else 0)).makeVisible()
            verify(platform, never()).removeGroup(any(), any())
        }
    }
}
