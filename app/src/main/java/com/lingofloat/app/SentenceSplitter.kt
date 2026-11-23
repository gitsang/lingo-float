package com.lingofloat.app

/**
 * Splits transcribed text into sentence fragments.
 * A fragment is "complete" when it ends with terminal punctuation
 * (CJK or Latin). A trailing "." inside numbers (e.g. 3.5) is not a boundary.
 */
object SentenceSplitter {

    private val enders = Regex("[。！？!?…；;]|\\.(?!\\d)")

    /** Split into fragments; the last one may be an incomplete tail. */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (m in enders.findAll(text)) {
            val end = m.range.last + 1
            val piece = text.substring(start, end).trim()
            if (piece.any { it.isLetterOrDigit() }) out.add(piece)
            start = end
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    fun isComplete(sentence: String): Boolean {
        val c = sentence.lastOrNull() ?: return false
        return c in "。！？!?…；;."
    }

    /** Whitespace/case-insensitive form used for prefix stability checks. */
    fun norm(sentence: String): String =
        sentence.filter { !it.isWhitespace() }.lowercase()
}
