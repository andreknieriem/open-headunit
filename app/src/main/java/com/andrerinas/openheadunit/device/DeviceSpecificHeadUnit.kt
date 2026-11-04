package com.andrerinas.openheadunit.device

import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.Settings

class DeviceSpecificHeadUnitSettings(context: Context) {
    private val preferences = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    var modelKey: String
        get() = preferences.getString(KEY_MODEL, null) ?: "none"
        set(value) {
            preferences.edit().putString(KEY_MODEL, value).apply()
        }

    fun isEnabled(hack: DeviceHackDefinition): Boolean =
        preferences.getBoolean(hack.preferenceKey, false)

    fun setEnabled(hack: DeviceHackDefinition, enabled: Boolean) {
        preferences.edit().putBoolean(hack.preferenceKey, enabled).apply()
    }

    companion object {
        private const val KEY_MODEL = "device-specific-head-unit-model"
    }
}

data class DeviceHackDefinition(
    val id: String,
    val titleResId: Int,
    val descriptionResId: Int,
    val helpResId: Int? = null,
    val preferenceKey: String,
    val checkingResId: Int,
    val unavailableResId: Int,
    val isAvailable: () -> Boolean,
    val scope: DeviceHackScope,
    val create: (ProjectionVideoControl) -> DeviceHack
)

data class DeviceProfile(
    val id: String,
    val titleResId: Int,
    val hacks: List<DeviceHackDefinition>
)

object DeviceProfiles {
    val all = listOf(
        DeviceProfile("none", R.string.device_specific_model_none, emptyList()),
        DeviceProfile(
            "adayo_haval",
            R.string.device_specific_model_adayo_haval,
            listOf(DeviceHackDefinition(
                id = AdayoReverseCameraHack.ID,
                titleResId = R.string.device_specific_pause_projection_reverse,
                descriptionResId = R.string.device_specific_pause_projection_reverse_summary,
                helpResId = R.string.device_specific_pause_projection_reverse_help,
                // Keep the original preference key so existing installations retain their choice.
                preferenceKey = "device-specific-pause-projection-on-reverse",
                checkingResId = R.string.device_specific_mcu_checking,
                unavailableResId = R.string.device_specific_mcu_unavailable,
                isAvailable = AdayoMcuGearSource::canAccessMcu,
                scope = DeviceHackScope.PROJECTION_SESSION,
                create = { video -> AdayoReverseCameraHack(video) }
            ))
        )
    )

    fun selected(settings: DeviceSpecificHeadUnitSettings): DeviceProfile =
        all.firstOrNull { it.id == settings.modelKey } ?: all.first()

    fun enabledHacks(settings: DeviceSpecificHeadUnitSettings): List<DeviceHackSpec> =
        selected(settings).hacks.filter { settings.isEnabled(it) }.map { hack ->
            DeviceHackSpec(hack.id, scope = hack.scope, create = hack.create)
        }
}
