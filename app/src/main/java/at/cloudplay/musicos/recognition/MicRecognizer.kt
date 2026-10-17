package at.cloudplay.musicos.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Nimmt bis zu 12 s über das Mikrofon auf und versucht nach 4, 8 und 12 s,
 * den Song zu erkennen. Die Aufnahme läuft während der Anfragen weiter.
 * Audio verlässt das Gerät nie – nur der Fingerabdruck.
 */
class MicRecognizer {

    sealed interface Result {
        data class Found(
            val match: ShazamClient.Match,
            /** SystemClock.elapsedRealtime() beim Start der Aufnahme. */
            val recordingStartedAt: Long,
        ) : Result

        data object NoMatch : Result
        data class Failed(val message: String) : Result
    }

    @SuppressLint("MissingPermission") // Berechtigung wird in der UI geprüft
    suspend fun listen(onProgress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val (recorder, rate) = openRecorder()
            ?: return@withContext Result.Failed("Mikrofon konnte nicht geöffnet werden")

        val maxSamples = rate * MAX_SECONDS
        val buffer = ShortArray(maxSamples)
        val chunk = ShortArray(rate / 10)
        val attemptsAt = ATTEMPT_SECONDS.map { it * rate }
        val found = AtomicReference<ShazamClient.Match?>(null)
        val lastFailure = AtomicReference<String?>(null)
        var count = 0
        var nextAttempt = 0
        var startedAt = 0L

        try {
            recorder.startRecording()
            startedAt = SystemClock.elapsedRealtime()
            coroutineScope {
                val jobs = mutableListOf<Job>()
                while (count < maxSamples && found.get() == null) {
                    ensureActive()
                    val read = recorder.read(chunk, 0, minOf(chunk.size, maxSamples - count))
                    if (read < 0) {
                        lastFailure.set("Aufnahmefehler ($read)")
                        break
                    }
                    System.arraycopy(chunk, 0, buffer, count, read)
                    count += read
                    onProgress(count.toFloat() / maxSamples)

                    if (nextAttempt < attemptsAt.size && count >= attemptsAt[nextAttempt]) {
                        nextAttempt++
                        val snapshot = buffer.copyOf(count)
                        jobs += launch {
                            val signature = ShazamSignature.generate(Resampler.to16k(snapshot, rate))
                            when (val outcome = ShazamClient.recognize(signature)) {
                                is ShazamClient.Outcome.Found -> found.compareAndSet(null, outcome.match)
                                is ShazamClient.Outcome.Failed -> lastFailure.set(outcome.message)
                                ShazamClient.Outcome.NoMatch -> Unit
                            }
                        }
                    }
                }
                recorder.stop()
                if (found.get() == null) jobs.joinAll() else jobs.forEach { it.cancel() }
            }
        } finally {
            recorder.release()
        }

        val match = found.get()
        val failure = lastFailure.get()
        when {
            match != null -> Result.Found(match, startedAt)
            failure != null -> Result.Failed(failure)
            else -> Result.NoMatch
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(16_000, 48_000, 44_100)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, rate * 2), // ≥ 1 s Puffer
                )
            } catch (e: Exception) {
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder to rate
            recorder.release()
        }
        return null
    }

    private companion object {
        const val MAX_SECONDS = ShazamSignature.MAX_SECONDS
        val ATTEMPT_SECONDS = listOf(4, 8, 12)
    }
}
