package com.andrerinas.openheadunit.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.SettingsRestoreHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OnboardingRestoreViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Importing : State
        data class Restored(
            val result: SettingsBackupManager.ImportResult,
            val before: SettingsRestoreHandler.Snapshot
        ) : State
        data class Failed(val error: Exception) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state = mutableState.asStateFlow()

    fun restore(context: Context, uri: Uri? = null, file: File? = null) {
        if (mutableState.value != State.Idle) return
        require((uri == null) != (file == null))
        val appContext = context.applicationContext
        mutableState.value = State.Importing
        viewModelScope.launch {
            mutableState.value = try {
                val settings = Settings(appContext)
                val before = SettingsRestoreHandler.capture(appContext, settings)
                val result = withContext(Dispatchers.IO) {
                    // The activity marks onboarding complete before applying the restored theme.
                    if (uri != null) SettingsBackupManager.importFromUri(appContext, uri, applyTheme = false)
                    else SettingsBackupManager.importFromFile(appContext, requireNotNull(file), applyTheme = false)
                }
                require(result.importedKeys > 0) { "Backup contains no supported settings" }
                State.Restored(result, before)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                State.Failed(e)
            }
        }
    }

    fun acknowledge() {
        check(mutableState.value != State.Importing)
        mutableState.value = State.Idle
    }
}
