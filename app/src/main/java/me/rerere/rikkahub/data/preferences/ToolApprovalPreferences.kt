package me.rerere.rikkahub.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "ToolApprovalPreferences"

/** Workspace tools (created by createWorkspaceTools) are per-workspace, never
 *  per-app, so this prefix must never be granted or read from the global
 *  always-allow set. */
private const val WORKSPACE_TOOL_PREFIX = "workspace_"

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

/** True iff [name] is a workspace tool (reserved "workspace_" prefix, see
 *  createWorkspaceTools). Pure so the prefix rule can be unit-tested without a
 *  DataStore instance. */
internal fun isWorkspaceToolName(name: String): Boolean = name.startsWith(WORKSPACE_TOOL_PREFIX)

/** Splits [stored] into (kept, removed) where removed is every workspace_-prefixed
 *  entry. Pure - backs both the read-time filter and the one-shot migration below. */
internal fun migrateWorkspaceToolsFrom(stored: Set<String>): Pair<Set<String>, Set<String>> {
    val removed = stored.filterTo(mutableSetOf(), ::isWorkspaceToolName)
    return (stored - removed) to removed
}

/**
 * Persistent tool-approval prefs. Two pieces:
 *
 * 1. **"Always Allow" allow-list** — per-tool grants that survive app restart until the
 *    user revokes them from Settings → Tool approvals. The HARDLINE floor still applies
 *    even with Always Allow granted.
 *
 * 2. **"I AM STUPID" global auto-approve** — single boolean that, when true, treats every
 *    tool as pre-approved across every conversation and every surface (in-app, Telegram,
 *    cron). HARDLINE STILL APPLIES — there is no override for that. This is the user's
 *    explicit "I trust the agent fully, stop asking me" escape hatch. Surfaced as a
 *    bright-red toggle behind a confirm dialog because it's a live foot-gun.
 *
 * 3. **无审核模式** — 比 YOLO 更彻底。YOLO 免审批但 HARDLINE 和越界预拒还在，
 *    root_shell / shizuku_exec 也不受它影响。无审核模式把审批、HARDLINE、
 *    越界预拒**全部关掉**，只保留快照（那是唯一的退路）。入口藏在
 *    「关于页连点 5 下头像 → 工具授权页 → 危险授权」，开启要手打一句话确认。
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tool_names")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")
    private val K_DANGEROUS_ENABLED = booleanPreferencesKey("dangerous_mode_enabled")
    private val K_DANGEROUS_UNLOCKED = booleanPreferencesKey("dangerous_mode_unlocked")

    // Filtered on every read: a stale workspace_-prefixed entry (from before this
    // filtering existed) must never resurface in the settings list or an auto-approve
    // check, even before the lazy migration below gets a chance to persist the cleanup.
    val alwaysAllowFlow: Flow<Set<String>> = store.data.map {
        migrateWorkspaceToolsFrom(it[K_ALWAYS_ALLOW].orEmpty()).first
    }

    /** Live flow of the "I AM STUPID" global auto-approve flag. Default false. */
    val globalYoloFlow: Flow<Boolean> = store.data.map {
        it[K_GLOBAL_YOLO] ?: false
    }

    suspend fun current(): Set<String> = migrateAwayWorkspaceTools()

    /** Snapshot read of the YOLO flag. Used by the per-call auto-approval check. */
    suspend fun currentYolo(): Boolean = globalYoloFlow.first()

    suspend fun setYolo(enabled: Boolean) {
        store.edit { it[K_GLOBAL_YOLO] = enabled }
    }

    suspend fun grantAlways(toolName: String) {
        // Workspace tools are per-workspace, not per-app; ChatService routes those
        // through WorkspaceRepository.setToolApproval instead, so this should never be
        // called with one. Refuse rather than silently poisoning the global set.
        if (isWorkspaceToolName(toolName)) {
            Log.w(TAG, "refusing to add workspace tool '$toolName' to the global always-allow set")
            return
        }
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) + toolName }
    }

    /** One-shot lazy migration: strips any stale workspace_-prefixed entry out of the
     *  persisted always-allow set (left over from before this filtering existed) and
     *  persists the cleanup. Cheap on the steady-state path: only opens a write
     *  transaction when there is actually something to remove. */
    private suspend fun migrateAwayWorkspaceTools(): Set<String> {
        val stored = store.data.first()[K_ALWAYS_ALLOW].orEmpty()
        val (_, removed) = migrateWorkspaceToolsFrom(stored)
        if (removed.isEmpty()) return stored
        // Return the post-edit snapshot, not the pre-edit `stored` read above: a concurrent
        // grantAlways landing between that read and this edit is persisted by DataStore's
        // read-modify-write but would otherwise be dropped from this call's return value.
        val result = store.edit { prefs ->
            val (cleaned, actuallyRemoved) = migrateWorkspaceToolsFrom(prefs[K_ALWAYS_ALLOW].orEmpty())
            if (actuallyRemoved.isNotEmpty()) {
                Log.i(TAG, "removing stale workspace tool grants from always-allow: $actuallyRemoved")
                prefs[K_ALWAYS_ALLOW] = cleaned
            }
        }
        return migrateWorkspaceToolsFrom(result[K_ALWAYS_ALLOW].orEmpty()).first
    }

    suspend fun revoke(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) - toolName }
    }

    suspend fun revokeAll() {
        store.edit { it.remove(K_ALWAYS_ALLOW) }
    }

    // ── 无审核模式 ──
    //
    // 跟 YOLO 不是一回事。YOLO 只免审批，HARDLINE 和越界预拒仍然生效，
    // 而且 root_shell / shizuku_exec 根本不受它影响。无审核模式连那两道
    // 一起关掉——它关的是**最后两道防线**，所以入口藏得深、开启要多打一句话。

    /** 入口是否已解锁（关于页连点 5 下头像）。只控制按钮出不出现。 */
    val dangerousUnlockedFlow: Flow<Boolean> = store.data.map {
        it[K_DANGEROUS_UNLOCKED] ?: false
    }

    /** 无审核模式是否开启。默认 false。 */
    val dangerousEnabledFlow: Flow<Boolean> = store.data.map {
        it[K_DANGEROUS_ENABLED] ?: false
    }

    suspend fun currentDangerousEnabled(): Boolean = dangerousEnabledFlow.first()

    suspend fun currentDangerousUnlocked(): Boolean = dangerousUnlockedFlow.first()

    /** 解锁入口。只设一次，没有反向操作——锁回去没有意义，关开关就够了。 */
    suspend fun unlockDangerousMode() {
        store.edit { it[K_DANGEROUS_UNLOCKED] = true }
    }

    suspend fun setDangerousMode(enabled: Boolean) {
        store.edit {
            it[K_DANGEROUS_ENABLED] = enabled
            // 开启即视为解锁过，免得下次进来又要连点五下
            if (enabled) it[K_DANGEROUS_UNLOCKED] = true
        }
    }
}
