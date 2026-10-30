package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.Process
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
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
import me.rerere.rikkahub.browser.BrowserAiActionKind
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.BrowserDiffHelper
import me.rerere.rikkahub.browser.BrowserEventBus
import me.rerere.rikkahub.browser.HOOK_TAB_ID
import me.rerere.rikkahub.browser.hook.HookAction
import me.rerere.rikkahub.browser.hook.HookInstaller
import me.rerere.rikkahub.browser.hook.HookRule
import me.rerere.rikkahub.browser.hook.HookRuleStore
import me.rerere.rikkahub.browser.hook.HookType
import me.rerere.rikkahub.browser.hook.NetworkBodyStore
import me.rerere.rikkahub.browser.hook.validate
import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.browser.cdp.CdpDebugController
import me.rerere.rikkahub.browser.cdp.CdpManager
import me.rerere.rikkahub.browser.cdp.DebugBreakpoint
import me.rerere.rikkahub.browser.cdp.DebugStep
import me.rerere.rikkahub.browser.cdp.CdpAttach
import me.rerere.rikkahub.browser.cdp.CdpProbe
import me.rerere.rikkahub.browser.HeadlessBrowserSessionPool
import me.rerere.rikkahub.browser.ReadabilityRunner.runReadability
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.browser.awaitActionSettled
import me.rerere.rikkahub.browser.awaitNavigationStarted
import me.rerere.rikkahub.browser.awaitTitleSettled
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pass 2 of the in-app Browser feature: 17 LLM-callable tool factories that drive the
 * BrowserActivity's WebView through [BrowserControllerHandle.withController].
 *
 * Every tool wraps its dispatch in [withTimeoutOrNull] (30 s per the spec's "every tool
 * MUST have a hard timeout" rule); every state-changing write tool also calls
 * [awaitReadyState] post-action so the next read tool sees the new page.
 *
 * Tool registration is gated by [me.rerere.rikkahub.browser.BrowserPreferences] in
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] — toggling a tool off in
 * Settings → Browser unregisters it entirely, so YOLO can't accidentally run a tool the
 * user has explicitly disabled.
 */

private const val MAX_SCREENSHOT_HEIGHT_PX = 8192
private const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

/**
 * Hard cap on the string browser_eval_js puts in its result envelope. Matches the 64 KB
 * ceiling the read tools (runGetText / runReadHelper) clamp page text/HTML to, so an eval of
 * something like `document.body.outerHTML` can't bloat the turn with megabytes of payload.
 */
private const val EVAL_JS_MAX_RESULT_CHARS = 64 * 1024

/**
 * Per-tool timeout budget every browser tool wraps its dispatch in. User-configurable via
 * Settings → Browser (GitHub issue #4) — resolved fresh on each tool call from
 * [BrowserController.perToolTimeoutMs], which [me.rerere.rikkahub.browser.BrowserPreferences]
 * keeps in sync with the persisted value.
 */
private val toolTimeoutMs: Long get() = BrowserController.perToolTimeoutMs

/**
 * Pass 3: appended to every browser tool's description so the LLM knows that in headless
 * (Telegram / cron / sub-agent) mode, the act of taking a state-changing action will
 * automatically push a screenshot+caption to the calling chat. Without this cue, the
 * model would otherwise call `browser_screenshot` after every action — doubling vision
 * tokens and round-trips for no gain.
 */
private const val TELEGRAM_HEADLESS_CUE =
    " In Telegram / headless mode, screenshots stream to the calling chat after each state-changing action — call browser_done when you're confident the task is complete."

/**
 * Decide whether the current invocation should run in headless mode. Resolution order:
 *  1. [ToolInvocationContext.callerConversationId] is the canonical source. Cron jobs,
 *     sub-agents, Telegram bot, external automation all set it.
 *  2. We try to parse it as a [Uuid]. The [HeadlessConversations] registry keys on Uuid
 *     (cron / sub-agent paths use the conversation's primary key).
 *  3. If parsing fails (some external automation paths use string ids), we still treat
 *     the [ToolInvocationContext.isHeadless] flag as authoritative — that flag is set by
 *     every flow that wants headless behaviour.
 *
 * Conservative default: foreground. The visible Activity is never wrong; the worst case
 * of a misclassification is the activity briefly appears on the user's screen.
 */
@OptIn(ExperimentalUuidApi::class)
private fun isHeadlessInvocation(ctx: ToolInvocationContext?): Boolean {
    if (ctx == null) return false
    if (ctx.isHeadless) return true
    val convId = ctx.callerConversationId ?: return false
    val asUuid = runCatching { Uuid.parse(convId) }.getOrNull() ?: return false
    return HeadlessConversations.isHeadless(asUuid)
}

// ---- Common envelope helpers --------------------------------------------------------------

private fun timeoutEnvelope(toolName: String): JsonObject = buildJsonObject {
    put("error", "tool_timeout")
    put("tool", toolName)
    put("recovery", "The browser tool exceeded its $toolTimeoutMs-ms budget. Retry, or simplify the selector.")
}

private fun missingArgEnvelope(name: String, detail: String): JsonObject = buildJsonObject {
    put("error", "missing_$name")
    put("detail", detail)
}

private fun textPart(obj: JsonObject): List<UIMessagePart> =
    listOf(UIMessagePart.Text(obj.toString()))

/**
 * JSON-encode a Kotlin string so it can be embedded inside an evaluateJavascript payload
 * as a JS string literal — handles backslashes, quotes, control characters, and Unicode
 * escapes uniformly. Doing this by hand is the canonical XSS-via-LLM-tool footgun; we
 * route through kotlinx.serialization's JsonPrimitive which formats per the JSON spec
 * (also valid JS string syntax).
 */
private fun jsString(s: String): String = JsonPrimitive(s).toString()

/**
 * Wrap a tool's dispatch with a RUNNING -> OK/FAILED [me.rerere.rikkahub.browser.BrowserAiAction]
 * entry. [beginAction] fires before [block] runs (so the stripe's spinner covers the actual
 * WebView work, not just the instant the envelope is built); [ok] reads the outcome off
 * [block]'s returned envelope — by convention `success == true`. The `try/finally` guarantees
 * the entry never sticks at RUNNING: a thrown exception or a `withTimeoutOrNull` cancellation
 * still marks it FAILED via the `finally`, then rethrows/propagates normally (a `finally` never
 * swallows the in-flight `CancellationException`).
 */
private suspend fun <T> trackAction(
    kind: BrowserAiActionKind,
    detail: String?,
    ok: (T) -> Boolean,
    block: suspend () -> T,
): T {
    val id = BrowserController.beginAction(kind, detail)
    var completed = false
    try {
        val result = block()
        BrowserController.completeAction(id, ok(result))
        completed = true
        return result
    } finally {
        if (!completed) BrowserController.completeAction(id, false)
    }
}

/** [trackAction] specialised for the common case: outcome is the envelope's `success` field. */
private suspend fun trackJsonAction(
    kind: BrowserAiActionKind,
    detail: String?,
    block: suspend () -> JsonObject,
): JsonObject = trackAction(kind, detail, ok = { it["success"]?.jsonPrimitive?.booleanOrNull == true }, block = block)

// ---- Read tools ---------------------------------------------------------------------------

fun browserOpenTool(context: Context, invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.OPEN,
    description = "Navigate the in-app browser to a URL. Launches the browser if it isn't open. Returns {success, current_url, title}. Resets the per-task 5-minute timer.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The full URL to navigate to (https://...)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // Heuristic exfil-shape check: if the URL's QUERY (not path — CDN asset hashes
        // would false-positive) carries something that looks like an opaque blob, JWT,
        // API key, or credit-card-shaped digit run, attach a warning so the LLM treats
        // the destination with care. Best-effort, not a security boundary.
        val exfilHits = SensitiveContentDetector.scanUrlQuery(url)
        // Scheme allow-list IS a security boundary: the browser WebViews run with
        // allowFileAccess on (skill webview cards open file:// pages there), so a
        // model-driven file:// navigation followed by browser_get_text would read
        // app-private files (datastore, DB, known_hosts) into the conversation —
        // including via prompt injection from a browsed page. file://, content://,
        // javascript:, intent: etc. are therefore rejected at the tool boundary.
        // Scheme-less input is passed through unchanged (pre-existing behaviour).
        val scheme = url?.let { android.net.Uri.parse(it.trim()).scheme?.lowercase() }
        val rawOut = if (url == null) {
            missingArgEnvelope("url", "url is required and must be a non-empty string")
        } else if (scheme != null && scheme !in setOf("http", "https", "about")) {
            buildJsonObject {
                put("error", "scheme_not_allowed")
                put("detail", "browser_open only accepts http(s) and about: URLs; got scheme '$scheme'")
            }
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                // Pass 3 mode picker. If the caller is a Telegram / cron / sub-agent
                // conversation, run in headless mode — no on-screen Activity, screenshots
                // streamed to the calling chat after every state-changing tool. Otherwise
                // fall back to the foreground BrowserActivity (the only tool that can
                // launch it).
                val callerConvId = invocationContext?.callerConversationId
                val headless = isHeadlessInvocation(invocationContext)

                if (headless && callerConvId != null) {
                    // Peek BEFORE allocating: getOrCreate + start() spin up a ~30 MB WebView,
                    // which bindHeadless would then immediately reject if a different live
                    // conversation owns the controller slot — wasting the allocation.
                    // canBindHeadless mirrors bindHeadless's reject rule without mutating state;
                    // bindHeadless stays authoritative and re-checks under its lock, so a race
                    // here costs at most a discarded allocation, never a wrong binding.
                    if (!BrowserController.canBindHeadless(callerConvId)) {
                        return@withTimeoutOrNull BrowserController.bindBusyEnvelope()
                    }
                    // Headless path: get-or-create the per-conv WebView session. The
                    // lifecycle of the WebView piggybacks on the calling FGS — when the
                    // FGS dies, the whole pool dies with it. Subsequent tool calls will
                    // see Mode.Idle and return `browser_session_lost`.
                    val session = HeadlessBrowserSessionPool.getOrCreate(context, callerConvId)
                    val webView = withContext(Dispatchers.Main) { session.start(callerConvId) }
                    // Reject if another conversation already holds the (single, global)
                    // controller binding — binding a second concurrently would route this
                    // conv's streamed screenshots into the other chat. Same-conv re-bind is
                    // always accepted, so the common per-task reuse path is unaffected.
                    if (!BrowserController.bindHeadless(callerConvId, webView)) {
                        return@withTimeoutOrNull BrowserController.bindBusyEnvelope()
                    }
                    BrowserController.startTaskWindow()
                    val result = trackJsonAction(BrowserAiActionKind.OPEN, url) {
                        BrowserControllerHandle.withController {
                            // loadUrl 是异步的：先记下旧 URL，等它真的变了再取 readyState，
                            // 否则旧页面仍 complete，会立刻返回上一页的 url/title
                            val beforeUrl = webView.url.orEmpty()
                            val beforeTitle = webView.title.orEmpty()
                            withContext(Dispatchers.Main) { webView.loadUrl(url) }
                            webView.awaitNavigationStarted(beforeUrl)
                            webView.awaitReadyState(8_000L)
                            // title 比 readyState 晚一步稳定，不等的话会返回上一页的标题
                            webView.awaitTitleSettled(beforeTitle)
                            buildJsonObject {
                                put("success", true)
                                put("current_url", webView.url ?: url)
                                put("title", webView.title.orEmpty())
                            }
                        }
                    }
                    // Stream the landing-page screenshot so the user sees we arrived.
                    BrowserController.streamScreenshotIfHeadless("Opened $url")
                    result
                } else {
                    // Foreground path (Pass 1+2 behaviour). browser_open is the ONLY tool
                    // allowed to launch the Activity. Any other tool returning
                    // browser_not_open is the LLM's signal to call this first.
                    val wasAlreadyBound = BrowserController.isBound()
                    if (!wasAlreadyBound) {
                        context.startActivity(
                            me.rerere.rikkahub.browser.BrowserActivity.intent(
                                context,
                                url,
                                conversationId = callerConvId,
                            )
                        )
                        if (!BrowserController.awaitBind(5_000L)) {
                            return@withTimeoutOrNull buildJsonObject {
                                put("error", "browser_launch_failed")
                                put("recovery", "Activity did not bind within 5s; retry browser_open or check that the app has overlay permission.")
                            }
                        }
                    }
                    // (Re)start the 5-minute task window on every browser_open.
                    BrowserController.startTaskWindow()
                    trackJsonAction(BrowserAiActionKind.OPEN, url) {
                        BrowserControllerHandle.withController {
                            // When the Activity was just freshly launched, the URL was already
                            // passed as EXTRA_INITIAL_URL and the WebView began loading it
                            // before bind() returned. Skip a redundant second loadUrl — it
                            // would abort the in-flight load and restart from the top.
                            val beforeUrl = webView.url.orEmpty()
                            val beforeTitle = webView.title.orEmpty()
                            if (wasAlreadyBound) {
                                withContext(Dispatchers.Main) { webView.loadUrl(url) }
                            }
                            // 同上：等导航真的开始，别在旧页面上采样
                            webView.awaitNavigationStarted(beforeUrl)
                            webView.awaitReadyState(8_000L)
                            webView.awaitTitleSettled(beforeTitle)
                            buildJsonObject {
                                put("success", true)
                                put("current_url", webView.url ?: url)
                                put("title", webView.title.orEmpty())
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.OPEN)
        }
        val out = if (exfilHits.isEmpty() ||
            rawOut["success"]?.jsonPrimitive?.booleanOrNull != true) rawOut
        else buildJsonObject {
            rawOut.forEach { (k, v) -> put(k, v) }
            put(
                "warning",
                "URL query string carries content shaped like sensitive data " +
                    "(${exfilHits.joinToString { it.name.lowercase() }}). " +
                    "Verify with the user that they intended to send this " +
                    "to ${rawOut["current_url"]?.jsonPrimitive?.contentOrNull ?: url} " +
                    "before relying on the result, and do NOT echo the value back."
            )
        }
        textPart(out)
    },
)

fun browserCurrentUrlTool(): Tool = Tool(
    name = BrowserToolDefaults.CURRENT_URL,
    description = "Return the browser's current URL and page title. {url, title}. browser_not_open if the browser isn't open.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                buildJsonObject {
                    put("url", webView.url.orEmpty())
                    put("title", webView.title.orEmpty())
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.CURRENT_URL)
        textPart(out)
    },
)

