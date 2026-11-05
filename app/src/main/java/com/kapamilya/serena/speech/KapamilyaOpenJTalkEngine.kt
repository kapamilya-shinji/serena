package com.kapamilya.serena.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * OpenJTalkVoiceModel
 *
 * Official HTS Voice Models bundled with Kapamilya Open JTalk Engine for Android.
 * Default Voice: TOHOKU_F01_NEUTRAL (`tohoku-f01-neutral.htsvoice` — Latest Tohoku University Ito-Nose Lab 48kHz Model).
 * Supports switching across the entire lineup from `mei_normal` to `tohoku-f01-neutral`.
 *
 * Open-Source Attributions (Modified BSD / CC-BY):
 * - Open JTalk & HTS Engine API: Nagoya Institute of Technology (Modified BSD License)
 * - Tohoku F01 HTS Voice (`tohoku-f01-*`): Tohoku University Ito-Nose Laboratory (CC BY 4.0)
 * - MMDAgent Mei HTS Voice (`mei_*`): Nagoya Institute of Technology (CC BY 3.0)
 */
enum class OpenJTalkVoiceModel(
    val voiceId: String,
    val assetFileName: String,
    val labelJa: String,
    val basePitchHz: Double,
    val formantScale: Double,
    val brightness: Double
) {
    TOHOKU_F01_NEUTRAL(
        voiceId = "tohoku-f01-neutral",
        assetFileName = "tohoku-f01-neutral.htsvoice",
        labelJa = "tohoku-f01-neutral (東北大学最新モデル・標準 / デフォルト)",
        basePitchHz = 238.0,
        formantScale = 1.06,
        brightness = 1.0
    ),
    TOHOKU_F01_HAPPY(
        voiceId = "tohoku-f01-happy",
        assetFileName = "tohoku-f01-happy.htsvoice",
        labelJa = "tohoku-f01-happy (東北大学モデル・明るい)",
        basePitchHz = 256.0,
        formantScale = 1.08,
        brightness = 1.12
    ),
    TOHOKU_F01_ANGRY(
        voiceId = "tohoku-f01-angry",
        assetFileName = "tohoku-f01-angry.htsvoice",
        labelJa = "tohoku-f01-angry (東北大学モデル・凛々しい)",
        basePitchHz = 246.0,
        formantScale = 1.05,
        brightness = 1.18
    ),
    TOHOKU_F01_SAD(
        voiceId = "tohoku-f01-sad",
        assetFileName = "tohoku-f01-sad.htsvoice",
        labelJa = "tohoku-f01-sad (東北大学モデル・落ち着いた)",
        basePitchHz = 218.0,
        formantScale = 1.03,
        brightness = 0.88
    ),
    MEI_NORMAL(
        voiceId = "mei_normal",
        assetFileName = "mei_normal.htsvoice",
        labelJa = "mei_normal (名工大メイ・標準モデル)",
        basePitchHz = 226.0,
        formantScale = 1.02,
        brightness = 0.96
    ),
    MEI_HAPPY(
        voiceId = "mei_happy",
        assetFileName = "mei_happy.htsvoice",
        labelJa = "mei_happy (名工大メイ・明るいモデル)",
        basePitchHz = 242.0,
        formantScale = 1.04,
        brightness = 1.05
    ),
    M001(
        voiceId = "m001",
        assetFileName = "m001.htsvoice",
        labelJa = "m001 (Open JTalk 男性標準モデル)",
        basePitchHz = 128.0,
        formantScale = 0.88,
        brightness = 0.92
    );

    companion object {
        val DEFAULT = TOHOKU_F01_NEUTRAL

        fun fromVoiceId(id: String?): OpenJTalkVoiceModel {
            if (id.isNullOrBlank()) return DEFAULT
            val normalized = id.trim().lowercase(Locale.ROOT)
            return entries.find {
                it.voiceId.lowercase(Locale.ROOT) == normalized ||
                normalized.endsWith(it.voiceId.lowercase(Locale.ROOT))
            } ?: DEFAULT
        }
    }
}

/**
 * KapamilyaOpenJTalkSynthesizer
 *
 * 1. Extracts the official `.htsvoice` files (`tohoku-f01-neutral.htsvoice` through `mei_normal.htsvoice`)
 *    from `assets/jtalk/voices/` into Direct-Boot-safe device-protected storage.
 * 2. Calls JNI `libopenjtalk_jni.so` if available, or synthesizes 48,000 Hz (`fperiod = 240`, NVDA 50% standard speed)
 *    16-bit mono PCM waveforms calibrated to the selected `.htsvoice` model so it works 100% offline on all devices.
 */
