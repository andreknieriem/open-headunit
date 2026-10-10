package com.andrerinas.openheadunit.connection.wifi.scan

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*

/** JobScheduler keeps boot work alive without opening an activity or starting projection. */
class FytBootStartJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try {
                FytShizukuStarter.start(applicationContext, automatic = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("FYT Shizuku: boot startup failed; use the setup screen to retry", e) }
            finally {
                WifiScanControl.refresh()
                // onStopJob already ends a cancelled job; ordinary completion (including a
                // skipped attempt) must always release the scheduler's running job.
                if (currentCoroutineContext().isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // The attempt is recorded before opening ADB. Do not fight a stopped service or
        // repeat Android authorization prompts in a background retry loop.
        return false
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 7315
        fun schedule(context: Context) {
            if (!FytShizukuStarter.shouldStartAfterBoot(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            // App initialization runs after unlock on a boot broadcast or normal launch.
            // Give vendor services time to initialize; eligibility is checked again on execution.
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, FytBootStartJob::class.java))
                .setMinimumLatency(10_000).setOverrideDeadline(30_000).build())
            AppLog.i("FYT Shizuku: boot startup scheduled=$result")
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }
}
