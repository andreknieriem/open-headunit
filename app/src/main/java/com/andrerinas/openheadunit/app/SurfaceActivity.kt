package com.andrerinas.openheadunit.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.LocaleHelper

/**
 * Base for the projection activity. Does NOT extend [BaseActivity] to avoid
 * [BaseActivity.onResume] calling [recreate] on night-mode or theme changes,
 * which would destroy the video surface mid-session.
 */
abstract class SurfaceActivity : AppCompatActivity() {
    private val appUiEntryIdentity: AppUiEntryIdentity by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppUiEntry.isEntryActivity(this)) AppUiEntry.created(this, appUiEntryIdentity)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (AppUiEntry.isEntryActivity(this)) AppUiEntry.delivered(this, intent)
    }

    override fun onResume() {
        super.onResume()
        if (AppUiEntry.isEntryActivity(this)) AppUiEntry.resumed(this)
    }
}
