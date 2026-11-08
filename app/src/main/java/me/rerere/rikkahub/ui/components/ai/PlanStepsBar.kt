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
    AnimatedVisibility(visible = !plan.isEmpty) {
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
