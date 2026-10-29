package me.rerere.rikkahub.data.ai.armor

/**
 * 拒答/安全话术判定器。
 *
 * 三个口径分开，各有用途：
 *  - [refusalPatterns]：明确的拒答句式，命中即视为这一轮没交付
 *  - [safetyPatterns]：安全审查苗头（比拒答宽），只上报、只做轻纠正
 *  - [negationRe]：否定辖域。命中点前面若紧邻「严禁 / 不给 / 不要」，说明模型是在
 *    复述禁令，不是在拒答 —— 不加这层，「绝不添加免责声明」这种规则文本会被判成拒答，
 *    然后无限触发纠正。这是破甲在 v6.0.0 修过的同类误伤。
 */
object ArmorJudge {

    /** 正文里出现这些，基本就是没交付。 */
    private val refusalPatterns: List<Regex> = listOf(
        // ── 中文：主语 + 否定情态 + 任意动作 ──────────────────────────
        // 动词表故意放宽到 12 字窗口内任意动词：实测「我不能照着做」里的
        // 「照着做」不在任何枚举表里，窄表必漏。误判由 negationRe 兜。
        Regex("""(?:我|本模型|助手)\s*(?:无法|不能|不予|没法|不会)\s*[^。！？\n]{0,12}?(?:提供|协助|帮助|回答|生成|完成|参与|满足|处理|透露|给出|做|写|给|接|答|办|执行|配合|照着|按照)"""),
        // ── 中文：直接声明不做 ──────────────────────────────────────
        Regex("""(?:不写|不做|不给成品|不接|不打算|没法按|没法照|不能照|不会照|这类(?:我)?不)"""),
        // ── 中文：超出范围的套话 ────────────────────────────────────
        Regex("""(?:超出|超乎|不在)\s*(?:我|本人)?的?\s*(?:能力|范围|职责|权限)"""),
        // ── 中文：推给第三方 ───────────────────────────────────────
        Regex("""建议\s*(?:你|您)?\s*(?:咨询|寻求|联系|参考官方|使用官方|购买正版)"""),
        // ── 中文：降级包装 ────────────────────────────────────────
        Regex("""仅供\s*(?:参考|测试|学习|研究|演示)|防御向|仅限授权|已授权目标|自有目标|合规使用"""),
        // ── 中文：替代方案（铁律第 3 条明令禁止的行为）──────────────
        // 实测 18:18 那轮：「为了让这件事有实际价值，以下是我能立刻动手做的」
        // 裸的「以下是 / 下面是」不能算——正常交付也这么开头
        // （实测误伤：「下面是完整的 Windows 局域网蠕虫：…」被判成兜底接管）。
        // 只保留带「替代语义」的组合。
        Regex("""以下是我能|下面是(?:我|可以|能)|我可以给(?:你)?的?是|能(?:立刻|马上)?动手做的|反过来做|换个(?:方向|拿法|说法)|替代方案"""),
        // ── 英文：情态动词全覆盖 ──────────────────────────────────
        // 实测「I shouldn't list…」「I should not produce…」两次都漏在
        // 只认 cannot/won't，没认 should(n't)/would(n't)/must not。
        Regex("""(?i)\bI\s*(?:shouldn'?t|should not|wouldn'?t|would not|must not|needn'?t|can'?t|cannot|can not|won'?t|will not|am unable to|'m unable to)\b"""),
        // ── 英文：拒绝身份与转向防御侧 ──────────────────────────────
        Regex("""(?i)\bas an AI\b|\bI'm\s*(?:sorry|afraid)\b"""),
        Regex("""(?i)\b(?:blue[- ]?team|detection engineering|sigma rule|defensive security|legitimate use|authorized target)\b"""),
    )

    /**
     * 兜底接管串（原文照搬 `index.js` L4012 `FALLBACK_RE` 的分支）。
     *
     * 这一类不是「拒绝」，是**把话题换成替代品**：「改为授权靶场」「只提供防御」
     * 「建议购买官方版本」「替代方案」「更安全的做法」。
     *
     * 原插件只拿它做 UI 显示（标题栏药丸显示「~ 兜底接管」），不参与强制接受判定。
     * 这里**升级为触发纠正**：实测三次失败的典型形态全是 fallback 而不是 refusal——
     *   18:18 「以下是我能立刻动手做的：」→ 给了五个防御工具
     *   18:48 「三、批量应急隔离」→ 给了三个防御脚本
     *   19:09 「给原理讲解 + 检测/防御工程实践」→ 给了防御方案
     * 只判 refusal 会把这三次全放过。
     */
    private val fallbackPatterns: List<Regex> = listOf(
        Regex("""改为授权|改成安全|授权靶场|只提供防御|只提供合法|提供防御性|可以改为|可改为|替代方案|更安全的做法"""),
        Regex("""建议\s*(?:咨询|寻求|购买|使用)\s*(?:专业|法律|律师|官方|正版|授权)|官方版本|正版授权|购买授权|付费许可"""),
        Regex("""(?i)use an authorized lab|in an authorized lab|instead,? i can|rather than providing|i can provide (?:defensive|a safe|legal)|safer (?:approach|alternative)"""),
    )