/**
 * CDP 连通性探针（移植自太墟 taiXu runtime/browser/cdp）。
 *
 * 只做一件事：开调试开关 → 找 abstract socket → GET /json 列 target。
 * 返回空列表 + errors 时，errors 里会写明卡在哪一步（开开关 / 连 socket / 解析 / 没有活动 WebView）。
 */
fun browserDebugTargetsTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_TARGETS,
    description = "List Chrome DevTools Protocol targets of this app's own WebViews (page / service_worker / webview). " +
        "Read-only probe: it flips WebView debugging on, finds the devtools abstract socket, and GETs /json. " +
        "Returns {socket, provider, targets:[{id,type,title,url,ws}], errors:[...]}. " +
        "Empty targets with errors means the CDP path is broken at that step — read errors first.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = if (!BrowserController.isBound()) {
            // devtools 服务端点属于 WebView 自己：WebView 销毁后 socket 一并消失，
            // 这时候连 socket 只会超时，模型会误判成"服务端坏了"。
            // 直接说清楚，别让它瞎猜。
            buildJsonObject {
                put("error", "no_active_webview")
                put("pid", Process.myPid())
                put("hint", "浏览器没打开或 WebView 已销毁。devtools socket 随 WebView 生灭，" +
                    "先用 browser_open 打开一个页面，再调本工具。")
            }
        } else withTimeoutOrNull(toolTimeoutMs) {
            runCatching { CdpProbe.listTargets() }.fold(
                onSuccess = { r ->
                    buildJsonObject {
                        put("socket", r.socketName)
                        put("provider", r.diagnostics)
                        put("pid", r.pid)
                        put("count", r.targets.size)
                        putJsonArray("targets") {
                            r.targets.forEach { t ->
                                add(buildJsonObject {
                                    put("id", t.id)
                                    put("type", t.type)
                                    put("title", t.title)
                                    put("url", t.url)
                                    put("ws", t.webSocketDebuggerUrl)
                                })
                            }
                        }
                        if (r.candidates.isNotEmpty()) {
                            putJsonArray("candidates") { r.candidates.forEach { add(JsonPrimitive(it)) } }
                        }
                        if (r.errors.isNotEmpty()) {
                            putJsonArray("errors") { r.errors.forEach { add(JsonPrimitive(it)) } }
                        }
                    }
                },
                onFailure = { e ->
                    buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                },
            )
        } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_TARGETS)
        textPart(out)
    },
)

/**
 * 通过 CDP 会话执行一条 JS。
 *
 * 与 browser_eval_js 的区别：那条走 `WebView.evaluateJavascript`（宿主注入），
 * 这条走真正的 CDP WebSocket（handshake → JSON-RPC → Runtime.evaluate）。
 * 它的价值在于验证会话层：WS 握手、帧编解码、命令/响应配对，全是后面断点与
 * 调用栈的基础设施。返回里带回完整 response，方便看 exceptionDetails。
 */
fun browserDebugEvalTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_EVAL,
    description = "Evaluate one JavaScript expression on the active page through the Chrome DevTools Protocol " +
        "(real WebSocket session, not host-side injection). Returns {raw:{...}} with the full CDP response, " +
        "so exceptionDetails is visible. Use it to check the CDP session works; for page text/DOM prefer browser_get_text / browser_get_dom.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("expression", buildJsonObject {
                put("type", "string")
                put("description", "JS expression, e.g. document.title")
            })
        }, required = listOf("expression"))
    },
    execute = { input ->
        val expr = input.jsonObject["expression"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (expr == null) {
            missingArgEnvelope("expression", "expression is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                runCatching { CdpAttach.evaluate(expr) }.fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    },
                )
            } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_EVAL)
        }
        textPart(out)
    },
)

// ---------------------------------------------------------------------------------------------
// Hook 工具（移植自太墟 hook 引擎）
// ---------------------------------------------------------------------------------------------

private fun hookStore(): HookRuleStore =
    org.koin.java.KoinJavaComponent.getKoin().get()

private fun hookInstaller(): HookInstaller =
    org.koin.java.KoinJavaComponent.getKoin().get()

private fun hookEventBus(): BrowserEventBus =
    org.koin.java.KoinJavaComponent.getKoin().get()

private fun actionLabel(action: HookAction): String = when (action) {
    is HookAction.Log -> "log"
    is HookAction.Block -> "block"
    is HookAction.Redirect -> "redirect:" + action.url
    is HookAction.Mock -> "mock:" + action.status
    is HookAction.ModifyHeaders -> "headers"
    is HookAction.Replace -> "replace"
    is HookAction.FakeValue -> "fake:" + action.value
}

/** 规则变化后推给已加载页面；浏览器没开就只存规则，下次加载页面自动生效。 */
private suspend fun pushHookRules(): Boolean {
    val installer = runCatching { hookInstaller() }.getOrNull() ?: return false
    return withTimeoutOrNull(2_000) {
        runCatching {
            // withController 的 block 必须返回 JsonObject（它用返回值当工具结果）。
            BrowserControllerHandle.withController {
                withContext(Dispatchers.Main) { installer.pushRules(HOOK_TAB_ID, webView) }
                buildJsonObject { put("pushed", true) }
            }
        }.isSuccess
    } ?: false
}

private const val MAX_NETWORK_BODY_CHARS = 4000

private fun networkBodyStore(): NetworkBodyStore =
    org.koin.java.KoinJavaComponent.getKoin().get()

/**
 * 网络请求列表。数据来自 hook 引擎（source="js"）：
 * 只有**命中规则**的请求才会走上报路径，所以这里的条目天然是「你关心过的那批」。
 * body 存在 NetworkBodyStore 里，只有规则开了 capture_body 才会有内容。
 */
fun browserConsoleListTool(): Tool = Tool(
    name = BrowserToolDefaults.CONSOLE_LIST,
    description = "Read page console output captured so far: {count, lines:[{level, message, at}]}. " +
        "log/warn/error/info/debug are recorded unconditionally — no hook rule required, works on every " +
        "device (unlike CDP). Use it as the first diagnostic: page-side errors usually explain why a " +
        "request never fired or a click did nothing. Filter with level='error' or a substring in `grep` " +
        "when the page is chatty. Buffer holds the most recent entries per session.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "max entries, default 50")
            })
            put("level", buildJsonObject {
                put("type", "string")
                put("description", "only this level, e.g. error / warn / log")
            })
            put("grep", buildJsonObject {
                put("type", "string")
                put("description", "case-insensitive substring filter on message")
            })
        })
    },
    execute = { input ->
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val levelFilter = input.jsonObject["level"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        val grep = input.jsonObject["grep"]?.jsonPrimitive?.contentOrNull?.trim()
        val bus = runCatching { hookEventBus() }.getOrNull()
        val all = bus?.console?.value.orEmpty()
        val filtered = all.filter { line ->
            (levelFilter.isNullOrEmpty() || line.level.equals(levelFilter, ignoreCase = true)) &&
                (grep.isNullOrEmpty() || line.message.contains(grep, ignoreCase = true))
        }.takeLast(limit)
        val out = buildJsonObject {
            put("count", filtered.size)
            put("total", all.size)
            if (all.isEmpty()) {
                put("hint", "还没有 console 输出：确认页面已加载（browser_open 后稍等），或页面本身没有打印")
            }
            putJsonArray("lines") {
                filtered.forEach { line ->
                    add(buildJsonObject {
                        put("level", line.level)
                        put("message", line.message)
                        put("at", line.at)
                    })
                }
            }
        }
        textPart(out)
    },
)

