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

    enum class Backend { ROOT, ADB, SHIZUKU, NONE }

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val ok: Boolean,
        val backend: Backend = Backend.NONE,
    )

    // ---- ADB 通道 ---------------------------------------------------------
    //
    // 走真 ADB（连 adbd）而不是 Shizuku，是为了绕开 Android 的 per-app
    // network namespace 隔离：Shizuku 起的 helper 在另一个 netns 里，
    // App 连 127.0.0.1 连不过去；ADB 走真实网络栈就没这个问题。

    private const val PREFS = "rikkahub.adb"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    @Volatile
    private var adbClient: AdbClient? = null

    fun configureAdb(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_PORT, port)
            .apply()
        adbClient?.close()
        adbClient = null
    }

    fun adbEndpoint(context: Context): Pair<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOST, "").orEmpty() to prefs.getInt(KEY_PORT, 5555)
    }

    /** 尝试连一次 adbd，成功后复用这条连接。 */
    suspend fun connectAdb(context: Context): Result<String> {
        val (host, port) = adbEndpoint(context)
        if (host.isBlank()) return Result.failure(IllegalStateException("还没配置 ADB 地址"))
        return runCatching {
            adbClient?.close()
            val client = AdbClient(host, port, AdbKeys.load(context))
            client.connect()
            adbClient = client
            val probe = client.shell("id -u", 10_000)
            if (!probe.ok) error("连上了但执行失败：${probe.stdout}${probe.stderr}")
            "已连接 $host:$port（远端 uid=${probe.stdout.trim()}）"
        }
    }

    private fun adbConnected(): Boolean = adbClient != null

    suspend fun backend(context: Context? = null): Backend = when {
        RootShell.isAvailable() -> Backend.ROOT
        adbConnected() -> Backend.ADB
        ShizukuManager.isBinderAlive() -> Backend.SHIZUKU
        else -> Backend.NONE
    }

    /** 给状态显示用的一句话。 */
    suspend fun describe(context: Context? = null): String = when (backend(context)) {
        Backend.ROOT -> "root"
        Backend.ADB -> "ADB 直连"
        Backend.SHIZUKU -> "Shizuku (ADB shell)"
        Backend.NONE -> "无（root / ADB / Shizuku 都不可用）"
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

        // 2. ADB 直连（跨 netns 可靠）
        adbClient?.let { client ->
            val result = runCatching { client.shell(command, timeoutMs) }
            if (result.isSuccess) {
                val r = result.getOrThrow()
                return Result(r.exitCode, r.stdout, r.stderr, r.ok, Backend.ADB)
            }
            // 连接断了就丢掉，下一次重新连
            adbClient?.close()
            adbClient = null
        }

        // 3. 退回 Shizuku（ADB shell 身份）
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
