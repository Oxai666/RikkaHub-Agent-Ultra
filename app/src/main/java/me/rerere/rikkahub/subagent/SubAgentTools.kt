package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

internal fun encodeRun(run: SubAgentRun): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null && !run.noResult) {
        put("result", run.result)
    } else if (run.noResult) {
        put("result_suppressed", true)
    }
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_out", run.tokensOut)
    put("trip_count", run.tripCount)
    run.claimStatus?.let { put("claim_status", it) }
    run.claimReport?.let { put("claim_report", it) }
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. The four register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
    // #36: named sub-agent profiles, passed in fresh at tool-construction time (the
    // caller reads them from current settings) so the description below - and whether `agent`
    // is offered as a parameter at all - always reflects what's configured right now.
    profiles: List<SubAgentProfile> = emptyList(),
): Tool {
    val enabledProfiles = SubAgentProfileResolver.enabledProfiles(profiles)
    // 内置角色库只给部门索引，不逐条列 136 个角色：那会把工具描述撑成几千 token，
    // 而且每一轮请求都要重发。模型按"部门 + 关键词"自己路由，引擎侧再逐级降级。
    val builtinDepartmentIndex = BuiltinSubAgents.departmentIndex()
    val builtinRoleCount = BuiltinSubAgents.agents().size
    val description = buildString {
        append(
            """
                Dispatch a focused sub-agent — a clean-context LLM run that returns a concise
                summary. Use when the task is independent (research, lookup, multi-step work)
                and would otherwise pollute your context with intermediate output, OR when the
                user explicitly asks for parallel work.

                Pass a clear, self-contained task — the sub-agent doesn't see your conversation,
                so restate any context it needs. Pass a short label so the user can recognise
                the running sub-agent. For long-running work, set run_in_background=true and
                poll with subagent_get; otherwise foreground (default) blocks until terminal.

                Concurrency caps: each assistant has its own (default 3, configurable 1-8) and
                there's a global cap of 30 across all assistants. Over-cap dispatches fail with
                a clear error — back off and retry, or wait for a slot.

                Approval-required: every dispatch needs explicit confirmation. Eligible for
                Always Allow if the user trusts the assistant to delegate freely.

                Result carries a claim report when the sub-agent appended one: `files` criteria are
                verified against the workspace, `verification` commands are left UNVERIFIED for you
                to re-run yourself, and a self-reported `complete` with no backed criterion is
                downgraded to `partial`. Treat `partial` as unfinished work, not as a success.
            """.trimIndent()
        )
        if (enabledProfiles.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Named sub-agent profiles (pass the name as `agent`):\n")
            append(enabledProfiles.joinToString("\n") { "- ${it.name}: ${it.description}" })
        }
        if (builtinDepartmentIndex.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Built-in role library — $builtinRoleCount specialist personas. Pass `agent` as a role ")
            append("name (e.g. \"Code Reviewer\") or 2-5 keywords (e.g. \"android kotlin\", \"security audit\"). ")
            append("Departments:\n")
            append(builtinDepartmentIndex)
            appendLine()
            appendLine()
            append("Use `subagent_roles` to look up exact role names inside a department before ")
            append("passing a name you are unsure about.")
        }
    }
    return Tool(
        name = "subagent_dispatch",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject { put("type", "string") })
                    put("label", buildJsonObject { put("type", "string") })
                    if (enabledProfiles.isNotEmpty() || builtinDepartmentIndex.isNotEmpty()) {
                        put("agent", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Name of a configured sub-agent profile (case-insensitive), " +
                                    "supplying that profile's model and system prompt. Unknown " +
                                    "names fail the dispatch and the error lists the valid " +
                                    "names. model_id, if also given, wins over the profile's " +
                                    "model.",
                            )
                        })
                    }
                    put("model_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Model for this sub-agent: a model uuid, a provider model id, or a " +
                                "display name (case-insensitive exact match). Ambiguous or unknown " +
                                "values fail the dispatch and the error lists the valid options. " +
                                "Takes precedence over agent's model. Omit to inherit the agent " +
                                "profile's model (if agent is set) or the parent assistant's model.",
                        )
                    })
                    put("system_prompt", buildJsonObject { put("type", "string") })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("run_in_background", buildJsonObject { put("type", "boolean") })
                    put("no_result", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "When true the sub-agent's final output is not returned to you " +
                                "(only status, ids and counters). Use for fire-and-forget work " +
                                "whose result you do not need, to keep your context small.",
                        )
                    })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                    put("max_trips", buildJsonObject { put("type", "integer") })
                    put("write_paths", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put(
                            "description",
                            "Write lease for this sub-agent, enforced by the workspace write tools: " +
                                "omit for no restriction, [] for read-only, [\"*\"] for the whole workspace, " +
                                "otherwise path prefixes it may write under. The shell tool is NOT covered.",
                        )
                    })
                },
                required = listOf("task"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            // Hard recursion guard — refuse the dispatch if the caller is itself a headless
            // run (cron / workflow / external-automation / another sub-agent). The engine's
            // own guard relies on a registered conversation id; cron / workflow direct-mode
            // paths have no conversation so the engine guard wouldn't fire there. Catch it here.
            if (callerContext.isHeadless) {
                return@Tool errEnv(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / sub-agent / external automation). Run the work inline instead.",
                )
            }
            val params = args.jsonObject
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool errEnv("invalid_task", "task is required")
            val request = SubAgentRequest(
                task = task,
                modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
                agentName = params["agent"]?.jsonPrimitive?.contentOrNull,
                systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
                tools = params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull },
                runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
                noResult = params["no_result"]?.jsonPrimitive?.booleanOrNull ?: false,
                timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
                maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
                label = params["label"]?.jsonPrimitive?.contentOrNull,
                writePaths = params["write_paths"]?.let { element ->
                    runCatching { element.jsonArray }.getOrNull()
                }?.mapNotNull { it.jsonPrimitive.contentOrNull },
            )
            // The engine's recursion guard checks `HeadlessConversations.isHeadless(parentChatId)`
            // — if the calling conversation is itself headless (cron / sub-agent / workflow /
            // external-automation) we refuse the dispatch. ToolInvocationContext propagation
            // (added 2026-05-07 stability pass) gives us the calling conversation id at tool-
            // construction time. Empty fallback is a no-knowledge sentinel — engine treats it
            // as "not in a headless run" which is correct for the legacy registration paths
            // that don't yet wire context (one-off / test).
            val parentAssistantId = callerContext.callerAssistantId.orEmpty()
            val parentChatId: String? = callerContext.callerConversationId
            when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
                is SubAgentEngine.DispatchResult.Reject ->
                    return@Tool errEnv(res.error, res.detail)
                is SubAgentEngine.DispatchResult.Ok ->
                    listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
            }
        },
    )
}

