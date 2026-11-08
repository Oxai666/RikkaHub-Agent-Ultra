package me.rerere.rikkahub.ui.pages.setting.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.safety.FileSnapshotManager
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.safety.SnapshotRoots
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全快照页。
 *
 * 快照是「不可逆操作之前的备份」，但它在哪、有没有拍、占多少空间，
 * 之前只能靠问 AI——而 AI 也未必知道。
 *
 * 这个页面把三件事直接摆出来：有哪些快照、每个拍了什么、占了多大。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSnapshotsPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()

    // 工作区绑定了就显示工作区里的那份，否则显示全局那份
    val context = LocalContext.current
    val root = remember(assistant.workspaceId) {
        SnapshotRoots.of(context, assistant.workspaceId?.toString())
    }

    var records by remember { mutableStateOf(FileSnapshotManager.list(root)) }
    var restoringId by remember { mutableStateOf<String?>(null) }
    // 恢复结果弹一次 Snackbar 就够了，不占卡片位置
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("安全快照") },
                navigationIcon = { BackButton() },
                actions = {
                    if (records.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                FileSnapshotManager.clearAll(root)
                                records = emptyList()
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = "清空全部")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "不可逆操作（删除、擦除、覆写）通过审批之前，会自动把目标文件复制一份。" +
                        "删错了可以从这里恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text(
                    text = root.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (records.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = "还没有快照",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "普通写文件不会触发快照，只有不可逆操作才会",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            items(records, key = { it.id }) { record ->
                val captured = record.entries.count { it.existed && it.snapshotPath.isNotEmpty() }
                val skipped = record.entries.count { it.skipped != null }
                val bytes = record.entries.sumOf { it.bytes }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = timeFormat.format(Date(record.createdAtMs)),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = formatBytes(bytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (captured > 0) {
                                    TextButton(
                                        enabled = restoringId == null,
                                        onClick = {
                                            restoringId = record.id
                                            val result = FileSnapshotManager.restore(
                                                root = root,
                                                record = record,
                                                resolveTarget = { sandboxPath ->
                                                    SnapshotRoots.resolveTarget(
                                                        context = context,
                                                        workspaceId = assistant.workspaceId?.toString(),
                                                        sandboxPath = sandboxPath,
                                                    )
                                                },
                                            )
                                            restoringId = null
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (result.failed.isNotEmpty()) {
                                                        "恢复了 ${result.restored} 个，${result.failed.size} 个失败：${result.failed.first()}"
                                                    } else {
                                                        "恢复了 ${result.restored} 个文件"
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        Text(
                                            if (restoringId == record.id) "恢复中…" else "恢复",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "由 ${record.toolName} 触发",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = buildString {
                                append("备份 $captured 个文件")
                                if (skipped > 0) append("，跳过 $skipped 个（超限或读不了）")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // 列前三个文件，够判断这是哪次操作了
                        record.entries
                            .filter { it.existed && it.snapshotPath.isNotEmpty() }
                            .take(3)
                            .forEach { entry ->
                                Text(
                                    text = "· ${entry.originalPath}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        if (captured > 3) {
                            Text(
                                text = "…还有 ${captured - 3} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024}MB"
    bytes >= 1024 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}
