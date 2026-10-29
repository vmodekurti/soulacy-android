package dev.soulacy.android

import dev.soulacy.android.ui.text.Markdown
import dev.soulacy.android.ui.text.MdBlock
import dev.soulacy.android.ui.text.MdSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun `blocks of a typical agent reply`() {
        val blocks = Markdown.parse(
            """
            # Morning brief
            You have **3 meetings** and
            one *overdue* reply.

            - Standup at 9
            - Review `PR 42`
              - nested detail
            1. First
            2) Second
            > quoted line

            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading(1, listOf(MdSpan.Text("Morning brief"))), blocks[0])
        val p = blocks[1] as MdBlock.Paragraph
        assertEquals(
            listOf(MdSpan.Text("You have "), MdSpan.Text("3 meetings", bold = true), MdSpan.Text(" and one "), MdSpan.Text("overdue", italic = true), MdSpan.Text(" reply.")),
            p.spans,
        )
        assertEquals(MdBlock.Bullet(null, 0, listOf(MdSpan.Text("Standup at 9"))), blocks[2])
        assertEquals(listOf(MdSpan.Text("Review "), MdSpan.Code("PR 42")), (blocks[3] as MdBlock.Bullet).spans)
        assertEquals(1, (blocks[4] as MdBlock.Bullet).depth)
        assertEquals("1.", (blocks[5] as MdBlock.Bullet).ordinal)
        assertEquals("2.", (blocks[6] as MdBlock.Bullet).ordinal)
        assertTrue(blocks[7] is MdBlock.Quote)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[8])
    }

    @Test fun `unterminated fence while streaming keeps its text`() {
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "line 1\nline 2")), Markdown.parse("```\nline 1\nline 2"))
    }

    @Test fun `literal text is left alone`() {
        assertEquals(listOf<MdSpan>(MdSpan.Text("use snake_case_names and 2 * 3 = 6")), Markdown.inline("use snake_case_names and 2 * 3 = 6"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("**not closed")), Markdown.inline("**not closed"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("a *literal* star")), Markdown.inline("a \\*literal\\* star"))
    }

    @Test fun `only http links become links`() {
        assertEquals(listOf(MdSpan.Text("see "), MdSpan.Link("docs", "https://docs.soulacy.io")), Markdown.inline("see [docs](https://docs.soulacy.io)"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("[x](javascript:alert(1))")), Markdown.inline("[x](javascript:alert(1))"))
    }
}
