package me.rerere.rikkahub.data.plan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

/**
 * `update_plan` 工具：让模型在执行期维护一张进度检查表。
 *
 * 和 Plan 模式是两回事，别混：
 *  - Plan 模式 = 只规划不执行，产出 `<proposed_plan>`
 *  - update_plan = 执行期的 TODO 表，边做边勾
 *
 * Plan 模式下调用它会被 [PlanModePolicy] 直接拒掉。
 */
fun planTools(conversationKey: String?): List<Tool> {
    val key = conversationKey ?: DEFAULT_KEY
    return listOf(updatePlanTool(key))
}

/** 拿不到会话 id 时的兜底 key，一个进程内共用一个计划。 */
private const val DEFAULT_KEY = "__default__"

fun updatePlanTool(key: String): Tool = Tool(
    name = PlanToolCatalog.UPDATE_PLAN,
    description = PlanToolCatalog.DESCRIPTION,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("explanation", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional: why the plan changed. Max ${PlanToolCatalog.MAX_EXPLANATION_CHARS} characters."
                    )
                })
                put("plan", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "1..${PlanToolCatalog.MAX_STEPS} steps. At most one may be in_progress."
                    )
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("step", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "What this step does. 1..${PlanToolCatalog.MAX_STEP_CHARS} characters."
                                )
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(listOf(
                                    kotlinx.serialization.json.JsonPrimitive("pending"),
                                    kotlinx.serialization.json.JsonPrimitive("in_progress"),
                                    kotlinx.serialization.json.JsonPrimitive("completed"),
                                )))
                            })
                        })
                        put("required", JsonArray(listOf(
                            kotlinx.serialization.json.JsonPrimitive("step"),
                            kotlinx.serialization.json.JsonPrimitive("status"),
                        )))
                    })
                })
            },
            required = listOf("plan")
        )
    },
    execute = { input ->
        val parsed = parseAndValidate(input.jsonObject)
        when (parsed) {
            is PlanParseResult.Failure -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("error", parsed.code)
                        put("message", parsed.message)
                    }.toString()
                )
            )

            is PlanParseResult.Success -> {
                PlanStore.set(
                    key,
                    PlanSnapshot(
                        steps = parsed.steps,
                        explanation = parsed.explanation,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                val incomplete = parsed.steps.count { it.status != PlanStepStatus.COMPLETED }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("steps", parsed.steps.size)
                            put("completed", parsed.steps.count { it.status == PlanStepStatus.COMPLETED })
                            put(
                                "in_progress",
                                parsed.steps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }?.step ?: ""
                            )
                            // 还有没做完的步骤时明确提一句。
                            // 实测里模型经常做完活就停，计划停在 2/5 不动——
                            // 它不知道用户看到的是个卡住的进度条。
                            if (incomplete > 0) {
                                put(
                                    "note",
                                    "还有 $incomplete 步没完成。全部做完时记得再调一次 update_plan，" +
                                        "把所有步骤标成 completed；做到哪一步就把那一步标 completed，" +
                                        "下一步标 in_progress。"
                                )
                            }
                        }.toString()
                    )
                )
            }
        }
    }
)

sealed interface PlanParseResult {
    data class Success(
        val steps: List<PlanStep>,
        val explanation: String?,
    ) : PlanParseResult

    data class Failure(val code: String, val message: String) : PlanParseResult
}

/**
 * 解析 + 校验。抽成纯函数是为了能单测——本地没有 Android 环境，
 * 所有能脱离 Context 的逻辑都要能单独跑。
 */
fun parseAndValidate(input: JsonObject): PlanParseResult {
    val explanation = input["explanation"]?.jsonPrimitive?.contentOrNull
    if (explanation != null && explanation.length > PlanToolCatalog.MAX_EXPLANATION_CHARS) {
        return PlanParseResult.Failure(
            PlanToolCatalog.ERR_EXPLANATION_TOO_LONG,
            "explanation exceeds ${PlanToolCatalog.MAX_EXPLANATION_CHARS} characters " +
                "(got ${explanation.length})"
        )
    }

    val arr = input["plan"] as? JsonArray
        ?: return PlanParseResult.Failure(
            PlanToolCatalog.ERR_TOO_FEW_STEPS,
            "`plan` is required and must be an array"
        )

    if (arr.size < PlanToolCatalog.MIN_STEPS) {
        return PlanParseResult.Failure(
            PlanToolCatalog.ERR_TOO_FEW_STEPS,
            "plan needs at least ${PlanToolCatalog.MIN_STEPS} step"
        )
    }
    if (arr.size > PlanToolCatalog.MAX_STEPS) {
        return PlanParseResult.Failure(
            PlanToolCatalog.ERR_TOO_MANY_STEPS,
            "plan has ${arr.size} steps, max is ${PlanToolCatalog.MAX_STEPS}"
        )
    }

    val steps = mutableListOf<PlanStep>()
    var inProgress = 0

    arr.forEachIndexed { i, el ->
        val obj = el as? JsonObject ?: return PlanParseResult.Failure(
            PlanToolCatalog.ERR_BAD_STATUS,
            "step ${i + 1} is not an object"
        )
        val rawStep = obj["step"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val step = rawStep.trim()
        if (step.length < PlanToolCatalog.MIN_STEP_CHARS) {
            return PlanParseResult.Failure(
                PlanToolCatalog.ERR_STEP_BLANK,
                "step ${i + 1} is blank"
            )
        }
        if (step.length > PlanToolCatalog.MAX_STEP_CHARS) {
            return PlanParseResult.Failure(
                PlanToolCatalog.ERR_STEP_TOO_LONG,
                "step ${i + 1} is ${step.length} chars, max is ${PlanToolCatalog.MAX_STEP_CHARS}"
            )
        }
        val statusRaw = obj["status"]?.jsonPrimitive?.contentOrNull
        val status = PlanStepStatus.fromWire(statusRaw)
            ?: return PlanParseResult.Failure(
                PlanToolCatalog.ERR_BAD_STATUS,
                "step ${i + 1} has invalid status `$statusRaw`; " +
                    "must be pending / in_progress / completed"
            )
        if (status == PlanStepStatus.IN_PROGRESS) inProgress++
        steps += PlanStep(index = i + 1, step = step, status = status)
    }

    // schema 里写了「最多一个 in_progress」，但提示词不是强约束——
    // 这里真的拒，否则模型会给出「三条同时进行中」的表。
    if (inProgress > 1) {
        return PlanParseResult.Failure(
            PlanToolCatalog.ERR_TOO_MANY_IN_PROGRESS,
            "found $inProgress steps with status in_progress; at most one is allowed"
        )
    }

    return PlanParseResult.Success(steps = steps, explanation = explanation?.trim()?.ifBlank { null })
}
