package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.main.FavoriteAppsPolicy
import com.andrerinas.openheadunit.main.FavoriteTarget
import com.andrerinas.openheadunit.utils.Settings

/** What the projection screen does on its own, without asking Android Auto. */
enum class LocalAction(val id: String) {
    EXIT_DIALOG("exit"),
    TOGGLE_FULLSCREEN("fullscreen"),
    NIGHT_OVERLAY("overlay"),
    DIM_SCREEN("dim");

    companion object {
        fun fromId(id: String): LocalAction? = values().firstOrNull { it.id == id }
    }
}

/**
 * Anything a key binding or a gesture can trigger. Keys and gestures share this type and the
 * same picker screen, so either can send an Android Auto key, run a projection action or open an
 * app.
 */
sealed class InputAction {
    object None : InputAction()

    /** A key sent to Android Auto, by the logical code [KeyCode.convert] understands. */
    data class AaKey(val keyCode: Int) : InputAction()

    data class Local(val kind: LocalAction) : InputAction()

    /** Opens an app or one of its shortcuts. Never [FavoriteTarget.Empty]. */
    data class LaunchApp(val target: FavoriteTarget) : InputAction()
}

/**
 * Stores an [InputAction] as one string: `none`, `key:85`, `local:dim`, or `app:` followed by the
 * target the way [FavoriteAppsPolicy] stores a favorite slot. Anything unreadable decodes to
 * [InputAction.None], so a corrupt value never triggers something unexpected.
 */
object InputActionCodec {
    private const val NONE = "none"
    private const val KEY = "key:"
    private const val LOCAL = "local:"
    private const val APP = "app:"

    fun encode(action: InputAction): String = when (action) {
        InputAction.None -> NONE
        is InputAction.AaKey -> KEY + action.keyCode
        is InputAction.Local -> LOCAL + action.kind.id
        is InputAction.LaunchApp ->
            if (action.target == FavoriteTarget.Empty) NONE
            else APP + FavoriteAppsPolicy.encode(listOf(action.target))
    }

    fun decode(stored: String?): InputAction {
        if (stored == null) return InputAction.None
        return when {
            stored.startsWith(KEY) ->
                stored.removePrefix(KEY).toIntOrNull()?.let { InputAction.AaKey(it) } ?: InputAction.None
            stored.startsWith(LOCAL) ->
                LocalAction.fromId(stored.removePrefix(LOCAL))?.let { InputAction.Local(it) } ?: InputAction.None
            stored.startsWith(APP) -> {
                val target = FavoriteAppsPolicy.decode(stored.removePrefix(APP)).first()
                if (target == FavoriteTarget.Empty) InputAction.None else InputAction.LaunchApp(target)
            }
            else -> InputAction.None
        }
    }
}

/** The action an old Int-stored gesture value means, for gestures saved before [InputAction]. */
fun Settings.GestureAction.toInputAction(): InputAction = when (this) {
    Settings.GestureAction.DISABLED -> InputAction.None
    Settings.GestureAction.EXIT_DIALOG -> InputAction.Local(LocalAction.EXIT_DIALOG)
    Settings.GestureAction.TOGGLE_FULLSCREEN -> InputAction.Local(LocalAction.TOGGLE_FULLSCREEN)
    Settings.GestureAction.NIGHT_OVERLAY -> InputAction.Local(LocalAction.NIGHT_OVERLAY)
    Settings.GestureAction.DIM_SCREEN -> InputAction.Local(LocalAction.DIM_SCREEN)
}

/**
 * What a bound key does on press and on release. Android Auto keys are forwarded both ways; local
 * actions and app launches run once, on release, so holding a key never repeats them.
 */
object KeyBindingPolicy {
    sealed class Resolution {
        /** Not bound: the key goes on unchanged, as before bindings existed. */
        object Unbound : Resolution()
        data class SendToAa(val logicalCode: Int) : Resolution()
        data class Run(val action: InputAction) : Resolution()
        /** Bound to a one-shot action, but this is its press: swallowed. */
        object Swallow : Resolution()
    }

    fun resolve(bindings: Map<Int, InputAction>, keyCode: Int, isPress: Boolean): Resolution =
        when (val action = bindings[keyCode]) {
            null, InputAction.None -> Resolution.Unbound
            is InputAction.AaKey -> Resolution.SendToAa(action.keyCode)
            is InputAction.Local, is InputAction.LaunchApp ->
                if (isPress) Resolution.Swallow else Resolution.Run(action)
        }

    /**
     * Turns the old `key-codes` map (Android Auto key → physical key) into bindings (physical key →
     * action). Two targets learned on the same physical key keep the lowest code, which is
     * deterministic; the old lookup kept whichever came first in the set.
     */
    fun fromLegacy(logicalToPhysical: Map<Int, Int>): Map<Int, InputAction> =
        logicalToPhysical.entries.sortedBy { it.key }.reversed()
            .associate { (logical, physical) -> physical to (InputAction.AaKey(logical) as InputAction) }
}
