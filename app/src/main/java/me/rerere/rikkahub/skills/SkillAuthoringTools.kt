package me.rerere.rikkahub.skills

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SKILL_CATEGORY_ORDER
import me.rerere.rikkahub.data.files.SkillManager

/**
 * 技能创作工具：AI 自己写技能、改技能、删技能。
 *
 * 为什么要让模型能写：一次成功的逆向或渗透流程，里面有大量调试出来的
 * 命令序列、踩过的坑、判断依据。这些不沉淀下来，下次遇到同类目标
 * 还得从头摸一遍。
 *
 * 安全边界：
 *  - `skill_list` 只读，直接放行
 *  - 另外三个都改磁盘，全部进审批（见 ToolApprovalDefaults）
 *  - `skill_create` 不能覆盖同名技能，要覆盖得显式走 `skill_update`
 */
/**
 * 只读的技能列表。始终可用——模型得先能看见有哪些技能、有哪些分类，
 * 才谈得上用或改。不需要用户先去设置里开开关。
 */
fun createSkillReadTools(skillManager: SkillManager): List<Tool> = listOf(
    skillListTool(skillManager),
)

/**
 * 写技能：创建、修改、删除。
 *
 * 挂在 `LocalToolOption.SkillAuthoring` 开关下面——这些会改磁盘，
 * 默认不开，用户明确要「让 AI 帮我沉淀技能」时才打开。
 */
fun createSkillWriteTools(skillManager: SkillManager): List<Tool> = listOf(
    skillCreateTool(skillManager),
    skillUpdateTool(skillManager),
    skillDeleteTool(skillManager),
)

/** 读 + 写一起给。调用方不方便分开时用这个。 */
fun createSkillAuthoringTools(skillManager: SkillManager): List<Tool> =
    createSkillReadTools(skillManager) + createSkillWriteTools(skillManager)


/**
 * 把一段文本包成 YAML 双引号字符串。
 *
 * 单独抽出来是因为不能直接写 `"title: \"${x.replace(\"\", ...)}\""`——
 * Kotlin 的字符串模板 ${...} 里不允许再出现同种引号，那样写编译不过。
 */
private fun yamlQuoted(value: String): String =
    "\"" + value.replace("\"", "\\\"") + "\""

/** 技能标识符允许的字符。跟目录名一致，中文标点和空格会让路径解析出问题。 */
private val NAME_PATTERN = Regex("""^[a-z0-9][a-z0-9_\-]{0,63}$""")

private fun err(code: String, vararg extra: Pair<String, String>): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", false)
                put("error", code)
                extra.forEach { (k, v) -> put(k, v) }
            }.toString()
        )
    )

// ── 列出 ──