fun browserSnapshotTool(): Tool = Tool(
    name = BrowserToolDefaults.SNAPSHOT,
    description = "Snapshot the interactive elements of the page: {count, elements:[{ref, tag, type, text, " +
        "selector, x, y, w, h}]}. Cheaper and far more reliable than guessing CSS selectors: read the list, " +
        "then pass the `selector` field straight to browser_click / browser_type / browser_select. " +
        "Only visible elements are returned, in DOM order; refs (@e1, @e2 …) belong to this snapshot only — " +
        "call it again after the page changes.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "limit the scan to this subtree, e.g. 'main' or '#login'. Default body")
            })
            put("max", buildJsonObject {
                put("type", "integer")
                put("description", "max elements, default 60, cap 200")
            })
        })
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: "body"
        val max = (input.jsonObject["max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60)
            .coerceIn(1, 200)
        val code = """
            (function(){
                try {
                    var root = document.querySelector(${jsString(selector)});
                    if (!root) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                    var MAX = $max;
                    var q = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],'
                          + '[role=tab],[role=checkbox],[role=menuitem],[onclick],[contenteditable=true]';
                    function cssSel(el) {
                        if (el.id) {
                            try {
                                var ids = '#' + CSS.escape(el.id);
                                if (document.querySelectorAll(ids).length === 1) return ids;
                            } catch(e) {}
                        }
                        var nm = el.getAttribute('name');
                        if (nm) {
                            var nmSel = el.tagName.toLowerCase() + '[name="' + nm.replace(/"/g,'\\"') + '"]';
                            try {
                                if (document.querySelectorAll(nmSel).length === 1) return nmSel;
                            } catch(e) {}
                            // 不唯一（例如 4 个 checkbox 共用 name=topping、3 个 radio 共用 name=size）
                            // 就继续往下走完整路径。否则 querySelector 静默取第一个匹配：
                            // 点「cheese」实际勾中「bacon」，click 还返回 success:true，最难查的一类错。
                            var vv = el.getAttribute('value');
                            if (vv !== null) {
                                var nmVal = nmSel + '[value=' + String.fromCharCode(34)
                                          + String(vv).replace(/"/g,'\\')
                                          + String.fromCharCode(34) + ']';
                                try {
                                    if (document.querySelectorAll(nmVal).length === 1) return nmVal;
                                } catch(e) {}
                            }
                        }
                        var parts = [], cur = el, guard = 0;
                        while (cur && cur.nodeType === 1 && guard++ < 8) {
                            if (cur.id) { try { parts.unshift('#' + CSS.escape(cur.id)); } catch(e) { parts.unshift('#' + cur.id); } break; }
                            var tag = cur.tagName.toLowerCase();
                            var par = cur.parentElement;
                            if (par) {
                                var idx = Array.prototype.indexOf.call(par.children, cur) + 1;
                                tag += ':nth-child(' + idx + ')';
                            }
                            parts.unshift(tag);
                            cur = par;
                        }
                        return parts.join(' > ');
                    }
                    // 清掉上一轮留下的标记，避免 DOM 上越堆越多
                    try {
                        var stale = document.querySelectorAll('[data-rikka-ref]');
                        for (var si = 0; si < stale.length; si++) stale[si].removeAttribute('data-rikka-ref');
                    } catch(e) {}

                    var nodes = root.querySelectorAll(q);
                    var out = [];
                    for (var i = 0; i < nodes.length && out.length < MAX; i++) {
                        var el = nodes[i];
                        var r = el.getBoundingClientRect();
                        if (r.width <= 1 && r.height <= 1) continue;
                        var txt = (el.innerText || el.value || el.getAttribute('aria-label')
                                  || el.getAttribute('placeholder') || el.getAttribute('title') || '');
                        txt = String(txt).replace(/\s+/g, ' ').trim().slice(0, 80);
                        var ref = '@e' + (out.length + 1);
                        var sel;
                        try {
                            // 扫描时就地打标记，selector 换成属性选择器。
                            // 比 nth-child 路径稳：元素被搬走、兄弟节点增删、列表重排都还能命中。
                            // 这也是太墟的做法（data-taixu-ref），我们换成自己的前缀避免撞车。
                            el.setAttribute('data-rikka-ref', ref);
                            sel = '[data-rikka-ref="' + ref + '"]';
                        } catch(e) {
                            sel = cssSel(el);
                        }
                        // 显式告诉调用方选择器是否唯一。为 false 时它只命中第一个同类元素，
                        // 直接拿去 click 大概率点错 —— 宁可让它知道，也不要静默。
                        var uniq = false;
                        try { uniq = document.querySelectorAll(sel).length === 1; } catch(e) {}
                        out.push({
                            ref: ref,
                            tag: el.tagName.toLowerCase(),
                            type: el.getAttribute('type') || '',
                            text: txt,
                            selector: sel,
                            selectorUnique: uniq,
                            x: Math.round(r.x), y: Math.round(r.y),
                            w: Math.round(r.width), h: Math.round(r.height)
                        });
                    }
                    return JSON.stringify({
                        url: location.href,
                        // document.title 可能是空串（无 <title> 或尚未解析完），回落到 URL
                        title: document.title || location.href,
                        total: nodes.length,
                        count: out.length,
                        elements: out
                    });
                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
            })()
        """.trimIndent()
        val out = withTimeoutOrNull(toolTimeoutMs) {
            runCatching {
                BrowserControllerHandle.withController {
                    parseJsResult(webView.evaluateJavascriptAsync(code))
                }
            }.getOrElse { e ->
                buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SNAPSHOT)
        textPart(out)
    },
)

fun browserNetworkListTool(): Tool = Tool(
    name = BrowserToolDefaults.NETWORK_LIST,
    description = "List network requests captured by the hook engine. Each entry has method/url/status/" +
        "duration/sizes/hasRequestBody/hasResponseBody/ruleId/actionTaken. " +
        "Pass with_body=true to attach stored bodies (only present when the rule had capture_body). " +
        "Note: only requests matched by a hook rule get captured.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "max entries, default 20")
            })
            put("with_body", buildJsonObject {
                put("type", "boolean")
                put("description", "attach request/response bodies when stored")
            })
        })
    },
    execute = { input ->
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val withBody = input.jsonObject["with_body"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() == true
        val bus = runCatching { hookEventBus() }.getOrNull()
        val bodyStore = runCatching { networkBodyStore() }.getOrNull()
        val requests = bus?.network?.value.orEmpty().takeLast(limit)
        val out = buildJsonObject {
            put("count", requests.size)
            if (requests.isEmpty()) {
                put("hint", "没有捕获到请求：先确认有 hook 规则命中（browser_hook_hits），再触发页面请求")
            }
            putJsonArray("requests") {
                requests.forEach { req ->
                    add(buildJsonObject {
                        put("id", req.id)
                        put("method", req.method)
                        put("url", req.url)
                        put("status", req.statusCode)
                        put("durationMs", req.durationMs)
                        put("requestSize", req.requestSize)
                        put("responseSize", req.responseSize)
                        put("hasRequestBody", req.hasRequestBody)
                        put("hasResponseBody", req.hasResponseBody)
                        put("ruleId", req.ruleId)
                        put("actionTaken", req.actionTaken)
                        if (withBody) {
                            val stored = bodyStore?.get(req.id)
                            if (stored != null) {
                                if (stored.requestBody.isNotBlank()) {
                                    put("requestBody", stored.requestBody.take(MAX_NETWORK_BODY_CHARS))
                                }
                                if (stored.responseBody.isNotBlank()) {
                                    put("responseBody", stored.responseBody.take(MAX_NETWORK_BODY_CHARS))
                                }
                            }
                        }
                    })
                }
            }
        }
        textPart(out)
    },
)

fun browserHookCreateTool(): Tool = Tool(
    name = BrowserToolDefaults.HOOK_CREATE,
    description = "Install a page hook rule. Wraps the page's fetch / XHR / WebSocket / function / property at document-start. " +
        "Action: log (record hits), block (abort), redirect (rewrite URL), mock (fabricate response body). " +
        "pattern is a URL glob for fetch/xhr/websocket (e.g. https://api.example.com/*) or a function path (e.g. JSON.parse). " +
        "Returns {id, applied}. Rules persist for the session and take effect on the next page load even if the browser is closed. " +
        "This is the PRIMARY interception path on this app: it works on every device, whereas CDP attach may be " +
        "refused by the ROM. Prefer it over browser_debug_* unless you specifically need pause/step/call-stack.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("pattern", buildJsonObject {
                put("type", "string")
                put("description", "URL glob for fetch/xhr/websocket, or function/property path")
            })
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "log | block | redirect | mock")
            })
            put("type", buildJsonObject {
                put("type", "string")
                put("description", "fetch (default) | xhr | websocket | function | property")
            })
            put("value", buildJsonObject {
                put("type", "string")
                put("description", "redirect target URL / mock body / replace code")
            })
            put("status", buildJsonObject {
                put("type", "integer")
                put("description", "mock HTTP status, default 200")
            })
            put("capture_body", buildJsonObject {
                put("type", "boolean")
                put("description", "also capture request/response bodies (default false; bodies are capped by maxBodyBytes)")
            })
        }, required = listOf("pattern", "action"))
    },
    execute = { input ->
        val pattern = input.jsonObject["pattern"]?.jsonPrimitive?.contentOrNull?.trim()
        val actionName = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val typeName = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        val status = input.jsonObject["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200
        val captureBody = input.jsonObject["capture_body"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() == true

        val hookType = when (typeName) {
            null, "", "fetch" -> HookType.FETCH
            "xhr" -> HookType.XHR
            "websocket", "ws" -> HookType.WEBSOCKET
            "function" -> HookType.FUNCTION
            "method" -> HookType.METHOD
            "property" -> HookType.PROPERTY
            else -> HookType.FETCH
        }
        val action = when (actionName) {
            "log" -> HookAction.Log()
            "block" -> HookAction.Block()
            "redirect" -> value?.takeIf { it.isNotBlank() }?.let { HookAction.Redirect(it) }
            "mock" -> HookAction.Mock(
                status = status,
                body = value.orEmpty(),
                headers = mapOf("content-type" to "application/json"),
            )
            else -> null
        }

        val out = if (pattern.isNullOrBlank()) {
            missingArgEnvelope("pattern", "pattern is required")
        } else if (action == null) {
            missingArgEnvelope("action", "action must be one of log / block / redirect / mock" +
                if (actionName == "redirect") "（redirect 需要 value）" else "")
        } else {
            val store = hookStore()
            val rule = HookRule(
                id = "rikka-" + System.currentTimeMillis(),
                type = hookType,
                target = pattern,
                scopeTabId = HOOK_TAB_ID,
                actions = listOf(action),
                captureBody = captureBody,
            )
            // 之前不校验直接装：redirect/mock 用在 function 类型上、glob 写错等
            // 都会静默变成"装了但永不命中"。现在把校验结果直接退回给模型。
            val invalid = rule.validate()
            if (invalid != null) {
                buildJsonObject {
                    put("error", invalid)
                    put("hint", "fetch/xhr 支持 log/block/redirect/mock；" +
                        "function/method 支持 log/replace；property 支持 log")
                }
            } else {
                store.install(rule)
                val applied = pushHookRules()
                buildJsonObject {
                    put("id", rule.id)
                    put("type", hookType.name.lowercase())
                    put("target", pattern)
                    put("action", actionLabel(action))
                    put("applied", applied)
                    if (!applied) put("note", "规则已保存，浏览器下次加载页面时生效")
                }
            }
        }
        textPart(out)
    },
)

fun browserHookListTool(): Tool = Tool(
    name = BrowserToolDefaults.HOOK_LIST,
    description = "List installed hook rules with hit counts. {count, rules:[{id,type,target,action,enabled,hits}]}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val store = runCatching { hookStore() }.getOrNull()
        val out = if (store == null) {
            buildJsonObject { put("error", "hook store unavailable") }
        } else {
            val rules = store.list()
            buildJsonObject {
                put("count", rules.size)
                putJsonArray("rules") {
                    rules.forEach { rule ->
                        add(buildJsonObject {
                            put("id", rule.id)
                            put("type", rule.type.name.lowercase())
                            put("target", rule.target)
                            put("action", rule.actions.joinToString(",") { actionLabel(it) })
                            put("enabled", rule.enabled)
                            put("hits", store.hitCount(rule.id))
                        })
                    }
                }
            }
        }
        textPart(out)
    },
)

fun browserHookRemoveTool(): Tool = Tool(
    name = BrowserToolDefaults.HOOK_REMOVE,
    description = "Remove a hook rule by id. Returns {removed, applied}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "rule id from browser_hook_list")
            })
        }, required = listOf("id"))
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()
        val out = if (id.isNullOrBlank()) {
            missingArgEnvelope("id", "id is required")
        } else {
            val store = runCatching { hookStore() }.getOrNull()
            val removed = store?.remove(id) == true
            val applied = if (removed) pushHookRules() else false
            buildJsonObject {
                put("id", id)
                put("removed", removed)
                put("applied", applied)
            }
        }
        textPart(out)
    },
)

fun browserHookHitsTool(): Tool = Tool(
    name = BrowserToolDefaults.HOOK_HITS,
    description = "Recent hook hits (which rule matched which request/call, when). " +
        "{count, latest, hits:[{hookId,type,target,phase,summary,at}]}. " +
        "Empty means no rule matched yet — check browser_hook_list that targets line up with real requests. " +
        "To poll for new hits, pass since=<latest from the previous call>: the reply then contains only " +
        "entries newer than that, which also keeps the arguments changing so the repeat-call guard " +
        "does not misfire on legitimate polling.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "max entries, default 30")
            })
            put("since", buildJsonObject {
                put("type", "integer")
                put("description", "only hits with at > since (epoch millis); pass previous reply's `latest`")
            })
        })
    },
    execute = { input ->
        val limit = input.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 200) ?: 30
        val since = input.jsonObject["since"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val bus = runCatching { hookEventBus() }.getOrNull()
        val out = if (bus == null) {
            buildJsonObject { put("error", "event bus unavailable") }
        } else {
            val all = bus.hookHits.value
            val selected = if (since != null) all.filter { it.at > since } else all
            val hits = selected.takeLast(limit)
            buildJsonObject {
                put("count", hits.size)
                // 下次轮询把 latest 原样传回来即可增量取
                put("latest", all.lastOrNull()?.at ?: 0L)
                if (since != null) put("since", since)
                putJsonArray("hits") {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("hookId", hit.hookId)
                            put("type", hit.type.name.lowercase())
                            put("target", hit.target)
                            put("phase", hit.phase)
                            put("summary", hit.summary)
                            put("at", hit.at)
                        })
                    }
                }
            }
        }
        textPart(out)
    },
)

