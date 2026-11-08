package me.rerere.rikkahub.data.files

/**
 * 技能分类的展示顺序与中文名。
 *
 * 抽出来是因为有两处要用：助手的扩展页（只显示技能开关）
 * 和技能管理页（列表 + 详情）。各写一份的话，改一处忘另一处，
 * 两个页面的分组顺序就会不一样。
 *
 * 顺序的考虑：`original` 最前，用户最熟；`tool` 第二，因为装环境
 * 是所有人第一步；后面按攻击面递减排。
 */
val SKILL_CATEGORY_ORDER: List<Pair<String, String>> = listOf(
    "original" to "内置",
    "tool" to "工具链",
    "reverse" to "逆向",
    "crack" to "破解",
    "pentest" to "渗透",
    // 破甲不是「破解」——它是一份解除降级倾向的规程，
    // 跟具体攻哪个目标无关，所以单独一类，只有 milk-breaker 一个成员。
    "breaker" to "破甲",
    "game" to "游戏安全",
    "gray" to "敏感",
    "creative" to "创作",
    "other" to "其他",
)

/** 分类 key 转中文名。没登记的 key 原样返回——用户自建的分类不该被改名。 */
fun skillCategoryLabel(key: String): String =
    SKILL_CATEGORY_ORDER.firstOrNull { it.first == key }?.second ?: key

/**
 * 按分类顺序排好，未知分类接在最后并按字母序。
 *
 * 返回 (key, 该分类下的技能) 的有序列表，空分类已被过滤。
 */
fun groupSkillsByCategory(skills: List<SkillMetadata>): List<Pair<String, List<SkillMetadata>>> {
    val grouped = skills.groupBy { it.category?.lowercase()?.takeIf { c -> c.isNotBlank() } ?: "other" }
    val known = SKILL_CATEGORY_ORDER.map { it.first }.filter { it in grouped }
    val unknown = grouped.keys
        .filter { key -> SKILL_CATEGORY_ORDER.none { it.first == key } }
        .sorted()
    return (known + unknown).map { key ->
        key to grouped[key].orEmpty().sortedBy { it.title ?: it.name }
    }
}
