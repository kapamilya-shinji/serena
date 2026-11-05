package com.kapamilya.serena.recorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kapamilya Serena Recorder — Background Vocal Recording Foreground Service
 * Keeps a PARTIAL_WAKE_LOCK and Foreground Microphone Service active so long a cappella vocal takes
 * are never interrupted when the screen locks or the user switches apps.
 */
class KapamilyaRecorderForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "kapamilya_recorder_channel"
        const val NOTIFICATION_ID = 5301
        private val isRecordingFlag = AtomicBoolean(false)
        @Volatile
        private var lastRecordedSamples: FloatArray = FloatArray(0)

        fun isCurrentlyRecording(): Boolean = isRecordingFlag.get()
        fun getLastRecordedSamples(): FloatArray = lastRecordedSamples
    }

    inner class LocalBinder : Binder() {
        fun getService(): KapamilyaRecorderForegroundService = this@KapamilyaRecorderForegroundService
    }

    private val binder = LocalBinder()
    private var recordingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("Kapamilya Serena Recorder: 48kHz ロスレス録音待機中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    fun startVocalRecording(delayMsForScreenReaderSilence: Long = 900L, onStarted: () -> Unit) {
        if (isRecordingFlag.getAndSet(true)) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KapamilyaSerenaRecorder::VocalWakeLock").apply {
            acquire(30 * 60 * 1000L) // up to 30 mins
        }

        val notif = buildNotification("Kapamilya Serena Recorder: アカペラボーカル高音質録音中 (48kHz)")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notif)

        recordingThread = Thread {
            try {
                // Wait for screen reader announcement ("録音開始ボタン") to finish before opening microphone gate
                if (delayMsForScreenReaderSilence > 0) {
                    Thread.sleep(delayMsForScreenReaderSilence)
                }
                val sr = KapamilyaFlacAndCodecEngine.SAMPLE_RATE
                val minBuf = AudioRecord.getMinBufferSize(
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(sr)

                // Use UNPROCESSED or VOICE_RECOGNITION source for pure, un-muffled natural singing voice
                val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }

                var recorder = AudioRecord(
                    source,
                    sr,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sr,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                }

                val pcmStream = ByteArrayOutputStream(sr * 2 * 60)
                val readShorts = ShortArray(2048)
                val byteChunk = ByteArray(readShorts.size * 2)

                recorder.startRecording()
                onStarted()

                while (isRecordingFlag.get()) {
                    val read = recorder.read(readShorts, 0, readShorts.size)
                    if (read > 0) {
                        ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(readShorts, 0, read)
                        pcmStream.write(byteChunk, 0, read * 2)
                    }
                }

                try {
                    recorder.stop()
                } catch (_: Exception) {}
                recorder.release()

                val rawBytes = pcmStream.toByteArray()
                val totalShorts = rawBytes.size / 2
                val shortBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val floats = FloatArray(totalShorts)
                for (i in 0 until totalShorts) {
                    floats[i] = shortBuf.get(i) / 32768.0f
                }
                lastRecordedSamples = floats
            } catch (_: Exception) {
                isRecordingFlag.set(false)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                } catch (_: Exception) {}
            }
        }.apply {
            name = "KapamilyaVocalRecordThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stopVocalRecording(): FloatArray {
        if (!isRecordingFlag.getAndSet(false)) {
            return lastRecordedSamples
        }
        try {
            recordingThread?.join(3000L)
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return lastRecordedSamples
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kapamilya Serena Recorder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background lossless vocal recording & AI mastering service"
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kapamilya Serena Recorder v5.3.0")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
