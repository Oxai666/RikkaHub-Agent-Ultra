package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections, key = { it.id }) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked -> onToggle(injection.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(lorebooks, key = { it.id }) { lorebook ->
            ListItem(
                headlineContent = {
                    Text(lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                },
                supportingContent = if (lorebook.description.isNotBlank()) {
                    {
                        Text(
                            text = lorebook.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(lorebook.id),
                        onCheckedChange = { checked -> onToggle(lorebook.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

/**
 * 技能分类的展示顺序与中文名。
 *
 * `original` 排最前：那是 RikkaHub 自带的，用户最熟悉；
 * 后面按「攻击面 → 防御 → 其他」递减排列，破甲/逆向/渗透这类
 * 靠前是因为装了这套技能的人多半是冲它们来的。
 */
private val SKILL_CATEGORY_ORDER = listOf(
    "original" to "内置",
    // 工具链排在第二：装环境是所有逆向/渗透的第一步，
    // 用户第一次打开列表最可能先找的就是它
    "tool" to "工具链",
    "reverse" to "逆向",
    "crack" to "破甲",
    "pentest" to "渗透",
    "game" to "游戏安全",
    "gray" to "敏感",
    "creative" to "创作",
    "other" to "其他",
)

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 批量开关。
     *
     * 不能靠循环调 [onToggle]：那个回调每次基于**调用方持有的旧快照**算新集合，
     * 连调 N 次只有最后一次生效，结果就是「点全选只勾上最后一个」。
     * 批量必须一次性算出完整集合并提交。
     */
    onBulkToggle: ((List<String>, Boolean) -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    // 按 category 分组；没写 category 的归 other，老的自建技能不会消失
    val grouped = remember(skills) {
        skills.groupBy { it.category?.lowercase() ?: "other" }
    }
    val orderedKeys = remember(grouped) {
        val known = SKILL_CATEGORY_ORDER.map { it.first }.filter { it in grouped }
        val unknown = grouped.keys.filter { key -> SKILL_CATEGORY_ORDER.none { it.first == key } }.sorted()
        known + unknown
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        orderedKeys.forEach { key ->
            val items = grouped[key].orEmpty().sortedBy { it.name }
            if (items.isEmpty()) return@forEach

            val label = SKILL_CATEGORY_ORDER.firstOrNull { it.first == key }?.second ?: key
            val enabledInGroup = items.count { enabledSkills.contains(it.name) }

            item(key = "header-$key") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (enabledInGroup > 0) {
                                "$enabledInGroup/${items.size}"
                            } else {
                                items.size.toString()
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 整组开关：22 条渗透技能一条条点太累。
                    // 有批量回调就走批量，没有就退化成逐条（会丢状态，调用方要自己负责）。
                    TextButton(
                        onClick = {
                            val names = items.map { it.name }
                            val allOn = names.all { enabledSkills.contains(it) }
                            if (onBulkToggle != null) {
                                onBulkToggle(names, !allOn)
                            } else {
                                names.forEach { onToggle(it, !allOn) }
                            }
                        }
                    ) {
                        Text(
                            text = if (items.all { enabledSkills.contains(it.name) }) "全不选" else "全选",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            items(items, key = { it.skillDir.absolutePath }) { skill ->
                ListItem(
                    // 主标题用中文名，英文标识符缩成小字放在副标题前面——
                    // 用户要能一眼看懂干什么，但排查问题时还需要英文名
                    headlineContent = {
                        Text(skill.title?.takeIf { it.isNotBlank() } ?: skill.name)
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                            if (skill.description.isNotBlank()) {
                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = enabledSkills.contains(skill.name),
                            onCheckedChange = { checked -> onToggle(skill.name, checked) }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        if (onManage != null) {
            item { ManageButton(onClick = onManage) }
        }
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            ListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}
