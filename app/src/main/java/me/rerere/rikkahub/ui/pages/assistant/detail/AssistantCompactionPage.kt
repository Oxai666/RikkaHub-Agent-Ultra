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
            // ── ① 压缩大小 ──────────────────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_threshold),
                desc = stringResource(R.string.assistant_compaction_threshold_desc),
            )
            OutlinedTextField(
                value = assistant.compactionThresholdK?.toString().orEmpty(),
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }
                    vm.updateAssistant { aid ->
                        aid.copy(compactionThresholdK = digits.toIntOrNull())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.assistant_compaction_threshold_unit)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("500") },
            )

            // ── ② 保留消息 ──────────────────────────────────────
            SectionLabel(
                title = stringResource(R.string.assistant_compaction_keep),
                desc = stringResource(R.string.assistant_compaction_keep_desc),
            )
            OutlinedTextField(
                value = assistant.compactionKeepMessages.toString(),
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }
                    vm.updateAssistant { aid ->
                        aid.copy(compactionKeepMessages = digits.toIntOrNull()?.coerceIn(0, 500) ?: 0)
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
