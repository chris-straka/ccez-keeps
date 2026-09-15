package dev.cstraka.keeps.sync

/**
 * Markdown subset parser mirroring web/components/markdown.ts and the
 * "Markdown in body" contract (contracts/data.md): **bold**, *italic*,
 * ~~strike~~, `code` (markers inside stay literal), and "- [ ] "/"- [x] "
 * checklist line markers.
 *
 * Pure Kotlin (no Compose dependency): the app maps [RichBody] onto an
 * AnnotatedString. Bodies stay verbatim everywhere else.
 */
enum class SpanKind { BOLD, ITALIC, STRIKE, CODE }

data class RichSpan(val start: Int, val end: Int, val kind: SpanKind)

data class RichLine(
    /** Display text with all markers stripped. */
    val text: String,
    val spans: List<RichSpan>,
    /** Non-null when the source line was a checklist item. */
    val checked: Boolean?,
)

data class RichBody(val lines: List<RichLine>)

private val boldRe = Regex("""\*\*([^*]+)\*\*""")
private val italicRe = Regex("""(^|[^*\w])\*([^*\n]+)\*""")
private val strikeRe = Regex("""~~([^~]+)~~""")
private val checkRe = Regex("""^- \[( |x)\] (.*)$""")

private data class RawSpan(val start: Int, val end: Int, val kind: SpanKind)

/** Parse one marker-free segment, collecting spans over [base] offset. */
private fun parseInline(segment: String, base: Int, out: MutableList<RawSpan>): StringBuilder {
    val text = StringBuilder()
    var cursor = 0
    // Walk bold/italic/strike matches in order; regexes mirror the web ones.
    val matches = (boldRe.findAll(segment).map { it to SpanKind.BOLD } +
        italicRe.findAll(segment).map { it to SpanKind.ITALIC } +
        strikeRe.findAll(segment).map { it to SpanKind.STRIKE })
        .sortedBy { it.first.range.first }
    for ((m, kind) in matches) {
        if (m.range.first < cursor) continue // overlapped an earlier match
        text.append(segment.substring(cursor, m.range.first))
        val inner = if (kind == SpanKind.ITALIC) m.groupValues[2] else m.groupValues[1]
        val prefix = if (kind == SpanKind.ITALIC) m.groupValues[1] else ""
        text.append(prefix)
        val spanStart = base + text.length
        text.append(inner)
        out.add(RawSpan(spanStart, base + text.length, kind))
        cursor = m.range.last + 1
    }
    text.append(segment.substring(cursor))
    return text
}

fun parseRichBody(body: String): RichBody {
    val lines = body.split("\n").map { raw ->
        val check = checkRe.matchEntire(raw)
        val source = check?.groupValues?.get(2) ?: raw
        val checked = check?.let { it.groupValues[1] == "x" }
        // Code spans split first so markers inside stay literal.
        val segments = source.split("`")
        val text = StringBuilder()
        val spans = mutableListOf<RawSpan>()
        segments.forEachIndexed { i, segment ->
            if (i % 2 == 1) {
                val start = text.length
                text.append(segment)
                spans.add(RawSpan(start, text.length, SpanKind.CODE))
            } else {
                text.append(parseInline(segment, text.length, spans))
            }
        }
        RichLine(
            text = text.toString(),
            spans = spans.map { RichSpan(it.start, it.end, it.kind) },
            checked = checked,
        )
    }
    return RichBody(lines)
}
