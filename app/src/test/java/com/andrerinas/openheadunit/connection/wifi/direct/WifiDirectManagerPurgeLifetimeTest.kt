package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class WifiDirectManagerPurgeLifetimeTest {
    @Test
    fun `pending legacy rotation replaces a surviving owned group instead of adopting it`() {
        val f = Fixture()
        f.owner.rotateNativeIdentityNow()
        assertEquals(1, f.removals)
        assertTrue("The requested rename must reach stored-profile cleanup", f.purgeRequested)
        f.owner.stop()
    }

    @Test
    @Config(sdk = [28, 30])
    fun `ordinary reconnect keeps a surviving owned identity without purging`() {
        val f = Fixture(rotationPending = false)
        f.owner.startNativeAaQuietHost()
        assertEquals(0, f.removals)
        assertEquals(0, f.creates)
        assertTrue(!f.purgeRequested)
        f.owner.stop()
    }

    @Test
    fun `pending legacy rotation preserves an advertised endpoint until retirement`() {
        val f = Fixture(retirementOwed = true)
        f.owner.rotateNativeIdentityNow()
        assertEquals(0, f.removals)
        assertEquals(0, f.creates)
        assertTrue(!f.purgeRequested)
        f.owner.stop()
    }

    @Test
    fun `stop before stored profiles arrive prevents deletion by the retired manager`() {
        val f = Fixture()
        f.startRotation()
        f.owner.stop()
        f.emitProfiles()
        assertTrue("A stopped rotation must not forget the next session's saved network", f.deleted.isEmpty())
        assertEquals(0, f.creates)
    }

    @Test
    fun `stop during a deletion prevents dispatching the rest of the retired batch`() {
        val f = Fixture()
        f.onDelete = { _, listener ->
            f.owner.stop()
            listener.onSuccess()
        }
        f.startRotation()
        f.emitProfiles()
        assertEquals(listOf(0), f.deleted)
        assertEquals(0, f.creates)
    }

    @Test
    fun `stop on another thread during deletion returns without allowing another dispatch`() {
        val f = Fixture()
        val stopsReturned = mutableListOf<Boolean>()
        f.onDelete = { _, listener ->
            val stop = Thread { f.owner.stop() }.apply {
                isDaemon = true
                start()
            }
            stop.join(3000)
            stopsReturned.add(!stop.isAlive)
            listener.onSuccess()
        }
        f.startRotation()
        f.emitProfiles()
        assertEquals(listOf(true), stopsReturned)
        assertEquals(listOf(0), f.deleted)
        assertEquals(0, f.creates)
    }

    private class Fixture(rotationPending: Boolean = true, retirementOwed: Boolean = false) {
        val deleted = mutableListOf<Int>()
        var creates = 0
        var removals = 0
        var purgeRequested = false
        private val kept = StoredP2pIdentity("DIRECT-AB-Car", "abcdefgh1234")
        private val liveGroup = mock(WifiP2pGroup::class.java, Answer { call ->
            when (call.method.name) {
                "isGroupOwner" -> true
                "getNetworkName" -> kept.networkName
                "getPassphrase" -> kept.passphrase
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
        var onDelete: (Int, WifiP2pManager.ActionListener) -> Unit = { _, _ -> }
        private lateinit var info: Any
        private val infoClass = Class.forName("android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener")
        private val groupListClass = Class.forName("android.net.wifi.p2p.WifiP2pGroupList")
        private val groups = listOf(0, 7).map { id ->
            mock(WifiP2pGroup::class.java, Answer { call ->
                when (call.method.name) {
                    "getNetworkId" -> id
                    "isGroupOwner" -> true
                    else -> RETURNS_DEFAULTS.answer(call)
                }
            })
        }
        private val groupList = mock(groupListClass, Answer { call ->
            if (call.method.name == "getGroupList") groups else RETURNS_DEFAULTS.answer(call)
        })
        private val platform = mock(WifiP2pManager::class.java, Answer { call ->
            when (call.method.name) {
                "removeGroup" -> {
                    removals++
                    (call.arguments[1] as WifiP2pManager.ActionListener).onSuccess()
                    null
                }
                "requestPersistentGroupInfo" -> {
                    purgeRequested = true
                    info = call.arguments[1]
                    null
                }
                "deletePersistentGroup" -> {
                    val id = call.arguments[1] as Int
                    deleted.add(id)
                    onDelete(id, call.arguments[2] as WifiP2pManager.ActionListener)
                    null
                }
                "createGroup" -> {
                    creates++
                    null
                }
                "requestGroupInfo" -> {
                    val listener = call.arguments[1] as WifiP2pManager.GroupInfoListener
                    // Answer the adopt-or-create query, not credential delivery after adoption.
                    if (listener !is WifiDirectManager) listener.onGroupInfoAvailable(liveGroup)
                    null
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        })
        val owner: WifiDirectManager

        init {
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            val settings = mock(Settings::class.java)
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(app)
            `when`(context.packageManager).thenReturn(mock(PackageManager::class.java))
            `when`(context.getSystemService(Context.WIFI_P2P_SERVICE)).thenReturn(platform)
            val wifi = mock(WifiManager::class.java)
            `when`(wifi.isWifiEnabled).thenReturn(true)
            `when`(app.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazyOf(component))
            `when`(component.settings).thenReturn(settings)
            `when`(settings.wifiDirectStableIdentity).thenReturn(true)
            `when`(settings.wifiDirectRotationPending).thenReturn(rotationPending)
            `when`(settings.wifiDirectGroupIdentity).thenReturn(kept)
            if (retirementOwed) `when`(settings.wifiDirectAdvertisedIdentity).thenReturn(kept)
            owner = WifiDirectManager(context)
            // Keep radio/discovery setup out of this callback-lifetime test.
            WifiDirectManager::class.java.getDeclaredField("channel").apply { isAccessible = true }
                .set(owner, mock(WifiP2pManager.Channel::class.java))
            WifiDirectManager::class.java.getDeclaredField("isReceiverRegistered").apply { isAccessible = true }
                .set(owner, true)
        }

        fun startRotation() {
            WifiDirectManager::class.java.getDeclaredMethod("recreateNativeGroup", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(owner, true)
        }

        fun emitProfiles() {
            infoClass.getMethod("onPersistentGroupInfoAvailable", groupListClass).invoke(info, groupList)
        }
    }
}
