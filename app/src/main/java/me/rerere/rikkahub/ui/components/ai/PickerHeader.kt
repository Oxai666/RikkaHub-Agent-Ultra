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
 * 只影响大号的 PickerHero；输入框里那个小徽章也吃同一个参数，
 * 只是尺寸小，翅膀会显得细一些。
 */
internal enum class HeroDecoration {
    /** 什么都不加。 */
    None,

    /** 头顶一个天使光环。 */
    Halo,

    /** 右侧伸出一只天使翅膀。 */
    AngelWing,

    /** 左右各一只恶魔翅膀。 */
    DemonWings,
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
    val bodyScale = if (hasDecoration) 0.68f else 1f
    val innerIconSize = if (hasDecoration) iconSize * 0.68f else iconSize

    Box(
        modifier = modifier
            .size(heroSize)
            .drawBehind {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f

                // 翅膀先画，球会压住它们的根部，看起来像从球后面长出来
                when (decoration) {
                    HeroDecoration.AngelWing ->
                        drawAngelWing(cx, cy, w, h, animatedContainerColor, mirror = false)

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

                // 光环压在最上面，不能被球盖住
                if (decoration == HeroDecoration.Halo) {
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
    }
}

/**
 * 天使翅膀：根部贴在球的中下部，向外上方张开，末端收一个羽毛缺口。
 * mirror=true 时左右翻转（目前只画单只，参数留着方便以后加对称版）。
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
        moveTo(cx + s * w * 0.21f, cy + h * 0.03f)
        // 上缘：向外上方张开，顶尖落在右上
        cubicTo(
            cx + s * w * 0.33f, cy - h * 0.17f,
            cx + s * w * 0.50f, cy - h * 0.27f,
            cx + s * w * 0.47f, cy - h * 0.05f,
        )
        // 羽毛缺口：收回来再探出去一次，制造两瓣的层次
        cubicTo(
            cx + s * w * 0.44f, cy + h * 0.02f,
            cx + s * w * 0.40f, cy + h * 0.03f,
            cx + s * w * 0.38f, cy + h * 0.10f,
        )
        // 下缘：弧线收回根部
        cubicTo(
            cx + s * w * 0.33f, cy + h * 0.21f,
            cx + s * w * 0.25f, cy + h * 0.17f,
            cx + s * w * 0.21f, cy + h * 0.14f,
        )
        close()
    }
    drawPath(wing, color)
}

/**
 * 恶魔翅膀：直边锯齿轮廓，三个尖角朝外，收口贴回球身。
 * 和天使翅膀的圆润弧线形成对比，一眼能分清档次。
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
        moveTo(cx + s * w * 0.19f, cy - h * 0.07f)   // 根部上
        lineTo(cx + s * w * 0.41f, cy - h * 0.31f)   // 上尖
        lineTo(cx + s * w * 0.37f, cy - h * 0.09f)   // 上凹
        lineTo(cx + s * w * 0.50f, cy - h * 0.19f)   // 外尖
        lineTo(cx + s * w * 0.43f, cy + h * 0.03f)   // 中凹
        lineTo(cx + s * w * 0.45f, cy + h * 0.25f)   // 下尖
        lineTo(cx + s * w * 0.30f, cy + h * 0.07f)   // 下凹
        lineTo(cx + s * w * 0.20f, cy + h * 0.13f)   // 根部下
        close()
    }
    drawPath(wing, color)
}

/**
 * 天使光环：球顶上方的扁椭圆环，画在球之后所以不会被盖住。
 * 球缩小到 0.64 后顶部大约落在 h*0.18，光环放在再上面一点。
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
