package com.localmind.chat.data.cloud

import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity

/**
 * Cloud surface stub for 100% local operation.
 *
 * No external network operations or third-party cloud sync exist in this app.
 * All operations remain strictly local on the device.
 */
class CloudStore {

    suspend fun uid(): String? = null

    suspend fun putMemory(memory: MemoryEntity): Boolean = false

    suspend fun deleteMemory(id: String): Boolean = false

    suspend fun putPinnedMessage(message: MessageEntity): Boolean = false

    suspend fun deletePinnedMessage(id: String): Boolean = false

    suspend fun putProfile(persona: String, groundingEnabled: Boolean): Boolean = false

    suspend fun fetchMemories(): List<Triple<String, String, Float>> = emptyList()

    suspend fun wipeEverything(): Boolean = true
}
