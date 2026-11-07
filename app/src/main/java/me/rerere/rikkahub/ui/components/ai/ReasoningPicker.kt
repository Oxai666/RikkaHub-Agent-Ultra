package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.flow.drop
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size
private val levelShapes = levels.map { it.shape() }

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                // 和选择器里那个大刺球同一套形状，只是缩到输入框的尺寸。
                // 之前这里是个光秃秃的灯泡线条，跟选择器里的样子完全对不上。
                PickerHero(
                    shapes = levelShapes,
                    index = levels.indexOf(reasoningLevel).coerceAtLeast(0),
                    icon = reasoningLevel.icon(),
                    containerColor = reasoningLevel.badgeContainerColor(),
                    contentColor = reasoningLevel.badgeContentColor(),
                    heroSize = 28.dp,
                    iconSize = 15.dp,
                )
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    // material3 1.5.0-alpha27 的 SliderState 还没有 trackRange，Slider(state = ...) 也没有
    // onValueChange / onValueChangeFinished。改用 value + valueRange 的那个重载，
    // 拖动值和提交时机自己持有，行为与官方 beta 版一致。
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    // 拖动过程中就跟随滑块预览，松手后才真正提交
    val previewLevel = levels[sliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { sliderValue.roundToInt() }
            .drop(1)
            .collect { hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PickerValueHeader(
                title = stringResource(R.string.reasoning_picker_title),
                value = previewLevel,
                hint = stringResource(R.string.reasoning_picker_hint),
                label = { it.label() },
            ) {
                // 等级越高形状越「激烈」
                PickerHero(
                    shapes = levelShapes,
                    index = levels.indexOf(previewLevel),
                    icon = previewLevel.icon(),
                    containerColor = previewLevel.badgeContainerColor(),
                    contentColor = previewLevel.badgeContentColor(),
                )
            }

            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                    sliderValue = snappedIndex.toFloat()
                    onUpdateReasoningLevel(levels[snappedIndex])
                },
                modifier = Modifier.fillMaxWidth(),
                valueRange = 0f..(levelCount - 1).toFloat(),
                steps = levelCount - 2,
                interactionSource = interactionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        thumbSize = DpSize(4.dp, 52.dp),
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        trackInsideCornerSize = 12.dp,
                        modifier = Modifier.height(40.dp),
                    )
                }
            )
        }
    }
}

private fun ReasoningLevel.icon(): ImageVector = when (this) {
    ReasoningLevel.OFF -> HugeIcons.Idea
    ReasoningLevel.AUTO -> HugeIcons.Idea01
    ReasoningLevel.LOW -> ReasoningLow
    ReasoningLevel.MEDIUM -> ReasoningMedium
    ReasoningLevel.HIGH -> ReasoningHigh
    ReasoningLevel.XHIGH -> ReasoningHigh
    ReasoningLevel.MAX -> ReasoningHigh
}

// 刺球的底色：关闭时灰掉，高档位上主色，中间用浅一层的容器色。
// 输入框里的小徽章和选择器里的大刺球共用这一套，保证两处长得一样。
@Composable
private fun ReasoningLevel.badgeContainerColor(): Color = when {
    !isEnabled -> MaterialTheme.colorScheme.surfaceContainerHighest
    this >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun ReasoningLevel.badgeContentColor(): Color = when {
    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
    this >= ReasoningLevel.HIGH -> MaterialTheme.colorScheme.onPrimary
    else -> MaterialTheme.colorScheme.onPrimaryContainer
}

private fun ReasoningLevel.shape(): RoundedPolygon = when (this) {
    ReasoningLevel.OFF -> MaterialShapes.Circle
    ReasoningLevel.AUTO -> MaterialShapes.Cookie4Sided
    ReasoningLevel.LOW -> MaterialShapes.Cookie6Sided
    ReasoningLevel.MEDIUM -> MaterialShapes.Cookie7Sided
    ReasoningLevel.HIGH -> MaterialShapes.Cookie9Sided
    ReasoningLevel.XHIGH -> MaterialShapes.Cookie12Sided
    ReasoningLevel.MAX -> MaterialShapes.SoftBurst
}

// 显示值直接用传给 API 的那个参数名，不再翻译成「关闭/自动/低/中/高」。
// 用的人多半是照着文档核对参数，看到 Off / Low / High 比看到中文更好对。
@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> "Off"
    ReasoningLevel.AUTO -> "Auto"
    ReasoningLevel.LOW -> "Low"
    ReasoningLevel.MEDIUM -> "Medium"
    ReasoningLevel.HIGH -> "High"
    ReasoningLevel.XHIGH -> "XHigh"
    ReasoningLevel.MAX -> "Max"
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}
