package me.rerere.rikkahub.browser.cdp

import kotlinx.serialization.json.JsonObject

/**
 * CDP 命令发送接口。
 *
 * 原本定义在太墟的 `CdpDebugController.kt` 里，但我们先只移植了传输与会话层，
 * 调试状态机还没进来，所以把接口单独抽出来，避免为此拖进整个 DebugController
 * （它依赖 BrowserEvent / BrowserEventBus）。
 *
 * 等 CdpDebugController 落地时，删掉它文件里的同名接口定义即可。
 */
interface CdpCommandApi {
    suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
        timeoutMs: Long = 10_000,
    ): JsonObject
}
