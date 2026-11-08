package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.goal.Goal
import me.rerere.rikkahub.data.goal.GoalStatus
import me.rerere.rikkahub.data.goal.GoalStore
import me.rerere.rikkahub.ui.components.ui.icons.IconFlag

/**
 * 目标面板。
 *
 * 只做三件事：立目标、暂停/恢复、放弃。
 * 不在这里编辑目标正文——改目标等于换任务，应该先清掉再重立，
 * 否则「改了目标的同一个 goal」在续跑调度里会很难判。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalSheet(
    goalKey: String,
    goal: Goal?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var draft by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(IconFlag, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = "目标",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (goal == null) {
                Text(
                    text = "还没有目标。立一个之后，助手会围着它跨轮推进，而不是每轮重新开始。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("要达成什么") },
                    placeholder = { Text("描述你的目标，定义可衡量的效果，以获得最佳效果") },
                    minLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = {
                            val objective = draft.trim()
                            if (objective.isNotEmpty()) {
                                GoalStore.set(goalKey, Goal(objective = objective))
                                GoalStore.clearBlocked(goalKey)
                                GoalStore.resetContinuation(goalKey)
                                draft = ""
                                onDismiss()
                            }
                        },
                        enabled = draft.isNotBlank(),
                    ) { Text("开始") }
                }
            } else {
                Text(
                    text = goal.objective,
                    style = MaterialTheme.typography.bodyLarge,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MetricCell("状态", goal.status.displayName())
                    MetricCell(
                        "消耗",
                        if (goal.tokensUsed > 0) "${goal.tokensUsed} tokens" else "—",
                    )
                    MetricCell(
                        "耗时",
                        // 耗时实时算，不从存储里读：目标暂停期间不该继续涨，
                        // 而且存下来的数字一旦进程被杀就停了，反而不准
                        readableDuration(
                            if (goal.status.isLive) {
                                System.currentTimeMillis() - goal.createdAtMs
                            } else {
                                goal.updatedAtMs - goal.createdAtMs
                            }
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    // 按状态给不同的操作。已完成/已受阻的还给「放弃」很奇怪——
                    // 它已经不是活的了，该给的是「结束」。
                    when (goal.status) {
                        GoalStatus.ACTIVE -> TextButton(
                            onClick = {
                                GoalStore.set(
                                    goalKey,
                                    goal.copy(
                                        status = GoalStatus.PAUSED,
                                        updatedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        ) { Text("暂停") }

                        GoalStatus.PAUSED -> TextButton(
                            onClick = {
                                GoalStore.set(
                                    goalKey,
                                    goal.copy(
                                        status = GoalStatus.ACTIVE,
                                        updatedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                                GoalStore.clearBlocked(goalKey)
                            }
                        ) { Text("继续") }

                        GoalStatus.BLOCKED -> TextButton(
                            onClick = {
                                GoalStore.set(
                                    goalKey,
                                    goal.copy(
                                        status = GoalStatus.ACTIVE,
                                        updatedAtMs = System.currentTimeMillis(),
                                    ),
                                )
                                GoalStore.clearBlocked(goalKey)
                                GoalStore.resetContinuation(goalKey)
                            }
                        ) { Text("再试一次") }

                        GoalStatus.COMPLETE -> TextButton(
                            onClick = {
                                GoalStore.clear(goalKey)
                                onDismiss()
                            }
                        ) {
                            Text("结束目标", color = MaterialTheme.colorScheme.primary)
                        }

                        GoalStatus.USAGE_LIMITED,
                        GoalStatus.BUDGET_LIMITED,
                        -> TextButton(
                            onClick = {
                                GoalStore.clear(goalKey)
                                onDismiss()
                            }
                        ) {
                            Text("结束目标", color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    // 「放弃」只在还活着的时候有意义
                    if (goal.status.isLive || goal.status == GoalStatus.PAUSED) {
                        TextButton(
                            onClick = {
                                GoalStore.clear(goalKey)
                                onDismiss()
                            }
                        ) {
                            Text("放弃目标", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (goal.status == GoalStatus.BLOCKED) {
                    Text(
                        text = "连续三轮没进展，已经停下。点「再试一次」会从零开始数阻塞次数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (goal.status == GoalStatus.COMPLETE) {
                    Text(
                        text = "目标已达成。点「结束目标」把它从列表里清掉，再立新的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 毫秒转成人看得懂的时长。超过一小时就只报小时和分钟，秒没意义。 */
private fun readableDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h${minutes}m"
        minutes > 0 -> "${minutes}m${seconds}s"
        else -> "${seconds}s"
    }
}
