package at.cloudplay.singalong.lyrics

data class LyricLine(val timeMs: Long, val text: String)

/** Parser für LRC-Lyrics, z. B. "[01:23.45] Textzeile". */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue // Metadaten wie [ar:...] oder Leerzeilen
            val text = raw.substring(tags.last().range.last + 1)
                .replace(wordTag, "")
                .trim()
            for (tag in tags) {
                val (min, sec, frac) = tag.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                result += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    /** Index der Zeile, die bei [positionMs] aktiv ist, oder -1 vor der ersten Zeile. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
