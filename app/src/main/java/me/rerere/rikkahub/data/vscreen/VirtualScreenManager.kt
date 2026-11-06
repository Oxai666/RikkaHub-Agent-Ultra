package me.rerere.rikkahub.data.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.root.RootShell
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "VirtualScreen"

/**
 * 虚拟屏幕的 App 侧控制。
 *
 * 只负责三件事：把 [VscreenCore] 进程拉起来、跟它说话、把它关掉。
 * 真正的 VirtualDisplay 在另一个进程里（见 VscreenCore 的注释：权限原因）。
 */
object VirtualScreenManager {

    data class State(
        val running: Boolean = false,
        val starting: Boolean = false,
        val port: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val mirror: Boolean = false,
        val displayId: Int = -1,
        val error: String? = null,
    )

    data class LaunchResult(val ok: Boolean, val message: String)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private const val CORE_CLASS = "me.rerere.rikkahub.data.vscreen.VscreenCore"

    /**
     * 启动虚拟屏。
     *
     * 命令走 setsid + 后台：`su -c` 的 shell 一退出，子进程会被一起回收，
     * setsid 让它脱离这个进程组；`&` 让 su 立刻返回，不去等一个常驻进程。
     */
    suspend fun start(
        context: Context,
        width: Int = 720,
        height: Int = 1280,
        dpi: Int = 320,
        mirror: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext true
        _state.value = State(starting = true)

        if (!RootShell.isAvailable()) {
            _state.value = State(error = "需要 root：虚拟屏要 CAPTURE_VIDEO_OUTPUT 权限，普通进程拿不到")
            return@withContext false
        }

        val apkPath = context.applicationInfo.sourceDir
        val port = (20000..40000).random()

        // 命令行里不带任何秘密；apk 路径与参数都可被 ps 看到，但这里没有敏感信息。
        val command = buildString {
            append("setsid app_process")
            append(" -Djava.class.path=").append(apkPath)
            append(" /system/bin ").append(CORE_CLASS)
            append(" --port ").append(port)
            append(" --width ").append(width)
            append(" --height ").append(height)
            append(" --dpi ").append(dpi)
            append(" --mirror ").append(mirror)
            append(" > /data/local/tmp/rikkahub-vscreen.log 2>&1 &")
        }

        val launched = RootShell.exec(command, timeoutMs = 15_000)
        if (!launched.ok) {
            // su 本身失败（没授权），这时候连 ping 都不用试
            _state.value = State(
                error = "启动失败（su 退出码 ${launched.exitCode}）：${launched.stderr.trim().ifBlank { launched.stdout.trim() }}"
            )
            return@withContext false
        }

        // 子进程在后台，端口什么时候可用不确定，轮询最多 10 秒
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (ping(port)) {
                // displayId 只有 helper 里拿得到，投应用要用它，所以启动成功就顺手问一次
                val displayId = runCatching { parseDisplayId(send(port, "info")) }.getOrDefault(-1)
                _state.value = State(
                    running = true,
                    port = port,
                    width = width,
                    height = height,
                    mirror = mirror,
                    displayId = displayId,
                )
                Log.i(TAG, "virtual screen ready on port $port (${width}x$height, mirror=$mirror, display=$displayId)")
                return@withContext true
            }
            Thread.sleep(250)
        }

        val log = runCatching {
            RootShell.exec("tail -n 20 /data/local/tmp/rikkahub-vscreen.log", 8_000).stdout.trim()
        }.getOrDefault("")
        _state.value = State(error = "helper 起来了但没响应。日志：\n${log.ifBlank { "（空）" }}")
        false
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val port = _state.value.port
        if (port != 0) {
            runCatching { send(port, "quit") }
        }
        // 兜底：万一 quit 没送到，按命令行特征杀掉
        runCatching { RootShell.exec("pkill -f $CORE_CLASS", 8_000) }
        _state.value = State()
    }

    /** 抓一帧。失败返回 null，原因写进 state.error。 */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        val port = _state.value.port
        if (!_state.value.running || port == 0) {
            _state.value = _state.value.copy(error = "虚拟屏没在运行")
            return@withContext null
        }
        val reply = runCatching { send(port, "shot") }.getOrElse { t ->
            _state.value = _state.value.copy(error = "连接失败：${t.message}")
            return@withContext null
        }
        when {
            reply.startsWith("OK ") -> {
                val parts = reply.split(" ", limit = 4)
                if (parts.size < 4) {
                    _state.value = _state.value.copy(error = "返回格式不对")
                    return@withContext null
                }
                val bytes = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull()
                    ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            reply.startsWith("ERR ") -> {
                _state.value = _state.value.copy(error = reply.removePrefix("ERR "))
                null
            }
            else -> {
                _state.value = _state.value.copy(error = "未知返回：$reply")
                null
            }
        }
    }

    private fun ping(port: Int): Boolean = runCatching { send(port, "ping") == "OK pong" }.getOrDefault(false)

    private fun parseDisplayId(reply: String): Int =
        Regex("\"display_id\"\\s*:\\s*(-?\\d+)").find(reply)
            ?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /**
     * 把一个应用投到虚拟屏上。
     *
     * `am start --display <id>` 需要 shell/root 身份（普通应用只能投自己），所以走 RootShell。
     * 不给 activity 时用 `cmd package resolve-activity` 找启动入口。
     */
    suspend fun launchApp(packageName: String, activity: String? = null): LaunchResult =
        withContext(Dispatchers.IO) {
            val state = _state.value
            if (!state.running) return@withContext LaunchResult(false, "虚拟屏没在运行")
            if (state.displayId < 0) return@withContext LaunchResult(false, "拿不到 displayId，重建一次虚拟屏试试")
            if (state.mirror) return@withContext LaunchResult(false, "镜像模式不能投应用，先关掉「镜像当前屏幕」")

            val component = activity?.let {
                if ('/' in it) it else "$packageName/$it"
            } ?: resolveLauncherActivity(packageName)
                ?: return@withContext LaunchResult(
                    false,
                    "找不到 $packageName 的启动 Activity。确认包名正确，或者显式传 activity。",
                )

            val result = RootShell.exec("am start --display ${state.displayId} -n $component", 20_000)
            if (result.ok) {
                LaunchResult(true, component)
            } else {
                LaunchResult(
                    false,
                    "am start 失败（退出码 ${result.exitCode}）：" +
                        result.stderr.trim().ifBlank { result.stdout.trim() },
                )
            }
        }

    /** 用命令行问系统要这个包的启动 Activity，例如 `com.android.settings/.Settings`。 */
    private suspend fun resolveLauncherActivity(packageName: String): String? {
        val result = RootShell.exec("cmd package resolve-activity --brief $packageName", 15_000)
        if (!result.ok) return null
        return result.stdout.trim()
            .lines()
            .lastOrNull { it.contains('/') }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun send(port: Int, command: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.soTimeout = 20_000
            val out = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            out.println(command)
            return input.readLine().orEmpty()
        }
    }
}
