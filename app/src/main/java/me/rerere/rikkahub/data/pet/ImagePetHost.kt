package me.rerere.rikkahub.data.pet

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 「一张图当宠物」的渲染器。
 *
 * 跟 [PetHost] 的区别：
 *   · [PetHost] 吃素材包（pack.json + 序列图），有十二种状态、每状态一段动画
 *   · 这个只吃一张静态图，没有状态，动作只有一个——被按的时候压一下
 *
 * ## 为什么是"压一下"而不是"缩一下"
 *
 * 想要的是那种果冻手感。做出来的关键是两条：
 *
 * 1. **变形而不是等比缩放**
 *    按下时横向撑开（stretch）、纵向压扁（squash），面积大致守恒。
 *    等比缩小看起来只是"变小了"，不像被按。
 *
 * 2. **松手那一下有回弹**
 *    阻尼比 0.3，弹簧刚度 260——这个组合会冲过头再荡回来，
 *    跟低刚度高阻尼的"慢慢归位"完全是两种手感。
 *
 * 3. **锚点在脚底**
 *    transformOrigin 是 (0.5, 1)，压扁的时候是往地面沉，
 *    不是从中心往两头缩。中心缩看着像原地消失。
 */
@Composable
fun ImagePetHost(
    imagePath: String,
    sizeDp: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // 解码一次。key 用路径：换图了才重新解。
    val bitmap = remember(imagePath) {
        runCatching {
            BitmapFactory.decodeFile(File(imagePath).absolutePath)
        }.getOrNull()
    }
    if (bitmap == null) return

    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)

    // 尺寸跟素材包宠物对齐：PetSize.dp 是**身体高度**，不是画布高。
    val bodyH = with(density) { sizeDp.dp.toPx() }
    val bodyW = bodyH * aspect
    // 抬升余量。素材包宠物能"被拎起来"，单图宠物不挪位置，
    // 但留同样的空白，切换两种宠物时跟输入框的距离不会变。
    val liftMax = bodyH * 0.5f
    val containerH = bodyH + liftMax

    var pressed by remember { mutableStateOf(false) }

    // 按下：高刚度 + 无回弹 → 一瞬间到底，不拖泥带水
    // 松手：低阻尼 + 低刚度 → 冲过头再荡回来
    val squash by animateFloatAsState(
        targetValue = if (pressed) 0.74f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = 1f, stiffness = 3_200f)
        } else {
            spring(dampingRatio = 0.30f, stiffness = 260f)
        },
        label = "imagePetSquash",
    )
    val stretch by animateFloatAsState(
        targetValue = if (pressed) 1.18f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = 1f, stiffness = 3_000f)
        } else {
            spring(dampingRatio = 0.32f, stiffness = 240f)
        },
        label = "imagePetStretch",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { containerH.toDp() }),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(with(density) { bodyW.toDp() })
                .height(with(density) { bodyH.toDp() })
                .graphicsLayer {
                    scaleX = stretch
                    scaleY = squash
                    // 脚底中心。压下去是往地面沉，不是从中间缩。
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .pointerInput(imagePath) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            // 手指抬起或事件被抢都走到这里，pressed 一定会复位。
                            tryAwaitRelease()
                            pressed = false
                        },
                    )
                },
        )
    }
}
