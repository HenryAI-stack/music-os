package at.cloudplay.musicos.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Vergleicht die Kotlin-Signatur mit der Referenz aus shazamio (Python).
 * Referenzdaten: src/test/resources/shazam/ (erzeugt mit gen_golden.py).
 */
class ShazamSignatureTest {

    private data class Decoded(
        val numberField: Int,
        val sampleRateField: Int,
        val bands: Map<Int, List<ShazamSignature.Peak>>,
    )

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("shazam/$name")) { "fehlt: $name" }
            .use { it.readBytes() }

    private fun loadPcm(): ShortArray {
        val bytes = resource("input_16k_s16le.pcm")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun decode(bytes: ByteArray): Decoded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("magic1", 0xCAFE2580L.toInt(), b.getInt(0))
        assertEquals("magic2", 0x94119C00L.toInt(), b.getInt(12))
        assertEquals("Größe", bytes.size - 48, b.getInt(8))
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        assertEquals("CRC32", crc, b.getInt(4))

        val bands = sortedMapOf<Int, List<ShazamSignature.Peak>>()
        b.position(56)
        while (b.remaining() >= 8) {
            val band = b.int - 0x60030040
            val size = b.int
            val end = b.position() + size
            var pass = 0
            val peaks = mutableListOf<ShazamSignature.Peak>()
            while (b.position() < end) {
                val delta = b.get().toInt() and 0xFF
                if (delta == 0xFF) {
                    pass = b.int
                    continue
                }
                pass += delta
                val magnitude = b.short.toInt() and 0xFFFF
                val bin = b.short.toInt() and 0xFFFF
                peaks += ShazamSignature.Peak(pass, magnitude, bin)
            }
            b.position(end + (4 - size % 4) % 4)
            bands[band] = peaks
        }
        return Decoded(b.getInt(40), b.getInt(28), bands)
    }

    @Test
    fun matchesShazamioReference() {
        val actualBytes = ShazamSignature.generate(loadPcm()).encode()
        val expectedBytes = resource("expected.sig")
        val actual = decode(actualBytes)
        val expected = decode(expectedBytes)

        println("Bytes identisch: ${actualBytes.contentEquals(expectedBytes)}")
        assertEquals("Sample-Anzahl", expected.numberField, actual.numberField)
        assertEquals("Sample-Rate", expected.sampleRateField, actual.sampleRateField)
        assertEquals("Frequenzbänder", expected.bands.keys, actual.bands.keys)

        var total = 0
        var matched = 0
        for ((band, expectedPeaks) in expected.bands) {
            val actualPeaks = actual.bands.getValue(band)
            println("Band $band: erwartet ${expectedPeaks.size}, erzeugt ${actualPeaks.size}")
            for (e in expectedPeaks) {
                total++
                val hit = actualPeaks.any {
                    it.fftPass == e.fftPass && abs(it.bin - e.bin) <= 2 && abs(it.magnitude - e.magnitude) <= 2
                }
                if (hit) matched++
            }
            assertTrue("Peak-Anzahl Band $band", abs(expectedPeaks.size - actualPeaks.size) <= 2)
        }
        println("Übereinstimmende Peaks: $matched / $total")
        assertTrue("zu wenige übereinstimmende Peaks: $matched/$total", matched >= total * 0.97)
    }

    @Test
    fun silenceHasNoPeaksButValidHeader() {
        val signature = ShazamSignature.generate(ShortArray(16_000 * 3))
        assertEquals(0, signature.peakCount)
        val decoded = decode(signature.encode())
        assertEquals(48_000 + 3840, decoded.numberField)
        assertEquals(3 shl 27, decoded.sampleRateField)
    }

    @Test
    fun limitsToTwelveSeconds() {
        val signature = ShazamSignature.generate(ShortArray(16_000 * 20))
        assertEquals(16_000 * 12, signature.numberSamples)
        assertEquals(12_000L, signature.sampleMs)
    }

    @Test
    fun resamplerConvertsLength() {
        assertEquals(16_000, Resampler.to16k(ShortArray(48_000), 48_000).size)
        assertEquals(16_000, Resampler.to16k(ShortArray(44_100), 44_100).size)
        val same = ShortArray(10)
        assertTrue(Resampler.to16k(same, 16_000) === same)
    }
}
