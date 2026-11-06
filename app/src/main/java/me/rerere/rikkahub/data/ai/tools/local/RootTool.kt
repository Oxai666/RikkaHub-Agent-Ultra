package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.root.RootShell

/**
 * 以 root 身份跑 shell 命令。
 *
 * 和 `shizuku_exec` 的区别：那个拿到的是 shell UID（等同 `adb shell`，不是 root），
 * 这个直接走 `su -c`，能碰系统分区、能改别的应用数据、能读写 /data。
 * 也就意味着它比 Shizuku 危险一档，所以：
 *
 *  - 默认在 [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.ALWAYS_ASK] 里，每次调用都要批
 *  - `command` 参数过 [me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard]，
 *    `rm -rf /` / `mkfs` / fork bomb 这类即使「永远允许」也拦
 *  - 只有助手级开关 [me.rerere.rikkahub.data.ai.tools.LocalToolOption.Root] 打开时才注册
 *
 * su 不存在时不抛异常，返回结构化错误，让模型知道是环境问题而不是命令写错。
 */
fun rootShellTool(): Tool = Tool(
    name = "root_shell",
    description = """
        Run a shell command as root via su. Returns exit_code, timed_out, stdout and stderr.
        Use it for things a normal app or the shell user cannot do: writing /system or /data,
        inspecting other packages' private data, changing kernel parameters, controlling services.
        Requires the device to be rooted (Magisk / KernelSU / APatch) and the assistant's
        Root tool toggle to be on. Prefer shizuku_exec when shell UID is enough — root is a
        bigger hammer than most tasks need.
        Note: on Android 14+, `dumpsys activity activities` no longer prints `mResumedActivity`;
        grep for `topResumedActivity` instead.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run as root, e.g. 'id' or 'ls /data/data'")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Timeout in milliseconds. Default ${RootShell.defaultTimeoutMs}, " +
                            "max ${RootShell.maxTimeoutMs}."
                    )
                })
            },
            required = listOf("command")
        )
    },
    execute = { input ->
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command is required") }.toString()
                )
            )
        }
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull
            ?: RootShell.defaultTimeoutMs.toInt()).toLong()
            .coerceIn(RootShell.minTimeoutMs, RootShell.maxTimeoutMs)

        val result = RootShell.exec(command, timeoutMs)
        val payload = buildJsonObject {
            put("exit_code", result.exitCode)
            put("timed_out", result.timedOut)
            put("ok", result.ok)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