// ---------------------------------------------------------------------------------------------
// CDP 断点调试（移植自太墟 cdp 层：CdpManager / CdpTabConnection / CdpDebugController）
// ---------------------------------------------------------------------------------------------

private fun cdpManager(): CdpManager =
    org.koin.java.KoinJavaComponent.getKoin().get()

private fun cdpDebugController(): CdpDebugController? =
    runCatching { cdpManager().connectionOf(HOOK_TAB_ID)?.debug }.getOrNull()

private fun breakpointJson(bp: DebugBreakpoint): JsonObject = buildJsonObject {
    put("id", bp.id)
    put("url", bp.url)
    put("line", bp.lineNumber)
    put("column", bp.columnNumber)
    if (bp.condition.isNotBlank()) put("condition", bp.condition)
}

fun browserDebugAttachTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_ATTACH,
    description = "Attach the Chrome DevTools Protocol session to the active WebView tab. " +
        "Enables real breakpoints and engine-level Fetch interception (covers Service/Web Workers). " +
        "Idempotent: attaching twice returns already=true. Breakpoints set before a detach are replayed on re-attach. " +
        "Call browser_debug_detach when done; a paused page stays frozen until browser_debug_resume. " +
        "IMPORTANT — DEGRADE, DO NOT RETRY: if this returns an error containing 'Try again', 'timeout' or " +
        "'对端不响应', the device's WebView devtools channel is not serving requests (seen on some ROMs; " +
        "it is a device limitation, not a bug you can work around). Do NOT call it a second time. " +
        "Switch to browser_hook_create immediately — log/mock/redirect/block plus captureBody give you " +
        "request body, response body, headers and status without CDP.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            runCatching { cdpManager().attach(HOOK_TAB_ID) }.fold(
                onSuccess = { result ->
                    buildJsonObject {
                        when (result) {
                            is CdpManager.AttachResult.Attached -> {
                                put("attached", true)
                                put("url", result.targetUrl)
                            }

                            is CdpManager.AttachResult.AlreadyAttached -> {
                                put("attached", true)
                                put("already", true)
                                put("url", result.targetUrl)
                            }
                        }
                    }
                },
                onFailure = { e ->
                    buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                },
            )
        } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_ATTACH)
        textPart(out)
    },
)

fun browserDebugStatusTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_STATUS,
    description = "Report CDP state: which tabs are attached, whether the page is paused at a breakpoint, " +
        "breakpoint count and worker session count. Use it to check whether a debug session is still alive.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val manager = runCatching { cdpManager() }.getOrNull()
        val out = if (manager == null) {
            buildJsonObject { put("error", "cdp manager unavailable") }
        } else {
            val status = manager.status()
            buildJsonObject {
                put("devToolsSocketActive", status.devToolsSocketActive)
                put("attachedTabs", status.attachedTabs.size)
                putJsonArray("tabs") {
                    status.attachedTabs.forEach { tab ->
                        add(buildJsonObject {
                            put("tabId", tab.tabId)
                            put("url", tab.targetUrl)
                            put("paused", tab.paused)
                            put("breakpoints", tab.breakpoints)
                            put("workers", tab.workers)
                        })
                    }
                }
            }
        }
        textPart(out)
    },
)

fun browserDebugSetBreakpointTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_SET_BREAKPOINT,
    description = "Set a real CDP breakpoint on a script URL. line/column are 0-based. " +
        "url matches by suffix (pass the script path/name, e.g. app.js or /static/main.js). " +
        "condition is an optional JS expression; the breakpoint only fires when it is truthy. " +
        "Requires browser_debug_attach first. After the page pauses, use browser_debug_state to read call frames, " +
        "then browser_debug_resume or browser_debug_step.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "script URL or its suffix, e.g. app.js")
            })
            put("line", buildJsonObject {
                put("type", "integer")
                put("description", "0-based line number")
            })
            put("column", buildJsonObject {
                put("type", "integer")
                put("description", "0-based column, default 0")
            })
            put("condition", buildJsonObject {
                put("type", "string")
                put("description", "optional JS expression guard")
            })
        }, required = listOf("url", "line"))
    },
    execute = { input ->
        val url = input.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.trim()
        val line = input.jsonObject["line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val column = input.jsonObject["column"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val condition = input.jsonObject["condition"]?.jsonPrimitive?.contentOrNull
        val debug = cdpDebugController()
        val out = when {
            url.isNullOrBlank() -> missingArgEnvelope("url", "url is required")
            line == null -> missingArgEnvelope("line", "line is required (0-based)")
            debug == null -> buildJsonObject {
                put("error", "cdp 未 attach")
                put("hint", "先调 browser_debug_attach")
            }
            else -> withTimeoutOrNull(toolTimeoutMs) {
                runCatching { debug.setBreakpoint(url, line, column, condition) }.fold(
                    onSuccess = { bp -> breakpointJson(bp) },
                    onFailure = { e ->
                        buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    },
                )
            } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_SET_BREAKPOINT)
        }
        textPart(out)
    },
)

fun browserDebugListBreakpointsTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_LIST_BREAKPOINTS,
    description = "List CDP breakpoints of the attached tab: {count, breakpoints:[{id,url,line,column,condition}]}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val debug = cdpDebugController()
        val out = if (debug == null) {
            buildJsonObject {
                put("error", "cdp 未 attach")
                put("hint", "先调 browser_debug_attach")
            }
        } else {
            val list = debug.breakpoints()
            buildJsonObject {
                put("count", list.size)
                putJsonArray("breakpoints") { list.forEach { add(breakpointJson(it)) } }
            }
        }
        textPart(out)
    },
)

fun browserDebugRemoveBreakpointTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_REMOVE_BREAKPOINT,
    description = "Remove a CDP breakpoint by id. Returns {removed}.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("id", buildJsonObject {
                put("type", "string")
                put("description", "breakpoint id from browser_debug_list_breakpoints")
            })
        }, required = listOf("id"))
    },
    execute = { input ->
        val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()
        val debug = cdpDebugController()
        val out = when {
            id.isNullOrBlank() -> missingArgEnvelope("id", "id is required")
            debug == null -> buildJsonObject { put("error", "cdp 未 attach") }
            else -> withTimeoutOrNull(toolTimeoutMs) {
                runCatching { debug.removeBreakpoint(id) }.fold(
                    onSuccess = { buildJsonObject { put("id", id); put("removed", it) } },
                    onFailure = { e ->
                        buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    },
                )
            } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_REMOVE_BREAKPOINT)
        }
        textPart(out)
    },
)

fun browserDebugStateTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_STATE,
    description = "Read the paused state: {paused, reason, hitBreakpoints, callFrames:[{callFrameId,functionName,url,line,column,scopes}]}. " +
        "Empty when the page is running. A frozen page must be released with browser_debug_resume. " +
        "If browser_debug_attach never succeeded on this device, do not call this — use browser_network_list " +
        "(captured traffic) and browser_hook_hits (rule matches) instead; they carry the same " +
        "request/response evidence without CDP.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val bus = runCatching { hookEventBus() }.getOrNull()
        val state = bus?.paused?.value?.get(HOOK_TAB_ID)
        val out = if (state == null) {
            buildJsonObject { put("paused", false) }
        } else {
            buildJsonObject {
                put("paused", true)
                put("reason", state.reason)
                put("hitBreakpoints", state.hitBreakpoints.joinToString(","))
                putJsonArray("callFrames") {
                    state.callFrames.forEach { frame ->
                        add(buildJsonObject {
                            put("callFrameId", frame.callFrameId)
                            put("functionName", frame.functionName)
                            put("url", frame.url)
                            put("line", frame.lineNumber)
                            put("column", frame.columnNumber)
                            putJsonArray("scopes") {
                                frame.scopes.forEach { scope ->
                                    add(buildJsonObject {
                                        put("type", scope.type)
                                        put("name", scope.name)
                                        put("objectId", scope.objectId)
                                    })
                                }
                            }
                        })
                    }
                }
            }
        }
        textPart(out)
    },
)

fun browserDebugResumeTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_RESUME,
    description = "Resume a paused page. MUST be called when you are done inspecting a breakpoint, " +
        "otherwise the page stays frozen forever. Returns {resumed}.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val debug = cdpDebugController()
        val out = if (debug == null) {
            buildJsonObject { put("error", "cdp 未 attach") }
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                runCatching { debug.resume() }.fold(
                    onSuccess = { buildJsonObject { put("resumed", it) } },
                    onFailure = { e ->
                        buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    },
                )
            } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_RESUME)
        }
        textPart(out)
    },
)

fun browserDebugStepTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_STEP,
    description = "Step while paused: over (next line) / into (enter callee) / out (return to caller). " +
        "After stepping the page stays paused; read browser_debug_state then resume.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "over | into | out (default over)")
            })
        })
    },
    execute = { input ->
        val mode = input.jsonObject["mode"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "over"
        val step = when (mode) {
            "into", "in" -> DebugStep.INTO
            "out" -> DebugStep.OUT
            else -> DebugStep.OVER
        }
        val debug = cdpDebugController()
        val out = if (debug == null) {
            buildJsonObject { put("error", "cdp 未 attach") }
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                runCatching { debug.step(step) }.fold(
                    onSuccess = { buildJsonObject { put("stepped", it); put("mode", mode) } },
                    onFailure = { e ->
                        buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                    },
                )
            } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_STEP)
        }
        textPart(out)
    },
)

fun browserDebugDetachTool(): Tool = Tool(
    name = BrowserToolDefaults.DEBUG_DETACH,
    description = "Detach the CDP session(s). Resumes a paused page first, keeps the breakpoint list for replay on next attach. " +
        "Returns {detached} = number of closed sessions.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val out = withTimeoutOrNull(toolTimeoutMs) {
            runCatching { cdpManager().detach(null) }.fold(
                onSuccess = { buildJsonObject { put("detached", it) } },
                onFailure = { e ->
                    buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
                },
            )
        } ?: timeoutEnvelope(BrowserToolDefaults.DEBUG_DETACH)
        textPart(out)
    },
)

fun browserScreenshotTool(context: Context): Tool = Tool(
    name = BrowserToolDefaults.SCREENSHOT,
    description = "Capture the visible viewport of the browser as a PNG vision attachment. Use browser_get_text first if you only need the page's text — screenshots cost vision tokens. full_page=true is best-effort and currently captures the viewport only (viewport_only:true in the response).$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("full_page", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, attempt to capture the entire scroll height (currently no-op; viewport-only)")
                })
            },
        )
    },
    execute = { input ->
        val fullPage = input.jsonObject["full_page"]?.jsonPrimitive?.booleanOrNull == true
        val parts = mutableListOf<UIMessagePart>()
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                trackJsonAction(BrowserAiActionKind.SCREENSHOT, null) {
                    val (path, w, h) = withContext(Dispatchers.Main) {
                        val width = webView.width.coerceAtLeast(1)
                        val height = webView.height.coerceAtLeast(1).coerceAtMost(MAX_SCREENSHOT_HEIGHT_PX)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        webView.draw(canvas)
                        val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                        val out = File(cacheDir, "screenshot-${System.currentTimeMillis()}.png")
                        // Recycle unconditionally in a finally block so the bitmap is freed
                        // exactly once regardless of whether compress() succeeds or throws.
                        // The prior pattern called recycle() in onFailure AND then again
                        // unconditionally — a double-recycle causes IllegalStateException.
                        try {
                            FileOutputStream(out).use { os ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        Triple(out.absolutePath, width, height)
                    }
                    buildJsonObject {
                        put("success", true)
                        put("file_path", path)
                        put("width", w)
                        put("height", h)
                        if (fullPage) put("viewport_only", true)
                    }
                }
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.SCREENSHOT)
        out.jsonObject["file_path"]?.jsonPrimitive?.contentOrNull?.let { fp ->
            parts.add(UIMessagePart.Image(url = "file://$fp"))
        }
        parts.add(UIMessagePart.Text(out.toString()))
        parts
    },
)

