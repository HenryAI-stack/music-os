package at.cloudplay.musicos.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryJsonTest {

    private val entries = listOf(
        HistoryEntry("1", "Song A", "Artist", "Album", viaMic = true, timestamp = 1_700_000_000_000),
        HistoryEntry("2", "Song B", "Artist", null, viaMic = false, timestamp = 1_700_000_100_000),
    )

    @Test
    fun roundTrip() {
        assertEquals(entries, HistoryJson.decode(HistoryJson.encode(entries)))
    }

    @Test
    fun albumNullStaysNull() {
        val decoded = HistoryJson.decode(HistoryJson.encode(entries))
        assertEquals(null, decoded[1].album)
    }

    @Test
    fun sameSongIgnoresCase() {
        assertTrue(HistoryJson.isSameSong(entries[0], "song a", "ARTIST"))
        assertFalse(HistoryJson.isSameSong(entries[0], "Song B", "Artist"))
    }
}
