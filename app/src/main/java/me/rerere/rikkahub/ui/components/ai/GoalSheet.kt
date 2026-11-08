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
                    placeholder = { Text("例如：把项目里的所有旧 API 迁移到新接口") },
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
                    MetricCell("消耗", "${goal.tokensUsed} tokens")
                    MetricCell("耗时", "${goal.timeUsedSeconds}s")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    // 恢复 / 暂停：只有这两个状态互转是有意义的
                    when (goal.status) {
                        GoalStatus.ACTIVE -> TextButton(
                            onClick = {
                                GoalStore.set(
                                    goalKey,
                                    goal.copy(status = GoalStatus.PAUSED),
                                )
                            }
                        ) { Text("暂停") }

                        GoalStatus.PAUSED -> TextButton(
                            onClick = {
                                GoalStore.set(
                                    goalKey,
                                    goal.copy(status = GoalStatus.ACTIVE),
                                )
                                GoalStore.clearBlocked(goalKey)
                            }
                        ) { Text("继续") }

                        else -> Unit
                    }

                    TextButton(
                        onClick = {
                            GoalStore.clear(goalKey)
                            onDismiss()
                        }
                    ) {
                        Text("放弃目标", color = MaterialTheme.colorScheme.error)
                    }
                }

                if (goal.status == GoalStatus.BLOCKED) {
                    Text(
                        text = "连续三轮没进展，已经停下。要继续的话点「继续」，阻塞计数会清零重新算。",
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
