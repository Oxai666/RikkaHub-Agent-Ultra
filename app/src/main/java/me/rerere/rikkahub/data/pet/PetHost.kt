package me.rerere.rikkahub.data.pet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 聊天页的宠物托管。
 *
 * 放在输入框上方，脚踩在输入框上沿。它自己不做状态判断——
 * [mood] 由调用方从会话状态算好喂进来，这里只负责：
 *  - 挑动画（状态 → 动画名 → 具体的 [PetAnimation]）
 *  - 处理点击 / 长按 / 拖动
 *  - 播一次性反应（戳一下、被挠痒）
 *
 * 为什么把状态判断推给调用方：宠物该表达什么取决于「这一轮在不在跑」
 * 「有没有卡等你点」，那些信息只有聊天页有。让它自己去猜，就得
 * 反向依赖一堆全局单例，测都没法测。
 */
@Composable
fun PetHost(
    packId: String?,
    size: PetSize,
    mood: PetMood,
    modifier: Modifier = Modifier,
) {
    if (packId == null) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val sprites = remember { PetSprites(context) }
    val pack = remember(packId) { sprites.loadPack(packId) }
    if (pack == null) return

    // 基础状态
    val baseState = remember(mood) { mood.state() }
    val tier = remember(mood) { mood.tier() }

    // 一次性反应会临时盖住基础状态，播完自动退回
    var reaction by remember { mutableStateOf<PetAnimation?>(null) }
    var reactionToken by remember { mutableIntStateOf(0) }

    fun playReaction(anim: PetAnimation?) {
        if (anim == null) return
        reaction = anim
        reactionToken++
    }

    LaunchedEffect(reactionToken) {
        val r = reaction ?: return@LaunchedEffect
        delay(pack.playMsOf(r))
        reaction = null
    }

    // 该播哪一段：反应优先，否则按状态 + 分档挑。
    val stateAnim = remember(baseState, tier) { pack.primary(baseState.serial, tier) }
    val anim = reaction ?: stateAnim

    // 拖动偏移。**松手回零**，不记位置。
    //
    // 之前做过"记住位置"，但宠物是站在输入框上沿的状态指示器，
    // 让它能停在半空或者跑进输入框里没有意义，而且用户看到"往上滑能停在上面
    // 不往下掉"会以为是 bug。拖动只用来触发被拎起来的反应。
    var dragX by remember(packId) { mutableStateOf(0f) }
    var dragY by remember(packId) { mutableStateOf(0f) }

    // 连点计数。1.1 秒内点满 3 下 → 挠痒痒。
    // 阈值取 1.1 秒是原版的数字：手机上目标很小，间隔再长一点连点容易点偏。
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val animatedX by animateFloatAsState(dragX, label = "petDragX")
    val animatedY by animateFloatAsState(dragY, label = "petDragY")

    val k = remember(pack, size, density) { pack.screenScale(size, density.density) }
    // 容器高度必须按**画布**算，不能按身体算：
    // 思考云、跳起来的帧、Zzz 都会超出 contentBox，
    // 按身体高留空间的话这些全被裁掉。
    val canvasHeightDp = with(density) { (pack.canvasH * k).toDp() }
    // 理论高度：pack 的画布高 × 缩放。给 onSizeChanged 还没回调的第一帧兜底。
    val theoreticalHeightPx = pack.canvasH * k
    // Box 的实际像素尺寸。Pet 拿它算绘制原点——
    // DrawScope.size 在 LazyColumn item 里可能是 0（见 Pet 的参数注释）。
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // 容器宽度用屏宽近似。宠物是放在输入框那一行里的，
    // 实际可用宽度会窄一点（左右各 8dp padding），拿它算拖动边界差几个像素无所谓。
    // LocalConfiguration.current 是 @Composable 属性，必须直接写在
    // Composable 函数体里，不能塞进 with(density) 块——提出来读一次。
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val containerWidthPx = with(density) { screenWidthDp.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(canvasHeightDp)
            .onSizeChanged { boxSize = it }
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    animatedX.roundToInt(),
                    animatedY.roundToInt(),
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Pet(
            pack = pack,
            sprites = sprites,
            anim = anim,
            petSize = size,
            // 首次布局前 boxSize 还是 Zero，用理论值兜底——
            // 不兜的话第一帧会按高度 0 画，闪一下才恢复。
            canvasWidthPx = boxSize.width.toFloat()
                .takeIf { it > 0f } ?: containerWidthPx,
            canvasHeightPx = boxSize.height.toFloat()
                .takeIf { it > 0f } ?: theoreticalHeightPx,
            // **必须 fillMaxSize**。Canvas 内部是 Spacer + drawBehind，
            // 只给 fillMaxWidth 的话高度约束是无界的，它会 collapse 到 0——
            // containerSize.height 变成 0，originY 算出来是负数，
            // 画布整个跑到容器上方，看起来就是「预览不全 + 悬空」。
            modifier = Modifier.fillMaxSize(),
            onTap = { leftHalf ->
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt > 1100L) 1 else tapCount + 1
                lastTapAt = now
                if (tapCount >= 3) {
                    // 连点三下 → 挠痒痒，播完把计数清零，
                    // 不然第四次点击又立刻触发一轮
                    tapCount = 0
                    playReaction(pack.reaction("tickle"))
                } else {
                    playReaction(pack.reaction(if (leftHalf) "tapLeft" else "tapRight"))
                }
            },
            onLongPress = {
                // 长按菜单留给第二期（换宠物 / 藏起来 1 小时 / 设置）
            },
            onDragStart = {
                // 被拎起来：播 drag 反应。
                // 原版这个动作是循环的，拎着的时候一直保持被抓住的姿势。
                playReaction(pack.reaction("drag"))
            },
            onDrag = { dx, dy ->
                // 拖出边界必须夹住。之前不限的话往下滑能把宠物推到输入框里面，
                // 再滑远一点就整个跑出可视区——看起来像"一滑就消失"。
                //
                // 横向余量 = (容器宽 - 画布宽) / 2，正好是居中位置左右各能挪多远。
                // 用容器宽一半的话，宠物会被拖到一半还在屏幕外。
                val canvasWidthPx = pack.canvasW * k
                val maxX = ((containerWidthPx - canvasWidthPx) / 2f).coerceAtLeast(0f)
                // 纵向只能往上一点点。
                //
                // 容器高度正好等于画布高，往上提多少，顶部就裁掉多少。
                // 之前给 0.5 倍画布高，提起来头就被切掉——
                // 用户看到的是"提起来头没了"。
                // 0.12 倍是能看出"被拎起来"又不裁到身体的量。
                val maxUp = theoreticalHeightPx * 0.12f
                dragX = (dragX + dx).coerceIn(-maxX, maxX)
                dragY = (dragY + dy).coerceIn(-maxUp, 0f)
            },
            onDragEnd = {
                // 垂直回弹：提起来是"被拎住"的临时状态，松手就该落地。
                // 留着悬空会让人以为它挂住了。
                dragY = 0f
                // 水平**保留**：左右位置是用户自己摆的，回中间等于白拖。
                // dragX 不动。
                //
                // 反应动画也立刻结束。之前是等 playMsOf 播完才清，
                // 用户看到的是"松手了还举着双手不放"。
                reaction = null
                reactionToken++
            },
        )
    }
}
