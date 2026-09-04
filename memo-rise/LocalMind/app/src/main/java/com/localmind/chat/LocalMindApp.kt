package com.localmind.chat

import android.app.Application
import com.localmind.chat.data.repo.RetentionWorker

class LocalMindApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Idempotent: enqueueUniquePeriodicWork with KEEP means calling this on
        // every cold start is safe and does not reset the schedule.
        RetentionWorker.schedule(this)
    }
}
