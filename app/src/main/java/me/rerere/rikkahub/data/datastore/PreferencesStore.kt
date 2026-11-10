package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.gemini.DENIED_MODEL_IDS
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

enum class AutoCompactionThresholdMode {
    PERCENT,
    TOKENS,
}

/** Default automatic compaction summary target as a percentage of the active context. */
const val DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT = 1

private const val TAG = "PreferencesStore"

/**
 * Per-entry tolerant decode for the persisted `providers` list.
 *
 * Why this exists: the providers list is stored in DataStore as a single JSON array of
 * polymorphic [ProviderSetting] entries. A single-shot `decodeFromString<List<ProviderSetting>>`
 * throws on the entire list as soon as one element has an unknown polymorphic discriminator.
 *
 * Concrete trigger that motivated this: the never-shipped Phase-22A scaffolding seeded a
 * `"type":"local_llamacpp"` entry into DEFAULT_PROVIDERS for early test installs. Deleting
 * the `LlamaCppLocal` subclass would otherwise make decode-of-list throw on those entries
 * → user loses ALL their saved providers (API keys, custom models, the lot).
 *
 * Per-entry decode lets surviving entries land while the unknown one is logged and skipped.
 * Keep this even though `local_llamacpp` now ships: it's good hygiene for any future
 * polymorphic schema change (renamed types, removed types, etc).
 */
private fun decodeProvidersTolerant(raw: String): List<ProviderSetting> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        try {
            JsonInstant.decodeFromJsonElement<ProviderSetting>(element)
        } catch (e: SerializationException) {
            Log.w(TAG, "Skipping unrecognised provider entry during decode: ${e.message}")
            null
        }
    }
}

/**
 * `models` transform for the GeminiOAuth normalization branch below: de-duplicate by id, then
 * drop any entry whose modelId is in [DENIED_MODEL_IDS].
 *
 * The fetch-side filter in `GeminiProvider.fetchAvailableModels` only stops
 * `gemini-3.1-pro-high` being re-added; a copy already persisted before that filter existed
 * survives the merge in `mergeCodexModels` untouched. This runs on every settings load, so it
 * evicts the stale entry read-side, without a migration or rewriting the persisted JSON.
 */
