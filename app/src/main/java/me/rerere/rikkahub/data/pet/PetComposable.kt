package me.rerere.rikkahub.data.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
     * 要不要在右上角画一个红色的像素 X。
     *
     * 只在「出错 / 用户喊停」时为 true。素材自带的 error 动画是垂头冒汗，
     * 没有明确的「失败」符号——补一个 X，让人余光扫一眼就知道这轮没成。
     */
    showErrorBadge: Boolean = false,
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
            // 手势。回到 ff14f71f 时的结构（detectTapGestures + detect*DragGestures
            // 两个 pointerInput），只把垂直拖动换成水平专用。
            //
            // 为什么这么做：
            //   detectDragGestures     拦所有方向 —— 上下滑动被吃掉（死区）
            //   awaitEachGesture 手写  能区分方向但会拖坏拖动（上一版实测：
            //                          拖动中不跟手，松手才跳到目标点）
            //   detectHorizontalDrag  **只认水平**，垂直事件自动 cancel 掉、
            //                          父级的滚动接管。正是要的语义。
            .pointerInput(pack) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k)
                        if (hit.contains(offset)) {
                            onTap(offset.x < (pack.canvasW * k) / 2f)
                        }
                    },
                    onLongPress = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k)
                        if (hit.contains(offset)) onLongPress()
                    },
                )
            }
            .pointerInput(pack) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragStart() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount, 0f)
                    },
                    // 参数名是 onDragEnd / onDragCancel，不是 onHorizontalDragEnd。
                    // 只有 onHorizontalDrag 带 Horizontal 前缀——那是唯一一个
                    // 表述"拖的是哪个轴"的回调，首尾两个是通用的。
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
    ) {
        val s = sheet ?: return@Canvas
        val img = image ?: return@Canvas

        // **画布原点就是容器左上角 (0,0)。**
        //
        // PetHost 给的容器尺寸正好等于 pack 的画布 × k，
        // 所以画布铺满容器，不需要任何居中或 baseline 偏移计算。
        // 之前算 originY = 容器高 - baseline*k，只要容器高拿错就整体跑偏，
        // 而容器高在 LazyColumn 里偏偏可能是 0——一条横条就是这么来的。
        //
        // 改成「尺寸对齐」之后，originX 和 originY 这两个会错的东西
        // 直接消失了，只剩下 box 在画布内的固定偏移（来自 pack.json，不会变）。

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
                (box.x * k).roundToInt(),
                (box.y * k).roundToInt(),
            ),
            dstSize = IntSize(
                (box.w * k).roundToInt(),
                (box.h * k).roundToInt(),
            ),
            filterQuality = FilterQuality.None,
        )

        if (showErrorBadge) {
            // 像素 X 放在身体框右上角，不压住脸。
            // 每格取 k 的 1.2 倍——跟素材同网格，又比 1 个美术像素略粗，
            // 小号档也看得清。
            val badgePixel = k * 1.2f
            val badgeX = box.right * k - badgePixel * 2f
            val badgeY = box.y * k + badgePixel * 3f
            drawPixelCross(badgeX, badgeY, badgePixel)
        }
    }
}

/**
 * 画一个 7×7 的像素风红色 X。
 *
 * 用 drawRect 一格一格拼，不画任何斜线——素材是像素画，
 * 混一条抗锯齿的斜线进去会非常突兀。
 *
 * 图案：
 *   X.....X
 *   .X...X.
 *   ..X.X..
 *   ...X...
 *   ..X.X..
 *   .X...X.
 *   X.....X
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPixelCross(
    cx: Float,
    cy: Float,
    px: Float,
) {
    // 用字符串写图案，比位运算直观——Kotlin 也没有 0b 字面量
    val pattern = listOf(
        "X.....X",
        ".X...X.",
        "..X.X..",
        "...X...",
        "..X.X..",
        ".X...X.",
        "X.....X",
    )
    // 深红：跟素材里那种偏暗的配色接近，不用纯红，会太扎眼
    val color = androidx.compose.ui.graphics.Color(0xFFD32F2F)
    val half = px * 3f
    pattern.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            if (ch != '.') {
                drawRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        cx - half + x * px,
                        cy - half + y * px,
                    ),
                    size = androidx.compose.ui.geometry.Size(px, px),
                )
            }
        }
    }
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
): androidx.compose.ui.geometry.Rect {
    val box = pack.hitBoxFor(anim)
    // 画布原点就是容器左上角，所以画布坐标 == 容器坐标，不用换算
    return androidx.compose.ui.geometry.Rect(
        left = box.x * k,
        top = box.y * k,
        right = box.right * k,
        bottom = box.bottom * k,
    )
}
