package at.cloudplay.musicos.recognition

/** Wandelt Aufnahmen mit 44,1/48 kHz in 16 kHz um (einfacher Mittelwert-Tiefpass). */
object Resampler {

    fun to16k(input: ShortArray, sampleRate: Int): ShortArray {
        if (sampleRate == ShazamSignature.SAMPLE_RATE) return input
        val ratio = sampleRate.toDouble() / ShazamSignature.SAMPLE_RATE
        val outLength = (input.size / ratio).toInt()
        val half = (ratio / 2).toInt().coerceAtLeast(1)
        return ShortArray(outLength) { i ->
            val center = (i * ratio).toInt()
            var sum = 0L
            var n = 0
            for (j in center - half..center + half) {
                if (j in input.indices) {
                    sum += input[j]
                    n++
                }
            }
            if (n == 0) 0 else (sum / n).toInt().toShort()
        }
    }
}
