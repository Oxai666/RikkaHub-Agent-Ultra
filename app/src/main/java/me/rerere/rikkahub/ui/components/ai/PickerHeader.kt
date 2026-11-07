package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

// sheet 的头部：左侧标题与说明，右侧放操作
@Composable
internal fun PickerHeader(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    hero: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                // 各语言标题长短差别很大，放不下两行时自动缩小字号
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 28.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMediumEmphasized.copy(
                    fontWeight = FontWeight.Black,
                    lineHeight = 1.2.em,
                    lineBreak = LineBreak.Heading,
                ),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hero()
    }
}

// 选择器 sheet 的紧凑头部：左侧依次是小标题（可省略）、当前选项和说明，右侧放随选项变形的形状
@Composable
internal fun <T : Comparable<T>> PickerValueHeader(
    title: String?,
    value: T,
    hint: String,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
    hero: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PickerValueLabel(value = value, label = label)
            Text(
                text = hint,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hero()
    }
}

/**
 * 刺球外围的装饰。档位越高越张扬，扫一眼就知道现在开到多大。
 *
 * 翅膀贴着球身画，不从球后面往外拖——之前那种细长的拖尾看着像尾巴，
 * 所以改成从球侧水平展开、带明确羽毛尖/指骨的扇形。
 */
internal enum class HeroDecoration {
    /** 什么都不加。 */
    None,

    /** 头顶一个天使光环。 */
    Halo,

    /** 右侧一只天使翅膀。 */
    AngelWing,

    /** 天使翅膀 + 头顶光环。 */
    AngelWingHalo,

    /** 左右各一只恶魔翅膀 + 灯泡头顶两只角。 */
    DemonWings,
}

/**
 * 灯泡上的表情。画在图标上层，坐标按 24x24 的图标 viewbox 归一化，
 * 这样换任何尺寸的 PickerHero 表情都跟着缩放。
 */
internal enum class HeroExpression {
    /** 素脸，什么都不画。 */
    None,

    /** 闭眼睡觉 + 三个飘着的 z。 */
    Sleeping,

    /** 圆点眼 + 微笑。 */
    Smile,

    /** 半睁眼，迷迷糊糊。 */
    Drowsy,

    /** 微笑 + 灯泡底下系一条领带。 */
    Tie,

    /** 斜眼 + 咧嘴露尖牙。 */
    Evil,
}

// 形状随 index 切换，相邻形状之间用 Morph 连续过渡
@Composable
internal fun PickerHero(
    shapes: List<RoundedPolygon>,
    index: Int,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    // 默认是选择器里的大号；输入框里的小图标也用同一套形状，
    // 只是尺寸缩到二十几 dp，所以尺寸要能传进来。
    heroSize: Dp = 72.dp,
    iconSize: Dp = 32.dp,
    decoration: HeroDecoration = HeroDecoration.None,
    expression: HeroExpression = HeroExpression.None,
) {
    val morphs = remember(shapes) { shapes.zipWithNext { from, to -> Morph(from, to) } }
    val path = remember { Path() }
    val position by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
    )
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val iconSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val iconEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    // 有装饰时球要缩一圈，把两侧和头顶让出来；没装饰就占满整个方块
    val hasDecoration = decoration != HeroDecoration.None
    val bodyScale = if (hasDecoration) 0.62f else 1f
    // 灯泡缩得比球少一点：装饰画在球外面，脸还得看得清
    val innerIconSize = if (hasDecoration) iconSize * 0.72f else iconSize

    Box(
        modifier = modifier
            .size(heroSize)
            .drawBehind {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f

                // 翅膀先画，球会压住它们的根部，看起来像从球侧面长出来
                when (decoration) {
                    HeroDecoration.AngelWing, HeroDecoration.AngelWingHalo -> {
                        drawAngelWing(cx, cy, w, h, animatedContainerColor, mirror = false)
                        drawAngelWing(cx, cy, w, h, animatedContainerColor, mirror = true)
                    }

                    HeroDecoration.DemonWings -> {
                        drawDemonWing(cx, cy, w, h, animatedContainerColor, mirror = false)
                        drawDemonWing(cx, cy, w, h, animatedContainerColor, mirror = true)
                    }

                    else -> Unit
                }

                // 弹簧会过冲，position 可能略微越界
                val segment = position.toInt().coerceIn(0, morphs.lastIndex)
                morphs[segment].toPath(
                    progress = (position - segment).coerceIn(0f, 1f),
                    path = path,
                )
                withTransform({
                    scale(bodyScale, bodyScale, pivot = Offset(cx, cy))
                }) {
                    withTransform({
                        rotate(position * 30f)
                        // MaterialShapes 是归一化到 1x1 的
                        scale(w, h, pivot = Offset.Zero)
                    }) {
                        drawPath(path, animatedContainerColor)
                    }
                }

                // 角从球顶往上顶出来，根部被球压住
                if (decoration == HeroDecoration.DemonWings) {
                    drawDemonHorn(cx - w * 0.11f, cy - h * 0.24f, w, h, animatedContainerColor, mirror = true)
                    drawDemonHorn(cx + w * 0.11f, cy - h * 0.24f, w, h, animatedContainerColor, mirror = false)
                }

                // 光环压在最上面，不能被球盖住
                if (decoration == HeroDecoration.Halo || decoration == HeroDecoration.AngelWingHalo) {
                    drawHeroHalo(cx, w, h, animatedContainerColor)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(iconEffectsSpec) + scaleIn(iconSpatialSpec, initialScale = 0.6f)) togetherWith
                    (fadeOut(iconEffectsSpec) + scaleOut(iconSpatialSpec, targetScale = 0.6f))
            },
        ) { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(innerIconSize),
                tint = animatedContentColor,
            )
        }
        // 表情叠在灯泡上。Canvas 和 Icon 同尺寸同位置，坐标按 24x24 的
        // 图标 viewbox 换算，换任何 heroSize 都跟着缩放。
        if (expression != HeroExpression.None) {
            Canvas(modifier = Modifier.size(innerIconSize)) {
                drawHeroFace(
                    expression = expression,
                    unit = size.width / 24f,
                    color = animatedContentColor,
                )
            }
        }
    }
}

