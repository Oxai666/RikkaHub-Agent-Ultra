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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.ChatMessage
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow


/**
 * 浏览器里的「缩小版会话」浮窗。
 *
 * 收起态是一条显示最新消息的条状按钮；展开态是 360×520dp 的对话窗，
 * 气泡分左右、思考与工具折叠成灰字/蓝字、可拖动、自动滚到最新。
 *
 * 注意：消息列表**不做 remember 缓存**。流式输出时消息条数与最后一条 id 都不变，
 * 用 (size, lastId) 当 key 会让小窗停在半句话上不再刷新 —— 这是上一版最致命的问题。
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserMiniChat(
    conversationId: Uuid?,
    modifier: Modifier = Modifier,
) {
    if (conversationId == null) return

    val chatService = koinInject<ChatService>()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val conversation by chatService.getConversationFlow(conversationId).collectAsStateWithLifecycle()
    val processingStatus by chatService
        .getProcessingStatusFlow(conversationId)
        .collectAsStateWithLifecycle()

    val isGenerating = processingStatus != null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 直接用会话里的 MessageNode，渲染交给 ChatMessage —— 小窗要「和会话一模一样」，
    // 手搓气泡永远追不上主界面的工具卡片/思考折叠/Markdown。
    val nodes = conversation.messageNodes

    var expanded by remember { mutableStateOf(false) }
    // 悬浮窗位置：以右下角为原点，offset 为负值（向左上）。
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
                BrowserMiniChatCard(
                    nodes = nodes,
                    settings = settings,
                    isGenerating = isGenerating,
                    onSend = { text ->
                        chatService.sendMessage(
                            conversationId = conversationId,
                            content = listOf(UIMessagePart.Text(text)),
                        )
                    },
                    onCollapse = { expanded = false },
                    onRegenerate = { msg ->
                        // 非 suspend：内部自己开 job
                        chatService.regenerateAtMessage(conversationId, msg)
                    },
                    onFork = { msg ->
                        scope.launch {
                            runCatching { chatService.forkConversationAtMessage(conversationId, msg.id) }
                                .onSuccess {
                                    Toast.makeText(context, R.string.browser_mini_chat_forked, Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onDelete = { msg ->
                        scope.launch {
                            runCatching { chatService.deleteMessage(conversationId, msg.id) }
                        }
                    },
                )
            } else {
                BrowserMiniChatPill(
                    preview = nodes.lastOrNull()?.let { node ->
                        node.messages.lastOrNull()?.parts
                            ?.filterIsInstance<UIMessagePart.Text>()
                            ?.joinToString(" ") { it.text }
                            ?.takeIf { it.isNotBlank() }
                    },
                    isGenerating = isGenerating,
                    onClick = { expanded = true },
                )
            }
        }
    }
}

/** 收起态：一条挂在页面上的消息条，显示最新一条内容摘要。 */
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
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
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

/** 展开态：缩小版会话（气泡 + 思考 + 工具 + 输入框）。 */
@Composable
private fun BrowserMiniChatCard(
    nodes: List<MessageNode>,
    settings: Settings,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCollapse: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onFork: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val cardContext = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // 是否还「跟随」底部。默认跟随，用户一旦手动往上翻就退出跟随，
    // 自己滑回底部附近再自动恢复。
    //
    // 之前是无条件 animateScrollToItem：流式输出时这个 effect 每帧都触发，
    // 用户往上翻就被立刻顶回去；而且连续动画互相打断、排队，观感是
    // 「一直往上滑 / 来回抽动」。跟随开关交给用户，不要替他做决定。
    var followBottom by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                true
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                // 距底部 2 条以内算「还在底部」，留一点容差避免抖动
                lastVisible >= info.totalItemsCount - 2
            }
        }.collect { followBottom = it }
    }

    LaunchedEffect(nodes.size, nodes.lastOrNull()?.messages?.lastOrNull()?.parts, isGenerating) {
        if (nodes.isNotEmpty() && followBottom) {
            // scrollToItem（瞬时）而不是 animateScrollToItem：流式输出时每帧都会走到这里，
            // 动画版本会一帧起一个新动画、互相打断，看着像在抽。
            listState.scrollToItem(nodes.lastIndex)
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .width(360.dp)
            .height(520.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.browser_mini_chat_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.browser_mini_chat_close),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 直接用主界面的 ChatMessage 渲染：工具卡片、思考折叠、Markdown、分支标记
            // 全部与会话页一致，小窗只是缩小了宽度。
            // ChatMessage 内部会读 LocalNavController（点击消息里的链接/图片时跳转）。
            // 浏览器 Activity 没有 NavHost，缺这个 CompositionLocal 会直接
            // IllegalStateException: No Navigator provided 崩掉。这里给一个孤立 Navigator：
            // 浏览态下这些跳转本来就用不上，点不动好过崩。
            val miniNav = remember { Navigator(mutableListOf<NavKey>()) }
            // 工具卡片里的复制/重跑按钮会读 LocalToaster，同样没有默认值。
            val miniToaster = rememberToasterState()
            // 消息操作栏里的朗读按钮会读 LocalTTSState，同样没有默认值。
            val miniTts = rememberCustomTtsState()
            CompositionLocalProvider(
                LocalSettings provides settings,
                LocalNavController provides miniNav,
                LocalToaster provides miniToaster,
                LocalTTSState provides miniTts,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(nodes) { index, node ->
                        ChatMessage(
                            node = node,
                            loading = isGenerating && index == nodes.lastIndex,
                            generationActive = isGenerating,
                            lastMessage = index == nodes.lastIndex,
                            onFork = { onFork(node.currentMessage) },
                            onRegenerate = { onRegenerate(node.currentMessage) },
                            onEdit = {
                                // 小窗里没有编辑面板，就把原文回填到输入框，
                                // 用户改完直接发 —— 比留个死按钮强。
                                val text = node.currentMessage.parts
                                    .filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("") { it.text }
                                if (text.isNotBlank()) {
                                    input = text
                                    keyboard?.show()
                                }
                            },
                            onShare = {
                                val text = node.currentMessage.parts
                                    .filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("") { it.text }
                                if (text.isNotBlank()) {
                                    val cm = cardContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("message", text))
                                    Toast.makeText(cardContext, R.string.browser_mini_chat_copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { onDelete(node.currentMessage) },
                            onUpdate = {},
                        )
                    }
                    if (isGenerating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.browser_mini_chat_generating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }
            }

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
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
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
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = HugeIcons.Sparkles,
                        contentDescription = stringResource(R.string.browser_mini_chat_send),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

