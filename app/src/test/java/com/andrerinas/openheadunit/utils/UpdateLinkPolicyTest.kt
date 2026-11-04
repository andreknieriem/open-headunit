package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateLinkPolicyTest {

    private val base = "https://github.com/andreknieriem/open-headunit/releases"
    private val page = "$base/tag/v.3.5.0-beta3"
    private val apk = "$base/download/v.3.5.0-beta3/com.andrerinas.headunitrevived_3.5.0-beta3.apk"
    private val debugApk = "$base/download/v.3.5.0-beta3/com.andrerinas.headunitrevived_3.5.0-beta3_debug.apk"

    @Test
    fun picksTheReleaseApkAndSkipsTheDebugBuild() {
        val assets = listOf(
            "com.andrerinas.headunitrevived_3.5.0-beta3_debug.apk" to debugApk,
            "com.andrerinas.headunitrevived_3.5.0-beta3.apk" to apk,
        )
        assertEquals(apk, UpdateLinkPolicy.pickApk(assets))
    }

    @Test
    fun noReleaseApkGivesNull() {
        assertNull(UpdateLinkPolicy.pickApk(emptyList()))
        assertNull(UpdateLinkPolicy.pickApk(listOf("notes.txt" to "$base/notes.txt")))
        assertNull(UpdateLinkPolicy.pickApk(listOf("x_debug.apk" to debugApk)))
    }

    @Test
    fun apkIsPrimaryAndReleasePageIsSecondary() {
        assertEquals(
            UpdateLinkPolicy.Choice.APK to UpdateLinkPolicy.Choice.RELEASE_PAGE,
            UpdateLinkPolicy.choices(apk)
        )
    }

    @Test
    fun noApkLeavesOnlyTheReleasePage() {
        assertEquals(UpdateLinkPolicy.Choice.RELEASE_PAGE to null, UpdateLinkPolicy.choices(null))
    }

    private fun release(tag: String, vararg assets: String) = UpdateLinkPolicy.Release(
        tag, "$base/tag/$tag", assets.map { it to "$base/download/$tag/$it" }
    )

    @Test
    fun newestReleaseWithAnApkIsOffered() {
        val got = UpdateLinkPolicy.newestInstallable(listOf(
            release("v.3.5.0", "com.andrerinas.headunitrevived_3.5.0.apk"),
            release("v.4.0.0-alpha", "com.andrerinas.headunitrevived_4.0.0-alpha.apk"),
        ))!!
        assertEquals("4.0.0-alpha", got.tag)
        assertEquals("$base/tag/v.4.0.0-alpha", got.pageUrl)
        assertEquals("$base/download/v.4.0.0-alpha/com.andrerinas.headunitrevived_4.0.0-alpha.apk", got.apkUrl)
    }

    @Test
    fun releaseStillUploadingItsApkIsSkippedForTheNewestInstallable() {
        val got = UpdateLinkPolicy.newestInstallable(listOf(
            release("v.4.0.1"),
            release("v.4.0.0", "com.andrerinas.headunitrevived_4.0.0.apk"),
        ))!!
        assertEquals("4.0.0", got.tag)
    }

    @Test
    fun debugApkAloneDoesNotMakeAReleaseInstallable() {
        val got = UpdateLinkPolicy.newestInstallable(listOf(
            release("v.4.0.1", "com.andrerinas.headunitrevived_4.0.1_debug.apk"),
            release("v.4.0.0", "com.andrerinas.headunitrevived_4.0.0.apk"),
        ))!!
        assertEquals("4.0.0", got.tag)
    }

    @Test
    fun noInstallableReleaseGivesNull() {
        assertNull(UpdateLinkPolicy.newestInstallable(listOf(release("v.4.0.1"), release("2.1.0-artefacts"))))
        assertNull(UpdateLinkPolicy.newestInstallable(listOf(release("nightly", "app.apk"))))
    }
}
