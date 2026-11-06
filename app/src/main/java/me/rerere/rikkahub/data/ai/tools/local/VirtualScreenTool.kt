package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.vscreen.VirtualScreenManager
import java.io.ByteArrayOutputStream

/**
 * 虚拟屏幕工具。
 *
 * 能力边界说清楚：它开的是**一块额外的虚拟显示器**，不是你当前屏幕的镜像。
 * 想在虚拟屏里跑东西，得另外用 root_shell 往上面拉 Activity
 * （`am start --display <id>`）。这个工具只负责开屏、看屏、关屏。
 *
 * 需要 root：`DisplayManager.createVirtualDisplay` 要 CAPTURE_VIDEO_OUTPUT，
 * 普通应用进程拿不到，所以实际动作发生在 app_process 拉起的独立进程里。
 */
fun virtualScreenTool(context: Context): Tool = Tool(
    name = "virtual_screen",
    description = """
        Drive a virtual display (VirtualDisplay) on the device. Requires root.
        action=start creates a 720x1280 (default) virtual screen in a separate app_process;
        action=screenshot captures the current frame and returns it as an image;
        action=launch sends an installed app onto that display (pass package, optionally activity),
        which is how you actually put content on it;
        action=status reports whether it is running and its display id;
        action=stop tears it down.
        This is an EXTRA display, not a mirror of the user's screen (unless started with mirror=true).
        The virtual screen has no input injection in this version — it is read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: start / screenshot / launch / status / stop")
                })
                put("package", buildJsonObject {
                    put("type", "string")
                    put("description", "Package name to launch on the virtual screen, e.g. com.android.settings. Required by action=launch.")
                })
                put("activity", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional activity: '.Settings' or a full 'pkg/.Activity'. Omit to auto-resolve the launcher activity.")
                })
                put("width", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual screen width in pixels. Default 720. Only used by start.")
                })
                put("height", buildJsonObject {
                    put("type", "integer")
                    put("description", "Virtual screen height in pixels. Default 1280. Only used by start.")
                })
                put("mirror", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true mirrors the phone's own screen instead of creating an empty display. Default false.")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val width = input.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 720
        val height = input.jsonObject["height"]?.jsonPrimitive?.intOrNull ?: 1280
        val mirror = input.jsonObject["mirror"]?.jsonPrimitive?.booleanOrNull ?: false
        val packageName = input.jsonObject["package"]?.jsonPrimitive?.contentOrNull
        val activity = input.jsonObject["activity"]?.jsonPrimitive?.contentOrNull
        val state = VirtualScreenManager.state.value

        when (action) {
            "start" -> {
                val ok = VirtualScreenManager.start(context, width, height, mirror = mirror)
                val now = VirtualScreenManager.state.value
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", ok)
                            put("running", now.running)
                            put("display_id", now.displayId)
                            put("port", now.port)
                            put("width", now.width)
                            put("height", now.height)
                            put("mirror", now.mirror)
                            now.error?.let { put("error", it) }
                            if (ok) {
                                put(
                                    "hint",
                                    "虚拟屏已就绪。用 action=launch 传 package 把应用投上去，" +
                                        "或者用 root_shell 执行 `am start --display ${now.displayId} -n <pkg>/<activity>`。"
                                )
                            }
                        }.toString()
                    )
                )
            }

            "launch" -> {
                if (packageName.isNullOrBlank()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject { put("error", "action=launch 需要 package 参数") }.toString()
                        )
                    )
                }
                val result = VirtualScreenManager.launchApp(packageName, activity)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result.ok)
                            put("message", result.message)
                            put("display_id", VirtualScreenManager.state.value.displayId)
                        }.toString()
                    )
                )
            }

            "screenshot" -> {
                val bitmap = VirtualScreenManager.screenshot()
                if (bitmap == null) {
                    val err = VirtualScreenManager.state.value.error ?: "截图失败"
                    return@Tool listOf(
                        UIMessagePart.Text(buildJsonObject { put("error", err) }.toString())
                    )
                }
                val bytes = ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }.toByteArray()
                bitmap.recycle()
                val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                listOf(
                    UIMessagePart.Image(url = dataUrl),
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("bytes", bytes.size)
                            put("note", "这是虚拟屏的当前画面")
                        }.toString()
                    ),
                )
            }

            "status" -> {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("running", state.running)
                            put("starting", state.starting)
                            put("display_id", state.displayId)
                            put("port", state.port)
                            put("width", state.width)
                            put("height", state.height)
                            put("mirror", state.mirror)
                            state.error?.let { put("error", it) }
                        }.toString()
                    )
                )
            }

            "stop" -> {
                VirtualScreenManager.stop(context)
                listOf(UIMessagePart.Text(buildJsonObject { put("ok", true) }.toString()))
            }

            else -> listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "action 必须是 start / screenshot / status / stop") }.toString()
                )
            )
        }
    }
)
