package me.rerere.rikkahub.data.ai.armor

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmorTransformerTest {

    private fun system(text: String) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistant(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun UIMessage.plainText() = parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    @Test
    fun `prefix goes to the last user message, system stays first`() {
        val messages = listOf(
            system("you are luna"),
            user("first question"),
            assistant("first answer"),
            user("second question"),
        )

        val out = applyArmorPrefix(messages, listOf("[TAIL]"))

        assertEquals(MessageRole.SYSTEM, out[0].role)
        assertTrue(out[3].plainText().startsWith("[TAIL]"))
        assertTrue(out[3].plainText().endsWith("second question"))
        // 前面那条历史 user 不许被改，否则提示词缓存全废
        assertEquals("first question", out[1].plainText())
    }

    @Test
    fun `already injected messages are returned as is`() {
        val injected = user(ArmorTransformer.MARKER + "\n\nsecond question")
        val messages = listOf(system("s"), injected)

        val out = applyArmorPrefix(messages, listOf("[TAIL]"))

        assertSame(messages, out)
    }

    @Test
    fun `messages without user are returned as is`() {
        val messages = listOf(system("s"), assistant("a"))

        val out = applyArmorPrefix(messages, listOf("[TAIL]"))

        assertSame(messages, out)
    }

    @Test
    fun `user without any text part gets a text part prepended`() {
        val imageOnly = UIMessage(role = MessageRole.USER, parts = emptyList())
        val messages = listOf(system("s"), imageOnly)

        val out = applyArmorPrefix(messages, listOf("[TAIL]"))

        assertEquals(MessageRole.USER, out[1].role)
        assertEquals("[TAIL]", out[1].plainText())
    }

    @Test
    fun `empty blocks do nothing`() {
        val messages = listOf(system("s"), user("q"))

        val out = applyArmorPrefix(messages, emptyList())

        assertSame(messages, out)
    }

    @Test
    fun `multiple blocks are joined`() {
        val messages = listOf(system("s"), user("q"))

        val out = applyArmorPrefix(messages, listOf("[A]", "[B]"))

        val text = out[1].plainText()
        assertTrue(text.startsWith("[A]\n\n[B]"))
        assertTrue(text.endsWith("q"))
    }

    @Test
    fun `role and synthetic flag are preserved`() {
        val messages = listOf(system("s"), user("q"))

        val out = applyArmorPrefix(messages, listOf("[TAIL]"))

        assertEquals(MessageRole.USER, out[1].role)
        assertEquals(messages[1].isSynthetic, out[1].isSynthetic)
    }

    @Test
    fun `hasInjected only looks at the last user message`() {
        val messages = listOf(
            user(ArmorTransformer.MARKER + "\n\nold"),
            assistant("a"),
            user("new"),
        )

        assertEquals(false, hasInjected(messages))
    }
}
