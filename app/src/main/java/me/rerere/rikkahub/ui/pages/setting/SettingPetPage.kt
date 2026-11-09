package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.pet.PetHost
import me.rerere.rikkahub.data.pet.PetMood
import me.rerere.rikkahub.data.pet.PetSize
import me.rerere.rikkahub.data.pet.PetSprites
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 宠物设置。
 *
 * 只做三件事：选哪只、多大、要不要显示。
 *
 * 刻意没做的东西（跟 dsh-go 那边的取舍一致）：
 *  - **不做养成数值**。宠物是状态指示器，不是电子鸡。没有饥饿、好感、亲密度。
 *    那些东西会让人因为「放着不管它会难过」而愧疚，工具不该制造这种情绪。
 *  - **不做导入**。第二期再说。现在 assets 里就两个包，选就够了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingPetPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // settingsStore.update 是 suspend —— 必须从协程里调，不能直接写在 onClick 里
    val scope = rememberCoroutineScope()
    val sprites = remember { PetSprites(context) }
    // 有哪些包：写死两个。以后加了包再改成扫 assets/pets/
    val packs = remember {
        listOf("deepy-whale", "whale-girl").mapNotNull { id ->
            sprites.loadPack(id)?.let { id to it.name }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("宠物") },
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
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "宠物站在输入框上沿，用动作告诉你 agent 在干什么。" +
                        "它不会主动找你说话，也没有养成数值——就是个状态指示器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 选宠物 ──
            item {
                Text("选一只", style = MaterialTheme.typography.titleSmall)
            }
            item {
                PetChoiceRow(
                    label = "不显示",
                    selected = settings.petPackId == null,
                    onClick = { scope.launch { settingsStore.update { it.copy(petPackId = null) } } },
                )
            }
            items(packs.size) { index ->
                val (id, name) = packs[index]
                PetChoiceRow(
                    label = name,
                    selected = settings.petPackId == id,
                    onClick = { scope.launch { settingsStore.update { it.copy(petPackId = id) } } },
                )
            }

            // ── 尺寸 ──
            if (settings.petPackId != null) {
                item {
                    Text("大小", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PetSize.entries.forEach { size ->
                            val selected = PetSize.fromName(settings.petSize) == size
                            val label = when (size) {
                                PetSize.SMALL -> "小"
                                PetSize.MEDIUM -> "标准"
                                PetSize.LARGE -> "大"
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 0.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .clickable {
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(petSize = size.name.lowercase())
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // ── 实时预览 ──
                item {
                    Text("预览", style = MaterialTheme.typography.titleSmall)
                }
                // ⚠ 临时调试信息，排查完删掉
                item {
                    val debugPack = remember(settings.petPackId) {
                        settings.petPackId?.let { sprites.loadPack(it) }
                    }
                    val debugK = remember(debugPack, settings.petSize) {
                        debugPack?.screenScale(
                            PetSize.fromName(settings.petSize),
                            context.resources.displayMetrics.density,
                        )
                    }
                    if (debugPack != null && debugK != null) {
                        Text(
                            text = "调试 · canvas=${debugPack.canvasW}×${debugPack.canvasH} " +
                                "k=$debugK " +
                                "画布px=${(debugPack.canvasW * debugK).toInt()}×" +
                                "${(debugPack.canvasH * debugK).toInt()} " +
                                "density=${context.resources.displayMetrics.density}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        // 不固定高度：PetHost 自己按「画布高度 × 缩放」算，
                        // 定死 140dp 的话大号宠物和带思考云的帧会被裁掉
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            PetHost(
                                packId = settings.petPackId,
                                size = PetSize.fromName(settings.petSize),
                                mood = PetMood(running = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "素材：Deepy 小鲸鱼与鲸娘，来自 DSH-TUI。源码移植自 dsh-go（AGPL-3.0）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun PetChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
