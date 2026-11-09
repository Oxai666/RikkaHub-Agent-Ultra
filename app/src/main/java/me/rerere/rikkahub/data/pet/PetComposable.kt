package me.rerere.rikkahub.data.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 宠物本体。移植自 dsh-go 的 `PetView.kt`（AGPL-3.0），从 Android View 改成 Compose。
 *
 * 跟原版一样的几件事：
 *  - **每个美术像素对应整数个物理像素**，位置也对齐物理像素。
 *    像素画不这么做边缘就会糊，而且拖动时会出现半像素抖动。
 *  - **只在帧号变化时重绘**。待机是一张静图，没必要按屏幕刷新率重画。
 *  - **命中区只算身体**。思考云、Zzz、音符这些飘在外面的道具不算可点区域，
 *    点它们应该穿透给下面的输入框。
 *
 * 跟原版不一样的地方：
 *  - 不处理窗口尺寸变化（App 内不需要）
 *  - 不做「按住时窗口变大」（原版是为了悬浮窗不能超出屏幕，App 内随便画）
 *
 * @param pack 当前宠物包
 * @param sprites 素材加载器
 * @param anim 正在播的动画
 * @param petSize 尺寸档
 * @param pixelSize 本组件的像素尺寸，由调用方给（用来算缩放）
 * @param onTap 点了身体。参数是「点的左半边还是右半边」
 * @param onLongPress 长按
 * @param onDrag 拖动中
 * @param onDragEnd 松手
 */
