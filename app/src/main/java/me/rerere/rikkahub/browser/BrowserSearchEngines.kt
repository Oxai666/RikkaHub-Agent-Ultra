package me.rerere.rikkahub.browser

import java.net.URLEncoder

/**
 * 浏览器搜索入口的唯一真相源。
 *
 * 原版把地址栏搜索写死成 DuckDuckGo（`BrowserAddressBar.normalizeBrowserQuery`），
 * 那个域名在国内直连不通 —— 只要输入的不是完整 URL，就会跳去一个打不开的搜索页，
 * 体验上等于"任何网页都打不开"。
 *
 * 这里换成国内可直连的默认引擎，并且和 assets/browser/newtab.html 里的 `ENGINES`
 * 保持同一份顺序。两边一旦漂移就会出现"起始页能搜、地址栏还是卡死"的怪象。
 */
object BrowserSearchEngines {

    data class Engine(
        val id: String,
        val name: String,
        val searchUrl: String,
    )

    /** 顺序即默认优先级；第一个是默认引擎。 */
    val engines: List<Engine> = listOf(
        Engine("bing", "必应", "https://cn.bing.com/search?q="),
        Engine("baidu", "百度", "https://www.baidu.com/s?wd="),
        Engine("sogou", "搜狗", "https://www.sogou.com/web?query="),
        Engine("google", "Google", "https://www.google.com/search?q="),
    )

    val default: Engine get() = engines.first()

    fun byId(id: String?): Engine = engines.firstOrNull { it.id == id } ?: default

    /** 关键词 → 搜索引擎 URL。Google 等需要在有代理时才有结果，默认不走它。 */
    fun buildSearchUrl(query: String, engineId: String? = null): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return byId(engineId).searchUrl + encoded
    }

    // --- 新标签页 -------------------------------------------------------------

    /**
     * 用 WebViewAssetLoader 的虚拟 https origin 提供起始页。
     * 相比 `file:///android_asset/...`：origin 是正常 https，localStorage 可用
     * （起始页靠它记住用户选的引擎），跨站跳转也不会被 file:// 的隔离规则卡住。
     */
    const val NEW_TAB_ASSET_PATH = "browser/newtab.html"
    const val ASSET_URL_PREFIX = "https://appassets.androidplatform.net/assets/"
    const val NEW_TAB_URL = ASSET_URL_PREFIX + NEW_TAB_ASSET_PATH

    fun isNewTabUrl(url: String?): Boolean =
        url != null && url.startsWith(NEW_TAB_URL)
}