private fun dropDeniedGeminiOAuthModels(models: List<Model>): List<Model> =
    models.distinctBy { model -> model.id }.filterNot { model -> model.modelId in DENIED_MODEL_IDS }

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val ENABLE_AUTO_COMPACTION = booleanPreferencesKey("enable_auto_compaction")
        val AUTO_COMPACTION_THRESHOLD_MODE = stringPreferencesKey("auto_compaction_threshold_mode")
        val AUTO_COMPACTION_THRESHOLD_PERCENT = intPreferencesKey("auto_compaction_threshold_percent")
        val AUTO_COMPACTION_THRESHOLD_TOKENS_K = intPreferencesKey("auto_compaction_threshold_tokens_k")
        val AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS = intPreferencesKey("auto_compaction_keep_recent_tool_calls")
        val CONTEXT_COMPACTION_TARGET_TOKENS_K = intPreferencesKey("context_compaction_target_tokens_k")
        val RESPONSE_STREAM_MAX_RETRIES = intPreferencesKey("response_stream_max_retries")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")
        // IDs of built-in providers the user explicitly deleted; the re-seed pass
        // skips these so deletions are sticky across app restarts.
        val DELETED_BUILTIN_PROVIDER_IDS = stringPreferencesKey("deleted_builtin_provider_ids")
        // Names of bundled skills the user explicitly deleted; the seed pass skips these
        // (see Settings.deletedBundledSkills). Missing key -> emptySet(), no migration.
        val DELETED_BUNDLED_SKILLS = stringPreferencesKey("deleted_bundled_skills")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val ENABLE_WEB_FETCH_TOOLS = booleanPreferencesKey("enable_web_fetch_tools")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // 子代理
        val SUB_AGENTS = stringPreferencesKey("sub_agents")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // AI logging
        val AI_LOG_LEVEL = stringPreferencesKey("ai_log_level")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // 宠物。val petPackId: String? 是个 nullable，DataStore 不能存 null，
        // 所以用空串表示「不显示」，读的时候再转回 null。
        val PET_PACK_ID = stringPreferencesKey("pet_pack_id")
        val PET_SIZE = stringPreferencesKey("pet_size")
        val PET_OFFSET_X = floatPreferencesKey("pet_offset_x")
        val CUSTOM_PET_PATH = stringPreferencesKey("custom_pet_path")
        val MAID_PET_ENABLED = booleanPreferencesKey("maid_pet_enabled")
        val MAID_PET_OFFSET_X = floatPreferencesKey("maid_pet_offset_x")
        val PET_PACK_IDS = stringPreferencesKey("pet_pack_ids")
        val PET_SIZES = stringPreferencesKey("pet_sizes")

        // 目标续跑。这两个之前也没持久化——加开关那次只改了内存，
        // persistSettings 的白名单没跟上，重启直接恢复默认。
        val ENABLE_GOAL_AUTO_CONTINUATION = booleanPreferencesKey("enable_goal_auto_continuation")
        val GOAL_CONTINUATION_LIMIT = intPreferencesKey("goal_continuation_limit")

        // 技能自动启用相关的两个内部标记
        val AUTO_ENABLED_DEFAULT_SKILLS = stringPreferencesKey("auto_enabled_default_skills")
        val AGENT_CORE_SEED_DONE = booleanPreferencesKey("agent_core_seed_done")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
        }

        private suspend fun persistSettings(dataStore: DataStore<Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
                preferences[DYNAMIC_COLOR] = settings.dynamicColor
                preferences[THEME_ID] = settings.themeId
                preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                preferences[DEVELOPER_MODE] = settings.developerMode
                preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(
                    settings.displaySetting.copy(
                        pasteLongTextThreshold = settings.displaySetting.pasteLongTextThreshold.coerceIn(100, 10000)
                    )
                )
                preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                preferences[SELECT_MODEL] = settings.chatModelId.toString()
                preferences[FAST_MODEL] = settings.fastModelId.toString()
                preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                preferences[TITLE_PROMPT] = settings.titlePrompt
                preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                preferences[OCR_MODEL] = settings.ocrModelId.toString()
                preferences[OCR_PROMPT] = settings.ocrPrompt
                preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
                preferences[COMPRESS_PROMPT] = settings.compressPrompt
                preferences[ENABLE_AUTO_COMPACTION] = settings.enableAutoCompaction
                preferences[AUTO_COMPACTION_THRESHOLD_MODE] = settings.autoCompactionThresholdMode.name
                preferences[AUTO_COMPACTION_THRESHOLD_PERCENT] =
                    settings.autoCompactionThresholdPercent.coerceIn(5, 95)
                preferences[AUTO_COMPACTION_THRESHOLD_TOKENS_K] =
                    settings.autoCompactionThresholdTokensK.coerceIn(1, Int.MAX_VALUE / 1_000)
                preferences[AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] =
                    settings.autoCompactionKeepRecentToolCalls.coerceIn(0, 1_000)
                settings.contextCompactionTargetTokensK?.let { targetTokensK ->
                    preferences[CONTEXT_COMPACTION_TARGET_TOKENS_K] =
                        targetTokensK.coerceIn(1, Int.MAX_VALUE / 1_000)
                } ?: preferences.remove(CONTEXT_COMPACTION_TARGET_TOKENS_K)
                preferences[RESPONSE_STREAM_MAX_RETRIES] = settings.responseStreamMaxRetries.coerceIn(0, 10)

                preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
                preferences[DELETED_BUILTIN_PROVIDER_IDS] = JsonInstant.encodeToString(
                    settings.deletedBuiltInProviderIds.map { it.toString() }.toSet()
                )
                preferences[DELETED_BUNDLED_SKILLS] = JsonInstant.encodeToString(settings.deletedBundledSkills)

                preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

                preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                // maxOf(0, size - 1) guards the empty-list case: a persisted "[]" for
                // search_services leaves searchServices empty (the ?: default only fires on a
                // missing key, not on an empty array), and coerceIn(0, -1) throws
                // IllegalArgumentException because min > max, crashing every settings write.
                preferences[SEARCH_SELECTED] =
                    settings.searchServiceSelected.coerceIn(0, maxOf(0, settings.searchServices.size - 1))
                preferences[ENABLE_WEB_FETCH_TOOLS] = settings.enableWebFetchTools

                preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                preferences[SUB_AGENTS] = JsonInstant.encodeToString(settings.subAgents)
                preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                settings.selectedTTSProviderId?.let {
                    preferences[SELECTED_TTS_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                settings.selectedASRProviderId?.let {
                    preferences[SELECTED_ASR_PROVIDER] = it.toString()
                } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                preferences[WEB_SERVER_PORT] = settings.webServerPort
                preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                preferences[AI_LOG_LEVEL] = settings.aiLogLevel.preferenceName
                preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                preferences[LAUNCH_COUNT] = settings.launchCount
                preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt

                // 宠物。persistSettings 是逐字段手写的白名单，
                // 新加的 Settings 字段必须在这里显式写一次，否则它永远存不下来——
                // 之前 petPackId 就是这样：改了内存、落了空。
                val petId = settings.petPackId
                if (petId.isNullOrBlank()) {
                    preferences.remove(PET_PACK_ID)
                } else {
                    preferences[PET_PACK_ID] = petId
                }
                preferences[PET_SIZE] = settings.petSize
                preferences[PET_OFFSET_X] = settings.petOffsetX
                settings.customPetPath?.let { preferences[CUSTOM_PET_PATH] = it }
                    ?: preferences.remove(CUSTOM_PET_PATH)
                preferences[MAID_PET_ENABLED] = settings.maidPetEnabled
                preferences[MAID_PET_OFFSET_X] = settings.maidPetOffsetX
                preferences[PET_PACK_IDS] = settings.petPackIds
                preferences[PET_SIZES] = settings.petSizes

                preferences[ENABLE_GOAL_AUTO_CONTINUATION] = settings.enableGoalAutoContinuation
                preferences[GOAL_CONTINUATION_LIMIT] = settings.goalContinuationLimit.coerceIn(1, 50)
                preferences[AUTO_ENABLED_DEFAULT_SKILLS] =
                    JsonInstant.encodeToString(settings.autoEnabledDefaultSkills)
                preferences[AGENT_CORE_SEED_DONE] = settings.agentCoreSeedDone
            }
        }
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Uuid>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode favoriteModels, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_ASSISTANT_ID,
                enableAutoCompaction = preferences[ENABLE_AUTO_COMPACTION] == true,
                autoCompactionThresholdMode = preferences[AUTO_COMPACTION_THRESHOLD_MODE]
                    ?.let { value -> runCatching { AutoCompactionThresholdMode.valueOf(value) }.getOrNull() }
                    ?: AutoCompactionThresholdMode.PERCENT,
                autoCompactionThresholdPercent = (preferences[AUTO_COMPACTION_THRESHOLD_PERCENT] ?: 80)
                    .coerceIn(5, 95),
                autoCompactionThresholdTokensK = (preferences[AUTO_COMPACTION_THRESHOLD_TOKENS_K] ?: 8)
                    .coerceIn(1, Int.MAX_VALUE / 1_000),
                autoCompactionKeepRecentToolCalls =
                    (preferences[AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] ?: 5).coerceIn(0, 1_000),
                contextCompactionTargetTokensK = preferences[CONTEXT_COMPACTION_TARGET_TOKENS_K]
                    ?.coerceIn(1, Int.MAX_VALUE / 1_000),
                responseStreamMaxRetries = (preferences[RESPONSE_STREAM_MAX_RETRIES] ?: 5)
                    .coerceIn(0, 10),
                assistantTags = preferences[ASSISTANT_TAGS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Tag>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode assistantTags, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                providers = decodeProvidersTolerant(preferences[PROVIDERS] ?: "[]"),
                deletedBuiltInProviderIds = preferences[DELETED_BUILTIN_PROVIDER_IDS]
                    ?.let { raw ->
                        runCatching {
                            JsonInstant.decodeFromString<Set<String>>(raw)
                                .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
                                .toSet()
                        }.getOrNull()
                    } ?: emptySet(),
                deletedBundledSkills = preferences[DELETED_BUNDLED_SKILLS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<Set<String>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode deletedBundledSkills, using default", it)
                        emptySet()
                    }
                } ?: emptySet(),
                assistants = runCatching {
                    JsonInstant.decodeFromString<List<Assistant>>(preferences[ASSISTANTS] ?: "[]")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode assistants, using default", it)
                    emptyList()
                },
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<CustomTheme>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode customThemes, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = runCatching {
                    JsonInstant.decodeFromString<DisplaySetting>(preferences[DISPLAY_SETTING] ?: "{}")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode displaySetting, using default", it)
                    DisplaySetting()
                },
                networkSetting = runCatching {
                    JsonInstant.decodeFromString<NetworkSetting>(preferences[NETWORK_SETTING] ?: "{}")
                }.getOrElse {
                    Log.w(TAG, "Failed to decode networkSetting, using default", it)
                    NetworkSetting()
                },
                searchServices = preferences[SEARCH_SERVICES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<SearchServiceOptions>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode searchServices, using default", it)
                        listOf(SearchServiceOptions.DEFAULT)
                    }
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<SearchCommonOptions>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode searchCommonOptions, using default", it)
                        SearchCommonOptions()
                    }
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                enableWebFetchTools = preferences[ENABLE_WEB_FETCH_TOOLS] != false,
                mcpServers = preferences[MCP_SERVERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<McpServerConfig>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode mcpServers, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                // #36: key absent -> emptyList(), exactly like mcpServers above - the
                // whole migration for an existing install that predates this field.
                subAgents = preferences[SUB_AGENTS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<SubAgentProfile>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode subAgents, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<WebDavConfig>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode webDavConfig, using default", it)
                        WebDavConfig()
                    }
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<S3Config>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode s3Config, using default", it)
                        S3Config()
                    }
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<TTSProviderSetting>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode ttsProviders, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<ASRProviderSetting>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode asrProviders, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                modeInjections = preferences[MODE_INJECTIONS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode modeInjections, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<Lorebook>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode lorebooks, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<QuickMessage>>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode quickMessages, using default", it)
                        emptyList()
                    }
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                aiLogLevel = AiLogLevel.fromPreference(preferences[AI_LOG_LEVEL]),
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<BackupReminderConfig>(raw) }.getOrElse {
                        Log.w(TAG, "Failed to decode backupReminderConfig, using default", it)
                        BackupReminderConfig()
                    }
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                petPackId = preferences[PET_PACK_ID]?.takeIf { it.isNotBlank() },
                petSize = preferences[PET_SIZE] ?: "medium",
                petOffsetX = preferences[PET_OFFSET_X] ?: 0f,
                customPetPath = preferences[CUSTOM_PET_PATH]?.takeIf { it.isNotBlank() },
                // 老版本把女仆当素材包存进 petPackId（值 "maid-deepseek"）。
                // 现在它是独立开关，读到老值就顺手迁过来，不然升级后那只会消失。
                maidPetOffsetX = preferences[MAID_PET_OFFSET_X] ?: 0f,
                // 多选列表。老版本只有单个 petPackId（还有一版把女仆也塞在里面），
                // 读到旧的单值就合并进来，升级后不至于一只都不剩。
                petPackIds = preferences[PET_PACK_IDS] ?: buildString {
                    preferences[PET_PACK_ID]?.takeIf { it.isNotBlank() }?.let { append(it) }
                    if (preferences[MAID_PET_ENABLED] == true) {
                        if (isNotEmpty()) append(',')
                        append("maid-deepseek")
                    }
                },
                petSizes = preferences[PET_SIZES] ?: "",
                maidPetEnabled = preferences[MAID_PET_ENABLED]
                    ?: (preferences[PET_PACK_ID] == "maid-deepseek"),
                enableGoalAutoContinuation =
                    preferences[ENABLE_GOAL_AUTO_CONTINUATION] == true,
                goalContinuationLimit =
                    (preferences[GOAL_CONTINUATION_LIMIT] ?: 5).coerceIn(1, 50),
                autoEnabledDefaultSkills = preferences[AUTO_ENABLED_DEFAULT_SKILLS]?.let { raw ->
                    runCatching {
                        JsonInstant.decodeFromString<Set<String>>(raw)
                    }.getOrElse {
                        Log.w(TAG, "Failed to decode autoEnabledDefaultSkills", it)
                        emptySet()
                    }
                } ?: emptySet(),
                agentCoreSeedDone = preferences[AGENT_CORE_SEED_DONE] == true,
            )
        }
        .map {
            val deletedDefaultIds = it.deletedBuiltInProviderIds
            var providers = it.providers.ifEmpty {
                DEFAULT_PROVIDERS.filter { p -> p.id !in deletedDefaultIds }
            }.toMutableList()
            // For existing installs that pre-date the on-device AICore provider being
            // promoted to first-place, hoist it to the top so the user does not have to
            // scroll past every legacy aggregator to find it.
            val aicoreIndex = providers.indexOfFirst { it is ProviderSetting.AICore }
            if (aicoreIndex > 0) {
                val aicoreRow = providers.removeAt(aicoreIndex)
                providers.add(0, aicoreRow)
            }
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (defaultProvider.id in deletedDefaultIds) return@forEach
                if (providers.none { it.id == defaultProvider.id }) {
                    // On-device built-in providers (AICore, LiteRT) are pinned to the top of
                    // the list in the order they appear in DEFAULT_PROVIDERS. Remote provider
                    // defaults continue to append at the end so existing users see no
                    // reordering of their configured remote providers.
                    when (defaultProvider) {
                        is ProviderSetting.AICore -> providers.add(0, defaultProvider.copyProvider())
                        is ProviderSetting.LiteRtLocal -> {
                            // Insert right after AICore, or at 0 if AICore is absent.
                            // indexOfFirst returns -1 when absent; -1 + 1 = 0, so insert at 0.
                            val insertAt = providers.indexOfFirst { it is ProviderSetting.AICore } + 1
                            providers.add(insertAt, defaultProvider.copyProvider())
                        }
                        is ProviderSetting.LlamaCppLocal -> {
                            // Insert right after LiteRtLocal, so it groups with the other
                            // on-device provider instead of appending after every remote
                            // provider below. Falls back to right after AICore, or 0, if
                            // LiteRtLocal is absent - same absent-index fallback as above.
                            val insertAt = providers.indexOfFirst { it is ProviderSetting.LiteRtLocal }
                                .let { if (it >= 0) it + 1 else providers.indexOfFirst { p -> p is ProviderSetting.AICore } + 1 }
                            providers.add(insertAt, defaultProvider.copyProvider())
                        }
                        else -> providers.add(defaultProvider.copyProvider())
                    }
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            var assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            // agent-core 自动勾选已废弃。
            //
            // 原来是「默认助手启用列表为空 → 判定为新装 → 勾上 agent-core」。
            // 两个问题：
            //   1. 用户手动把唯一勾着的 agent-core 关掉之后，列表恰好变空，
            //      下次启动又被判定成新装，再勾回去。关一次开一次，永远关不掉。
            //   2. 默认助手模板本身已经不预勾了（见 DEFAULT_ASSISTANTS），
            //      这个迁移的前提不存在了。
            //
            // 所以整段删掉。agentCoreSeedDone 只写不读，留着是为了
            // 记住「这个历史迁移已经作废」，以后不再有人把它加回来。
            //
            // 已经装过又想要这个技能的用户，自己在 Skills 面板里打开就行——
            // 它一直在「内置」分类的第一位。
            // One-shot additive enable for newly-bundled default-on skills. Each name is added
            // to every default assistant exactly once, tracked in autoEnabledDefaultSkills, so a
            // user who later disables one is not re-opted-in on the next launch. A brand-new
            // skill cannot have been deliberately disabled before it shipped, so the first add is
            // always safe.
            //
            // 现在 DEFAULT_AUTO_ENABLED_SKILLS 是空的（用户要求「默认一个都不勾」），
            // 所以下面这段实际上不会再加任何东西。真正要做的是**反悔**：
            // 老版本已经自动开了 autonomous-agent / openclaw-converter 的，
            // 这里把它们从启用列表里摘掉一次。只摘这两个具名技能，
            // 用户自己勾的别的技能一律不动。
            val previouslyAutoEnabled = it.autoEnabledDefaultSkills
            if (previouslyAutoEnabled.isNotEmpty()) {
                assistants = assistants.map { assistant ->
                    if (DEFAULT_ASSISTANTS.any { d -> d.id == assistant.id }) {
                        assistant.copy(enabledSkills = assistant.enabledSkills - previouslyAutoEnabled)
                    } else assistant
                }.toMutableList()
            }
            val skillsToSeed = DEFAULT_AUTO_ENABLED_SKILLS - it.autoEnabledDefaultSkills
            if (skillsToSeed.isNotEmpty()) {
                assistants = assistants.map { assistant ->
                    if (DEFAULT_ASSISTANTS.any { d -> d.id == assistant.id }) {
                        assistant.copy(enabledSkills = assistant.enabledSkills + skillsToSeed)
                    } else assistant
                }.toMutableList()
            }
            val newAutoEnabled = it.autoEnabledDefaultSkills + DEFAULT_AUTO_ENABLED_SKILLS
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                autoEnabledDefaultSkills = newAutoEnabled,
                agentCoreSeedDone = true,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.AICore -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LiteRtLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LlamaCppLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Codex -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Grok -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.GeminiOAuth -> provider.copy(
                            models = dropDeniedGeminiOAuthModels(provider.models)
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        transformLock.withLock {
            updateInternal(settings)
        }
    }

    /**
     * Unlocked write path: every caller must already hold [transformLock]. Writes disk
     * before memory: if `dataStore.edit` throws (IOException, disk full, a serialization
     * bug), `settingsFlow` must still match what's actually on disk rather than a value
     * that was never persisted, or observers would react to a change that silently rolls
     * back on the next app launch.
     */
    private suspend fun updateInternal(settings: Settings) {
        persistSettings(dataStore, settings)
        settingsFlow.value = settings
    }

    /** Serialises every settings write: both transform-based `update {fn}` calls and
     *  direct `update(Settings)` calls (e.g. a WebDAV/S3 restore replacing the whole
     *  settings object), so concurrent callers don't race each other. Without this lock,
     *  two writes dispatched in quick succession could both read `settingsFlow.value`
     *  before either has persisted, then each write its own delta off the same stale
     *  base: last writer wins, the earlier change is silently dropped. The most-visible
     *  repro: rapid-fire taps on per-assistant tool toggles where every other tap
     *  appeared to revert; a restore racing an in-flight transform update is the same
     *  class of bug with worse stakes. */
    private val transformLock = kotlinx.coroutines.sync.Mutex()

    suspend fun update(fn: (Settings) -> Settings) {
        transformLock.withLock {
            updateInternal(fn(settingsFlow.value))
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val enableAutoCompaction: Boolean = false,
    // 目标自动续跑。默认关：开着但没人看就是烧钱机器。
    // 原实现默认 true，这里刻意反过来。
    val enableGoalAutoContinuation: Boolean = false,
    // 一个目标最多自动续几轮，到上限就自动暂停。
    val goalContinuationLimit: Int = 5,
    val autoCompactionThresholdMode: AutoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT,
    val autoCompactionThresholdPercent: Int = 80,
    val autoCompactionThresholdTokensK: Int = 8,
    /** Number of most-recent executed tool calls kept raw during the first automatic pass. */
    val autoCompactionKeepRecentToolCalls: Int = 5,
    /**
     * Null uses [DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT] of the active context. A non-null
     * value is an explicit summary target stored in thousands of tokens for easy mobile editing.
     */
    val contextCompactionTargetTokensK: Int? = null,
    /** Additional attempts for Response API streams that fail before yielding content. */
    val responseStreamMaxRetries: Int = 5,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    /**
     * IDs of built-in providers the user explicitly removed via long-press. The re-seed
     * pass on settings load skips these so deletions stick across app restarts. Without
     * this gate, deleting a default provider would just re-add it on next read.
     */
    val deletedBuiltInProviderIds: Set<Uuid> = emptySet(),
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    /**
     * Names of bundled default-on skills (see [DEFAULT_AUTO_ENABLED_SKILLS]) that have already
     * been seeded into the default assistants' enabledSkills exactly once. Mirrors
     * [deletedBuiltInProviderIds]: it lets a newly-shipped skill auto-enable on upgrade while
     * still respecting a later deliberate user-disable - once a name is recorded here it is
     * never re-added, so toggling it off sticks across launches.
     */
    /**
     * 当前显示的宠物包 id（`assets/pets/<id>/`）。
     *
     * null 表示不显示——默认就是这样。宠物是个可选的外观组件，
     * 不该默认糊在每个人脸上。
     */
    val petPackId: String? = null,

    /** 宠物尺寸档：`small` / `medium` / `large`，按身体高度分。 */
    val petSize: String = "medium",

    /**
     * 宠物相对输入框中心的横向偏移（像素）。
     *
     * 拖到哪就记到哪，切页面 / 重启都保持。0 表示居中。
     * 实际值在渲染时会 coerce 回合法范围——换设备或换尺寸档之后
     * 旧值可能超出新的可用宽度。
     */
    val petOffsetX: Float = 0f,

    /**
     * 鲸鱼娘女仆是否同时显示。
     *
     * 跟 [petPackId] / [customPetPath] **不互斥**——这是它跟另外两种的区别：
     * 素材包只能选一只，但女仆可以跟任意一只并排站着。
     * 她是独立的、带自己交互（点一下查余额），不是素材包的一种。
     */
    val maidPetEnabled: Boolean = false,

    /** 哦鲸鲸的横向位置（像素），拖到哪记到哪。0 = 居中。 */
    val maidPetOffsetX: Float = 0f,

    /**
     * 同时显示的宠物 id，逗号分隔。
     *
     * 为什么不是 Set<String>：Settings 是 data class + DataStore 逐字段存的，
     * 塞集合要自己序列化。逗号串够用，而且读出错了好恢复。
     */
    val petPackIds: String = "",

    /**
     * 每只宠物的自定义高度，格式 `id:dp,id:dp`。
     *
     * 之前是全局三档（22/30/40dp），一是三档差别太小看不出来，
     * 二是没法给不同的宠物配不同大小。现在是每只一个滑块，16~96dp。
     */
    val petSizes: String = "",

    /**
     * 用户自己那张图当宠物时，图片在 App 私有目录里的绝对路径。
     *
     * 为什么不存 content:// URI：相册给的那个 URI 权限是一次性的，
     * 重启之后就读不到了。选图时复制一份到 files/pets/custom/ 下，
     * 存自己的路径才活得久。
     *
     * null = 用内置素材包（[petPackId]）。
     */
    val customPetPath: String? = null,

    val autoEnabledDefaultSkills: Set<String> = emptySet(),

    /**
     * 「默认助手自动勾上 agent-core」这个一次性迁移是否已经执行过。
     *
     * 为什么需要这个而不是靠 `enabledSkills.isEmpty()` 判断：
     * 用户手动把 agent-core 关掉之后，启用列表**恰好就是空的**——
     * 用「空」当「没迁移过」的信号，下一次启动又会把它勾回去，
     * 关一次开一次，永远关不掉。这个标志把「迁移做过了」和
     * 「当前没启用任何技能」两件事分开。
     */
    val agentCoreSeedDone: Boolean = false,
    /**
     * Names of bundled skills the user explicitly deleted via [SkillManager.deleteSkill].
     * Recorded outside the skill directory (deletion wipes that directory, sentinel and
     * all), so [SkillManager.seedDefaultSkillsIfNeeded] can tell "never seeded" apart from
     * "seeded, then deleted" and skip re-creating it. A deliberate reinstall through the
     * skill catalog removes the name again.
     */
    val deletedBundledSkills: Set<String> = emptySet(),
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val enableWebFetchTools: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * #36: named sub-agent profiles the model can dispatch to by name via
     * `subagent_dispatch`'s `agent` parameter. MUST default to an empty list so an install
     * that predates this field decodes cleanly - see [SettingsStore.settingsFlowRaw].
     */
    val subAgents: List<SubAgentProfile> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val aiLogLevel: AiLogLevel = AiLogLevel.INFO,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class AiLogLevel(val preferenceName: String) {
    @SerialName("off")
    OFF("off"),
    @SerialName("info")
    INFO("info"),
    @SerialName("debug")
    DEBUG("debug");

    companion object {
        fun fromPreference(value: String?): AiLogLevel = entries.firstOrNull { it.preferenceName == value } ?: INFO
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

private const val FALLBACK_CONTEXT_COMPACTION_TARGET_TOKENS = 2_000

/** Returns the configured summary target, defaulting to 1% of the active context. */
fun Settings.getContextCompactionTargetTokens(contextLength: Int?): Int {
    val configured = contextCompactionTargetTokensK
        ?.toLong()
        ?.coerceAtLeast(1L)
        ?.times(1_000L)
    if (configured != null) {
        return configured.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return contextLength
        ?.takeIf { it > 0 }
        ?.let { length ->
            (length.toLong() * DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT / 100L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
        ?: FALLBACK_CONTEXT_COMPACTION_TARGET_TOKENS
}

/** Uses the token-threshold setting as the model context ceiling when that mode is active. */
fun Settings.getCompactionContextLength(model: Model?): Int? =
    autoCompactionThresholdTokensK
        .takeIf { autoCompactionThresholdMode == AutoCompactionThresholdMode.TOKENS }
        ?.toLong()
        ?.coerceAtLeast(1L)
        ?.times(1_000L)
        ?.coerceAtMost(Int.MAX_VALUE.toLong())
        ?.toInt()
        ?: model?.contextLength

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.findAssistantById(id: Uuid): Assistant? {
    return this.assistants.firstOrNull { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

// ── 宠物多选 / 自定义尺寸 ────────────────────────────────────────────────

/** 同时显示的宠物 id。 */
fun Settings.petIdList(): List<String> =
    petPackIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 某只宠物自定义的高度（dp）。
 *
 * 没设过就用 30——那是原来 MEDIUM 的值，升级上来的用户看到的还是原来大小。
 * 夹在 16~96：再小看不出是什么，再大能把输入框顶出屏幕。
 */
fun Settings.petHeightOf(id: String): Float =
    petSizes.split(',')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$id:") }
        ?.substringAfter(':')
        ?.toFloatOrNull()
        ?.coerceIn(16f, 96f)
        ?: 30f

/** 改某只的高度，返回新的 [Settings.petSizes] 串。 */
fun Settings.withPetHeight(id: String, dp: Float): String {
    val kept = petSizes.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("$id:") }
    return (kept + "$id:${dp.coerceIn(16f, 96f)}").joinToString(",")
}

/** 勾上或取消一只，返回新的 [Settings.petPackIds] 串。 */
fun Settings.withPetToggled(id: String, on: Boolean): String {
    val ids = petIdList().toMutableList()
    if (on) {
        if (!ids.contains(id)) ids.add(id)
    } else {
        ids.remove(id)
    }
    return ids.joinToString(",")
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

/**
 * Bundled skills shipped enabled-by-default on top of agent-core. The behavioral
 * `autonomous-agent` skill is auto_load (injected every turn); `openclaw-converter` is lazy
 * (loaded on demand). Both are seeded to disk by [SkillManager.seedDefaultSkillsIfNeeded] and
 * added to the default assistants' enabledSkills once via [Settings.autoEnabledDefaultSkills].
 */
internal val DEFAULT_AUTO_ENABLED_SKILLS = emptySet<String>()

internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = "",
        // The agent-core skill bundle (SOUL/HEARTBEAT/TOOLS) ships with the app and is what
        // teaches every model "you are running on RikkaHub, here are the tools, here is how
        // to avoid loops".
        //
        // **默认不勾。** 模板助手的初始状态应该跟用户自己新建的一致——
        // 一个空名单。想开就在 Skills 面板里自己打开，而不是被预置一个
        // 他不知道为什么开着的技能。
        enabledSkills = DEFAULT_AUTO_ENABLED_SKILLS,
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent(),
        enabledSkills = DEFAULT_AUTO_ENABLED_SKILLS,
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
