package me.rerere.rikkahub.data.pet

import android.graphics.Paint
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
 * @param size 尺寸档
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
    size: PetSize,
    modifier: Modifier = Modifier,
    onTap: (leftHalf: Boolean) -> Unit = {},
    onLongPress: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    // 每个美术像素占多少物理像素。像素画包这里是整数。
    val k = remember(pack, size, density) { pack.screenScale(size, density.density) }

    // 画布尺寸（物理像素）：包的画布 × k
    val drawnW = (pack.canvasW * k).roundToInt()

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var sheet by remember(pack.id, anim.sheet) { mutableStateOf<PetSheet?>(null) }

    // 解码放到 LaunchedEffect 里——它在 IO 协程上跑，不会卡住首帧
    LaunchedEffect(pack.id, anim.sheet) {
        if (sheet == null) {
            sheet = sprites.sheet(pack.id, anim)
        }
    }

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

    val paint = remember {
        Paint().apply {
            isAntiAlias = false          // 像素画不插值
            isFilterBitmap = false
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            // 点击和拖动分成两个 pointerInput 块是必须的——
            // 合成一个的话 detectTapGestures 和 detectDragGestures 会互相抢，
            // 拖动永远触发不了。
            //
            // key 里不放 anim：动画每换一次就重建手势处理器，
            // 正在拖的时候正好换动画，拖动就断了。手势只跟 pack 有关。
            .pointerInput(pack) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k, originFor(containerSize, pack, k))
                        // Rect.contains 只收 Offset，不收 (x, y) 两个 Float
                        if (hit.contains(offset)) {
                            onTap(offset.x < containerSize.width / 2f)
                        }
                    },
                    onLongPress = { offset ->
                        val hit = hitRectPx(pack, latestAnim, k, originFor(containerSize, pack, k))
                        if (hit.contains(offset)) onLongPress()
                    },
                )
            }
            .pointerInput(pack) {
                detectDragGestures(
                    onDragStart = { },
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
        // 画布原点：脚线（pack.baseline）贴在容器底部，水平居中。
        //
        // 之前写的是 `containerSize.height - drawnH`——那是让**画布底边**贴容器底。
        // 但脚线在 baseline=48，画布高 52，中间差 4 个逻辑像素，
        // 所以宠物看起来"悬在输入框上方一段距离"。必须按 baseline 算。
        val originX = ((containerSize.width - drawnW) / 2f).roundToInt().toFloat()
        val originY = (containerSize.height - pack.baseline * k).toFloat()

        val src = sprites.srcRect(anim, cell, s)
        // 帧格贴回画布的位置是 anim.box，**不是**整个画布。
        //
        // 之前这里写的是 RectF(0, 0, drawnW, drawnH)，等于把 36×24 的帧格
        // 拉伸到 52×52 的画布上——比例全错，画出来只有中间一块，
        // 看起来像"缺了一块"。
        val box = anim.box
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(originX, originY)
            val dst = android.graphics.RectF(
                box.x * k,
                box.y * k,
                box.right * k,
                box.bottom * k,
            )
            native.drawBitmap(s.bitmap, src, dst, paint)
            native.restore()
        }
    }
}

/**
 * 画布原点在容器里的位置。绘制和命中判定必须用同一个值，
 * 否则会出现「看起来在这、点上去没反应」。
 */
private fun originFor(
    containerSize: IntSize,
    pack: PetPack,
    k: Float,
): Pair<Float, Float> {
    val drawnW = (pack.canvasW * k).roundToInt()
    return Pair(
        ((containerSize.width - drawnW) / 2f).roundToInt().toFloat(),
        containerSize.height - pack.baseline * k,
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
