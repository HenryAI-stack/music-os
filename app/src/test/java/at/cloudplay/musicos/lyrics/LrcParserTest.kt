package at.cloudplay.musicos.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesTimestampsAndSorts() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [00:12.34]Zweite Zeile
            [00:01.5][00:30.00] Wiederholt <00:01.70>mit Wort-Tag
            [01:02.123]Dritte
            """.trimIndent()
        )
        assertEquals(listOf(1_500L, 12_340L, 30_000L, 62_123L), lines.map { it.timeMs })
        assertEquals("Wiederholt mit Wort-Tag", lines[0].text)
        assertEquals("Zweite Zeile", lines[1].text)
    }

    @Test
    fun findsActiveLine() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, LrcParser.indexAt(lines, 500))
        assertEquals(0, LrcParser.indexAt(lines, 1_000))
        assertEquals(1, LrcParser.indexAt(lines, 8_999))
        assertEquals(2, LrcParser.indexAt(lines, 60_000))
    }
}
