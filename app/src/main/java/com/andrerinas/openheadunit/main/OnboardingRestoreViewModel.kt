package com.andrerinas.openheadunit.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
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

    /**
     * [connection] supplies the Stop ticket of the user's choice: a Stop accepted while the file
     * is read withdraws the restore before it writes anything.
     */
    fun restore(context: Context, connection: CommManager, uri: Uri? = null, file: File? = null) {
        if (mutableState.value != State.Idle) return
        require((uri == null) != (file == null))
        val appContext = context.applicationContext
        val ticket = connection.appConnectionTicket()
        mutableState.value = State.Importing
        viewModelScope.launch {
            mutableState.value = try {
                val settings = Settings(appContext)
                val before = SettingsRestoreHandler.capture(appContext, settings, ticket)
                val prepared = withContext(Dispatchers.IO) {
                    if (uri != null) SettingsBackupManager.prepareImportFromUri(appContext, uri)
                    else SettingsBackupManager.prepareImportFromFile(requireNotNull(file))
                }
                if (!connection.acceptsAppConnectionTicket(ticket)) {
                    AppLog.i("Onboarding restore withdrawn: Stop was accepted while the file was read")
                    State.Idle
                } else {
                    // Apply on Main like the settings screen. The activity marks onboarding
                    // complete before applying the restored theme.
                    val result = SettingsBackupManager.applyImport(appContext, prepared, applyTheme = false)
                    require(result.importedKeys > 0) { "Backup contains no supported settings" }
                    State.Restored(result, before)
                }
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
