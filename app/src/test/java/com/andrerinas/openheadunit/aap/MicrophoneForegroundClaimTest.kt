package com.andrerinas.openheadunit.aap

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

/** Exercises the actual Service decision, rather than preselecting a mocked claim result. */
class MicrophoneForegroundClaimTest {
    @Test fun `service declines missing microphone type and can claim after permission or setting recovers`() {
        val sdk = Build.VERSION::class.java.getDeclaredField("SDK_INT").apply { isAccessible = true }
        val oldSdk = sdk.getInt(null)
        // SDK_INT is final in the host Android stub; restore its original value after this test.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val writeInt = unsafeClass.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        fun setSdk(value: Int) { writeInt.invoke(unsafe, base, offset, value) }
        val level = AppLog::class.java.getDeclaredField("cachedLogLevel").apply { isAccessible = true }
        val oldLevel = level.getInt(null)
        val resources = mutableListOf<AutoCloseable>()
        try {
            setSdk(34)
            level.setInt(null, Int.MAX_VALUE)
            resources.add(Mockito.mockStatic(SystemClock::class.java))
            val permission = Mockito.mockStatic(PermissionChecker::class.java).also { resources.add(it) }
            var allowed = true
            permission.`when`<Int> { PermissionChecker.checkSelfPermission(any(), eq(Manifest.permission.RECORD_AUDIO)) }
                .thenAnswer { if (allowed) PermissionChecker.PERMISSION_GRANTED else PermissionChecker.PERMISSION_DENIED }
            resources.add(Mockito.mockStatic(PendingIntent::class.java))
            resources.add(Mockito.mockConstruction(Intent::class.java, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)))
            val notification = mock<Notification>()
            resources.add(Mockito.mockConstruction(NotificationCompat.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
                whenever(builder.build()).thenReturn(notification)
            })
            val settings = mock<Settings>()
            whenever(settings.useHeadUnitMicrophone).thenReturn(true)
            val component = mock<AppComponent>()
            whenever(component.commManager).thenReturn(mock<CommManager>())
            val app = mock<App>()
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazyOf(component))
            val service = mock<AapService>()
            whenever(service.applicationContext).thenReturn(app)
            whenever(service.getString(any())).thenReturn("Microphone test")
            AapService::class.java.getDeclaredField("settings\$delegate").apply { isAccessible = true }
                .set(service, lazyOf(settings))
            val promote = AapService::class.java.getDeclaredMethod("promoteForMicrophone").apply { isAccessible = true }
            fun claim() = promote.invoke(service) as Boolean

            for (deniedBySetting in listOf(true, false)) {
                clearInvocations(service)
                allowed = deniedBySetting
                whenever(settings.useHeadUnitMicrophone).thenReturn(!deniedBySetting)
                assertFalse(claim())
                verify(service, never()).startForeground(any(), any(), any())

                allowed = true
                whenever(settings.useHeadUnitMicrophone).thenReturn(true)
                assertTrue(claim())
                val mask = argumentCaptor<Int>()
                verify(service).startForeground(eq(1), same(notification), mask.capture())
                assertTrue(mask.firstValue and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
            }
        } finally {
            resources.asReversed().forEach { it.close() }
            setSdk(oldSdk)
            level.setInt(null, oldLevel)
        }
    }
}
