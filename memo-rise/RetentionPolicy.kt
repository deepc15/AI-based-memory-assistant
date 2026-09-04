package com.localmind.chat.data.repo

/**
 * How long unimportant chat history survives on the phone.
 *
 * The companion to SyncPolicy: that one decides what may leave the device, this one
 * decides what stops existing. Together they're the whole data lifecycle, and both are
 * short enough to read in full before trusting the app.
 *
 * Survives the sweep regardless of age:
 *   - pinned messages — the user said these matter, and they have a cloud copy
 *   - extracted facts in the memory store — these are the app's long-term knowledge
 *   - a reply still streaming, or one that failed and can be retried
 *
 * Deleting old turns costs less than it looks like it should. Facts are extracted at
 * send time, so what the assistant *learned* from a conversation outlives the
 * conversation itself. A chat from three months ago contributes nothing to the current
 * prompt anyway — only the last CONTEXT_TURNS are ever sent to the model.
 */
object RetentionPolicy {

    const val DEFAULT_DAYS = 30

    /** Offered in settings. 0 means keep everything. */
    val CHOICES = listOf(7, 30, 90, 365, 0)

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Messages created before this instant are eligible for deletion.
     * Null means retention is off and nothing should be swept.
     */
    fun cutoff(days: Int, now: Long = System.currentTimeMillis()): Long? =
        if (days <= 0) null else now - days * DAY_MS

    fun label(days: Int): String = when (days) {
        0 -> "Keep everything"
        1 -> "1 day"
        365 -> "1 year"
        else -> "$days days"
    }

    /**
     * VACUUM rewrites the entire database to hand free pages back to the filesystem.
     * It's the only way the file actually shrinks, but it's proportional to database
     * size, so it isn't worth doing after trimming a handful of rows.
     */
    const val VACUUM_ROW_THRESHOLD = 150

    /** Vacuum at least this often once anything has been deleted, regardless of volume. */
    const val VACUUM_MIN_INTERVAL_MS = 7 * DAY_MS
}
