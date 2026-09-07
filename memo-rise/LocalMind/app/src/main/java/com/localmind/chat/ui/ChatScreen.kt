package com.localmind.chat.ui

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.localmind.chat.ai.DownloadStatus
import com.localmind.chat.ai.IntelligenceStatus
import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.local.Role
import com.localmind.chat.data.local.SyncState
import com.localmind.chat.data.repo.RetentionPolicy
import com.localmind.chat.data.repo.SyncPolicy
import com.localmind.chat.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val showMemories by viewModel.showMemories.collectAsState()
    val retentionDays by viewModel.retentionDays.collectAsState()
    val expiringCount by viewModel.expiringCount.collectAsState()
    val storageBytes by viewModel.storageBytes.collectAsState()
    val lastSweep by viewModel.lastSweep.collectAsState()
    val intelligenceStatus by viewModel.intelligenceStatus.collectAsState()

    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val listState = rememberLazyListState()

    // The empty state and the storage section both spell out the retention window.
    // Seeing either counts as disclosure, and nothing is deleted before that.
    LaunchedEffect(messages.isEmpty(), showMemories) {
        if (messages.isEmpty() || showMemories) viewModel.markRetentionDisclosed()
    }

    @OptIn(ExperimentalLayoutApi::class)
    val isImeVisible = WindowInsets.isImeVisible

    // Scroll to the latest message automatically when a new message arrives, tokens stream in, or keyboard opens
    LaunchedEffect(isImeVisible, messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            if (isImeVisible) {
                delay(100) // Allow IME viewport resize animation to settle before scrolling
            }
            listState.scrollToItem(messages.lastIndex)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val showScrollToBottom by remember {
        derivedStateOf {
            messages.size > 3 && listState.firstVisibleItemIndex < messages.size - 3
        }
    }

    val isAutoMode by viewModel.isAutoMode.collectAsState()
    val manualSelectedMode by viewModel.manualSelectedMode.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { _ ->
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            StorageHeader(
                messageCount = messages.size,
                memoryCount = memories.size,
                onOpenSettings = { viewModel.setMemoriesVisible(true) }
            )

            if (messages.isEmpty()) {
                EmptyState(retentionDays = retentionDays, modifier = Modifier.weight(1f))
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = 20.dp,
                            vertical = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageRow(
                                message = message,
                                onTogglePin = { viewModel.togglePin(message) }
                            )
                        }
                    }

                    ScrollToBottomButton(
                        visible = showScrollToBottom,
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(messages.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
            }

            Composer(
                value = draft,
                enabled = !sending,
                sending = sending,
                onValueChange = viewModel::onDraftChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                intelligenceStatus = intelligenceStatus,
                isAutoMode = isAutoMode,
                manualSelectedMode = manualSelectedMode,
                onToggleAutoMode = viewModel::setAutoMode,
                onSelectManualMode = { mode ->
                    viewModel.setAutoMode(false)
                    viewModel.setManualMode(mode)
                }
            )
        }
    }

    if (!hasPermission) {
        PermissionModal(
            onGrantPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                } else {
                    permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        )
    }

    val downloadStatus by viewModel.downloadStatus.collectAsState()
    val isModelInstalled by viewModel.isModelInstalled.collectAsState()
    val isOnlineEnabled by viewModel.isOnlineEnabled.collectAsState()

    val showLearnedMemoriesPage by viewModel.showLearnedMemoriesPage.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val importExportError by viewModel.importExportError.collectAsState()

    if (showMemories) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setMemoriesVisible(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            MemorySheet(
                memories = memories,
                retentionDays = retentionDays,
                expiringCount = expiringCount,
                storageBytes = storageBytes,
                lastSweep = lastSweep,
                downloadStatus = downloadStatus,
                isModelInstalled = isModelInstalled,
                isOnlineEnabled = isOnlineEnabled,
                onToggleOnline = viewModel::setOnlineEnabled,
                onDownloadModel = viewModel::downloadLocalModel,
                onUninstallModel = viewModel::uninstallLocalModel,
                onOpenLearnedMemories = { viewModel.setLearnedMemoriesPageVisible(true) },
                onForget = { viewModel.forget(it) },
                onRetentionChange = viewModel::setRetentionDays,
                onClearNow = { viewModel.clearOldChatsNow() },
                onDeleteEverything = {
                    viewModel.deleteEverything()
                    viewModel.setMemoriesVisible(false)
                },
                onDismiss = { viewModel.setMemoriesVisible(false) }
            )
        }
    }

    if (showLearnedMemoriesPage) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setLearnedMemoriesPageVisible(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LearnedMemoriesSheet(
                memories = memories,
                currentPage = currentPage,
                importExportError = importExportError,
                onPageChange = viewModel::setCurrentPage,
                onExport = { viewModel.exportMemories(context) },
                onImportUri = { uri: Uri -> viewModel.importMemories(context, uri) },
                onClearError = viewModel::clearImportExportError,
                onForget = { memory: MemoryEntity -> viewModel.forget(memory) },
                onDismiss = { viewModel.setLearnedMemoriesPageVisible(false) }
            )
        }
    }
}

