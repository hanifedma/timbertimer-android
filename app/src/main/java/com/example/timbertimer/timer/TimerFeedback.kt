package com.example.timbertimer.timer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.timbertimer.data.local.RestAlertStyle
import com.example.timbertimer.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
     * The rest alarm's own track, kept apart from [track] on purpose.
     *
     * The two can overlap — a focus countdown can land while a rest alarm is
     * still ringing — and sharing one field would have the quieter of them
     * release the louder mid-ring. This one also outlives its caller: it loops
     * in the audio hardware rather than being re-fed by a coroutine, so a
     * throttled or dozing process cannot make it skip.
     */
    private var alarmTrack: AudioTrack? = null

    /**
     * True while the real alarm is going off, so a settings audition cannot
     * take its track away from it.
     */
    @Volatile
    private var ringing = false

    /** The running audition, cancelled when another starts or the alarm does. */
    private var previewJob: Job? = null

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

    // ---------- the rest alarm ----------

    /**
     * Starts the rest alarm and leaves it running until [stopRestAlarm].
     *
     * Both halves loop in hardware rather than being re-fed from Kotlin: the
     * [AudioTrack] repeats its buffer through its own loop points, and the
     * vibrator repeats its waveform from index 0. That is what makes the alarm
     * survive the thing most likely to happen to it — the process being dozed,
     * throttled, or descheduled at the exact moment it is supposed to be making
     * noise. Nothing has to run for it to keep ringing.
     *
     * Neither half consults the focus-chime switches. Those govern a cue; this
     * is an alarm, and it answers to [SettingsStore.restAlert] alone.
     */
    fun startRestAlarm() {
        val style = settings.restAlert.value
        ringing = true
        previewJob?.cancel()
        if (style.wantsSound) {
            audioScope.launch {
                val samples = buildRestAlarmCycle()
                audioLock.withLock { playAlarm(samples, loop = true) }
            }
        }
        if (style.wantsVibration) vibrateRestAlarm()
    }

    fun stopRestAlarm() {
        ringing = false
        stopAlarmVibration()
        audioScope.launch {
            audioLock.withLock {
                runCatching { alarmTrack?.pause() }
                releaseAlarm()
            }
        }
    }

    /**
     * One cycle of whatever [style] would do, for auditioning the setting.
     *
     * Plays the real thing at the real level rather than a polite sample: the
     * point of the audition is to find out whether this will actually wake you,
     * and a quieter preview would answer a different question.
     *
     * Stopping it is done by *not asking it to loop* and then releasing the
     * track a cycle later — not by cancelling a loop already under way. An
     * [AudioTrack] in `MODE_STATIC` refuses to have its loop points changed
     * once it is playing, so an earlier version that started the looping alarm
     * and then tried to call it back left the preview ringing forever with
     * nothing that would ever stop it.
     */
    fun previewRestAlarm(style: RestAlertStyle = settings.restAlert.value) {
        // The real thing outranks a demonstration of it. Without this, tapping
        // a setting while the alarm is going off would replace the ringing
        // track with a one-shot and silence an alarm that is still owed.
        if (ringing) return

        previewJob?.cancel()
        if (style.wantsSound) {
            previewJob = audioScope.launch {
                val samples = buildRestAlarmCycle()
                audioLock.withLock { playAlarm(samples, loop = false) }
                // Released rather than left to finish quietly, so a second tap
                // a moment later is not queued behind a track still holding the
                // device's audio.
                delay(samples.size * 1000L / SAMPLE_RATE + PREVIEW_TAIL_MS)
                audioLock.withLock {
                    if (!ringing) {
                        runCatching { alarmTrack?.pause() }
                        releaseAlarm()
                    }
                }
            }
        }
        if (style.wantsVibration) previewAlarmVibration()
    }

    /** The alarm's waveform, played through exactly once. */
    @SuppressLint("MissingPermission")
    private fun previewAlarmVibration() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return

        val pattern = longArrayOf(0, 600, 250, 300, 250, 600)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> vibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, amplitudes, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(pattern, amplitudes, -1),
                        alarmVibrationAttributes(),
                    )
                }

                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1, alarmVibrationAttributes())
                }
            }
        }
    }

    /**
     * The repeating buzz, attributed as an alarm.
     *
     * The attributes are the entire point of this method existing separately
     * from [vibrateCompletion]. A bare `vibrate(effect)` is filed as a
     * notification and is silently dropped under Do Not Disturb, and by the
     * ring-mode-silent policy on many OEM builds — which is precisely when a
     * rest alarm most needs to land. `USAGE_ALARM` is the class the platform
     * lets through under its own "allow alarms" rule.
     */
    @SuppressLint("MissingPermission")
    private fun vibrateRestAlarm() {
        val vibrator = vibrator() ?: return
        if (!vibrator.hasVibrator()) return

        // Long, short, long — a pattern that reads as insistent rather than as
        // a message arriving, and repeats from the top forever.
        val pattern = longArrayOf(0, 600, 250, 300, 250, 600, 900)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
                    vibrator.vibrate(
                        effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, alarmVibrationAttributes())
                }

                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0, alarmVibrationAttributes())
                }
            }
        }
    }

    private fun stopAlarmVibration() {
        runCatching { vibrator()?.cancel() }
    }

    /**
     * The rest alarm's sound, filed as **media**.
     *
     * `USAGE_ALARM` would be the obvious choice and is not the one made here.
     * Usage decides which of the phone's volume sliders governs the sound, and
     * an alarm-usage tone answers only to the alarm slider — which is the one
     * nobody adjusts, so the rest alarm arrived at whatever level the last
     * morning alarm was set to and ignored the volume keys entirely.
     *
     * Media is the slider people actually hold. What it costs: the alarm stream
     * is exempt from Do Not Disturb by default and media is exempt only while
     * DND's "Media" allowance is on — which it is out of the box, but can be
     * turned off. Silent mode is unaffected either way; the ringer switch has
     * never governed media.
     *
     * The vibration keeps `USAGE_ALARM`, below, because vibration has no volume
     * for this to trade against and so nothing to gain by moving.
     */
    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /**
     * The attributes the pre-33 vibration API takes.
     *
     * Still an alarm: this is the API's only way to say "this buzz is not a
     * notification", and it is what keeps the vibration from being dropped
     * under Do Not Disturb and by the ring-mode policy on many OEM builds.
     */
    private fun alarmVibrationAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

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

    /**
     * One cycle of the rest alarm: three rising beeps, then a gap.
     *
     * Deliberately not the session chime. That one resolves — it is a full stop,
     * and a full stop repeated forty times still sounds like good news. This
     * rises and does not resolve, which is what makes a sound read as a demand,
     * and it sits high enough (880-1318 Hz) to carry through a pocket and over
     * a room, where the chime's low C would not.
     *
     * The gap matters as much as the beeps: an unbroken tone is easy for a
     * half-asleep ear to fold into the background, and impossible to talk over
     * if the user is mid-sentence when it lands.
     */
    private fun buildRestAlarmCycle(): ShortArray {
        val cycleSeconds = 2.2
        val samples = ShortArray((cycleSeconds * SAMPLE_RATE).toInt())
        val mix = DoubleArray(samples.size)

        // A, C#, E an octave up — the same triad the chime uses, climbing
        // instead of settling.
        val frequencies = doubleArrayOf(880.0, 1108.73, 1318.51)
        val beepLength = 0.30

        frequencies.forEachIndexed { index, frequency ->
            val start = (index * 0.36 * SAMPLE_RATE).toInt()
            val length = (beepLength * SAMPLE_RATE).toInt()
            for (i in 0 until length) {
                val at = start + i
                if (at >= mix.size) break
                val t = i.toDouble() / SAMPLE_RATE
                // A near-square attack and a hard cut: no bell-like decay, which
                // would blur the three beeps into one wash.
                val envelope = min(1.0, t / 0.008) *
                    min(1.0, ((beepLength - t) / 0.03).coerceAtLeast(0.0))
                // Square-ish through a triangle plus its fifth harmonic: cuts
                // through far better than a sine at the same measured level.
                val phase = at.toDouble() / SAMPLE_RATE
                mix[at] += (triangle(frequency, phase) * 0.5 +
                    triangle(frequency * 2.0, phase) * 0.18) * envelope * 0.62
            }
        }

        return applyAlarmGain(mix, samples)
    }

    /**
     * The alarm's own level, floored at full scale.
     *
     * The chime's slider is allowed to raise this and not to lower it. Someone
     * who turned the chime down did so to be less disturbed while focusing,
     * which says nothing about whether they want to be woken from a break — and
     * an alarm quiet enough to sleep through is not an alarm.
     */
    private fun applyAlarmGain(mix: DoubleArray, out: ShortArray): ShortArray {
        val gain = settings.soundVolume.value.coerceIn(1f, 2f)
        for (i in mix.indices) {
            val value = (mix[i] * gain * Short.MAX_VALUE).coerceIn(MIN_SAMPLE, MAX_SAMPLE)
            out[i] = value.roundToInt().toShort()
        }
        return out
    }

    /**
     * Plays [samples], repeating forever when [loop] is set.
     *
     * `MODE_STATIC` puts the whole cycle in the track's own buffer and
     * `setLoopPoints(..., -1)` repeats it in the audio hardware, so once this
     * returns there is nothing left for the app to do or to fail to do.
     *
     * **Whether it loops is decided here and cannot be changed afterwards.** A
     * static track rejects `setLoopPoints` once it is playing, so there is no
     * such thing as starting the loop and calling it back — the choice has to
     * be made before [AudioTrack.play], which is why it is a parameter rather
     * than something the caller adjusts.
     */
    private fun playAlarm(samples: ShortArray, loop: Boolean) {
        if (samples.isEmpty()) return
        releaseAlarm()

        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(alarmAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        runCatching {
            created.write(samples, 0, samples.size)
            // -1 is "loop forever"; the frame count is the whole buffer. Before
            // play(), for the reason in the note above.
            if (loop) created.setLoopPoints(0, samples.size, -1)
            created.play()
            alarmTrack = created
        }.onFailure { created.release() }
    }

    private fun releaseAlarm() {
        alarmTrack?.let { existing ->
            // Stopped first: releasing a track that is still playing is
            // allowed but leaves the tail to the mixer's discretion, and the
            // one thing this must never do is keep making noise.
            runCatching { existing.pause() }
            runCatching { existing.flush() }
            runCatching { existing.release() }
        }
        alarmTrack = null
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

        /** Slack after the audition's last sample, so nothing is clipped. */
        const val PREVIEW_TAIL_MS = 150L
        const val TWO_PI = 2.0 * PI
        const val MIN_SAMPLE = -32768.0
        const val MAX_SAMPLE = 32767.0
    }
}
