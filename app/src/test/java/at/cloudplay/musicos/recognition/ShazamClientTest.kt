package at.cloudplay.musicos.recognition

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShazamClientTest {

    @Test
    fun parsesMatchWithOffsetAndAlbum() {
        val json = JSONObject(
            """
            {"matches":[{"id":"1","offset":42.5,"timeskew":0.0001}],
             "track":{"title":"Song A","subtitle":"Artist B",
               "images":{"coverart":"https://example.com/c.jpg"},
               "sections":[{"type":"SONG","metadata":[
                 {"title":"Album","text":"Album C"},{"title":"Released","text":"2020"}]}]}}
            """.trimIndent()
        )
        val outcome = ShazamClient.parse(json) as ShazamClient.Outcome.Found
        assertEquals("Song A", outcome.match.title)
        assertEquals("Artist B", outcome.match.artist)
        assertEquals("Album C", outcome.match.album)
        assertEquals(42_500L, outcome.match.offsetMs)
        assertEquals("https://example.com/c.jpg", outcome.match.coverUrl)
    }

    @Test
    fun emptyMatchesMeansNoMatch() {
        assertEquals(ShazamClient.Outcome.NoMatch, ShazamClient.parse(JSONObject("""{"matches":[]}""")))
    }

    @Test
    fun requestBodyContainsSignature() {
        val signature = ShazamSignature.generate(ShortArray(16_000 * 4))
        val body = ShazamClient.requestBody(signature)
        val sig = body.getJSONObject("signature")
        assertTrue(sig.getString("uri").startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals(4000L, sig.getLong("samplems"))
    }

    /**
     * Echte Anfrage an Shazam – nur in GitHub Actions (SHAZAM_LIVE_TEST=1).
     * Prüft, dass Endpoint und Format akzeptiert werden (synthetisches Signal → kein Treffer erwartet).
     */
    @Test
    fun liveEndpointAcceptsSignature() {
        assumeTrue(System.getenv("SHAZAM_LIVE_TEST") == "1")
        val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("shazam/input_16k_s16le.pcm")).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(buffer.remaining()).also { buffer.get(it) }

        val outcome = ShazamClient.recognize(ShazamSignature.generate(pcm))
        println("Shazam-Antwort: $outcome")
        assertFalse("Shazam-Anfrage fehlgeschlagen: $outcome", outcome is ShazamClient.Outcome.Failed)
    }
}
