package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
// No TLS here. Robolectric's Conscrypt provider is process-wide and breaks later plain-JVM TLS tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class WifiDirectManagerPurgeLifetimeTest {
    @Test
    fun `pending legacy rotation replaces a surviving owned group instead of adopting it`() {
        val f = Fixture()
        f.owner.rotateNativeIdentityNow(f.command)
        assertEquals(1, f.removals)
        assertTrue("The requested rename must reach stored-profile cleanup", f.purgeRequested)
        f.owner.stop()
    }

    @Test
    @Config(sdk = [28, 30])
    fun `ordinary reconnect keeps a surviving owned identity without purging`() {
        val f = Fixture(rotationPending = false)
        f.owner.startNativeAaQuietHost(f.command)
        assertEquals(0, f.removals)
        assertEquals(0, f.creates)
        assertTrue(!f.purgeRequested)
        f.owner.stop()
    }

    @Test
    fun `pending legacy rotation preserves an advertised endpoint until retirement`() {
        val f = Fixture(retirementOwed = true)
        f.owner.rotateNativeIdentityNow(f.command)
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
            // Retained group ownership is Main-ordered. Keep this test's cross-thread
            // platform reentry while giving that worker its own scoped Main boundary.
            val main = Looper.getMainLooper()
            val stop = Thread {
                mockStatic(Looper::class.java).use { loopers ->
                    loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
                    loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
                    f.owner.stop()
                }
            }.apply {
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

    @Test
    fun `withdrawn command before stored profiles arrive prevents deletion without manager stop`() {
        val f = Fixture()
        try {
            f.startRotation()
            f.commandCurrent = false
            f.emitProfiles()
            assertTrue("A withdrawn command must not delete stored profiles", f.deleted.isEmpty())
            assertEquals(0, f.creates)
        } finally {
            f.owner.stop()
        }
    }

    @Test
    fun `withdrawn arm before stored profiles arrive prevents deletion without manager stop`() {
        val f = Fixture()
        try {
            f.startRotation()
            f.armCurrent = false
            f.commandCurrent = false
            f.emitProfiles()
            assertTrue("A withdrawn manager arm must not delete stored profiles", f.deleted.isEmpty())
            assertEquals(0, f.creates)
        } finally {
            f.owner.stop()
        }
    }

    @Test
    fun `withdrawn command during deletion prevents the next dispatch without manager stop`() {
        val f = Fixture()
        try {
            f.onDelete = { _, listener ->
                f.commandCurrent = false
                listener.onSuccess()
            }
            f.startRotation()
            f.emitProfiles()
            assertEquals("A withdrawn command must retire the remaining delete batch", listOf(0), f.deleted)
            assertEquals(0, f.creates)
        } finally {
            f.owner.stop()
        }
    }

    private class Fixture(rotationPending: Boolean = true, retirementOwed: Boolean = false) {
        val deleted = mutableListOf<Int>()
        private val arm = ConnectionOperation(0L, null)
        val command = ConnectionOperation(0L, CommManager.ConnectionState.Disconnected(
            reason = CommManager.DisconnectReason.SETTINGS_RESTART,
        ))
        var armCurrent = true
        var commandCurrent = true
        private var groupQueries = 0
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
                    if (groupQueries++ == 0) listener.onGroupInfoAvailable(liveGroup)
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
            val comm = mock(CommManager::class.java)
            `when`(component.commManager).thenReturn(comm)
            `when`(comm.isCurrent(arm)).thenAnswer { armCurrent }
            `when`(comm.isCurrent(command)).thenAnswer { commandCurrent }
            org.mockito.kotlin.doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[0] as () -> Any?)()
            }.`when`(comm).withAppConnectionLifecycleLock<Any?>(org.mockito.kotlin.any())
            `when`(settings.wifiDirectStableIdentity).thenReturn(true)
            `when`(settings.wifiDirectRotationPending).thenReturn(rotationPending)
            `when`(settings.wifiDirectGroupIdentity).thenReturn(kept)
            if (retirementOwed) `when`(settings.wifiDirectAdvertisedIdentity).thenReturn(kept)
            owner = WifiDirectManager(context, arm)
            // Keep radio/discovery setup out of this callback-lifetime test.
            WifiDirectManager::class.java.getDeclaredField("channel").apply { isAccessible = true }
                .set(owner, mock(WifiP2pManager.Channel::class.java))
            WifiDirectManager::class.java.getDeclaredField("isReceiverRegistered").apply { isAccessible = true }
                .set(owner, true)
        }

        fun startRotation() {
            // This private continuation normally follows the public bring-up's owner claim.
            val groupOwner = WifiDirectManager::class.java.getDeclaredField("groupOwnership")
                .apply { isAccessible = true }.get(owner)
            check(!RetainedNativeGroup.deferUntilReady(groupOwner) { error("unexpected pending group removal") })
            WifiDirectManager::class.java.getDeclaredMethod(
                "recreateNativeGroup", Boolean::class.javaPrimitiveType, ConnectionOperation::class.java,
            ).apply { isAccessible = true }.invoke(owner, true, command)
        }

        fun emitProfiles() {
            infoClass.getMethod("onPersistentGroupInfoAvailable", groupListClass).invoke(info, groupList)
        }
    }
}
