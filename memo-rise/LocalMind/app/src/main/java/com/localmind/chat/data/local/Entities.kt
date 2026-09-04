package com.localmind.chat.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Where a piece of data currently lives. */
enum class SyncState {
    /** On this phone only. The default for everything. */
    LOCAL_ONLY,

    /** Marked important; waiting for the next sync window. */
    PENDING,

    /** A copy exists in Firestore. */
    SYNCED
}

enum class Role { USER, MODEL }

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A single chat turn. Rows in this table are never uploaded in bulk.
 * Only rows the user explicitly pins are eligible for the cloud — see SyncPolicy.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId"), Index("pinned")]
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: Role,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),

    /** User pressed the pin. This is the only thing that makes a message important. */
    val pinned: Boolean = false,
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    /** Newline-separated "title\turl" pairs from Google Search grounding, if any. */
    val sources: String? = null,

    /** True while the model is still streaming into this row. */
    val streaming: Boolean = false,
    val failed: Boolean = false
)

/**
 * A durable fact about the user, distilled from conversation.
 *
 * This is the app's substitute for "training" the model: facts here are injected
 * into the system instruction on every request, so the assistant appears to learn
 * continuously without any weights ever changing.
 */
@Entity(tableName = "memories", indices = [Index(value = ["text"], unique = true)])
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val confidence: Float,
    val sourceMessageId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    /** User can veto a fact; vetoed facts stay local and are never used or synced. */
    val revoked: Boolean = false
)
