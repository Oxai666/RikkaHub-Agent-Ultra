package me.rerere.rikkahub.data.ai.tools

/**
 * 工具是只读还是会改状态。
 *
 * Plan 模式靠这个把工具表掐成只读子集——**必须落在工具层**，
 * 不能靠提示词说「别写文件」。模型只要有一次没听，就真写了。
 *
 * 判定方式是**白名单**：只有名字在这张表里的才算只读，
 * 其余一律按可写处理。新增工具时如果忘了登记，最坏结果是
 * Plan 模式里用不了它，不会出现「以为只读结果写坏了」。
 *
 * 登记标准只有一条：**执行完，外部世界有没有任何可观察的变化**。
 * 写文件、发消息、点屏幕、跑命令、装东西，全都算变化。
 */
object ToolAccess {

    /**
     * 只读白名单。
     *
     * 注意几个容易误判的：
     *  - `workspace_shell` 能跑任意命令 → 不算只读
     *  - `memory_tool` 含新增/修改/删除 → 不算只读
     *  - `take_screenshot` 会产生一张图，但不改任何外部状态 → 算只读
     *  - `ask_user` 是 Plan 模式要用的提问工具 → 算只读
     */
    private val READ_ONLY: Set<String> = setOf(
        // ── 系统信息查询 ──
        "get_battery_status", "get_audio_info", "get_brightness",
        "get_location", "get_media_status", "get_storage_info",
        "get_telephony_info", "get_time_info", "get_volume", "get_wifi_info",
        "list_sensors", "read_sensor", "notification_status", "whisper_status",

        // ── 文件与目录读取 ──
        "read_file", "list_files", "file_info", "find_files", "list_zip_contents",
        "list_granted_directories", "list_storage_volumes",

        // ── 应用与界面观察 ──
        "list_installed_apps", "list_app_activities",
        "read_window_tree", "find_node", "take_screenshot",

        // ── 通讯录 / 通话 / 短信（读） ──
        "list_contacts", "search_contacts", "list_call_log",
        "list_sms_inbox", "search_sms",

        // ── 通知（读） ──
        "list_active_notifications", "list_recent_notifications",

        // ── 定时任务（读） ──
        "list_jobs", "get_job_history",

        // ── SSH 主机清单（读） ──
        "list_ssh_hosts",

        // ── 网络读取 ──
        "search_web", "scrape_web", "web_fetch", "web_extract",

        // ── 会话与历史（读） ──
        "conversation_search", "recent_chats", "history_search",

        // ── 技能（读） ──
        "skill_get_content", "use_skill", "skill_list",
        "snapshot_list",

        // ── 密钥库（只读操作） ──
        "keystore_list_keys", "keystore_verify",

        // ── 输入法（读） ──
        "keyboard_read_field", "keyboard_editor_info",

        // ── 工作区（只读） ──
        "workspace_read_file", "workspace_read_folder", "workspace_background_status",

        // ── 交互与展示 ──
        "ask_user", "show_image", "show_toast",
    )

    /** 这个工具执行完会不会改变外部状态。 */
    fun isReadOnly(toolName: String): Boolean = toolName in READ_ONLY

    /** 暴露出去给测试和 UI 用。 */
    fun readOnlyToolNames(): Set<String> = READ_ONLY
}
