package com.kapamilya.serena.mail.sound

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

object KapamilyaKrisSoundEngine {

    private const val SAMPLE_RATE = 48000
    private val soundExecutor = Executors.newCachedThreadPool()
    @Volatile private var lastClickSoundMs = 0L
    @Volatile private var lastFlickSoundMs = 0L
    @Volatile private var lastEdgeSoundMs = 0L

    private val pcmDrawStartup: ShortArray by lazy { synthesizeKrisDrawStartup() }
    private val pcmDrawClick: ShortArray by lazy { synthesizeKrisDrawClick() }
    private val pcmSheatheShutdown: ShortArray by lazy { synthesizeKrisSheatheShutdown() }
    private val pcmSlashEdge: ShortArray by lazy { synthesizeKrisSlashEdge() }
    private val pcmSwingFlick: ShortArray by lazy { synthesizeKrisSwingFlick() }

    private fun pseudoNoise(idx: Int): Double {
        var n = (idx.toLong() * 747796405L + 2891336453L) and 0xFFFFFFFFL
        val shift = ((n ushr 28) + 4).toInt()
        n = ((n ushr shift) xor n) * 277803737L and 0xFFFFFFFFL
        n = (n ushr 22) xor n
        return ((n and 0xFFFFL).toDouble() / 32768.0) - 1.0
    }

