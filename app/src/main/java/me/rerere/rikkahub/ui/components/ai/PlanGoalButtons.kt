package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.goal.Goal
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.IconFlag
import me.rerere.rikkahub.ui.components.ui.icons.IconMap

/**
 * 聊天输入框上的两个模式入口，摆在模型选择器和搜索之间。
 *
 * 地图 = 计划模式，旗帜 = 跨轮目标。都只画图标，跟旁边那两个保持同一行高。
 */

/** 计划模式开关。点亮表示当前只规划不执行。 */
@Composable
fun PlanModeButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        modifier = modifier,
        checked = active,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IconMap,
                    contentDescription = stringResource(R.string.plan_mode_button),
                )
            }
        }
    }
}

/**
 * 目标入口。
 *
 * 有未完成目标时图标用主色点亮，没有就跟其他按钮一个色。
 * 只在图标上带一点状态，不占地方——目标不是每轮都要看的东西。
 */
@Composable
fun GoalButton(
    goal: Goal?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = goal != null && goal.status.isLive
    ToggleSurface(
        modifier = modifier,
        checked = live,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = IconFlag,
                    contentDescription = stringResource(R.string.goal_button),
                    tint = if (live) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
