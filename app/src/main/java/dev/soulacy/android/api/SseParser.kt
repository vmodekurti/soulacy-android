package dev.soulacy.android.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Incremental parser for the gateway's chat stream. Frames look like
 *
 *     event: run
 *     data: {"run_id":"..."}
 *
 *     data: Hello\nworld      (token, newlines escaped by the gateway)
 *
 *     event: tool_confirm
 *     data: {"call_id":"...","tool":"...","args":{...}}
 *
 * and the stream ends with `data: [DONE]`. Feed it one line at a time.
 */
class SseParser(private val json: Json = GatewayJson) {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Returns the event completed by [line], if any. */
    fun feed(line: String): ChatEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Flushes a final frame that was not followed by a blank line. */
    fun finish(): ChatEvent? = dispatch()

    private fun dispatch(): ChatEvent? {
        if (!hasData && event == null) return null
        val name = event
        val payload = data.toString()
        event = null
        data.setLength(0)
        hasData = false
        return when (name) {
            null, "", "message" ->
                if (payload == "[DONE]") ChatEvent.Done else ChatEvent.Token(payload.replace("\\n", "\n"))
            "run" -> ChatEvent.Run(
                runCatching { json.parseToJsonElement(payload).jsonObject["run_id"]?.jsonPrimitive?.content }
                    .getOrNull().orEmpty()
            )
            "tool_confirm" -> runCatching { ChatEvent.Confirm(json.decodeFromString<ConfirmRequest>(payload)) }
                .getOrElse { ChatEvent.Error("Unreadable approval request from the gateway") }
            "result" -> runCatching { ChatEvent.Result(json.decodeFromString<ChatResult>(payload)) }
                .getOrElse { ChatEvent.Error("Unreadable result from the gateway") }
            "error" -> ChatEvent.Error(payload)
            else -> null
        }
    }
}

val GatewayJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
