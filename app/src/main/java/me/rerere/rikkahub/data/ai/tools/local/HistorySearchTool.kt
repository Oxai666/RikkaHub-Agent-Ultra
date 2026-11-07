package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.compaction.CompactionArchive

/**
 * 历史存档检索。
 *
 * 对话被压缩之后，模型不记得细节了。压缩前我们已经把完整对话存成了
 * `压缩会话N <时间>.md`，这个工具负责把它们翻出来。
 *
 * **必须先问用户**：同一会话可能有压缩会话1、压缩会话2、压缩会话3……
 * 直接全量翻等于把一堆旧上下文重新灌回来。所以流程固定是：
 *   1. action=list 看清有哪些存档
 *   2. 问用户要解锁哪一份
 *   3. action=read 或 action=search 并带上 index
 * 没带 index 的 search 会被直接拒绝，把这句话原样回给模型。
 */
fun historySearchTool(context: Context): Tool = Tool(
    name = "history_search",
    description = """
        Search archived transcripts saved before context compaction. Each archive holds ONLY
        the messages added since the previous compaction (压缩会话2 contains just the new turns,
        not 1 + 2 again), so read several archives if the user asks about a long history.
        Workflow is mandatory: call action=list first, then ASK THE USER which archive to unlock
        (压缩会话1 / 压缩会话2 / ...), and only then call read or search with that index.
        A search without index is rejected on purpose — never scan every archive at once.
        When reading results, focus on the reasoning and the answers; images and attachments
        appear as [图片] / [文件] placeholders and carry no information.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: list / read / search")
                })
                put("index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Which archive to unlock (1 = 压缩会话1, 2 = 压缩会话2, …). Required for read and search.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword to look for. Required for search.")
                })
                put("assistant", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: limit to one assistant name.")
                })
                put("conversation", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: limit to one conversation title.")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val index = input.jsonObject["index"]?.jsonPrimitive?.intOrNull
        val keyword = input.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assistant = input.jsonObject["assistant"]?.jsonPrimitive?.contentOrNull
        val conversation = input.jsonObject["conversation"]?.jsonPrimitive?.contentOrNull

        when (action) {
            "list" -> {
                val entries = CompactionArchive.list(context, assistant, conversation)
                val payload = buildJsonObject {
                    put("count", entries.size)
                    put("archives", buildJsonArray {
                        entries.take(50).forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("index", entry.index)
                                    put("assistant", entry.assistantName)
                                    put("conversation", entry.conversationTitle)
                                    put("file", entry.file.name)
                                    put("bytes", entry.file.length())
                                }
                            )
                        }
                    })
                    if (entries.isEmpty()) {
                        put("note", "还没有任何压缩存档（可能从没触发过压缩）")
                    } else {
                        put(
                            "next_step",
                            "把上面的清单给用户看，问清楚要解锁哪一份（压缩会话1 / 压缩会话2 …），" +
                                "拿到明确答复后再用 action=read 或 action=search 并带上 index。"
                        )
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "read" -> {
                if (index == null) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "missing_index")
                                put(
                                    "recovery",
                                    "先用 action=list 列出存档，然后问用户要解锁哪一份，再带上 index 重试。"
                                )
                            }.toString()
                        )
                    )
                }
                val entries = CompactionArchive.list(context, assistant, conversation)
                val entry = entries.firstOrNull { it.index == index }
                if (entry == null) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "archive_not_found")
                                put("index", index)
                                put("hint", "用 action=list 看看现有存档的序号")
                            }.toString()
                        )
                    )
                }
                val text = runCatching { entry.file.readText(Charsets.UTF_8) }
                    .getOrElse { return@Tool listOf(UIMessagePart.Text(
                        buildJsonObject { put("error", "read_failed: ${it.message}") }.toString()
                    )) }
                // 存档可能很长，截断保护上下文
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("index", entry.index)
                            put("assistant", entry.assistantName)
                            put("conversation", entry.conversationTitle)
                            put("truncated", text.length > 60_000)
                            put("content", text.take(60_000))
                        }.toString()
                    )
                )
            }

            "search" -> {
                if (index == null) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "missing_index")
                                put(
                                    "recovery",
                                    "检索必须指定要解锁哪一份存档。先 action=list，然后问用户要 压缩会话1 还是 压缩会话2，再带上 index 重试。"
                                )
                            }.toString()
                        )
                    )
                }
                if (keyword.isBlank()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject { put("error", "keyword is required for search") }.toString()
                        )
                    )
                }
                val entries = CompactionArchive.list(context, assistant, conversation)
                    .filter { it.index == index }
                if (entries.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject { put("error", "archive_not_found"); put("index", index) }.toString()
                        )
                    )
                }
                val hits = CompactionArchive.search(
                    context = context,
                    keyword = keyword,
                    limit = 20,
                    assistantName = assistant,
                    conversationTitle = conversation,
                ).filter { it.entry.index == index }

                val payload = buildJsonObject {
                    put("index", index)
                    put("keyword", keyword)
                    put("hits", hits.size)
                    put("results", buildJsonArray {
                        hits.forEach { hit ->
                            add(
                                buildJsonObject {
                                    put("assistant", hit.entry.assistantName)
                                    put("conversation", hit.entry.conversationTitle)
                                    put("line", hit.line)
                                }
                            )
                        }
                    })
                    if (hits.isEmpty()) put("note", "这份存档里没有匹配的关键词")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "action 必须是 list / read / search") }.toString()
                )
            )
        }
    }
)