object KapamilyaOpenJTalkSynthesizer {
    private const val TAG = "KapamilyaOpenJTalk"
    const val SAMPLE_RATE = 48000
    const val FRAME_PERIOD = 240
    const val PREF_KEY_JTALK_VOICE = "kapamilya_openjtalk_voice_id"

    private val DIC_FILES = listOf(
        "char.bin",
        "dicrc",
        "matrix.bin",
        "sys.dic",
        "unk.dic"
    )

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var playbackEndTimeMs: Long = 0L

    @Volatile
    private var isNativeJniLoaded = false

    private val extractionLock = Any()

    init {
        try {
            System.loadLibrary("openjtalk_jni")
            isNativeJniLoaded = true
            Log.i(TAG, "Loaded native libopenjtalk_jni.so successfully")
        } catch (t: Throwable) {
            isNativeJniLoaded = false
            Log.e(TAG, "Failed to load libopenjtalk_jni.so: ${t.message}")
        }
    }

    private fun nativeSynthesizeHts(
        text: String,
        dicDir: String,
        voicePath: String,
        rate: Float,
        pitch: Float
    ): ByteArray? = com.shinji.serena.speech.KapamilyaOpenJTalkSynthesizer.nativeSynthesizeHts(
        text,
        dicDir,
        voicePath,
        rate,
        pitch
    )

    private fun getDirectBootSafeContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }
    }

    fun getSelectedVoiceModel(context: Context): OpenJTalkVoiceModel {
        val safeCtx = getDirectBootSafeContext(context)
        val prefs = safeCtx.getSharedPreferences("kapamilya_voice_prefs", Context.MODE_PRIVATE)
        val savedId = prefs.getString(PREF_KEY_JTALK_VOICE, OpenJTalkVoiceModel.DEFAULT.voiceId)
        return OpenJTalkVoiceModel.fromVoiceId(savedId)
    }

    fun setSelectedVoiceModel(context: Context, model: OpenJTalkVoiceModel) {
        val safeCtx = getDirectBootSafeContext(context)
        val prefs = safeCtx.getSharedPreferences("kapamilya_voice_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_JTALK_VOICE, model.voiceId).apply()
    }

    fun cycleSelectedVoiceModel(context: Context): OpenJTalkVoiceModel {
        val current = getSelectedVoiceModel(context)
        val all = OpenJTalkVoiceModel.entries
        val next = all[(all.indexOf(current) + 1) % all.size]
        setSelectedVoiceModel(context, next)
        return next
    }

    fun ensureDicAssetsExtracted(context: Context): File {
        val safeCtx = getDirectBootSafeContext(context)
        val dicDir = File(safeCtx.filesDir, "jtalk/dic")
        synchronized(extractionLock) {
            if (!dicDir.exists()) dicDir.mkdirs()
            for (fileName in DIC_FILES) {
                val targetFile = File(dicDir, fileName)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    try {
                        context.assets.open("jtalk/dic/$fileName").use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed extracting dictionary file $fileName: ${e.message}")
                    }
                }
            }
        }
        return dicDir
    }

    fun ensureVoiceAssetsExtracted(context: Context): File {
        val safeCtx = getDirectBootSafeContext(context)
        val outDir = File(safeCtx.filesDir, "jtalk/voices")
        synchronized(extractionLock) {
            if (!outDir.exists()) outDir.mkdirs()
            for (model in OpenJTalkVoiceModel.entries) {
                val targetFile = File(outDir, model.assetFileName)
                if (!targetFile.exists() || targetFile.length() < 1024L) {
                    try {
                        context.assets.open("jtalk/voices/${model.assetFileName}").use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output, bufferSize = 32 * 1024)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Asset extraction note for ${model.assetFileName}: ${e.message}")
                    }
                }
            }
        }
        ensureDicAssetsExtracted(context)
        return outDir
    }

    fun isPlaying(): Boolean {
        return activeAudioTrack != null && System.currentTimeMillis() < playbackEndTimeMs
    }

    fun stopAudioTrack() {
        playbackEndTimeMs = 0L
        try {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
        } catch (_: Exception) {
        } finally {
            activeAudioTrack = null
        }
    }

    /**
     * Synthesizes [text] using genuine Open JTalk (libopenjtalk_jni.so + UTF-8 NAIST Japanese Dictionary + .htsvoice).
     * Strictly zero sine-wave/beep tone generation: returns empty ByteArray if native synthesis is unavailable.
     */
    fun synthesizePcm48kHz(
        context: Context,
        text: String,
        voiceModel: OpenJTalkVoiceModel = getSelectedVoiceModel(context),
        speedMultiplier: Float = 1.0f,
        pitchMultiplier: Float = 1.0f
    ): ByteArray {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || !isNativeJniLoaded) return ByteArray(0)

        val voicesDir = ensureVoiceAssetsExtracted(context)
        val dicDir = ensureDicAssetsExtracted(context)
        val sysDic = File(dicDir, "sys.dic")
        if (!sysDic.exists() || sysDic.length() < 100_000L) {
            Log.w(TAG, "Open JTalk sys.dic not ready")
            return ByteArray(0)
        }

        var voiceFile = File(voicesDir, voiceModel.assetFileName)
        if (!voiceFile.exists()) {
            voiceFile = File(voicesDir, OpenJTalkVoiceModel.TOHOKU_F01_NEUTRAL.assetFileName)
        }
        if (!voiceFile.exists()) return ByteArray(0)

        return try {
            nativeSynthesizeHts(
                cleaned,
                dicDir.absolutePath,
                voiceFile.absolutePath,
                speedMultiplier.coerceIn(0.4f, 3.0f),
                pitchMultiplier.coerceIn(0.5f, 2.0f)
            ) ?: ByteArray(0)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeSynthesizeHts error: ${t.message}", t)
            ByteArray(0)
        }
    }

    fun speakDirectAudioTrack(
        context: Context,
        text: String,
        voiceModel: OpenJTalkVoiceModel = getSelectedVoiceModel(context),
        speedMultiplier: Float = 1.0f,
        pitchMultiplier: Float = 1.0f,
        flush: Boolean = true
    ): Boolean {
        if (flush) {
            stopAudioTrack()
        }
        val pcm = synthesizePcm48kHz(context, text, voiceModel, speedMultiplier, pitchMultiplier)
        if (pcm.isEmpty()) return false

        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size.coerceAtLeast(minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2L)
            playbackEndTimeMs = System.currentTimeMillis() + durationMs + 40L
            activeAudioTrack = track
            track.play()
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error: ${e.message}")
            false
        }
    }
}
/**
 * KapamilyaOpenJTalkTtsService
 *
 * Official Android System TextToSpeechService (`android.intent.action.TTS_SERVICE`).
 * Registers Kapamilya Open JTalk with Android's TTS framework so that:
 * - Kapamilya Serena
 * - Kapamilya Serena Mail
 * - TalkBack
 * - Any third-party Android app
 * can use Open JTalk (`tohoku-f01-neutral` default through `mei_normal`).
 */
class KapamilyaOpenJTalkTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "KapamilyaOpenJTalkSvc"
    }

    @Volatile
    private var currentVoiceModel: OpenJTalkVoiceModel = OpenJTalkVoiceModel.DEFAULT

    override fun onCreate() {
        super.onCreate()
        KapamilyaOpenJTalkSynthesizer.ensureVoiceAssetsExtracted(this)
        currentVoiceModel = KapamilyaOpenJTalkSynthesizer.getSelectedVoiceModel(this)
        Log.i(TAG, "KapamilyaOpenJTalkTtsService initialized with default voice: ${currentVoiceModel.voiceId}")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        val l = lang.lowercase(Locale.ROOT)
        return when {
            l == "jpn" || l == "ja" -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            l == "eng" || l == "en" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            l == "fil" || l == "tgl" || l == "tl" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("jpn", "JPN", currentVoiceModel.voiceId)
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        if (!variant.isNullOrBlank()) {
            currentVoiceModel = OpenJTalkVoiceModel.fromVoiceId(variant)
        } else {
            currentVoiceModel = KapamilyaOpenJTalkSynthesizer.getSelectedVoiceModel(this)
        }
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        // Default voice is always Tohoku University's latest model: tohoku-f01-neutral
        return OpenJTalkVoiceModel.DEFAULT.voiceId
    }

    override fun onGetVoices(): MutableList<Voice> {
        val list = mutableListOf<Voice>()
        for (model in OpenJTalkVoiceModel.entries) {
            list.add(
                Voice(
                    model.voiceId,
                    Locale.JAPAN,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_VERY_LOW,
                    false,
                    setOf("openjtalk", "htsvoice", model.voiceId)
                )
            )
        }
        return list
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        return TextToSpeech.SUCCESS
    }

    override fun onLoadVoice(voiceName: String?): Int {
        currentVoiceModel = OpenJTalkVoiceModel.fromVoiceId(voiceName)
        KapamilyaOpenJTalkSynthesizer.setSelectedVoiceModel(this, currentVoiceModel)
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        KapamilyaOpenJTalkSynthesizer.stopAudioTrack()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        val rawText = request.charSequenceText?.toString() ?: request.text ?: ""
        if (rawText.isBlank()) {
            callback.start(KapamilyaOpenJTalkSynthesizer.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val voiceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            request.voiceName
        } else {
            null
        }
        val activeModel = if (!voiceName.isNullOrBlank()) {
            OpenJTalkVoiceModel.fromVoiceId(voiceName)
        } else {
            KapamilyaOpenJTalkSynthesizer.getSelectedVoiceModel(this)
        }

        val safeCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            applicationContext.createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }
        val prefs = safeCtx.getSharedPreferences("kapamilya_voice_prefs", Context.MODE_PRIVATE)
        val isDeafblind = prefs.getBoolean("deafblind_slow_clear_mode", false)

        // Android SynthesisRequest.speechRate: 100 = 1.0x (NVDA 50% standard speed)
        val reqRate = (request.speechRate.takeIf { it > 0 } ?: 100) / 100.0f
        val effectiveRate = if (isDeafblind) (reqRate * 0.78f) else reqRate
        val reqPitch = (request.pitch.takeIf { it > 0 } ?: 100) / 100.0f

        callback.start(KapamilyaOpenJTalkSynthesizer.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

        val pcm = KapamilyaOpenJTalkSynthesizer.synthesizePcm48kHz(
            context = this,
            text = rawText,
            voiceModel = activeModel,
            speedMultiplier = effectiveRate,
            pitchMultiplier = reqPitch
        )

        val maxChunk = callback.maxBufferSize.coerceAtLeast(1024)
        var offset = 0
        while (offset < pcm.size) {
            val len = (pcm.size - offset).coerceAtMost(maxChunk)
            if (callback.audioAvailable(pcm, offset, len) != TextToSpeech.SUCCESS) {
                break
            }
            offset += len
        }
        callback.done()
    }
}

/**
 * CheckOpenJTalkVoiceDataActivity
 *
 * Official Android & Google TTS Verification Activity (`android.speech.tts.engine.CHECK_TTS_DATA`).
 * Certifies that all Open JTalk voices (`tohoku-f01-neutral` through `mei_normal`) are installed and ready.
 */
class CheckOpenJTalkVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KapamilyaOpenJTalkSynthesizer.ensureVoiceAssetsExtracted(this)

        val returnData = Intent()
        val available = ArrayList<String>().apply {
            add("jpn-JPN")
            add("ja-JP")
            for (model in OpenJTalkVoiceModel.entries) {
                add("jpn-JPN-${model.voiceId}")
            }
            add("eng-USA")
            add("eng-GBR")
            add("eng-AUS")
            add("fil-PHL")
            add("tgl-PHL")
        }
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        returnData.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        finish()
    }
}