/**
 * 天使翅膀：从球侧水平展开的一扇羽翼，上缘扬起、末端三根羽毛。
 *
 * 上一版画成一条从球后拖出来的细长弧线，看着像尾巴。这次改成
 * 「根部贴球、横向张开、羽毛尖朝外」，轮廓上就能认出是翅膀。
 * mirror=true 时左右翻转（目前只用右侧单只）。
 */
private fun DrawScope.drawAngelWing(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    mirror: Boolean,
) {
    val s = if (mirror) -1f else 1f
    val wing = Path().apply {
        // 根部：贴在球的右上方
        moveTo(cx + s * w * 0.26f, cy - h * 0.10f)
        // 上缘：一条向上扬起的弧，收在翼尖
        cubicTo(
            cx + s * w * 0.34f, cy - h * 0.30f,
            cx + s * w * 0.50f, cy - h * 0.34f,
            cx + s * w * 0.49f, cy - h * 0.15f,
        )
        // 第一根羽毛（最上，最长）
        lineTo(cx + s * w * 0.40f, cy - h * 0.12f)
        lineTo(cx + s * w * 0.47f, cy - h * 0.03f)
        // 第二根羽毛
        lineTo(cx + s * w * 0.36f, cy - h * 0.02f)
        lineTo(cx + s * w * 0.41f, cy + h * 0.07f)
        // 第三根羽毛
        lineTo(cx + s * w * 0.30f, cy + h * 0.05f)
        lineTo(cx + s * w * 0.31f, cy + h * 0.15f)
        // 收回根部
        lineTo(cx + s * w * 0.24f, cy + h * 0.08f)
        close()
    }
    drawPath(wing, color)
}

/**
 * 恶魔翅膀：蝙蝠翼的三根指骨，尖角朝外、指间留凹口。
 *
 * 和天使翅膀用同一套外轮廓思路，但全是直线，没有一根弧——
 * 光看轮廓就能分清是哪种翼，不依赖颜色。
 */
private fun DrawScope.drawDemonWing(
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    color: Color,
    mirror: Boolean,
) {
    val s = if (mirror) -1f else 1f
    val wing = Path().apply {
        // 根部上
        moveTo(cx + s * w * 0.24f, cy - h * 0.14f)
        // 上指骨
        lineTo(cx + s * w * 0.38f, cy - h * 0.34f)
        lineTo(cx + s * w * 0.36f, cy - h * 0.08f)
        // 中指骨（最长，伸到最外）
        lineTo(cx + s * w * 0.50f, cy - h * 0.19f)
        lineTo(cx + s * w * 0.42f, cy + h * 0.01f)
        // 下指骨
        lineTo(cx + s * w * 0.45f, cy + h * 0.21f)
        lineTo(cx + s * w * 0.31f, cy + h * 0.04f)
        // 根部下
        lineTo(cx + s * w * 0.24f, cy + h * 0.10f)
        close()
    }
    drawPath(wing, color)
}

