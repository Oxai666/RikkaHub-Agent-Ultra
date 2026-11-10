package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.plan.PlanSnapshot
import me.rerere.rikkahub.data.plan.PlanStep
import me.rerere.rikkahub.data.plan.PlanStepStatus

/**
 * 输入框上方的计划条。
 *
 * 模型调 `update_plan` 之后，用户得能看见现在走到第几步——
 * 不然那个工具就是个黑洞：模型自说自话地更新，人一点感知都没有。
 *
 * 没有步骤时整块不占位（[AnimatedVisibility] 管这个）。
 */
@Composable
fun PlanStepsBar(
    plan: PlanSnapshot,
    modifier: Modifier = Modifier,
) {
    // 全部做完之后，这条东西没用了——它占着输入框上方一块地方，
    // 而 AI 已经把 2/7 变成 7/7 了，留着只是噪音。
    //
    // 但也不能做完就立刻抽走：最后一步刚打勾的那一瞬间，用户可能正
    // 看着它，突然消失会让人怀疑是不是没做完。停 3 秒再收。
    //
    // 依赖 steps.size 而不是整个 plan：新计划（步数变了）要重新显示，
    // 而 explanation 之类的小改动不该打断倒计时。
    val allDone = !plan.isEmpty && plan.steps.all { it.status == PlanStepStatus.COMPLETED }
    // 用计划内容做 key，而不是 steps.size：
    // 同一份计划切页面回来时 key 不变，"已经收起来了"这个状态能跟着走，
    // 不会每次进聊天页都重播一遍"出现 → 三秒 → 消失"。
    //
    // rememberSaveable 而不是 remember：切页面时 Composable 会离开组合，
    // 普通 remember 会丢，回来又是 visible=true 从头播一遍。
    val planKey = plan.steps.joinToString("|") { "${it.index}:${it.step}" }
    var doneShown by rememberSaveable(planKey) { mutableStateOf(false) }
    LaunchedEffect(allDone, planKey) {
        if (allDone && !doneShown) {
            // 停 3 秒让用户看到 7/7，然后收起来
            kotlinx.coroutines.delay(3_000L)
            doneShown = true
        }
    }

    AnimatedVisibility(visible = !plan.isEmpty && !(allDone && doneShown)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${plan.completedCount()}/${plan.steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = plan.explanation ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                plan.steps.forEach { step ->
                    StepChip(step)
                }
            }
        }
    }
}

@Composable
private fun StepChip(step: PlanStep) {
    val (dotColor, textColor) = when (step.status) {
        PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary to
            MaterialTheme.colorScheme.onSurfaceVariant

        PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary to
            MaterialTheme.colorScheme.onSurface

        PlanStepStatus.PENDING -> MaterialTheme.colorScheme.outline to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = step.step,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
