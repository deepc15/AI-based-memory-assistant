package com.localmind.chat.data.repo

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs the retention sweep roughly once a day, whether or not the app is open.
 *
 * WorkManager is the right tool here rather than a sweep-on-launch alone: someone who
 * doesn't open the app for two months should not be storing two months of chat, and
 * the work needs to survive reboots and process death. Sweep-on-launch still runs too
 * (see ChatRepository), so a returning user sees the effect immediately instead of
 * waiting for the next window.
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = RetentionSettings(applicationContext)

        // Never delete data before the user has been told it happens.
        if (!settings.disclosureShown) return Result.success()

        return try {
            val result = RetentionSweeper(applicationContext, settings).sweep()
            if (result.messagesDeleted > 0) {
                Log.i(
                    TAG,
                    "Swept ${result.messagesDeleted} messages, " +
                        "released ${result.bytesReclaimed / 1024}KB " +
                        "(vacuumed=${result.vacuumed})"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sweep failed; will retry on the next window", e)
            // retry() rather than failure(): a transient DB lock shouldn't cancel
            // retention permanently.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RetentionWorker"
        private const val WORK_NAME = "localmind_retention_sweep"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // No network constraint: this is purely local work.
                        // Deliberately no requiresStorageNotLow — that would block the
                        // sweep exactly when freeing storage matters most.
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, not UPDATE: re-enqueuing on every cold start would keep
                // resetting the period and the job would never fire.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
