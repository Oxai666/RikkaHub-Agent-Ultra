# browser/cdp — 从太墟移植的 CDP 协议层

来源：**太墟 taiXu** `https://github.com/wkbin/taixu`
（`runtime/browser/src/main/java/top/wkbin/taixu/runtime/browser/cdp/`）
作者：王克斌，许可证：**GPL-3.0**。本目录内的代码受该许可证约束。

## 为什么能直接用

太墟是万象的开源上游，浏览器部分是可读 Kotlin 而不是反编译产物。
这份 cdP 层已在其产品里跑通「无 root 连 WebView devtools」，省掉我们从零验证。

关键实现（`CdpTransport.kt`）：

```kotlin
WebView.setWebContentsDebuggingEnabled(true)
LocalSocket().connect(LocalSocketAddress("webview_devtools_remote_$pid", ABSTRACT))
// 同一 socket：先 HTTP GET /json 发现 target，再手写 WebSocket 握手与帧
// 兜底：扫 /proc/net/unix 找 @webview_devtools_remote_*
```

`android.net.LocalSocket` 不是 `java.net.Socket` 子类，OkHttp 的 SocketFactory 走不通，
所以这里手写了最小 HTTP + WS（`HttpOverSocket` / `WsHandshake` / `StreamWsConnection`）。

## 本仓库内已移植的文件

| 文件 | 作用 | 依赖 |
|---|---|---|
| `CdpTransport.kt` | socket 发现、HTTP over socket、WS 握手 | 无 |
| `WsFrameCodec.kt` | WebSocket 帧编解码 | 无 |
| `CdpSession.kt` | 单 target 的 JSON-RPC 会话 | 无 |
| `CdpModels.kt` | Target / 断点 / 调用栈模型 | 无 |
| `CdpTargetMatcher.kt` | tab 与 target 的匹配（marker probe） | 无 |
| `CdpGlob.kt` | url glob 匹配 | 无 |
| `WebViewDebugging.kt` | 调试开关 + 诊断 | 无 |
| `CdpProbe.kt` | **本仓库新增**：连通性探针，串起全链路 | 以上 |

尚未移植（依赖事件总线与 Hook 层，等后续阶段）：

```
CdpManager.kt         attach/detach 生命周期、断点重放、attach 上限
CdpTabConnection.kt   单 tab 的会话封装（依赖 BrowserEventBus / HookRuleStore）
CdpDebugController.kt 断点/单步状态机（依赖 BrowserEvent）
CdpFetchInterceptor.kt 引擎级 Fetch 拦截（依赖 Hook）
CdpFetchDecision.kt   hook 决策（依赖 HookAction/HookRule）
```

## 移植时的改动

- 包名 `top.wkbin.taixu.runtime.browser.cdp` → `me.rerere.rikkahub.browser.cdp`
- 页面内 tab 标记 `window.__taixuTabId` → `window.__rikkaTabId`
- 新增 `CdpProbe`（原项目没有这个独立探针）
