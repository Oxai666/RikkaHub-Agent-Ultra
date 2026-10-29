package me.rerere.rikkahub.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject

/**
 * Bottom-anchored chat overlay rendered above the BrowserView's WebView. Two states:
 *
 *  - **Pill** (default): a small floating chat icon at the bottom-right corner. Semi-
 *    transparent so it doesn't compete with the page underneath. Tap to expand.
 *  - **Expanded**: a translucent card with the latest assistant reply (single-line preview,
 *    scrollable in-place when long), an "Generating…" indicator while a turn is in flight,
 *    and a single-line input + send button. Tap the X to collapse back to pill.
 *
 * The overlay always operates on the conversation that opened the browser. browser_open
 * threads the caller's conversation id into the intent extra; for Settings-launched
 * sessions where no conversation is associated, the overlay falls back to whatever
 * convId is currently active in [ChatService] sessions, and if none exists, hides itself
 * entirely (manual browsing path doesn't need an AI input — the user can ask a fresh
 * question from the in-app chat instead).
 *
 * Sends post directly via [ChatService.sendMessage] using the same path as the in-app
 * chat surface, so HARDLINE / approval / token accounting all apply unchanged. The user
 * watches the model drive the page in real time without ever leaving this Activity.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserMiniChat(
    conversationId: Uuid?,
    modifier: Modifier = Modifier,
) {
    if (conversationId == null) return

    val chatService = koinInject<ChatService>()
    val conversation by chatService.getConversationFlow(conversationId).collectAsStateWithLifecycle()
    val processingStatus by chatService
        .getProcessingStatusFlow(conversationId)
        .collectAsStateWithLifecycle()

    val isGenerating = processingStatus != null
    // 最近 6 条（用户 + 助手），每条截断。之前只取「最后一条助手文本」，
    // 用户进浏览器看不到上下文，也看不到自己刚说的话。
    val recentMessages = remember(
        conversation.currentMessages.size,
        conversation.currentMessages.lastOrNull()?.id,
    ) {
        conversation.currentMessages
            .mapNotNull { msg ->
                // 之前只收 Text part，于是「只调工具还没出正文」的助手消息整条消失 ——
                // 用户看到的就是「只有我说话，AI 不吭声」。思考、工具调用也一并渲染。
                val lines = buildList {
                    msg.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text ->
                                if (part.text.isNotBlank()) add(part.text.trim())

                            is UIMessagePart.Reasoning ->
                                if (part.reasoning.isNotBlank()) add("💭 " + part.reasoning.trim())

                            is UIMessagePart.Tool ->
                                add(
                                    "🔧 ${part.toolName}" +
                                        if (part.output.isNotEmpty()) " ✓" else " …"
                                )

                            else -> Unit
                        }
                    }
                }
                val text = lines.joinToString("\n")
                if (text.isBlank()) null else (msg.role == MessageRole.USER) to text.take(1200)
            }
            .takeLast(8)
    }

    var expanded by remember { mutableStateOf(false) }
    // 悬浮窗位置：以右下角为原点，offset 为负值（向左上）。拖动即改这两个数。
    var pos by remember { mutableStateOf(Offset(-16f, -16f)) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .imePadding()
            .navigationBarsPadding(),
    ) {
        val boundsW = constraints.maxWidth.toFloat()
        val boundsH = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .onSizeChanged { contentSize = it }
                .pointerInput(boundsW, boundsH) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val minX = -(boundsW - contentSize.width).coerceAtLeast(0f)
                        val minY = -(boundsH - contentSize.height).coerceAtLeast(0f)
                        pos = Offset(
                            (pos.x + drag.x).coerceIn(minX, 0f),
                            (pos.y + drag.y).coerceIn(minY, 0f),
                        )
                    }
                },
        ) {
            if (expanded) {
                BrowserMiniChatExpandedCard(
                    recentMessages = recentMessages,
                    isGenerating = isGenerating,
                    onSend = { text ->
                        chatService.sendMessage(
                            conversationId = conversationId,
                            content = listOf(UIMessagePart.Text(text)),
                        )
                    },
                    onCollapse = { expanded = false },
                    modifier = Modifier.width(340.dp),
                )
            } else {
                BrowserMiniChatPill(
                    preview = recentMessages.lastOrNull()?.second,
                    isGenerating = isGenerating,
                    onClick = { expanded = true },
                )
            }
        }
    }
}

/**
 * 收起态：一条挂在页面上的消息条（不是圆圈图标）。
 *
 * 用户反馈「那个死人小球狗用啊」—— 一个 Sparkles 圆球既看不到内容也看不出在等什么。
 * 换成条状：左边显示最新一条消息的摘要，生成中显示转圈，点一下展开成对话窗，可拖动。
 */
@Composable
private fun BrowserMiniChatPill(
    preview: String?,
    isGenerating: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(240.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Sparkles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isGenerating -> "AI 正在处理…"
                    preview.isNullOrBlank() -> "问 AI 点什么"
                    else -> preview.replace("\n", " ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BrowserMiniChatExpandedCard(
    recentMessages: List<Pair<Boolean, String>>,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: "Ask the AI" + collapse.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.browser_mini_chat_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.browser_mini_chat_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 最近几条消息：用户靠右、助手靠左，整体限高 220dp 可滚动。
            if (recentMessages.isNotEmpty() || isGenerating) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        recentMessages.forEach { (fromUser, text) ->
                            Text(
                                text = (if (fromUser) "你：" else "AI：") + text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (fromUser) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        if (isGenerating) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.browser_mini_chat_generating),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            // Input row.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.browser_mini_chat_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val q = input.trim()
                            if (q.isNotEmpty() && !isGenerating) {
                                onSend(q)
                                input = ""
                                keyboard?.hide()
                            }
                        },
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                )
                FilledIconButton(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotEmpty() && !isGenerating) {
                            onSend(q)
                            input = ""
                            keyboard?.hide()
                        }
                    },
                    enabled = input.isNotBlank() && !isGenerating,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = HugeIcons.Sparkles,
                        contentDescription = stringResource(R.string.browser_mini_chat_send),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
