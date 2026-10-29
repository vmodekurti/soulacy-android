package dev.soulacy.android.ui.text

/**
 * A small Markdown reader for agent replies: headings, paragraphs, bullet
 * and numbered lists, block quotes, fenced code, and inline bold, italic,
 * code and links. Anything else stays literal text, which is the safe
 * failure for model output. Rendering lives in MarkdownText.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullet(val ordinal: String?, val depth: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
}

sealed interface MdSpan {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val fence = Regex("^\\s*(```|~~~)\\s*([\\w+-]*)\\s*$")

    fun parse(source: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = source.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(inline(para.toString().trim()))
            para.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val marker = f.groupValues[1]
                val body = mutableListOf<String>()
                i++
                // An unterminated fence (still streaming) runs to the end.
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) body += lines[i++]
                out += MdBlock.Code(f.groupValues[2], body.joinToString("\n"))
                i++
                continue
            }
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                h != null -> { flush(); out += MdBlock.Heading(h.groupValues[1].length, inline(h.groupValues[2].trim())) }
                b != null -> { flush(); out += MdBlock.Bullet(null, b.groupValues[1].length / 2, inline(b.groupValues[2])) }
                n != null -> { flush(); out += MdBlock.Bullet(n.groupValues[2] + ".", n.groupValues[1].length / 2, inline(n.groupValues[3])) }
                line.trimStart().startsWith(">") -> { flush(); out += MdBlock.Quote(inline(line.trimStart().removePrefix(">").trim())) }
                line.isBlank() -> flush()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline spans: `code`, **bold**, *italic* / _italic_, [text](url). */
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun emit() {
            if (buf.isNotEmpty()) out += MdSpan.Text(buf.toString(), bold, italic)
            buf.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#" -> { buf.append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { emit(); out += MdSpan.Code(text.substring(i + 1, end)); i = end + 1 } else { buf.append(c); i++ }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    if (bold || text.indexOf(marker, i + 2) > i + 2) { emit(); bold = !bold; i += 2 } else { buf.append(marker); i += 2 }
                }
                (c == '*' || c == '_') && (italic || isOpening(text, i, c)) -> { emit(); italic = !italic; i++ }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    val url = if (end > 0) text.substring(close + 2, end) else ""
                    if (end > 0 && (url.startsWith("http://") || url.startsWith("https://"))) {
                        emit(); out += MdSpan.Link(text.substring(i + 1, close), url); i = end + 1
                    } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        emit()
        return out
    }

    // `*` or `_` opens emphasis only before a non-space with a matching close
    // later, and `_` only at a word start, so snake_case and 2 * 3 stay literal.
    private fun isOpening(text: String, i: Int, c: Char): Boolean {
        if (i + 1 >= text.length || text[i + 1].isWhitespace()) return false
        if (c == '_' && i > 0 && text[i - 1].isLetterOrDigit()) return false
        val close = text.indexOf(c, i + 1)
        return close > i + 1 && !text[close - 1].isWhitespace()
    }
}
