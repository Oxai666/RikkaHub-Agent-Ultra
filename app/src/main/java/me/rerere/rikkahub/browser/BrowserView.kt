package me.rerere.rikkahub.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewAssetLoader
import me.rerere.rikkahub.browser.hook.HookInstaller
import org.koin.compose.koinInject
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.BuildConfig
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
/**
 * 页内 hook 的逻辑 tab 标识。
 *
 * RikkaHub 没有多 tab 池（一会话一个 WebView），前台与 headless 共用同一个逻辑会话，
 * 规则按这个 id 归属；将来真做 tab 池时换成真实 tabId 即可。
 */
internal const val HOOK_TAB_ID = "foreground"

/**
 * Top-level Compose root for [BrowserActivity]. Lays out:
 *   - [BrowserAddressBar] (top — read-only URL + nav controls)
 *   - [WebView] embedded via AndroidView (middle — fills weight=1f)
 *   - [BrowserAiStripe] (bottom — AI status, expandable to recent actions list)
 *
 * The WebView is created exactly once via `remember { WebView(ctx).apply { … } }` and
 * reused across recompositions — recreating it on every recomp would lose page state
 * and re-fetch the URL. Same pattern as Phase 19's WebViewPage.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun BrowserView(
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
    canGoBackState: MutableState<Boolean>,
    canGoForwardState: MutableState<Boolean>,
    currentUrlState: MutableState<String>,
    currentTitleState: MutableState<String>,
    loadProgressState: MutableState<Int>,
    onClose: () -> Unit,
    onBackTap: () -> Unit,
    onForwardTap: () -> Unit,
    onRefreshTap: () -> Unit,
    onStopAi: () -> Unit,
    onNavigate: (String) -> Unit,
    initialUrl: String,
    conversationId: Uuid?,
) {
    val browserPreferences = koinInject<BrowserPreferences>()
    val uiScope = rememberCoroutineScope()
    val coBrowsing by browserPreferences.coBrowsingFlow().collectAsState(initial = true)

    val tabs by BrowserTabs.tabs.collectAsState()
    val activeTabId by BrowserTabs.activeId.collectAsState()
    val activeTab = tabs.firstOrNull { it.id == activeTabId }

    // 首次进入把 Activity 传进来的 initialUrl 落到第一个标签上
    LaunchedEffect(initialUrl) { BrowserTabs.ensureInitial(initialUrl) }

    // 切标签 = 让唯一的 WebView 导航到目标 URL。
    // 比较 URL 再决定要不要 load：activeTab.url 会随导航同步更新，
    // 不比较的话这里会和自己打起来（load → 回调更新 url → 又触发 load）。
    LaunchedEffect(activeTab?.id, activeTab?.url) {
        val target = activeTab?.url ?: return@LaunchedEffect
        val wv = BrowserController.activeWebView() ?: return@LaunchedEffect
        if (wv.url.orEmpty() != target) {
            runCatching { wv.loadUrl(target) }
        }
    }

    Scaffold(
        topBar = {
            BrowserAddressBar(
                url = currentUrlState.value,
                canGoBack = canGoBackState.value,
                canGoForward = canGoForwardState.value,
                onClose = onClose,
                onBack = onBackTap,
                onForward = onForwardTap,
                onRefresh = onRefreshTap,
                onStopAi = onStopAi,
                onNavigate = onNavigate,
                coBrowsing = coBrowsing,
                onToggleCoBrowsing = {
                    uiScope.launch { browserPreferences.setCoBrowsing(!coBrowsing) }
                },
            )
        },
        bottomBar = {
            BrowserAiStripe(onStopAi = onStopAi)
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (loadProgressState.value in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgressState.value / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BrowserTabStrip(
                    tabs = tabs,
                    activeId = activeTabId,
                    onSelect = { BrowserTabs.select(it) },
                    onClose = { BrowserTabs.close(it) },
                    onNew = { BrowserTabs.open() },
                )
                WebViewHost(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    initialUrl = initialUrl,
                    onWebViewReady = onWebViewReady,
                    // 导航后同步到标签状态，地址栏和 chip 标题才跟得上
                    onUrlChange = { url ->
                        BrowserTabs.onNavigated(url)
                        onUrlChange(url)
                    },
                    onTitleChange = { title ->
                        BrowserTabs.onNavigated(BrowserTabs.activeTab()?.url.orEmpty(), title)
                        onTitleChange(title)
                    },
                    onLoadProgress = onLoadProgress,
                    onCanGoBackChange = onCanGoBackChange,
                    onCanGoForwardChange = onCanGoForwardChange,
                )
            }
            // Bottom-anchored mini-chat overlay. Self-hides when conversationId is null
            // (manual launch from Settings without a chat context).
            BrowserMiniChat(
                conversationId = conversationId,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewHost(
    modifier: Modifier = Modifier,
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onLoadProgress: (Int) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCanGoForwardChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    // 页内 hook 安装器（Koin 单例）：document-start 注入 + 规则推送都靠它。
    val hookInstaller = koinInject<HookInstaller>()
    val browserPreferences = koinInject<BrowserPreferences>()
    val uiScope = rememberCoroutineScope()
    val coBrowsing by browserPreferences.coBrowsingFlow().collectAsState(initial = true)
    val browserEventBus = koinInject<BrowserEventBus>()
    // Construct the WebView ONCE — `remember` survives recomp + (with key=Unit) survives
    // configuration changes (the Activity declares `configChanges` so the system never
    // recreates us). Recreating per-recomp would reset history, scroll, and JS state.
    val webView = remember {
        // Enable Chrome DevTools attachment ONLY in debug builds. In release, leaving
        // this on lets anyone with adb (lent phone, ADB-over-WiFi attacker) attach
        // chrome://inspect and read the WebView's cookies / localStorage / authenticated
        // session bodies. Gate behind BuildConfig.DEBUG — turning Chrome inspection on
        // in release is a privacy posture choice the user never consented to.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            // WebView derives its CSS viewport height (100vh/100dvh/percentage heights)
            // from its LayoutParams, not from its actual measured/laid-out size. Without
            // this, the default WRAP_CONTENT makes vh/% heights resolve to 0 even though
            // Compose's AndroidView correctly sizes the view on screen (measured on
            // device: innerHeight=774 but 100vh=0). HeadlessBrowserSession already sets
            // its own fixed-size LayoutParams for the same reason; MATCH_PARENT here lets
            // the real Compose-driven size flow through instead.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // Shared with HeadlessBrowserSession — every render-related setting
            // (mixedContentMode, hardware layer, autoplay, UA strip, file:// access)
            // lives in configureWebViewForRikka so foreground + headless behave
            // identically. See BrowserWebViewConfig.kt for the why.
            configureWebViewForRikka(this)

            // 页内 hook 运行时：document-start 注入 hook_runtime.js + RikkaBridge。
            // tabId 暂时固定 —— RikkaHub 没有多 tab 池，前台 headless 共用一个逻辑会话。
            hookInstaller?.onWebViewCreated(HOOK_TAB_ID, this)

            // Profile dir is informational — the global WebView databases live where the
            // WebView wants. We create the dir ourselves in BrowserActivity.onCreate so
            // Pass 3's "Clear browsing data" has a stable target to wipe.
            File(ctx.filesDir, "browser-profile").apply { if (!exists()) mkdirs() }

            // 起始页（newtab.html）通过虚拟 https origin 提供：
            //  - 比 file:///android_asset 多一个正常 origin，localStorage 才存得住用户选的引擎
            //  - 跨站跳转不会被 file:// 的隔离规则拦住
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    return assetLoader.shouldInterceptRequest(url)
                        ?: super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    // Block page/JS-initiated navigation into file:// unless the current
                    // document is already file:// (skill webview cards moving between
                    // their own sub-pages). App-initiated loadUrl() bypasses this
                    // callback, so opening a skill card stays unaffected. Without this,
                    // a browsed page (or eval'd JS) could steer the WebView into
                    // app-private files and exfiltrate them via browser_get_text.
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    return toFile && view?.url?.startsWith("file:", ignoreCase = true) != true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) {
                        onUrlChange(url)
                        // CDP attach 要靠它把 target 对上 tab（多 page target 时按 URL 精确匹配）
                        browserEventBus?.noteTabUrl(HOOK_TAB_ID, url)
                    }
                    view?.let { hookInstaller?.injectFallback(it) }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) onUrlChange(url)
                    onCanGoBackChange(view?.canGoBack() == true)
                    onCanGoForwardChange(view?.canGoForward() == true)
                    view?.let { hookInstaller?.verifyInstalled(it) }
                    // Adb-friendly white-page diagnostic. Tag = "RikkaWebView". Filter:
                    //   adb logcat -s RikkaWebView
                    // Dumps body innerText length + first 100 chars + meta viewport so
                    // you can tell at a glance whether the DOM is populated (render bug)
                    // or empty (load bug) without needing chrome://inspect.
                    view?.evaluateJavascript(
                        """
                        JSON.stringify({
                          rs: document.readyState,
                          docLen: (document.documentElement ? document.documentElement.outerHTML.length : 0),
                          bodyLen: (document.body ? document.body.innerText.length : 0),
                          children: (document.body ? document.body.children.length : 0),
                          firstText: (document.body ? document.body.innerText.slice(0, 100) : ''),
                          viewport: (function(){var m=document.querySelector('meta[name=viewport]');return m?m.content:'';})()
                        })
                        """.trimIndent(),
                    ) { json ->
                        Log.d("RikkaWebView", "onPageFinished $url -> $json")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    val isMainFrame = request?.isForMainFrame == true
                    Log.w(
                        "RikkaWebView",
                        "onReceivedError mainFrame=$isMainFrame url=${request?.url} code=${error?.errorCode} desc=${error?.description}",
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onLoadProgress(newProgress)
                }
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (title != null) onTitleChange(title)
                }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    // Surface page-side console.log / console.error to logcat so the user
                    // can debug white pages from a terminal without DevTools.
                    if (message != null) {
                        val priority = when (message.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            else -> Log.INFO
                        }
                        Log.println(
                            priority,
                            "RikkaWebViewConsole",
                            "${message.sourceId()}:${message.lineNumber()} ${message.message()}",
                        )
                    }
                    return true
                }
            }

            loadUrl(initialUrl)
            onWebViewReady(this)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
    )
}

/**
 * 标签栏。
 *
 * 只在**逻辑**层面分 tab —— 底层仍是一个 WebView，切 tab 就是换 URL。
 * 所以关掉标签不会真的销毁页面状态，切回来是重新加载。
 * 顶部单行、横向可滚，chip 上显示标题，点 × 关闭。
 */
@Composable
private fun BrowserTabStrip(
    tabs: List<BrowserTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        tabs.forEach { tab ->
            val label = tab.title.ifBlank {
                if (tab.url == BrowserTab.START_URL) stringResource(R.string.browser_tab_untitled)
                else tab.url.removePrefix("https://").removePrefix("http://").take(28)
            }
            InputChip(
                selected = tab.id == activeId,
                onClick = { onSelect(tab.id) },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(max = 110.dp),
                    )
                },
                trailingIcon = {
                    // 只剩一个标签时不给关，否则会出现「零标签」的空状态
                    if (tabs.size > 1) {
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(18.dp)) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.browser_tab_close),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(
            onClick = onNew,
            enabled = tabs.size < BrowserTabs.MAX_TABS,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Add01,
                contentDescription = stringResource(R.string.browser_tab_new),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
