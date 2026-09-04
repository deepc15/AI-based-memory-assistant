package com.localmind.chat.data.repo

import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity

/**
 * 100% Local Privacy Policy.
 *
 * All messages, memories, and personal context stay strictly on this device.
 * No data is uploaded to any cloud or third-party server.
 */
object SyncPolicy {

    /** 100% Local AI architecture. Outbound cloud sync is disabled. */
    fun shouldSync(message: MessageEntity): Boolean = false

    fun shouldSync(memory: MemoryEntity): Boolean = false

    /** Shown in the privacy screen so the user can verify local storage. */
    fun explain(memory: MemoryEntity): String = when {
        memory.revoked -> "Removed by you. Not used."
        else -> "Stored 100% locally on this phone."
    }
}
