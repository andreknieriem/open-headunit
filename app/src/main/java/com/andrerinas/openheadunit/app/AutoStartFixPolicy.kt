package com.andrerinas.openheadunit.app

/**
 * Phase two of #1122: the fixes that need root or the unit's ADB but leave /system alone. The
 * commands live here so the API cut-offs are tested on the JVM; nothing in this file runs them.
 */
object AutoStartFixPolicy {

    const val DURASPEED_SETTING = "setting.duraspeed.enabled"

    /** Either property set to 1 means the MediaTek background killer is built in. */
    val DURASPEED_PROPS = listOf("persist.duraspeed.support", "ro.mtk_bg_power_saving_support")

    private const val API_M = 23
    private const val API_O = 26
    private const val API_P = 28

    fun duraSpeedSupported(propValues: List<String>): Boolean = propValues.any { it.trim() == "1" }

    /**
     * Writes 1 and then 0 to the setting in both tables. DuraSpeed only acts when the value
     * changes, and it comes back on after every restart, so this runs on each boot.
     */
    fun duraSpeedOffCommand(): String = listOf("global", "system").joinToString("; ") { table ->
        "settings put $table $DURASPEED_SETTING 1; sleep 1; settings put $table $DURASPEED_SETTING 0"
    }

    /** True when the unit has anything to exempt the app from: Doze arrived with Android 6. */
    fun exemptionsApply(apiLevel: Int): Boolean = apiLevel >= API_M

    /**
     * Battery optimization (6+), the background start limit (8+) and the "restricted" background
     * state (9+). [grant] false hands each one back to the system default.
     */
    fun exemptionCommands(apiLevel: Int, packageName: String, grant: Boolean): List<String> {
        val mode = if (grant) "allow" else "default"
        return buildList {
            if (apiLevel >= API_M) add("dumpsys deviceidle whitelist ${if (grant) "+" else "-"}$packageName")
            if (apiLevel >= API_O) add("appops set $packageName RUN_IN_BACKGROUND $mode")
            if (apiLevel >= API_P) add("appops set $packageName RUN_ANY_IN_BACKGROUND $mode")
        }
    }
}
