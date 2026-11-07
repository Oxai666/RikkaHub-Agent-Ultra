package me.rerere.rikkahub.data.root

import android.content.Context
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.shizuku.ShizukuManager

/**
 * 特权 shell 统一入口。
 *
 * 虚拟屏需要 CAPTURE_VIDEO_OUTPUT / INJECT_EVENTS 这类 system 级权限，
 * 普通应用进程拿不到。能提供这个身份的有两条路：
 *
 *  1. root（su）—— 权限最高
 *  2. Shizuku —— 本质就是 ADB shell（uid 2000），同样是特权身份
 *
 * 之前只走 root，没 root 的手机就用不了。现在有 Shizuku 就用 Shizuku，
 * 相当于把门槛从「必须 root」降到「装个 Shizuku 并授权」。
 *
 * 调用方拿到的结果格式统一，不用关心底下是哪个后端。
 */
object PrivilegedShell {

    enum class Backend { ROOT, SHIZUKU, NONE }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val ok: Boolean,
        val backend: Backend = Backend.NONE,
    )

    suspend fun backend(): Backend = when {
        RootShell.isAvailable() -> Backend.ROOT
        ShizukuManager.isBinderAlive() -> Backend.SHIZUKU
        else -> Backend.NONE
    }

    /** 给状态显示用的一句话。 */
    suspend fun describe(): String = when (backend()) {
        Backend.ROOT -> "root"
        Backend.SHIZUKU -> "Shizuku (ADB shell)"
        Backend.NONE -> "无（root 和 Shizuku 都不可用）"
    }

    suspend fun exec(
        context: Context,
        command: String,
        timeoutMs: Long = 30_000,
    ): Result {
        // 1. root 优先
        if (RootShell.isAvailable()) {
            val result = RootShell.exec(command, timeoutMs)
            return Result(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                ok = result.ok,
                backend = Backend.ROOT,
            )
        }

        // 2. 退回 Shizuku（ADB shell 身份）
        if (ShizukuManager.isBinderAlive()) {
            val json = runCatching {
                ShizukuManager.exec(context, command, timeoutMs.toInt().coerceIn(1_000, 600_000))
            }.getOrElse { t ->
                return Result(-1, "", "shizuku exec failed: ${t.message}", false, Backend.SHIZUKU)
            }
            val obj = json.jsonObject
            val ok = obj["success"]?.jsonPrimitive?.booleanOrNull == true
            return Result(
                exitCode = obj["exit_code"]?.jsonPrimitive?.intOrNull ?: -1,
                stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ok = ok,
                backend = Backend.SHIZUKU,
            )
        }

        return Result(
            exitCode = -1,
            stdout = "",
            stderr = "没有可用的特权通道：root 不可用，Shizuku 也没运行/没授权",
            ok = false,
            backend = Backend.NONE,
        )
    }
}