@Composable
fun Pet(
    pack: PetPack,
    sprites: PetSprites,
    anim: PetAnimation,
    // **不能叫 size**。Canvas 的 draw lambda 是 DrawScope.() -> Unit，
    // 里面有个 `size: Size`（当前绘制区域尺寸）。参数也叫 size 的话
    // 会把它遮蔽掉，`size.width` 就变成在 PetSize 上找 width——找不到。
    // 那两行 Unresolved reference 'width' / 'height' 就是这么来的。
    petSize: PetSize,
    /**
     * 绘制区域的确切像素尺寸，由调用方给。
     *
     * **不要用 DrawScope.size 算原点**——实测在 LazyColumn 的 item 里
     * 它可能是 0（垂直约束无界时 fillMaxSize 会塌成 0），
     * then originY = 0 - baseline*k 变成负数，整幅画布被推到容器上方，
     * 只剩底部一条边露出来。
     *
     * 用户看到的是「Deepy 变成一条蓝色横条」「鲸娘变成 4 个压扁的头」，
     * 而拖动时 offset 改变又让它短暂露出来，看起来像「拖起来就正常」。
     */
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    modifier: Modifier = Modifier,
    onTap: (leftHalf: Boolean) -> Unit = {},
    onLongPress: () -> Unit = {},
    /** 拖动真正开始时调一次。用来播"被拎起来"的反应动画。 */
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    // 每个美术像素占多少物理像素。像素画包这里是整数。
    val k = remember(pack, petSize, density) { pack.screenScale(petSize, density.density) }

    // 画布尺寸（物理像素）：包的画布 × k
    val drawnW = (pack.canvasW * k).roundToInt()


    // **同步**加载，不走 LaunchedEffect。
    //
    // 原来是 `var sheet by remember(...) { mutableStateOf(null) }` 加一个
    // LaunchedEffect 异步填。那样有个隐蔽的竞态：
    //   · key 变了 → remember 重置成 null → LaunchedEffect 启动
    //   · 但如果上一轮的协程还没被取消，或者 sprites 内部状态没就绪，
    //     新协程可能读到 null 然后直接返回
    //   · sheet 停在 null，而 LaunchedEffect 的 key 没变，不会重试
    //   · 结果就是这一只一直空白，切走再切回来才重新触发
    //
    // 用户看到的现象完全对得上：切来切去「随机」有一只不显示。
    //
    // 素材很小（单张 130KB 左右），同步解码几十毫秒，首次会顿一下，
    // 换来的是「key 一定对应唯一结果」——不会再有中间态。
    val sheet = remember(pack.id, anim.sheet) { sprites.sheet(pack.id, anim) }

    // 当前该画第几格。只有它变化时才重组 Canvas。
    var cell by remember(anim) { mutableIntStateOf(anim.frames.firstOrNull()?.cell ?: 0) }
    var elapsed by remember(anim) { mutableLongStateOf(0L) }

    LaunchedEffect(anim) {
        val cursor = PetFrameCursor(anim)
        var last = 0L
        elapsed = 0L
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                last = now
                elapsed += dt / 1_000_000L
                val next = cursor.cellAt(elapsed)
                if (next != cell) cell = next
            }
            // 不循环的动画播完就别再空转了
            if (!anim.loop && elapsed >= cursor.totalMs) break
        }
    }

    // pointerInput 的 key 是 pack（不能放 anim，否则每次换动画重建手势、
    // 拖到一半就断）。但闭包里读的 anim 会停在首次捕获的那个值——
    // 命中区就一直按旧动画算。用 rememberUpdatedState 拿到最新值。
    val latestAnim by rememberUpdatedState(anim)

    // 解码好的位图转成 Compose 的 ImageBitmap。
    // **必须在 Canvas 外面做**：Canvas 的 draw lambda 是 DrawScope.() -> Unit，
    // 不是 @Composable，里面不能调 remember。
    val image = remember(sheet?.bitmap) { sheet?.bitmap?.asImageBitmap() }

    Canvas(
        modifier = modifier
            // 点击和拖动分成两个 pointerInput 块是必须的——
            // 合成一个的话 detectTapGestures 和 detectDragGestures 会互相抢，
            // 拖动永远触发不了。
            //
            // key 里不放 anim：动画每换一次就重建手势处理器，
            // 正在拖的时候正好换动画，拖动就断了。手势只跟 pack 有关。
            .pointerInput(pack) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k, originFor(canvasWidthPx, canvasHeightPx, pack, k))
                        // Rect.contains 只收 Offset，不收 (x, y) 两个 Float
                        if (hit.contains(offset)) {
                            onTap(offset.x < canvasWidthPx / 2f)
                        }
                    },
                    onLongPress = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k, originFor(canvasWidthPx, canvasHeightPx, pack, k))
                        if (hit.contains(offset)) onLongPress()
                    },
                )
            }
            .pointerInput(pack) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        val s = sheet ?: return@Canvas
        val img = image ?: return@Canvas

        // 画布原点：脚线（pack.baseline）贴在容器底部，水平居中。
        // 尺寸用调用方传进来的，不读 DrawScope.size——原因见参数注释。
        val drawnW = (pack.canvasW * k).roundToInt().toFloat()
        val originX = ((canvasWidthPx - drawnW) / 2f).roundToInt().toFloat()
        val originY = canvasHeightPx - pack.baseline * k

        val src = sprites.srcRect(anim, cell, s)
        val box = anim.box

        // 用 Compose 的 drawImage，不用 drawIntoCanvas + nativeCanvas。
        //
        // 后者要自己 translate、自己管坐标系，任何一处算错都是「空白一片」——
        // 而且编译期完全看不出来。drawImage 直接收 srcOffset/srcSize/
        // dstOffset/dstSize 四个矩形，坐标系就是 DrawScope 的像素坐标，
        // 没有中间状态可以让它错。
        drawImage(
            image = img,
            srcOffset = IntOffset(src.left, src.top),
            srcSize = IntSize(src.right - src.left, src.bottom - src.top),
            dstOffset = IntOffset(
                (originX + box.x * k).roundToInt(),
                (originY + box.y * k).roundToInt(),
            ),
            dstSize = IntSize(
                (box.w * k).roundToInt(),
                (box.h * k).roundToInt(),
            ),
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * 画布原点在容器里的位置。绘制和命中判定必须用同一个值，
 * 否则会出现「看起来在这、点上去没反应」。
 */
private fun originFor(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    pack: PetPack,
    k: Float,
): Pair<Float, Float> {
    val drawnW = (pack.canvasW * k).roundToInt()
    return Pair(
        ((canvasWidthPx - drawnW) / 2f).roundToInt().toFloat(),
        canvasHeightPx - pack.baseline * k,
    )
}

/**
 * 命中区，换算到画布坐标（物理像素）。
 *
 * 用 `pack.hitBoxFor(anim)`——那是量在身体上的框，不含思考云、Zzz 这些
 * 飘在外面的东西。点它们应该穿透，不然宠物就成了输入框上方一块凭空多出来的
 * 死区。
 */
private fun hitRectPx(
    pack: PetPack,
    anim: PetAnimation,
    k: Float,
    origin: Pair<Float, Float>,
): androidx.compose.ui.geometry.Rect {
    val box = pack.hitBoxFor(anim)
    // 加上画布原点，换算成**容器坐标**——触摸事件给的是容器坐标，
    // 直接拿画布坐标比会整体偏掉一个 origin
    return androidx.compose.ui.geometry.Rect(
        left = origin.first + box.x * k,
        top = origin.second + box.y * k,
        right = origin.first + box.right * k,
        bottom = origin.second + box.bottom * k,
    )
}