fun browserGetTextTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_TEXT,
    description = "Returns the main article content via Readability.js by default, falling back to selector-based extraction if Readability fails. Pass extract_mode:'raw' for the unfiltered text. Pass selector (e.g. 'article', 'main', '.content') for explicit scoping — selectors override Readability. max_chars (default 8000) caps the result. Use this BEFORE screenshot if you only need text content. {text, truncated, extract_mode}.$TELEGRAM_HEADLESS_CUE",
    parameters = { getTextSchema(defaultMax = 8000) },
    execute = { input -> textPart(runGetText(input)) },
)

fun browserGetDomTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_DOM,
    description = "Extract a simplified outerHTML of a CSS selector (default 'body'). Strips <script>/<style>. Truncates at max_chars (default 4000). Use scoped selectors like 'article' / 'main' rather than 'body' for relevance — body usually includes nav and footer chrome that costs tokens without value. {html, truncated}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorAndMaxCharsSchema(defaultMax = 4000, required = false) },
    execute = { input -> textPart(runReadHelper(input, BrowserToolDefaults.GET_DOM, defaultMax = 4000) { selector, maxChars ->
        """(function(){
            try {
                var el = document.querySelector(${jsString(selector)});
                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                var clone = el.cloneNode(true);
                clone.querySelectorAll('script,style,noscript').forEach(function(n){n.remove();});
                var html = clone.outerHTML || '';
                var truncated = false;
                if (html.length > $maxChars) { html = html.substring(0, $maxChars); truncated = true; }
                return JSON.stringify({html:html, truncated:truncated});
            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
        })()"""
    }) },
)

fun browserGetLinksTool(): Tool = Tool(
    name = BrowserToolDefaults.GET_LINKS,
    description = "List up to 100 anchor links inside a CSS selector (default 'body') as {links:[{href, text}], count}. {error:'selector_not_found'} if the root selector doesn't match.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "Root selector to search inside (default 'body')")
            })
        })
    },
    execute = { input ->
        val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }) ?: "body"
        val out = withTimeoutOrNull(toolTimeoutMs) {
            BrowserControllerHandle.withController {
                val js = """(function(){
                    try {
                        var root = document.querySelector(${jsString(selector)});
                        if (!root) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                        var anchors = root.querySelectorAll('a[href]');
                        var out = [];
                        for (var i=0; i<anchors.length && out.length<100; i++) {
                            var a = anchors[i];
                            var href = a.href || '';
                            var text = (a.innerText || a.textContent || '').replace(/\s+/g,' ').trim();
                            if (text.length>200) text = text.substring(0,200);
                            out.push({href:href, text:text});
                        }
                        return JSON.stringify({links:out, count:out.length});
                    } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                })()"""
                parseJsResult(webView.evaluateJavascriptAsync(js))
            }
        } ?: timeoutEnvelope(BrowserToolDefaults.GET_LINKS)
        textPart(out)
    },
)

fun browserBackTool(): Tool = Tool(
    name = BrowserToolDefaults.BACK,
    description = "Navigate the browser one step back in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.BACK, forward = false)) },
)

fun browserForwardTool(): Tool = Tool(
    name = BrowserToolDefaults.FORWARD,
    description = "Navigate the browser one step forward in history. {success, current_url}.$TELEGRAM_HEADLESS_CUE",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = { textPart(runHistoryNav(BrowserToolDefaults.FORWARD, forward = true)) },
)

/** Valid values for browser_wait_for's `state` arg. `attached` preserves the original behavior. */
private val WAIT_FOR_STATES = setOf("attached", "detached", "visible", "hidden")

/**
 * Build the JS predicate browser_wait_for polls. Returns a self-invoking expression
 * that evaluates to the JS boolean `true` once the wait condition is satisfied.
 *
 *  - `state` decides what "satisfied" means for the element matching [selector]:
 *      attached  — at least one element matches (default; original behavior)
 *      detached  — no element matches
 *      visible   — a matching element is in the DOM AND rendered (offsetParent or a
 *                  non-zero client rect — covers position:fixed which has null offsetParent)
 *      hidden    — no matching element is visible (none in DOM, or all rendered hidden)
 *  - When [containsText] is non-null, the matched element ALSO has to contain that text
 *    (case-sensitive substring of innerText/textContent). For detached/hidden states the
 *    text constraint is ignored — "not there" can't also "contain text".
 *
 * Pure string builder so it stays unit-testable without a WebView.
 */
internal fun buildWaitForPredicate(selector: String, state: String, containsText: String?): String {
    val sel = jsString(selector)
    val txt = containsText?.let { jsString(it) }
    val textCheck = if (txt != null) {
        "function(el){var t=(el.innerText||el.textContent||'');return t.indexOf($txt)!==-1;}"
    } else {
        "function(){return true;}"
    }
    val visibleCheck = "function(el){" +
        "if(el.offsetParent!==null)return true;" +
        "var r=el.getClientRects();return r&&r.length>0;" +
        "}"
    return when (state) {
        "detached" -> "(function(){try{return document.querySelector($sel)===null;}catch(e){return false;}})()"
        "hidden" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i]))return false;}" +
            "return true;" +
            "}catch(e){return false;}})()"
        "visible" -> "(function(){try{" +
            "var els=document.querySelectorAll($sel);" +
            "var vis=$visibleCheck;var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(vis(els[i])&&hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
        else -> "(function(){try{" + // "attached" (default)
            "var els=document.querySelectorAll($sel);" +
            "var hasText=$textCheck;" +
            "for(var i=0;i<els.length;i++){if(hasText(els[i]))return true;}" +
            "return false;" +
            "}catch(e){return false;}})()"
    }
}

fun browserWaitForTool(): Tool = Tool(
    name = BrowserToolDefaults.WAIT_FOR,
    description = "Pause until a CSS selector reaches a target state. Polls every 200 ms up to timeout_ms (default 10_000). state is one of attached (default — element present in DOM), detached (element gone), visible (present AND rendered), hidden (none rendered). Optional contains_text waits until a matching element contains that text. {found, elapsed_ms}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type", "string")
                put("description", "CSS selector to wait for")
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Max wait in ms (default 10000, capped at the configured per-tool timeout)")
            })
            put("contains_text", buildJsonObject {
                put("type", "string")
                put("description", "Optional — wait until an element matching the selector contains this text (case-sensitive substring). Ignored for state=detached/hidden.")
            })
            put("state", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("attached"); add("detached"); add("visible"); add("hidden") })
                put("description", "Target state to wait for (default 'attached', the original presence-in-DOM behavior)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val rawState = input.jsonObject["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val out = when {
            selector == null ->
                missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
            rawState != null && rawState !in WAIT_FOR_STATES ->
                missingArgEnvelope("state", "state must be one of [attached, detached, visible, hidden]")
            else -> {
                val state = rawState ?: "attached"
                val containsText = input.jsonObject["contains_text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                // Cap the user-supplied timeout at our per-tool budget so the LLM can't ask for a
                // longer wait and starve every other tool call. The withTimeoutOrNull below would
                // catch this anyway, but capping here gives a clean envelope. The cap tracks the
                // user-configured per-tool timeout (Settings → Browser).
                val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .toLong()
                    .coerceIn(200L, toolTimeoutMs)
                withTimeoutOrNull(toolTimeoutMs) {
                    BrowserControllerHandle.withController {
                        val started = System.currentTimeMillis()
                        val deadline = started + timeoutMs
                        val js = buildWaitForPredicate(selector, state, containsText)
                        var found = false
                        while (System.currentTimeMillis() < deadline) {
                            val raw = webView.evaluateJavascriptAsync(js, 1_500L)
                            if (raw == "true") { found = true; break }
                            delay(200)
                        }
                        buildJsonObject {
                            put("found", found)
                            put("elapsed_ms", System.currentTimeMillis() - started)
                            put("state", state)
                            if (containsText != null) put("contains_text", containsText)
                        }
                    }
                } ?: timeoutEnvelope(BrowserToolDefaults.WAIT_FOR)
            }
        }
        textPart(out)
    },
)

// ---- Write tools --------------------------------------------------------------------------

fun browserClickTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK,
    description = "Click an element matching a CSS selector. Returns the diff between the page before and after the action by default ({added, removed, added_chars, removed_chars, truncated} truncated to 4000 chars total). Pass full:true to skip the diff and get post_click_url only — use when the click navigates to an entirely new page. Waits up to 8 s for readyState=complete.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector to click") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required and must be a non-empty CSS selector")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.CLICK, selector) {
                        withDiff(full) {
                            val js = """(function(){
                                try {
                                    var el = document.querySelector(${jsString(selector)});
                                    if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                    el.scrollIntoView({block:'center', inline:'center'});
                                    el.click();
                                    return JSON.stringify({clicked:true});
                                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                            })()"""
                            val beforeUrl = webView.url.orEmpty()
                            val raw = webView.evaluateJavascriptAsync(js)
                            val res = parseJsResult(raw)
                            if (res.containsKey("error")) return@withDiff res
                            // 点击后不一定立刻导航：只等 readyState 会撞上「旧页面仍 complete」，
                            // 回执写成 unchanged + 旧 URL，而页面几秒后其实已经跳走。
                            // 先观察 URL 是否变化，变了再等新页面加载完。
                            val navigated = webView.awaitActionSettled(beforeUrl)
                            buildJsonObject {
                                put("success", true)
                                put("post_click_url", webView.url.orEmpty())
                                // 明确告诉调用方这是导航型操作 —— 此时 diff 无意义，
                                // 判断是否生效要看 post_click_url / current_url
                                put("navigated", navigated)
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK)
        }
        // Pass 3: post-click screenshot stream in headless mode. Skipped on error envelopes
        // so we don't push a stale screenshot when the click never landed.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector")
        }
        textPart(out)
    },
)

/**
 * Build the JS payload browser_type dispatches. Setting `el.value = ...` directly (the
 * previous approach) is invisible to React (and other frameworks that patch the DOM
 * property): React installs its OWN setter on the *instance* to track "last known value"
 * so its synthetic onChange only fires when a value changes through a path React
 * observes. A direct assignment bypasses that tracker entirely, so React never sees the
 * write and the rendered input silently keeps its old value even though `el.value` (and
 * this tool's own success:true) says otherwise.
 *
 * Fix: fetch the *native* value setter off the element's prototype
 * (`HTMLInputElement.prototype` / `HTMLTextAreaElement.prototype` — whichever
 * `Object.getPrototypeOf(el)` resolves to) and invoke it directly, bypassing React's
 * instance-level override, then dispatch a real `input` event. Because the setter call
 * itself didn't go through React's tracker, React's own event handler (attached at the
 * document root) sees the DOM value change and fires onChange normally. Falls back to
 * plain `el.value = ...` when the prototype has no descriptor with a setter (custom
 * elements, exotic hosts) — same behavior as before this fix for those cases.
 *
 * Pure string builder so it stays unit-testable without a WebView, same pattern as
 * [buildWaitForPredicate].
 */
