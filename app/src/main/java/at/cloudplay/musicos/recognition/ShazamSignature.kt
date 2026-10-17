package at.cloudplay.musicos.recognition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Erzeugt Shazam-kompatible Audio-Fingerabdrücke aus 16-kHz-Mono-PCM.
 *
 * Kotlin-Umsetzung des quelloffenen Algorithmus aus SongRec / shazamio
 * (shazamio/algorithm.py + signature.py). Reines Kotlin ohne Android-Abhängigkeiten,
 * damit es per JVM-Unit-Test gegen Referenzdaten geprüft werden kann.
 */
object ShazamSignature {

    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 12

    private const val FFT_SIZE = 2048
    private const val BINS = FFT_SIZE / 2 + 1 // 1025
    private const val RING = 256
    private const val CHUNK = 128

    private val NEIGHBORS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHER_FFTS = intArrayOf(
        -53, -45,
        165, 172, 179, 186, 193, 200,
        214, 221, 228, 235, 242, 249,
    )

    /** numpy.hanning(2050)[1:-1] */
    private val hanning = DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * cos(2.0 * PI * (i + 1) / 2049.0) }

    private val bitReverse = IntArray(FFT_SIZE) { i ->
        var x = i
        var r = 0
        repeat(11) { r = (r shl 1) or (x and 1); x = x shr 1 }
        r
    }
    private val twiddleRe = DoubleArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE) }
    private val twiddleIm = DoubleArray(FFT_SIZE / 2) { -sin(2.0 * PI * it / FFT_SIZE) }

    data class Peak(val fftPass: Int, val magnitude: Int, val bin: Int)

    class Signature(val numberSamples: Int, val bands: Map<Int, List<Peak>>) {

        val sampleMs: Long get() = numberSamples * 1000L / SAMPLE_RATE

        val peakCount: Int get() = bands.values.sumOf { it.size }

        fun toUri(): String = "data:audio/vnd.shazam.sig;base64," + Base64.getEncoder().encodeToString(encode())

        /** Binärformat wie shazamio DecodedMessage.encode_to_binary(). */
        fun encode(): ByteArray {
            val contents = ByteArrayOutputStream()
            for ((band, peaks) in bands.toSortedMap()) {
                val peakBytes = ByteArrayOutputStream()
                var lastPass = 0
                for (peak in peaks) {
                    if (peak.fftPass - lastPass >= 255) {
                        peakBytes.write(0xFF)
                        peakBytes.writeIntLE(peak.fftPass)
                        lastPass = peak.fftPass
                    }
                    peakBytes.write(peak.fftPass - lastPass)
                    peakBytes.writeShortLE(peak.magnitude)
                    peakBytes.writeShortLE(peak.bin)
                    lastPass = peak.fftPass
                }
                contents.writeIntLE(0x60030040 + band)
                contents.writeIntLE(peakBytes.size())
                contents.write(peakBytes.toByteArray())
                repeat((4 - peakBytes.size() % 4) % 4) { contents.write(0) }
            }
            val body = contents.toByteArray()

            val buffer = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0xCAFE2580L.toInt()) // magic1
            buffer.putInt(0) // CRC32, wird unten gesetzt
            buffer.putInt(body.size + 8) // size_minus_header
            buffer.putInt(0x94119C00L.toInt()) // magic2
            repeat(3) { buffer.putInt(0) }
            buffer.putInt(3 shl 27) // Sample-Rate-ID 3 = 16 kHz
            repeat(2) { buffer.putInt(0) }
            buffer.putInt((numberSamples + SAMPLE_RATE * 0.24).toInt())
            buffer.putInt((15 shl 19) + 0x40000)
            buffer.putInt(0x40000000)
            buffer.putInt(body.size + 8)
            buffer.put(body)

            val bytes = buffer.array()
            val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc.toInt())
            return bytes
        }
    }

    /** Berechnet die Signatur für (höchstens) die ersten 12 Sekunden von [samples]. */
    fun generate(samples: ShortArray): Signature {
        val usable = minOf(samples.size, SAMPLE_RATE * MAX_SECONDS) / CHUNK * CHUNK

        val ring = DoubleArray(FFT_SIZE)
        var ringPos = 0
        val fftOutputs = Array(RING) { DoubleArray(BINS) }
        val spread = Array(RING) { DoubleArray(BINS) }
        var pos = 0 // gemeinsame Ringposition für fftOutputs und spread
        var written = 0
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val bands = sortedMapOf<Int, MutableList<Peak>>()

        var offset = 0
        while (offset + CHUNK <= usable) {
            // 1) FFT über die letzten 2048 Samples
            for (i in 0 until CHUNK) ring[ringPos + i] = samples[offset + i].toDouble()
            ringPos = (ringPos + CHUNK) % FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] = ring[(ringPos + i) % FFT_SIZE] * hanning[i]
                im[i] = 0.0
            }
            fft(re, im)
            val slot = pos
            val fftOut = fftOutputs[slot]
            for (k in 0 until BINS) {
                fftOut[k] = max((re[k] * re[k] + im[k] * im[k]) / 131072.0, 1e-10)
            }

            // 2) Peak-Spreading in Frequenz- und Zeitrichtung
            val spreadOut = spread[slot]
            for (k in 0 until BINS - 3) spreadOut[k] = max(fftOut[k], max(fftOut[k + 1], fftOut[k + 2]))
            for (k in BINS - 3 until BINS) spreadOut[k] = fftOut[k]
            val former1 = spread[(slot - 1 + RING) % RING]
            val former3 = spread[(slot - 3 + RING) % RING]
            val former6 = spread[(slot - 6 + RING) % RING]
            for (k in 0 until BINS) {
                var m = max(spreadOut[k], former1[k]); former1[k] = m
                m = max(m, former3[k]); former3[k] = m
                m = max(m, former6[k]); former6[k] = m
            }
            pos = (slot + 1) % RING
            written++

            // 3) Peak-Erkennung, 46 FFTs verzögert
            if (written >= 46) {
                val fft46 = fftOutputs[(pos - 46 + RING) % RING]
                val spread49 = spread[(pos - 49 + RING) % RING]
                for (bin in 10 until 1015) {
                    val value = fft46[bin]
                    if (value < 1.0 / 64 || value < spread49[bin - 1]) continue

                    var maxNeighbor = 0.0
                    for (n in NEIGHBORS) maxNeighbor = max(maxNeighbor, spread49[bin + n])
                    if (value <= maxNeighbor) continue

                    var maxOther = maxNeighbor
                    for (o in OTHER_FFTS) maxOther = max(maxOther, spread[(pos + o + RING) % RING][bin - 1])
                    if (value <= maxOther) continue

                    val magnitude = ln(max(1.0 / 64, value)) * 1477.3 + 6144
                    val before = ln(max(1.0 / 64, fft46[bin - 1])) * 1477.3 + 6144
                    val after = ln(max(1.0 / 64, fft46[bin + 1])) * 1477.3 + 6144
                    val variation1 = magnitude * 2 - before - after
                    if (variation1 <= 0) continue
                    val variation2 = (after - before) * 32 / variation1
                    val correctedBin = bin * 64 + variation2
                    val hz = correctedBin * (16000.0 / 2 / 1024 / 64)

                    val band = when {
                        hz > 250 && hz < 520 -> 0
                        hz > 520 && hz < 1450 -> 1
                        hz > 1450 && hz < 3500 -> 2
                        else -> continue
                    }
                    bands.getOrPut(band) { mutableListOf() } +=
                        Peak(written - 46, magnitude.toInt(), correctedBin.toInt())
                }
            }
            offset += CHUNK
        }
        return Signature(usable, bands)
    }

    /** Iterative Radix-2-FFT (in place) für genau 2048 Punkte. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until FFT_SIZE) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len / 2
            val step = FFT_SIZE / len
            var start = 0
            while (start < FFT_SIZE) {
                for (k in 0 until half) {
                    val wr = twiddleRe[k * step]
                    val wi = twiddleIm[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += len
            }
            len *= 2
        }
    }

    private fun ByteArrayOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