/**
 * 恶魔角：从球顶两侧往上顶的小三角，尖朝外撇。
 * 画在球之后，根部会盖住球边，看起来像从球里长出来的。
 */
private fun DrawScope.drawDemonHorn(
    baseX: Float,
    baseY: Float,
    w: Float,
    h: Float,
    color: Color,
    mirror: Boolean,
) {
    val s = if (mirror) -1f else 1f
    val horn = Path().apply {
        moveTo(baseX - s * w * 0.055f, baseY + h * 0.02f)
        lineTo(baseX + s * w * 0.015f, baseY - h * 0.17f)
        lineTo(baseX + s * w * 0.055f, baseY + h * 0.02f)
        close()
    }
    drawPath(horn, color)
}

/**
 * 天使光环：球顶上方的扁椭圆环，画在球之后所以不会被盖住。
 * 球缩小到 0.62 后顶部大约落在 h*0.19，光环放在再上面一点。
 */
private fun DrawScope.drawHeroHalo(
    cx: Float,
    w: Float,
    h: Float,
    color: Color,
) {
    val ringW = w * 0.36f
    val ringH = h * 0.12f
    drawOval(
        color = color,
        topLeft = Offset(cx - ringW / 2f, h * 0.02f),
        size = Size(ringW, ringH),
        style = Stroke(width = (h * 0.055f).coerceAtLeast(1.2f)),
    )
}

/**
 * 灯泡上的表情。
 *
 * 坐标全部按 24x24 的图标 viewbox 写，unit 传的是「1 个 viewbox 单位 = 多少像素」，
 * 所以 72dp 的选择器和 32dp 的输入框徽章共用同一份脸，只是缩放不同。
 *
 * 灯泡圆泡的中心大约在 (12, 11)，脸就画在这一带；底座在 y>16，
 * 领带那种要挂在底座上的另算。
 */