/**
 * Sits above the conversation because it answers the question the app exists to answer:
 * where is my data right now. Counts are live, so it doubles as a receipt.
 */
@Composable
private fun StorageHeader(
    messageCount: Int,
    memoryCount: Int,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Local Mind",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(percent = 50))
                            .clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    text = buildString {
                        append(if (messageCount == 1) "1 message" else "$messageCount messages")
                        append(" · ")
                        append(if (memoryCount == 1) "1 memory" else "$memoryCount memories")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(retentionDays: Int, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp)
        ) {
            Text(
                text = "Say something.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("This conversation is written to your phone, not to a server. ")
                    if (retentionDays > 0) {
                        append("Messages are cleared after $retentionDays days to save space. ")
                    }
                    append("Pin one to keep it for good.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MessageRow(message: MessageEntity, onTogglePin: () -> Unit) {
    val fromUser = message.role == Role.USER
    val timeText = remember(message.createdAt) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.createdAt))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        if (fromUser) Spacer(Modifier.weight(1f))

        Column(
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (fromUser) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF4F46E5), Color(0xFF3B82F6))
                            )
                        )
                        .clickable(onClick = onTogglePin)
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .clickable(onClick = onTogglePin)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column {
                        when {
                            message.failed -> Text(
                                text = "Message delivery issue. Tap to retry.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )

                            message.text.isEmpty() && message.streaming -> Thinking()

                            else -> Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFF1F5F9)
                            )
                        }
                        message.sources?.takeIf { it.isNotBlank() }?.let { Sources(it) }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B)
                )
                if (message.pinned) {
                    Spacer(Modifier.width(6.dp))
                    StorageBadge(message.syncState)
                }
            }
        }

        if (!fromUser) Spacer(Modifier.weight(1f))
    }
}

/** Tells the user exactly where this pinned message lives. */
@Composable
private fun StorageBadge(state: SyncState) {
    val colors = LocalChatColors.current
    val (tint, label) = when (state) {
        SyncState.SYNCED -> colors.cloud to "Pinned · backed up"
        SyncState.PENDING -> colors.muted to "Pinned · waiting to back up"
        SyncState.LOCAL_ONLY -> colors.local to "Pinned · this phone only"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.95f))
                .border(1.dp, Color(0xFF6366F1), RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom",
                    tint = Color(0xFF818CF8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Scroll to bottom",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun Sources(encoded: String) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val parsed = remember(encoded) {
        encoded.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }
    if (parsed.isEmpty()) return

    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parsed.take(4).forEach { (title, url) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }
            ) {
                Dot(colors.cloud, size = 5.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.cloud,
                    maxLines = 1
                )
            }
        }
    }
}

