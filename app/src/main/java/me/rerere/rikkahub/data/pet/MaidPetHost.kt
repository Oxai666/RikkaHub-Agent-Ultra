package me.rerere.rikkahub.data.pet

/** 老版本放在 petPackId 里的值，现在只用于升级迁移。 */
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
 * 哦鲸鲸（鲸鱼娘女仆）。名字取自立绘里那个思考气泡的三个字。
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
    // 气泡也是从原图抠的——那个白色椭圆加两个尾巴圆点，连同深蓝描边一起。
    // 原来图里的「哦鲸鲸...」被抹掉了，位置留给余额。
    val bubbleBitmap = remember {
        runCatching {
            context.assets.open("pets/maid/bubble.png").use { BitmapFactory.decodeStream(it) }
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
        //
        // 形状是原图那个白色椭圆 + 两个尾巴圆点，单独抠出来做的贴图。
        // 没有用 Surface 画圆角矩形——那样出来是 Material 卡片，
        // 跟立绘不是一套语言。文字直接压在抠图留出的空白区上。
        val bubbleWidth = 184.dp
        val bubbleHeight = bubbleWidth * (445f / 512f)
        AnimatedVisibility(
            visible = showBalance,
            enter = fadeIn(tween(150)) + scaleIn(
                animationSpec = tween(180),
                initialScale = 0.78f,
                // 从小长大，起点在尾巴那侧（右下方），看着是"从嘴里冒出来"
                transformOrigin = TransformOrigin(0.42f, 0.86f),
            ),
            exit = fadeOut(tween(130)) + scaleOut(
                animationSpec = tween(130),
                targetScale = 0.92f,
                transformOrigin = TransformOrigin(0.42f, 0.86f),
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = -(bubbleHeight - 14.dp)),
        ) {
            Box(
                modifier = Modifier
                    .width(bubbleWidth)
                    .height(bubbleHeight),
            ) {
                if (bubbleBitmap != null) {
                    Image(
                        bitmap = bubbleBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 文字区：气泡下方约 22% 是尾巴和圆点，内容要避开。
                // 左右留白按原图文字的位置量出来的。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 30.dp, end = 34.dp, top = 34.dp, bottom = 46.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = modelLabel ?: "当前模型",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 跟气泡描边同一个深蓝，字压在白色上才不出戏
                            color = Color(0xFF1E2F5B),
                        )
                        if (providerSetting != null &&
                            providerSetting.balanceOption.enabled &&
                            providerSetting is ProviderSetting.OpenAI
                        ) {
                            ProviderBalanceText(
                                providerSetting = providerSetting,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF1E2F5B),
                            )
                        } else {
                            Text(
                                text = "这个服务商查不了余额",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF5A6A8C),
                            )
                        }
                    }
                }
            }
        }

        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "哦鲸鲸",
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
                            // 气泡已经在的时候，点它只做 Q 弹——不重弹、不刷新余额。
                            // 用户要的是"看着余额的时候戳它，它还是那个反应"，
                            // 而不是每戳一下数字就跳一次。
                            if (!showBalance) {
                                showBalance = true
                                balanceToken++
                            }
                        },
                    )
                },
        )
    }
}
