package me.rerere.rikkahub.subagent

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置子智能体角色库（assets 里的 136 个角色 + 9 个部门索引）。
 *
 * 来源：`msitarzewski/agency-agents` @ 3c95888，MIT，见 `assets/agency_agents/LICENSE`。
 * 打包路径与 catalog 里的 `promptPath` 一致（都是 `agency_agents/...`），所以取正文时
 * 不需要再做前缀映射。
 *
 * 设计约束（对齐 `SkillCatalog` 的做法）：
 *  - 只有 catalog.json 常驻内存（约 64 KB），角色正文按需读、读过的留在 [promptCache]；
 *  - 任何一步失败都降级成"没有内置角色"，绝不抛出到主流程 —— 内置库坏了不该让
 *    subagent_dispatch 整体不可用；
 *  - 不写进 `Settings.subAgents`：内置角色是只读资源，用户自定义 profile 仍是唯一可写来源，
 *    两者在 [SubAgentProfileResolver] 之后按"用户优先、内置兜底"的顺序解析。
 */
@Serializable
data class BuiltinDepartment(
    val id: String,
    val name: String = "",
    val icon: String = "",
    val color: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BuiltinAgent(
    val id: String,
    val name: String,
    val description: String = "",
    val departmentId: String = "",
    val promptPath: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BuiltinCatalog(
    val schemaVersion: Int = 1,
    val departments: List<BuiltinDepartment> = emptyList(),
    val agents: List<BuiltinAgent> = emptyList(),
)

/** 命中方式，用于把"降级到什么程度"记进日志，方便排查路由为什么选了这个角色。 */
enum class BuiltinRouteVia {
    EXACT_NAME,
    DEPT_KEYWORD,
    GLOBAL_KEYWORD,
    DEPT_FIRST,
    ANY_FIRST,
}

data class BuiltinRouting(
    val agent: BuiltinAgent,
    val via: BuiltinRouteVia,
)

object BuiltinSubAgents {
    private const val TAG = "BuiltinSubAgents"
    private const val CATALOG_ASSET = "agency_agents/catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var catalogCache: BuiltinCatalog? = null

    private val promptCache = ConcurrentHashMap<String, String>()

    /**
     * 在 `Application.onCreate` 调用一次。目录读不出来就永远返回空结果，
     * 上层看到的只是"没有内置角色"，不会崩。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { catalog() }
            .onSuccess { Log.i(TAG, "builtin sub-agents loaded: ${it.agents.size} roles / ${it.departments.size} departments") }
            .onFailure { Log.w(TAG, "builtin sub-agent catalog unavailable", it) }
    }

    fun catalog(): BuiltinCatalog {
        catalogCache?.let { return it }
        val ctx = appContext ?: return BuiltinCatalog()
        val loaded = runCatching {
            val raw = ctx.assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<BuiltinCatalog>(raw)
        }.getOrElse { t ->
            Log.w(TAG, "$CATALOG_ASSET 读取/解析失败，内置角色库按空处理", t)
            BuiltinCatalog()
        }
        catalogCache = loaded
        return loaded
    }

    fun departments(): List<BuiltinDepartment> = catalog().departments.sortedBy { it.sortOrder }

    fun agents(): List<BuiltinAgent> = catalog().agents.sortedBy { it.sortOrder }

    fun departmentOf(agent: BuiltinAgent): BuiltinDepartment? =
        catalog().departments.firstOrNull { it.id == agent.departmentId }

    /** 角色名精确匹配（大小写不敏感）。 */
    fun findByExactName(name: String): BuiltinAgent? {
        val target = name.trim()
        if (target.isEmpty()) return null
        return agents().firstOrNull { it.name.equals(target, ignoreCase = true) }
    }

    /**
     * 按 `agent` 参数路由到内置角色，逐级降级保证"派得出去"：
     *   角色名精确 → 部门内关键词 → 全局关键词 → 部门内首个角色 → 任意首个角色。
     *
     * 与用户自定义 profile 的关系：调用方应先用 [SubAgentProfileResolver] 解析用户 profile，
     * 只有解析不到时才走到这里 —— 用户同名角色永远优先。
     */
    fun route(query: String, departmentId: String? = null): BuiltinRouting? {
        val routing = routeBuiltinAgent(agents(), query, departmentId) ?: return null
        Log.i(
            TAG,
            "routed \"$query\" -> ${routing.agent.name} (${routing.agent.departmentId}) via ${routing.via}",
        )
        return routing
    }

    /** 读角色 md 正文，剥掉 YAML frontmatter；id 与正文都缓存。 */
    fun systemPrompt(agent: BuiltinAgent): String {
        promptCache[agent.id]?.let { return it }
        val ctx = appContext ?: return ""
        if (agent.promptPath.isBlank()) return ""
        val body = runCatching {
            ctx.assets.open(agent.promptPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { t ->
            Log.w(TAG, "读取角色正文失败: ${agent.promptPath}", t)
            return ""
        }.let(::stripFrontmatter)
        promptCache[agent.id] = body
        return body
    }

    /** 内置角色 → 可直接喂给 SubAgentEngine 的 profile（model 跟随父级）。 */
    fun toProfile(agent: BuiltinAgent): SubAgentProfile = SubAgentProfile(
        name = agent.name,
        description = agent.description,
        systemPrompt = systemPrompt(agent),
        modelId = null,
        enabled = true,
    )

    /** 工具描述里用的部门索引，一行一个部门。 */
    fun departmentIndex(): String {
        val depts = departments()
        if (depts.isEmpty()) return ""
        val byDept = agents().groupBy { it.departmentId }
        return depts.joinToString("\n") { dept ->
            "- ${dept.name} (${dept.id}): ${byDept[dept.id]?.size ?: 0} roles"
        }
    }

    /** 几个示例角色名，给模型一个"名字长什么样"的参照。 */
    fun sampleNames(limit: Int = 6): String =
        agents().take(limit).joinToString(", ") { it.name }
}

/** 剥掉 `---` 包起来的 frontmatter，只留正文。没有 frontmatter 时原样返回。 */
internal fun stripFrontmatter(raw: String): String {
    val text = raw.trimStart('\uFEFF', '\n', '\r')
    if (!text.startsWith("---")) return text.trim()
    val end = text.indexOf("\n---", startIndex = 3)
    if (end < 0) return text.trim()
    val after = text.substring(end + 4)
    return after.trimStart('\r', '\n').trim()
}

/**
 * 逐级降级路由本体。抽成纯函数是为了不依赖 assets 也能跑单测。
 *
 * [query] 通常是模型给的 `agent` 值：可能是角色名（"Security Engineer"），
 * 也可能是 2–5 个英文关键词（"android kotlin reverse"）。
 */
internal fun routeBuiltinAgent(
    agents: List<BuiltinAgent>,
    query: String,
    departmentId: String? = null,
): BuiltinRouting? {
    if (agents.isEmpty()) return null
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    agents.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let {
        return BuiltinRouting(it, BuiltinRouteVia.EXACT_NAME)
    }

    val tokens = trimmed.lowercase()
        .split(Regex("[\\s,;:/|]+"))
        .filter { it.length >= 2 }
        .take(8)

    fun score(agent: BuiltinAgent): Int {
        val haystack = buildString {
            append(agent.name.lowercase())
            append(' ')
            append(agent.description.lowercase())
            append(' ')
            append(agent.id.replace('_', ' ').lowercase())
            append(' ')
            append(agent.departmentId.lowercase())
        }
        return tokens.count { haystack.contains(it) }
    }

    if (departmentId != null) {
        val inDept = agents.filter { it.departmentId == departmentId }
        inDept.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }?.let {
            return BuiltinRouting(it, BuiltinRouteVia.DEPT_KEYWORD)
        }
    }

    agents.maxByOrNull { score(it) }?.takeIf { score(it) > 0 }?.let {
        return BuiltinRouting(it, BuiltinRouteVia.GLOBAL_KEYWORD)
    }

    if (departmentId != null) {
        agents.filter { it.departmentId == departmentId }.minByOrNull { it.sortOrder }?.let {
            return BuiltinRouting(it, BuiltinRouteVia.DEPT_FIRST)
        }
    }

    return agents.minByOrNull { it.sortOrder }?.let { BuiltinRouting(it, BuiltinRouteVia.ANY_FIRST) }
}
