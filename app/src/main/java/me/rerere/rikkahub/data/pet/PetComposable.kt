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
    val drawnH = (pack.canvasH * k).roundToInt()

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

    val paint = remember {
        Paint().apply {
            isAntiAlias = false          // 像素画不插值
            isFilterBitmap = false
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(anim, pack) {
                detectTapGestures(
                    onTap = { offset ->
                        val hit = hitRectPx(pack, anim, k)
                        if (offset.x >= hit.left && offset.x < hit.right &&
                            offset.y >= hit.top && offset.y < hit.bottom
                        ) {
                            onTap(offset.x < drawnW / 2f)
                        }
                    },
                    onLongPress = { offset ->
                        val hit = hitRectPx(pack, anim, k)
                        if (offset.x >= hit.left && offset.x < hit.right &&
                            offset.y >= hit.top && offset.y < hit.bottom
                        ) onLongPress()
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
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
        // 整幅画布在容器里的偏移：脚线贴容器底，水平居中
        val originX = ((containerSize.width - drawnW) / 2f).roundToInt().toFloat()
        val originY = (containerSize.height - drawnH).toFloat()
        val src = sprites.srcRect(anim, cell, s)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(originX, originY)
            val dst = android.graphics.RectF(
                0f, 0f, drawnW.toFloat(), drawnH.toFloat(),
            )
            native.drawBitmap(s.bitmap, src, dst, paint)
            native.restore()
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
private fun hitRectPx(pack: PetPack, anim: PetAnimation, k: Float): androidx.compose.ui.geometry.Rect {
    val box = pack.hitBoxFor(anim)
    return androidx.compose.ui.geometry.Rect(
        left = box.x * k,
        top = box.y * k,
        right = box.right * k,
        bottom = box.bottom * k,
    )
}
