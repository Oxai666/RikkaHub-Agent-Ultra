package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Lists every tool the user has granted "Always Allow" to (across the whole app, persistent
 * to disk) and lets them revoke individual entries or all of them at once.
 *
 * Also surfaces the "I AM STUPID" YOLO toggle: when enabled, every tool call auto-approves
 * across every conversation and every surface. HARDLINE patterns (rm -rf /, mkfs, …) still
 * block; nothing else does. Behind a confirm dialog because flipping it on is a live
 * foot-gun and the user should own the choice deliberately.
 *
 * "Allow for this chat" grants are NOT shown here — they live in process memory only and
 * vanish on /new or app restart. There's no useful surface for them in Settings.
 */
@Composable
fun SettingToolApprovalsPage() {
    val prefs: ToolApprovalPreferences = koinInject()
    val granted by prefs.alwaysAllowFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val yolo by prefs.globalYoloFlow.collectAsStateWithLifecycle(initialValue = false)
    val dangerousUnlocked by prefs.dangerousUnlockedFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val dangerousEnabled by prefs.dangerousEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var pendingYoloEnable by remember { mutableStateOf(false) }
    var showDangerousDialog by remember { mutableStateOf(false) }
    var dangerousInput by remember { mutableStateOf("") }
    if (pendingYoloEnable) {
        AlertDialog(
            onDismissRequest = { pendingYoloEnable = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_yolo_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.setting_page_tool_approvals_yolo_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingYoloEnable = false
                    scope.launch { prefs.setYolo(true) }
                }) {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_yolo_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingYoloEnable = false }) {
                    Text(stringResource(R.string.setting_page_tool_approvals_yolo_dialog_cancel))
                }
            },
        )
    }

    // ── 无审核模式的确认面板 ──
    // 要用户手打一整句话才能开。不是形式主义：它关掉的是最后两道防线
    // （HARDLINE 和安全层越界预拒）。让人一个字一个字敲出来，
    // 至少说明他读到了自己在同意什么。
    if (showDangerousDialog) {
        val requiredPhrase = "我自愿开放无审批模式，承担一切破坏风险。"
        // 严格匹配：去掉首尾空白后必须一字不差。不忽略标点、不忽略大小写，
        // 错一个字说明没认真看。
        val matches = dangerousInput.trim() == requiredPhrase
        AlertDialog(
            onDismissRequest = {
                showDangerousDialog = false
                dangerousInput = ""
            },
            title = {
                Text(
                    text = if (dangerousEnabled) "关闭无审核模式？" else "开启无审核模式？",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (dangerousEnabled) {
                        Text(
                            text = "关闭后，审批、HARDLINE 和安全层越界预拒会重新生效。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = "开启后：所有工具、所有危险命令都不再有任何审批和拒绝。" +
                                "包括 HARDLINE（rm -rf /、mkfs、shutdown、dd 到块设备）" +
                                "和 root / shizuku 的强制确认。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "快照照常创建——那是唯一的退路，不会被关掉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "把下面这句话完整抄一遍：",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = requiredPhrase,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedTextField(
                            value = dangerousInput,
                            onValueChange = { dangerousInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("在这里输入") },
                            singleLine = false,
                            maxLines = 3,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            prefs.setDangerousMode(!dangerousEnabled)
                        }
                        showDangerousDialog = false
                        dangerousInput = ""
                    },
                    enabled = dangerousEnabled || matches,
                ) {
                    Text(
                        if (dangerousEnabled) "关闭" else "确定开启",
                        color = if (dangerousEnabled || matches) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDangerousDialog = false
                        dangerousInput = ""
                    }
                ) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tool_approvals)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // YOLO toggle. Top of the page so the user always sees its state when they
            // come here. Background goes red when the toggle is on so it's visually
            // unmistakable that approvals are off.
            item {
                val containerColor =
                    if (yolo) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                val onContainer =
                    if (yolo) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_yolo_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onContainer,
                        )
                        Text(
                            text = stringResource(
                                if (yolo) R.string.setting_page_tool_approvals_yolo_on
                                else R.string.setting_page_tool_approvals_yolo_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer,
                        )
                    }
                    Switch(
                        checked = yolo,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                pendingYoloEnable = true
                            } else {
                                scope.launch { prefs.setYolo(false) }
                            }
                        },
                        colors = if (yolo) SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.error,
                            checkedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                        ) else SwitchDefaults.colors(),
                    )
                }
            }

            // ── 危险授权：无审核模式 ──
            // 只有「关于页连点 5 下头像」解锁之后才渲染。平时整块不出现，
            // 免得用户在设置里随手翻到一个能把所有闸关掉的开关。
            if (dangerousUnlocked) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (dangerousEnabled) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Shield01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (dangerousEnabled) "无审核模式 · 已开启" else "危险授权",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = if (dangerousEnabled) {
                                        "所有工具、所有命令都不再审批和拒绝。" +
                                            "HARDLINE 和越界预拒已关闭。快照仍在拍。"
                                    } else {
                                        "关闭全部审批与拒绝（含 HARDLINE）。需要手打一句话确认。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (dangerousEnabled) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showDangerousDialog = true }) {
                                Text(
                                    text = if (dangerousEnabled) "关闭无审核模式" else "开启无审核模式",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_header),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (granted.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                item {
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        granted.sorted().forEach { toolName ->
                            item(
                                headlineContent = { Text(toolName) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        scope.launch { prefs.revoke(toolName) }
                                    }) {
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(
                                                R.string.setting_page_tool_approvals_revoke
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { scope.launch { prefs.revokeAll() } },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_revoke_all))
                    }
                }
            }
        }
    }
}
