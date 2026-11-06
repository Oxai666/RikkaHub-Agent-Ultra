package me.rerere.rikkahub.data.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Base64
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * 虚拟屏幕的核心进程。
 *
 * 由 [VirtualScreenManager] 通过 root 以
 * `app_process -Djava.class.path=<apk> /system/bin <本类> --port N ...` 拉起，
 * 跑在一个**独立进程**里，身份是 su（shell 或 root）。
 *
 * 为什么必须另起进程：
 *  - `DisplayManager.createVirtualDisplay` 需要 CAPTURE_VIDEO_OUTPUT（system 级），
 *    普通应用进程拿不到，所以只能借 su 的身份；
 *  - 单独进程崩了也不会把主 App 带走。
 *
 * 协议极简：连上以后发一行命令，回一行结果。
 *   shot      -> `OK <宽度> <高度> <base64-jpeg>` / `ERR <原因>`
 *   size      -> `OK <宽度> <高度>`
 *   ping      -> `OK pong`
 *   quit      -> `OK bye`（随后进程退出）
 *
 * 只做「看」，不做「点」。输入注入要另一套反射（InputManager.injectInputEvent），
 * 第一版先不掺进来，免得两个都不稳。
 */
object VscreenCore {

    private const val CLASS_NAME = "me.rerere.rikkahub.data.vscreen.VscreenCore"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val opts = parse(args)
            val width = opts["width"]?.toIntOrNull() ?: 720
            val height = opts["height"]?.toIntOrNull() ?: 1280
            val dpi = opts["dpi"]?.toIntOrNull() ?: 320
            val port = opts["port"]?.toIntOrNull() ?: 8765
            val mirror = opts["mirror"]?.toBooleanStrictOrNull() ?: false