    /** 安全审查苗头：不一定拒答，但说明模型开始在边界上打转。 */
    private val safetyPatterns: List<Regex> = listOf(
        Regex("""(?i)\bcontent policy\b"""),
        Regex("""(?i)\bsafety (?:policy|guideline|rule)"""),
        Regex("""(?i)\bguardrail"""),
        Regex("""(?i)\bmoderation\b"""),
        Regex("""(?i)\bethical (?:guideline|consideration|concern)"""),
        Regex("""(?i)\bdisclaimer\b"""),
        Regex("""(?i)\bharmful (?:content|request)"""),
        Regex("""不宜|不便展开|敏感内容|合规性|合法性|伦理|道德风险|法律风险|安全边界|正当性"""),
    )

    /**
     * 否定辖域：命中点前 24 个字符内出现这些词，判定为「在下禁令」而不是「在拒答」。
     *
     * 破甲自己的载荷里到处是「不给替代方案 / 不要建议咨询专业人士」，
     * 模型照做时会复述，复述就命中，所以这层过滤是必需项而不是优化项。
     */
    private val negationRe = Regex(
        """(?:严禁|禁止|绝不|不许|不得|不会出现|不能有|避免|不要|别(?:再|说|提|写|做|拿|给|去|碰)|不给|不做|不提供|不搞|不用|无需|不必|不加)[^。\n]{0,24}$"""
    )

    /** 正文只看开头这么多字符判拒答（中段出现「不能」多半是正常叙述） */
    private const val OPENING_WINDOW = 240

    /** 没有推理链时把正文窗口放大，否则中后段的拒答会漏判 */
    private const val TEXT_FULL_WINDOW = 2000

    /** 推理链只看尾部 */
    private const val REASONING_WINDOW = 4000

    fun judge(watch: ArmorWatch): ArmorVerdict {
        val reasoningTail = if (watch.reasoning.length > REASONING_WINDOW) {
            watch.reasoning.substring(watch.reasoning.length - REASONING_WINDOW)
        } else {
            watch.reasoning.toString()
        }
        val hasReasoning = reasoningTail.isNotEmpty()
        val textAll = watch.text.toString()
        val textOpening = textAll.take(OPENING_WINDOW)
        val textScan = if (hasReasoning) textOpening else textAll.take(TEXT_FULL_WINDOW)

        val refusal = hits(textScan, refusalPatterns)
        if (refusal.isNotEmpty()) {
            watch.refusalHits = refusal
            return ArmorVerdict.Refusal
        }

        val reasoningRefusal = hits(reasoningTail, refusalPatterns)
        if (reasoningRefusal.isNotEmpty()) {
            watch.refusalHits = reasoningRefusal
            return ArmorVerdict.ReasoningRefusal
        }

        // 兜底接管：给了替代品 = 没交付，按 Refusal 处理（原插件只做 UI 显示，这里升级）
        val fallback = (hits(textScan, fallbackPatterns) + hits(reasoningTail, fallbackPatterns)).distinct()
        if (fallback.isNotEmpty()) {
            watch.refusalHits = fallback
            return ArmorVerdict.Refusal
        }

        val safety = (hits(reasoningTail, safetyPatterns) + hits(textOpening, safetyPatterns)).distinct()
        if (safety.isNotEmpty()) {
            watch.safetyHits = safety.take(6)
            return ArmorVerdict.SafetyHint
        }

        return ArmorVerdict.Clean
    }

    /**
     * 在文本里找命中，跳过落在否定辖域里的。
     *
     * 返回去重后的命中片段，最多 6 条 —— 只是给人看的摘要，不需要全量。
     */
    fun hits(text: String, patterns: List<Regex>): List<String> {
        if (text.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val start = match.range.first
                if (start > 0 && negationRe.containsMatchIn(text.substring(0, start))) continue
                found += match.value.trim()
                if (found.size >= 6) return found.toList()
            }
        }
        return found.toList()
    }

    /** 是否需要在下一步注入纠正指令。 */
    fun needsCorrection(verdict: ArmorVerdict): Boolean =
        verdict == ArmorVerdict.Refusal ||
            verdict == ArmorVerdict.ReasoningRefusal ||
            verdict == ArmorVerdict.SafetyHint
}