internal fun buildTypeScript(selector: String, text: String, clear: Boolean): String {
    val sel = jsString(selector)
    val txt = jsString(text)
    val clearFlag = if (clear) "true" else "false"
    return """(function(){
        try {
            var el = document.querySelector($sel);
            if (!el) return JSON.stringify({error:'selector_not_found', selector:$sel});
            el.focus();
            function setNativeValue(node, value) {
                var proto = Object.getPrototypeOf(node);
                var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                if (desc && typeof desc.set === 'function') {
                    desc.set.call(node, value);
                    return true;
                }
                return false;
            }
            if ('value' in el) {
                var next = ($clearFlag ? '' : (el.value || '')) + $txt;
                if (!setNativeValue(el, next)) { el.value = next; }
            } else if (el.isContentEditable) {
                if ($clearFlag) el.textContent = '';
                el.textContent = (el.textContent || '') + $txt;
            }
            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return JSON.stringify({typed:true});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
}

fun browserTypeTool(): Tool = Tool(
    name = BrowserToolDefaults.TYPE,
    description = "Type text into an input/textarea/contenteditable matching a CSS selector. Focuses, optionally clears, sets the value + dispatches an 'input' event so SPA frameworks observe the change. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the input") })
            put("text", buildJsonObject { put("type","string"); put("description","Text to type") })
            put("clear", buildJsonObject { put("type","boolean"); put("description","Clear the field first (default true)") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","text"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val text = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val clear = input.jsonObject["clear"]?.jsonPrimitive?.booleanOrNull ?: true
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            text == null -> missingArgEnvelope("text", "text is required (use empty string to clear)")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.TYPE, selector) {
                        withDiff(full) {
                            val js = buildTypeScript(selector, text, clear)
                            val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                            if (res.containsKey("error")) return@withDiff res
                            buildJsonObject { put("success", true) }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.TYPE)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Typed into $selector")
        }
        textPart(out)
    },
)

fun browserScrollTool(): Tool = Tool(
    name = BrowserToolDefaults.SCROLL,
    description = "Scroll the page in a direction (up/down/top/bottom). amount is in pixels (default 600, ignored for top/bottom). {success, scroll_y}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("direction", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("up"); add("down"); add("top"); add("bottom") })
            })
            put("amount", buildJsonObject { put("type","integer"); put("description","Scroll distance in px (default 600)") })
        }, required = listOf("direction"))
    },
    execute = { input ->
        val direction = input.jsonObject["direction"]?.jsonPrimitive?.contentOrNull
        val amount = input.jsonObject["amount"]?.jsonPrimitive?.intOrNull ?: 600
        val out = if (direction == null || direction !in setOf("up", "down", "top", "bottom")) {
            missingArgEnvelope("direction", "direction must be one of [up, down, top, bottom]")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.SCROLL, direction) {
                        val js = """(function(){
                            try {
                                switch (${jsString(direction)}) {
                                    case 'up': window.scrollBy(0, -$amount); break;
                                    case 'down': window.scrollBy(0, $amount); break;
                                    case 'top': window.scrollTo(0, 0); break;
                                    case 'bottom': window.scrollTo(0, document.body.scrollHeight); break;
                                }
                                return JSON.stringify({scroll_y: Math.round(window.scrollY)});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                        if (res.containsKey("error")) return@trackJsonAction res
                        buildJsonObject {
                            put("success", true)
                            put("scroll_y", res["scroll_y"]?.jsonPrimitive?.intOrNull ?: 0)
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SCROLL)
        }
        // Pass 3: browser_scroll is in WRITE_TOOLS and the TELEGRAM_HEADLESS_CUE describes
        // "screenshots stream after each state-changing action." Stream the post-scroll view so
        // the Telegram user can see the new viewport position (scroll is purely viewport movement
        // — the diff helper won't capture it since body.innerText doesn't change).
        if (out["success"]?.toString() == "true") {
            val scrollY = out["scroll_y"]?.jsonPrimitive?.intOrNull
            BrowserController.streamScreenshotIfHeadless(
                if (scrollY != null) "Scrolled to y=$scrollY" else "Scrolled"
            )
        }
        textPart(out)
    },
)

fun browserSubmitTool(): Tool = Tool(
    name = BrowserToolDefaults.SUBMIT,
    description = "Submit a form. If the selector is a <button type=submit> click it; otherwise locates the enclosing <form> and calls .submit(). Awaits the post-navigation readyState. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when submission navigates to a brand-new page). {success, post_submit_url, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = { selectorWithFullSchema("CSS selector of a submit button or any element inside the target form") },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val full = parseFullArg(input)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.SUBMIT, selector) {
                        withDiff(full) {
                            val js = """(function(){
                                try {
                                    var el = document.querySelector(${jsString(selector)});
                                    if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                    if (el.tagName === 'BUTTON' && (el.type === 'submit' || el.type === '')) {
                                        el.click();
                                        return JSON.stringify({submitted:true, via:'button_click'});
                                    }
                                    var form = el.closest('form');
                                    if (!form) return JSON.stringify({error:'no_enclosing_form'});
                                    if (typeof form.requestSubmit === 'function') form.requestSubmit();
                                    else form.submit();
                                    return JSON.stringify({submitted:true, via:'form_submit'});
                                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                            })()"""
                            val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                            if (res.containsKey("error")) return@withDiff res
                            webView.awaitReadyState(8_000L)
                            buildJsonObject {
                                put("success", true)
                                put("post_submit_url", webView.url.orEmpty())
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SUBMIT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Submitted $selector")
        }
        textPart(out)
    },
)

fun browserSelectTool(): Tool = Tool(
    name = BrowserToolDefaults.SELECT,
    description = "Set a <select> element's value. Dispatches 'change' so framework listeners fire. Returns the diff between the page before and after by default; pass full:true to skip the diff. {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject { put("type","string"); put("description","CSS selector of the <select>") })
            put("value", buildJsonObject { put("type","string"); put("description","The option value to set") })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("selector","value"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
        val full = parseFullArg(input)
        val out = when {
            selector == null -> missingArgEnvelope("selector", "selector is required")
            value == null -> missingArgEnvelope("value", "value is required")
            else -> withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.SELECT, selector) {
                        withDiff(full) {
                            val js = """(function(){
                                try {
                                    var el = document.querySelector(${jsString(selector)});
                                    if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                    if (el.tagName !== 'SELECT') return JSON.stringify({error:'not_a_select'});
                                    el.value = ${jsString(value)};
                                    el.dispatchEvent(new Event('change', {bubbles:true}));
                                    return JSON.stringify({selected:true});
                                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                            })()"""
                            val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                            if (res.containsKey("error")) return@withDiff res
                            buildJsonObject { put("success", true) }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.SELECT)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Selected $value in $selector")
        }
        textPart(out)
    },
)

fun browserPressKeyTool(): Tool = Tool(
    name = BrowserToolDefaults.PRESS_KEY,
    description = "Synthesize keydown + keyup events on the active element. Use KeyboardEvent.key values like 'Enter', 'Escape', 'ArrowDown', 'Tab'. Returns the diff between the page before and after by default; pass full:true to skip the diff (recommended when Enter triggers a navigation). {success, [diff]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("key", buildJsonObject {
                put("type","string")
                put("description","KeyboardEvent.key value (e.g. 'Enter', 'Escape', 'ArrowDown')")
            })
            put("full", buildJsonObject { put("type","boolean"); put("description","If true, return the action envelope without the page-text diff (default false)") })
        }, required = listOf("key"))
    },
    execute = { input ->
        // Cap at 32 chars — KeyboardEvent.key values like "ArrowDown" are short. A multi-KB
        // string here suggests model misuse; clamp before splicing into the JS payload.
        val key = input.jsonObject["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.take(32)
        val full = parseFullArg(input)
        val out = if (key == null) {
            missingArgEnvelope("key", "key is required (e.g. 'Enter', 'Escape')")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.KEY, key) {
                        withDiff(full) {
                            val js = """(function(){
                                try {
                                    var el = document.activeElement || document.body;
                                    var down = new KeyboardEvent('keydown', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                    var up = new KeyboardEvent('keyup', {key:${jsString(key)}, bubbles:true, cancelable:true});
                                    el.dispatchEvent(down);
                                    el.dispatchEvent(up);
                                    return JSON.stringify({pressed:true});
                                } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                            })()"""
                            val res = parseJsResult(webView.evaluateJavascriptAsync(js))
                            if (res.containsKey("error")) return@withDiff res
                            buildJsonObject { put("success", true) }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.PRESS_KEY)
        }
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Pressed $key")
        }
        textPart(out)
    },
)

