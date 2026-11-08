package me.rerere.rikkahub.data.plan

/**
 * 从模型回复里抠出 `<proposed_plan>` 块。
 *
 * 标签规则跟提示词里写死的完全一致，改一边必须改另一边：
 *  - 开标签、闭标签各自独占一行
 *  - 标签原样保留，不翻译不改名
 *  - 每轮最多一个块，出现第二个就整个判废
 *
 * 判废的取舍：宁可返回 null 让 UI 什么都不显示，也不要把半截计划
 * 当成完整计划渲染出来——用户看到一份缺 Test Plan 的方案会更糟。
 */
object ProposedPlanParser {

    const val OPEN_TAG = "<proposed_plan>"
    const val CLOSE_TAG = "</proposed_plan>"

    data class Parsed(
        val title: String?,
        val markdown: String,
    )

    /**
     * 返回 null 表示这轮没有、或者有但格式不完整。
     */
    fun parse(text: String): Parsed? {
        val openCount = text.winCount(OPEN_TAG)
        val closeCount = text.winCount(CLOSE_TAG)
        // 每轮最多一个：出现两对就判废
        if (openCount != 1 || closeCount != 1) return null

        val openIdx = text.indexOf(OPEN_TAG)
        val closeIdx = text.indexOf(CLOSE_TAG)
        if (closeIdx <= openIdx) return null

        // 标签必须独占行：开标签前只能是行首或换行，后面必须紧跟换行
        if (!isOwnLine(text, openIdx, OPEN_TAG.length)) return null
        if (!isOwnLine(text, closeIdx, CLOSE_TAG.length)) return null

        val body = text.substring(openIdx + OPEN_TAG.length, closeIdx).trim('\n', '\r', ' ')
        if (body.isBlank()) return null

        val title = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.ifBlank { null }

        return Parsed(title = title, markdown = body)
    }

    /** 这个位置上的标签是不是独占一行。 */
    private fun isOwnLine(text: String, index: Int, tagLength: Int): Boolean {
        // 前面：行首，或者前一个字符是换行（允许行尾有空白）
        var i = index - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i--
        if (i >= 0 && text[i] != '\n') return false

        // 后面：行尾，或者后一个字符是换行（允许行首有空白）
        var j = index + tagLength
        while (j < text.length && (text[j] == ' ' || text[j] == '\t' || text[j] == '\r')) j++
        return j >= text.length || text[j] == '\n'
    }

    private fun String.winCount(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) break
            count++
            from = at + needle.length
        }
        return count
    }
}
