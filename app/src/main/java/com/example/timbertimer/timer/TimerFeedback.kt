package com.example.timbertimer.timer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.timbertimer.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The chime and the buzz.
 *
 * The web app synthesises its cues with the Web Audio API rather than shipping
 * sound files, so they are rebuilt here as PCM instead of being approximated
 * with the system notification tone — same three-note C-E-G resolve, same
 * ticking run-in over the last ten seconds.
 *
 * Everything plays through `USAGE_ALARM`: a countdown the user deliberately
 * started should still be heard over a silenced ringer, which is exactly the
 * distinction that usage draws.
 */
class TimerFeedback(
    context: Context,
    private val settings: SettingsStore,
) {

    private val appContext = context.applicationContext
    private var track: AudioTrack? = null

    /**
     * Cues are built and started here rather than on the caller's thread.
     *
     * The run-in tone is ten seconds long, which is 441,000 samples to
     * synthesise — enough arithmetic to drop frames if it ran on the main
     * thread, and it fires exactly when the user is watching the last seconds
     * tick down. The mutex keeps two cues from racing over the same track.
     */
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioLock = Mutex()

    /** The three-note resolve a finished session ends on. */
    fun playCompletion() = enqueue { buildCompletion(preview = false) }

    /** Shorter two-note version, for auditioning the volume. */
    fun playPreview() = enqueue { buildCompletion(preview = true) }

    /**
     * The run-in over the final seconds: a low drone under a beep every half
     * second, sized to exactly the time that is left so it lands on zero.
     */
    fun playFinishSoon(secondsRemaining: Long) =
        enqueue { buildFinishSoon(secondsRemaining.coerceIn(1L, 10L).toDouble()) }

    fun stop() {
        audioScope.launch {
            audioLock.withLock {
                runCatching { track?.pause() }
                release()
            }
        }
    }

    private fun enqueue(build: () -> ShortArray) {
        if (!settings.soundEnabled.value) return
        audioScope.launch {
            val samples = build()
            audioLock.withLock { play(samples) }
        }
    }

    /**
     * Two firm pulses, long enough to notice through a pocket. Skipped when the
     * user has turned vibration off.
     */
    @SuppressLint("MissingPermission")
    fun vibrateCompletion() {
        if (!settings.vibrate.value) return
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return

        val pattern = longArrayOf(0, 420, 180, 420, 180, 700)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
            runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { vibrator.vibrate(pattern, -1) }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // ---------- synthesis ----------

    private fun buildCompletion(preview: Boolean): ShortArray {
        val frequencies = if (preview) doubleArrayOf(659.25, 783.99) else doubleArrayOf(523.25, 659.25, 783.99)
        val total = if (preview) 0.82 else 1.35
        val noteLength = if (preview) 0.46 else 0.78
        val samples = ShortArray((total * SAMPLE_RATE).toInt())
        val mix = DoubleArray(samples.size)

        frequencies.forEachIndexed { index, frequency ->
            val start = (index * 0.12 * SAMPLE_RATE).toInt()
            val length = (noteLength * SAMPLE_RATE).toInt()
            for (i in 0 until length) {
                val at = start + i
                if (at >= mix.size) break
                val t = i.toDouble() / SAMPLE_RATE
                // Quick attack, exponential decay — a struck-bell shape.
                val envelope = min(1.0, t / 0.05) * exp(-3.2 * t / noteLength)
                mix[at] += triangle(frequency, at.toDouble() / SAMPLE_RATE) * envelope * 0.42
            }
        }

        return applyGain(mix, samples)
    }

    private fun buildFinishSoon(seconds: Double): ShortArray {
        val samples = ShortArray((seconds * SAMPLE_RATE).toInt())
        val mix = DoubleArray(samples.size)
        val fadeStart = maxOf(0.25, seconds - 0.35)

        // Drone: two sustained sines that fade out on the last beat.
        for (i in mix.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = min(1.0, t / 0.18) * if (t > fadeStart) {
                ((seconds - t) / (seconds - fadeStart)).coerceIn(0.0, 1.0)
            } else 1.0
            mix[i] += (sin(TWO_PI * 220.0 * t) + sin(TWO_PI * 329.63 * t)) * 0.07 * envelope
        }

        // A beep every half second, alternating between two notes.
        var index = 0
        while (index * 0.5 < seconds) {
            val start = (index * 0.5 * SAMPLE_RATE).toInt()
            val length = (0.34 * SAMPLE_RATE).toInt()
            val frequency = if (index % 2 == 0) 523.25 else 659.25
            for (i in 0 until length) {
                val at = start + i
                if (at >= mix.size) break
                val t = i.toDouble() / SAMPLE_RATE
                val envelope = min(1.0, t / 0.04) * exp(-9.0 * t)
                mix[at] += triangle(frequency, at.toDouble() / SAMPLE_RATE) * envelope * 0.28
            }
            index++
        }

        return applyGain(mix, samples)
    }

    /** Band-limited enough for this purpose, and matches the web app's timbre. */
    private fun triangle(frequency: Double, t: Double): Double {
        val phase = (t * frequency) % 1.0
        return 4.0 * abs(phase - 0.5) - 1.0
    }

    private fun applyGain(mix: DoubleArray, out: ShortArray): ShortArray {
        val gain = settings.soundVolume.value.coerceIn(0f, 2f)
        for (i in mix.indices) {
            // Clip rather than wrap: a loud mix should get louder, not invert.
            val value = (mix[i] * gain * Short.MAX_VALUE).coerceIn(MIN_SAMPLE, MAX_SAMPLE)
            out[i] = value.roundToInt().toShort()
        }
        return out
    }

    private fun play(samples: ShortArray) {
        if (!settings.soundEnabled.value || samples.isEmpty()) return
        release()

        val bytes = samples.size * 2
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        // A device with no free audio track is not a reason to fail a session.
        runCatching {
            created.write(samples, 0, samples.size)
            created.play()
            track = created
        }.onFailure { created.release() }
    }

    private fun release() {
        track?.let { existing ->
            runCatching { existing.release() }
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val TWO_PI = 2.0 * PI
        const val MIN_SAMPLE = -32768.0
        const val MAX_SAMPLE = 32767.0
    }
}
