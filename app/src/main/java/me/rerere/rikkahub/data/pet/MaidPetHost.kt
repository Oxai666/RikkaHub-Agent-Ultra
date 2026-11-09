package me.rerere.rikkahub.data.pet

/** 这个宠物在 petPackId 里占的值。不是素材包，走专属渲染。 */
const val MAID_PET_ID = "maid-deepseek"

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 鲸鱼娘女仆宠物（"哦鲸鲸…" 那只）。
 *
 * 跟另外两种宠物的区别：
 *
 *  · [PetHost]      素材包，序列图，十二种状态。动作多，但看着是像素小人
 *  · [ImagePetHost] 用户随便扔一张图，通用容器。没有性格
 *  · 这个           一张抠好的立绘，**一套专属交互**——压下去会陷、松手缓降、
 *                   点一下吐个气泡告诉你这个模型还剩多少钱
 *
 * 前两种能换素材，这个换不了：图的形状、压扁的量、缓降的时长、
 * 气泡挂在哪个位置，都是照这张图调出来的。
 *
 * ## 手感
 *
 * 用户要的是「按一下非常 Q 弹，放下去缓降」。这是两段完全不同的曲线：
 *
 *   按下  spring(dampingRatio = 0.85, stiffness = 2600)
 *         高刚度瞬间到底，0.85 的阻尼让它在到底时有一点点过冲，
 *         那一下就是「Q」
 *
 *   松手  tween(420ms, FastOutSlowInEasing)
 *         不是 spring。弹簧会「弹」回来，用户明确要的是「缓降」——
 *         慢慢升回去，没有回弹。
 *
 * 另外压的时候整体往下沉 3dp，配合锚点在脚底，看起来像被按进地面。
 */
@Composable
fun MaidPetHost(
    size: PetSize,
    providerSetting: ProviderSetting?,
    modelLabel: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val bitmap = remember {
        runCatching {
            context.assets.open("pets/maid/body.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    if (bitmap == null) return

    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    val bodyH = with(density) { size.dp.toPx() }
    val bodyW = bodyH * aspect
    // 跟另外两种宠物保持同样的容器高度：身体 + 一半的抬升余量。
    // 切换宠物的时候宠物跟输入框的距离不会跳。
    val liftMax = bodyH * 0.5f
    val containerH = bodyH + liftMax

    var pressed by remember { mutableStateOf(false) }
    var showBalance by remember { mutableStateOf(false) }
    // 连点时重开计时用。每次点击 +1，LaunchedEffect 重跑，3 秒从上一次点击算起。
    var balanceToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(balanceToken) {
        if (!showBalance) return@LaunchedEffect
        delay(3_000L)
        showBalance = false
    }

    // 按下：高刚度 + 微微过冲 = 「Q」
    // 松手：缓动 = 「缓降」
    val squash by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = 0.85f, stiffness = 2_600f)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "maidSquash",
    )
    val stretch by animateFloatAsState(
        targetValue = if (pressed) 1.16f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = 0.85f, stiffness = 2_400f)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "maidStretch",
    )
    val sink by animateDpAsState(
        targetValue = if (pressed) 3.dp else 0.dp,
        animationSpec = if (pressed) {
            spring(dampingRatio = 0.9f, stiffness = 3_000f)
        } else {
            tween(durationMillis = 420, easing = FastOutSlowInEasing)
        },
        label = "maidSink",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { containerH.toDp() }),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 气泡。**不用 Popup**——Popup 是独立窗口，会把输入框的圆角和模糊背景切开，
        // 而且定位要自己算全局坐标。直接在 Box 里 offset 飘上去，
        // 它跟着宠物走，宠物在哪气泡就在哪。
        AnimatedVisibility(
            visible = showBalance,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.92f),
            // 气泡挂在宠物头顶正上方。用固定偏移而不是算容器高——
            // 容器高是 px 算出来的 Dp，来回换算容易差几个像素，
            // 而这里只要"在头顶上方"这一个视觉关系。
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = if (size == PetSize.LARGE) (-88).dp else (-74).dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = modelLabel ?: "当前模型",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (providerSetting != null &&
                        providerSetting.balanceOption.enabled &&
                        providerSetting is ProviderSetting.OpenAI
                    ) {
                        ProviderBalanceText(
                            providerSetting = providerSetting,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "这个服务商查不了余额",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "鲸鱼娘",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(with(density) { bodyW.toDp() })
                .height(with(density) { bodyH.toDp() })
                .offset { IntOffset(0, sink.roundToPx()) }
                .graphicsLayer {
                    scaleX = stretch
                    scaleY = squash
                    // 脚底中心：压下去往地面陷，不是从中间缩
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                            // 抬手就吐气泡；拖动/取消也一样，
                            // 因为这里没有拖动手势，release 只有一种含义
                            showBalance = true
                            balanceToken++
                        },
                    )
                },
        )
    }
}
