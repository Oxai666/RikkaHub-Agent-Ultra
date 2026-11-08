package me.rerere.rikkahub.plan

import me.rerere.rikkahub.data.ai.tools.ToolAccess
import me.rerere.rikkahub.data.plan.PlanModePolicy
import me.rerere.rikkahub.data.plan.PlanToolCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan 模式工具闸门的纯逻辑测试。
 *
 * 重点验两件事：
 *  1. 写工具在 Plan 模式下真的进不来（不是靠提示词劝）
 *  2. `update_plan` 在 Plan 模式下硬拒
 */
class PlanModePolicyTest {

    @Test
    fun `write tools are blocked in plan mode`() {
        val blocked = listOf(
            "write_text_file", "write_binary_file", "delete_file", "move_file", "copy_file",
            "create_directory", "batch_delete", "batch_move", "batch_copy",
            "termux_run_command", "termux_session_start", "ssh_exec", "root_shell", "shizuku_exec",
            "tap", "swipe", "scroll", "long_press", "set_text", "click_node", "global_action",
            "launch_app", "open_url", "open_file", "set_brightness", "set_volume", "set_wallpaper",
            "send_sms", "post_notification", "share", "vibrate",
            "workspace_shell", "workspace_write_file", "workspace_run_background",
            "virtual_screen", "camera_photo", "record_audio", "speech_to_text",
            "telegram_send_message", "schedule_job",
        )
        blocked.forEach { name ->
            assertFalse("`$name` 不该在 Plan 模式放行", PlanModePolicy.isAllowedInPlan(name))
        }
    }

    @Test
    fun `read tools are allowed in plan mode`() {
        val allowed = listOf(
            "read_file", "list_files", "find_files", "file_info",
            "search_web", "web_fetch", "web_extract", "scrape_web",
            "get_battery_status", "get_wifi_info", "get_time_info",
            "take_screenshot", "read_window_tree", "find_node",
            "list_contacts", "list_sms_inbox", "search_sms",
            "conversation_search", "history_search", "recent_chats",
            "skill_get_content", "ask_user",
        )
        allowed.forEach { name ->
            assertTrue("`$name` 应该能在 Plan 模式用", PlanModePolicy.isAllowedInPlan(name))
        }
    }

    @Test
    fun `update_plan is allowed in plan mode but only for outlining`() {
        // 行为改过：原本一律拒绝，但那样界面上的步骤条永远是空的。
        // 现在放行，靠提示词约束「不许标 in_progress」。
        assertTrue(PlanModePolicy.isAllowedInPlan(PlanToolCatalog.UPDATE_PLAN))
    }

    @Test
    fun `unknown tool names default to blocked`() {
        // 白名单的保守面：没登记过的一律当可写处理
        assertFalse(PlanModePolicy.isAllowedInPlan("some_future_tool_v2"))
        assertFalse(ToolAccess.isReadOnly("some_future_tool_v2"))
    }

    @Test
    fun `dangerous shell runners are not read only`() {
        // 这几个最容易误判成「只是读一下」
        assertFalse(ToolAccess.isReadOnly("workspace_shell"))
        assertFalse(ToolAccess.isReadOnly("termux_run_command"))
        assertFalse(ToolAccess.isReadOnly("memory_tool"))
    }
}
