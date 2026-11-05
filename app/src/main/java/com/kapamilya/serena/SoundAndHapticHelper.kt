package com.kapamilya.serena

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * SoundAndHapticHelper
 * Google Pixel Buds Pro 2 / Android 17 / Bluetooth LE Audio 最適化
 * フィリピン伝統の波刃刀剣「クリス（カリス / Kris / Kalis）」サウンド＆3D空間ステレオバイノーラル音響エンジン
 */
class SoundAndHapticHelper(private val context: Context) {

    companion object {
        private const val TAG = "SoundAndHapticHelper"
    }

    private var soundPool: SoundPool? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    private var soundFocusId: Int = 0
    private var soundClickId: Int = 0
    private var soundScrollId: Int = 0
    private var soundEdgeId: Int = 0
    private var soundMenuId: Int = 0
    private var soundBackId: Int = 0
    private var soundHourlyChimeId: Int = 0
    private var soundKrisDrawId: Int = 0
    private var soundKrisSheatheId: Int = 0

    init {
        initSoundPool()
        initToneGenerator()
        initVibrator()
    }

    private fun initSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.let { pool ->
                soundFocusId = pool.load(context, R.raw.serena_focus, 1)
                soundClickId = pool.load(context, R.raw.serena_click, 1)
                soundScrollId = pool.load(context, R.raw.serena_scroll, 1)
                soundEdgeId = pool.load(context, R.raw.serena_edge, 1)
                soundMenuId = pool.load(context, R.raw.serena_menu, 1)
                soundBackId = pool.load(context, R.raw.serena_back, 1)
                soundHourlyChimeId = pool.load(context, R.raw.serena_hourly_chime, 1)
                soundKrisDrawId = pool.load(context, R.raw.serena_kris_draw, 1)
                soundKrisSheatheId = pool.load(context, R.raw.serena_kris_sheathe, 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool init failed: ${e.message}")
        }
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 100)
        } catch (e: Exception) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 100)
            } catch (ex: Exception) {
                Log.e(TAG, "ToneGenerator init failed: ${ex.message}")
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun announceTts(text: String) {
        SerenaScreenReaderService.instance?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH)
    }

    fun performHoverHaptic() {
        vibrate(10)
    }

    fun performKeyClickHaptic() {
        playClick()
    }

    /**
     * 1. Kapamilya Serena 起動音（フィリピンの波刃の伝統短剣「クリス / カリス」抜刀音）
     */
    fun playSerenaStartupChime() {
        KapamilyaKrisSoundEngine.playKrisDrawStartup()
        if (soundKrisDrawId != 0 && soundPool != null) {
            soundPool?.play(soundKrisDrawId, 1.0f, 1.0f, 3, 0, 1.0f)
        }
        vibratePattern(longArrayOf(0, 25, 45, 30, 45, 55))
    }

    /**
     * 2. Kapamilya Serena 終了音（フィリピンの波刃の伝統短剣「クリス / カリス」納刀音 — 鞘に収める音）
     */
    fun playSerenaShutdownChime() {
        KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        if (soundKrisSheatheId != 0 && soundPool != null) {
            soundPool?.play(soundKrisSheatheId, 1.0f, 1.0f, 3, 0, 1.0f)
        }
        vibratePattern(longArrayOf(0, 45, 40, 30, 40, 20))
    }

    /**
     * 3. 画面オフ・スリープ・ロック時の専用ロック音（鞘に収めるクリス納刀音）
     */
    fun playScreenLockChime() {
        val played = if (soundKrisSheatheId != 0 && soundPool != null) {
            soundPool?.play(soundKrisSheatheId, 0.90f, 0.90f, 2, 0, 1.05f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        }
        vibratePattern(longArrayOf(0, 20, 35, 35))
    }

    /**
     * ページめくり・スクロール音（クリスの風切り素振り音「シュッ」）
     */
    fun playScroll(isForward: Boolean = true) {
        val rate = if (isForward) 1.05f else 0.95f
        val played = if (soundScrollId != 0 && soundPool != null) {
            soundPool?.play(soundScrollId, 0.85f, 0.85f, 1, 0, rate) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSwingFlick(0.85f, 0.85f)
        }
        vibrate(12)
    }

    enum class HapticTexture {
        BUTTON,
        IMAGE,
        SLIDER,
        EDIT_TEXT,
        LINK,
        DEFAULT
    }

    fun playHapticTexture(texture: HapticTexture) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vibrator != null) {
            val effect = when (texture) {
                HapticTexture.BUTTON -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                HapticTexture.IMAGE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                HapticTexture.SLIDER -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                HapticTexture.EDIT_TEXT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                HapticTexture.LINK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                HapticTexture.DEFAULT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            vibrator?.vibrate(effect)
        } else {
            val duration = when (texture) {
                HapticTexture.BUTTON -> 25L
                HapticTexture.IMAGE -> 10L
                HapticTexture.SLIDER -> 18L
                HapticTexture.EDIT_TEXT -> 35L
                HapticTexture.LINK -> 15L
                HapticTexture.DEFAULT -> 12L
            }
            vibrate(duration)
        }
    }

    /**
     * 通常のフォーカス移動音（右フリック・左フリックでのクリス素振り風切り音「シュッ！」）
     */
    fun playFocusMove() {
        val played = if (soundFocusId != 0 && soundPool != null) {
            soundPool?.play(soundFocusId, 0.90f, 0.90f, 1, 0, 1.0f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSwingFlick(0.90f, 0.90f)
        }
        vibrate(15)
    }

    /**
     * 左右ステレオパンニング対応フォーカス移動音（クリスを振る素振り風切り音 3D空間オーディオ）
     * Pixel Buds Pro 2 で画面上の位置が耳で正確にわかる3D空間オーディオ
     * @param normalizedX 0.0f (左端) 〜 0.5f (中央) 〜 1.0f (右端)
     * @param normalizedY 0.0f (上部/高音) 〜 1.0f (下部/低音)
     */
    fun playFocusMovePanned(normalizedX: Float, normalizedY: Float = 0.5f) {
        val clampedX = normalizedX.coerceIn(0.0f, 1.0f)
        val clampedY = normalizedY.coerceIn(0.0f, 1.0f)

        // ステレオパンニング音量計算 (Pixel Buds Pro 2 で明確に定位)
        val leftVol = ((1.0f - clampedX) * 1.4f).coerceIn(0.18f, 1.0f)
        val rightVol = (clampedX * 1.4f).coerceIn(0.18f, 1.0f)

        // 上下位置に応じたピッチ制御（上部は高音、下部は低音）
        val pitch = (1.15f - clampedY * 0.30f).coerceIn(0.85f, 1.25f)

        val played = if (soundFocusId != 0 && soundPool != null) {
            soundPool?.play(soundFocusId, leftVol, rightVol, 1, 0, pitch) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSwingFlick(leftVol, rightVol)
        }
        vibrate(15)
    }

    fun playSpatialTouchFeedback(normX: Float, normY: Float, isButton: Boolean) {
        val clampedX = normX.coerceIn(0.0f, 1.0f)
        val clampedY = normY.coerceIn(0.0f, 1.0f)
        val leftVol = ((1.0f - clampedX) * 1.4f).coerceIn(0.18f, 1.0f)
        val rightVol = (clampedX * 1.4f).coerceIn(0.18f, 1.0f)
        val pitch = (1.20f - clampedY * 0.40f).coerceIn(0.80f, 1.30f)

        val soundId = if (isButton) soundClickId else soundFocusId
        val played = if (soundId != 0 && soundPool != null) {
            soundPool?.play(soundId, leftVol, rightVol, 1, 0, pitch) ?: 0
        } else 0
        if (played == 0) {
            if (isButton) KapamilyaKrisSoundEngine.playKrisDrawClick(leftVol, rightVol)
            else KapamilyaKrisSoundEngine.playKrisSwingFlick(leftVol, rightVol)
        }

        val vibeTime = when {
            isButton -> 35L
            normY < 0.33f -> 10L
            normY < 0.66f -> 18L
            else -> 25L
        }
        vibrate(vibeTime)
    }

    /**
     * ダブルタップ・決定・実行音（フィリピンの短剣「クリス / カリス」抜刀音）
     */
    fun playClick() {
        val played = if (soundClickId != 0 && soundPool != null) {
            soundPool?.play(soundClickId, 1.0f, 1.0f, 2, 0, 1.0f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisDrawClick()
        }
        vibrate(30)
    }

    /**
     * メニュー展開音（クリス抜刀音）
     */
    fun playMenuOpen() {
        val played = if (soundClickId != 0 && soundPool != null) {
            soundPool?.play(soundClickId, 0.95f, 0.95f, 2, 0, 1.05f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisDrawClick()
        }
        vibrate(20)
    }

    /**
     * 戻る・キャンセル音（クリス納刀音）
     */
    fun playBackSound() {
        val played = if (soundBackId != 0 && soundPool != null) {
            soundPool?.play(soundBackId, 0.90f, 0.90f, 1, 0, 1.0f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        }
        vibrate(15)
    }

    fun playFullChargeJingle() {
        if (soundHourlyChimeId != 0 && soundPool != null) {
            soundPool?.play(soundHourlyChimeId, 1.0f, 1.0f, 3, 0, 1.25f)
            vibratePattern(longArrayOf(0, 30, 40, 60))
        } else {
            KapamilyaKrisSoundEngine.playKrisDrawStartup()
        }
    }

    fun playWarningSound() {
        KapamilyaKrisSoundEngine.playKrisSlashEdge()
        vibratePattern(longArrayOf(0, 50, 40, 50))
    }

    /**
     * 画面端・先頭要素到達音（クリスで切り捨てる一閃斬撃音）
     */
    fun playFirstItemEdgeSound() {
        val played = if (soundEdgeId != 0 && soundPool != null) {
            soundPool?.play(soundEdgeId, 0.95f, 0.95f, 2, 0, 1.12f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSlashEdge()
        }
        vibratePattern(longArrayOf(0, 20, 20, 20))
    }

    /**
     * 画面端・末尾要素到達音（クリスで切り捨てる一閃斬撃音）
     */
    fun playLastItemEdgeSound() {
        val played = if (soundEdgeId != 0 && soundPool != null) {
            soundPool?.play(soundEdgeId, 0.95f, 0.95f, 2, 0, 0.92f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSlashEdge()
        }
        vibratePattern(longArrayOf(0, 35, 30, 35))
    }

    /**
     * 画面境界・端到達音（クリスで切り捨てる一閃斬撃音）
     */
    fun playEdgeReached() {
        val played = if (soundEdgeId != 0 && soundPool != null) {
            soundPool?.play(soundEdgeId, 0.95f, 0.95f, 2, 0, 1.0f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisSlashEdge()
        }
        vibratePattern(longArrayOf(0, 35, 30, 35))
    }

    fun playActionDone() {
        val played = if (soundClickId != 0 && soundPool != null) {
            soundPool?.play(soundClickId, 0.95f, 0.95f, 2, 0, 1.08f) ?: 0
        } else 0
        if (played == 0) {
            KapamilyaKrisSoundEngine.playKrisDrawClick()
        }
        vibratePattern(longArrayOf(0, 25, 30, 25))
    }

    private val chimeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * モダンクリスタル時報メロディ（透明感あふれる最新グラスベル♪）
     */
    fun playHourlyChime() {
        if (soundHourlyChimeId != 0 && soundPool != null) {
            soundPool?.play(soundHourlyChimeId, 1.0f, 1.0f, 3, 0, 1.0f)
            vibratePattern(longArrayOf(0, 40, 80, 50, 80, 60))
        } else {
            playNhkRadioChime()
        }
    }

    fun playNhkRadioChime() {
        playHourlyChime()
    }

    fun playCuteBeepChime() {
        playHourlyChime()
    }

    fun playJapaneseBellChime() {
        playHourlyChime()
    }

    fun playProgressBeep(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val pitch = (0.75f + (clamped / 100.0f) * 0.55f).coerceIn(0.75f, 1.30f)
        if (soundFocusId != 0 && soundPool != null) {
            soundPool?.play(soundFocusId, 0.70f, 0.70f, 1, 0, pitch)
        } else {
            KapamilyaKrisSoundEngine.playKrisSwingFlick(0.70f, 0.70f)
        }
        vibrate(15)
    }

    private fun vibrate(milliseconds: Long) {
        try {
            vibrator?.let {
                if (!it.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(milliseconds)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate error: ${e.message}")
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            vibrator?.let {
                if (!it.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibratePattern error: ${e.message}")
        }
    }

    var isSpatialSoundstageEnabled: Boolean = true
    var isAudioDuckingEnabled: Boolean = true

    enum class WhisperScheduleMode(val resId: Int, val displayName: String) {
        AUTO(R.string.whisper_mode_auto, "夜間自動 (端末時刻 22:00〜07:00)"),
        ALWAYS_ON(R.string.whisper_mode_always, "常時ささやき ON"),
        OFF(R.string.whisper_mode_off, "オフ (通常音声)");

        fun getDisplayName(context: Context): String = try {
            context.getString(resId)
        } catch (_: Exception) {
            displayName
        }
    }

    var whisperScheduleMode: WhisperScheduleMode = WhisperScheduleMode.AUTO
    var isNightWhisperModeEnabled: Boolean
        get() = isEffectiveWhisperMode()
        set(value) {
            whisperScheduleMode = if (value) WhisperScheduleMode.ALWAYS_ON else WhisperScheduleMode.OFF
        }

    fun isEffectiveWhisperMode(): Boolean {
        return when (whisperScheduleMode) {
            WhisperScheduleMode.ALWAYS_ON -> true
            WhisperScheduleMode.OFF -> false
            WhisperScheduleMode.AUTO -> {
                val calendar = java.util.Calendar.getInstance()
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                hour >= 22 || hour < 7
            }
        }
    }

    fun cycleWhisperScheduleMode(): WhisperScheduleMode {
        whisperScheduleMode = when (whisperScheduleMode) {
            WhisperScheduleMode.AUTO -> WhisperScheduleMode.ALWAYS_ON
            WhisperScheduleMode.ALWAYS_ON -> WhisperScheduleMode.OFF
            WhisperScheduleMode.OFF -> WhisperScheduleMode.AUTO
        }
        return whisperScheduleMode
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var cachedDuckFocusRequest: android.media.AudioFocusRequest? = null

    fun requestDuckAudioFocus() {
        if (!isAudioDuckingEnabled || audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .build()
                cachedDuckFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ACCESSIBILITY, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestDuckAudioFocus error: ${e.message}")
        }
    }

    fun abandonDuckAudioFocus() {
        if (audioManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .build()
                audioManager.abandonAudioFocusRequest(cachedDuckFocusRequest ?: focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "abandonDuckAudioFocus error: ${e.message}")
        }
    }

    fun playSpatialNodeSound(bounds: android.graphics.Rect, screenWidth: Int, screenHeight: Int) {
        if (!isSpatialSoundstageEnabled) {
            playFocusMove()
            return
        }

        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        val normX = if (screenWidth > 0) (centerX.toFloat() / screenWidth.toFloat()).coerceIn(0.0f, 1.0f) else 0.5f
        val normY = if (screenHeight > 0) (centerY.toFloat() / screenHeight.toFloat()).coerceIn(0.0f, 1.0f) else 0.5f

        playFocusMovePanned(normX, normY)
    }

    fun playSonarPulse(distanceMeters: Float, pan: Float = 0f) {
        val leftVol = ((1.0f - pan) / 2.0f).coerceIn(0.1f, 1.0f)
        val rightVol = ((1.0f + pan) / 2.0f).coerceIn(0.1f, 1.0f)
        val rate = when {
            distanceMeters < 0.8f -> 1.6f
            distanceMeters < 1.5f -> 1.3f
            distanceMeters < 2.5f -> 1.0f
            else -> 0.8f
        }
        soundPool?.play(soundFocusId, leftVol, rightVol, 1, 0, rate)
    }

    fun release() {
        abandonDuckAudioFocus()
        soundPool?.release()
        soundPool = null
        toneGenerator?.release()
        toneGenerator = null
        try {
            chimeExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "chimeExecutor shutdown error: ${e.message}")
        }
    }
}
