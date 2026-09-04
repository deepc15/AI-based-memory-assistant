package com.localmind.chat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ConversationEntity?

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Clears conversation shells that the retention sweep emptied out.
     *
     * The `updatedAt` guard matters: without it this would delete the brand-new
     * conversation the app creates on launch, before the user has typed anything.
     */
    @Query(
        """
        DELETE FROM conversations
        WHERE updatedAt < :cutoff
          AND id NOT IN (SELECT DISTINCT conversationId FROM messages)
        """
    )
    suspend fun deleteEmptyOlderThan(cutoff: Long): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    /** The tail of the conversation, oldest-first, used to build model history. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND streaming = 0 AND failed = 0
        ORDER BY createdAt DESC LIMIT :limit
        """
    )
    suspend fun recentForContext(conversationId: String, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET text = :text, streaming = :streaming WHERE id = :id")
    suspend fun updateText(id: String, text: String, streaming: Boolean)

    @Query("UPDATE messages SET sources = :sources WHERE id = :id")
    suspend fun updateSources(id: String, sources: String?)

    @Query("UPDATE messages SET failed = 1, streaming = 0 WHERE id = :id")
    suspend fun markFailed(id: String)

    @Query("UPDATE messages SET pinned = :pinned, syncState = :state WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, state: SyncState)

    @Query("SELECT * FROM messages WHERE pinned = 1 AND syncState = 'PENDING'")
    suspend fun pinnedAwaitingUpload(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE pinned = 1 ORDER BY createdAt DESC")
    fun observePinned(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET syncState = :state WHERE id = :id")
    suspend fun setSyncState(id: String, state: SyncState)

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    // ---- Retention ----------------------------------------------------------
    //
    // The exemptions live in the WHERE clause rather than in Kotlin so that no
    // caller can accidentally sweep without them. Three things are protected:
    //   pinned = 0    the user marked it important; it also has a cloud copy
    //   streaming = 0 a reply still being written must never vanish mid-token
    //   failed = 0    kept so the user can see and retry what didn't send

    @Query(
        """
        DELETE FROM messages
        WHERE createdAt < :cutoff
          AND pinned = 0
          AND streaming = 0
          AND failed = 0
        """
    )
    suspend fun deleteExpired(cutoff: Long): Int

    /** Same predicate, counted instead of deleted, so the UI can preview the sweep. */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE createdAt < :cutoff
          AND pinned = 0
          AND streaming = 0
          AND failed = 0
        """
    )
    fun observeExpiring(cutoff: Long): Flow<Int>

    @Query("SELECT MIN(createdAt) FROM messages")
    suspend fun oldestTimestamp(): Long?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface MemoryDao {

    /** IGNORE so that re-extracting the same fact doesn't churn its id or timestamps. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE revoked = 0 ORDER BY lastUsedAt DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE revoked = 0 ORDER BY confidence DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun topForPrompt(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE revoked = 0 AND syncState = 'PENDING'")
    suspend fun awaitingUpload(): List<MemoryEntity>

    @Query("UPDATE memories SET syncState = :state WHERE id = :id")
    suspend fun setSyncState(id: String, state: SyncState)

    @Query("UPDATE memories SET revoked = 1, syncState = 'LOCAL_ONLY' WHERE id = :id")
    suspend fun revoke(id: String)

    @Query("UPDATE memories SET lastUsedAt = :at WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<String>, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