    private fun synthesizeKrisDrawStartup(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.38).toInt()
        val buf = ShortArray(totalSamples)
        var phase1 = 0.0
        var phase2 = 0.0
        var phase3 = 0.0
        var lpNoise = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            if (t < 0.032) {
                val env = exp(-t * 140.0)
                sample += env * (0.55 * sin(2.0 * Math.PI * 1920.0 * t) + 0.35 * sin(2.0 * Math.PI * 3840.0 * t) + 0.25 * pseudoNoise(i))
            }
            if (t >= 0.020 && t < 0.260) {
                val progress = (t - 0.020) / 0.240
                val waveMod = sin(2.0 * Math.PI * 7.0 * progress)
                val amEnv = sin(Math.PI * progress) * (0.72 + 0.28 * waveMod)
                val scrapeFreq = 980.0 + 1650.0 * progress.pow(1.15) + 195.0 * waveMod
                phase1 += 2.0 * Math.PI * scrapeFreq / SAMPLE_RATE
                val rawN = pseudoNoise(i + 1000)
                lpNoise = 0.82 * lpNoise + 0.18 * rawN
                sample += amEnv * (0.48 * sin(phase1) + 0.28 * sin(phase1 * 2.41) + 0.32 * (rawN - lpNoise))
            }
            if (t >= 0.170) {
                val ringT = t - 0.170
                val ringEnv = exp(-ringT * 14.5) * min(1.0, ringT / 0.012)
                phase2 += 2.0 * Math.PI * 2637.02 / SAMPLE_RATE
                phase3 += 2.0 * Math.PI * 3951.07 / SAMPLE_RATE
                sample += ringEnv * (0.52 * sin(phase2) + 0.30 * sin(phase3) + 0.18 * sin(2.0 * Math.PI * 5274.04 * ringT))
            }
            buf[i] = (max(-0.98, min(0.98, sample)) * 28000.0).toInt().toShort()
        }
        return buf
    }

    private fun synthesizeKrisDrawClick(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.155).toInt()
        val buf = ShortArray(totalSamples)
        var phase1 = 0.0
        var phase2 = 0.0
        var lpNoise = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            if (t < 0.018) {
                val env = exp(-t * 190.0)
                sample += env * (0.60 * sin(2.0 * Math.PI * 2150.0 * t) + 0.35 * sin(2.0 * Math.PI * 4300.0 * t))
            }
            if (t >= 0.010 && t < 0.105) {
                val progress = (t - 0.010) / 0.095
                val waveMod = sin(2.0 * Math.PI * 5.0 * progress)
                val amEnv = sin(Math.PI * progress) * (0.75 + 0.25 * waveMod)
                val freq = 1220.0 + 1850.0 * progress + 170.0 * waveMod
                phase1 += 2.0 * Math.PI * freq / SAMPLE_RATE
                val rawN = pseudoNoise(i + 5000)
                lpNoise = 0.80 * lpNoise + 0.20 * rawN
                sample += amEnv * (0.50 * sin(phase1) + 0.25 * sin(phase1 * 2.3) + 0.28 * (rawN - lpNoise))
            }
            if (t >= 0.068) {
                val ringT = t - 0.068
                val ringEnv = exp(-ringT * 28.0) * min(1.0, ringT / 0.006)
                phase2 += 2.0 * Math.PI * 2960.0 / SAMPLE_RATE
                sample += ringEnv * (0.55 * sin(phase2) + 0.30 * sin(phase2 * 1.5))
            }
            buf[i] = (max(-0.98, min(0.98, sample)) * 28000.0).toInt().toShort()
        }
        return buf
    }

    private fun synthesizeKrisSheatheShutdown(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.35).toInt()
        val buf = ShortArray(totalSamples)
        var phase1 = 0.0
        var lpNoise = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            if (t < 0.240) {
                val progress = t / 0.240
                val waveMod = sin(2.0 * Math.PI * 6.0 * progress)
                val amEnv = sin(Math.PI * progress) * (0.74 + 0.26 * waveMod)
                val freq = 2350.0 - 1580.0 * progress.pow(0.9) + 150.0 * waveMod
                phase1 += 2.0 * Math.PI * freq / SAMPLE_RATE
                val rawN = pseudoNoise(i + 9000)
                lpNoise = 0.78 * lpNoise + 0.22 * rawN
                sample += amEnv * (0.42 * sin(phase1) + 0.22 * sin(phase1 * 1.85) + 0.32 * lpNoise)
            }
            if (t >= 0.235) {
                val lockT = t - 0.235
                val woodEnv = exp(-lockT * 58.0)
                val brassEnv = exp(-lockT * 95.0)
                sample += woodEnv * (0.62 * sin(2.0 * Math.PI * 390.0 * lockT) + 0.35 * sin(2.0 * Math.PI * 780.0 * lockT)) +
                        brassEnv * (0.50 * sin(2.0 * Math.PI * 1680.0 * lockT) + 0.30 * pseudoNoise(i + 15000))
            }
            buf[i] = (max(-0.98, min(0.98, sample)) * 28000.0).toInt().toShort()
        }
        return buf
    }

    private fun synthesizeKrisSlashEdge(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.130).toInt()
        val buf = ShortArray(totalSamples)
        var phase1 = 0.0
        var lpNoise = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            if (t < 0.075) {
                val progress = t / 0.075
                val env = sin(Math.PI * progress)
                val swoopFreq = 1850.0 * exp(-progress * 1.75) + 220.0
                phase1 += 2.0 * Math.PI * swoopFreq / SAMPLE_RATE
                val rawN = pseudoNoise(i + 20000)
                lpNoise = 0.72 * lpNoise + 0.28 * rawN
                sample += env * (0.48 * sin(phase1) + 0.45 * (rawN - lpNoise * 0.5))
            }
            if (t >= 0.052) {
                val cutT = t - 0.052
                val cutEnv = exp(-cutT * 52.0)
                sample += cutEnv * (0.55 * sin(2.0 * Math.PI * 1420.0 * cutT) + 0.35 * sin(2.0 * Math.PI * 2840.0 * cutT) + 0.40 * sin(2.0 * Math.PI * 260.0 * cutT))
            }
            buf[i] = (max(-0.98, min(0.98, sample)) * 28000.0).toInt().toShort()
        }
        return buf
    }

    private fun synthesizeKrisSwingFlick(): ShortArray {
        val totalSamples = (SAMPLE_RATE * 0.056).toInt()
        val buf = ShortArray(totalSamples)
        var phase1 = 0.0
        var lp1 = 0.0
        var lp2 = 0.0
        for (i in 0 until totalSamples) {
            val progress = i.toDouble() / totalSamples
            val env = sin(Math.PI * progress).pow(1.45)
            val waveMod = 0.82 + 0.18 * sin(2.0 * Math.PI * 3.0 * progress)
            val whistleFreq = 580.0 + 640.0 * sin(Math.PI * progress)
            phase1 += 2.0 * Math.PI * whistleFreq / SAMPLE_RATE
            val rawN = pseudoNoise(i + 30000)
            lp1 = 0.76 * lp1 + 0.24 * rawN
            lp2 = 0.88 * lp2 + 0.12 * lp1
            val sample = env * waveMod * (0.42 * sin(phase1) + 0.18 * sin(phase1 * 2.0) + 0.58 * (lp1 - lp2))
            buf[i] = (max(-0.95, min(0.95, sample)) * 25000.0).toInt().toShort()
        }
        return buf
    }

    private fun playPcmAsync(samples: ShortArray, leftVol: Float = 1.0f, rightVol: Float = 1.0f) {
        soundExecutor.execute {
            var track: AudioTrack? = null
            try {
                val byteSize = samples.size * 2
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
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
                    .setBufferSizeInBytes(max(byteSize, AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.setStereoVolume(leftVol.coerceIn(0f, 1f), rightVol.coerceIn(0f, 1f))
                track.play()
                Thread.sleep(((samples.size * 1000L) / SAMPLE_RATE) + 45L)
            } catch (_: Throwable) {
            } finally {
                try { track?.stop() } catch (_: Throwable) {}
                try { track?.release() } catch (_: Throwable) {}
            }
        }
    }

    fun playKrisDrawStartup() = playPcmAsync(pcmDrawStartup)

    fun playKrisDrawClick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickSoundMs < 70L) return
        lastClickSoundMs = now
        playPcmAsync(pcmDrawClick)
    }

    fun playKrisSheatheShutdown() = playPcmAsync(pcmSheatheShutdown)

    fun playKrisSlashEdge() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeSoundMs < 140L) return
        lastEdgeSoundMs = now
        playPcmAsync(pcmSlashEdge)
    }

    fun playKrisSwingFlick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFlickSoundMs < 40L) return
        lastFlickSoundMs = now
        playPcmAsync(pcmSwingFlick)
    }

    private fun isKapamilyaSerenaServiceActive(activity: Activity): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                activity.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains("com.kapamilya.serena", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun attachToActivity(activity: Activity, playStartupSound: Boolean = true) {
        if (playStartupSound) {
            playKrisDrawStartup()
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent
            ): Boolean {
                if (!isKapamilyaSerenaServiceActive(activity)) {
                    when (event.eventType) {
                        AccessibilityEvent.TYPE_VIEW_CLICKED -> playKrisDrawClick()
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                        AccessibilityEvent.TYPE_VIEW_FOCUSED -> playKrisSwingFlick()
                        AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                            if (!child.canScrollVertically(-1) || !child.canScrollVertically(1)) {
                                playKrisSlashEdge()
                            }
                        }
                    }
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
    }
}
