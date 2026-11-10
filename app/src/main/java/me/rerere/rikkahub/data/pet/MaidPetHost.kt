package me.rerere.rikkahub.data.pet

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import me.rerere.ai.provider.ProviderManager
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** 老版本放在 petPackId 里的值，现在只用于升级迁移。 */
const val MAID_PET_ID = "maid-deepseek"

/**
 * 哦鲸鲸（鲸鱼娘女仆）。名字取自立绘里那个思考气泡的三个字。
 *
 * 跟另外两种宠物的区别：
 *
 *  · [PetHost]      素材包，序列图，十二种状态
 *  · [ImagePetHost] 用户扔一张图，通用容器
 *  · 这个           一张抠好的立绘 + 一套专属交互：
 *                   按住压下去、松手缓降、拖到哪停哪、点一下吐气泡查余额
 *
 * ## 手感
 *
 *   按下  spring(dampingRatio = 0.85, stiffness = 2600)   瞬间到底，略微过冲 = Q
 *   松手  tween(420ms, FastOutSlowInEasing)              慢慢降回去，不是弹回来
 *
 * 拖动的时候另外算——拖动中必须**贴住手指**，不能有任何弹簧滞后，
 * 所以 dragX 在拖动期间走 stiffness = 10000 的直线跟随。
 */
