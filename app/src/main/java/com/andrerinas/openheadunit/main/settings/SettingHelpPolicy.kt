package com.andrerinas.openheadunit.main.settings

/** Which settings rows carry help text. The holders and the search both read it, so they agree. */
object SettingHelpPolicy {

    fun helpResId(item: SettingItem): Int? = when (item) {
        is SettingItem.SettingEntry -> item.helpResId
        is SettingItem.StreamSettingEntry -> item.helpResId
        is SettingItem.ToggleSettingEntry -> item.helpResId
        is SettingItem.SliderSettingEntry -> item.helpResId
        is SettingItem.SegmentedButtonSettingEntry -> item.helpResId
        is SettingItem.CategoryHeader,
        is SettingItem.InfoBanner,
        is SettingItem.ActionButton,
        is SettingItem.ScopeNotice -> null
    }

    fun showsHelp(item: SettingItem): Boolean = helpResId(item) != null
}
