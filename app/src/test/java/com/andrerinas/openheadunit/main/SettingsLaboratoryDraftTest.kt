package com.andrerinas.openheadunit.main

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import android.os.Parcel
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SettingsLaboratoryDraftTest {
    private fun field(name: String) = SettingsFragment::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `recreated Laboratory parent restores draft and nullable relay switch`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val manager = activity.get().supportFragmentManager
            for (relay in listOf(null, false, true)) {
                val original = SettingsFragment()
                // The parent sits on the back stack without a view while Laboratory is open.
                manager.beginTransaction().add(original, "parent")
                    .setMaxLifecycle(original, Lifecycle.State.CREATED).commitNow()
                val parent = field("pending").get(original) as SettingsPendingCopy
                parent.fpsLimit = 60
                parent.nativePokeBtMacs = setOf("00:11:22:33:44:55")
                parent.wifiDirectGroupIdentity = StoredP2pIdentity("DIRECT-ab-Test", "testPassword")
                field("pendingEsp32RelayEnabled").set(original, relay)
                field("preservePendingForLaboratory").setBoolean(original, true)
                val state = checkNotNull(manager.saveFragmentInstanceState(original))
                manager.beginTransaction().remove(original).commitNow()

                val restored = SettingsFragment()
                val parcel = Parcel.obtain()
                try {
                    parcel.writeParcelable(state, 0)
                    parcel.setDataPosition(0)
                    restored.setInitialSavedState(parcel.readParcelable<Fragment.SavedState>(Fragment::class.java.classLoader))
                } finally {
                    parcel.recycle()
                }
                manager.beginTransaction().add(restored, "parent")
                    .setMaxLifecycle(restored, Lifecycle.State.CREATED).commitNow()

                val fps = SettingsFragment::class.java.getDeclaredMethod("getPendingFpsLimit").apply { isAccessible = true }
                val restoredDraft = field("pending").get(restored) as SettingsPendingCopy
                assertEquals(60, fps.invoke(restored))
                assertEquals(parent.nativePokeBtMacs, restoredDraft.nativePokeBtMacs)
                assertEquals(parent.wifiDirectGroupIdentity, restoredDraft.wifiDirectGroupIdentity)
                assertEquals(relay, field("pendingEsp32RelayEnabled").get(restored))
                assertTrue(field("preservePendingForLaboratory").getBoolean(restored))
                manager.beginTransaction().remove(restored).commitNow()
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
