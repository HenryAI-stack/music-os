package at.cloudplay.musicos.history

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLauncherTest {

    @Test
    fun buildsQueryArtistFirst() {
        assertEquals("Queen Bohemian Rhapsody", YouTubeLauncher.query("Bohemian Rhapsody", "Queen"))
        assertEquals("Song", YouTubeLauncher.query(" Song ", ""))
    }

    @Test
    fun encodesSearchUrl() {
        assertEquals(
            "https://www.youtube.com/results?search_query=Simon+%26+Garfunkel+The+Boxer",
            YouTubeLauncher.searchUrl("The Boxer", "Simon & Garfunkel"),
        )
    }
}
