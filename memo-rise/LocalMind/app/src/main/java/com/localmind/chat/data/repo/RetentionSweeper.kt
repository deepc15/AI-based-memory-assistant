package com.localmind.chat.data.repo

import android.content.Context
import android.util.Log
import com.localmind.chat.data.local.LocalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class SweepResult(
    val messagesDeleted: Int,
    val conversationsDeleted: Int,
    val bytesBefore: Long,
    val bytesAfter: Long,
    val vacuumed: Boolean
) {
    val bytesReclaimed: Long get() = (bytesBefore - bytesAfter).coerceAtLeast(0)

    companion object {
        val NOTHING = SweepResult(0, 0, 0, 0, false)
    }
}

/**
 * Deletes chat history past the retention window and returns the space to the OS.
 *
 * The two-step shape is the whole point. `DELETE` in SQLite marks pages as free for
 * reuse but never shrinks the file, so deletion alone would clear the data while
 * leaving the user's storage exactly as full as before — the opposite of the goal.
 * `VACUUM` rewrites the database compactly and hands the pages back to the filesystem.
 *
 * VACUUM is expensive (proportional to database size, and it needs room for a temporary
 * copy), so it's rate-limited rather than run on every sweep.
 */
class RetentionSweeper(
    private val context: Context,
    private val settings: RetentionSettings = RetentionSettings(context)
) {

    private val db = LocalDatabase.get(context)

    suspend fun sweep(force: Boolean = false): SweepResult = withContext(Dispatchers.IO) {
        val cutoff = RetentionPolicy.cutoff(settings.days) ?: return@withContext SweepResult.NOTHING

        val before = databaseBytes()

        val messagesDeleted = db.messages().deleteExpired(cutoff)

        // Only runs after messages are gone, so it can only catch shells that the
        // sweep itself emptied. Conversations still holding a pinned message survive,
        // because the pinned row keeps them non-empty.
        val conversationsDeleted = db.conversations().deleteEmptyOlderThan(cutoff)

        if (messagesDeleted == 0 && conversationsDeleted == 0) {
            return@withContext SweepResult.NOTHING
        }

        // Memory rows can now hold a sourceMessageId pointing at a deleted message.
        // That's intentional: there's no foreign key on it, so the fact survives the
        // conversation it came from. The id is only ever used for debugging provenance.

        val shouldVacuum = force ||
            messagesDeleted >= RetentionPolicy.VACUUM_ROW_THRESHOLD ||
            System.currentTimeMillis() - settings.lastVacuumAt >= RetentionPolicy.VACUUM_MIN_INTERVAL_MS

        var vacuumed = false
        if (shouldVacuum) {
            vacuumed = compact()
            if (vacuumed) settings.lastVacuumAt = System.currentTimeMillis()
        }

        SweepResult(
            messagesDeleted = messagesDeleted,
            conversationsDeleted = conversationsDeleted,
            bytesBefore = before,
            bytesAfter = databaseBytes(),
            vacuumed = vacuumed
        )
    }

    /** Rebuilds the database file compactly, then truncates the write-ahead log. */
    private fun compact(): Boolean = try {
        val writable = db.openHelper.writableDatabase

        // VACUUM cannot run inside a transaction, which is why this bypasses
        // Room's runInTransaction and talks to the support database directly.
        writable.execSQL("VACUUM")

        // VACUUM compacts the main file but can leave a large -wal alongside it.
        // Checkpointing with TRUNCATE folds it in and drops it back to zero.
        writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        true
    } catch (e: Exception) {
        // A failed VACUUM is not a failed sweep: the rows are already gone and the
        // freed pages get reused. Worth logging, not worth surfacing.
        Log.w(TAG, "Compaction failed; space will be reused rather than released", e)
        false
    }

    /** Main file plus its WAL and shared-memory sidecars. */
    fun databaseBytes(): Long {
        val main = context.getDatabasePath(DB_NAME)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"))
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    private companion object {
        const val TAG = "RetentionSweeper"
        const val DB_NAME = "localmind.db"
    }
}