private fun DrawScope.drawHeroFace(
    expression: HeroExpression,
    unit: Float,
    color: Color,
) {
    val hairline = Stroke(
        width = 1.4f * unit,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )

    /** viewbox 坐标 → 像素 */
    fun v(x: Float, y: Float) = Offset(x * unit, y * unit)

    when (expression) {
        HeroExpression.Sleeping -> {
            // 闭着的眼：两条向上拱的弧
            drawPath(
                Path().apply {
                    moveTo(8.5f * unit, 11.0f * unit)
                    quadraticTo(9.9f * unit, 9.6f * unit, 11.3f * unit, 11.0f * unit)
                },
                color, style = hairline,
            )
            drawPath(
                Path().apply {
                    moveTo(12.7f * unit, 11.0f * unit)
                    quadraticTo(14.1f * unit, 9.6f * unit, 15.5f * unit, 11.0f * unit)
                },
                color, style = hairline,
            )
            // 微张的嘴
            drawCircle(
                color = color,
                radius = 0.85f * unit,
                center = v(12f, 14.2f),
                style = hairline,
            )
            // 飘着的 z，越远越小
            drawSleepingZ(v(17.0f, 8.0f), 1.7f * unit, hairline, color)
            drawSleepingZ(v(19.2f, 5.6f), 1.35f * unit, hairline, color)
            drawSleepingZ(v(21.0f, 3.6f), 1.0f * unit, hairline, color)
        }

        HeroExpression.Smile -> {
            drawCircle(color, radius = 0.85f * unit, center = v(9.6f, 10.9f))
            drawCircle(color, radius = 0.85f * unit, center = v(14.4f, 10.9f))
            drawPath(
                Path().apply {
                    moveTo(10.2f * unit, 13.5f * unit)
                    quadraticTo(12f * unit, 15.4f * unit, 13.8f * unit, 13.5f * unit)
                },
                color, style = hairline,
            )
        }

        HeroExpression.Drowsy -> {
            // 上眼睑压得很低，只留一条缝
            drawLine(color, v(8.6f, 10.7f), v(11.3f, 10.7f), hairline.width, StrokeCap.Round)
            drawLine(color, v(12.7f, 10.7f), v(15.4f, 10.7f), hairline.width, StrokeCap.Round)
            drawCircle(color, radius = 0.55f * unit, center = v(9.95f, 11.6f))
            drawCircle(color, radius = 0.55f * unit, center = v(14.05f, 11.6f))
            // 没睡醒的波浪嘴
            drawPath(
                Path().apply {
                    moveTo(10.3f * unit, 13.9f * unit)
                    lineTo(11.3f * unit, 14.25f * unit)
                    lineTo(12.3f * unit, 13.9f * unit)
                    lineTo(13.3f * unit, 14.25f * unit)
                },
                color, style = hairline,
            )
        }

        HeroExpression.Tie -> {
            drawCircle(color, radius = 0.85f * unit, center = v(9.6f, 10.9f))
            drawCircle(color, radius = 0.85f * unit, center = v(14.4f, 10.9f))
            drawPath(
                Path().apply {
                    moveTo(10.2f * unit, 13.4f * unit)
                    quadraticTo(12f * unit, 15.2f * unit, 13.8f * unit, 13.4f * unit)
                },
                color, style = hairline,
            )
            // 领带：领结一个三角，下面一个倒梯形
            drawPath(
                Path().apply {
                    moveTo(10.6f * unit, 16.6f * unit)
                    lineTo(13.4f * unit, 16.6f * unit)
                    lineTo(12f * unit, 17.8f * unit)
                    close()
                },
                color,
            )
            drawPath(
                Path().apply {
                    moveTo(11.2f * unit, 17.9f * unit)
                    lineTo(12.8f * unit, 17.9f * unit)
                    lineTo(12.45f * unit, 20.6f * unit)
                    lineTo(11.55f * unit, 20.6f * unit)
                    close()
                },
                color,
            )
        }

        HeroExpression.Evil -> {
            // 斜眼：外高内低，怒气往中间压
            drawLine(color, v(8.6f, 9.8f), v(11.2f, 11.3f), hairline.width + 0.3f * unit, StrokeCap.Round)
            drawLine(color, v(15.4f, 9.8f), v(12.8f, 11.3f), hairline.width + 0.3f * unit, StrokeCap.Round)
            // 咧开的嘴
            drawPath(
                Path().apply {
                    moveTo(9.9f * unit, 13.2f * unit)
                    quadraticTo(12f * unit, 15.8f * unit, 14.1f * unit, 13.2f * unit)
                },
                color, style = hairline,
            )
            // 两颗尖牙从上唇往下扎
            drawPath(
                Path().apply {
                    moveTo(10.5f * unit, 13.55f * unit)
                    lineTo(11.15f * unit, 13.9f * unit)
                    lineTo(10.75f * unit, 14.85f * unit)
                    close()
                },
                color,
            )
            drawPath(
                Path().apply {
                    moveTo(13.5f * unit, 13.55f * unit)
                    lineTo(12.85f * unit, 13.9f * unit)
                    lineTo(13.25f * unit, 14.85f * unit)
                    close()
                },
                color,
            )
        }

        HeroExpression.None -> Unit
    }
}

/** 睡觉表情里飘出来的一个 z，用三条线折出来。 */
private fun DrawScope.drawSleepingZ(
    topLeft: Offset,
    sizePx: Float,
    stroke: Stroke,
    color: Color,
) {
    val path = Path().apply {
        moveTo(topLeft.x, topLeft.y)
        lineTo(topLeft.x + sizePx, topLeft.y)
        lineTo(topLeft.x, topLeft.y + sizePx)
        lineTo(topLeft.x + sizePx, topLeft.y + sizePx)
    }
    drawPath(path, color, style = stroke)
}

// 当前选项的名称，切换时上下滚动
@Composable
private fun <T : Comparable<T>> PickerValueLabel(
    value: T,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
) {
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            // 值变大时向上滚动，变小时向下滚动
            val direction = if (targetState > initialState) 1 else -1
            (slideInVertically(spatialSpec) { it * direction / 2 } + fadeIn(effectsSpec)) togetherWith
                (slideOutVertically(spatialSpec) { -it * direction / 2 } + fadeOut(effectsSpec)) using
                SizeTransform(clip = false)
        },
    ) {
        Text(
            text = label(it),
            // 个别语言的选项名很长，放不下两行时自动缩小字号
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 24.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmallEmphasized.copy(
                fontWeight = FontWeight.Black,
                lineHeight = 1.2.em,
                lineBreak = LineBreak.Heading,
            ),
        )
    }
}

// sheet 内子页面的头部：返回按钮加标题
@Composable
internal fun SheetHeader(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (navigationIcon == null) 8.dp else 4.dp),
        )
        actions()
    }
}
