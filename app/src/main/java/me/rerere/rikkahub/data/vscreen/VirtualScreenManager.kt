package me.rerere.rikkahub.data.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    /** 自愈要用：重建 display 需要 Context。start 时记下来。 */
    @Volatile
    private var appContext: Context? = null

    /** 连续多少次抓不到帧。到阈值就重建整块 display。 */
    private var consecutiveFrameFailures = 0

    private const val CORE_CLASS = "me.rerere.rikkahub.data.vscreen.VscreenCore"

    // 状态持久化：App 进程被系统回收后，helper 还在跑，得能把它认回来
    private const val PREFS = "rikkahub.vscreen"
    private const val KEY_PORT = "port"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_DISPLAY = "display_id"
    private const val KEY_WAS_RUNNING = "was_running"
    private const val KEY_PACKAGE = "launched_package"
    private const val KEY_ACTIVITY = "launched_activity"

    private fun persist(context: Context, state: State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WAS_RUNNING, true)
            .putInt(KEY_PORT, state.port)
            .putInt(KEY_WIDTH, state.width)
            .putInt(KEY_HEIGHT, state.height)
            .putBoolean(KEY_MIRROR, state.mirror)
            .putInt(KEY_DISPLAY, state.displayId)
            .apply()
    }

    /** 记住最后一次投送的应用，下次恢复时自动把它放回去。 */
    private fun persistLaunch(context: Context, packageName: String, activity: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_ACTIVITY, activity)
            .apply()
    }

    private fun clearPersisted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 恢复到上次的现场。
     *
     * 三种情况：
     *  1. 进程还在（ping 通）→ 恢复 state，然后把上次投的应用重新放上去
     *  2. 进程没了但之前是运行状态 → 按记录重启 helper，再重新投送
     *  3. 本来就没开过 → 什么都不做
     *
     * 第 2 种是这次补的：之前只恢复状态、不重启进程，所以退出重进永远是空的。
     */
    suspend fun restore(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.running) return@withContext true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WAS_RUNNING, false)) return@withContext false

        val width = prefs.getInt(KEY_WIDTH, 720)
        val height = prefs.getInt(KEY_HEIGHT, 1280)
        val mirror = prefs.getBoolean(KEY_MIRROR, false)
        val port = prefs.getInt(KEY_PORT, 0)

        if (port != 0 && ping(port)) {
            val displayId = runCatching { parseDisplayId(send(port, "info")) }.getOrDefault(-1)
            _state.value = State(
                running = true,
                port = port,
                width = width,
                height = height,
                mirror = mirror,
                displayId = displayId,
            )
            Log.i(TAG, "restored live virtual screen on port $port (display=$displayId)")
            relaunchLast(context)
            return@withContext true
        }

        // 进程没了，按记录重新拉起来
        Log.i(TAG, "helper gone, restarting with ${width}x$height mirror=$mirror")
        val ok = start(context, width, height, mirror = mirror)
        if (!ok) {
            clearPersisted(context)
            return@withContext false
        }
        relaunchLast(context)
        true
    }

    /** 把上次投送的应用重新放到虚拟屏上；没记录就跳过。 */
    private suspend fun relaunchLast(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return
        val activity = prefs.getString(KEY_ACTIVITY, null)
        val result = launchApp(context, packageName, activity)
        Log.i(TAG, "relaunch last app $packageName -> ${result.ok} ${result.message}")
    }

    /** helper 进程还活着吗（跟 socket 是否可用分开看）。 */
    suspend fun isProcessAlive(): Boolean = withContext(Dispatchers.IO) {
        val result = RootShell.exec("pidof $CORE_CLASS || pgrep -f $CORE_CLASS", 5_000)
        result.stdout.isNotBlank()
    }

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
        appContext = context.applicationContext
        _state.value = State(starting = true)

        if (!RootShell.isAvailable()) {
            _state.value = State(error = "需要 root：虚拟屏要 CAPTURE_VIDEO_OUTPUT 权限，普通进程拿不到")
            return@withContext false
        }

        val apkPath = context.applicationInfo.sourceDir
        val port = (20000..40000).random()

        // 命令行里不带任何秘密；apk 路径与参数都可被 ps 看到，但这里没有敏感信息。
        //
        // nohup + setsid 两层：
        //   setsid  脱离 su shell 的进程组 —— 否则 su -c 一退出，子进程被一起回收
        //   nohup   忽略 SIGHUP
        //   < /dev/null  断开 stdin，进程不会等着终端输入
        // 三者缺一个，切走 App 或者 su 那个 shell 退出时就可能把 helper 带走。
        val command = buildString {
            append("nohup setsid app_process")
            append(" -Djava.class.path=").append(apkPath)
            append(" /system/bin ").append(CORE_CLASS)
            append(" --port ").append(port)
            append(" --width ").append(width)
            append(" --height ").append(height)
            append(" --dpi ").append(dpi)
            append(" --mirror ").append(mirror)
            append(" < /dev/null > /data/local/tmp/rikkahub-vscreen.log 2>&1 &")
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
                // 记下来：App 进程被系统回收之后靠它把状态认回来
                persist(
                    context,
                    State(
                        running = true,
                        port = port,
                        width = width,
                        height = height,
                        mirror = mirror,
                        displayId = displayId,
                    ),
                )
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

    suspend fun stop(context: Context? = null) = withContext(Dispatchers.IO) {
        val port = _state.value.port
        // 先清状态：UI 立刻停止拉流，不再占着那条长连接
        _state.value = State()
        context?.let { clearPersisted(it) }
        if (port != 0) {
            // 短超时：quit 能送到最好，送不到也别在这里卡住用户
            runCatching { send(port, "quit", timeoutMs = 1_500) }
        }
        // 兜底：按命令行特征杀掉 helper。quit 走不通时（比如服务端被占住）靠这个收尾
        runCatching { RootShell.exec("pkill -f $CORE_CLASS", 8_000) }
    }

    /**
     * 诊断：问 helper 这块 display 的状态。
     *
     * `screencap -d` 和 ImageReader 同时拿不到帧时先看这里：
     *  - state 不是 2（STATE_ON）→ display 没被打开
     *  - surface_valid=false → SurfaceFlinger 已经不往这个 surface 合成了
     */
    suspend fun diagnose(): String? = withContext(Dispatchers.IO) {
        val port = _state.value.port
        if (!_state.value.running || port == 0) return@withContext null
        runCatching { send(port, "diag") }.getOrNull()
    }

    /**
     * 抓一帧。连续 3 次拿不到帧就整块重建。
     *
     * 为什么要自愈：实测从别的页面返回之后，这块 display 的合成会停掉，
     * 之后无论 screenshot 还是 screencap -d 都拿不到画面，只有重建才恢复。
     * 与其让用户手动停掉重开，不如在这里自己处理。
     */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = captureOnce()
        if (bitmap == null) {
            // 只有「跑着但拿不到帧」才算失败，没运行的情况不走自愈
            if (_state.value.running) {
                consecutiveFrameFailures++
                if (consecutiveFrameFailures >= 3) selfHeal()
            }
        } else {
            consecutiveFrameFailures = 0
        }
        bitmap
    }

    private suspend fun selfHeal() = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        consecutiveFrameFailures = 0
        val state = _state.value
        if (!state.running) return@withContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null)
        val act = prefs.getString(KEY_ACTIVITY, null)
        Log.w(TAG, "no frame 3x in a row on display ${state.displayId}, rebuilding")
        stop(ctx)
        if (start(ctx, state.width, state.height, mirror = state.mirror) && pkg != null) {
            val result = launchApp(ctx, pkg, act)
            Log.i(TAG, "after rebuild, relaunch $pkg -> ${result.ok}")
        }
    }

    private suspend fun captureOnce(): Bitmap? = withContext(Dispatchers.IO) {
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

    /**
     * 轻点。坐标是虚拟屏像素。
     *
     * 走 `input -d <displayId> tap x y` 这条命令，不用 InputManager 反射：
     * Android 15 对 hidden API 的反射拦截很严，即便进程是 root，
     * `injectInputEvent` 也可能在 invoke 阶段被拒（抛 InvocationTargetException）。
     * `input` 命令是 shell 自己的工具，带 `-d` 指定 display，绕开整条 hidden API 路径。
     */
    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        val state = _state.value
        if (!state.running || state.displayId < 0) return@withContext false

        val shell = RootShell.exec(
            "input -d ${state.displayId} tap ${x.toInt()} ${y.toInt()}",
            10_000,
        )
        if (shell.ok) return@withContext true
        val shellReason = shell.stderr.trim().ifBlank { shell.stdout.trim() }
            .ifBlank { "退出码 ${shell.exitCode}" }

        // 回退：有些 ROM 裁剪了 input 的 -d 参数，这时走 helper 里的反射注入
        val fallback = runCatching { send(state.port, "tap ${x.toInt()} ${y.toInt()}") }.getOrNull()
        if (fallback != null && fallback.startsWith("OK")) return@withContext true

        val fallbackReason = fallback?.removePrefix("ERR ") ?: "helper 无响应"
        _state.value = _state.value.copy(
            error = "点击失败\n· input 命令：$shellReason\n· 反射注入：$fallbackReason"
        )
        false
    }

    /** 滑动。同样走 `input -d` 命令。 */
    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 250,
    ): Boolean = withContext(Dispatchers.IO) {
        val state = _state.value
        if (!state.running || state.displayId < 0) return@withContext false

        val shell = RootShell.exec(
            "input -d ${state.displayId} swipe " +
                "${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs",
            10_000,
        )
        if (shell.ok) return@withContext true
        val shellReason = shell.stderr.trim().ifBlank { shell.stdout.trim() }
            .ifBlank { "退出码 ${shell.exitCode}" }

        val fallback = runCatching {
            send(state.port, "swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
        }.getOrNull()
        if (fallback != null && fallback.startsWith("OK")) return@withContext true

        val fallbackReason = fallback?.removePrefix("ERR ") ?: "helper 无响应"
        _state.value = _state.value.copy(
            error = "滑动失败\n· input 命令：$shellReason\n· 反射注入：$fallbackReason"
        )
        false
    }

    /**
     * 实时帧流。
     *
     * 协议：客户端发 `stream`，服务端回 `OK stream`，之后每帧先发一行 `FRAME <字节数>`，
     * 紧跟 JPEG 原始字节。协程取消 → socket 关闭 → helper 那边写入抛异常 → 推流循环退出。
     *
     * 注意这里不能用 BufferedReader 读头再切回 InputStream：BufferedReader 会
     * 预读一大块，把后面的二进制也吞进它自己的缓冲区。所以统一用 BufferedInputStream
     * 自己按字节读行。
     */
    suspend fun stream(onFrame: suspend (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        val port = _state.value.port
        if (!_state.value.running || port == 0) return@withContext

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.soTimeout = 30_000
            val bis = java.io.BufferedInputStream(socket.getInputStream())
            val writer = java.io.OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

            writer.write("stream\n")
            writer.flush()

            fun readLine(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = bis.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString()
                    if (b != '\r'.code) sb.append(b.toChar())
                }
            }

            if (!readLine().orEmpty().startsWith("OK")) return@withContext

            while (currentCoroutineContext().isActive) {
                val header = readLine() ?: break
                if (!header.startsWith("FRAME ")) continue
                val len = header.removePrefix("FRAME ").trim().toIntOrNull() ?: continue
                if (len <= 0 || len > 8 * 1024 * 1024) continue
                val bytes = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = bis.read(bytes, read, len - read)
                    if (n < 0) break
                    read += n
                }
                if (read < len) break
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, len) ?: continue
                onFrame(bitmap)
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
    suspend fun launchApp(
        context: Context,
        packageName: String,
        activity: String? = null,
    ): LaunchResult = withContext(Dispatchers.IO) {
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
                persistLaunch(context, packageName, activity)
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

    private fun send(port: Int, command: String, timeoutMs: Int = 20_000): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.soTimeout = timeoutMs
            val out = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            out.println(command)
            return input.readLine().orEmpty()
        }
    }
}
