package me.rerere.rikkahub.data.pet

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // remember 必须在 Composable 的顶层调用，不能塞进 `?:` 右边——
    // 那样是条件调用，Compose 的重组位置对不上会直接崩。
    val stateAnim = remember(baseState, tier) { pack.primary(baseState.serial, tier) }
    val anim = reaction ?: stateAnim

    // 拖动偏移：跟手，松手回零
    var dragX by remember { mutableStateOf(0f) }
    val animatedX by animateFloatAsState(dragX, label = "petDragX")

    // 高度：身体高度换算成 dp，给上一层留出空位
    val bodyHeightDp = with(density) {
        (pack.contentBox.h * pack.screenScale(size, density.density)).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 容器比身体高一截，给跳起来、思考云这些留头顶空间
            .height(bodyHeightDp * 1.6f)
            .offset { androidx.compose.ui.unit.IntOffset(animatedX.roundToInt(), 0) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Pet(
            pack = pack,
            sprites = sprites,
            anim = anim,
            size = size,
            modifier = Modifier.fillMaxWidth(),
            onTap = { leftHalf ->
                // 戳一下：左右半身各播一段，纯反馈，不改任何状态
                playReaction(pack.reaction(if (leftHalf) "tapLeft" else "tapRight"))
            },
            onLongPress = {
                // 长按菜单留给第二期（换宠物 / 藏起来 1 小时 / 设置）
            },
            onDrag = { dx, _ -> dragX += dx },
            onDragEnd = {
                // 松手吸附回原位。App 内不记拖动位置——宠物是站在输入框上的，
                // 记住偏移反而会让它下次出现在奇怪的地方
                dragX = 0f
            },
        )
    }
}