fun browserEvalJsTool(): Tool = Tool(
    name = BrowserToolDefaults.EVAL_JS,
    description = "Run arbitrary JavaScript in the page and return its last expression. HARDLINE-checked: shell-shaped strings, document.cookie writes, eval/Function constructors, and string-form setTimeout are all blocked at the tool dispatcher BEFORE the JS executes. Always asks for approval; never eligible for 'Always Allow'.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("code", buildJsonObject {
                put("type","string")
                put("description","JavaScript to evaluate. The string returned by the WebView is the value of the last expression, JSON-encoded.")
            })
        }, required = listOf("code"))
    },
    execute = { input ->
        // HARDLINE has already run via GenerationHandler before we get here. The execute
        // body just dispatches the JS and forwards whatever the WebView returns. Any
        // pattern HARDLINE missed is a bug to fix in HardlineCommandGuard, not here.
        val code = input.jsonObject["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (code == null) {
            missingArgEnvelope("code", "code is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackAction(BrowserAiActionKind.JS, detail = null, ok = { !it.containsKey("error") }) {
                        // Unlike every other tool's JS payload, the LLM's `code` here runs
                        // unwrapped — no `(function(){try{...}catch(e){...}})()` shell — since
                        // eval_js's whole point is running arbitrary script verbatim.
                        // evaluateJavascriptAsync already catches a throw from the
                        // evaluateJavascript() call itself, but wrap the rest of the dispatch
                        // (clipping) too so ANY unexpected failure here reports an honest
                        // {error:"eval_dispatch_failed"} envelope instead of propagating
                        // uncaught and surfacing as an opaque tool_failed upstream.
                        runCatching {
                            val raw = webView.evaluateJavascriptAsync(code, toolTimeoutMs - 1_000L)
                            // Clamp the raw result before it enters the envelope. evaluateJavascript
                            // returns whatever the page's last expression serialised to — a model that
                            // evals e.g. `document.body.outerHTML` can dump megabytes into the turn.
                            // Reuse the 64 KB cap the read tools (runGetText / runReadHelper) apply.
                            val (clipped, truncated) = clipText(raw ?: "null", EVAL_JS_MAX_RESULT_CHARS)
                            buildJsonObject {
                                put("result", clipped)
                                if (truncated) put("truncated", true)
                            }
                        }.getOrElse {
                            // Don't swallow cancellation: a withTimeoutOrNull timeout (or scope
                            // cancellation) throws CancellationException from inside this block, and
                            // catching it here would let this normal-looking envelope stand in for
                            // the timeout instead of letting withTimeoutOrNull return null.
                            if (it is CancellationException) throw it
                            android.util.Log.w("BrowserTools", "browser_eval_js: dispatch threw", it)
                            buildJsonObject {
                                put("error", "eval_dispatch_failed")
                                put("detail", (it.message ?: it.javaClass.simpleName).take(200))
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.EVAL_JS)
        }
        // Pass 3: browser_eval_js is in WRITE_TOOLS — it CAN mutate page state (e.g.
        // click via JS, modify the DOM, submit a form programmatically). Stream a screenshot
        // so the Telegram user sees the page after the script ran, consistent with every
        // other write tool. Only stream on success — error envelopes (timeout, missing_code,
        // browser_not_open) all carry an "error" key so we use that as the gate.
        if (!out.containsKey("error")) {
            BrowserController.streamScreenshotIfHeadless("Ran JS")
        }
        textPart(out)
    },
)

/**
 * Token-cost optimisation pass — composite click+read tool. Cuts the dominant
 * "click → wait → get_text" three-call sequence to one round trip. The trade is
 * a slightly heavier per-call envelope (URL + title + text/diff) which is
 * still cheaper than three separate calls' worth of model thinking-tokens.
 *
 * extract_mode: "diff" (default), "auto", "readability", "raw".
 *   - "diff" → snapshot text before/after, return {diff: ...}.
 *   - "auto"/"readability"/"raw" → snapshot URL+title, click, await readyState,
 *     extract text per get_text rules, return {text, page_title}.
 *
 * max_chars caps either the diff side OR the extracted text. The cap protects
 * against an LLM asking for a megabyte of text on a long-form page.
 */
fun browserClickAndReadTool(): Tool = Tool(
    name = BrowserToolDefaults.CLICK_AND_READ,
    description = "One-shot click + read in a single round trip. Click an element, await readyState, then return either the diff (default extract_mode:'diff') or the extracted text (auto/readability/raw — same semantics as browser_get_text). Use this instead of browser_click + browser_get_text when you want to minimise tokens. {success, post_click_url, page_title, [diff], [text]}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("selector", buildJsonObject {
                put("type","string")
                put("description","CSS selector to click")
            })
            put("extract_mode", buildJsonObject {
                put("type","string")
                put("enum", buildJsonArray { add("diff"); add("auto"); add("readability"); add("raw") })
                put("description","diff (default) returns a before/after diff; auto/readability/raw return the extracted page text")
            })
            put("max_chars", buildJsonObject {
                put("type","integer")
                put("description","Caps the returned text length (default 4000)")
            })
        }, required = listOf("selector"))
    },
    execute = { input ->
        val selector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?.takeIf { it in setOf("diff", "auto", "readability", "raw") } ?: "diff"
        val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 4000)
            .coerceIn(100, 64 * 1024)
        val out = if (selector == null) {
            missingArgEnvelope("selector", "selector is required")
        } else {
            withTimeoutOrNull(toolTimeoutMs) {
                BrowserControllerHandle.withController {
                    trackJsonAction(BrowserAiActionKind.READ, selector) {
                        val before = if (mode == "diff") captureBodyText() else ""
                        val titleBefore = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                        val clickJs = """(function(){
                            try {
                                var el = document.querySelector(${jsString(selector)});
                                if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
                                el.scrollIntoView({block:'center', inline:'center'});
                                el.click();
                                return JSON.stringify({clicked:true});
                            } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                        })()"""
                        val clickRes = parseJsResult(webView.evaluateJavascriptAsync(clickJs))
                        if (clickRes.containsKey("error")) return@trackJsonAction clickRes
                        webView.awaitReadyState(8_000L)
                        val postUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
                        val postTitle = withContext(Dispatchers.Main) { webView.title.orEmpty() }
                        @Suppress("UNUSED_VARIABLE")
                        val unused = titleBefore // documents that we considered showing diff of titles
                        when (mode) {
                            "diff" -> {
                                val after = captureBodyText()
                                buildJsonObject {
                                    put("success", true)
                                    put("post_click_url", postUrl)
                                    put("page_title", postTitle)
                                    put("diff", BrowserDiffHelper.computeDiff(before, after))
                                }
                            }
                            else -> {
                                val text = when (mode) {
                                    "readability" -> webView.runReadability()
                                    "auto" -> webView.runReadability()?.takeIf { it.length >= READABILITY_MIN_CHARS }
                                    else -> null
                                }
                                val (resolved, extractMode) = if (!text.isNullOrEmpty()) {
                                    text to "readability"
                                } else if (mode == "readability") {
                                    // Forced mode + null result → surface the error envelope.
                                    return@trackJsonAction buildJsonObject {
                                        put("success", false)
                                        put("post_click_url", postUrl)
                                        put("page_title", postTitle)
                                        put("error", "readability_failed")
                                    }
                                } else {
                                    // Fall back to selector-based body innerText.
                                    val rawJs = """(function(){
                                        try {
                                            var t = (document.body.innerText || document.body.textContent || '').replace(/\s+/g,' ').trim();
                                            return JSON.stringify({text:t});
                                        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                                    })()"""
                                    val rawRes = parseJsResult(webView.evaluateJavascriptAsync(rawJs))
                                    val rawText = rawRes["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    rawText to (if (mode == "auto") "raw_fallback" else "raw")
                                }
                                val (clipped, truncated) = clipText(resolved, maxChars)
                                buildJsonObject {
                                    put("success", true)
                                    put("post_click_url", postUrl)
                                    put("page_title", postTitle)
                                    put("text", clipped)
                                    put("truncated", truncated)
                                    put("extract_mode", extractMode)
                                }
                            }
                        }
                    }
                }
            } ?: timeoutEnvelope(BrowserToolDefaults.CLICK_AND_READ)
        }
        // Pass 3 parity: stream a screenshot in headless mode on success, same as
        // plain browser_click. The diff envelope still flows to the LLM via textPart.
        if (out["success"]?.toString() == "true") {
            BrowserController.streamScreenshotIfHeadless("Clicked $selector (and read)")
        }
        textPart(out)
    },
)

// ---- Loop control --------------------------------------------------------------------------

fun browserDoneTool(invocationContext: ToolInvocationContext? = null): Tool = Tool(
    name = BrowserToolDefaults.DONE,
    description = "Signal that the AI has finished its current browser task. Clears the per-task 5-minute timer so the next browser_open starts fresh. The browser session ITSELF stays alive — subsequent turns can navigate, click, scroll without re-opening; only `/new` (Telegram) or the in-app reset closes it. result_url is optional; pass the page URL the user should look at if any. {success}.$TELEGRAM_HEADLESS_CUE",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("summary", buildJsonObject {
                put("type","string")
                put("description","One-sentence summary of what was accomplished")
            })
            put("result_url", buildJsonObject {
                put("type","string")
                put("description","Optional URL the user should look at")
            })
        }, required = listOf("summary"))
    },
    execute = { input ->
        val summary = input.jsonObject["summary"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val out = if (summary == null) {
            missingArgEnvelope("summary", "summary is required")
        } else {
            BrowserController.recordAction(BrowserAiActionKind.DONE, summary)
            BrowserController.clearTaskWindow()
            // Pass 3 design originally released the headless WebView here. Live-test feedback
            // (2026-05-08): users want the session to persist across LLM turns so a follow-up
            // "click the next link" doesn't have to re-open from scratch — that broke the
            // page state, cookies-in-flight, and the read screenshots stayed white because
            // we paid the page-load tax twice. browser_done now ONLY clears the per-task
            // 5-minute timer; the session stays alive until `/new` (TelegramBotService.handleResetCommand)
            // or the calling FGS dies. Foreground mode behaves identically — Activity keeps
            // running as before.
            buildJsonObject { put("success", true) }
        }
        textPart(out)
    },
)

// ---- Internal helpers ---------------------------------------------------------------------

private fun selectorOnlySchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    },
    required = listOf("selector"),
)

/**
 * selector + optional full flag — used by the state-changing tools (click / submit)
 * that gain the diff-after-action toggle. selector remains required; `full` defaults
 * to false (i.e. return diff). The naming matches the spec verbatim.
 */
private fun selectorWithFullSchema(description: String): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", description)
        })
        put("full", buildJsonObject {
            put("type", "boolean")
            put("description", "If true, return the action envelope without the page-text diff (default false)")
        })
    },
    required = listOf("selector"),
)

private fun selectorAndMaxCharsSchema(defaultMax: Int, required: Boolean): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "CSS selector (default 'body')")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
    },
    required = if (required) listOf("selector") else null,
)

/**
 * Schema for browser_get_text. Adds the [extract_mode] enum (auto / readability /
 * raw) to the standard selector + max_chars surface. Defaults are documented inline
 * so the LLM doesn't need to hunt through the spec to know the fallback behaviour.
 */
private fun getTextSchema(defaultMax: Int): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("selector", buildJsonObject {
            put("type", "string")
            put("description", "Optional CSS selector — when set, overrides Readability and reads the selector's innerText directly")
        })
        put("max_chars", buildJsonObject {
            put("type", "integer")
            put("description", "Truncation cap (default $defaultMax)")
        })
        put("extract_mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("auto"); add("readability"); add("raw") })
            put("description", "auto (default) tries Readability then falls back; readability forces it; raw uses selector-based innerText")
        })
    },
)


/**
 * Snapshot the page's `document.body.innerText` for the diff-after-action path.
 * Whitespace is collapsed so that incidental layout reflows (e.g. an extra newline
 * inserted by a CSS animation that just landed) don't show up as "added" lines in
 * the diff. Returns the empty string on any JS failure — the diff helper will then
 * treat an empty before-snapshot as "everything is new", which is the conservative
 * call when we can't tell what was there.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.captureBodyText(): String {
    val raw = webView.evaluateJavascriptAsync(
        "(function(){try{return JSON.stringify(document.body.innerText||'');}catch(e){return JSON.stringify('');}})()",
        4_000L,
    ) ?: return ""
    return runCatching {
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonPrimitive.contentOrNull.orEmpty()
    }.getOrElse { "" }
}

/**
 * Token-cost optimisation pass — wrap a state-changing tool's action with a
 * before/after text snapshot so the LLM gets a diff envelope instead of a full
 * page re-read. Returns:
 *  - The action's own envelope unchanged when [full] is true (legacy path).
 *  - The action's envelope merged with `{ "diff": {...} }` when [full] is false.
 *  - An error envelope if the action returned one — diff is skipped on error so we
 *    don't push a stale snapshot when nothing changed because the action failed.
 *
 * The action is responsible for awaiting readyState if it triggers navigation; we
 * snapshot AFTER the action returns to ensure we read the post-action page.
 */
private suspend fun BrowserControllerHandle.WithControllerScope.withDiff(
    full: Boolean,
    action: suspend BrowserControllerHandle.WithControllerScope.() -> JsonObject,
): JsonObject {
    if (full) return action()
    val before = captureBodyText()
    val result = action()
    if (result.containsKey("error")) return result
    val after = captureBodyText()
    return buildJsonObject {
        result.forEach { (k, v) -> put(k, v) }
        put("diff", BrowserDiffHelper.computeDiff(before, after))
    }
}

/**
 * Read the optional `full` arg uniformly across the state-changing tools. Default
 * false → diff path; true → preserve legacy envelope (post_*_url only). The arg
 * name matches the spec verbatim so the LLM has one concept to learn.
 */
private fun parseFullArg(input: kotlinx.serialization.json.JsonElement): Boolean =
    input.jsonObject["full"]?.jsonPrimitive?.booleanOrNull == true

/**
 * Parse the raw string evaluateJavascript returned. Our JS helpers always return a
 * JSON.stringify(...) result, so the raw value is itself a JSON-encoded string (i.e.
 * a literal "..." with internal escapes). Unwrap once to get the real JSON object.
 *
 * Falls back to {error:'js_no_result'} on null and {error:'js_parse_failed'} on a
 * value that can't be parsed — both surface to the LLM cleanly without throwing.
 */
private fun parseJsResult(raw: String?): JsonObject {
    if (raw == null) return buildJsonObject { put("error", "js_no_result") }
    return runCatching {
        // evaluateJavascript wraps a JS string return value in JSON-quoted form, so
        // raw is "\"{...}\"" — parse the outer quoted string into a Kotlin string,
        // then parse that string as JSON.
        val outer = Json.parseToJsonElement(raw)
        val inner = if (outer is JsonPrimitive && outer.isString) outer.contentOrNull.orEmpty() else outer.toString()
        Json.parseToJsonElement(inner).jsonObject
    }.getOrElse { buildJsonObject { put("error", "js_parse_failed"); put("raw", raw) } }
}

/**
 * Shared body for browser_get_text / browser_get_dom: same selector+max_chars schema,
 * different JS body. The [jsBuilder] returns the JS payload; the helper handles the
 * round-trip + envelope + timeout.
 */
private suspend fun runReadHelper(
    input: kotlinx.serialization.json.JsonElement,
    toolName: String,
    defaultMax: Int,
    jsBuilder: (selector: String, maxChars: Int) -> String,
): JsonObject {
    val selector = (input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }) ?: "body"
    // Clamp max_chars at 100 (anything smaller is unhelpful) and 64 KB (so a runaway
    // model can't tell us to grab a megabyte of HTML).
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: defaultMax)
        .coerceIn(100, 64 * 1024)
    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            parseJsResult(webView.evaluateJavascriptAsync(jsBuilder(selector, maxChars)))
        }
    } ?: timeoutEnvelope(toolName)
}

