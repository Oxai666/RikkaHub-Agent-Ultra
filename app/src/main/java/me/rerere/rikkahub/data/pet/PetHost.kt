package me.rerere.rikkahub.data.pet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    /**
     * 外部给的 mood。传 null 表示订阅 [PetSignal] 的全局状态——
     * 聊天页用 null，设置页的预览区传一个固定值（只想看动画，不想受真实状态影响）。
     */
    mood: PetMood? = null,
    modifier: Modifier = Modifier,
) {
    if (packId == null) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val sprites = remember { PetSprites(context) }
    val pack = remember(packId) { sprites.loadPack(packId) }
    if (pack == null) return

    // 没传 mood 就订阅全局信号
    val liveMood by me.rerere.rikkahub.data.pet.PetSignal.mood.collectAsStateWithLifecycle()
    val effectiveMood = mood ?: liveMood

    // 「睡觉」：每秒 tick 一次，直接从 PetSignal 算，不存中间状态。
    //
    // 之前用 `var asleep` 缓存判断结果，有个同步问题：
    // 那个状态只在 LaunchedEffect 的 delay 到点后才更新，
    // 而 LaunchedEffect 的 key 里有 liveMood——任何 mood 变化都会重启协程、
    // delay 从头计时，asleep 就一直是旧值。表现出来就是
    // 「打断了之后回不到原来的状态，非要等下一轮或再等 60 秒」。
    //
    // 改成 tick：每秒 +1 触发重组，shouldSleep 每帧现算，没有中间态。
    var clockTick by remember(packId) { mutableIntStateOf(0) }
    LaunchedEffect(packId) {
        if (mood != null) return@LaunchedEffect  // 预览不该睡觉
        while (true) {
            delay(1_000L)
            clockTick++
        }
    }
    val shouldSleep = remember(clockTick, effectiveMood) {
        mood == null &&
            me.rerere.rikkahub.data.pet.PetSignal.shouldSleep() &&
            !effectiveMood.running &&
            !effectiveMood.awaitingApproval
    }

    // 「刚做完 / 刚出错」也是有窗口的，过了就回待机。
    // 跟 shouldSleep 一样每秒现算，不存缓存——缓存会失步。
    val celebrating = remember(clockTick, effectiveMood) {
        mood == null && me.rerere.rikkahub.data.pet.PetSignal.isCelebrating()
    }
    val failing = remember(clockTick, effectiveMood) {
        mood == null && me.rerere.rikkahub.data.pet.PetSignal.isFailing()
    }

    val finalMood = remember(effectiveMood, shouldSleep, celebrating, failing) {
        effectiveMood.copy(
            idleForAWhile = shouldSleep,
            justFinished = celebrating,
            justFailed = failing,
        )
    }

    // 基础状态
    val baseState = remember(finalMood) { finalMood.state() }
    val tier = remember(finalMood) { finalMood.tier() }

    // 一次性反应会临时盖住基础状态，播完自动退回
    // reaction 也要跟 packId 绑：切包时旧包的动画对象必须丢掉，
    // 否则播的可能是上一个包的一段动画，尺寸对不上一样会崩
    var reaction by remember(packId) { mutableStateOf<PetAnimation?>(null) }
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
    //
    // **key 里必须有 pack**。之前是 remember(baseState, tier)——
    // 切包时 baseState 和 tier 都没变（都是 idle / 1），remember 不重算，
    // stateAnim 还是上一个包的动画对象。
    //
    // 于是：pack 是 Deepy（画布 52×52、格子 180×120），
    // anim 是鲸娘的（格子 52×49）→ src 取 52×49、dst 算成 52*5 × 49*5，
    // 整张图被拉伸 5 倍还错位。用户看到的就是"崩坏"。
    val stateAnim = remember(pack, baseState, tier) {
        pack.primary(baseState.serial, tier)
    }
    val anim = reaction ?: stateAnim

    // 横向位置要跨页面保留——拖到右边、去设置看一眼回来，它得还在右边。
    // 存进 PreferencesStore，key 是 petOffsetX。
    // 预览页（mood 传了非 null）不做持久化，那里只是看大小用的。
    //
    // 纵向**不持久化**：垂直是"被拎起来"的临时状态，松手就该落地。
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore =
        org.koin.compose.koinInject()
    val persistScope = rememberCoroutineScope()
    val persistX = mood == null
    val liveSettings by if (persistX) {
        settingsStore.settingsFlow.collectAsStateWithLifecycle()
    } else {
        remember {
            kotlinx.coroutines.flow.MutableStateFlow(
                me.rerere.rikkahub.data.datastore.Settings.dummy()
            )
        }.collectAsStateWithLifecycle()
    }
    val savedOffsetX = if (persistX) liveSettings.petOffsetX else 0f

    // remember(packId, savedOffsetX)：首次进入和切回页面时都用存下来的值初始化。
    // 之前是 remember(packId) 固定从 0 开始，所以每次回页面都弹回中间。
    var dragX by remember(packId, savedOffsetX) { mutableStateOf(savedOffsetX) }
    var dragY by remember(packId) { mutableStateOf(0f) }

    // 连点计数。1.1 秒内点满 3 下 → 挠痒痒。
    // 阈值取 1.1 秒是原版的数字：手机上目标很小，间隔再长一点连点容易点偏。
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val animatedX by animateFloatAsState(dragX, label = "petDragX")
    // 松手落地用带一点回弹的弹簧，不是瞬间归位。
    // mediaBouncy + 低刚度 = 落下去的时候有一点下沉回弹，
    // 比 LinearEasing 直接滑回去更像"掉下来"。
    val animatedY by animateFloatAsState(
        targetValue = dragY,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
        ),
        label = "petDragY",
    )

    val k = remember(pack, size, density) { pack.screenScale(size, density.density) }
    // 容器高度必须按**画布**算，不能按身体算：
    // 思考云、跳起来的帧、Zzz 都会超出 contentBox，
    // 按身体高留空间的话这些全被裁掉。
    val canvasHeightDp = with(density) { (pack.canvasH * k).toDp() }
    // 容器宽度也按画布算。**这是这次的关键改动**：
    // 之前容器是 fillMaxWidth（整个屏幕宽），Pet 在里面居中算 originX。
    // 现在容器宽 = 画布宽，画布原点就是容器左上角，originX 这个概念消失。
    val canvasWidthDp = with(density) { (pack.canvasW * k).toDp() }
    // 能把自己提多高。容器高度 = 画布高 + 这个值，
    // 画布平时停在容器底部，提起来最多顶到容器顶部——不会被裁。
    //
    // 中间有一版把它改成「容器只占画布高、靠 offset 超出容器」，那是错的：
    // 宠物确实能提起来了，但正常位置也偏了。改回来。
    val liftMax = pack.canvasH * k * 0.5f
    val containerHeightDp = with(density) { (pack.canvasH * k + liftMax).toDp() }
    // 容器宽度用屏宽近似。宠物是放在输入框那一行里的，
    // 实际可用宽度会窄一点（左右各 8dp padding），拿它算拖动边界差几个像素无所谓。
    // LocalConfiguration.current 是 @Composable 属性，必须直接写在
    // Composable 函数体里，不能塞进 with(density) 块——提出来读一次。
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val containerWidthPx = with(density) { screenWidthDp.dp.toPx() }

    // Composable 只能有一个根节点，调试文字 + 宠物框要包进 Column
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
    ) {
    // 外层：占满可用宽度，高度 = 画布 + 抬升余量。
    //
    // **对齐必须是 TopCenter，不能是 BottomCenter**：
    // 内层的 offset 里已经含了 liftMax 作为「平时贴底」的基线，
    // 再用 BottomCenter 等于把 liftMax 加两次，画布会被推到容器外面。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(containerHeightDp),
        contentAlignment = Alignment.TopCenter,
    ) {
    // 内层：尺寸**正好等于画布**，画布原点就是 (0,0)。
    // 正常状态（dragY=0）时靠 liftMax 的偏移贴住容器底部，
    // 提起来（dragY 负到 -liftMax）时正好顶到容器顶部。
    Box(
        modifier = Modifier
            .width(canvasWidthDp)
            .height(canvasHeightDp)
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    animatedX.roundToInt(),
                    (animatedY + liftMax).roundToInt(),
                )
            },
    ) {
        Pet(
            pack = pack,
            sprites = sprites,
            anim = anim,
            petSize = size,
            showErrorBadge = baseState == PetState.ERROR,
            // 外层 Box 的尺寸严格等于画布尺寸，fillMaxSize 正好铺满，
            // Pet 里画布原点就是 (0,0)，不需要任何居中或 baseline 计算。
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
                val theoreticalWidthPx = pack.canvasW * k
                val maxX = ((containerWidthPx - theoreticalWidthPx) / 2f).coerceAtLeast(0f)
                // 纵向能提的最大高度 = 容器给画布留的抬升余量。
                // 再多就会顶出容器被裁。
                val maxUp = liftMax
                dragX = (dragX + dx).coerceIn(-maxX, maxX)
                dragY = (dragY + dy).coerceIn(-maxUp, 0f)
            },
            onDragEnd = {
                // 横向位置写回持久化。放在这里而不是 onDrag 里，
                // 是因为拖动过程中每一帧都写 DataStore 太重。
                if (persistX && dragX != savedOffsetX) {
                    val target = dragX
                    persistScope.launch {
                        settingsStore.update { it.copy(petOffsetX = target) }
                    }
                }
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
    }
}
