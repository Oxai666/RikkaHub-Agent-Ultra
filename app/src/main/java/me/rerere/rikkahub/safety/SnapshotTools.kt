package me.rerere.rikkahub.safety

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 快照的查看与恢复。
 *
 * 为什么要有这两个工具：快照存在 App 私有目录
 * （`files/safety-snapshots/`），用户从文件管理器看不到，
 * 主人问「到底拍了没」的时候没有任何办法验证。
 *
 * 有了工具之后，跟模型说一句「列一下最近的快照」就能确认，
 * 而且真删错了也能让它恢复。
 */
fun createSnapshotTools(context: Context, workspaceId: String?): List<Tool> = listOf(
    snapshotListTool(context, workspaceId),
    snapshotRestoreTool(context, workspaceId),
)

private fun snapshotListTool(context: Context, workspaceId: String?): Tool = Tool(
    name = "snapshot_list",
    description = """
        List file snapshots taken before irreversible operations on the bound workspace.
        Call this when: (a) the user asks whether something was backed up, (b) a destructive
        operation went wrong and you need to pick the snapshot to restore, or (c) you are
        about to retry a destructive command and want to know whether the previous attempt
        already captured the target files.
        Returns id, taken_at_ms, the tool that triggered it, captured_files, skipped_files,
        total_bytes, and the file paths inside each snapshot.
        Snapshots live under /workspace/.snapshots/ — you can also `ls` that path with
        workspace_shell instead of calling this tool.
        This tool only exists when the assistant is bound to a workspace; without one there
        is nothing to snapshot and the tool is not registered at all.
    """.trimIndent().replace("\n", " "),
    parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
    execute = { _ ->
        val root = SnapshotRoots.of(context, workspaceId)
        val records = FileSnapshotManager.list(root)
        val payload = buildJsonObject {
            put("ok", true)
            put("count", records.size)
            put("snapshots", buildJsonArray {
                records.take(20).forEach { record ->
                    add(
                        buildJsonObject {
                            put("id", record.id)
                            put("taken_at_ms", record.createdAtMs)
                            put("tool", record.toolName)
                            val captured = record.entries.count { it.existed && it.snapshotPath.isNotEmpty() }
                            val skipped = record.entries.count { it.skipped != null }
                            put("captured_files", captured)
                            put("skipped_files", skipped)
                            put("total_bytes", record.entries.sumOf { it.bytes })
                        }
                    )
                }
            })
            if (records.isEmpty()) {
                put(
                    "note",
                    "还没有任何快照。快照只在「不可逆操作」通过审批时才会拍——" +
                        "普通的写文件不触发，只读操作也不触发。" +
                        "快照目录在 /workspace/.snapshots/，可以用 workspace_shell 直接 ls 它。" +
                        "如果这次本来就不该触发（比如只是 cat 一个文件），列表为空是正常的。"
                )
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun snapshotRestoreTool(context: Context, workspaceId: String?): Tool = Tool(
    name = "snapshot_restore",
    description = """
        Restore files from a snapshot, returning the workspace to its state right before the
        recorded operation. Files that existed at capture time are copied back over the current
        version; files that did not exist at capture time are deleted.
        Only call this when the user explicitly asks to undo. It overwrites whatever is there
        now, so get the snapshot id from snapshot_list first and confirm it is the right one
        (check taken_at_ms and the file paths, not just the timestamp).
        Returns ok, restored count, deleted count, and the list of failures.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Snapshot id from snapshot_list.")
                })
            },
            required = listOf("id"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (id.isBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("ok", false); put("error", "id is required") }.toString()
                )
            )
        }
        val root = SnapshotRoots.of(context, workspaceId)
        val record = FileSnapshotManager.recordById(root, id)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "NOT_FOUND")
                        put("hint", "先调 snapshot_list 看看有哪些")
                    }.toString()
                )
            )
        val result = FileSnapshotManager.restore(
            root = root,
            record = record,
            resolveTarget = { sandboxPath ->
                SnapshotRoots.resolveTarget(context, workspaceId, sandboxPath)
            },
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", result.ok)
                    put("restored", result.restored)
                    put("deleted", result.deleted)
                    put("failed_count", result.failed.size)
                    if (result.failed.isNotEmpty()) {
                        put("failed", buildJsonArray {
                            result.failed.take(10).forEach {
                                add(kotlinx.serialization.json.JsonPrimitive(it))
                            }
                        })
                    }
                }.toString()
            )
        )
    },
)
