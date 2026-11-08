package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * One searchable entry in the settings hub. [titleRes] prefers the destination page's own
 * top-bar title over the hub row's headline when the two differ in wording, so a search for
 * either term finds the page.
 *
 * [keywords] 是**页面内部**的重要条目名，用来解决「搜得到页面、搜不到页面里的开关」。
 * 这一层索引只到页面级，所以一个开关如果不在标题或描述里出现，用户搜它就一无所获——
 * 比如「目标自动续跑」在「常规」页里、藏在第三屏，「让 AI 写技能」在助手详情页里。
 * 把这些词补进 keywords，命中后仍然跳整页，但至少能找到地方。
 *
 * **不要给「危险授权 / 无审批模式」加关键词。** 那个入口是刻意藏起来的：
 * 关于页连点 5 下才出现，而且就算解锁了也不该被搜索发现——
 * 用户能搜到就说明它不该只靠「不知道在哪」来防护，那是另一套设计。
 * 现在这套是「知道的人知道，不知道的人翻不到」，搜索会破坏这个前提。
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes val groupRes: Int,
    val route: Screen,
    /** 页面内部的关键词。纯文本，不走 string 资源——它们只用于匹配，不显示。 */
    val keywords: List<String> = emptyList(),
)

/**
 * Static mirror of the hub rows in [SettingPage] and [SettingPreferencesPage]. This list is
 * NOT derived from those pages, so it drifts if a row is added, removed, or renamed there
 * without a matching edit here (see the reminder comment in SettingPage.kt).
 */
fun settingsSearchIndex(developerMode: Boolean): List<SettingsSearchEntry> {
    val entries = mutableListOf(
        // General settings
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences,
            descriptionRes = R.string.setting_page_preferences_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.SettingPreferences,
        ),
        SettingsSearchEntry(
            titleRes = R.string.assistant_page_title,
            descriptionRes = R.string.setting_page_assistant_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.Assistant,
            keywords = listOf(
                // 本地工具里的开关
                "写技能", "技能创作", "skill", "authoring", "让 AI 写技能",
                "root", "shizuku", "Termux", "虚拟屏",
                // 协作模式
                "计划", "目标", "plan", "goal",
            ),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_extensions,
            descriptionRes = R.string.setting_page_extensions_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.Extensions,
        ),

        // Model and services
        SettingsSearchEntry(
            titleRes = R.string.setting_model_page_title,
            descriptionRes = R.string.setting_page_default_model_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingModels,
            keywords = listOf("上下文", "上下文长度", "1M", "context", "1000000", "token 上限"),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_providers,
            descriptionRes = R.string.setting_page_providers_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingProvider,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_search_service,
            descriptionRes = R.string.setting_page_search_service_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSearch,
        ),
        SettingsSearchEntry(
            titleRes = R.string.speech_page_title,
            descriptionRes = R.string.setting_page_tts_service_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSpeech,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_snapshots,
            descriptionRes = R.string.setting_page_snapshots_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSnapshots,
            keywords = listOf(
                "快照", "备份", "回滚", "恢复", "还原", "snapshot", "restore", "backup",
                "删错了", "撤销", "undo",
            ),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_mcp,
            descriptionRes = R.string.setting_page_mcp_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingMcp,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_sub_agents,
            descriptionRes = R.string.setting_page_sub_agents_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSubAgents,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_web_server,
            descriptionRes = R.string.setting_page_web_server_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingWeb,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_telegram,
            descriptionRes = R.string.setting_page_telegram_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingTelegram,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_workflows,
            descriptionRes = R.string.setting_page_workflows_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingWorkflows,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_scheduled_jobs,
            descriptionRes = R.string.setting_page_scheduled_jobs_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingScheduledJobs,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_browser,
            descriptionRes = R.string.setting_page_browser_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingBrowser,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_termux,
            descriptionRes = R.string.setting_page_termux_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingTermux,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_root,
            descriptionRes = R.string.setting_page_root_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingRoot,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_virtual_screen,
            descriptionRes = R.string.setting_page_virtual_screen_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingVirtualScreen,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_shizuku,
            descriptionRes = R.string.setting_page_shizuku_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingShizuku,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_doctor,
            descriptionRes = R.string.setting_page_doctor_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingDoctor,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_tool_approvals,
            descriptionRes = R.string.setting_page_tool_approvals_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingToolApprovals,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_accessibility,
            descriptionRes = R.string.setting_page_accessibility_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingAccessibility,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_notifications,
            descriptionRes = R.string.setting_page_notifications_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingNotifications,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_permissions,
            descriptionRes = R.string.setting_page_permissions_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingPermissions,
        ),

        // Data settings
        SettingsSearchEntry(
            titleRes = R.string.backup_page_title,
            descriptionRes = R.string.setting_page_data_backup_desc,
            groupRes = R.string.setting_page_data_settings,
            route = Screen.Backup,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_files_page_title,
            descriptionRes = R.string.setting_page_chat_storage_desc,
            groupRes = R.string.setting_page_data_settings,
            route = Screen.SettingFiles,
        ),

        // About
        SettingsSearchEntry(
            titleRes = R.string.setting_page_about,
            descriptionRes = R.string.setting_page_about_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.SettingAbout,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_request_logs,
            descriptionRes = R.string.setting_page_request_logs_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.Log,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_donate,
            descriptionRes = R.string.setting_page_donate_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.SettingDonate,
        ),

        // Preferences sub-pages
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_theme,
            descriptionRes = R.string.setting_page_preferences_theme_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesTheme,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_notification,
            descriptionRes = R.string.setting_page_preferences_notification_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesNotification,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_general,
            descriptionRes = R.string.setting_page_preferences_general_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesGeneral,
            keywords = listOf(
                // 目标自动续跑（在这一页的第三屏，标题里搜不到）
                "目标", "续跑", "自动续跑", "续跑轮数", "goal", "continuation",
                // 其它容易被搜的
                "TTS", "语音播放", "震动", "长文本", "粘贴", "图片裁剪",
            ),
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_ui,
            descriptionRes = R.string.setting_page_preferences_ui_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesUI,
            keywords = listOf("视频背景", "背景", "思考深度", "刺球", "缩放", "壁纸"),
        ),
    )

    if (developerMode) {
        entries.add(
            SettingsSearchEntry(
                titleRes = R.string.accessibility_developer_options,
                descriptionRes = null,
                groupRes = R.string.settings,
                route = Screen.Developer,
            )
        )
    }

    return entries
}
