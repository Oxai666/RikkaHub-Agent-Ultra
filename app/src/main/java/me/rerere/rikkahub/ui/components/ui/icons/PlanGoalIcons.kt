package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 地图图标 —— 计划模式的入口。
 *
 * 手画的，不走图标库：库里的名字随时可能变，而这两个图标要跟着
 * 聊天输入框常驻，不能因为依赖升级就没了。
 *
 * 形状是「折叠地图」：左边一折、中间一折、右边一折，三条竖边加上下斜边。
 */
val IconMap: ImageVector by lazy {
    ImageVector.Builder(
        name = "IconMap",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 外轮廓：左上 → 上折 → 右上 → 右下 → 下折 → 左下
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(3f, 6f)
            lineTo(9f, 3.5f)
            lineTo(15f, 6f)
            lineTo(21f, 3.5f)
            lineTo(21f, 18f)
            lineTo(15f, 20.5f)
            lineTo(9f, 18f)
            lineTo(3f, 20.5f)
            close()
        }
        // 中间两道折痕
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(9f, 3.5f)
            lineTo(9f, 18f)
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(15f, 6f)
            lineTo(15f, 20.5f)
        }
    }.build()
}

/**
 * 旗帜图标 —— 跨轮目标的入口。
 *
 * 一根竖杆 + 一面三角旗。旗面朝右，跟「立了个目标」的语义对上。
 */
val IconFlag: ImageVector by lazy {
    ImageVector.Builder(
        name = "IconFlag",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 旗杆
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(5f, 3f)
            lineTo(5f, 21f)
        }
        // 旗面
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 4f)
            lineTo(19f, 8f)
            lineTo(5f, 12f)
            close()
        }
    }.build()
}
