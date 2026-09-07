package com.localmind.chat.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.chat.ai.DownloadStatus
import com.localmind.chat.ai.IntelligenceStatus
import com.localmind.chat.ai.ModelDownloader
import com.localmind.chat.data.local.ExcelMemoryHandler
import com.localmind.chat.data.local.ImportResult
import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.repo.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode: StateFlow<Boolean> = _isAutoMode.asStateFlow()

    private val _isOnlineEnabled = MutableStateFlow(true)
    val isOnlineEnabled: StateFlow<Boolean> = _isOnlineEnabled.asStateFlow()

    private val _manualSelectedMode = MutableStateFlow(IntelligenceStatus.AI_MODE)
    val manualSelectedMode: StateFlow<IntelligenceStatus> = _manualSelectedMode.asStateFlow()

    private val modelDownloader = ModelDownloader(app)

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private val _isModelInstalled = MutableStateFlow(modelDownloader.isModelDownloaded())
    val isModelInstalled: StateFlow<Boolean> = _isModelInstalled.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _importExportError = MutableStateFlow<String?>(null)
    val importExportError: StateFlow<String?> = _importExportError.asStateFlow()

    private val _showLearnedMemoriesPage = MutableStateFlow(false)
    val showLearnedMemoriesPage: StateFlow<Boolean> = _showLearnedMemoriesPage.asStateFlow()

    val intelligenceStatus: StateFlow<IntelligenceStatus> = repo.intelligenceStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntelligenceStatus.LOADING)

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
            // Shortening the window or selecting 10m should take effect immediately.
            repo.sweepExpired(force = true)
            refreshStorage()
        }
    }

    fun uninstallLocalModel() {
        val deleted = modelDownloader.deleteDownloadedModel()
        if (deleted) {
            _isModelInstalled.value = false
            _downloadStatus.value = DownloadStatus.Idle
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

    fun downloadLocalModel() {
        val usableSpace = getApplication<Application>().filesDir.usableSpace
        val requiredBytes = 900_000_000L // 900 MB required free space

        if (usableSpace < requiredBytes) {
            val freeMb = usableSpace / (1024 * 1024)
            _downloadStatus.value = DownloadStatus.Error(
                "Insufficient storage space available. Free space: ${freeMb} MB. Please free up at least 900 MB on your phone."
            )
            return
        }

        viewModelScope.launch {
            modelDownloader.downloadModel().collect { status ->
                _downloadStatus.value = status
                if (status is DownloadStatus.Completed) {
                    _isModelInstalled.value = true
                }
            }
        }
    }

    fun setOnlineEnabled(enabled: Boolean) {
        _isOnlineEnabled.value = enabled
        repo.setOnlineEnabled(enabled)
    }

    fun setAutoMode(isAuto: Boolean) {
        _isAutoMode.value = isAuto
        repo.setAutoMode(isAuto)
    }

    fun setManualMode(mode: IntelligenceStatus) {
        _manualSelectedMode.value = mode
        repo.setManualMode(mode)
    }

    /** Called once the retention notice has actually been shown to the user. */
    fun markRetentionDisclosed() {
        repo.retentionDisclosed = true
    }

    fun onDraftChange(value: String) {
        _draft.value = value
    }

    private var sendJob: Job? = null

    fun send() {
        val text = _draft.value.trim()
        val id = conversationId.value
        if (text.isEmpty() || id == null || _sending.value) return

        _draft.value = ""
        _sending.value = true
        sendJob = viewModelScope.launch {
            try {
                repo.send(id, text)
            } finally {
                _sending.value = false
            }
        }
    }

    fun stop() {
        if (_sending.value) {
            sendJob?.cancel()
            _sending.value = false
        }
    }

    fun togglePin(message: MessageEntity) = viewModelScope.launch {
        repo.setPinned(message, !message.pinned)
    }

    fun forget(memory: MemoryEntity) = viewModelScope.launch { repo.forget(memory) }

    fun setMemoriesVisible(visible: Boolean) {
        _showMemories.value = visible
    }

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }

    fun setLearnedMemoriesPageVisible(visible: Boolean) {
        _showLearnedMemoriesPage.value = visible
        if (visible) {
            _importExportError.value = null
        }
    }

    fun clearImportExportError() {
        _importExportError.value = null
    }

    fun exportMemories(context: Context) {
        val memoryList = memories.value
        if (memoryList.isEmpty()) {
            _importExportError.value = "No learned memories available to export."
            return
        }

        viewModelScope.launch {
            try {
                val file = ExcelMemoryHandler.exportToCsvFile(context, memoryList)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Export Learned Memories"))
            } catch (e: Exception) {
                _importExportError.value = "Failed to export learned memories."
            }
        }
    }

    fun importMemories(context: Context, uri: Uri) {
        viewModelScope.launch {
            when (val result = ExcelMemoryHandler.parseAndValidateFile(context, uri)) {
                is ImportResult.Error -> {
                    _importExportError.value = result.message
                }
                is ImportResult.Success -> {
                    _importExportError.value = null
                    repo.importMemoriesFromList(result.facts)
                }
            }
        }
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
