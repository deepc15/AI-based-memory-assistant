package com.localmind.chat.data.repo

import android.content.Context
import android.util.Log
import com.localmind.chat.ai.AiEngine
import com.localmind.chat.ai.Chunk
import com.localmind.chat.ai.ExtractedFact
import com.localmind.chat.ai.IntelligenceStatus
import com.localmind.chat.ai.MemoryExtractor
import com.localmind.chat.ai.PromptBuilder
import com.localmind.chat.data.local.ConversationEntity
import com.localmind.chat.data.local.LocalDatabase
import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.local.Role
import com.localmind.chat.data.local.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 100% Local Repository.
 *
 * Every message and extracted memory fact is committed to the SQLCipher encrypted
 * local database on device. No data is uploaded to any external server or cloud service.
 */
class ChatRepository(
    context: Context,
    private val ai: AiEngine = AiEngine(context),
    private val extractor: MemoryExtractor = MemoryExtractor()
) {

    private val db = LocalDatabase.get(context)
    private val messages = db.messages()
    private val memories = db.memories()
    private val conversations = db.conversations()

    /** Background work for local learning and fact extraction. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val intelligenceStatus: Flow<IntelligenceStatus> = ai.status

    fun setOnlineEnabled(enabled: Boolean) {
        ai.setOnlineEnabled(enabled)
    }

    fun setAutoMode(isAuto: Boolean) {
        ai.setAutoMode(isAuto)
    }

    fun setManualMode(mode: IntelligenceStatus) {
        ai.setManualMode(mode)
    }

    private var persona: String = PromptBuilder.DEFAULT_PERSONA

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messages.observeConversation(conversationId)

    fun observeMemories(): Flow<List<MemoryEntity>> = memories.observeActive()

    fun observeMessageCount(): Flow<Int> = messages.observeCount()

    suspend fun activeConversation(): ConversationEntity = withContext(Dispatchers.IO) {
        conversations.mostRecent() ?: ConversationEntity(title = "New chat").also {
            conversations.upsert(it)
        }
    }

    /**
     * Writes the user turn, streams the reply from local Llama model into a placeholder
     * row, then kicks off local fact extraction.
     */
    suspend fun send(conversationId: String, userText: String) = withContext(Dispatchers.IO) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return@withContext

        val userMessage = MessageEntity(
            conversationId = conversationId,
            role = Role.USER,
            text = trimmed
        )
        messages.insert(userMessage)
        conversations.touch(conversationId)

        // Build history BEFORE inserting the placeholder
        val history = messages.recentForContext(conversationId, CONTEXT_TURNS)
            .asReversed()
            .filter { it.id != userMessage.id }

        val promptMemories = memories.topForPrompt(MEMORIES_IN_PROMPT)
        val systemInstruction = PromptBuilder.build(persona, promptMemories)
        if (promptMemories.isNotEmpty()) {
            memories.markUsed(promptMemories.map { it.id })
        }

        val reply = MessageEntity(
            conversationId = conversationId,
            role = Role.MODEL,
            text = "",
            streaming = true
        )
        messages.insert(reply)

        val buffer = StringBuilder()
        var wasCancelled = false
        try {
            ai.stream(history, trimmed, systemInstruction).collect { chunk ->
                when (chunk) {
                    is Chunk.Text -> {
                        buffer.append(chunk.delta)
                        messages.updateText(reply.id, buffer.toString(), streaming = true)
                    }

                    is Chunk.Done -> {
                        messages.updateText(reply.id, buffer.toString(), streaming = false)
                        if (chunk.sources.isNotEmpty()) {
                            messages.updateSources(
                                reply.id,
                                chunk.sources.joinToString("\n") { "${it.title}\t${it.url}" }
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            wasCancelled = true
            Log.i("ChatRepository", "Streaming reply cancelled by user via Stop button.")
            messages.updateText(reply.id, "Response terminated.", streaming = false)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error streaming reply from Local Llama AI", e)
            if (buffer.isEmpty()) {
                messages.markFailed(reply.id)
            } else {
                // Partial answer is still worth keeping; just stop the spinner.
                messages.updateText(reply.id, buffer.toString(), streaming = false)
            }
        }

        titleIfNeeded(conversationId, trimmed)
        if (!wasCancelled) {
            val completedReply = buffer.toString()
            background.launch { learnFrom(trimmed, userMessage.id, completedReply) }
        }
    }

    /** Cheap local title from the first turn. */
    private suspend fun titleIfNeeded(conversationId: String, firstText: String) {
        val existing = conversations.mostRecent() ?: return
        if (existing.id != conversationId || existing.title != "New chat") return
        conversations.rename(conversationId, firstText.take(40).trim())
    }

    /**
     * Local AI learning: extracts facts and saves them to the encrypted local database.
     */
    private suspend fun learnFrom(userText: String, sourceMessageId: String, replyText: String) {
        val extractedFacts = extractor.extract(userText).toMutableList()

        val infoFact = extractor.extractInformativeFact(userText, replyText)
        if (infoFact != null) {
            extractedFacts.add(infoFact)
        }

        extractedFacts.forEach { fact ->
            val memory = MemoryEntity(
                text = fact.text,
                category = fact.category,
                confidence = fact.confidence,
                sourceMessageId = sourceMessageId,
                syncState = SyncState.LOCAL_ONLY
            )

            // Unique index on text means duplicates are silently ignored (-1).
            memories.insertIfNew(memory)
        }
    }

    suspend fun importMemoriesFromList(facts: List<ExtractedFact>) = withContext(Dispatchers.IO) {
        facts.forEach { fact ->
            val memory = MemoryEntity(
                text = fact.text,
                category = fact.category,
                confidence = fact.confidence,
                sourceMessageId = "imported",
                syncState = SyncState.LOCAL_ONLY
            )
            memories.insertIfNew(memory)
        }
    }

    /** Pinning is the user's explicit mark that a message is important locally. */
    suspend fun setPinned(message: MessageEntity, pinned: Boolean) = withContext(Dispatchers.IO) {
        messages.setPinned(message.id, pinned, SyncState.LOCAL_ONLY)
    }

    suspend fun forget(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        memories.revoke(memory.id)
    }

    suspend fun flushPending() = withContext(Dispatchers.IO) {
        // No-op in 100% local mode
    }

    // ---- Retention -----------------------------------------------------------

    private val settings = RetentionSettings(context)
    private val sweeper = RetentionSweeper(context, settings)

    var retentionDays: Int
        get() = settings.days
        set(value) {
            settings.days = value
        }

    var retentionDisclosed: Boolean
        get() = settings.disclosureShown
        set(value) {
            settings.disclosureShown = value
        }

    fun observeExpiring(): Flow<Int> {
        val cutoff = RetentionPolicy.cutoff(settings.days) ?: return flowOf(0)
        return messages.observeExpiring(cutoff)
    }

    fun databaseBytes(): Long = sweeper.databaseBytes()

    suspend fun sweepExpired(force: Boolean = false): SweepResult =
        if (!settings.disclosureShown) SweepResult.NOTHING else sweeper.sweep(force)

    suspend fun clearOldChatsNow(): SweepResult = sweeper.sweep(force = true)

    suspend fun updatePersona(newPersona: String) = withContext(Dispatchers.IO) {
        persona = newPersona.ifBlank { PromptBuilder.DEFAULT_PERSONA }
    }

    /** Wipes the local database. */
    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        messages.deleteAll()
        memories.deleteAll()
        conversations.deleteAll()
    }

    private companion object {
        const val CONTEXT_TURNS = 24
        const val MEMORIES_IN_PROMPT = 40
    }
}