fun subagentListTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_list",
    description = """
        List sub-agent runs visible to this assistant. Set active_only=true to omit
        terminal runs. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("active_only", buildJsonObject { put("type", "boolean") })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val activeOnly = args.jsonObject["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
        val list = registry.list(activeOnly)
        val arr = buildJsonArray {
            list.forEach { addJsonObject {
                put("id", it.id)
                put("label", it.label)
                put("status", it.status.name)
                if (it.modelId != null) put("model_id", it.modelId)
                put("started_at_ms", it.startedAtMs)
                put("trip_count", it.tripCount)
            } }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("runs", arr)
        }.toString()))
    },
)

fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)


/**
 * 内置角色库检索工具。
 *
 * 为什么需要它：136 个角色名不可能全塞进 `subagent_dispatch` 的描述（几千 token，每轮重发），
 * 只靠关键词路由又等于让模型盲猜。给它一个"先查后派"的入口，比让它硬猜角色名可靠。
 */
fun subagentRolesTool(): Tool = Tool(
    name = "subagent_roles",
    description = """
        Search the built-in sub-agent role library. Call this before subagent_dispatch when you
        are not certain which specialist fits: filter by department id and/or keywords, then pass
        the EXACT role name returned here as `agent`. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("department", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Department id: engineering, design, product, project-management, testing, " +
                            "security, game-development, spatial-computing, specialized",
                    )
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keywords matched against role name, id and description")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max roles to return. Default 20, max 60.")
                })
            },
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val department = params["department"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 60)
        val payload = searchBuiltinRoles(
            agents = BuiltinSubAgents.agents(),
            departments = BuiltinSubAgents.departments(),
            department = department,
            query = query,
            limit = limit,
        )
        listOf(UIMessagePart.Text(payload))
    },
)

/**
 * 检索本体，纯函数以便单测。
 * 返回 JSON：`{scope_department, matched, returned, roles:[{name,department,description}], departments:[…]}`
 */
internal fun searchBuiltinRoles(
    agents: List<BuiltinAgent>,
    departments: List<BuiltinDepartment>,
    department: String? = null,
    query: String? = null,
    limit: Int = 20,
): String {
    val tokens = query
        ?.lowercase()
        ?.split(Regex("[\\s,;:/|]+"))
        ?.filter { it.length >= 2 }
        .orEmpty()

    val inScope = agents.filter { department == null || it.departmentId == department }
    val matched = inScope.filter { agent ->
        tokens.isEmpty() || tokens.any { token ->
            agent.name.lowercase().contains(token) ||
                agent.id.lowercase().contains(token) ||
                agent.description.lowercase().contains(token) ||
                agent.departmentId.lowercase().contains(token)
        }
    }.sortedBy { it.sortOrder }

    return buildJsonObject {
        department?.let { put("scope_department", it) }
        put("matched", matched.size)
        put("returned", minOf(limit, matched.size))
        putJsonArray("roles") {
            matched.take(limit).forEach { agent ->
                addJsonObject {
                    put("name", agent.name)
                    put("department", agent.departmentId)
                    put("description", agent.description.take(200))
                }
            }
        }
        if (department == null) {
            putJsonArray("departments") {
                departments.sortedBy { it.sortOrder }.forEach { dept ->
                    addJsonObject {
                        put("id", dept.id)
                        put("name", dept.name)
                        put("roles", agents.count { it.departmentId == dept.id })
                    }
                }
            }
        }
    }.toString()
}