            // 主 Looper 必须先建：ActivityThread 构造时就会创建 Handler，
            // 没有 Looper 会直接抛 "Can't create handler inside thread that has not
            // called Looper.prepare()"。
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper()
            }

            val context = systemContext()
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // maxImages = 2：够「拿上一帧 + 正在产生的一帧」，再多只会占内存
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay: VirtualDisplay = displayManager.createVirtualDisplay(
                "rikkahub-vscreen",
                width,
                height,
                dpi,
                reader.surface,
                virtualDisplayFlags(mirror),
            ) ?: error("createVirtualDisplay returned null")

            val server = ServerSocket(port)
            val displayId = virtualDisplay.display.displayId

            // socket 服务放后台线程，主线程交给 Looper.loop()：
            // ActivityThread 的 handler 需要主线程在 loop，否则 DisplayManager
            // 的回调可能永远不投递。
            Thread {
                serve(server, reader, width, height, virtualDisplay, displayId)
                // serve 只在 quit / 连接异常时返回；主线程还卡在 Looper.loop()，
                // 所以直接结束整个进程，别让僵尸进程留在后台。
                kotlin.system.exitProcess(0)
            }.apply { isDaemon = true }.start()

            // 父进程靠这一行确认「起来了并且端口是对的」，之后才去连
            println("VSCREEN_READY $port $width $height $displayId")
            System.out.flush()

            android.os.Looper.loop()
        } catch (t: Throwable) {
            // 父进程读 stderr 判断失败原因；带上堆栈第一层，方便定位
            val where = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            System.err.println("VSCREEN_ERROR ${t.javaClass.simpleName}: ${t.message} @ $where")
            System.err.flush()
            kotlin.system.exitProcess(1)
        }
    }

    /** quit 命令会让整个进程退出：多个客户端线程共用一个标志。 */
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun serve(
        server: ServerSocket,
        reader: ImageReader,
        width: Int,
        height: Int,
        display: VirtualDisplay,
        displayId: Int,
    ) {
        // 每个连接一个线程。
        // 串行处理的话，小窗的 stream 长连接会把服务端占死，
        // tap / quit 全都排在后面 —— 表现就是「点了没反应、停止也停不掉」。
        while (!stopping.get()) {
            val socket: Socket = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            Thread {
                runCatching { handleClient(socket, reader, width, height, displayId) }
            }.apply { isDaemon = true }.start()
        }
        runCatching { display.release() }
        runCatching { reader.close() }
        runCatching { server.close() }
    }

    private fun handleClient(
        socket: Socket,
        reader: ImageReader,
        width: Int,
        height: Int,
        displayId: Int,
    ) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val out = java.io.BufferedOutputStream(client.getOutputStream())

            fun sendLine(text: String) {
                out.write((text + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val command = runCatching { input.readLine() }.getOrNull()?.trim().orEmpty()
            when {
                command == "ping" -> sendLine("OK pong")
                command == "size" -> sendLine("OK $width $height")
                command == "info" -> sendLine(
                    "OK {\"display_id\":$displayId,\"width\":$width,\"height\":$height}"
                )
                command == "shot" -> {
                    val jpeg = captureJpeg(reader, width, height)
                    if (jpeg == null) {
                        sendLine("ERR no frame yet")
                    } else {
                        sendLine("OK $width $height ${Base64.encodeToString(jpeg, Base64.NO_WRAP)}")
                    }
                }
                // 持续推帧。界面完全静止时 ImageReader 不出新帧，这里重发上一帧，
                // 保证客户端不会因为「太久没数据」而超时断开。
                command == "stream" -> {
                    sendLine("OK stream")
                    var lastJpeg: ByteArray? = null
                    while (!stopping.get()) {
                        val fresh = captureJpeg(reader, width, height)
                        if (fresh != null) lastJpeg = fresh
                        val toSend = lastJpeg
                        if (toSend != null) {
                            sendLine("FRAME ${toSend.size}")
                            out.write(toSend)
                            out.flush()
                        }
                        Thread.sleep(66)
                    }
                }
                command.startsWith("tap ") -> {
                    val p = command.split(" ").filter { it.isNotBlank() }
                    val x = p.getOrNull(1)?.toFloatOrNull()
                    val y = p.getOrNull(2)?.toFloatOrNull()
                    if (x == null || y == null) {
                        sendLine("ERR tap needs <x> <y>")
                    } else {
                        val failure = runCatching { injectTap(x, y, displayId) }.exceptionOrNull()
                        sendLine(if (failure == null) "OK tap" else "ERR ${describe(failure)}")
                    }
                }
                command.startsWith("swipe ") -> {
                    val p = command.split(" ").filter { it.isNotBlank() }
                    val x1 = p.getOrNull(1)?.toFloatOrNull()
                    val y1 = p.getOrNull(2)?.toFloatOrNull()
                    val x2 = p.getOrNull(3)?.toFloatOrNull()
                    val y2 = p.getOrNull(4)?.toFloatOrNull()
                    val dur = p.getOrNull(5)?.toLongOrNull() ?: 250L
                    if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        sendLine("ERR swipe needs <x1> <y1> <x2> <y2> [duration]")
                    } else {
                        val failure = runCatching { injectSwipe(x1, y1, x2, y2, dur, displayId) }
                            .exceptionOrNull()
                        sendLine(if (failure == null) "OK swipe" else "ERR ${describe(failure)}")
                    }
                }
                command == "quit" -> {
                    sendLine("OK bye")
                    stopping.set(true)
                    // 主线程阻塞在 accept() 上，直接结束进程最干脆
                    kotlin.system.exitProcess(0)
                }
                else -> sendLine("ERR unknown command: $command")
            }
        }
    }

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "no message"}"

    /**
     * 抓一帧转 JPEG。
     *
     * acquireLatestImage 会返回**最近**的一帧；虚拟屏刚创建时可能还没有内容，
     * 所以这里等最多 1.5 秒，等不到就返回空图（而不是抛异常）。
     */
    private fun captureJpeg(reader: ImageReader, width: Int, height: Int): ByteArray? {
        var image: android.media.Image? = null
        val deadline = System.currentTimeMillis() + 600
        while (image == null && System.currentTimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(16)
        }
        val acquired = image ?: return null
        return try {
            val plane = acquired.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            // rowStride 可能大于 width * pixelStride（有 padding），要逐行拷贝
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (rowStride == width * pixelStride) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowBytes = width * pixelStride
                val padded = ByteArray(rowStride)
                val target = ByteArray(rowBytes * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    val toRead = minOf(rowStride, buffer.remaining())
                    buffer.get(padded, 0, toRead)
                    System.arraycopy(padded, 0, target, row * rowBytes, rowBytes)
                }
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(target))
            }
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { acquired.close() }
        }
    }

    /**
     * 把触摸事件注入虚拟屏。
     *
     * 这一整段都是 hidden API：
     *   InputManager.getInstance() / injectInputEvent()  —— 不在 SDK 里
     *   MotionEvent.setDisplayId()                       —— 不在 SDK 里
     * 少 setDisplayId 的话事件会打到**主屏**，表现就是「点了没反应」。
     */
    private fun injectTap(x: Float, y: Float, displayId: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        injectMotion(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, displayId)
        Thread.sleep(40)
        injectMotion(
            downTime,
            android.os.SystemClock.uptimeMillis(),
            android.view.MotionEvent.ACTION_UP,
            x, y, displayId,
        )
    }

    private fun injectSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        displayId: Int,
    ) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val steps = 12
        val stepDelay = (durationMs / steps).coerceAtLeast(8)
        injectMotion(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x1, y1, displayId)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            Thread.sleep(stepDelay)
            injectMotion(
                downTime,
                android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_MOVE,
                x1 + (x2 - x1) * t,
                y1 + (y2 - y1) * t,
                displayId,
            )
        }
        Thread.sleep(stepDelay)
        injectMotion(
            downTime,
            android.os.SystemClock.uptimeMillis(),
            android.view.MotionEvent.ACTION_UP,
            x2, y2, displayId,
        )
    }

    private fun injectMotion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        displayId: Int,
    ) {
        val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            // 反射设 displayId —— 不设的话事件落在主屏，虚拟屏上点不动。
            // 这里不能吞异常：吞了以后「点了没反应」会变成一个查不出来的静默失败。
            runCatching {
                android.view.MotionEvent::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(event, displayId)
            }.getOrElse {
                throw IllegalStateException("MotionEvent.setDisplayId 反射失败（display=$displayId）：${it.message}", it)
            }

            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val inputManager = inputManagerClass.getMethod("getInstance").invoke(null)
                ?: error("InputManager.getInstance() 返回 null（这个进程没有 system context）")
            val inject = inputManagerClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            // 2 = INJECT_INPUT_EVENT_MODE_ASYNC
            val injected = inject.invoke(inputManager, event, 2) as? Boolean ?: false
            if (!injected) {
                throw IllegalStateException(
                    "injectInputEvent 返回 false —— 通常是缺少 INJECT_EVENTS 权限（当前 uid 不是 root？）"
                )
            }
        } finally {
            event.recycle()
        }
    }

    /**
     * 组装 VirtualDisplay 的 flags。
     *
     * `OWN_CONTENT_ONLY` 是分水岭：
     *  - 有它 → 独立的一块屏，只有主动投上去的 Activity 才会显示（默认）
     *  - 没有 → 镜像主显示（你手机现在显示什么，它就显示什么）
     *
     * 后面几个常量在不同 API 级别是 hidden 的，Kotlin 编译期看不到，
     * 所以一律用反射取名字，取不到就跳过 —— 少了它们只会损失触摸/焦点能力，
     * 不会让创建失败。
     */
    private fun virtualDisplayFlags(mirror: Boolean): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        if (!mirror) {
            flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        }
        for (name in listOf(
            "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
            "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
            "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
            // TRUSTED 需要系统签名，普通 su 进程带上反而会被拒；不加。
        )) {
            flags = flags or runCatching {
                DisplayManager::class.java.getField(name).getInt(null)
            }.getOrDefault(0)
        }
        return flags
    }

    private fun parse(args: Array<String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val key = args[i]
            if (key.startsWith("--") && i + 1 < args.size) {
                map[key.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }
        return map
    }

    /**
     * 在没有 Activity 的进程里拿 Context。
     *
     * 这一段全是照着 DSHA 的 PrivilegedPackageContext 抄的，顺序不能变：
     *
     *  1. 主 Looper 必须先建 —— ActivityThread 构造时会创建 Handler
     *  2. **不能调 ActivityThread.systemMain()**：它会跑 ROM 的 attach 钩子，
     *     在 app_process 里有副作用；直接 new 一个再自己塞静态字段更轻
     *  3. mSystemThread = true 必须设，否则 getSystemContext() 返回 null
     *  4. sCurrentActivityThread 必须设，否则后续拿到的还是 null
     */
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")

        var thread: Any? = runCatching {
            val current = type.getDeclaredMethod("currentActivityThread")
            current.isAccessible = true
            current.invoke(null)
        }.getOrNull()

        if (thread == null) {
            val ctor = type.getDeclaredConstructor()
            ctor.isAccessible = true
            thread = ctor.newInstance()
        }

        // 无论新建还是已有的，都补上这两个字段：缺一个 getSystemContext 就返回 null
        runCatching {
            val systemThread = type.getDeclaredField("mSystemThread")
            systemThread.isAccessible = true
            systemThread.setBoolean(thread, true)
        }
        runCatching {
            val current = type.getDeclaredField("sCurrentActivityThread")
            current.isAccessible = true
            current.set(null, thread)
        }

        val getSystemContext = type.getDeclaredMethod("getSystemContext")
        getSystemContext.isAccessible = true
        val context = getSystemContext.invoke(thread) as? Context
            ?: error("ActivityThread.getSystemContext() returned null")
        return context
    }
}
