package me.rerere.rikkahub.browser.hook

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json

/**
 * Hook 规则表：规则 + 持久脚本 + 版本化 payload 缓存。
 *
 * - `getRules()`（JavaBridge 线程同步调用）只读缓存串：payload 按 (version, tabId)
 *   缓存，miss 时现场构建一次并回填 —— 每个版本每个 tab 至多序列化一次；
 * - 任何变更（install/remove/script）version+1 并清空缓存，下轮导航/推送时重建。
 */
class HookRuleStore(
    private val maxBodyBytes: Int,
) {
    private val json = Json {
        // 必须为 true。payload 里 v / actions / method / enabled 全都靠默认值填充：
        //  - v=1 被省掉 → 页面侧 __rikkaApplyRules 的 `if (p.v !== 1) return` 直接拦下，
        //    规则静默丢弃，hits 永远 0（真机实测就是这样，得手动补 v 才生效）；
        //  - actions 被省掉 → 页面拿不到 block/mock/redirect，全部退化成 log。
        // 一条规则多编码几十字节，换正确性值得。
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val rules = ConcurrentHashMap<String, HookRule>()
    private val scripts = ConcurrentHashMap<String, InjectedScript>()
    private val hitCounts = ConcurrentHashMap<String, Int>()
    private val version = AtomicInteger(0)

    /** payload 缓存：key = "$version:$tabId"。 */
    private val payloadCache = ConcurrentHashMap<String, String>()

    fun currentVersion(): Int = version.get()

    fun install(rule: HookRule): HookRule {
        rules[rule.id] = rule
        bump()
        return rule
    }

    fun remove(id: String): Boolean {
        hitCounts.remove(id)
        val removed = rules.remove(id) != null
        if (removed) bump()
        return removed
    }

    fun removeScriptsFor(tabId: String?) {
        val before = scripts.size
        scripts.entries.removeIf { it.value.scopeTabId == tabId }
        if (scripts.size != before) bump()
    }

    fun addScript(script: InjectedScript) {
        scripts[script.id] = script
        bump()
    }

    /** 清空全部规则 + 脚本 + 命中计数；tabId 非空时仅清该 tab 范围。 */
    fun reset(tabId: String?) {
        if (tabId == null) {
            rules.clear()
            scripts.clear()
        } else {
            rules.entries.removeIf { it.value.scopeTabId == tabId }
            scripts.entries.removeIf { it.value.scopeTabId == tabId }
        }
        hitCounts.clear()
        bump()
    }

    fun list(tabId: String? = null): List<HookRule> =
        rules.values
            .filter { tabId == null || it.scopeTabId == null || it.scopeTabId == tabId }
            .sortedBy { it.id }

    fun recordHit(ruleId: String) {
        hitCounts.merge(ruleId, 1, Int::plus)
    }

    fun hitCount(ruleId: String): Int = hitCounts[ruleId] ?: 0

    fun rulesFor(tabId: String): List<HookRule> =
        rules.values.filter { it.enabled && (it.scopeTabId == null || it.scopeTabId == tabId) }

    fun scriptsFor(tabId: String): List<InjectedScript> =
        scripts.values.filter { it.scopeTabId == null || it.scopeTabId == tabId }

    /**
     * 该 tab 的完整规则 payload（JSON 串）。miss 时构建并回填缓存；
     * 空规则 + 空脚本时返回最小 envelope，页内快速直通。
     */
    fun payloadFor(tabId: String): String {
        val v = version.get()
        val key = "$v:$tabId"
        payloadCache[key]?.let { return it }
        val payload = HookRulesPayload(
            config = HookRulesPayload.PayloadConfig(maxBodyBytes = maxBodyBytes),
            rules = rulesFor(tabId),
            scripts = scriptsFor(tabId),
        )
        val encoded = json.encodeToString(HookRulesPayload.serializer(), payload)
        payloadCache[key] = encoded
        return encoded
    }

    private fun bump() {
        version.incrementAndGet()
        payloadCache.clear()
    }
}
