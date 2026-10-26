package dev.soulacy.android

import dev.soulacy.android.api.ChatEvent
import dev.soulacy.android.api.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun parse(stream: String): List<ChatEvent> {
        val p = SseParser()
        val out = stream.split("\n").mapNotNull(p::feed).toMutableList()
        p.finish()?.let(out::add)
        return out
    }

    @Test fun `reads the gateway's frame sequence`() {
        val events = parse(
            """
            event: run
            data: {"run_id":"r-1"}

            data: Hello\nworld

            event: tool_confirm
            data: {"call_id":"c-9","tool":"shell.exec","args":{"cmd":"ls"},"reason":"risky"}

            event: result
            data: {"reply":"Hello\nworld!","session_id":"s","run_id":"r-1","parts":[]}

            data: [DONE]

            """.trimIndent()
        )
        assertEquals(ChatEvent.Run("r-1"), events[0])
        assertEquals(ChatEvent.Token("Hello\nworld"), events[1])
        val confirm = events[2] as ChatEvent.Confirm
        assertEquals("c-9", confirm.request.callId)
        assertEquals("shell.exec", confirm.request.tool)
        assertEquals("risky", confirm.request.reason)
        assertEquals("r-1", (events[3] as ChatEvent.Result).result.runId)
        assertEquals(ChatEvent.Done, events[4])
        assertEquals(5, events.size)
    }

    @Test fun `error frames, comments and unknown events`() {
        val events = parse(": keep-alive\n\nevent: ping\ndata: x\n\nevent: error\ndata: model unavailable\n\n")
        assertEquals(listOf<ChatEvent>(ChatEvent.Error("model unavailable")), events)
    }

    @Test fun `malformed confirm becomes an error, never a silent approval`() {
        val events = parse("event: tool_confirm\ndata: {not json\n\n")
        assertTrue(events.single() is ChatEvent.Error)
    }

    @Test fun `final frame without trailing blank line is flushed`() {
        assertEquals(listOf<ChatEvent>(ChatEvent.Token("tail")), parse("data: tail"))
    }
}
