package com.localmind.chat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.repo.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepository(app)

    private val conversationId = MutableStateFlow<String?>(null)

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _showMemories = MutableStateFlow(false)
    val showMemories: StateFlow<Boolean> = _showMemories.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = conversationId
        .filterNotNull()
        .flatMapLatest { repo.observeMessages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = repo.observeMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messageCount: StateFlow<Int> = repo.observeMessageCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _retentionDays = MutableStateFlow(repo.retentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    /** How many messages the next sweep will remove. Re-derived when the window changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val expiringCount: StateFlow<Int> = _retentionDays
        .flatMapLatest { repo.observeExpiring() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _lastSweep = MutableStateFlow<String?>(null)
    val lastSweep: StateFlow<String?> = _lastSweep.asStateFlow()

    init {
        viewModelScope.launch {
            conversationId.value = repo.activeConversation().id

            // Apply retention before anything else, so a user returning after a long
            // gap doesn't briefly see history that is about to disappear.
            repo.sweepExpired()
            refreshStorage()

            // Catch up on anything that was approved for sync but couldn't upload.
            repo.flushPending()
        }
    }

    private fun refreshStorage() {
        _storageBytes.value = repo.databaseBytes()
    }

    fun setRetentionDays(days: Int) {
        repo.retentionDays = days
        _retentionDays.value = days
        viewModelScope.launch {
            // Shortening the window should take effect now, not tomorrow.
            repo.sweepExpired()
            refreshStorage()
        }
    }

    /** Explicit "Clear now". Always compacts, so the freed space is immediately real. */
    fun clearOldChatsNow() = viewModelScope.launch {
        val result = repo.clearOldChatsNow()
        refreshStorage()
        _lastSweep.value = when {
            result.messagesDeleted == 0 -> "Nothing old enough to clear yet."
            result.bytesReclaimed > 0 ->
                "Cleared ${result.messagesDeleted} messages and freed " +
                    formatBytes(result.bytesReclaimed) + "."
            else -> "Cleared ${result.messagesDeleted} messages."
        }
    }

    /** Called once the retention notice has actually been shown to the user. */
    fun markRetentionDisclosed() {
        repo.retentionDisclosed = true
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    fun send() {
        val text = _draft.value.trim()
        val id = conversationId.value
        if (text.isEmpty() || id == null || _sending.value) return

        _draft.value = ""
        _sending.value = true
        viewModelScope.launch {
            try {
                repo.send(id, text)
            } finally {
                _sending.value = false
            }
        }
    }

    fun togglePin(message: MessageEntity) = viewModelScope.launch {
        repo.setPinned(message, !message.pinned)
    }

    fun forget(memory: MemoryEntity) = viewModelScope.launch { repo.forget(memory) }

    fun setMemoriesVisible(visible: Boolean) {
        _showMemories.value = visible
    }

    fun deleteEverything() = viewModelScope.launch {
        repo.deleteEverything()
        conversationId.value = repo.activeConversation().id
        refreshStorage()
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