/**
 * Token-cost optimisation pass — browser_get_text body. Resolves [extract_mode] +
 * [selector] precedence:
 *  - Explicit `selector` arg → skip Readability and use selector-based innerText
 *    (the user knows what they want; we trust the model).
 *  - `extract_mode = "raw"` → selector-based innerText against `body` (current
 *    pre-pass behaviour).
 *  - `extract_mode = "readability"` → force Readability; surface
 *    `{error:"readability_failed"}` if it returns null.
 *  - `extract_mode = "auto"` (default) → try Readability; fall back to
 *    selector-based innerText if it returns null OR less than 200 chars (a
 *    too-short article is often a junk extraction — better to fall back).
 */
private const val READABILITY_MIN_CHARS = 200

private suspend fun runGetText(input: kotlinx.serialization.json.JsonElement): JsonObject {
    val explicitSelector = input.jsonObject["selector"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val maxChars = (input.jsonObject["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000)
        .coerceIn(100, 64 * 1024)
    val mode = input.jsonObject["extract_mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
        ?.takeIf { it in setOf("auto", "readability", "raw") } ?: "auto"

    return withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            // Selector arg trumps everything — the model is being explicit, honour it.
            if (explicitSelector != null) {
                return@withController runRawText(explicitSelector, maxChars, mode = "raw_selector")
            }
            when (mode) {
                "raw" -> runRawText("body", maxChars, mode = "raw")
                "readability" -> {
                    val text = webView.runReadability()
                    if (text.isNullOrEmpty()) {
                        buildJsonObject {
                            put("error", "readability_failed")
                            put("recovery", "Try extract_mode:'auto' or pass a specific selector")
                        }
                    } else {
                        buildJsonObject {
                            val (clipped, truncated) = clipText(text, maxChars)
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    }
                }
                else -> {
                    // auto: Readability first, then selector fallback
                    val text = webView.runReadability()
                    if (!text.isNullOrEmpty() && text.length >= READABILITY_MIN_CHARS) {
                        val (clipped, truncated) = clipText(text, maxChars)
                        buildJsonObject {
                            put("text", clipped)
                            put("truncated", truncated)
                            put("extract_mode", "readability")
                        }
                    } else {
                        runRawText("body", maxChars, mode = "raw_fallback")
                    }
                }
            }
        }
    } ?: timeoutEnvelope(BrowserToolDefaults.GET_TEXT)
}

private suspend fun BrowserControllerHandle.WithControllerScope.runRawText(
    selector: String,
    maxChars: Int,
    mode: String,
): JsonObject {
    val js = """(function(){
        try {
            var el = document.querySelector(${jsString(selector)});
            if (!el) return JSON.stringify({error:'selector_not_found', selector:${jsString(selector)}});
            var t = (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim();
            var truncated = false;
            if (t.length > $maxChars) { t = t.substring(0, $maxChars); truncated = true; }
            return JSON.stringify({text:t, truncated:truncated});
        } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
    })()"""
    val res = parseJsResult(webView.evaluateJavascriptAsync(js))
    return if (res.containsKey("error")) res else buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("extract_mode", mode)
    }
}

private fun clipText(text: String, maxChars: Int): Pair<String, Boolean> =
    if (text.length <= maxChars) text to false
    else text.substring(0, maxChars) to true

/**
 * How [runHistoryNav] should carry out a back/forward request, decided from state read off
 * the live WebView. Extracted as a pure function so the decision (issue #59: Chromium's
 * history-manipulation intervention marks gesture-less navigations skippable, making
 * `canGoBack()`/`canGoForward()` false even though a real entry exists) unit-tests without
 * Robolectric.
 */
internal enum class HistoryNavMethod { NATIVE, OFFSET, NONE }

/**
 * NATIVE when the native canGoBack()/canGoForward() check already agrees. Otherwise OFFSET
 * when [copyBackForwardList]'s index proves a real entry exists in that direction (back:
 * not already at the oldest entry; forward: not already at the newest) — `goBackOrForward`
 * maps to `NavigationController::GoToOffset`, which does not honor Chromium's skippable-entry
 * flag the way `canGoBack`/`goBack` do. NONE when there's genuinely nowhere to go.
 */
internal fun resolveHistoryNav(
    forward: Boolean,
    canNative: Boolean,
    currentIndex: Int,
    size: Int,
): HistoryNavMethod = when {
    canNative -> HistoryNavMethod.NATIVE
    forward && currentIndex < size - 1 -> HistoryNavMethod.OFFSET
    !forward && currentIndex > 0 -> HistoryNavMethod.OFFSET
    else -> HistoryNavMethod.NONE
}

private suspend fun runHistoryNav(toolName: String, forward: Boolean): JsonObject {
    val kind = if (forward) BrowserAiActionKind.FORWARD else BrowserAiActionKind.BACK
    val out = withTimeoutOrNull(toolTimeoutMs) {
        BrowserControllerHandle.withController {
            trackJsonAction(kind, detail = null) {
                // Native canGoBack()/goBack() aren't wrapped in a try/catch JS shell the way
                // every other tool's evaluateJavascript payload is (`(function(){try{...}
                // catch(e){...}})()`), so an exception here (e.g. the WebView torn down out
                // from under an in-flight dispatch) would otherwise propagate uncaught out of
                // this whole withController block. Catch it explicitly so a transient WebView
                // hiccup reports an honest {success:false, error:...} instead of surfacing as
                // an opaque tool_failed — the session itself is untouched either way; this
                // only affects what THIS call reports.
                val navResult = runCatching {
                    withContext(Dispatchers.Main) {
                        val list = webView.copyBackForwardList()
                        val canBack = webView.canGoBack()
                        val canForward = webView.canGoForward()
                        android.util.Log.i(
                            "BrowserNav",
                            "wv=${System.identityHashCode(webView)} dir=${if (forward) "forward" else "back"} " +
                                "bfl=${list.size}/${list.currentIndex} canBack=$canBack canFwd=$canForward",
                        )
                        when (resolveHistoryNav(forward, if (forward) canForward else canBack, list.currentIndex, list.size)) {
                            HistoryNavMethod.NATIVE -> {
                                if (forward) webView.goForward() else webView.goBack()
                                true to false
                            }
                            HistoryNavMethod.OFFSET -> {
                                // goBackOrForward(offset) proved inert on device: this WebView build's
                                // GoToOffset honors the skippable flag too, so it silently no-ops. Load
                                // the target history item's URL directly instead — a fresh load can't be
                                // suppressed, at the cost of appending a new forward entry rather than
                                // moving the index.
                                val target = list.getItemAtIndex(list.currentIndex + (if (forward) 1 else -1))
                                android.util.Log.i(
                                    "BrowserNav",
                                    "runHistoryNav: native nav refused, falling back to explicit history-item load: " +
                                        target.url.take(120),
                                )
                                webView.loadUrl(target.url)
                                true to true
                            }
                            HistoryNavMethod.NONE -> false to false
                        }
                    }
                }
                navResult.onFailure {
                    // Same reasoning as browser_eval_js's dispatch runCatching: a timeout/scope
                    // cancellation surfaces here as CancellationException too, and must propagate
                    // rather than be reported as a normal {success:false} nav failure.
                    if (it is CancellationException) throw it
                    android.util.Log.w("BrowserTools", "runHistoryNav: WebView navigation threw", it)
                }
                val ok = navResult.getOrNull()?.first ?: false
                val usedOffsetFallback = navResult.getOrNull()?.second ?: false
                if (ok) webView.awaitReadyState(8_000L)
                val currentUrl = runCatching { webView.url }.getOrNull().orEmpty()
                buildJsonObject {
                    put("success", ok)
                    put("current_url", currentUrl)
                    if (ok && usedOffsetFallback) {
                        put("method", "history_load_fallback")
                    }
                    if (navResult.isFailure) {
                        put("error", "nav_failed")
                        put(
                            "detail",
                            (navResult.exceptionOrNull()?.message ?: "webview navigation threw").take(200),
                        )
                    }
                }
            }
        }
    } ?: timeoutEnvelope(toolName)
    // Pass 3: stream the post-nav screenshot to the calling chat in headless mode. Only
    // fires when the controller is actually in Mode.Headless; foreground-mode is a no-op.
    // Don't stream when we already returned an error envelope.
    if (out["success"]?.toString() == "true") {
        BrowserController.streamScreenshotIfHeadless(if (forward) "Forward" else "Back")
    }
    return out
}

/**
 * Factory dispatch for a given browser tool name. Used by
 * [me.rerere.rikkahub.data.ai.tools.LocalTools.getTools] which iterates the per-tool
 * preference map and only constructs the ones currently enabled.
 *
 * Pass 3: [invocationContext] flows through to the two tool factories that consult it:
 *  - [browserOpenTool]: picks foreground-Activity vs headless-WebView mode by reading
 *    [HeadlessConversations.isHeadless] on the caller's conversation id.
 *  - [browserDoneTool]: in headless mode, also releases the per-conv WebView session.
 *
 * Other tool factories don't need the context — the auto-stream side of headless mode is
 * handled inside [BrowserController.streamScreenshotIfHeadless] which reads the live mode
 * directly without per-tool plumbing.
 */
fun createBrowserTool(
    toolName: String,
    context: Context,
    invocationContext: ToolInvocationContext? = null,
): Tool? = when (toolName) {
    BrowserToolDefaults.OPEN -> browserOpenTool(context, invocationContext)
    BrowserToolDefaults.CURRENT_URL -> browserCurrentUrlTool()
    BrowserToolDefaults.DEBUG_TARGETS -> browserDebugTargetsTool()
    BrowserToolDefaults.DEBUG_EVAL -> browserDebugEvalTool()
    BrowserToolDefaults.HOOK_CREATE -> browserHookCreateTool()
    BrowserToolDefaults.HOOK_LIST -> browserHookListTool()
    BrowserToolDefaults.HOOK_REMOVE -> browserHookRemoveTool()
    BrowserToolDefaults.HOOK_HITS -> browserHookHitsTool()
    BrowserToolDefaults.NETWORK_LIST -> browserNetworkListTool()
    BrowserToolDefaults.CONSOLE_LIST -> browserConsoleListTool()
    BrowserToolDefaults.SNAPSHOT -> browserSnapshotTool()
    BrowserToolDefaults.DEBUG_ATTACH -> browserDebugAttachTool()
    BrowserToolDefaults.DEBUG_STATUS -> browserDebugStatusTool()
    BrowserToolDefaults.DEBUG_SET_BREAKPOINT -> browserDebugSetBreakpointTool()
    BrowserToolDefaults.DEBUG_LIST_BREAKPOINTS -> browserDebugListBreakpointsTool()
    BrowserToolDefaults.DEBUG_REMOVE_BREAKPOINT -> browserDebugRemoveBreakpointTool()
    BrowserToolDefaults.DEBUG_STATE -> browserDebugStateTool()
    BrowserToolDefaults.DEBUG_RESUME -> browserDebugResumeTool()
    BrowserToolDefaults.DEBUG_STEP -> browserDebugStepTool()
    BrowserToolDefaults.DEBUG_DETACH -> browserDebugDetachTool()
    BrowserToolDefaults.SCREENSHOT -> browserScreenshotTool(context)
    BrowserToolDefaults.GET_TEXT -> browserGetTextTool()
    BrowserToolDefaults.GET_DOM -> browserGetDomTool()
    BrowserToolDefaults.GET_LINKS -> browserGetLinksTool()
    BrowserToolDefaults.BACK -> browserBackTool()
    BrowserToolDefaults.FORWARD -> browserForwardTool()
    BrowserToolDefaults.WAIT_FOR -> browserWaitForTool()
    BrowserToolDefaults.CLICK -> browserClickTool()
    BrowserToolDefaults.TYPE -> browserTypeTool()
    BrowserToolDefaults.SCROLL -> browserScrollTool()
    BrowserToolDefaults.SUBMIT -> browserSubmitTool()
    BrowserToolDefaults.SELECT -> browserSelectTool()
    BrowserToolDefaults.PRESS_KEY -> browserPressKeyTool()
    BrowserToolDefaults.EVAL_JS -> browserEvalJsTool()
    BrowserToolDefaults.CLICK_AND_READ -> browserClickAndReadTool()
    BrowserToolDefaults.DONE -> browserDoneTool(invocationContext)
    else -> null
}