/** The one piece of un-prompted motion in the app, and only while waiting. */
@Composable
private fun Thinking() {
    val colors = LocalChatColors.current
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(Modifier.alpha(alpha), verticalAlignment = Alignment.CenterVertically) {
        Dot(colors.local, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Dot(colors.local, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Dot(colors.local, size = 6.dp)
    }
}

@Composable
private fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 7.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
    )
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    intelligenceStatus: IntelligenceStatus,
    isAutoMode: Boolean,
    manualSelectedMode: IntelligenceStatus,
    onToggleAutoMode: (Boolean) -> Unit,
    onSelectManualMode: (IntelligenceStatus) -> Unit
) {
    var showComposerModeMenu by remember { mutableStateOf(false) }

    val activeStatus = if (isAutoMode) intelligenceStatus else manualSelectedMode
    val (dotColor, rawLabel) = when (activeStatus) {
        IntelligenceStatus.AI_MODE -> Color(0xFF10B981) to "AI"
        IntelligenceStatus.MEMORY_MODE -> Color(0xFF06B6D4) to "Memory"
        IntelligenceStatus.SEARCH_MODE -> Color(0xFFF1F5F9) to "Search"
        IntelligenceStatus.LOADING -> Color(0xFFF59E0B) to "Init"
    }

    val displayLabel = if (isAutoMode) "Auto ($rawLabel)" else rawLabel
    val active = enabled && value.isNotBlank()

    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Outer Capsule Container
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Text Input
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Ask Local Mind...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFF64748B)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(Color(0xFF818CF8)),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Mode Option Button
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0F172A))
                            .clickable { showComposerModeMenu = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Dot(dotColor, size = 6.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = dotColor
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Mode Menu",
                            tint = dotColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showComposerModeMenu,
                        onDismissRequest = { showComposerModeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("⚡ Auto Mode") },
                            onClick = {
                                onToggleAutoMode(true)
                                showComposerModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🟢 AI Mode (Manual)") },
                            onClick = {
                                onToggleAutoMode(false)
                                onSelectManualMode(IntelligenceStatus.AI_MODE)
                                showComposerModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🩵 Memory Mode (Manual)") },
                            onClick = {
                                onToggleAutoMode(false)
                                onSelectManualMode(IntelligenceStatus.MEMORY_MODE)
                                showComposerModeMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("⚪ Search Mode (Manual)") },
                            onClick = {
                                onToggleAutoMode(false)
                                onSelectManualMode(IntelligenceStatus.SEARCH_MODE)
                                showComposerModeMenu = false
                            }
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Send or Stop Button inside input capsule
                if (sending) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFFEF4444))
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop response",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (active) {
                                    Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF3B82F6)))
                                } else {
                                    SolidColor(Color(0xFF334155))
                                }
                            )
                            .clickable(enabled = active, onClick = onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowUpward,
                            contentDescription = "Send message",
                            tint = if (active) Color.White else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full disclosure screen: every fact the app holds, which of them left the phone, and
 * why. Deleting here deletes on the server too.
 */
@Composable
private fun MemorySheet(
    memories: List<MemoryEntity>,
    retentionDays: Int,
    expiringCount: Int,
    storageBytes: Long,
    lastSweep: String?,
    downloadStatus: DownloadStatus,
    isModelInstalled: Boolean,
    isOnlineEnabled: Boolean,
    onToggleOnline: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onUninstallModel: () -> Unit,
    onOpenLearnedMemories: () -> Unit,
    onForget: (MemoryEntity) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onClearNow: () -> Unit,
    onDeleteEverything: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val colors = LocalChatColors.current
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        // Back Button Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Settings & Memory",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider(color = colors.hairline)
        Spacer(Modifier.height(16.dp))

        // AI Local Model Section Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI Local Model",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    if (isModelInstalled) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🟢 Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF94A3B8).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⚪ Not Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Runs Llama 3.2 100% on-device on your phone GPU/CPU with zero internet required. Model size: 807 MB (Requires 900 MB free storage).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8)
                )

                Spacer(Modifier.height(12.dp))

                when (downloadStatus) {
                    is DownloadStatus.Downloading -> {
                        val progress = downloadStatus.progressPercent
                        val mbDownloaded = downloadStatus.bytesDownloaded / (1024 * 1024)
                        val totalMb = if (downloadStatus.totalBytes > 0) downloadStatus.totalBytes / (1024 * 1024) else 807L

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Downloading AI Local Model...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = if (progress >= 0) "$progress% ($mbDownloaded / $totalMb MB)" else "$mbDownloaded MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF818CF8)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (progress >= 0) progress / 100f else 0.5f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF6366F1),
                                trackColor = Color(0xFF334155)
                            )
                        }
                    }

                    is DownloadStatus.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = downloadStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onDownloadModel,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry Download AI Local Model (807 MB)")
                        }
                    }

                    else -> {
                        if (!isModelInstalled) {
                            Button(
                                onClick = onDownloadModel,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download AI Local Model (807 MB)")
                            }
                        } else {
                            Button(
                                onClick = onUninstallModel,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Uninstall AI Local Model", color = Color(0xFFFCA5A5))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Online Web Knowledge Search Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = "Online Search",
                            tint = if (isOnlineEnabled) Color(0xFF818CF8) else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Online Web Search",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isOnlineEnabled) {
                            "Online Mode: LocalMind can search web knowledge for online information queries."
                        } else {
                            "100% Offline Mode: Web knowledge search is completely disabled."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Switch(
                    checked = isOnlineEnabled,
                    onCheckedChange = onToggleOnline,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF6366F1),
                        uncheckedThumbColor = Color(0xFF94A3B8),
                        uncheckedTrackColor = Color(0xFF0F172A)
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Learned Memories Button Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .clickable(onClick = onOpenLearnedMemories)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = Color(0xFF818CF8),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Learned Memories",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (memories.isEmpty()) "0 learned facts" else "${memories.size} learned memories stored locally",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onOpenLearnedMemories,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("View")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = colors.hairline)
        Spacer(Modifier.height(16.dp))

        StorageSection(
            retentionDays = retentionDays,
            expiringCount = expiringCount,
            storageBytes = storageBytes,
            lastSweep = lastSweep,
            onRetentionChange = onRetentionChange,
            onClearNow = onClearNow
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = colors.hairline)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDeleteEverything) {
            Text(
                text = "Delete everything, here and in the cloud",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun LearnedMemoriesSheet(
    memories: List<MemoryEntity>,
    currentPage: Int,
    importExportError: String?,
    onPageChange: (Int) -> Unit,
    onExport: () -> Unit,
    onImportUri: (Uri) -> Unit,
    onClearError: () -> Unit,
    onForget: (MemoryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalChatColors.current
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onImportUri(uri)
        }
    }

    val pageSize = 10
    val totalCount = memories.size
    val maxPage = if (totalCount == 0) 1 else (totalCount + pageSize - 1) / pageSize
    val safePage = currentPage.coerceIn(1, maxPage)

    val startIndex = (safePage - 1) * pageSize
    val pageMemories = if (startIndex < totalCount) {
        memories.subList(startIndex, minOf(startIndex + pageSize, totalCount))
    } else emptyList()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        // Back Button Header Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Learned Memories",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider(color = colors.hairline)
        Spacer(Modifier.height(16.dp))

        // Control Bar: Export to Excel & Import Data
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onExport,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = Color(0xFF818CF8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Export Excel", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }

            Button(
                onClick = { filePickerLauncher.launch("*/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Import Data", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }

        // Validation Error Alert Banner
        if (importExportError != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                    .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = importExportError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onClearError,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("✕", color = Color(0xFFFCA5A5))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Horizontal Page Indexing Bar (1 to maxPage)
        if (maxPage > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Page $safePage of $maxPage",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF94A3B8)
                )
                Spacer(Modifier.weight(1f))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(maxPage) { pageIdx ->
                        val pageNum = pageIdx + 1
                        val isSelected = pageNum == safePage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF6366F1) else Color(0xFF1E293B))
                                .clickable { onPageChange(pageNum) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$pageNum",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Memories List (10 per page)
        if (pageMemories.isEmpty()) {
            Text(
                text = "No learned memories on this page.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                pageMemories.forEach { memory ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = memory.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Category: ${memory.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            TextButton(onClick = { onForget(memory) }) {
                                Text(
                                    text = "Forget",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionModal(onGrantPermission: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E293B))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0xFF6366F1).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = "File Access",
                        tint = Color(0xFF818CF8),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "100% Local AI File Access",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "LocalMind operates 100% on your phone without cloud servers. To load local AI model files and manage encrypted local memories, file access permission is required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onGrantPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Grant File Access",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Retention controls, deliberately placed in the same sheet as the memory list.
 *
 * Auto-deletion and "what this app knows" are the same question from two directions, and
 * seeing them together is what makes the tradeoff legible: old chats disappear, but the
 * facts above them don't, because those were extracted when the message was sent.
 */
@Composable
private fun StorageSection(
    retentionDays: Int,
    expiringCount: Int,
    storageBytes: Long,
    lastSweep: String?,
    onRetentionChange: (Int) -> Unit,
    onClearNow: () -> Unit
) {
    val colors = LocalChatColors.current

    Text(
        text = "Storage",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Chat history uses ${ChatViewModel.formatBytes(storageBytes)} on this phone. " +
            "Older messages are deleted automatically. Pinned messages and the list " +
            "above are always kept.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.muted
    )

    Spacer(Modifier.height(14.dp))
    Text(
        text = "Keep history for",
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RetentionPolicy.CHOICES.forEach { days ->
            val selected = days == retentionDays
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) colors.local else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) colors.local else colors.hairline,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onRetentionChange(days) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (days == 0) "Forever" else "${days}d",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Naming the number before the user commits is the difference between a setting
    // they trust and one they're afraid to touch.
    Text(
        text = when {
            retentionDays == 0 -> "Nothing is deleted automatically."
            expiringCount == 0 -> "Nothing is old enough to be cleared right now."
            expiringCount == 1 -> "1 message is past $retentionDays days and will be cleared."
            else -> "$expiringCount messages are past $retentionDays days and will be cleared."
        },
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted
    )

    lastSweep?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = colors.local
        )
    }

    if (retentionDays != 0 && expiringCount > 0) {
        TextButton(onClick = onClearNow) {
            Text(
                text = "Clear now and free up space",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.local
            )
        }
    }
}
