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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.localmind.chat.data.local.MemoryEntity
import com.localmind.chat.data.local.MessageEntity
import com.localmind.chat.data.local.Role
import com.localmind.chat.data.local.SyncState
import com.localmind.chat.data.repo.RetentionPolicy
import com.localmind.chat.data.repo.SyncPolicy
import com.localmind.chat.ui.theme.LocalChatColors

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

    val listState = rememberLazyListState()

    // The empty state and the storage section both spell out the retention window.
    // Seeing either counts as disclosure, and nothing is deleted before that.
    LaunchedEffect(messages.isEmpty(), showMemories) {
        if (messages.isEmpty() || showMemories) viewModel.markRetentionDisclosed()
    }

    // Follow the stream as tokens land, but only if the user is already near the bottom,
    // so scrolling back through history isn't yanked away from them.
    LaunchedEffect(messages.lastOrNull()?.text, messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex >= (messages.size - 4)) {
            listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
        }
    }

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
                onOpenMemories = { viewModel.setMemoriesVisible(true) }
            )

            if (messages.isEmpty()) {
                EmptyState(retentionDays = retentionDays, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
            }

            Composer(
                value = draft,
                enabled = !sending,
                onValueChange = viewModel::onDraftChange,
                onSend = viewModel::send
            )
        }
    }

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
                onForget = { viewModel.forget(it) },
                onRetentionChange = viewModel::setRetentionDays,
                onClearNow = { viewModel.clearOldChatsNow() },
                onDeleteEverything = {
                    viewModel.deleteEverything()
                    viewModel.setMemoriesVisible(false)
                }
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
    onOpenMemories: () -> Unit
) {
    val colors = LocalChatColors.current

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenMemories)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "On this phone",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(if (messageCount == 1) "1 message" else "$messageCount messages")
                        append(" · ")
                        append(if (memoryCount == 1) "1 thing learned" else "$memoryCount things learned")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted
                )
            }
            Dot(colors.local)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Review",
                style = MaterialTheme.typography.labelSmall,
                color = colors.local
            )
        }
        HorizontalDivider(color = colors.hairline)
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
    val colors = LocalChatColors.current
    val fromUser = message.role == Role.USER

    Row(Modifier.fillMaxWidth()) {
        if (fromUser) Spacer(Modifier.weight(1f))

        Column(
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (fromUser) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                        .background(colors.raised)
                        .clickable(onClick = onTogglePin)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            } else {
                // Assistant text is unboxed and carries a hairline rule instead of a
                // bubble: it's the long-form side of the conversation and reads better
                // as a column of prose.
                Row {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(if (message.text.isEmpty()) 20.dp else 0.dp)
                    )
                    Column {
                        when {
                            message.failed -> Text(
                                text = "That didn't send. Ensure your local Llama server is running and try again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )

                            message.text.isEmpty() && message.streaming -> Thinking()

                            else -> Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.clickable(onClick = onTogglePin)
                            )
                        }
                        message.sources?.takeIf { it.isNotBlank() }?.let { Sources(it) }
                    }
                }
            }

            if (message.pinned) {
                Spacer(Modifier.height(6.dp))
                StorageBadge(message.syncState)
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
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val colors = LocalChatColors.current

    Column {
        HorizontalDivider(color = colors.hairline)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, colors.hairline, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "Message",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.muted
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(colors.local),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.width(10.dp))

            val active = enabled && value.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (active) colors.local else colors.raised)
                    .clickable(enabled = active, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowUpward,
                    contentDescription = "Send message",
                    tint = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        colors.muted
                    },
                    modifier = Modifier.size(20.dp)
                )
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
    onForget: (MemoryEntity) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onClearNow: () -> Unit,
    onDeleteEverything: () -> Unit
) {
    val colors = LocalChatColors.current

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(
            text = "What this app knows",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Learned from your messages and used to keep replies consistent. " +
                "Your conversations themselves are never uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted
        )
        Spacer(Modifier.height(20.dp))

        if (memories.isEmpty()) {
            Text(
                text = "Nothing yet. Keep talking and this fills in.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(360.dp)
            ) {
                items(memories, key = { it.id }) { memory ->
                    Column {
                        Text(
                            text = memory.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(
                                if (memory.syncState == SyncState.SYNCED) {
                                    colors.cloud
                                } else {
                                    colors.local
                                },
                                size = 6.dp
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = SyncPolicy.explain(memory),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                                modifier = Modifier.weight(1f)
                            )
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
