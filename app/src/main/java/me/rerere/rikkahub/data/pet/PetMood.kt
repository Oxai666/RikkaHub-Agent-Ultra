package me.rerere.rikkahub.data.pet

/**
 * 宠物状态。移植自 dsh-go 的 Clawd on Desk 协议（AGPL-3.0）。
 *
 * 优先级沿用 Clawd：error 8 > notification 7 > sweeping 6 > attention 5 >
 * carrying / juggling 4 > working 3 > thinking 2 > idle / roam 1 > sleeping 0。
 *
 * 「一次性」和「持续」分开：
 *  - 一次性：attention / error / sweeping / notification / carrying。
 *    播完落到 minDisplay 的时长就退回底层状态，autoReturn 到了还没新事件就彻底退。
 *  - 持续：thinking / working / juggling / idle / sleeping / roaming。
 *    只要底层条件还在就一直播。
 *
 * 为什么这么分：一次性态表达的是「刚刚发生了一件事」，它需要一个自然的
 * 亮相时间和自动退场；持续态表达的是「现在处于什么状况」，跟着条件走。
 * 混在一起的话，要么庆祝动作一闪就没了，要么「在跑」永远退不下去。
 */
enum class PetState(val serial: String, val priority: Int, val oneShot: Boolean) {
    ERROR("error", 8, true),
    NOTIFICATION("notification", 7, true),
    SWEEPING("sweeping", 6, true),
    ATTENTION("attention", 5, true),
    CARRYING("carrying", 4, true),
    JUGGLING("juggling", 4, false),
    WORKING("working", 3, false),
    THINKING("thinking", 2, false),
    IDLE("idle", 1, false),
    ROAM("roam", 1, false),
    SLEEPING("sleeping", 0, false);

    companion object {
        fun fromSerial(value: String?): PetState =
            entries.firstOrNull { it.serial == value } ?: IDLE
    }
}

/**
 * 「同时有好几件事在发生」时挑哪个。
 *
 * 优先级高的赢；优先级相同的按 [PetState.entries] 顺序（先声明的赢）。
 * 这条规则单独抽出来是为了能写单测——它是整套状态机里最容易写错的一处。
 */
fun pickHighest(states: Collection<PetState>): PetState =
    states.maxWithOrNull(
        compareBy<PetState> { it.priority }.thenBy { -it.ordinal }
    ) ?: PetState.IDLE

/**
 * 宠物此刻该表达什么。
 *
 * 给 UI 用的输入：聊天页把「这一轮在不在跑」「有没有等你确认的卡」
 * 「刚才那轮是成功还是失败」喂进来，这里算出该播哪个状态。
 */
data class PetMood(
    /** 这一轮在生成。 */
    val running: Boolean = false,
    /** 同时在跑几个（分档用：1 / 2 / 3+）。 */
    val runningCount: Int = 1,
    /** 有工具停在 Pending，等你点允许/拒绝。 */
    val awaitingApproval: Boolean = false,
    /** 模型调了 ask_user，等你回答。 */
    val awaitingAnswer: Boolean = false,
    /** 刚出错（这一轮以错误结束）。 */
    val justFailed: Boolean = false,
    /** 刚做完（这一轮正常结束），还没被看过。 */
    val justFinished: Boolean = false,
    /** 正在压缩上下文。 */
    val compacting: Boolean = false,
    /** 用户 60 秒没碰过屏幕。 */
    val idleForAWhile: Boolean = false,
) {
    /**
     * 当前该播哪个状态。
     *
     * 优先级表跟 Clawd 一致，但「等你确认/回答」合并进 [PetState.NOTIFICATION]：
     * 对宠物来说这两件事是同一个意思——有事要你处理。真正的区分留给气泡文案。
     */
    fun state(): PetState {
        val candidates = buildList {
            if (justFailed && !running) add(PetState.ERROR)
            if (awaitingApproval || awaitingAnswer) add(PetState.NOTIFICATION)
            if (compacting) add(PetState.SWEEPING)
            // 「做完了」用 attention 表达：庆祝一下，但优先级低于「等你处理」。
            if (justFinished && !running) add(PetState.ATTENTION)
            if (running) {
                add(if (runningCount >= 2) PetState.JUGGLING else PetState.WORKING)
            }
            // 模型在思考（有工具调用但还没出结果）——这里用跟 running 的差异表达：
            // 简化版里 running 就是 thinking + working 的总称，细分留给第二期
            if (!running && !justFinished && !justFailed) {
                add(if (idleForAWhile) PetState.SLEEPING else PetState.IDLE)
            }
        }
        return pickHighest(candidates)
    }

    /** working / juggling 的分档：同时跑几个对话。 */
    fun tier(): Int = runningCount.coerceIn(1, 3)
}
