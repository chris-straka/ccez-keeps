package dev.cstraka.keeps.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors tests/markdown.test.ts: same syntax, same outcomes. */
class RichTextTest {

    private fun styled(body: String): String {
        // Re-render spans as web tags for a direct comparison.
        val out = StringBuilder()
        for ((i, line) in parseRichBody(body).lines.withIndex()) {
            if (i > 0) out.append('\n')
            if (line.checked != null) out.append(if (line.checked) "☑ " else "☐ ")
            val tags = line.spans.sortedBy { it.start }
            var cursor = 0
            for (span in tags) {
                out.append(line.text.substring(cursor, span.start))
                val inner = line.text.substring(span.start, span.end)
                out.append(
                    when (span.kind) {
                        SpanKind.BOLD -> "<strong>$inner</strong>"
                        SpanKind.ITALIC -> "<em>$inner</em>"
                        SpanKind.STRIKE -> "<del>$inner</del>"
                        SpanKind.CODE -> "<code>$inner</code>"
                    },
                )
                cursor = span.end
            }
            out.append(line.text.substring(cursor))
        }
        return out.toString()
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("hello", styled("hello"))
        assertEquals("a **b", styled("a **b"))
        assertEquals("a *b", styled("a *b"))
    }

    @Test
    fun boldItalicStrike() {
        assertEquals("a <strong>b</strong> c", styled("a **b** c"))
        assertEquals("a <em>b</em> c", styled("a *b* c"))
        assertEquals("a <del>b</del> c", styled("a ~~b~~ c"))
    }

    @Test
    fun codeSpansStayLiteral() {
        assertEquals("<code>**x**</code>", styled("`**x**`"))
    }

    @Test
    fun checklistLines() {
        assertEquals("☐ milk", styled("- [ ] milk"))
        assertEquals("☑ milk", styled("- [x] milk"))
        assertEquals("☑ <strong>milk</strong>", styled("- [x] **milk**"))
        assertEquals("-not a list", styled("-not a list"))
        assertNull(parseRichBody("plain").lines[0].checked)
    }
}
