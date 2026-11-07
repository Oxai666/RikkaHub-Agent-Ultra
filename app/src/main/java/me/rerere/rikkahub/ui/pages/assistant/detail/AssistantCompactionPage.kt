package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 压缩对话。
 *
 * 和「记忆」是两件事：
 *  - 记忆是 AI 主动记下来的事实
 *  - 压缩是把长对话摘要掉腾出上下文；摘要的同时会把**完整对话**按项目导出格式
 *    写进 App 私有目录，AI 之后可以用 search_history 回查原文
 */
@Composable
fun AssistantCompactionPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 三个数字框都用本地缓冲：直接从 assistant 里读值、每次按键回写，
    // 清空的那一瞬间会被 toString()/?: 0 补成 "0"，再打一位就成了 "05" → 50。
    var targetInput by remember(assistant.id) {
        mutableStateOf(assistant.compactionTargetK?.toString().orEmpty())
    }
    var thresholdInput by remember(assistant.id) {
        mutableStateOf(assistant.compactionThresholdK?.toString().orEmpty())
    }
    var keepInput by remember(assistant.id) {
        mutableStateOf(assistant.compactionKeepMessages.toString())
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_compaction_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── ① 压缩大小（压缩后缩到多少）────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_target),
                desc = stringResource(R.string.assistant_compaction_target_desc),
            )
            OutlinedTextField(
                value = targetInput,
                onValueChange = { raw ->
                    targetInput = raw.filter { it.isDigit() }.take(6)
                    vm.updateAssistant { aid ->
                        aid.copy(compactionTargetK = targetInput.toIntOrNull())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.assistant_compaction_threshold_unit)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("32") },
            )

            // ── ② 压缩阈值（超过多少才压）────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_threshold),
                desc = stringResource(R.string.assistant_compaction_threshold_desc),
            )
            OutlinedTextField(
                value = thresholdInput,
                onValueChange = { raw ->
                    thresholdInput = raw.filter { it.isDigit() }.take(6)
                    vm.updateAssistant { aid ->
                        aid.copy(compactionThresholdK = thresholdInput.toIntOrNull())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.assistant_compaction_threshold_unit)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("500") },
            )

            // ── ② 保留消息 ──────────────────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_keep),
                desc = stringResource(R.string.assistant_compaction_keep_desc),
            )
            OutlinedTextField(
                value = keepInput,
                onValueChange = { raw ->
                    keepInput = raw.filter { it.isDigit() }.take(3)
                    keepInput.toIntOrNull()?.let { value ->
                        vm.updateAssistant { aid ->
                            aid.copy(compactionKeepMessages = value.coerceIn(0, 500))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            // ── ③ 附加说明 ──────────────────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_extra),
                desc = stringResource(R.string.assistant_compaction_extra_desc),
            )
            OutlinedTextField(
                value = assistant.compactionExtraInstruction,
                onValueChange = { vm.updateAssistant { aid -> aid.copy(compactionExtraInstruction = it) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text(stringResource(R.string.assistant_compaction_extra_hint)) },
            )

            // ── ④ 自动压缩 ──────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.assistant_compaction_auto),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.assistant_compaction_auto_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = assistant.compactionAutoEnabled,
                        onCheckedChange = { vm.updateAssistant { aid -> aid.copy(compactionAutoEnabled = it) } },
                    )
                }
            }

            Text(
                text = stringResource(R.string.assistant_compaction_archive_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, desc: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