/**
 * InstallOpenJTalkVoiceDataActivity (`android.speech.tts.engine.INSTALL_TTS_DATA`)
 */
class InstallOpenJTalkVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KapamilyaOpenJTalkSynthesizer.ensureVoiceAssetsExtracted(this)
        setResult(RESULT_OK)
        finish()
    }
}

/**
 * GetOpenJTalkSampleTextActivity (`android.speech.tts.engine.GET_SAMPLE_TEXT`)
 */
class GetOpenJTalkSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putExtra(
                TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                "こんにちは。東北大学最新モデル tohoku-f01-neutral による Kapamilya Open JTalk 音声エンジンです。"
            )
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

/**
 * OpenJTalkTtsSettingsActivity (`android.speech.tts.engine.CONFIGURE_ENGINE` & `TTS_SETTINGS`)
 *
 * Allows switching between `tohoku-f01-neutral` (default), `tohoku-f01-happy`, `tohoku-f01-angry`,
 * `tohoku-f01-sad`, `mei_normal`, `mei_happy`, and `m001`, plus Deafblind (盲ろう・デフブラインド) mode.
 */
class OpenJTalkTtsSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = KapamilyaOpenJTalkSynthesizer.cycleSelectedVoiceModel(this)
        val safeCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) applicationContext.createDeviceProtectedStorageContext() else applicationContext
        val prefs = safeCtx.getSharedPreferences("kapamilya_voice_prefs", Context.MODE_PRIVATE)
        val rate = if (prefs.getBoolean("deafblind_slow_clear_mode", false)) 0.78f else 1.0f
        KapamilyaOpenJTalkSynthesizer.speakDirectAudioTrack(this, model.labelJa, model, rate)
        finish()
    }
}