private fun skillListTool(skillManager: SkillManager): Tool = Tool(
    name = "skill_list",
    description = """
        List the skills currently installed on this device, with their Chinese title,
        category and description. Use it before creating or editing a skill so you know
        which categories already exist and which names are taken.
        Pass `category` to filter to one category.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: only return skills in this category.")
                })
            },
            required = emptyList(),
        )
    },
    execute = { input ->
        val filter = input.jsonObject["category"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
        val all = skillManager.listSkills()
        val shown = if (filter == null) all else all.filter { it.category.equals(filter, true) }
        val payload = buildJsonObject {
            put("ok", true)
            put("total", shown.size)
            put("categories", buildJsonArray {
                // 已有分类按固定顺序给出来，加上用户自建的
                val fromSkills = all.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
                val registry = skillManager.knownCategories()
                (registry + fromSkills).distinct()
                    .sortedWith(compareBy(
                        { key -> SKILL_CATEGORY_ORDER.indexOfFirst { it.first == key }.let { if (it < 0) Int.MAX_VALUE else it } },
                        { it },
                    ))
                    .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("skills", buildJsonArray {
                shown.forEach { s ->
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("title", s.title ?: "")
                            put("category", s.category ?: "")
                            put("description", s.description)
                        }
                    )
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

// ── 创建 ──

private fun skillCreateTool(skillManager: SkillManager): Tool = Tool(
    name = "skill_create",
    description = """
        Create a new skill and write it to the local skill library.
        Use this AFTER a task succeeded and the user agreed to keep it as a reusable skill
        — a reverse-engineering workflow, a pentest playbook, a debugging sequence, etc.
        The skill body should capture the ACTUAL steps that worked: exact commands,
        what each step told you, pitfalls you hit, and how you decided what to do next.
        Do not invent generic advice — write what you actually ran.
        `name` is the English identifier (lowercase letters, digits, dash, underscore);
        `title` is the human-readable name shown in the UI.
        `category` may be a new name or an existing one (call skill_list first).
        Fails if a skill with the same name already exists.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "English identifier, e.g. apk-signature-bypass. Lowercase, digits, - and _ only.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Human-readable name shown in the UI, e.g. APK 签名校验绕过流程")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Category name. Existing ones are preferred; a new name creates a new category.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "One sentence telling the model WHEN to use this skill. This drives auto-trigger.")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill body in Markdown. Put the real commands and decision points here.")
                })
            },
            required = listOf("name", "title", "category", "description", "content"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val category = obj["category"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

        if (!NAME_PATTERN.matches(name)) {
            return@Tool err(
                "INVALID_NAME",
                "hint" to "只能用小写字母、数字、- 和 _，以字母或数字开头，64 字符以内"
            )
        }
        if (title.isBlank()) return@Tool err("TITLE_REQUIRED")
        if (description.isBlank()) return@Tool err("DESCRIPTION_REQUIRED", "hint" to "这句话决定什么时候自动触发这个技能")
        if (content.isBlank()) return@Tool err("CONTENT_REQUIRED")

        if (skillManager.listSkills().any { it.name == name }) {
            return@Tool err("NAME_TAKEN", "hint" to "重名了，用 skill_update 改已有的，或者换个 name")
        }

        // 自己拼 frontmatter 而不是让模型拼：模型写 YAML 的时候经常把
        // description 里的冒号或引号写坏，整个技能就解析不了了。
        val markdown = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("title: ${yamlQuoted(title)}")
            appendLine("category: ${yamlQuoted(category)}")
            appendLine("description: ${yamlQuoted(description)}")
            appendLine("---")
            appendLine()
            append(content.trim())
        }

        val created = skillManager.saveSkill(name, markdown)
        if (created == null) {
            return@Tool err("SAVE_FAILED")
        }
        if (category.isNotBlank()) skillManager.rememberCategory(category)

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("name", name)
                    put("title", title)
                    put("category", category)
                    put("hint", "技能已写入。用户可以在「技能」页看到它，默认未启用。")
                }.toString()
            )
        )
    },
)

// ── 修改 ──

private fun skillUpdateTool(skillManager: SkillManager): Tool = Tool(
    name = "skill_update",
    description = """
        Update an existing skill. Only the fields you pass are changed; omit the rest.
        Use it to fix a step that turned out wrong, add a pitfall you just hit, or
        rename/re-categorise a skill you or the user created earlier.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Identifier of the skill to update.")
                })
                put("title", buildJsonObject { put("type", "string") })
                put("category", buildJsonObject { put("type", "string") })
                put("description", buildJsonObject { put("type", "string") })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement Markdown body (frontmatter is regenerated).")
                })
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val existing = skillManager.listSkills().firstOrNull { it.name == name }
            ?: return@Tool err("NOT_FOUND", "hint" to "先 skill_list 看看有哪些")

        val newTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing.title.orEmpty()
        val newCategory = obj["category"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing.category.orEmpty()
        val newDescription = obj["description"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing.description
        val newContent = obj["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        if (newContent != null) {
            val markdown = buildString {
                appendLine("---")
                appendLine("name: $name")
                if (newTitle.isNotBlank()) appendLine("title: ${yamlQuoted(newTitle)}")
                if (newCategory.isNotBlank()) appendLine("category: ${yamlQuoted(newCategory)}")
                appendLine("description: ${yamlQuoted(newDescription)}")
                appendLine("---")
                appendLine()
                append(newContent.trim())
            }
            if (!skillManager.saveSkillFile(name, "SKILL.md", markdown)) {
                return@Tool err("SAVE_FAILED")
            }
        } else {
            val ok = skillManager.updateSkillMeta(
                skillName = name,
                title = newTitle.ifBlank { null },
                category = newCategory.ifBlank { null },
            )
            if (!ok) return@Tool err("SAVE_FAILED")
        }

        if (newCategory.isNotBlank()) skillManager.rememberCategory(newCategory)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("name", name)
                }.toString()
            )
        )
    },
)

// ── 删除 ──

private fun skillDeleteTool(skillManager: SkillManager): Tool = Tool(
    name = "skill_delete",
    description = """
        Delete a skill from the local library. Only use this when the user explicitly
        asks to remove it — never clean up on your own.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (name.isBlank()) return@Tool err("NAME_REQUIRED")
        if (skillManager.listSkills().none { it.name == name }) {
            return@Tool err("NOT_FOUND")
        }
        val ok = skillManager.deleteSkill(name)
        listOf(
            UIMessagePart.Text(
                buildJsonObject { put("ok", ok); put("name", name) }.toString()
            )
        )
    },
)