@Composable
fun MaidPetHost(
    /** 身体高度（dp）。跟其它宠物一样是每只独立的一份。 */
    sizeDp: Float,
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
    val bodyHPx = with(density) { sizeDp.dp.toPx() }
    val bodyWPx = bodyHPx * aspect
    val liftMax = bodyHPx * 0.5f
    val containerHPx = bodyHPx + liftMax

    val bodyHDp = with(density) { bodyHPx.toDp() }
    val bodyWDp = with(density) { bodyWPx.toDp() }
    val containerHDp = with(density) { containerHPx.toDp() }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }

    // 位置持久化，跟 PetHost 用同一个思路：拖动结束才写盘
    val settingsStore: SettingsStore = koinInject()
    val persistScope = rememberCoroutineScope()
    val liveSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val savedOffsetX = liveSettings.maidPetOffsetX

    var hostWidthPx by remember { mutableFloatStateOf(0f) }
    // 宠物在窗口坐标系里的位置。气泡必须用 Popup 画——
    // 它要飘到宠物头顶上方，超出 ChatInput 这个 bottomBar 的边界，
    // 普通 Box 里画出去会被父级裁掉（这就是"点了没反应"的真正原因：
    // 气泡其实弹了，只是被裁没了）。
    var petWindowPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 同 PetHost：不能用 savedOffsetX 当 remember key，
    // 否则松手写盘 → settingsFlow 推回来 → dragX 被重建，
    // 第二次拖动就会撞墙、松手才跳过去。
    // 同 PetHost：等真实配置，别被 dummy(init = true) 骗了
    var dragX by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val real = settingsStore.settingsFlow.first { !it.init }
        dragX = real.maidPetOffsetX
    }
    var grabY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    var pressed by remember { mutableStateOf(false) }
    var showBalance by remember { mutableStateOf(false) }
    var balanceToken by remember { mutableIntStateOf(0) }

    // 余额自己查，不用 ProviderBalanceText——那个出错时会把异常文本
    // 当成结果显示出来（"Error: ..."）。这里只要数字，拿不到就是 null，
    // 显示层兜底成「哦鲸鲸」。
    val providerManager: ProviderManager = koinInject()
    var balanceText by remember(providerSetting?.id) { mutableStateOf<String?>(null) }
    var balanceLoading by remember(providerSetting?.id) { mutableStateOf(false) }
    LaunchedEffect(showBalance, providerSetting?.id) {
        if (!showBalance) return@LaunchedEffect
        val ps = providerSetting
        if (ps !is ProviderSetting.OpenAI || !ps.balanceOption.enabled) {
            balanceText = null
            return@LaunchedEffect
        }
        balanceLoading = true
        balanceText = runCatching {
            providerManager.getProviderByType(ps).getBalance(ps)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("Error") }
        balanceLoading = false
    }

    LaunchedEffect(balanceToken) {
        if (!showBalance) return@LaunchedEffect
        delay(3_000L)
        showBalance = false
    }

    // 拖动中贴手指：高刚度、零阻尼过冲。松手才回到缓降曲线。
    val followSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 10_000f)
    val animatedX by animateFloatAsState(
        targetValue = dragX,
        animationSpec = if (dragging) followSpec else spring(),
        label = "maidDragX",
    )
    val animatedY by animateFloatAsState(
        targetValue = grabY,
        animationSpec = if (dragging) followSpec else tween(420, easing = FastOutSlowInEasing),
        label = "maidGrabY",
    )

    val squash by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = if (pressed) spring(0.85f, 2_600f) else tween(420, easing = FastOutSlowInEasing),
        label = "maidSquash",
    )
    val stretch by animateFloatAsState(
        targetValue = if (pressed) 1.16f else 1f,
        animationSpec = if (pressed) spring(0.85f, 2_400f) else tween(420, easing = FastOutSlowInEasing),
        label = "maidStretch",
    )

    // 气泡跟着宠物大小走。之前写死 184dp，宠物拖到 96dp 时气泡还是那么大，
    // 看着像贴上去的。
    val bubbleWidth = (sizeDp * 6.2f).dp
    val bubbleHeight = bubbleWidth * (521f / 600f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHDp)
            .onSizeChanged { hostWidthPx = it.width.toFloat() },
        contentAlignment = Alignment.TopCenter,
    ) {
        // 气泡。**必须用 Popup**。
        //
        // 它要飘到宠物头顶上方，那已经超出 ChatInput（Scaffold 的 bottomBar）
        // 的边界了。在普通 Box 里 offset 负值画出去，父级会把超出部分裁掉——
        // 表现就是"点了宠物有 Q 弹，但气泡不出来"。
        //
        // Popup 是独立的窗口层，不受父级裁剪，offset 用窗口坐标算。
        val bubbleWpx = with(density) { bubbleWidth.toPx() }
        val bubbleHpx = with(density) { bubbleHeight.toPx() }
        if (showBalance) {
            // Popup(offset = ...) 的 offset 是**相对父级**的，不是窗口坐标。
            // 之前直接往里塞 localToWindow 算出来的绝对值，等于把偏移算了两遍，
            // 气泡整个飞到屏幕外面去——所以看起来"点了没反应"。
            //
            // 用 PopupPositionProvider 自己算，它的 calculatePosition 拿到的
            // anchorBounds 是相对窗口的，返回的也是窗口坐标。
            Popup(
                popupPositionProvider = remember(petWindowPos, bodyWPx, bodyHPx) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val x = petWindowPos.x + bodyWPx / 2f - popupContentSize.width / 2f
                            val y = petWindowPos.y - popupContentSize.height + bodyHPx * 0.12f
                            return IntOffset(x.roundToInt(), y.roundToInt())
                        }
                    }
                },
                properties = PopupProperties(focusable = false),
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
                            // 拿不到余额（不支持 / 网络挂了 / 报错）就显示「哦鲸鲸」。
                            // 这三个字就是她的名字，比「查询失败」更像她会说的话。
                            val shown = when {
                                balanceLoading -> "…"
                                !balanceText.isNullOrBlank() -> balanceText!!
                                else -> "哦鲸鲸"
                            }
                            Text(
                                text = shown,
                                // 字号跟着气泡走，气泡跟着宠物走。
                                // 原来固定 titleMedium（16sp），放在 180dp 的气泡里太小。
                                fontSize = (sizeDp * 0.92f).sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFF1E2F5B),
                            )
                        }
                    }
                }
            }
        }

        // 宠物本体。外层 Box 尺寸正好是立绘尺寸，手势挂在它上面——
        // 这样触摸区域就是那只人，不是整块半屏。
        Box(
            modifier = Modifier
                .width(bodyWDp)
                .height(bodyHDp)
                .offset {
                    IntOffset(
                        animatedX.roundToInt(),
                        (animatedY + liftMax).roundToInt(),
                    )
                }
                .onGloballyPositioned { coords ->
                    petWindowPos = coords.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 抬手复位。**不在这里弹气泡**：
                        // onPress 的 tryAwaitRelease 在拖动结束时也会返回，
                        // 所以拖一下宠物就会冒出气泡。
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        // 只有真正的点击（没被拖动抢走）才弹。
                        onTap = {
                            if (!showBalance) {
                                showBalance = true
                                balanceToken++
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, drag ->
                            change.consume()
                            val usable = if (hostWidthPx > 0f) hostWidthPx else screenWidthPx
                            val maxX = ((usable - bodyWPx) / 2f).coerceAtLeast(0f)
                            dragX = (dragX + drag.x).coerceIn(-maxX, maxX)
                            grabY = (grabY + drag.y).coerceIn(-liftMax, 0f)
                        },
                        onDragEnd = {
                            dragging = false
                            grabY = 0f
                            if (dragX != savedOffsetX) {
                                val target = dragX
                                persistScope.launch(kotlinx.coroutines.NonCancellable) {
                                    settingsStore.update { s -> s.copy(maidPetOffsetX = target) }
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            grabY = 0f
                        },
                    )
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "哦鲸鲸",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = stretch
                        scaleY = squash
                        // 脚底中心：压下去往地面沉，不是从中间缩
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
            )
        }
    }
}
