package me.rerere.rikkahub.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 一个标签页的**逻辑**身份。
 *
 * v1 是「单 WebView + 多逻辑 tab」：同一时刻只有一个 WebView 在跑，
 * 切标签 = 换 URL。真并行要每个 tab 一个 WebView（~30MB each），
 * 手机上开三四个就把进程撑爆了 —— 先按这个取舍做。
 *
 * 已知限制（写在最前面，免得被当成 bug）：
 *  - 切走的 tab **不保留滚动位置、表单填写、SPA 内存状态**，只记住 URL 和标题；
 *  - 页内 hook 与抓包事件仍统一挂在 [HOOK_TAB_ID] 下，多 tab 的流量会混在一起；
 *  - AI 工具只操作**当前活动 tab**，没有 `tab` 参数。
 */
@OptIn(ExperimentalUuidApi::class)
data class BrowserTab(
    val id: String = Uuid.random().toString(),
    val url: String = START_URL,
    val title: String = "",
) {
    companion object {
        /** 起始页用虚拟 https origin，和 newtab.html 的加载方式保持一致。 */
        const val START_URL = "https://appassets.androidplatform.net/assets/browser/newtab.html"
    }
}

/**
 * 内存态标签集合。不落盘：重启浏览器回到一个干净标签，
 * 和「重新进浏览器就停在最新页面」的预期一致。
 */
object BrowserTabs {

    /** 手机屏幕最多同时舒服地显示 8 个 chip，再多就得靠滚动，没意义。 */
    const val MAX_TABS = 8

    private val _tabs = MutableStateFlow(listOf(BrowserTab()))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow(_tabs.value.first().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    fun activeTab(): BrowserTab? = _tabs.value.firstOrNull { it.id == _activeId.value }

    /** 首次进入时把 Activity 传进来的 initialUrl 落到第一个标签上（只做一次）。 */
    fun ensureInitial(url: String) {
        if (url.isBlank()) return
        val current = _tabs.value.firstOrNull { it.id == _activeId.value } ?: return
        if (current.url != BrowserTab.START_URL) return // 用户已经在这个标签里导航过了
        if (url == BrowserTab.START_URL) return
        _tabs.update { list ->
            list.map { if (it.id == current.id) it.copy(url = url) else it }
        }
    }

    /** 新开一个标签并切过去；返回新标签 id，null 表示已达上限。 */
    fun open(url: String? = null): String? {
        if (_tabs.value.size >= MAX_TABS) return null
        val tab = BrowserTab(url = url ?: BrowserTab.START_URL)
        _tabs.update { it + tab }
        _activeId.value = tab.id
        return tab.id
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) _activeId.value = id
    }

    /** 关标签。关掉活动标签时切到左邻；关掉最后一个则保留一个空白标签。 */
    fun close(id: String) {
        val list = _tabs.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val remaining = list.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val fresh = BrowserTab()
            _tabs.value = listOf(fresh)
            _activeId.value = fresh.id
            return
        }
        _tabs.value = remaining
        if (_activeId.value == id) {
            _activeId.value = remaining[(idx - 1).coerceAtLeast(0)].id
        }
    }

    /** 当前标签导航到新地址后同步状态（地址栏、标题 chip 都靠它）。 */
    fun onNavigated(url: String, title: String? = null) {
        if (url.isBlank()) return
        val id = _activeId.value
        _tabs.update { list ->
            list.map {
                if (it.id != id) it
                else it.copy(url = url, title = title?.takeIf { t -> t.isNotBlank() } ?: it.title)
            }
        }
    }

    /** 退出浏览器时清理，下次进入是一个干净标签。 */
    fun reset() {
        val fresh = BrowserTab()
        _tabs.value = listOf(fresh)
        _activeId.value = fresh.id
    }
}
