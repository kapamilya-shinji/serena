package com.kapamilya.serena

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class GranularityMode(val resId: Int, val displayName: String) {
    CHARACTERS(R.string.granularity_characters, "文字"),
    WORDS(R.string.granularity_words, "単語"),
    LINES(R.string.granularity_lines, "行"),
    PARAGRAPHS(R.string.granularity_paragraphs, "段落"),
    HEADINGS(R.string.granularity_headings, "見出し"),
    CONTROLS(R.string.granularity_controls, "コントロール"),
    LINKS(R.string.granularity_links, "リンク"),
    ACTIONS(R.string.granularity_actions, "アクション"),
    DEFAULT(R.string.granularity_default, "デフォルト");

    fun getDisplayName(context: Context): String = try {
        context.getString(resId)
    } catch (_: Exception) {
        displayName
    }
}

class SerenaScreenReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SerenaScreenReader"
        const val PREFS_NAME = "serena_prefs"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_SPEECH_PITCH = "speech_pitch"
        const val KEY_CALL_PERIODIC_ANNOUNCE = "call_periodic_announce"
        const val KEY_HOURLY_CHIME_ENABLED = "hourly_chime_enabled"
        const val KEY_HOURLY_CHIME_HALF_HOUR = "hourly_chime_half_hour"
        const val KEY_SMART_BATTERY_80 = "smart_battery_80_enabled"
        const val KEY_SMART_BATTERY_LOW = "smart_battery_low_enabled"
        const val KEY_SMART_BATTERY_STEPS = "smart_battery_steps_enabled"
        const val KEY_SMART_BATTERY_ESTIMATE = "smart_battery_estimate_enabled"
        const val KEY_FINGERPRINT_GUIDANCE_ENABLED = "fingerprint_guidance_enabled"
        const val KEY_SHAKE_THRESHOLD = "shake_threshold"
        const val KEY_TALKBACK_MODE = "key_talkback_mode"
        const val KEY_CHIME_STYLE = "key_chime_style"
        const val KEY_ANNOUNCE_OPERATION_ACTIONS = "announce_operation_actions"
        const val KEY_SPEAK_USAGE_HINTS = "speak_usage_hints"
        const val CHIME_STYLE_NHK = "nhk_radio"
        const val CHIME_STYLE_CUTE = "cute_beep"
        const val CHIME_STYLE_BELL = "japanese_bell"

        var instance: SerenaScreenReaderService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
        fun isOperationActionsAnnounceEnabled(context: Context): Boolean {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return p.getBoolean(KEY_ANNOUNCE_OPERATION_ACTIONS, false)
        }
        fun isUsageHintsEnabled(context: Context): Boolean {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return p.getBoolean(KEY_SPEAK_USAGE_HINTS, true)
        }
    }

    var focusNavigator: com.kapamilya.serena.navigation.SerenaFocusNavigator? = null
    var contextualActionHintHelper: com.kapamilya.serena.navigation.ContextualActionHintHelper? = null
    var callManager: com.kapamilya.serena.telephony.SerenaCallManager? = null
    var lastHoveredNode: AccessibilityNodeInfo? = null
    var gestureDispatcher: com.kapamilya.serena.gesture.SerenaGestureDispatcher? = null
    var activeMenuDialog: serenaMenuDialog? = null

    private var tts: TextToSpeech? = null
    var isTtsReady = false
    fun isSpeaking(): Boolean = try { tts?.isSpeaking == true } catch (_: Exception) { false }
    private var lastSpokenText: String? = null
    private var lastSpokenTime: Long = 0L
    var lastFocusTimeMs: Long = 0L
    private lateinit var prefs: SharedPreferences
    var soundHelper: SoundAndHapticHelper? = null
    private var timeTickReceiver: BroadcastReceiver? = null
    private var currentGranularity: GranularityMode = GranularityMode.DEFAULT
    var screenCurtainHelper: ScreenCurtainHelper? = null
    var statusHelper: StatusAnnouncementHelper? = null
    var ocrHelper: OcrCameraHelper? = null
    var clipboardHelper: ClipboardHistoryHelper? = null
    var notificationFilterHelper: SmartNotificationFilterHelper? = null
    var appProfileHelper: AppProfileHelper? = null
    var emojiHelper: EmojiAndKaomojiHelper? = EmojiAndKaomojiHelper()
    var faceHelper: FaceDetectionHelper? = null
    var objectHelper: ObjectRecognitionHelper? = null
    var assistantHelper: SerenaAiAssistantHelper? = null
    var wifiConnectivityHelper: WifiConnectivityHelper? = null
    var batteryHelper: BatteryStateHelper? = null
    var colorAndLightHelper: ColorAndLightHelper? = null
    var compassHelper: SpatialCompassHelper? = null
    var spatialObstacleSonarHelper: SpatialObstacleSonarHelper? = null
    var walkingNavigator: com.kapamilya.serena.navigation.SerenaWalkingNavigator? = null
    var osmValhallaNavHelper: com.kapamilya.serena.location.OsmValhallaNavigationHelper? = null
    var spatialSurroundingRadarHelper: com.kapamilya.serena.location.SpatialSurroundingRadarHelper? = null
    var streetIntersectionNavigator: com.kapamilya.serena.location.StreetIntersectionNavigator? = null
    var sentinelEyesManager: com.kapamilya.serena.ai.SerenaSentinelEyesManager? = null
    private var shakeDetectorHelper: ShakeDetectorHelper? = null
    private var spatialHapticTouchMapHelper: SpatialHapticTouchMapHelper? = null
    var fingerprintGuidanceHelper: FingerprintGuidanceHelper? = null
    private var isLiveEnvironmentModeActive = false
    private var liveEnvironmentHandler: android.os.Handler? = null
    private var liveEnvironmentRunnable: Runnable? = null

    var aiAutoLabelHelper: AiAutoLabelHelper? = null
    var morningSummaryHelper: MorningSummaryHelper? = null
    var brailleController: com.kapamilya.serena.braille.BrailleDisplayController? = null
    var instantTranslationHelper: com.kapamilya.serena.translation.InstantTranslationHelper? = null
    var soundRecognitionHelper: com.kapamilya.serena.sound.SoundRecognitionHapticsHelper? = null
    var visualAudioDescriptionHelper: com.kapamilya.serena.ai.VisualAudioDescriptionHelper? = null
    var smartScreenSummaryEngine: com.kapamilya.serena.ai.SmartScreenSummaryEngine? = null
    var omniScreenDeductionEngine: com.kapamilya.serena.ai.KapamilyaOmniScreenDeductionEngine? = null
    var nextGenAiSuite: com.kapamilya.serena.ai.KapamilyaNextGenAiSuite? = null
    var fivePillarAiEngine: com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine? = null
    var dailyLifeHub: com.kapamilya.serena.ai.KapamilyaDailyLifeIntelligenceHub? = null
    var kapamilyaVoiceBridge: com.kapamilya.serena.speech.KapamilyaVoiceBridge? = null
    val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 通話時間計測用
    var isCallActive = false
    private var activeCallApp: String = ""
    private var callStartTimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        val safeContext = getSafeContext()

        // 1. 最重要コアコンポーネント（絶対に停止・欠落させてはならない基本機構）の初期化
        try {
            prefs = safeContext.getSafeSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing prefs fallback: ${e.message}")
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        try {
            soundHelper = SoundAndHapticHelper(safeContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing soundHelper: ${e.message}")
        }

        try {
            focusNavigator = com.kapamilya.serena.navigation.SerenaFocusNavigator(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing focusNavigator: ${e.message}")
        }

        try {
            gestureDispatcher = com.kapamilya.serena.gesture.SerenaGestureDispatcher(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing gestureDispatcher: ${e.message}")
        }

        try {
            com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.init(safeContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SerenaFullKanjiDetailDictionary: ${e.message}")
        }

        // serenaオリジナルモードを正統デフォルトとして初期化
        try {
            if (!prefs.contains(KEY_TALKBACK_MODE)) {
                prefs.edit().putBoolean(KEY_TALKBACK_MODE, false).apply()
            }
        } catch (_: Exception) {}

        // 2. 独立した拡張アシスタント・AI・センサー機能群の安全な隔離初期化
        safeInitHelper("screenCurtain") { screenCurtainHelper = ScreenCurtainHelper(safeContext) }
        safeInitHelper("statusHelper") { statusHelper = StatusAnnouncementHelper(safeContext) }
        safeInitHelper("ocrHelper") { ocrHelper = OcrCameraHelper(this) }
        safeInitHelper("clipboardHelper") { clipboardHelper = ClipboardHistoryHelper(safeContext) }
        safeInitHelper("notificationFilter") { notificationFilterHelper = SmartNotificationFilterHelper(safeContext) }
        safeInitHelper("appProfile") { appProfileHelper = AppProfileHelper() }
        safeInitHelper("faceHelper") { faceHelper = FaceDetectionHelper(this) }
        safeInitHelper("objectHelper") { objectHelper = ObjectRecognitionHelper(this) }
        safeInitHelper("assistantHelper") { assistantHelper = SerenaAiAssistantHelper(this) }
        safeInitHelper("colorAndLight") { colorAndLightHelper = ColorAndLightHelper(safeContext) }
        safeInitHelper("compassHelper") { compassHelper = SpatialCompassHelper(safeContext).apply { try { startListening() } catch (_: Exception) {} } }
        safeInitHelper("spatialObstacleSonar") { spatialObstacleSonarHelper = SpatialObstacleSonarHelper(safeContext) }
        safeInitHelper("walkingNavigator") { walkingNavigator = com.kapamilya.serena.navigation.SerenaWalkingNavigator(this, compassHelper).apply { try { startTracking() } catch (_: Exception) {} } }
        safeInitHelper("osmValhallaNav") {
            osmValhallaNavHelper = com.kapamilya.serena.location.OsmValhallaNavigationHelper(this, soundHelper) { msg ->
                speak(msg, TextToSpeech.QUEUE_FLUSH)
            }
        }
        safeInitHelper("spatialSurroundingRadar") {
            spatialSurroundingRadarHelper = com.kapamilya.serena.location.SpatialSurroundingRadarHelper(this, soundHelper) { msg ->
                speak(msg, TextToSpeech.QUEUE_FLUSH)
            }
        }
        safeInitHelper("streetIntersectionNav") {
            streetIntersectionNavigator = com.kapamilya.serena.location.StreetIntersectionNavigator(this, soundHelper, com.kapamilya.serena.location.LocationAddressHelper(safeContext)) { msg ->
                speak(msg, TextToSpeech.QUEUE_FLUSH)
            }
        }
        safeInitHelper("sentinelEyes") {
            sentinelEyesManager = com.kapamilya.serena.ai.SerenaSentinelEyesManager(this) { msg ->
                speak(msg, TextToSpeech.QUEUE_FLUSH)
            }
        }
        safeInitHelper("wifiConnectivity") { wifiConnectivityHelper = WifiConnectivityHelper(this).apply { try { startMonitoring() } catch (_: Exception) {} } }
        safeInitHelper("batteryHelper") { batteryHelper = BatteryStateHelper(this).apply { try { start() } catch (_: Exception) {} } }
        safeInitHelper("aiAutoLabel") { aiAutoLabelHelper = AiAutoLabelHelper(safeContext) }
        safeInitHelper("morningSummary") { morningSummaryHelper = MorningSummaryHelper(this) }
        safeInitHelper("instantTranslation") { instantTranslationHelper = com.kapamilya.serena.translation.InstantTranslationHelper(safeContext) }
        safeInitHelper("soundRecognition") { soundRecognitionHelper = com.kapamilya.serena.sound.SoundRecognitionHapticsHelper(safeContext) }
        safeInitHelper("visualAudioDescription") { visualAudioDescriptionHelper = com.kapamilya.serena.ai.VisualAudioDescriptionHelper(safeContext) }
        safeInitHelper("smartScreenSummary") { smartScreenSummaryEngine = com.kapamilya.serena.ai.SmartScreenSummaryEngine(this) }
        safeInitHelper("omniScreenDeduction") { omniScreenDeductionEngine = com.kapamilya.serena.ai.KapamilyaOmniScreenDeductionEngine(this) }
        safeInitHelper("nextGenAiSuite") { nextGenAiSuite = com.kapamilya.serena.ai.KapamilyaNextGenAiSuite(this) }
        safeInitHelper("fivePillarAi") { fivePillarAiEngine = com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine(this) }
        safeInitHelper("dailyLifeHub") { dailyLifeHub = com.kapamilya.serena.ai.KapamilyaDailyLifeIntelligenceHub(this) }
        safeInitHelper("kapamilyaVoiceBridge") { kapamilyaVoiceBridge = com.kapamilya.serena.speech.KapamilyaVoiceBridge(safeContext, prefs) }
        safeInitHelper("contextualActionHint") { contextualActionHintHelper = com.kapamilya.serena.navigation.ContextualActionHintHelper(this) }
        safeInitHelper("spatialHapticTouchMap") {
            soundHelper?.let {
                spatialHapticTouchMapHelper = SpatialHapticTouchMapHelper(it)
            }
        }
        safeInitHelper("fingerprintGuidance") {
            fingerprintGuidanceHelper = FingerprintGuidanceHelper(this, soundHelper)
        }
        safeInitHelper("callManager") {
            callManager = com.kapamilya.serena.telephony.SerenaCallManager(this, com.kapamilya.serena.speech.SerenaSpeechEngine(this))
        }
        safeInitHelper("shakeDetector") {
            shakeDetectorHelper = ShakeDetectorHelper(safeContext) {
                announceFullStatus()
            }.apply { try { start() } catch (_: Exception) {} }
        }

        // 点字ディスプレイ（Braille Display）コントローラー初期化
        safeInitHelper("brailleController") {
            brailleController = com.kapamilya.serena.braille.BrailleDisplayController(this, object : com.kapamilya.serena.braille.BrailleDisplayController.BrailleInteractionListener {
                override fun onBrailleCommand(command: com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand, routingIndex: Int) {
                    when (command) {
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.NAVIGATE_NEXT -> navigateLinearFocus(forward = true)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.NAVIGATE_PREVIOUS -> navigateLinearFocus(forward = false)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.NAVIGATE_UP -> cycleGranularity(forward = false)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.NAVIGATE_DOWN -> cycleGranularity(forward = true)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.PERFORM_CLICK -> performActivateCurrentFocus()
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.PERFORM_LONG_CLICK -> {
                            val node = getAccessibilityFocusedNode() ?: lastHoveredNode
                            node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
                            soundHelper?.playClick()
                        }
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.ACTION_MENU -> showNormalSerenaMenu()
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.SCROLL_FORWARD -> scrollVerticalForward()
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.SCROLL_BACKWARD -> scrollVerticalBackward()
                        com.kapamilya.serena.braille.BrailleProtocolHandler.BrailleCommand.ROUTING_CLICK -> performActivateCurrentFocus()
                        else -> {}
                    }
                }

                override fun onBrailleConnectionStateChanged(connected: Boolean, deviceName: String) {
                    if (connected) {
                        soundHelper?.playActionDone()
                        speak(getString(R.string.braille_connected, deviceName), TextToSpeech.QUEUE_FLUSH)
                    } else {
                        soundHelper?.playScroll()
                        if (deviceName.isNotEmpty()) {
                            speak(getString(R.string.braille_disconnected, deviceName), TextToSpeech.QUEUE_FLUSH)
                        }
                    }
                }

                override fun onBrailleKeyInput(dots: Int) {
                    soundHelper?.playClick()
                }
            }).apply {
                try {
                    connectToPairedBrailleDevice()
                } catch (_: Exception) {}
            }
        }

        initTts(this)
        registerTimeTickReceiver()
        Log.i(TAG, "serena ScreenReaderService created with fail-safe direct boot resilience.")
    }

    private inline fun safeInitHelper(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing helper [$name]: ${e.message}")
        }
    }

    private val pendingSpeechQueue = mutableListOf<Pair<String, Int>>()

    private var ttsInitRetryCount = 0

    private fun initTts(context: Context = this, forceDefaultEngine: Boolean = false) {
        try {
            val safeCtx = context.getSafeContext()
            val isUnlocked = SafeContextUtils.isUserUnlocked(this)
            if (kapamilyaVoiceBridge == null) {
                kapamilyaVoiceBridge = com.kapamilya.serena.speech.KapamilyaVoiceBridge(safeCtx, prefs)
            }
            tts?.shutdown()
            isTtsReady = false
            val preferredEngine = if (!isUnlocked || forceDefaultEngine) {
                null
            } else {
                try {
                    android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH)
                } catch (_: Exception) { null }
            }
            Log.i(TAG, "Initializing TTS (isUnlocked=$isUnlocked, forceDefault=$forceDefaultEngine, preferredEngine=$preferredEngine)")
            tts = if (!preferredEngine.isNullOrEmpty()) {
                TextToSpeech(safeCtx, this, preferredEngine)
            } else {
                TextToSpeech(safeCtx, this)
            }
            // Direct Boot 時やサードパーティTTS無応答時のウォッチドッグ自動リカバリ
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isTtsReady && ttsInitRetryCount < 4) {
                    ttsInitRetryCount++
                    Log.w(TAG, "TTS watchdog triggered (retry=$ttsInitRetryCount). Retrying with system default engine.")
                    initTts(safeCtx, forceDefaultEngine = true)
                }
            }, 2500L)
        } catch (e: Exception) {
            Log.e(TAG, "initTts error: ${e.message}")
        }
    }

    private var hasPlayedStartupChime = false
    private var hasSpokenStartupGreeting = false
    private var hasSpokenShutdownGreeting = false
    private var isStartupGreetingSpeaking = false
    private var wasKeyguardLocked = true

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                ttsInitRetryCount = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttributes)
                }
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId == "serena_startup_greeting") {
                            isStartupGreetingSpeaking = true
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "serena_startup_greeting") {
                            isStartupGreetingSpeaking = false
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                flushPendingSpeechQueue()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "serena_startup_greeting") {
                            isStartupGreetingSpeaking = false
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                flushPendingSpeechQueue()
                            }
                        }
                    }
                })

                val defaultLocale = Locale.getDefault()
                val result = tts?.setLanguage(defaultLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val fallback = Locale.ENGLISH
                    val fbResult = tts?.setLanguage(fallback)
                    if (fbResult == TextToSpeech.LANG_MISSING_DATA || fbResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.JAPANESE
                    }
                }
                updateTtsSettings()
                isTtsReady = true
                speakStartupGreeting()
            } catch (e: Exception) {
                Log.e(TAG, "onInit configuration error: ${e.message}")
                isTtsReady = true
            }
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isTtsReady) initTts(getSafeContext(), forceDefaultEngine = true)
            }, 1800)
        }
    }

    private fun speakStartupGreeting() {
        if (!hasPlayedStartupChime) {
            hasPlayedStartupChime = true
            soundHelper?.playSerenaStartupChime()
        }
        if (!hasSpokenStartupGreeting && isTtsReady) {
            hasSpokenStartupGreeting = true
            isStartupGreetingSpeaking = true
            val welcomeMsg = "Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!"
            try {
                val filPh = Locale.forLanguageTag("fil-PH")
                val tlPh = Locale.forLanguageTag("tl-PH")
                val hasFil = (tts?.isLanguageAvailable(filPh) ?: -2) >= 0
                val hasTl = (tts?.isLanguageAvailable(tlPh) ?: -2) >= 0
                tts?.language = when {
                    hasFil -> filPh
                    hasTl -> tlPh
                    else -> detectLanguage(welcomeMsg)
                }
                val spokenWelcome = if (hasFil || hasTl) {
                    welcomeMsg
                } else {
                    welcomeMsg.replace("Kapamilya", "Kah-pah-meel-yah")
                }
                tts?.speak(spokenWelcome, TextToSpeech.QUEUE_FLUSH, null, "serena_startup_greeting")
            } catch (_: Exception) {
                tts?.speak(welcomeMsg, TextToSpeech.QUEUE_FLUSH, null, "serena_startup_greeting")
            }
            Log.i(TAG, "TTS initialized successfully. Spoke Tagalog startup greeting.")
            // フェイルセーフ（最悪の場合でも5.5秒後にフラグを解放）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isStartupGreetingSpeaking) {
                    isStartupGreetingSpeaking = false
                    flushPendingSpeechQueue()
                }
            }, 5500)
        } else {
            flushPendingSpeechQueue()
        }
    }

    /**
     * Direct Boot (LOCKED_BOOT_COMPLETED) または BOOT_COMPLETED 受信時の強制覚醒＆キーガード突破
     */
    fun onDirectBootOrUnlockBroadcast(action: String) {
        Log.i(TAG, "onDirectBootOrUnlockBroadcast received: $action")
        if (!isTtsReady || tts == null) {
            initTts(getSafeContext(), forceDefaultEngine = !SafeContextUtils.isUserUnlocked(this))
        } else if (!hasSpokenStartupGreeting) {
            speakStartupGreeting()
        }
        triggerDirectBootKeyguardBreakthrough()
    }

    /**
     * 初回再起動時（Direct Boot / キーガードロック中）にスワイプロック画面を自動突破して
     * PIN入力パッド（Bouncer）を展開＆自動フォーカスする多段リトライ機構
     */
    private fun triggerDirectBootKeyguardBreakthrough() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = longArrayOf(900L, 2400L, 4500L, 7000L)
        for ((idx, delayMs) in delays.withIndex()) {
            mainHandler.postDelayed({
                try {
                    if (isKeyguardLocked()) {
                        val foundPin = autoFocusPinKeypadIfPresent(force = (idx == 0))
                        if (!foundPin && idx <= 1) {
                            Log.i(TAG, "DirectBoot Keyguard Breakthrough: PIN pad not visible yet (attempt $idx), swiping up bouncer.")
                            unlockKeyguardOrShowBouncer()
                        }
                    } else if (idx == 0) {
                        navigateLinearFocus(forward = true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Keyguard breakthrough attempt $idx error: ${e.message}")
                }
            }, delayMs)
        }
    }

    private fun flushPendingSpeechQueue() {
        val itemsToSpeak: List<Pair<String, Int>> = synchronized(pendingSpeechQueue) {
            val copy = ArrayList(pendingSpeechQueue)
            pendingSpeechQueue.clear()
            copy
        }
        for (item in itemsToSpeak) {
            speak(item.first, item.second)
        }
    }

    fun updateTtsSettings() {
        val rate = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        val pitch = prefs.getFloat(KEY_SPEECH_PITCH, 1.0f)
        tts?.setSpeechRate(rate)
        tts?.setPitch(pitch)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            info.notificationTimeout = 0
            var flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
            try {
                info.flags = flags
                serviceInfo = info
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply full flags, falling back: ${e.message}")
                try {
                    info.flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    serviceInfo = info
                } catch (_: Exception) {}
            }
            Log.i(TAG, "serena AccessibilityService connected with full Interactive Windows flags=$flags.")

            hasSpokenShutdownGreeting = false
            speakStartupGreeting()

            // 再起動直後（Direct Boot）やサービス接続時の初期フォーカス自動捕捉＆キーガード自動突破
            triggerDirectBootKeyguardBreakthrough()

            // バックグラウンド自動アップデート定期チェック (10秒後)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAutoUpdateInBackground()
            }, 10000)
        } catch (t: Throwable) {
            Log.e(TAG, "Critical Shield: Error during onServiceConnected safely handled: ${t.message}", t)
        }
    }

    private fun checkAutoUpdateInBackground() {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(com.kapamilya.serena.update.AutoUpdateManager.KEY_LAST_CHECK_TIME, 0L)
        // 前回チェックから12時間以上経過しているか、初回の場合に実行
        if (now - lastCheck < 12 * 60 * 60 * 1000L) {
            return
        }
        prefs.edit().putLong(com.kapamilya.serena.update.AutoUpdateManager.KEY_LAST_CHECK_TIME, now).apply()

        com.kapamilya.serena.update.AutoUpdateManager.getInstance(this).checkForUpdate { info ->
            if (info != null && info.isUpdateAvailable) {
                val msg = getString(R.string.update_new_version_available_fmt, info.latestVersion)
                speak(msg, TextToSpeech.QUEUE_ADD)
            }
        }
    }

    @Volatile var isInternalGestureDispatching = false
    private var lastDispatchedGestureTimeMs = 0L

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        try {
            if (isInternalGestureDispatching) {
                Log.d(TAG, "onGesture ignored: internal gesture dispatch in progress.")
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastDispatchedGestureTimeMs < 150) return false
            lastDispatchedGestureTimeMs = now

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (fingerprintGuidanceHelper?.isGuidanceActive() == true) {
                    try {
                        val motionEvent = gestureEvent.motionEvents.lastOrNull()
                        if (motionEvent != null) {
                            fingerprintGuidanceHelper?.onTouchCoordinates(motionEvent.x, motionEvent.y)
                        }
                    } catch (_: Throwable) {}
                }
                val gestureId = gestureEvent.gestureId
                Log.i(TAG, "onGesture(AccessibilityGestureEvent) received: $gestureId")
                return gestureDispatcher?.onGesture(gestureId) ?: handleGestureId(gestureId)
            }
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "Critical Shield: Safe gesture guard caught in onGesture(event): ${t.message}", t)
            return false
        }
    }

    @Deprecated("Deprecated in API 30+")
    override fun onGesture(gestureId: Int): Boolean {
        try {
            if (isInternalGestureDispatching) {
                Log.d(TAG, "onGesture(Int) ignored: internal gesture dispatch in progress.")
                return false
            }
            Log.i(TAG, "onGesture(Int) received: $gestureId")
            return gestureDispatcher?.onGesture(gestureId) ?: handleGestureId(gestureId)
        } catch (t: Throwable) {
            Log.e(TAG, "Critical Shield: Safe gesture guard caught in onGesture(id): ${t.message}", t)
            return false
        }
    }

    private var lastUnlockTime = 0L

    private fun handleGestureId(gestureId: Int): Boolean {
        Log.i(TAG, "handleGestureId detected: $gestureId")
        AlphaTelemetryHelper.getInstance(this).incrementGestureCount()

        when (gestureId) {
            // 1本指右スワイプ: 次の項目へフォーカス移動 (リニア)
            GESTURE_SWIPE_RIGHT -> {
                soundHelper?.playFocusMove()
                navigateLinearFocus(forward = true)
                return true
            }
            // 1本指左スワイプ: 前の項目へフォーカス移動 (リニア)
            GESTURE_SWIPE_LEFT -> {
                soundHelper?.playFocusMove()
                navigateLinearFocus(forward = false)
                return true
            }
            // 1本指上スワイプ: 選択中の読み上げコントロール（行/単語/見出し等）に従って前へ移動
            GESTURE_SWIPE_UP -> {
                soundHelper?.playFocusMove()
                focusPrevious()
                return true
            }
            // 1本指下スワイプ: 選択中の読み上げコントロール（行/単語/見出し等）に従って次へ移動
            GESTURE_SWIPE_DOWN -> {
                soundHelper?.playFocusMove()
                focusNext()
                return true
            }
            // 1本指ダブルタップ: フォーカス中要素のクリック実行
            GESTURE_DOUBLE_TAP -> {
                soundHelper?.playClick()
                performClickOnFocusedNode()
                return true
            }
            // 1本指ダブルタップ＆ホールド (長押し / TalkBack互換 18): アクション・ショートカットメニュー表示
            18 -> {
                soundHelper?.playActionDone()
                showActionsMenu(getAccessibilityFocusedNode())
                return true
            }

            // 2本指シングルタップ (19): 読み上げの一時停止と再開
            19 -> {
                toggleSpeechPauseResume()
                return true
            }
            // 2本指ダブルタップ (20): 通話の応答と終了 / メディアの再生と一時停止
            20 -> {
                soundHelper?.playActionDone()
                handleMagicTapAction()
                return true
            }
            // 2本指トリプルタップ (21 / GESTURE_2_FINGER_TRIPLE_TAP): 音声読み上げの消音（ミュート）切替
            21 -> {
                toggleSpeechMute()
                return true
            }
            // 2本指左フリック (27) / 上フリック (25): 次のページへ（横スクロール進む ＋ 先頭アイテム音声ガイド）
            27, 25 -> {
                if (isKeyguardLocked()) {
                    unlockKeyguardOrShowBouncer()
                    return true
                }
                return scrollHorizontalForward()
            }
            // 2本指右フリック (28) / 下フリック (26): 前のページへ（横スクロール戻る ＋ 先頭アイテム音声ガイド）
            28, 26 -> {
                return scrollHorizontalBackward()
            }

            // 3本指シングルタップ (22 / GESTURE_3_FINGER_SINGLE_TAP): Serena メニューを開く！
            22 -> {
                soundHelper?.playMenuOpen()
                showNormalSerenaMenu()
                return true
            }
            // 3本指ダブルタップ (23): 全ステータスアナウンス
            23 -> {
                announceFullStatus()
                return true
            }
            // 3本指トリプルタップ (24): クリップボードにコピー
            24 -> {
                copyLastSpokenTextToClipboard()
                return true
            }
            // 3本指上フリック (29): 読み上げ粒度（コントロール）を前へ
            29 -> {
                soundHelper?.playActionDone()
                cycleGranularity(forward = false)
                return true
            }
            // 3本指下フリック (30): 読み上げ粒度（コントロール）を次へ
            30 -> {
                soundHelper?.playActionDone()
                cycleGranularity(forward = true)
                return true
            }
            // 3本指左フリック (31): Serena AI Voice アシスタントを即時起動！
            31 -> {
                soundHelper?.playActionDone()
                speak(getString(R.string.ai_voice_assistant_launching), TextToSpeech.QUEUE_FLUSH)
                launchAiAssistant()
                return true
            }
            // 3本指右フリック (32): リアルタイムカメラAI実況を即時起動！
            32 -> {
                launchRealtimeLiveSceneCommentary()
                return true
            }

            // TalkBack伝統 L字ジェスチャー: セレナメニュー
            GESTURE_SWIPE_DOWN_AND_RIGHT, GESTURE_SWIPE_UP_AND_RIGHT -> {
                soundHelper?.playMenuOpen()
                showNormalSerenaMenu()
                return true
            }
            // 下→左スワイプ: 戻る (セレナメニュー表示中ならメニューを即時閉じる)
            GESTURE_SWIPE_DOWN_AND_LEFT -> {
                val menu = activeMenuDialog
                if (menu != null && menu.isShowing) {
                    activeMenuDialog = null
                    try { menu.dismiss() } catch (_: Exception) {}
                    soundHelper?.playActionDone()
                    speak(getString(R.string.menu_closed), TextToSpeech.QUEUE_FLUSH)
                    return true
                }
                soundHelper?.playClick()
                speak(getString(R.string.gesture_back), TextToSpeech.QUEUE_FLUSH)
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }
            // 上→左スワイプ: ホーム画面 (セレナメニュー表示中ならメニューも閉じる)
            GESTURE_SWIPE_UP_AND_LEFT -> {
                val menu = activeMenuDialog
                if (menu != null && menu.isShowing) {
                    activeMenuDialog = null
                    try { menu.dismiss() } catch (_: Exception) {}
                }
                soundHelper?.playClick()
                speak(getString(R.string.gesture_home), TextToSpeech.QUEUE_FLUSH)
                performGlobalAction(GLOBAL_ACTION_HOME)
                return true
            }
            // 左→上スワイプ (43): 最近使ったアプリ
            GESTURE_SWIPE_LEFT_AND_UP, 43 -> {
                val menu = activeMenuDialog
                if (menu != null && menu.isShowing) {
                    activeMenuDialog = null
                    try { menu.dismiss() } catch (_: Exception) {}
                }
                soundHelper?.playClick()
                speak(getString(R.string.gesture_recents), TextToSpeech.QUEUE_FLUSH)
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return true
            }
            // 右→下スワイプ (44): クイック設定
            GESTURE_SWIPE_RIGHT_AND_DOWN, 44 -> {
                soundHelper?.playClick()
                speak(getString(R.string.gesture_quick_settings), TextToSpeech.QUEUE_FLUSH)
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                return true
            }
            // ダブルタップ長押し: 読み上げ停止
            GESTURE_DOUBLE_TAP_AND_HOLD -> {
                stopSpeech()
                return true
            }
        }
        return false
    }

    fun cycleGranularity(forward: Boolean) {
        val values = GranularityMode.values()
        val currentIndex = currentGranularity.ordinal
        val nextIndex = if (forward) {
            (currentIndex + 1) % values.size
        } else {
            if (currentIndex - 1 < 0) values.size - 1 else currentIndex - 1
        }
        currentGranularity = values[nextIndex]
        soundHelper?.playActionDone()
        val dirLabel = if (forward) getString(R.string.granularity_next_control) else getString(R.string.granularity_prev_control)
        speak("${dirLabel}: ${currentGranularity.getDisplayName(this)}", TextToSpeech.QUEUE_FLUSH)
    }

    fun copyLastSpokenTextToClipboard() {
        val text = lastSpokenText ?: ""
        if (text.isEmpty()) {
            speak(getString(R.string.clipboard_empty), TextToSpeech.QUEUE_FLUSH)
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Serena", text)
        cm?.setPrimaryClip(clip)
        soundHelper?.playActionDone()
        val analysis = clipboardHelper?.addClip(text) ?: getString(R.string.clipboard_copied)
        speak(analysis, TextToSpeech.QUEUE_FLUSH)
        fivePillarAiEngine?.sendOverBayanihanLinkIfActive("CLIPBOARD", text, announceSent = true)
    }

    fun scrollPageForward(onComplete: ((Boolean) -> Unit)? = null) {
        val horizontalNode = focusNavigator?.findHorizontalScrollableNode(forward = true)
        if (horizontalNode != null && focusNavigator?.performHorizontalScroll(horizontalNode, forward = true) == true) {
            soundHelper?.playScroll(isForward = true)
            if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.page_next), TextToSpeech.QUEUE_FLUSH)
            onComplete?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ it(true) }, 350) }
            return
        }

        val verticalNode = focusNavigator?.findScrollableNode(forward = true)
        if (verticalNode != null && focusNavigator?.performScroll(verticalNode, forward = true) == true) {
            soundHelper?.playScroll(isForward = true)
            onComplete?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ it(true) }, 300) }
            return
        }

        // スクロール可能な要素が存在しない・スクロール上限に達した場合は端音を鳴らして停止
        soundHelper?.playLastItemEdgeSound()
        onComplete?.let { it(false) }
    }

    fun scrollPageBackward(onComplete: ((Boolean) -> Unit)? = null) {
        val horizontalNode = focusNavigator?.findHorizontalScrollableNode(forward = false)
        if (horizontalNode != null && focusNavigator?.performHorizontalScroll(horizontalNode, forward = false) == true) {
            soundHelper?.playScroll(isForward = false)
            if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.page_prev), TextToSpeech.QUEUE_FLUSH)
            onComplete?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ it(true) }, 350) }
            return
        }

        val verticalNode = focusNavigator?.findScrollableNode(forward = false)
        if (verticalNode != null && focusNavigator?.performScroll(verticalNode, forward = false) == true) {
            soundHelper?.playScroll(isForward = false)
            onComplete?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ it(true) }, 300) }
            return
        }

        // スクロール可能な要素が存在しない・スクロール下限に達した場合は端音を鳴らして停止
        soundHelper?.playFirstItemEdgeSound()
        onComplete?.let { it(false) }
    }

    private fun performSwipeGesture(swipeUp: Boolean, onComplete: (() -> Unit)? = null) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX1 = width * 0.40f
        val startX2 = width * 0.60f
        val startY = if (swipeUp) height * 0.60f else height * 0.40f
        val endY = if (swipeUp) height * 0.40f else height * 0.60f

        val path1 = android.graphics.Path().apply {
            moveTo(startX1, startY)
            lineTo(startX1, endY)
        }
        val path2 = android.graphics.Path().apply {
            moveTo(startX2, startY)
            lineTo(startX2, endY)
        }
        val stroke1 = android.accessibilityservice.GestureDescription.StrokeDescription(path1, 0, 200)
        val stroke2 = android.accessibilityservice.GestureDescription.StrokeDescription(path2, 0, 200)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCompleted(gestureDescription)
                soundHelper?.playFocusMove()
                if (onComplete != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, 200)
                } else {
                    val dirStr = if (swipeUp) getString(R.string.service_direction_next) else getString(R.string.service_direction_prev)
                    if (isOperationActionsAnnounceEnabled(this@SerenaScreenReaderService)) {
                        speak(getString(R.string.scrolled_vertical, dirStr), TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }
        }, null)
    }

    private fun performHorizontalSwipeGesture(swipeLeft: Boolean, onComplete: (() -> Unit)? = null) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = if (swipeLeft) width * 0.85f else width * 0.15f
        val endX = if (swipeLeft) width * 0.15f else width * 0.85f
        val startY = height * 0.5f

        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, startY)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        Log.i(TAG, "performHorizontalSwipeGesture: swipeLeft=$swipeLeft from ($startX, $startY) to ($endX, $startY)")
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "performHorizontalSwipeGesture: gesture completed successfully.")
                soundHelper?.playScroll(isForward = swipeLeft)
                if (onComplete != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, 350)
                } else {
                    val pageMsg = if (swipeLeft) getString(R.string.page_next) else getString(R.string.page_prev)
                    if (isOperationActionsAnnounceEnabled(this@SerenaScreenReaderService)) {
                        speak(pageMsg, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }

            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "performHorizontalSwipeGesture: gesture was CANCELLED by system.")
                onComplete?.invoke()
            }
        }, null)
    }

    fun getAccessibilityFocusedNode(): AccessibilityNodeInfo? {
        try {
            val topDialog = com.kapamilya.serena.navigation.SystemDialogHelper.findTopDialogWindow(this)
            val dialogFocus = topDialog?.second?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (dialogFocus != null) return dialogFocus
        } catch (_: Exception) {}

        val root = rootInActiveWindow
        val a11yFocus = root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (a11yFocus != null) return a11yFocus

        val inputFocus = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocus != null && (inputFocus.isEditable || inputFocus.isPassword)) {
            return inputFocus
        }
        return null
    }

    fun handleVerticalSwipe(up: Boolean): Boolean {
        cycleGranularity(forward = !up)
        return true
    }

    fun getCurrentGranularity(): GranularityMode = currentGranularity

    fun executeActiveCustomAction(): Boolean {
        val node = getAccessibilityFocusedNode() ?: return false
        val isLockscreen = isKeyguardLocked()
        val customActions = getAvailableCustomActions(node)

        // 0 は「デフォルト（通常のアクティベート / クリック）」
        if (selectedCustomActionIndex <= 0) {
            performClickOnFocusedNode()
            return true
        }

        val actionIdx = selectedCustomActionIndex - 1
        if (actionIdx in customActions.indices) {
            val actionItem = customActions[actionIdx]
            if (actionItem.isKeyguardUnlock) {
                return unlockKeyguardOrShowBouncer()
            }
            val success = node.performAction(actionItem.action.id)
            if (success) {
                soundHelper?.playActionDone()
                speak(getString(R.string.action_executed_format, actionItem.action.label ?: ""), TextToSpeech.QUEUE_FLUSH)
                return true
            }
        }
        return false
    }

    data class SerenaCustomAction(val action: AccessibilityNodeInfo.AccessibilityAction, val isKeyguardUnlock: Boolean = false)

    private fun getAvailableCustomActions(node: AccessibilityNodeInfo): List<SerenaCustomAction> {
        val list = mutableListOf<SerenaCustomAction>()
        if (isKeyguardLocked()) {
            val unlockAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                AccessibilityNodeInfo.AccessibilityAction(0x7F0A0001, getString(R.string.service_action_unlock))
            } else null
            if (unlockAction != null) {
                list.add(SerenaCustomAction(unlockAction, isKeyguardUnlock = true))
            }
        }
        val nodeActions = node.actionList.filter { 
            it.id != AccessibilityNodeInfo.ACTION_CLICK && 
            it.id != AccessibilityNodeInfo.ACTION_FOCUS && 
            it.id != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS &&
            !it.label.isNullOrEmpty()
        }
        for (a in nodeActions) {
            list.add(SerenaCustomAction(a, isKeyguardUnlock = false))
        }
        return list
    }
    private var selectedCustomActionIndex = 0
    private var charOffsetInFocusedNode = -1
    private var lastCharNavText = ""

    fun focusNext() {
        when (currentGranularity) {
            GranularityMode.ACTIONS -> navigateCustomActions(forward = true)
            GranularityMode.HEADINGS -> navigateFilteredFocus(forward = true) { it.isHeading || getNodeRole(it) == "見出し" }
            GranularityMode.CONTROLS -> navigateFilteredFocus(forward = true) { it.isClickable || it.isCheckable || it.isFocusable || it.rangeInfo != null }
            GranularityMode.LINKS -> navigateFilteredFocus(forward = true) { isLinkNode(it) }
            GranularityMode.LINES -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward = true)
            GranularityMode.PARAGRAPHS -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH, forward = true)
            GranularityMode.WORDS -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, forward = true)
            GranularityMode.CHARACTERS -> handleCharacterGranularityNext()
            GranularityMode.DEFAULT -> handleDefaultGranularityNext()
        }
    }

    fun focusPrevious() {
        when (currentGranularity) {
            GranularityMode.ACTIONS -> navigateCustomActions(forward = false)
            GranularityMode.HEADINGS -> navigateFilteredFocus(forward = false) { it.isHeading || getNodeRole(it) == "見出し" }
            GranularityMode.CONTROLS -> navigateFilteredFocus(forward = false) { it.isClickable || it.isCheckable || it.isFocusable || it.rangeInfo != null }
            GranularityMode.LINKS -> navigateFilteredFocus(forward = false) { isLinkNode(it) }
            GranularityMode.LINES -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE, forward = false)
            GranularityMode.PARAGRAPHS -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH, forward = false)
            GranularityMode.WORDS -> moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD, forward = false)
            GranularityMode.CHARACTERS -> handleCharacterGranularityPrevious()
            GranularityMode.DEFAULT -> handleDefaultGranularityPrevious()
        }
    }

    private fun handleCharacterGranularityNext() {
        try {
            val imeLang = getActiveImeSubtypeLocale().ifBlank { null }
            val menu = activeMenuDialog
            if (menu != null && menu.isShowing) {
                val menuText = menu.getCurrentItemText()
                if (menuText.isNotEmpty()) {
                    if (menuText != lastCharNavText) {
                        lastCharNavText = menuText
                        charOffsetInFocusedNode = -1
                    }
                    charOffsetInFocusedNode++
                    if (charOffsetInFocusedNode < menuText.length) {
                        val targetChar = menuText[charOffsetInFocusedNode]
                        val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(targetChar, imeLang)
                        soundHelper?.playFocusMove()
                        speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                        return
                    } else {
                        charOffsetInFocusedNode = menuText.length - 1
                        soundHelper?.playLastItemEdgeSound()
                        return
                    }
                }
            }

            val focused = getAccessibilityFocusedNode() ?: lastHoveredNode
            val text = if (focused != null) getNodeText(focused) else (lastSpokenText ?: "")
            if (text.isNotEmpty()) {
                if (text != lastCharNavText) {
                    lastCharNavText = text
                    charOffsetInFocusedNode = -1
                }
                charOffsetInFocusedNode++
                if (charOffsetInFocusedNode < text.length) {
                    val targetChar = text[charOffsetInFocusedNode]
                    val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(targetChar, imeLang)
                    soundHelper?.playFocusMove()
                    speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                    return
                } else {
                    charOffsetInFocusedNode = text.length - 1
                    soundHelper?.playLastItemEdgeSound()
                    return
                }
            }
            moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, forward = true)
        } catch (_: Exception) {
            navigateLinearFocus(forward = true)
        }
    }

    private fun handleCharacterGranularityPrevious() {
        try {
            val imeLang = getActiveImeSubtypeLocale().ifBlank { null }
            val menu = activeMenuDialog
            if (menu != null && menu.isShowing) {
                val menuText = menu.getCurrentItemText()
                if (menuText.isNotEmpty()) {
                    if (menuText != lastCharNavText) {
                        lastCharNavText = menuText
                        charOffsetInFocusedNode = menuText.length
                    }
                    charOffsetInFocusedNode--
                    if (charOffsetInFocusedNode >= 0 && charOffsetInFocusedNode < menuText.length) {
                        val targetChar = menuText[charOffsetInFocusedNode]
                        val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(targetChar, imeLang)
                        soundHelper?.playFocusMove()
                        speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                        return
                    } else {
                        charOffsetInFocusedNode = 0
                        soundHelper?.playFirstItemEdgeSound()
                        return
                    }
                }
            }

            val focused = getAccessibilityFocusedNode() ?: lastHoveredNode
            val text = if (focused != null) getNodeText(focused) else (lastSpokenText ?: "")
            if (text.isNotEmpty()) {
                if (text != lastCharNavText) {
                    lastCharNavText = text
                    charOffsetInFocusedNode = text.length
                }
                charOffsetInFocusedNode--
                if (charOffsetInFocusedNode >= 0 && charOffsetInFocusedNode < text.length) {
                    val targetChar = text[charOffsetInFocusedNode]
                    val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(targetChar, imeLang)
                    soundHelper?.playFocusMove()
                    speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                    return
                } else {
                    charOffsetInFocusedNode = 0
                    soundHelper?.playFirstItemEdgeSound()
                    return
                }
            }
            moveByGranularity(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER, forward = false)
        } catch (_: Exception) {
            navigateLinearFocus(forward = false)
        }
    }

    private fun adjustSliderNode(node: AccessibilityNodeInfo, increase: Boolean): Boolean {
        val target = findProgressOrRangeNode(node) ?: node
        val cls = target.className?.toString() ?: ""
        val isSlider = cls.contains("SeekBar", ignoreCase = true) ||
                cls.contains("Slider", ignoreCase = true) ||
                (target.rangeInfo != null && (target.isFocusable || target.isClickable ||
                        target.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                            it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                            it.id == android.R.id.accessibilityActionSetProgress
                        }))
        if (!isSlider) return false

        val scrollAction = if (increase) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var success = target.performAction(scrollAction)

        if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val r = target.rangeInfo
            if (r != null && (r.max - r.min) > 0f) {
                val step = (r.max - r.min) * 0.05f
                val nextVal = (if (increase) r.current + step else r.current - step).coerceIn(r.min, r.max)
                val args = Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, nextVal)
                }
                success = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
            }
        }

        if (success) {
            soundHelper?.playActionDone()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { target.refresh() } catch (_: Exception) {}
                val updatedRange = target.rangeInfo
                val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) target.stateDescription?.toString()?.trim() else null
                if (!stateDesc.isNullOrEmpty()) {
                    speak(stateDesc, TextToSpeech.QUEUE_FLUSH)
                } else if (updatedRange != null && (updatedRange.max - updatedRange.min) > 0f) {
                    val pct = ((updatedRange.current - updatedRange.min) * 100f / (updatedRange.max - updatedRange.min)).toInt().coerceIn(0, 100)
                    speak(getString(R.string.progress_percent, pct), TextToSpeech.QUEUE_FLUSH)
                }
            }, 60L)
            return true
        }
        return false
    }

    private fun handleDefaultGranularityNext() {
        try {
            val menu = activeMenuDialog
            if (menu != null && menu.isShowing) {
                handleCharacterGranularityNext()
                return
            }

            val focused = getAccessibilityFocusedNode() ?: lastHoveredNode
            if (focused == null) {
                navigateLinearFocus(forward = true)
                return
            }

            // 1. スライダー (SeekBar / Slider): 1本指下スワイプで減少
            if (adjustSliderNode(focused, increase = false)) {
                return
            }

            // 2. カスタムアクションを持つ要素
            val customActions = focused.actionList.filter {
                it.id != AccessibilityNodeInfo.ACTION_CLICK &&
                it.id != AccessibilityNodeInfo.ACTION_FOCUS &&
                it.id != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS &&
                !it.label.isNullOrEmpty()
            }
            if (customActions.isNotEmpty()) {
                navigateCustomActions(forward = true)
                return
            }

            // 3. 1文字ずつフォネティック詳細送り（日本語TTS強制）
            handleCharacterGranularityNext()
        } catch (e: Exception) {
            navigateLinearFocus(forward = true)
        }
    }

    private fun handleDefaultGranularityPrevious() {
        try {
            val menu = activeMenuDialog
            if (menu != null && menu.isShowing) {
                handleCharacterGranularityPrevious()
                return
            }

            val focused = getAccessibilityFocusedNode() ?: lastHoveredNode
            if (focused == null) {
                navigateLinearFocus(forward = false)
                return
            }

            // 1. スライダー (SeekBar / Slider): 1本指上スワイプで増加
            if (adjustSliderNode(focused, increase = true)) {
                return
            }

            // 2. カスタムアクションを持つ要素
            val customActions = focused.actionList.filter {
                it.id != AccessibilityNodeInfo.ACTION_CLICK &&
                it.id != AccessibilityNodeInfo.ACTION_FOCUS &&
                it.id != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS &&
                !it.label.isNullOrEmpty()
            }
            if (customActions.isNotEmpty()) {
                navigateCustomActions(forward = false)
                return
            }

            // 3. 1文字ずつフォネティック詳細戻し（日本語TTS強制）
            handleCharacterGranularityPrevious()
        } catch (e: Exception) {
            navigateLinearFocus(forward = false)
        }
    }

    private var lastScrollTime = 0L
    private var lastScrollEventTime = 0L

    fun findBestVisibleNodeAfterScroll(nodes: List<AccessibilityNodeInfo>, forward: Boolean, horizontal: Boolean): AccessibilityNodeInfo? {
        if (nodes.isEmpty()) return null
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val visibleNodes = nodes.filter { node ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val text = getNodeText(node)
            rect.width() > 0 && rect.height() > 0 &&
            rect.left >= 0 && rect.right <= screenW &&
            rect.top >= (screenH * 0.04f).toInt() && rect.bottom <= (screenH * 0.96f).toInt() &&
            !text.contains("最近の項目はありません", ignoreCase = true)
        }

        if (visibleNodes.isEmpty()) return null

        if (horizontal) {
            // ホーム画面/ランチャー等のページめくり時:
            // 画面上部〜中央のメインワークスペース内のアプリアイコン（ドック領域・ステータスバー等を除外）を最優先！
            val workspaceNodes = visibleNodes.filter { node ->
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                r.top >= (screenH * 0.08f).toInt() && r.bottom <= (screenH * 0.82f).toInt() &&
                (node.isClickable || node.isFocusable || !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty())
            }

            val candidates = if (workspaceNodes.isNotEmpty()) workspaceNodes else visibleNodes

            val comparator = Comparator<AccessibilityNodeInfo> { n1, n2 ->
                val r1 = android.graphics.Rect()
                val r2 = android.graphics.Rect()
                n1.getBoundsInScreen(r1)
                n2.getBoundsInScreen(r2)
                if (kotlin.math.abs(r1.top - r2.top) > 60) {
                    r1.top.compareTo(r2.top)
                } else {
                    r1.left.compareTo(r2.left)
                }
            }

            // 左右ページスクロール時: 遷移先ページの先頭項目（一番左上）を常に捕捉してフォーカス＆音声ガイド！
            return candidates.minWithOrNull(comparator)
        } else {
            // 縦スクロール時:
            // forward=true (下へスクロール/次へ進む): 画面上部の最初の項目へ！
            // forward=false (上へスクロール/前へ戻る): 画面下部の最後の項目へ！
            val comparator = Comparator<AccessibilityNodeInfo> { n1, n2 ->
                val r1 = android.graphics.Rect()
                val r2 = android.graphics.Rect()
                n1.getBoundsInScreen(r1)
                n2.getBoundsInScreen(r2)
                if (kotlin.math.abs(r1.top - r2.top) > 30) {
                    r1.top.compareTo(r2.top)
                } else {
                    r1.left.compareTo(r2.left)
                }
            }

            return if (forward) {
                visibleNodes.minWithOrNull(comparator)
            } else {
                visibleNodes.maxWithOrNull(comparator)
            }
        }
    }

    fun scrollHorizontalForward(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 300) return true
        lastScrollTime = now

        soundHelper?.playScroll(isForward = true)
        if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_page_next), TextToSpeech.QUEUE_FLUSH)

        val scrollNode = focusNavigator?.findHorizontalScrollableNode(forward = true)
        Log.i(TAG, "scrollHorizontalForward: scrollNode=${scrollNode?.viewIdResourceName} class=${scrollNode?.className}")

        val success = if (scrollNode != null) {
            focusNavigator?.performHorizontalScroll(scrollNode, forward = true) == true
        } else {
            false
        }

        if (success) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val newNodes = collectAccessibleNodes()
                val target = findBestVisibleNodeAfterScroll(newNodes, forward = true, horizontal = true)
                if (target != null) {
                    focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                    focusNavigator?.setFocusAndShowOnScreen(target)
                    announceNode(target, TextToSpeech.QUEUE_ADD)
                }
            }, 350)
            return true
        }

        // 物理2本指スワイプフォールバック (TalkBack完全互換: 水平スワイプを実行)
        performPhysical2FingerScroll(forward = true, horizontal = true)
        return true
    }

    fun scrollHorizontalBackward(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 300) return true
        lastScrollTime = now

        soundHelper?.playScroll(isForward = false)
        if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_page_prev), TextToSpeech.QUEUE_FLUSH)

        val scrollNode = focusNavigator?.findHorizontalScrollableNode(forward = false)
        Log.i(TAG, "scrollHorizontalBackward: scrollNode=${scrollNode?.viewIdResourceName} class=${scrollNode?.className}")

        val success = if (scrollNode != null) {
            focusNavigator?.performHorizontalScroll(scrollNode, forward = false) == true
        } else {
            false
        }

        if (success) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val newNodes = collectAccessibleNodes()
                val target = findBestVisibleNodeAfterScroll(newNodes, forward = false, horizontal = true)
                if (target != null) {
                    focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                    focusNavigator?.setFocusAndShowOnScreen(target)
                    announceNode(target, TextToSpeech.QUEUE_ADD)
                }
            }, 350)
            return true
        }

        // 物理2本指スワイプフォールバック (TalkBack完全互換: 水平スワイプを実行)
        performPhysical2FingerScroll(forward = false, horizontal = true)
        return true
    }

    fun scrollVerticalForward(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 300) return true
        lastScrollTime = now

        soundHelper?.playScroll(isForward = true)

        val activeRoot = rootInActiveWindow
        val pkg = activeRoot?.packageName?.toString()?.lowercase() ?: ""
        val isLauncher = pkg.contains("launcher")

        val scrollNode = focusNavigator?.findScrollableNode(forward = true)
        if (scrollNode != null && focusNavigator?.performScroll(scrollNode, forward = true) == true) {
            if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_scroll_down), TextToSpeech.QUEUE_FLUSH)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val newNodes = collectAccessibleNodes()
                val target = findBestVisibleNodeAfterScroll(newNodes, forward = true, horizontal = false)
                if (target != null) {
                    focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                    focusNavigator?.setFocusAndShowOnScreen(target)
                    announceNode(target, TextToSpeech.QUEUE_ADD)
                }
            }, 300)
            return true
        }

        // ホーム画面で下スクロール（2本指上スワイプ）された場合はアプリ一覧ドロワーを展開
        if (isLauncher && !isAllAppsOpen()) {
            speak(getString(R.string.action_open_apps), TextToSpeech.QUEUE_FLUSH)
            performOpenAllAppsGesture()
            return true
        }

        if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_scroll_down), TextToSpeech.QUEUE_FLUSH)
        performPhysical2FingerScroll(forward = true, horizontal = false)
        return true
    }

    fun scrollVerticalBackward(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < 300) return true
        lastScrollTime = now

        soundHelper?.playScroll(isForward = false)

        val activeRoot = rootInActiveWindow
        val pkg = activeRoot?.packageName?.toString()?.lowercase() ?: ""
        val isLauncher = pkg.contains("launcher")

        val scrollNode = focusNavigator?.findScrollableNode(forward = false)
        if (scrollNode != null && focusNavigator?.performScroll(scrollNode, forward = false) == true) {
            if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_scroll_up), TextToSpeech.QUEUE_FLUSH)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val newNodes = collectAccessibleNodes()
                val target = findBestVisibleNodeAfterScroll(newNodes, forward = false, horizontal = false)
                if (target != null) {
                    focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                    focusNavigator?.setFocusAndShowOnScreen(target)
                    announceNode(target, TextToSpeech.QUEUE_ADD)
                }
            }, 300)
            return true
        }

        // ホーム画面で上スクロール（2本指下スワイプ）された場合は通知シェードを展開
        if (isLauncher && !isAllAppsOpen()) {
            speak(getString(R.string.action_open_notifications), TextToSpeech.QUEUE_FLUSH)
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            return true
        }

        if (isOperationActionsAnnounceEnabled(this)) speak(getString(R.string.action_scroll_up), TextToSpeech.QUEUE_FLUSH)
        performPhysical2FingerScroll(forward = false, horizontal = false)
        return true
    }

    private fun isAllAppsOpen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val allApps = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.nexuslauncher:id/apps_list_view")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/apps_list_view") }
        return allApps.isNotEmpty()
    }

    private fun performOpenAllAppsGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (performGlobalAction(14 /* AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS */)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val newNodes = collectAccessibleNodes()
                    val target = newNodes.firstOrNull { it.isClickable || !it.text.isNullOrEmpty() }
                    if (target != null) {
                        focusNavigator?.setFocusAndShowOnScreen(target)
                        announceNode(target, TextToSpeech.QUEUE_ADD)
                    }
                }, 350)
                return
            }
        }

        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = android.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.90f)
            lineTo(w * 0.5f, h * 0.20f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        isInternalGestureDispatching = true
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCompleted(gestureDescription)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isInternalGestureDispatching = false
                    val newNodes = collectAccessibleNodes()
                    val target = newNodes.firstOrNull { it.isClickable || !it.text.isNullOrEmpty() }
                    if (target != null) {
                        focusNavigator?.setFocusAndShowOnScreen(target)
                        announceNode(target, TextToSpeech.QUEUE_ADD)
                    }
                }, 350)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCancelled(gestureDescription)
                isInternalGestureDispatching = false
            }
        }, null)
    }

    fun isKeyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km != null) {
            return km.isKeyguardLocked || km.isDeviceLocked
        }

        val currentWindows = try { windows } catch (_: Exception) { null }
        if (!currentWindows.isNullOrEmpty()) {
            val hasKeyguardWindow = currentWindows.any { it.type == 4 /* TYPE_KEYGUARD */ }
            if (hasKeyguardWindow) return true
        }

        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString()?.lowercase() ?: ""
        if (rootPkg.contains("keyguard")) {
            return true
        }
        return false
    }

    private var lastPinFocusTimeMs = 0L

    fun autoFocusPinKeypadIfPresent(force: Boolean = false): Boolean {
        // 端末がロック解除されている場合はPIN入力欄の自動フォーカスは絶対に実行しない（通知シェード・SystemUIの暴走完全防止）
        if (!isKeyguardLocked()) return false

        val now = System.currentTimeMillis()
        if (!force && now - lastPinFocusTimeMs < 1000L) return false

        val currentFocus = getAccessibilityFocusedNode()
        if (currentFocus != null && !force) {
            return false
        }

        val roots = focusNavigator?.getAllRoots() ?: listOfNotNull(rootInActiveWindow)
        if (roots.isEmpty()) return false

        // Google TalkBack AccessibilityFocusMonitor 準拠: PIN入力欄・PINエリア・PIN数字キーを最優先捕捉
        var pinInputField: AccessibilityNodeInfo? = null
        var pinMessageArea: AccessibilityNodeInfo? = null
        var firstPinDigitButton: AccessibilityNodeInfo? = null

        fun scanKeyguardNodes(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val className = node.className?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""

            // 1. 最優先: PIN/パスワード入力欄そのもの (PasswordTextView / pinEntry / passwordEntry / lockPassword / element:pin_code_field / EditText / isPassword)
            if (pinInputField == null) {
                val isPinOrPass = node.isPassword ||
                        className.contains("passwordtextview") ||
                        viewId.contains("pinentry") || viewId.contains("passwordentry") ||
                        viewId.contains("lockpassword") || viewId.contains("pin_entry") ||
                        viewId.contains("password_entry") || viewId.contains("pin_field") ||
                        viewId.contains("pin_code") || viewId.contains("pin_view") ||
                        viewId.contains("element:pin") ||
                        (desc.contains("PIN", ignoreCase = true) && (node.isFocusable || node.isClickable || node.isPassword)) ||
                        (className.contains("edittext") && (viewId.contains("pin") || viewId.contains("password") || viewId.contains("keyguard") || node.isPassword)) ||
                        (node.isEditable && (viewId.contains("pin") || viewId.contains("password") || viewId.contains("keyguard")))

                if (isPinOrPass) {
                    pinInputField = node
                    return
                }
            }

            // 2. 次点: PINメッセージエリア (keyguard_message_area / bouncer_message / element:bouncer)
            if (pinMessageArea == null) {
                if (viewId.contains("bouncer_message") || viewId.contains("element:bouncer_message") ||
                    desc.contains("PINを入力") || desc.contains("再起動後") || text.contains("PINを入力") || text.contains("再起動後") ||
                    (viewId.contains("keyguard_message_area") && (text.isNotEmpty() || desc.isNotEmpty()))
                ) {
                    pinMessageArea = node
                }
            }

            // 3. フォールバック: PIN数字キーパッド（「1」キー等）が既に表示されている場合
            if (firstPinDigitButton == null) {
                if (viewId.endsWith("key1") || viewId.contains("pin_key_1") || viewId.contains("digit_1") ||
                    ((text.trim() == "1" || desc.trim().startsWith("1")) && node.isClickable && isKeyboardOrPinKeyNode(node))
                ) {
                    firstPinDigitButton = node
                }
            }

            for (i in 0 until node.childCount) {
                scanKeyguardNodes(node.getChild(i))
                if (pinInputField != null) return
            }
        }

        for (r in roots) {
            scanKeyguardNodes(r)
            if (pinInputField != null) break
        }

        val target = pinInputField ?: pinMessageArea ?: firstPinDigitButton
        if (target != null) {
            lastPinFocusTimeMs = now
            val focused = target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            if (!focused) {
                try {
                    target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                } catch (_: Exception) {}
            }
            lastHoveredNode = target
            soundHelper?.playFocusMove()
            announceNode(target)
            Log.i(TAG, "autoFocusPinKeypadIfPresent: successfully focused PIN target ${target.viewIdResourceName}")
            return true
        }

        return false
    }

    fun isKeyboardOrPinKeyNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        // 1. PIN keypad buttons on Lock Screen (Compose element:pin_key_*, standard keyguard digit buttons)
        val isPinDigitId = viewId.contains("digit") || viewId.contains("pin_key") ||
                viewId.endsWith("key1") || viewId.endsWith("key2") || viewId.endsWith("key3") ||
                viewId.endsWith("key4") || viewId.endsWith("key5") || viewId.endsWith("key6") ||
                viewId.endsWith("key7") || viewId.endsWith("key8") || viewId.endsWith("key9") ||
                viewId.endsWith("key0") || viewId.contains("delete") || viewId.contains("cancel") ||
                viewId.contains("enter") || viewId.contains("ok") || viewId.contains("numpad") ||
                viewId.contains("klav")

        val isSingleCharOrDigit = (text.length == 1 && !text.all { it.isWhitespace() }) ||
                (desc.length == 1 && !desc.all { it.isWhitespace() })

        val isKeyguardKey = isKeyguardLocked() && (pkg.contains("systemui") || pkg.contains("keyguard")) &&
                (isPinDigitId || (node.isClickable && (isSingleCharOrDigit || desc.contains("削除") || desc.contains("決定") || desc.contains("確定"))))

        // 2. Soft Keyboard / IME keys (Gboard, Serena Keyboard, etc.)
        val isImeKey = (pkg.contains("inputmethod") || pkg.contains("latin") || pkg.contains("gboard") ||
                pkg.contains("keyboard") || className.contains("Key", ignoreCase = true) || className.contains("Keyboard", ignoreCase = true)) &&
                node.isClickable && (isSingleCharOrDigit || isPinDigitId || viewId.contains("key") ||
                desc.contains("削除") || desc.contains("スペース") || desc.contains("確定") || desc.contains("改行"))

        return isKeyguardKey || isImeKey
    }

    fun unlockKeyguardOrShowBouncer(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastUnlockTime < 500L) return false
        lastUnlockTime = now

        soundHelper?.playActionDone()
        speak(getString(R.string.action_unlocking), TextToSpeech.QUEUE_FLUSH)

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // 1. Google TalkBack 完全準拠: OS標準 Global Action による通知シェード消去
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Global action dismiss notification shade: ${e.message}")
        }

        // 2. ロック画面ルートノードおよびCompose element:lockscreenへの ACTION_DISMISS / ACTION_CLICK 発行
        val roots = focusNavigator?.getAllRoots() ?: listOfNotNull(rootInActiveWindow)
        fun dismissNodeRecursively(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)
                }
            }
            if (viewId.contains("lockscreen") || viewId.contains("lock_icon") || viewId.contains("keyguard") || viewId.contains("scene_container") || viewId.contains("scene_window")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            for (i in 0 until node.childCount) {
                dismissNodeRecursively(node.getChild(i))
            }
        }

        for (r in roots) {
            dismissNodeRecursively(r)
        }

        // 3. Google TalkBack 完全準拠の縦スワイプドラッグ（中央 50%, 85% -> 中央 50%, 12%, 240ms）
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val p = android.graphics.Path().apply {
            moveTo(width * 0.50f, height * 0.85f)
            lineTo(width * 0.50f, height * 0.12f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 240)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        isInternalGestureDispatching = true

        val dispatched = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCompleted(gestureDescription)
                isInternalGestureDispatching = false
                autoFocusPinKeypadIfPresent(force = true)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCancelled(gestureDescription)
                isInternalGestureDispatching = false
            }
        }, mainHandler)

        // 4. バウンサー展開後のPIN入力欄へのフォーカス
        mainHandler.postDelayed({ autoFocusPinKeypadIfPresent(force = false) }, 350)
        return dispatched
    }

    private fun performPhysical2FingerScroll(forward: Boolean, horizontal: Boolean) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.postDelayed({
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            val startX1: Float; val endX1: Float; val startY1: Float; val endY1: Float
            val startX2: Float; val endX2: Float; val startY2: Float; val endY2: Float

            if (horizontal) {
                if (forward) {
                    startX1 = width * 0.80f; endX1 = width * 0.20f; startY1 = height * 0.48f; endY1 = height * 0.48f
                    startX2 = width * 0.80f; endX2 = width * 0.20f; startY2 = height * 0.52f; endY2 = height * 0.52f
                } else {
                    startX1 = width * 0.20f; endX1 = width * 0.80f; startY1 = height * 0.48f; endY1 = height * 0.48f
                    startX2 = width * 0.20f; endX2 = width * 0.80f; startY2 = height * 0.52f; endY2 = height * 0.52f
                }
            } else {
                if (forward) {
                    startX1 = width * 0.45f; endX1 = width * 0.45f; startY1 = height * 0.65f; endY1 = height * 0.30f
                    startX2 = width * 0.55f; endX2 = width * 0.55f; startY2 = height * 0.65f; endY2 = height * 0.30f
                } else {
                    startX1 = width * 0.45f; endX1 = width * 0.45f; startY1 = height * 0.30f; endY1 = height * 0.65f
                    startX2 = width * 0.55f; endX2 = width * 0.55f; startY2 = height * 0.30f; endY2 = height * 0.65f
                }
            }

            val p1 = android.graphics.Path().apply { moveTo(startX1, startY1); lineTo(endX1, endY1) }
            val p2 = android.graphics.Path().apply { moveTo(startX2, startY2); lineTo(endX2, endY2) }
            val s1 = android.accessibilityservice.GestureDescription.StrokeDescription(p1, 0, 200)
            val s2 = android.accessibilityservice.GestureDescription.StrokeDescription(p2, 0, 200)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(s1)
                .addStroke(s2)
                .build()

            isInternalGestureDispatching = true
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    mainHandler.postDelayed({ isInternalGestureDispatching = false }, 350)
                    mainHandler.postDelayed({
                        val newNodes = collectAccessibleNodes()
                        val target = findBestVisibleNodeAfterScroll(newNodes, forward = forward, horizontal = horizontal)
                        if (target != null) {
                            focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                            focusNavigator?.setFocusAndShowOnScreen(target)
                            announceNode(target, TextToSpeech.QUEUE_ADD)
                        }
                    }, 250)
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    mainHandler.postDelayed({ isInternalGestureDispatching = false }, 350)
                    mainHandler.postDelayed({
                        val newNodes = collectAccessibleNodes()
                        val target = findBestVisibleNodeAfterScroll(newNodes, forward = forward, horizontal = horizontal)
                        if (target != null) {
                            focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(newNodes, target) ?: 0
                            focusNavigator?.setFocusAndShowOnScreen(target)
                            announceNode(target, TextToSpeech.QUEUE_ADD)
                        }
                    }, 250)
                }
            }, mainHandler)
        }, 40)
    }

    private fun navigateCustomActions(forward: Boolean) {
        val node = getAccessibilityFocusedNode()
        if (node == null) {
            speak(getString(R.string.action_no_focus), TextToSpeech.QUEUE_FLUSH)
            return
        }
        val customActions = getAvailableCustomActions(node)
        val totalCount = 1 + customActions.size
        val defaultMsg = run {
            val contextualHint = contextualActionHintHelper?.getActionHint(node, getNodeText(node), getNodeRole(node)) ?: ""
            if (contextualHint.isNotEmpty()) {
                getString(R.string.action_default_hint_fmt, contextualHint)
            } else {
                getString(R.string.action_default_double_tap)
            }
        }
        if (totalCount <= 1) {
            selectedCustomActionIndex = 0
            speak(defaultMsg, TextToSpeech.QUEUE_FLUSH)
            return
        }
        selectedCustomActionIndex = if (forward) {
            (selectedCustomActionIndex + 1) % totalCount
        } else {
            if (selectedCustomActionIndex - 1 < 0) totalCount - 1 else selectedCustomActionIndex - 1
        }
        soundHelper?.playFocusMove()
        if (selectedCustomActionIndex == 0) {
            speak(defaultMsg, TextToSpeech.QUEUE_FLUSH)
        } else {
            val actionItem = customActions[selectedCustomActionIndex - 1]
            speak(getString(R.string.action_custom_action_fmt, actionItem.action.label), TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun isLinkNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        if (className.contains("Link", ignoreCase = true) || className.contains("URL", ignoreCase = true)) return true
        val text = getNodeText(node)
        return text.startsWith("http://") || text.startsWith("https://") || text.startsWith("www.")
    }

    fun navigateLinearFocus(forward: Boolean) {
        val menu = activeMenuDialog
        if (menu != null && menu.isShowing) {
            if (forward) {
                menu.navigateMenuNext()
            } else {
                menu.navigateMenuPrev()
            }
            return
        }

        if (focusNavigator != null) {
            focusNavigator?.navigateLinearFocus(forward)
            return
        }

        val root = rootInActiveWindow ?: return
        val nodes = collectAccessibleNodes(root)
        if (nodes.isEmpty()) return

        val currentFocus = getAccessibilityFocusedNode()
        var currentIndex = -1
        if (currentFocus != null) {
            currentIndex = nodes.indexOfFirst { isSameNode(it, currentFocus) }
        }

        // 次へ進む場合で、現在のページの末尾に達した時
        if (forward && (currentIndex >= nodes.size - 1)) {
            val horizontalScrollNode = focusNavigator?.findHorizontalScrollableNode(forward = true)
            val verticalScrollNode = focusNavigator?.findScrollableNode(forward = true)
            
            var didScroll = false
            if (horizontalScrollNode != null && focusNavigator?.performHorizontalScroll(horizontalScrollNode, forward = true) == true) {
                didScroll = true
                soundHelper?.playScroll(isForward = true)
            } else if (verticalScrollNode != null && focusNavigator?.performScroll(verticalScrollNode, forward = true) == true) {
                didScroll = true
                soundHelper?.playScroll(isForward = true)
            }

            if (didScroll) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val newRoot = rootInActiveWindow ?: return@postDelayed
                    val newNodes = collectAccessibleNodes(newRoot)
                    if (newNodes.isNotEmpty()) {
                        val firstNode = newNodes.first()
                        firstNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        announceNode(firstNode)
                    }
                }, 250)
                return
            } else {
                soundHelper?.playLastItemEdgeSound()
                return
            }
        } else if (!forward && (currentIndex <= 0)) {
            val horizontalScrollNode = focusNavigator?.findHorizontalScrollableNode(forward = false)
            val verticalScrollNode = focusNavigator?.findScrollableNode(forward = false)
            
            var didScroll = false
            if (horizontalScrollNode != null && focusNavigator?.performHorizontalScroll(horizontalScrollNode, forward = false) == true) {
                didScroll = true
                soundHelper?.playScroll(isForward = false)
            } else if (verticalScrollNode != null && focusNavigator?.performScroll(verticalScrollNode, forward = false) == true) {
                didScroll = true
                soundHelper?.playScroll(isForward = false)
            }

            if (didScroll) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val newRoot = rootInActiveWindow ?: return@postDelayed
                    val newNodes = collectAccessibleNodes(newRoot)
                    if (newNodes.isNotEmpty()) {
                        val prevNode = newNodes.last()
                        prevNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        announceNode(prevNode)
                    }
                }, 250)
                return
            } else {
                soundHelper?.playFirstItemEdgeSound()
                return
            }
        }

        val targetIndex = if (forward) {
            (currentIndex + 1).coerceIn(0, nodes.size - 1)
        } else {
            (currentIndex - 1).coerceIn(0, nodes.size - 1)
        }

        val targetNode = nodes[targetIndex]
        currentFocus?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (success) {
            if (targetIndex == 0) {
                soundHelper?.playFirstItemEdgeSound()
            } else if (targetIndex == nodes.size - 1) {
                soundHelper?.playLastItemEdgeSound()
            }
            announceNode(targetNode)
        }
    }

    private fun navigateFilteredFocus(forward: Boolean, filter: (AccessibilityNodeInfo) -> Boolean) {
        val root = rootInActiveWindow ?: return
        val allNodes = collectAccessibleNodes(root)
        val filteredNodes = allNodes.filter { filter(it) }

        if (filteredNodes.isEmpty()) {
            speak(getString(R.string.action_not_found_fmt, currentGranularity.getDisplayName(this)), TextToSpeech.QUEUE_FLUSH)
            return
        }

        val currentFocus = getAccessibilityFocusedNode()
        var currentIndex = -1
        if (currentFocus != null) {
            currentIndex = filteredNodes.indexOfFirst { isSameNode(it, currentFocus) }
        }

        val targetIndex = if (forward) {
            if (currentIndex < 0 || currentIndex >= filteredNodes.size - 1) 0 else currentIndex + 1
        } else {
            if (currentIndex <= 0) filteredNodes.size - 1 else currentIndex - 1
        }

        val targetNode = filteredNodes[targetIndex]
        currentFocus?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (success) {
            speak("${currentGranularity.getDisplayName(this)}: ", TextToSpeech.QUEUE_FLUSH)
            announceNode(targetNode)
        }
    }

    private fun moveByGranularity(granularity: Int, forward: Boolean) {
        val focusNode = getAccessibilityFocusedNode() ?: return
        val action = if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
        }

        val success = focusNode.performAction(action, args)
        if (!success) {
            navigateLinearFocus(forward)
        }
    }

    private fun isSameNode(node1: AccessibilityNodeInfo, node2: AccessibilityNodeInfo): Boolean {
        if (node1 == node2) return true
        val b1 = android.graphics.Rect()
        val b2 = android.graphics.Rect()
        node1.getBoundsInScreen(b1)
        node2.getBoundsInScreen(b2)
        if (b1 != b2) return false
        val c1 = node1.className?.toString() ?: ""
        val c2 = node2.className?.toString() ?: ""
        val id1 = node1.viewIdResourceName ?: ""
        val id2 = node2.viewIdResourceName ?: ""
        return c1 == c2 && id1 == id2
    }

    private fun collectAccessibleNodes(root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val navNodes = focusNavigator?.collectAccessibleNodes(root)
        if (!navNodes.isNullOrEmpty()) return navNodes
        val targetRoot = root ?: rootInActiveWindow ?: return emptyList()
        return focusNavigator?.collectAccessibleNodes(targetRoot) ?: emptyList()
    }

    private fun performClickOnFocusedNode() {
        val menu = activeMenuDialog
        if (menu != null && menu.isShowing) {
            if (menu.performCurrentItemClick()) return
        }

        val focusedNode = getAccessibilityFocusedNode()
        if (focusedNode == null) {
            Log.w(TAG, "performClickOnFocusedNode: No focused node found.")
            return
        }
        nextGenAiSuite?.onBeforeUserAction(focusedNode, getNodeText(focusedNode))

        // 0. カスタムアクションモード時の選択アクション実行（インデックス0はデフォルトクリック）
        if (currentGranularity == GranularityMode.ACTIONS && selectedCustomActionIndex > 0) {
            val customActions = focusedNode.actionList.filter { 
                it.id != AccessibilityNodeInfo.ACTION_CLICK && 
                it.id != AccessibilityNodeInfo.ACTION_FOCUS && 
                it.id != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS &&
                !it.label.isNullOrEmpty()
            }
            val actionIdx = selectedCustomActionIndex - 1
            if (actionIdx in customActions.indices) {
                val action = customActions[actionIdx]
                if (focusedNode.performAction(action.id)) {
                    soundHelper?.playActionDone()
                    speak(getString(R.string.action_executed_fmt, action.label), TextToSpeech.QUEUE_FLUSH)
                    return
                }
            }
        }

        val isKeyguardElement = isKeyguardLocked() && (
                focusedNode.packageName?.contains("systemui") == true ||
                focusedNode.viewIdResourceName?.contains("key", ignoreCase = true) == true ||
                focusedNode.viewIdResourceName?.contains("pin", ignoreCase = true) == true ||
                focusedNode.viewIdResourceName?.contains("digit", ignoreCase = true) == true ||
                focusedNode.viewIdResourceName?.contains("delete", ignoreCase = true) == true ||
                focusedNode.viewIdResourceName?.contains("enter", ignoreCase = true) == true
        )

        // 1. 直近ノード（特にPINキーやSystemUI要素）へのダイレクトクリック試行
        if (focusedNode.isClickable || focusedNode.isCheckable || isKeyguardElement) {
            val text = getNodeText(focusedNode)
            val logicalSuccess = focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            // PINキーやSystemUI要素、あるいは物理タッチが必要な要素には同時に物理ジェスチャータップを発射
            if (isKeyguardElement) {
                clickNodeByGesture(focusedNode)
                if (text.isNotEmpty()) speak(text, TextToSpeech.QUEUE_FLUSH)
                return
            }

            if (logicalSuccess) {
                if (isOperationActionsAnnounceEnabled(this) && text.isNotEmpty()) {
                    speak(getString(R.string.action_execute_fmt, text), TextToSpeech.QUEUE_FLUSH)
                }
                return
            }
        }

        // 2. 直近ノードでクリック不可の場合のみ、親階層を探索
        var target: AccessibilityNodeInfo? = focusedNode.parent
        while (target != null) {
            if (target.isClickable || target.isCheckable) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val text = getNodeText(focusedNode)
                    if (isOperationActionsAnnounceEnabled(this) && text.isNotEmpty()) {
                        speak(getString(R.string.action_execute_fmt, text), TextToSpeech.QUEUE_FLUSH)
                    }
                    return
                }
            }
            target = target.parent
        }

        // 3. ACTION_SELECT の試行
        if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
            val text = getNodeText(focusedNode)
            if (isOperationActionsAnnounceEnabled(this) && text.isNotEmpty()) {
                speak(getString(R.string.action_select_fmt, text), TextToSpeech.QUEUE_FLUSH)
            }
            return
        }

        // 4. 強力なフォールバック: 要素中央の画面座標へ物理タッチジェスチャーを発行
        clickNodeByGesture(focusedNode)
    }

    fun performActivateCurrentFocus(): Boolean {
        val menu = activeMenuDialog
        if (menu != null && menu.isShowing) {
            if (menu.performCurrentItemClick()) {
                soundHelper?.playClick()
                return true
            }
        }

        val focusNode = getAccessibilityFocusedNode() ?: lastHoveredNode ?: return false
        val rawText = getNodeText(focusNode)
        nextGenAiSuite?.onBeforeUserAction(focusNode, rawText)
        val cleanText = rawText.replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Extended_Pictographic}\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u26FF\u2700-\u27BF]"), "").replace(Regex("\\s+"), " ").trim()
        val announceText = if (cleanText.isNotEmpty()) getString(R.string.gesture_execute_named_fmt, cleanText) else getString(R.string.gesture_execute)

        // 1. 直近ノードの ACTION_CLICK
        if (focusNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            soundHelper?.playClick()
            if (isOperationActionsAnnounceEnabled(this)) {
                speak(announceText, TextToSpeech.QUEUE_FLUSH)
            }
            return true
        }

        // 2. 親ノードの ACTION_CLICK
        var parent = focusNode.parent
        while (parent != null) {
            if (parent.isClickable || parent.isCheckable) {
                if (parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    soundHelper?.playClick()
                    speak(announceText, TextToSpeech.QUEUE_FLUSH)
                    return true
                }
            }
            parent = parent.parent
        }

        // 3. 物理座標ジェスチャークリック
        clickNodeByGesture(focusNode)
        soundHelper?.playClick()
        speak(announceText, TextToSpeech.QUEUE_FLUSH)
        return true
    }

    fun clickNodeByGesture(node: AccessibilityNodeInfo) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        val path = android.graphics.Path().apply {
            moveTo(centerX, centerY)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

        isInternalGestureDispatching = true
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCompleted(gestureDescription)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isInternalGestureDispatching = false
                }, 150)
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                super.onCancelled(gestureDescription)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isInternalGestureDispatching = false
                }, 150)
            }
        }, null)
    }

    fun dismissActiveMenu(): Boolean {
        val menu = activeMenuDialog
        if (menu != null && menu.isShowing) {
            activeMenuDialog = null
            try {
                menu.dismiss()
            } catch (_: Exception) {}
            soundHelper?.playActionDone()
            speak(getString(R.string.menu_closed), TextToSpeech.QUEUE_FLUSH)
            return true
        }
        return false
    }

    fun triggerSerenaMenu() {
        showNormalSerenaMenu()
    }

    fun showNormalSerenaMenu(node: AccessibilityNodeInfo? = null) {
        if (dismissActiveMenu()) {
            return
        }
        val snapshotRoot = try { rootInActiveWindow } catch (e: Exception) { null }
        val snapshotFocused = node ?: getAccessibilityFocusedNode()
        soundHelper?.playMenuOpen()
        val curtainLabel = if (screenCurtainHelper?.isCurtainEnabled == true) "☀️ ${getString(R.string.menu_item_screen_curtain)}" else "🌑 ${getString(R.string.menu_item_screen_curtain)}"
        val isTalkBackMode = prefs.getBoolean(KEY_TALKBACK_MODE, false)
        val modeLabel = getString(R.string.menu_item_mode_switch, getString(if (isTalkBackMode) R.string.mode_talkback else R.string.mode_serena))
        val filterName = notificationFilterHelper?.detailLevel?.getDisplayName(this) ?: getString(R.string.notification_level_full)
        val onText = getString(R.string.state_on)
        val offText = getString(R.string.state_off)
        val items = listOf(
            serenaMenuItem("💡", getString(R.string.menu_item_smart_summary)) {
                speak(getString(R.string.gemma4_analyzing), TextToSpeech.QUEUE_FLUSH)
                serviceScope.launch {
                    val summary = com.kapamilya.serena.ai.SmartScreenSummaryEngine(this@SerenaScreenReaderService)
                        .generateDetailedSummaryAsync(snapshotRoot, snapshotFocused)
                    Log.i(TAG, "Generated Smart Screen Summary (Gemma 4/Edge): $summary")
                    speak(summary, TextToSpeech.QUEUE_FLUSH)
                }
            },
            serenaMenuItem("🎙️", getString(R.string.menu_item_ai_assistant)) {
                launchAiAssistant()
            },
            serenaMenuItem("🌤️", com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "生活・天気・3大ニュース全文・地震・為替・世界時計ハブ",
                en = "Daily Life Hub: Weather, Full-Text News (NHK/ABS-CBN/BBC), Quake & Currency",
                tl = "Sentro ng Panahon, Buong Balita (ABS-CBN/BBC/NHK), Lindol at Pera",
                es = "Centro diario: Clima, Noticias completas (NHK/ABS-CBN/BBC), Sismos y Divisas"
            )) {
                dailyLifeHub?.showDailyLifeHubMenu()
            },
            serenaMenuItem("📰", com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "3大公式ニュース全文リーダー (NHKニュース / ABS-CBN / BBC)",
                en = "Ad-Free Full-Text News Reader (NHK / ABS-CBN / BBC)",
                tl = "Buong Balita Reader (ABS-CBN News / BBC / NHK)",
                es = "Lector de noticias a texto completo (NHK / ABS-CBN / BBC)"
            )) {
                dailyLifeHub?.showGlobalNewsSourceMenu()
            },
            serenaMenuItem("📞", com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "Kapamilya Serena Phone を起動 (標準電話 ＆ WebRTC AIビデオ通話)",
                en = "Launch Kapamilya Serena Phone (Default Dialer & WebRTC AI Video Call)",
                tl = "Buksan ang Kapamilya Serena Phone (Tawag at WebRTC AI Video)",
                es = "Abrir Kapamilya Serena Phone (Teléfono y videollamada IA WebRTC)"
            )) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.kapamilya.serena.phone")
                        ?: Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                } catch (_: Exception) {}
            },
            serenaMenuItem("🔄", modeLabel) {
                toggleTalkBackMode()
            },
            serenaMenuItem("🔊", "Kapamilya 音声エンジン: ${kapamilyaVoiceBridge?.engineMode?.labelJa ?: "標準"}") {
                val next = kapamilyaVoiceBridge?.cycleEngineMode()
                soundHelper?.playActionDone()
                if (next != null) {
                    speak("Kapamilya 音声エンジンモード: ${next.labelJa}", TextToSpeech.QUEUE_FLUSH)
                }
            },
            serenaMenuItem("🌐", "Unicode多言語ルーター: ${kapamilyaVoiceBridge?.unicodeRouterMode?.labelJa ?: "自動"}") {
                val next = kapamilyaVoiceBridge?.cycleUnicodeRouterMode()
                soundHelper?.playActionDone()
                if (next != null) {
                    speak("Unicode多言語ルーター: ${next.labelJa}", TextToSpeech.QUEUE_FLUSH)
                }
            },
            serenaMenuItem("🇦🇺", "7地域英語・タグリッシュ: ${kapamilyaVoiceBridge?.englishDialectMode?.labelJa ?: "en-AU"}") {
                val next = kapamilyaVoiceBridge?.cycleEnglishDialectMode()
                soundHelper?.playActionDone()
                if (next != null) {
                    speak("英語・タグリッシュ発音: ${next.labelJa}", TextToSpeech.QUEUE_FLUSH)
                }
            },
            serenaMenuItem("📊", getString(R.string.menu_item_phone_status)) {
                announceFullStatus()
            },
            serenaMenuItem("📍", getString(R.string.menu_item_location_precision, statusHelper?.locationHelper?.getPrecisionDisplayName() ?: "")) {
                val exact = statusHelper?.locationHelper?.togglePrecision() ?: false
                soundHelper?.playActionDone()
                val name = statusHelper?.locationHelper?.getPrecisionDisplayName() ?: ""
                speak(getString(R.string.precision_changed_fmt, name), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🌍", getString(R.string.menu_item_country_setting, statusHelper?.locationHelper?.getCountrySettingDisplayName() ?: "")) {
                val always = statusHelper?.locationHelper?.toggleAlwaysIncludeCountry() ?: false
                soundHelper?.playActionDone()
                val name = statusHelper?.locationHelper?.getCountrySettingDisplayName() ?: ""
                speak(getString(R.string.country_changed_fmt, name), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🎛️", getString(R.string.menu_item_reading_control, currentGranularity.getDisplayName(this))) {
                cycleGranularity(forward = true)
            },
            serenaMenuItem("📋", getString(R.string.menu_item_clipboard_history)) {
                showClipboardHistoryDialog(null)
            },
            serenaMenuItem("💌", getString(R.string.menu_item_notification_filter, notificationFilterHelper?.detailLevel?.getDisplayName(this) ?: filterName)) {
                cycleNotificationFilterMode()
            },
            serenaMenuItem("🎧", getString(R.string.menu_item_spatial_soundstage, if (soundHelper?.isSpatialSoundstageEnabled == true) onText else offText)) {
                toggleSpatialSoundstage()
            },
            serenaMenuItem("🎵", getString(R.string.menu_item_audio_ducking, if (soundHelper?.isAudioDuckingEnabled == true) onText else offText)) {
                toggleAudioDucking()
            },
            serenaMenuItem("🌙", getString(R.string.menu_item_whisper_mode, soundHelper?.whisperScheduleMode?.getDisplayName(this) ?: getString(R.string.whisper_mode_auto))) {
                cycleNightWhisperMode()
            },
            serenaMenuItem("⌨️", getString(R.string.menu_item_keyboard_mode, getString(if (isKeyboardLiftToType) R.string.keyboard_mode_lift else R.string.keyboard_mode_double_tap))) {
                cycleKeyboardTypingMode()
            },
            serenaMenuItem("🎙️", getString(R.string.menu_item_voice_input)) {
                launchVoiceInput()
            },
            serenaMenuItem("🌏", getString(R.string.menu_item_voice_translation)) {
                launchVoiceTranslation()
            },
            serenaMenuItem("🌐", getString(R.string.menu_item_translation_lang, instantTranslationHelper?.defaultTargetLanguageCode?.uppercase() ?: "EN")) {
                cycleTranslationDefaultLanguage()
            },
            serenaMenuItem("🖼️", getString(R.string.menu_item_image_explainer)) {
                explainCurrentImageOrScreen()
            },
            serenaMenuItem("📳", getString(R.string.menu_item_sound_recognition, soundRecognitionHelper?.detailLevel?.getSpokenLabel(this) ?: offText)) {
                val nextLevel = soundRecognitionHelper?.cycleDetailLevel() ?: com.kapamilya.serena.sound.SoundAlertDetailLevel.DISABLED
                soundHelper?.playActionDone()
                speak(getString(R.string.sound_alert_changed_fmt, nextLevel.getSpokenLabel(this)), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🎬", com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine.MENU_TITLE_LIVE_TAGAPAGSALAYSAY) {
                fivePillarAiEngine?.toggleLiveTagapagsalaysay(launchCameraIfHome = true)
            },
            serenaMenuItem("🛍️", com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine.MENU_TITLE_TAGASURI_NG_PANINDA) {
                fivePillarAiEngine?.runTagasuriNgPaninda()
            },
            serenaMenuItem("📑", com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine.MENU_TITLE_TAGABASA_NG_DOKUMENTO) {
                fivePillarAiEngine?.runTagabasaNgDokumentoAtTalaan()
            },
            serenaMenuItem("🤝", com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine.MENU_TITLE_BAYANIHAN_LINK) {
                fivePillarAiEngine?.toggleBayanihanLink()
            },
            serenaMenuItem("👁️‍🗨️", getString(R.string.menu_item_sentinel_eyes, if (sentinelEyesManager?.isSentinelActive() == true) onText else offText)) {
                toggleSentinelEyes()
            },
            serenaMenuItem("👗", getString(R.string.menu_item_fashion_scanner)) {
                launchFashionScanner()
            },
            serenaMenuItem("🛡️", com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "全方位AI画面推測・セキュリティ壁突破スキャン (Omni-Deduction)",
                en = "Omni-Screen AI Deduction & Security Breakthrough",
                tl = "Omni-Screen AI Deduction at Paglampas sa Seguridad",
                es = "Deducción de pantalla completa con IA y salto de seguridad",
                nl = "Omni-Screen AI-deductie en beveiligingsdoorbraak"
            )) {
                launchOmniScreenDeduction()
            },
            serenaMenuItem("🚀", com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "次世代5大AIスイート (自律操作・詐欺検閲・タイムマシン・音波グラフ・手順記憶)",
                en = "Next-Gen 5-in-1 AI Suite (Autonomous Agent / Anti-Scam / Time Machine / Audio Graph / Workflow)",
                tl = "Next-Gen 5-in-1 AI Suite (Autonomous Agent / Anti-Scam / Time Machine / Audio Graph / Workflow)",
                es = "Suite IA 5 en 1 (Agente autónomo / Antiestafas / Máquina del tiempo / Audio-gráfico / Flujos)",
                nl = "Next-Gen 5-in-1 AI-suite (Autonome agent / Anti-scam / Tijdmachine / Audiografiek / Stappen)"
            )) {
                nextGenAiSuite?.showNextGenAiSuiteMenu()
            },
            serenaMenuItem("🧠", getString(R.string.menu_item_screen_summary)) {
                summarizeCurrentScreen()
            },
            serenaMenuItem("📬", getString(R.string.menu_item_notification_digest)) {
                announceNotificationDigest()
            },
            serenaMenuItem("📡", getString(R.string.menu_item_surrounding_radar)) {
                toggleSurroundingRadar()
            },
            serenaMenuItem("🔔", getString(R.string.menu_item_hourly_chime)) {
                cycleChimeStyle()
            },
            serenaMenuItem("⏱️", getString(R.string.menu_item_hourly_chime_interval)) {
                toggleChimeInterval()
            },
            serenaMenuItem(if (screenCurtainHelper?.isCurtainEnabled == true) "☀️" else "🌑", curtainLabel) {
                toggleScreenCurtain()
            },
            serenaMenuItem("🎨", getString(R.string.menu_item_light_checker)) {
                announceColorAndLightReport()
            },
            serenaMenuItem("🧭", getString(R.string.menu_item_compass_audio)) {
                toggleSpatialCompassAudio()
            },
            serenaMenuItem("🦇", getString(R.string.menu_item_sonar_radar)) {
                toggleSpatialObstacleSonar()
            },
            serenaMenuItem("👆", "${getString(R.string.title_fingerprint_guidance)} (${if (fingerprintGuidanceHelper?.isEnabled == true) onText else offText})") {
                toggleFingerprintGuidance()
            },
            serenaMenuItem("💡", "${getString(R.string.menu_item_usage_hints)} (${if (isUsageHintsEnabled()) onText else offText})") {
                toggleUsageHints()
            },
            serenaMenuItem("🏠", getString(R.string.menu_item_indoor_nav)) {
                launchIndoorNavigation()
            },
            serenaMenuItem("🗺️", getString(R.string.menu_item_street_nav)) {
                announceStreetAndIntersections()
            },
            serenaMenuItem("🚶‍♂️", getString(R.string.menu_item_walk_nav)) {
                announceCurrentLocationAndNav()
            },
            serenaMenuItem("🥫", getString(R.string.menu_item_food_scanner)) {
                launchFoodExpirationScanner()
            },
            serenaMenuItem("🚦", getString(R.string.menu_item_transit_nav)) {
                launchWalkTransitNav()
            },
            serenaMenuItem("📄", getString(R.string.menu_item_barcode_scanner)) {
                launchBarcodeDocScanner()
            },
            serenaMenuItem("📖", getString(R.string.menu_item_kanji_detail)) {
                explainCurrentFocusedKanji(snapshotFocused)
            },
            serenaMenuItem("⚡", getString(R.string.menu_item_speech_rate)) {
                toggleSpeechRateQuick()
            },
            serenaMenuItem("📄", getString(R.string.menu_item_page_next)) {
                scrollHorizontalForward()
            },
            serenaMenuItem("📄", getString(R.string.menu_item_page_prev)) {
                scrollHorizontalBackward()
            },
            serenaMenuItem("📖", getString(R.string.menu_item_read_from_top)) {
                readFromTop()
            },
            serenaMenuItem("📞", getString(R.string.menu_item_call_dev, BuildConfig.DEVELOPER_NAME)) {
                callDeveloper()
            },
            serenaMenuItem("✉️", getString(R.string.menu_item_email_dev, BuildConfig.DEVELOPER_NAME)) {
                emailDeveloper()
            },
            serenaMenuItem("🐛", getString(R.string.menu_item_diagnostics, BuildConfig.DEVELOPER_NAME)) {
                sendTelemetryLog()
            },
            serenaMenuItem("🔄", getString(R.string.menu_item_check_update)) {
                speak(getString(R.string.update_checking), TextToSpeech.QUEUE_FLUSH)
                com.kapamilya.serena.update.AutoUpdateManager.getInstance(this).checkForUpdate { info ->
                    if (info != null && info.isUpdateAvailable) {
                        com.kapamilya.serena.update.AutoUpdateManager.getInstance(this).showUpdateDialog(this, info)
                    } else if (info != null) {
                        speak(getString(R.string.update_already_latest, BuildConfig.VERSION_NAME), TextToSpeech.QUEUE_FLUSH)
                    } else {
                        speak(getString(R.string.update_download_failed, "Network error"), TextToSpeech.QUEUE_FLUSH)
                    }
                }
            },
            serenaMenuItem("⚙️", getString(R.string.menu_item_settings)) {
                openserenaSettings()
            },
            serenaMenuItem("❓", getString(R.string.menu_item_help)) {
                showHelp()
            }
        )
        val totalCount = items.size
        val firstTitle = items.firstOrNull()?.title?.replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Extended_Pictographic}\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u26FF\u2700-\u27BF]"), "")?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        soundHelper?.playMenuOpen()
        
        // メニューオープン音とほぼ同時に超高速レスポンスでナレーション開始！
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            speak(getString(R.string.menu_spoken_header, totalCount, firstTitle), TextToSpeech.QUEUE_FLUSH)
        }, 40)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dialog = serenaMenuDialog(this, false, items)
                activeMenuDialog = dialog
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show dialog: ${e.message}")
                Toast.makeText(this, "Kapamilya Menu: $totalCount items", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun showActionsMenu(targetNode: AccessibilityNodeInfo?) {
        val node = targetNode ?: getAccessibilityFocusedNode()
        val items = mutableListOf<serenaMenuItem>()

        items.add(serenaMenuItem("👆", getString(R.string.menu_long_click)) {
            node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        })

        val targetPkg = resolveTargetPackageName(node)
        if (!targetPkg.isNullOrEmpty()) {
            val appLabel = try {
                val pm = packageManager
                val info = pm.getApplicationInfo(targetPkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                targetPkg
            }

            items.add(serenaMenuItem("🗑️", getString(R.string.menu_uninstall_app, appLabel)) {
                launchUninstallApp(targetPkg)
            })
            items.add(serenaMenuItem("ℹ️", getString(R.string.menu_app_info, appLabel)) {
                launchAppDetailsSettings(targetPkg)
            })
        } else {
            items.add(serenaMenuItem("🗑️", getString(R.string.menu_uninstall_from_settings)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    speak(getString(R.string.uninstall_settings_opened), TextToSpeech.QUEUE_FLUSH)
                } catch (e: Exception) {
                    speak(getString(R.string.uninstall_settings_failed), TextToSpeech.QUEUE_FLUSH)
                }
            })
        }

        if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val customActions = node.actionList
            for (action in customActions) {
                val label = action.label?.toString()
                if (!label.isNullOrBlank()) {
                    items.add(serenaMenuItem("⚡", label) {
                        node.performAction(action.id)
                    })
                }
            }
        }

        soundHelper?.playMenuOpen()
        speak(getString(R.string.menu_actions_spoken_header, items.size), TextToSpeech.QUEUE_FLUSH)
        try {
            val dialog = serenaMenuDialog(this, false, items)
            activeMenuDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show actions menu: ${e.message}")
        }
    }

    private fun resolveTargetPackageName(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val directPkg = node.packageName?.toString()
        val isLauncher = directPkg == "com.android.launcher" || 
                         directPkg == "com.google.android.apps.nexuslauncher" || 
                         directPkg?.contains("launcher", ignoreCase = true) == true ||
                         directPkg?.contains("home", ignoreCase = true) == true

        if (!isLauncher && !directPkg.isNullOrEmpty() && directPkg != packageName) {
            return directPkg
        }

        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (!label.isNullOrBlank()) {
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(0)
                for (app in apps) {
                    val appLabel = pm.getApplicationLabel(app).toString()
                    if (appLabel.equals(label, ignoreCase = true) || label.contains(appLabel, ignoreCase = true)) {
                        if (app.packageName != packageName) {
                            return app.packageName
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Package resolve error: ${e.message}")
            }
        }

        return if (!directPkg.isNullOrEmpty() && directPkg != packageName) directPkg else null
    }

    private fun launchUninstallApp(pkgName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$pkgName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speak(getString(R.string.service_uninstall_dialog_opened), TextToSpeech.QUEUE_FLUSH)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch uninstall: ${e.message}")
            try {
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$pkgName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                speak(getString(R.string.service_uninstall_dialog_failed), TextToSpeech.QUEUE_FLUSH)
            }
        }
    }

    private fun launchAppDetailsSettings(pkgName: String) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkgName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app info: ${e.message}")
        }
    }

    fun toggleTalkBackMode(): Boolean {
        val current = prefs.getBoolean(KEY_TALKBACK_MODE, false)
        val next = !current
        prefs.edit().putBoolean(KEY_TALKBACK_MODE, next).apply()
        soundHelper?.playActionDone()
        val modeName = if (next) getString(R.string.mode_talkback_compat) else getString(R.string.mode_serena_original)
        speak(getString(R.string.action_mode_changed_fmt, modeName), TextToSpeech.QUEUE_FLUSH)
        return next
    }

    private fun showClipboardHistoryDialog(targetNode: AccessibilityNodeInfo?) {
        val history = clipboardHelper?.getHistory() ?: emptyList()
        if (history.isEmpty()) {
            soundHelper?.playActionDone()
            speak(getString(R.string.action_clipboard_empty), TextToSpeech.QUEUE_FLUSH)
            return
        }

        soundHelper?.playMenuOpen()
        speak(getString(R.string.action_clipboard_opened), TextToSpeech.QUEUE_FLUSH)
        val items = history.mapIndexed { index, text ->
            val preview = if (text.length > 20) text.take(20) + "..." else text
            serenaMenuItem("📋", "${index + 1}. $preview") {
                soundHelper?.playActionDone()
                if (targetNode != null) {
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    speak(getString(R.string.action_clipboard_pasted_fmt, preview), TextToSpeech.QUEUE_FLUSH)
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("serenaClip", text)
                    clipboard?.setPrimaryClip(clip)
                    speak(getString(R.string.action_clipboard_copied_fmt, preview), TextToSpeech.QUEUE_FLUSH)
                }
            }
        }
        try {
            val dialog = serenaMenuDialog(this, false, items)
            dialog.show()
        } catch (e: Exception) {
            speak(getString(R.string.action_clipboard_preview_history_fmt, history.take(3).joinToString(", ")), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun cycleNotificationFilterMode() {
        val level = notificationFilterHelper?.cycleDetailLevel()
        soundHelper?.playActionDone()
        speak(getString(R.string.notification_speech_level_fmt, level?.displayName ?: ""), TextToSpeech.QUEUE_FLUSH)
    }

    fun announceFullStatus() {
        soundHelper?.playActionDone()
        val helper = statusHelper
        if (helper != null) {
            helper.buildFullStatusAnnouncement { fullText ->
                speak(fullText, TextToSpeech.QUEUE_FLUSH)
            }
        } else {
            speak(getString(R.string.action_status_unavailable), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun toggleScreenCurtain() {
        val helper = screenCurtainHelper ?: return
        val enabled = helper.toggleCurtain()
        soundHelper?.playActionDone()
        if (enabled) {
            speak(getString(R.string.action_screen_curtain_enabled), TextToSpeech.QUEUE_FLUSH)
        } else {
            speak(getString(R.string.action_screen_curtain_disabled), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun unlockKeyguardSwipe() {
        unlockKeyguardOrShowBouncer()
    }

    private fun focusPinEntryField(): Boolean {
        return autoFocusPinKeypadIfPresent(force = true)
    }

    private fun findNodesByClass(node: AccessibilityNodeInfo, targetClass: String, outList: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.contains(targetClass, ignoreCase = true) == true) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByClass(child, targetClass, outList)
        }
    }

    fun launchRealtimeLiveSceneCommentary() {
        val engine = fivePillarAiEngine
        if (engine != null) {
            engine.toggleLiveTagapagsalaysay(launchCameraIfHome = true)
            return
        }
        soundHelper?.playActionDone()
        speak(
            com.kapamilya.serena.ai.KapamilyaFivePillarAiEngine.GREETING_LIVE_ON,
            TextToSpeech.QUEUE_FLUSH,
            forcedLocale = Locale.forLanguageTag("fil-PH")
        )
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "OBJECT")
                putExtra("ALREADY_SPOKEN_TAGALOG", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch LiveVision: ${e.message}")
        }
    }

    fun performCoordinateTap(x: Float, y: Float) {
        try {
            val dm = resources.displayMetrics
            val safeX = x.coerceIn(1f, (dm.widthPixels - 1).coerceAtLeast(10).toFloat())
            val safeY = y.coerceIn(1f, (dm.heightPixels - 1).coerceAtLeast(10).toFloat())
            val path = android.graphics.Path().apply { moveTo(safeX, safeY) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 65)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            isInternalGestureDispatching = true
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    isInternalGestureDispatching = false
                    soundHelper?.playClick()
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    isInternalGestureDispatching = false
                }
            }, null)
        } catch (e: Exception) {
            isInternalGestureDispatching = false
            Log.w(TAG, "performCoordinateTap error: ${e.message}")
        }
    }

    fun launchOmniScreenDeduction() {
        soundHelper?.playActionDone()
        omniScreenDeductionEngine?.performFullOmniScreenDeduction(openVirtualTouchMenu = true)
    }

    fun summarizeCurrentScreen() {
        soundHelper?.playActionDone()
        speak(getString(R.string.gemma4_analyzing), TextToSpeech.QUEUE_FLUSH)
        serviceScope.launch {
            val summary = smartScreenSummaryEngine?.generateDetailedSummaryAsync() 
                ?: smartScreenSummaryEngine?.generateDetailedSummary() 
                ?: getString(R.string.service_screen_analysis_failed)
            speak(summary, TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun announceNotificationDigest() {
        soundHelper?.playActionDone()
        val digest = notificationFilterHelper?.buildNotificationDigest() ?: getString(R.string.service_notification_no_unread)
        speak(digest, TextToSpeech.QUEUE_FLUSH)
    }

    fun launchSerenaEyes(mode: String = "EYES") {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_eyes), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", mode)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Serena Eyes: ${e.message}")
        }
    }

    var isKeyboardLiftToType: Boolean
        get() = prefs.getBoolean("serena_keyboard_lift_to_type", true)
        set(value) {
            prefs.edit().putBoolean("serena_keyboard_lift_to_type", value).apply()
        }

    fun cycleKeyboardTypingMode() {
        isKeyboardLiftToType = !isKeyboardLiftToType
        soundHelper?.playActionDone()
        val modeStr = getString(if (isKeyboardLiftToType) R.string.keyboard_mode_lift else R.string.keyboard_mode_double_tap)
        speak(getString(R.string.keyboard_mode_changed_fmt, modeStr), TextToSpeech.QUEUE_FLUSH)
    }

    private var lastKeyboardHoverNode: AccessibilityNodeInfo? = null
    private var lastKeyboardHoverTimeMs = 0L
    private var lastKeyboardHoverExitTimeMs = 0L
    private var lastLiftToTypeTimeMs = 0L
    private var lastStatusAnnounceTimeMs = 0L
    private var lastProgressNodeHash = 0
    private var lastProgressPercent = -1
    private var lastProgressAnnounceTimeMs = 0L

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else (28 * resources.displayMetrics.density).toInt()
    }

    private fun isKeyboardNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. 入力エリア（EditTextや編集可能ノード）は絶対にキーボードキーではない！
        val cls = node.className?.toString()?.lowercase() ?: ""
        if (node.isEditable || cls.contains("edittext") || cls.contains("editable")) {
            return false
        }

        // 1.5. キーガード（ロック画面 / Direct Boot）のPIN数字パッドキーは Lift to Type 完全対象！
        if (isKeyguardLocked() && isKeyboardOrPinKeyNode(node)) {
            val rawTxt = (node.text?.toString() ?: "").trim()
            val rawDsc = (node.contentDescription?.toString() ?: "").trim()
            val lbl = (if (rawTxt.isNotEmpty()) rawTxt else rawDsc).lowercase()
            if (!lbl.contains("緊急") && !lbl.contains("emergency") && !lbl.contains("戻る") && !lbl.contains("back")) {
                return true
            }
        }

        // 2. ウィンドウタイプが TYPE_INPUT_METHOD (ソフトウェアキーボードウィンドウ) であることを最優先確認
        val winType = try { node.window?.type } catch (_: Exception) { null }
        val isImeWindow = (winType == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)

        // ウィンドウ情報が取れる場合、TYPE_INPUT_METHOD 以外（通常アプリ・ダイアログ・ランチャー等）は絶対にキーボードではない！
        if (winType != null && !isImeWindow) {
            return false
        }

        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Serena の通常のActivity（MainActivityやメニューダイアログ等）は絶対にキーボードとして判定しない！
        if (pkg == "com.kapamilya.serena" || pkg == "com.kapamilya.serena") {
            if (!isImeWindow) return false
            // SerenaKeyboardView内の文字キーのみ許可（言語切替ボタンや音声・点字などのアクションボタンは除外）
            return viewId.contains("keyboard_key") || viewId.contains("kana_key") || viewId.contains("char_key")
        }

        // クリック可能であること
        val isClickable = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        if (!isClickable) return false

        // サードパーティ製ソフトキーボード（Gboard, LatinIME, ATOK, Simeji 等）
        val isImePackage = pkg.contains("inputmethod") || pkg.contains("gboard") ||
                pkg.contains("keyboard") || pkg.contains("latin") ||
                pkg.contains("simeji") || pkg.contains("atok")

        if (!isImeWindow && !isImePackage) {
            return false
        }

        // 3. GboardやIMEの「機能ボタン・メニュー・ツールバー・入力エリア」を完全除外！
        // これらはLift to Type（指を離して実行）の対象にしてはならず、TalkBack準拠で通常の「ボタン」としてダブルタップで実行させる！
        val rawText = (node.text?.toString() ?: "").trim()
        val rawDesc = (node.contentDescription?.toString() ?: "").trim()
        val label = (if (rawText.isNotEmpty()) rawText else rawDesc).lowercase()

        // 機能メニュー、設定、音声入力、クリップボード、ステッカー、絵文字、入力方法切替等の除外キーワード
        if (label.contains("メニュー") || label.contains("menu") ||
            label.contains("機能") || label.contains("feature") ||
            label.contains("設定") || label.contains("setting") ||
            label.contains("マイク") || label.contains("音声") || label.contains("voice") || label.contains("mic") ||
            label.contains("クリップボード") || label.contains("clipboard") ||
            label.contains("ステッカー") || label.contains("sticker") || label.contains("gif") ||
            label.contains("絵文字") || label.contains("emoji") ||
            label.contains("候補") || label.contains("suggestion") ||
            label.contains("オプション") || label.contains("option") ||
            label.contains("切り替え") || label.contains("switch") ||
            label.contains("非表示") || label.contains("hide") || label.contains("閉じる") || label.contains("close") ||
            viewId.contains("menu") || viewId.contains("more") || viewId.contains("setting") ||
            viewId.contains("voice") || viewId.contains("mic") || viewId.contains("clipboard") ||
            viewId.contains("action_bar") || viewId.contains("toolbar") || viewId.contains("tab") ||
            viewId.contains("suggestion") || viewId.contains("candidate")
        ) {
            return false
        }

        // アクションボタン（確定、完了、検索、Enter、改行、次へ、送信、削除等）も除外して通常のダブルタップボタンとして扱う
        if (label == "完了" || label == "確定" || label == "検索" || label == "改行" || label == "次へ" || label == "送信" ||
            label == "done" || label == "enter" || label == "search" || label == "next" || label == "send" || label == "go" ||
            label.contains("backspace") || label.contains("削除") || label.contains("delete")
        ) {
            return false
        }

        // 4. 文字入力キー（Typing Key）の判定:
        // 通常の文字キーは1文字（a-z, あ-ん, 0-9, 記号1文字等）、または明示的にキーを表すビュー
        val isSingleChar = (rawText.length == 1 || rawDesc.length == 1)
        val isKeyView = cls.contains("softkey") || cls.contains("keyboardview") || viewId.contains("key")

        return isSingleChar || isKeyView
    }

    private fun tryPerformLiftToType(node: AccessibilityNodeInfo?) {
        if (!isKeyboardLiftToType || node == null) return
        val now = System.currentTimeMillis()
        if (now - lastLiftToTypeTimeMs < 200) return
        if (isKeyboardNode(node)) {
            lastLiftToTypeTimeMs = now
            soundHelper?.playClick()
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    fun launchVoiceInput() {
        soundHelper?.playActionDone()
        assistantHelper?.startVoiceInput()
    }

    fun launchVoiceTranslation() {
        soundHelper?.playActionDone()
        assistantHelper?.startVoiceTranslation()
    }

    fun cycleTranslationDefaultLanguage() {
        val nextPair = instantTranslationHelper?.cycleDefaultTargetLanguage() ?: ("en" to "英語")
        soundHelper?.playActionDone()
        speak(getString(R.string.service_translation_lang_fmt, nextPair.second), TextToSpeech.QUEUE_FLUSH)
    }

    fun toggleSpatialSoundstage() {
        soundHelper?.let {
            it.isSpatialSoundstageEnabled = !it.isSpatialSoundstageEnabled
            it.playActionDone()
            val state = if (it.isSpatialSoundstageEnabled) "ON" else "OFF"
            speak(getString(R.string.service_spatial_soundstage_fmt, state), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun toggleAudioDucking() {
        soundHelper?.let {
            it.isAudioDuckingEnabled = !it.isAudioDuckingEnabled
            it.playActionDone()
            val state = if (it.isAudioDuckingEnabled) "ON" else "OFF"
            speak(getString(R.string.service_audio_ducking_fmt, state), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun cycleNightWhisperMode() {
        soundHelper?.let {
            val nextMode = it.cycleWhisperScheduleMode()
            it.playActionDone()
            val effective = if (it.isEffectiveWhisperMode()) getString(R.string.service_whisper_active) else getString(R.string.service_whisper_normal)
            speak(getString(R.string.service_night_whisper_fmt, nextMode.displayName, effective), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun explainCurrentImageOrScreen() {
        soundHelper?.playActionDone()
        val targetNode = lastHoveredNode ?: getAccessibilityFocusedNode()
        if (targetNode != null && isImageOrGraphicNode(targetNode)) {
            analyzeImageNodeUnderFinger(targetNode, forceAnnounce = true)
            return
        }
        speak(getString(R.string.service_ai_deep_explaining), TextToSpeech.QUEUE_FLUSH)
        val summary = smartScreenSummaryEngine?.generateDetailedSummary() ?: getString(R.string.service_screen_analysis_failed)
        speak(getString(R.string.service_ai_deep_result_fmt, summary), TextToSpeech.QUEUE_FLUSH)
    }

    fun launchAiAssistant() {
        soundHelper?.playActionDone()
        assistantHelper?.startListening()
    }

    fun launchCameraOcr() {
        launchSerenaEyes("OCR")
    }

    fun launchCameraFaceAnalysis() {
        launchSerenaEyes("FACE")
    }

    fun launchCameraObjectAnalysis() {
        launchSerenaEyes("OBJECT")
    }

    fun launchFashionScanner() {
        launchSerenaEyes("FASHION")
    }

    fun launchIndoorNavigation() {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_indoor_nav), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "INDOOR")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Indoor Navigation: ${e.message}")
        }
    }

    fun launchFoodExpirationScanner() {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_food_scanner), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "FOOD_EXPIRATION")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Food Expiration: ${e.message}")
        }
    }

    fun launchWalkTransitNav() {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_transit_nav), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "WALK_TRANSIT")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Walk Transit: ${e.message}")
        }
    }

    fun launchBarcodeDocScanner() {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_barcode_doc), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "BARCODE_DOC")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Barcode Doc: ${e.message}")
        }
    }

    fun explainCurrentFocusedKanji(targetNode: AccessibilityNodeInfo? = null) {
        soundHelper?.playClick()
        val node = targetNode
            ?: lastHoveredNode
            ?: getAccessibilityFocusedNode()
        val text = if (node != null) getNodeText(node) else (lastSpokenText ?: "")
        if (text.isNotBlank()) {
            val explanation = com.kapamilya.serena.ai.KanjiExplainerHelper.explainWord(text)
            speak(getString(R.string.service_kanji_detail_fmt, explanation), TextToSpeech.QUEUE_FLUSH, forcedLocale = Locale.JAPANESE)
        } else {
            speak(getString(R.string.service_focused_kanji_not_found), TextToSpeech.QUEUE_FLUSH, forcedLocale = Locale.JAPANESE)
        }
    }

    private fun launchChromeAuthPage(url: String) {
        soundHelper?.playClick()
        speak(getString(R.string.service_launch_chrome_auth), TextToSpeech.QUEUE_FLUSH)
        ChromeAuthHelper.openChromeAuth(this, url)
    }

    private fun sendTelemetryLog() {
        soundHelper?.playClick()
        speak(getString(R.string.service_send_telemetry), TextToSpeech.QUEUE_FLUSH)
        AlphaTelemetryHelper.getInstance(this).sendReportViaEmail(this)
    }

    fun announceCurrentLocationAndNav() {
        soundHelper?.playActionDone()
        val nav = walkingNavigator
        if (nav != null) {
            if (nav.activeDestination != null) {
                val text = nav.getNavigationGuidance()
                speak(text, TextToSpeech.QUEUE_FLUSH)
            } else {
                showWalkingNavMenu(nav)
            }
        } else {
            speak(getString(R.string.nav_location_unavailable), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun startOsmValhallaNavigation(destName: String) {
        soundHelper?.playMenuOpen()
        osmValhallaNavHelper?.startNavigationToDestination(destName)
    }

    fun stopOsmValhallaNavigation() {
        osmValhallaNavHelper?.stopNavigation()
    }

    fun toggleSurroundingRadar() {
        val radar = spatialSurroundingRadarHelper ?: return
        if (radar.isRadarActive()) {
            radar.stopSurroundingRadar()
        } else {
            radar.startSurroundingRadar()
        }
    }

    fun toggleSentinelEyes() {
        val sentinel = sentinelEyesManager ?: return
        if (sentinel.isSentinelActive()) {
            sentinel.stopSentinelEyes()
        } else {
            sentinel.startSentinelEyes()
        }
    }

    private fun showWalkingNavMenu(nav: com.kapamilya.serena.navigation.SerenaWalkingNavigator) {
        val currentSummary = nav.getCurrentLocationSummary()
        val items = listOf(
            serenaMenuItem("📡", getString(R.string.nav_menu_radar)) {
                toggleSurroundingRadar()
            },
            serenaMenuItem("🚶‍♂️", getString(R.string.nav_menu_route_search)) {
                speak(getString(R.string.nav_menu_route_search_prompt), TextToSpeech.QUEUE_FLUSH)
                assistantHelper?.startListening()
            },
            serenaMenuItem("📍", getString(R.string.nav_menu_location_check)) {
                speak(nav.getCurrentLocationSummary(), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🏪", getString(R.string.nav_menu_convenience)) {
                searchAndShowPlaces(getString(R.string.service_place_convenience), "🏪")
            },
            serenaMenuItem("🚉", getString(R.string.nav_menu_station)) {
                searchAndShowPlaces(getString(R.string.service_place_station), "🚉")
            },
            serenaMenuItem("☕", getString(R.string.nav_menu_cafe)) {
                searchAndShowPlaces(getString(R.string.service_place_cafe), "☕")
            },
            serenaMenuItem("🍔", getString(R.string.nav_menu_fastfood)) {
                searchAndShowPlaces(getString(R.string.service_place_fastfood), "🍔")
            },
            serenaMenuItem("🍽️", getString(R.string.nav_menu_restaurant)) {
                searchAndShowPlaces(getString(R.string.service_place_restaurant), "🍽️")
            },
            serenaMenuItem("🛍️", getString(R.string.nav_menu_supermarket)) {
                searchAndShowPlaces(getString(R.string.service_place_supermarket), "🛍️")
            },
            serenaMenuItem("🏥", getString(R.string.nav_menu_hospital)) {
                searchAndShowPlaces(getString(R.string.service_place_hospital), "🏥")
            },
            serenaMenuItem("📮", getString(R.string.nav_menu_post_bank)) {
                searchAndShowPlaces(getString(R.string.service_place_post_office), "📮")
            },
            serenaMenuItem("🧭", getString(R.string.nav_menu_compass_3d)) {
                toggleSpatialCompassAudio()
            },
            serenaMenuItem("❌", getString(R.string.service_nav_end_dest)) {
                stopOsmValhallaNavigation()
                nav.clearDestination()
                soundHelper?.playActionDone()
                speak(getString(R.string.places_destination_cleared), TextToSpeech.QUEUE_FLUSH)
            }
        )

        speak(getString(R.string.places_menu_opened_fmt, currentSummary, items.size, items[0].title), TextToSpeech.QUEUE_FLUSH)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dialog = serenaMenuDialog(this, false, items)
                activeMenuDialog = dialog
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Walking nav dialog error: ${e.message}")
            }
        }
    }

    fun searchAndShowPlaces(category: String, icon: String) {
        val nav = walkingNavigator ?: run {
            speak(getString(R.string.places_nav_unavailable), TextToSpeech.QUEUE_FLUSH)
            return
        }
        soundHelper?.playActionDone()
        speak(getString(R.string.places_searching_fmt, category), TextToSpeech.QUEUE_FLUSH)
        Thread {
            val places = nav.searchNearbyPlaces(category)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (places.isNotEmpty()) {
                    showNearbyPlacesListDialog(category, icon, places, nav)
                } else {
                    speak(getString(R.string.places_not_found_fmt, category), TextToSpeech.QUEUE_FLUSH)
                }
            }
        }.start()
    }

    private fun showNearbyPlacesListDialog(
        categoryTitle: String,
        categoryIcon: String,
        places: List<com.kapamilya.serena.navigation.SerenaWalkingNavigator.NavPlace>,
        nav: com.kapamilya.serena.navigation.SerenaWalkingNavigator
    ) {
        val items = places.map { place ->
            serenaMenuItem(categoryIcon, getString(R.string.service_place_item_fmt, place.name, place.distanceMeters)) {
                showPlaceDetailsDialog(place, nav)
            }
        }

        speak(getString(R.string.places_found_fmt, categoryTitle, items.size, items[0].title), TextToSpeech.QUEUE_FLUSH)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dialog = serenaMenuDialog(this, false, items)
                activeMenuDialog = dialog
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Places list dialog error: ${e.message}")
            }
        }
    }

    private fun showPlaceDetailsDialog(
        place: com.kapamilya.serena.navigation.SerenaWalkingNavigator.NavPlace,
        nav: com.kapamilya.serena.navigation.SerenaWalkingNavigator
    ) {
        val items = mutableListOf<serenaMenuItem>()

        // 1. 目的地に設定して徒歩ナビ開始
        items.add(serenaMenuItem("🎯", getString(R.string.places_menu_start_nav_fmt, place.distanceMeters)) {
            nav.setDestinationCoordinates(place.name, place.latitude, place.longitude)
            soundHelper?.playActionDone()
            val guidance = nav.getNavigationGuidance()
            speak(getString(R.string.places_destination_set_fmt, place.name, guidance), TextToSpeech.QUEUE_FLUSH)
        })

        // 2. 電話をかける
        items.add(serenaMenuItem("📞", getString(R.string.places_menu_call)) {
            soundHelper?.playClick()
            if (place.phone.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${place.phone}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    speak(getString(R.string.places_phone_failed), TextToSpeech.QUEUE_FLUSH)
                }
            } else {
                // 電話番号検索
                speak(getString(R.string.places_phone_search_fmt, place.name), TextToSpeech.QUEUE_FLUSH)
                try {
                    val searchUri = Uri.parse("https://www.google.com/search?q=${java.net.URLEncoder.encode("${place.name} 電話番号", "UTF-8")}")
                    val browserIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (_: Exception) {}
            }
        })

        // 3. ホームページ・詳細情報を開く
        items.add(serenaMenuItem("🌐", getString(R.string.places_menu_web)) {
            soundHelper?.playClick()
            speak(getString(R.string.places_web_search_fmt, place.name), TextToSpeech.QUEUE_FLUSH)
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(place.website)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(browserIntent)
            } catch (_: Exception) {
                speak(getString(R.string.places_browser_failed), TextToSpeech.QUEUE_FLUSH)
            }
        })

        // 4. 詳しい住所の読み上げ
        items.add(serenaMenuItem("📍", getString(R.string.places_menu_address)) {
            speak(getString(R.string.places_address_fmt, place.name, place.address, place.distanceMeters), TextToSpeech.QUEUE_FLUSH)
        })

        speak(getString(R.string.places_detail_menu_opened_fmt, place.name, items.size, items[0].title), TextToSpeech.QUEUE_FLUSH)

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dialog = serenaMenuDialog(this, false, items)
                activeMenuDialog = dialog
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Place details dialog error: ${e.message}")
            }
        }
    }

    fun toggleSpeechRateQuick() {
        val currentRate = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        val newRate = when {
            currentRate < 1.25f -> 1.5f
            currentRate < 1.75f -> 2.0f
            else -> 1.0f
        }
        prefs.edit().putFloat(KEY_SPEECH_RATE, newRate).apply()
        updateTtsSettings()
        soundHelper?.playActionDone()
        speak(getString(R.string.tts_rate_changed_fmt, newRate), TextToSpeech.QUEUE_FLUSH)
    }

    fun isUsageHintsEnabled(): Boolean = isUsageHintsEnabled(this)

    fun toggleUsageHints() {
        val current = isUsageHintsEnabled()
        val next = !current
        prefs.edit().putBoolean(KEY_SPEAK_USAGE_HINTS, next).apply()
        soundHelper?.playActionDone()
        val msg = if (next) getString(R.string.usage_hints_enabled) else getString(R.string.usage_hints_disabled)
        speak(msg, TextToSpeech.QUEUE_FLUSH)
    }

    private fun callDeveloper() {
        soundHelper?.playClick()
        speak(getString(R.string.support_call_dev_fmt, BuildConfig.DEVELOPER_NAME, BuildConfig.DEVELOPER_PHONE_DISPLAY), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${BuildConfig.DEVELOPER_PHONE}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Call developer error: ${e.message}")
            Toast.makeText(this, getString(R.string.main_support_call_fail_fmt, BuildConfig.DEVELOPER_PHONE_DISPLAY), Toast.LENGTH_LONG).show()
        }
    }

    private fun emailDeveloper() {
        soundHelper?.playClick()
        speak(getString(R.string.support_email_dev_fmt, BuildConfig.DEVELOPER_NAME), TextToSpeech.QUEUE_FLUSH)
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${BuildConfig.DEVELOPER_EMAIL}")
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.main_support_mail_subject))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Email developer error: ${e.message}")
            Toast.makeText(this, getString(R.string.main_support_mail_fail_fmt, BuildConfig.DEVELOPER_EMAIL), Toast.LENGTH_LONG).show()
        }
    }

    private fun showHelp() {
        soundHelper?.playMenuOpen()
        speak(getString(R.string.support_menu_opened), TextToSpeech.QUEUE_FLUSH)
        val items = listOf(
            serenaMenuItem("📞", getString(R.string.service_menu_support_call_fmt, BuildConfig.DEVELOPER_NAME, BuildConfig.DEVELOPER_PHONE_DISPLAY)) {
                callDeveloper()
            },
            serenaMenuItem("🐛", getString(R.string.service_menu_support_diag)) {
                sendTelemetryLog()
            },
            serenaMenuItem("✉️", getString(R.string.service_menu_support_mail)) {
                emailDeveloper()
            },
            serenaMenuItem("📖", getString(R.string.service_menu_support_guide)) {
                soundHelper?.playActionDone()
                speak(getString(R.string.support_basic_guide), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("ℹ️", getString(R.string.service_menu_support_about)) {
                soundHelper?.playActionDone()
                speak(getString(R.string.support_version_info_fmt, "1.0.0-alpha01", BuildConfig.DEVELOPER_NAME, BuildConfig.DEVELOPER_EMAIL), TextToSpeech.QUEUE_FLUSH)
            }
        )
        try {
            val dialog = serenaMenuDialog(this, false, items)
            dialog.show()
        } catch (e: Exception) {
            speak(getString(R.string.support_contact_fmt, BuildConfig.DEVELOPER_PHONE_DISPLAY, BuildConfig.DEVELOPER_EMAIL), TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun showEditTextAssistMenu(node: AccessibilityNodeInfo) {
        soundHelper?.playMenuOpen()
        speak(getString(R.string.edit_menu_opened), TextToSpeech.QUEUE_FLUSH)
        val items = listOf(
            serenaMenuItem("💬", getString(R.string.service_phrase_moving)) {
                insertPhrase(node, getString(R.string.service_phrase_moving_text))
            },
            serenaMenuItem("💬", getString(R.string.service_phrase_call_back)) {
                insertPhrase(node, getString(R.string.service_phrase_call_back_text))
            },
            serenaMenuItem("💬", getString(R.string.service_phrase_ok)) {
                insertPhrase(node, getString(R.string.service_phrase_ok_text))
            },
            serenaMenuItem("📋", getString(R.string.service_menu_clip_history)) {
                showClipboardHistoryDialog(node)
            },
            serenaMenuItem("✂️", getString(R.string.service_menu_select_all_copy)) {
                soundHelper?.playActionDone()
                val currentText = getNodeText(node)
                if (currentText.isNotEmpty()) {
                    clipboardHelper?.addClip(currentText)
                }
                val args = Bundle()
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
                node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                speak(getString(R.string.edit_select_all_copied), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("📋", getString(R.string.service_menu_paste)) {
                soundHelper?.playActionDone()
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                speak(getString(R.string.edit_pasted), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🧹", getString(R.string.service_menu_clear_text)) {
                soundHelper?.playActionDone()
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                speak(getString(R.string.edit_cleared_all), TextToSpeech.QUEUE_FLUSH)
            },
            serenaMenuItem("🔊", getString(R.string.service_menu_read_input_text)) {
                soundHelper?.playClick()
                val currentText = getNodeText(node)
                if (currentText.isNotEmpty()) {
                    speak(getString(R.string.edit_current_content_fmt, currentText), TextToSpeech.QUEUE_FLUSH)
                } else {
                    speak(getString(R.string.edit_empty), TextToSpeech.QUEUE_FLUSH)
                }
            },
            serenaMenuItem("☕", getString(R.string.service_menu_open_normal)) {
                showNormalSerenaMenu()
            }
        )
        try {
            val dialog = serenaMenuDialog(this, true, items)
            activeMenuDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show edit dialog: ${e.message}")
            Toast.makeText(this, getString(R.string.service_edit_assist_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun insertPhrase(node: AccessibilityNodeInfo, phrase: String) {
        soundHelper?.playActionDone()
        val currentText = getNodeText(node)
        val newText = if (currentText.isEmpty()) phrase else "$currentText $phrase"
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        speak(getString(R.string.edit_phrase_inserted_fmt, phrase), TextToSpeech.QUEUE_FLUSH)
    }

    private fun readFromTop() {
        val root = rootInActiveWindow ?: return
        soundHelper?.playActionDone()
        speak(getString(R.string.read_from_top_started), TextToSpeech.QUEUE_FLUSH)

        val textList = mutableListOf<String>()
        collectAllNodeText(root, textList)

        if (textList.isEmpty()) {
            speak(getString(R.string.read_no_text_found), TextToSpeech.QUEUE_ADD)
            return
        }

        for (text in textList) {
            speak(text, TextToSpeech.QUEUE_ADD)
        }
    }

    fun announceColorAndLightReport() {
        soundHelper?.playActionDone()
        val report = colorAndLightHelper?.buildFullSensoryReport()
            ?: getString(R.string.service_sensor_error)
        speak(report, TextToSpeech.QUEUE_FLUSH)
    }

    fun toggleSpatialCompassAudio() {
        soundHelper?.playActionDone()
        val helper = compassHelper ?: run {
            speak(getString(R.string.compass_unavailable), TextToSpeech.QUEUE_FLUSH)
            return
        }
        val isEnabled = helper.toggleSpatialAudio()
        if (isEnabled) {
            val heading = helper.getDirectionAnnouncement()
            speak(getString(R.string.compass_started_fmt, heading), TextToSpeech.QUEUE_FLUSH)
        } else {
            speak(getString(R.string.compass_stopped), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun toggleSpatialObstacleSonar() {
        soundHelper?.playActionDone()
        val helper = spatialObstacleSonarHelper ?: run {
            speak(getString(R.string.sonar_unavailable), TextToSpeech.QUEUE_FLUSH)
            return
        }
        val isRunning = helper.toggleSonar()
        if (isRunning) {
            speak(getString(R.string.sonar_started), TextToSpeech.QUEUE_FLUSH)
        } else {
            speak(getString(R.string.sonar_stopped), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun toggleFingerprintGuidance() {
        val helper = fingerprintGuidanceHelper ?: return
        val newState = !helper.isEnabled
        helper.setEnabled(newState)
        soundHelper?.playActionDone()
        val msg = if (newState) getString(R.string.main_fingerprint_guidance_enabled) else getString(R.string.main_fingerprint_guidance_disabled)
        speak(msg, TextToSpeech.QUEUE_FLUSH)
    }

    fun announceStreetAndIntersections() {
        soundHelper?.playMenuOpen()
        val nav = streetIntersectionNavigator ?: run {
            speak(getString(R.string.service_street_nav_preparing), TextToSpeech.QUEUE_FLUSH)
            return
        }
        nav.currentHeadingDegrees = compassHelper?.currentAzimuth ?: 0f
        nav.announceStreetAndIntersections()
    }

    fun announceCompassHeading() {
        soundHelper?.playFocusMove()
        val heading = compassHelper?.getDirectionAnnouncement()
            ?: getString(R.string.service_compass_error)
        speak(heading, TextToSpeech.QUEUE_FLUSH)
    }

    private fun openserenaSettings() {
        soundHelper?.playClick()
        speak(getString(R.string.settings_launching), TextToSpeech.QUEUE_FLUSH)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun announcePermissionDialogWithRetry(attempt: Int, fallbackNode: AccessibilityNodeInfo? = null) {
        com.kapamilya.serena.navigation.SystemDialogHelper.announceDialogWithRetry(this, attempt, fallbackNode)
    }

    private fun handleWindowFocusTransition(windowTitle: String, isDialog: Boolean, delayMs: Long = 150L) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 最前面にダイアログが存在する場合、または直近にダイアログ処理が行われた場合は背景へのフォーカス遷移を中断
            val topDialog = com.kapamilya.serena.navigation.SystemDialogHelper.findTopDialogWindow(this)
            if (topDialog != null) return@postDelayed
            val now = System.currentTimeMillis()
            if (now - com.kapamilya.serena.navigation.SystemDialogHelper.lastHandledTimeMs < 2500L) return@postDelayed
            if (com.kapamilya.serena.navigation.SystemDialogHelper.isDialogHandlingActive()) return@postDelayed

            val nodes = collectAccessibleNodes()
            if (nodes.isNotEmpty()) {
                val currentFocus = getAccessibilityFocusedNode()
                val isDifferentWindow = currentFocus == null || currentFocus.windowId != nodes[0].windowId
                val isFocusLost = currentFocus == null || !nodes.any { isSameNode(it, currentFocus) }

                if (isDifferentWindow || isFocusLost) {
                    val targetNode = if (isDialog) {
                        nodes.find { it.isClickable && (it.className?.toString()?.contains("Button") == true || it.isCheckable) }
                            ?: nodes.find { it.isClickable }
                            ?: nodes[0]
                    } else {
                        nodes[0]
                    }

                    focusNavigator?.setFocusAndShowOnScreen(targetNode)
                    if (isTtsReady) {
                        if (windowTitle.isNotEmpty() && !windowTitle.contains("画面") && isDialog) {
                            speak(windowTitle, TextToSpeech.QUEUE_FLUSH)
                            announceNode(targetNode, TextToSpeech.QUEUE_ADD)
                        } else {
                            announceNode(targetNode, TextToSpeech.QUEUE_FLUSH)
                        }
                    }
                }
            }
        }, delayMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkgName = event.packageName?.toString() ?: ""
        callManager?.checkCallState(pkgName, rootInActiveWindow)

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val rawNotifText = event.text.joinToString(" ").trim()
                if (rawNotifText.isNotEmpty()) {
                    fivePillarAiEngine?.onNotificationOrTextObservedForOtp(rawNotifText)
                }
                val result = notificationFilterHelper?.analyzeEvent(event)?.also {
                    if (it.shouldAnnounce) notificationFilterHelper?.recordNotification(it)
                }
                if (result != null && result.shouldAnnounce) {
                    fivePillarAiEngine?.onNotificationOrTextObservedForOtp(result.formattedAnnouncement)
                    if (result.isIncomingCall) {
                        soundHelper?.playFocusMove()
                        speak(getString(R.string.call_incoming_fmt, result.formattedAnnouncement), TextToSpeech.QUEUE_FLUSH)
                    } else {
                        soundHelper?.playScroll()
                        speak(result.formattedAnnouncement, TextToSpeech.QUEUE_ADD)
                    }

                    // 点字ディスプレイにも通知/着信テキストをリアルタイム出力
                    brailleController?.displayAnnouncement(result.formattedAnnouncement)
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                try {
                    com.kapamilya.serena.update.AutoUpdateManager.getInstance(applicationContext).checkAndResumePendingInstall()
                } catch (_: Exception) {}

                val currentlyLocked = isKeyguardLocked()
                if (wasKeyguardLocked && !currentlyLocked) {
                    wasKeyguardLocked = false
                    soundHelper?.playActionDone()
                    speak(getString(R.string.screen_unlocked), TextToSpeech.QUEUE_FLUSH)
                    fingerprintGuidanceHelper?.onKeyguardDismissed()
                } else if (currentlyLocked) {
                    wasKeyguardLocked = true
                    fingerprintGuidanceHelper?.updateSensorLocation()
                }

                if (activeMenuDialog != null) return
                if (!isKeyguardLocked()) {
                    try {
                        val tbWindow = com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.evaluateActiveSystemWindow(this)
                        if (tbWindow != null) {
                            when (tbWindow.role) {
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.POWER_MENU_GLOBAL_ACTIONS,
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.CREDENTIAL_OR_BIOMETRIC_PROMPT,
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.PERMISSION_OR_ALERT_DIALOG -> {
                                    announcePermissionDialogWithRetry(attempt = 1, fallbackNode = tbWindow.rootNode)
                                    return
                                }
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.QUICK_SETTINGS_PANEL,
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.NOTIFICATION_SHADE,
                                com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.SystemWindowRole.VOLUME_OR_MEDIA_DIALOG -> {
                                    tbWindow.primaryFocusTarget?.let { target ->
                                        try {
                                            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                                            lastHoveredNode = target
                                        } catch (_: Exception) {}
                                    }
                                }
                                else -> {}
                            }
                        }
                        val topDialog = com.kapamilya.serena.navigation.SystemDialogHelper.findTopDialogWindow(this)
                        if (topDialog != null) {
                            announcePermissionDialogWithRetry(attempt = 1, fallbackNode = topDialog.second)
                            return
                        }
                    } catch (_: Exception) {}
                    if (com.kapamilya.serena.navigation.SystemDialogHelper.isDialogHandlingActive()) return
                    handleWindowFocusTransition(windowTitle = "", isDialog = false, delayMs = 150)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                try {
                    com.kapamilya.serena.update.AutoUpdateManager.getInstance(applicationContext).checkAndResumePendingInstall()
                } catch (_: Exception) {}

                val currentlyLocked = isKeyguardLocked()
                if (wasKeyguardLocked && !currentlyLocked) {
                    wasKeyguardLocked = false
                    soundHelper?.playActionDone()
                    speak(getString(R.string.screen_unlocked), TextToSpeech.QUEUE_FLUSH)
                    fingerprintGuidanceHelper?.onKeyguardDismissed()
                } else if (currentlyLocked) {
                    wasKeyguardLocked = true
                    fingerprintGuidanceHelper?.updateSensorLocation()
                }

                // セレナメニューダイアログ表示中の場合、OSがウィンドウ内の全項目テキストを結合して送ってくるため全読みを抑制！
                if (activeMenuDialog != null) {
                    return
                }

                // 朝の初回ロック解除時のモーニングサマリー挨拶
                morningSummaryHelper?.checkAndAnnounceMorningSummary()

                checkCallState(pkgName)

                val windowTitle = event.contentDescription?.toString()
                    ?: event.text.joinToString(" ").trim()
                val className = event.className?.toString() ?: ""

                val eventSource = event.source
                val isPermission = com.kapamilya.serena.navigation.SystemDialogHelper.isPermissionPackage(pkgName)
                val isSpecial = com.kapamilya.serena.navigation.SystemDialogHelper.isSpecialSystemDialogPackage(pkgName)
                val isDialog = isPermission || isSpecial ||
                        className.contains("Dialog", ignoreCase = true) ||
                        className.contains("AlertDialog", ignoreCase = true) ||
                        className.contains("PopupWindow", ignoreCase = true) ||
                        className.contains("AlertActivity", ignoreCase = true) ||
                        className.contains("PermissionActivity", ignoreCase = true) ||
                        className.contains("ConfirmActivity", ignoreCase = true) ||
                        className.contains("SubSettings", ignoreCase = true) ||
                        className.contains("GlobalActions", ignoreCase = true) ||
                        pkgName.contains("com.google.android.gms") ||
                        com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.isPowerMenuGlobalActions(eventSource ?: rootInActiveWindow, windowTitle) ||
                        com.kapamilya.serena.navigation.TalkBack17SystemWindowBridge.isCredentialOrBiometricWindow(eventSource ?: rootInActiveWindow) ||
                        com.kapamilya.serena.navigation.SystemDialogHelper.isSystemUiDialog(eventSource ?: rootInActiveWindow) ||
                        com.kapamilya.serena.navigation.SystemDialogHelper.isSettingsConfirmationDialog(eventSource ?: rootInActiveWindow, className) ||
                        com.kapamilya.serena.navigation.SystemDialogHelper.isGeneralDialog(eventSource ?: rootInActiveWindow, className)

                if (!isDialog) {
                    val suggestedGranularity = appProfileHelper?.getSuggestedGranularity(pkgName)
                    if (suggestedGranularity != null && suggestedGranularity != currentGranularity) {
                        currentGranularity = suggestedGranularity
                        speak(getString(R.string.app_switch_granularity_fmt, suggestedGranularity.getDisplayName(this)), TextToSpeech.QUEUE_FLUSH)
                    }
                }

                if (isCallRelatedPackage(pkgName)) {
                    val callerInfo = parseCallerFromRootNode()
                    if (callerInfo.isNotEmpty() && !isCallActive) {
                        val appName = getMessagingAppName(pkgName)
                        speak(getString(R.string.call_app_incoming_fmt, appName, callerInfo), TextToSpeech.QUEUE_FLUSH)
                        return
                    }
                }

                if (isDialog) {
                    if (isKeyguardLocked()) {
                        autoFocusPinKeypadIfPresent(force = false)
                    } else {
                        announcePermissionDialogWithRetry(attempt = 1, fallbackNode = eventSource)
                    }
                    return
                }

                if (isKeyguardLocked()) {
                    // ロック画面 / Bouncer のウィンドウ検知時のみPIN入力欄を捕捉
                    autoFocusPinKeypadIfPresent(force = false)
                } else {
                    handleWindowFocusTransition(windowTitle, isDialog = false, delayMs = 180)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // コンテンツ動的更新時はフォーカス奪取を行わない（誤爆・ループ防止）
                val evtPkg = event.packageName?.toString() ?: ""
                if (com.kapamilya.serena.navigation.SystemDialogHelper.isPermissionPackage(evtPkg)) {
                    com.kapamilya.serena.navigation.SystemDialogHelper.onPermissionContentChanged(this, event.source)
                }
                handleProgressBarProgressChanged(event)
            }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                lastKeyboardHoverNode = null
                lastKeyboardHoverExitTimeMs = 0L
                fingerprintGuidanceHelper?.onTouchStart()
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                val node = event.source ?: return
                val pkg = node.packageName?.toString() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""
                val className = node.className?.toString() ?: ""

                // 画面内指紋センサー位置案内（ロック画面または生体認証ダイアログ時）
                if (fingerprintGuidanceHelper?.isGuidanceActive() == true) {
                    val consumed = fingerprintGuidanceHelper?.onHoverNode(node) == true
                    if (consumed) {
                        lastHoveredNode = node
                        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        return
                    }
                }

                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val statusBarHeight = getStatusBarHeight()
                val isTouchInStatusBarZone = (rect.top <= 0 && rect.bottom <= statusBarHeight * 1.5)

                val isSystemUi = pkg.contains("systemui", ignoreCase = true)
                val isLauncher = pkg.contains("launcher", ignoreCase = true) || pkg.contains("home", ignoreCase = true)

                // 1. ホーム画面（Launcher）のノードがステータスバー領域でホバーされた場合はホーム画面の内容を読まずステータスバー情報を優先アナウンス！
                if (!isSystemUi && isLauncher && isTouchInStatusBarZone) {
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAnnounceTimeMs > 1200) {
                        lastStatusAnnounceTimeMs = now
                        soundHelper?.playFocusMove()
                        announceFullStatus()
                    }
                    return
                }

                // 2. ステータスバー（SystemUI）の空コンテナに触れた場合もステータスバー情報を優先アナウンス！
                if (isSystemUi && isTouchInStatusBarZone && getNodeText(node).isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAnnounceTimeMs > 1200) {
                        lastStatusAnnounceTimeMs = now
                        soundHelper?.playFocusMove()
                        announceFullStatus()
                    }
                    return
                }

                // 画面全体の背景暗転枠（ScrimView等）で子要素を持つコンテナのみスキップ（ステータスバー領域以外）
                if (isKeyguardLocked() || isSystemUi || pkg.contains("keyguard")) {
                    if (viewId.contains("scrim") || className.contains("ScrimView", ignoreCase = true) ||
                        className.contains("NotificationPanelView", ignoreCase = true) ||
                        className.contains("NotificationShade", ignoreCase = true) ||
                        className.contains("KeyguardRootView", ignoreCase = true)
                    ) {
                        if (node.childCount > 0 && !isTouchInStatusBarZone) return
                    }
                }

                val now = System.currentTimeMillis()
                lastFocusTimeMs = now
                lastHoveredNode = node
                if (isKeyboardNode(node)) {
                    lastKeyboardHoverNode = node
                    lastKeyboardHoverTimeMs = now
                    lastKeyboardHoverExitTimeMs = 0L
                } else {
                    lastKeyboardHoverNode = null
                }
                val dm = resources.displayMetrics
                spatialHapticTouchMapHelper?.processNodeBounds(rect, dm.widthPixels, dm.heightPixels, node.isClickable)
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                if (isTtsReady) {
                    val queueMode = if (now - lastScrollTime < 900) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                    announceNode(node, queueMode, includeFingerPosition = true)
                    if (!isKeyboardNode(node) && isImageOrGraphicNode(node)) {
                        analyzeImageNodeUnderFinger(node, forceAnnounce = false)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                // ホバー移動中（キー探索中）は絶対に入力（クリック）を実行しない！
                // 指を画面から離した瞬間（TYPE_TOUCH_INTERACTION_END）のみLift to Typeを実行する。
                val node = event.source
                if (node != null && (node == lastKeyboardHoverNode || isKeyboardNode(node))) {
                    lastKeyboardHoverExitTimeMs = System.currentTimeMillis()
                }
            }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                fingerprintGuidanceHelper?.onTouchEnd()
                val now = System.currentTimeMillis()
                val targetKey = lastKeyboardHoverNode
                // 指を離した瞬間：キー上で指を離した場合のみ入力実行（離してからの猶予400ms以内）
                val exitDelta = if (lastKeyboardHoverExitTimeMs > 0L) now - lastKeyboardHoverExitTimeMs else 0L
                if (targetKey != null && (now - lastKeyboardHoverTimeMs < 10000) && exitDelta < 400) {
                    tryPerformLiftToType(targetKey)
                }
                lastKeyboardHoverNode = null
                lastKeyboardHoverExitTimeMs = 0L
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                val node = event.source ?: return
                selectedCustomActionIndex = 0
                val now = System.currentTimeMillis()
                lastFocusTimeMs = now
                lastHoveredNode = node
                if (isTtsReady) {
                    val isDialogRecent = (now - com.kapamilya.serena.navigation.SystemDialogHelper.lastHandledTimeMs) < 2500L
                    val queueMode = if (isDialogRecent || now - lastScrollTime < 900) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                    announceNode(node, queueMode)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                soundHelper?.playClick()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val scrolledNode = try { event.source } catch (_: Exception) { null }
                val scrolledClass = event.className?.toString() ?: scrolledNode?.className?.toString() ?: ""
                if (scrolledClass.contains("ProgressBar", ignoreCase = true) ||
                    scrolledClass.contains("ProgressIndicator", ignoreCase = true) ||
                    scrolledClass.contains("SeekBar", ignoreCase = true) ||
                    scrolledClass.contains("Slider", ignoreCase = true) ||
                    scrolledNode?.rangeInfo != null
                ) {
                    handleProgressBarProgressChanged(event)
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastScrollEventTime < 400) return
                lastScrollEventTime = now

                // 手動スクロール直後（700ms以内）またはフォーカス移動直後（800ms以内）は重複読み上げを抑制
                if (now - lastScrollTime < 700 || now - lastFocusTimeMs < 800) {
                    return
                }

                soundHelper?.playScroll()

                val itemCount = event.itemCount
                val fromIndex = event.fromIndex
                val toIndex = event.toIndex
                val text = event.text.joinToString(" ").trim()
                val contentDesc = event.contentDescription?.toString()?.trim() ?: ""

                if (contentDesc.isNotEmpty() && (contentDesc.contains("ページ") || contentDesc.contains("page") || contentDesc.contains("行") || contentDesc.contains("項目"))) {
                    speak(contentDesc, TextToSpeech.QUEUE_ADD)
                } else if (text.isNotEmpty() && (text.contains("ページ") || text.contains("page") || text.contains("行") || text.contains("項目"))) {
                    speak(text, TextToSpeech.QUEUE_ADD)
                } else if (itemCount > 0 && fromIndex >= 0) {
                    val pageIndex = if (toIndex > fromIndex) {
                        getString(R.string.service_page_index_fmt, fromIndex + 1, toIndex + 1, itemCount)
                    } else {
                        getString(R.string.service_page_index_single_fmt, fromIndex + 1, itemCount)
                    }
                    speak(pageIndex, TextToSpeech.QUEUE_ADD)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (event.scrollDeltaY != 0 || event.scrollDeltaX != 0)) {
                    val dx = event.scrollDeltaX
                    val dy = event.scrollDeltaY
                    val dirText = if (Math.abs(dx) >= Math.abs(dy)) {
                        // 横スクロール（ページめくり）
                        if (dx > 0) getString(R.string.service_scroll_next_page) else getString(R.string.service_scroll_prev_page)
                    } else {
                        // 縦スクロール
                        if (dy > 0) getString(R.string.service_scroll_down) else getString(R.string.service_scroll_up)
                    }
                    if (isOperationActionsAnnounceEnabled(this)) {
                        speak(dirText, TextToSpeech.QUEUE_ADD)
                    }
                }

                // スクロール後に画面内の最初の要素に自動フォーカス＆音声ガイド！
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val nodes = collectAccessibleNodes()
                    val target = findBestVisibleNodeAfterScroll(nodes, forward = true, horizontal = true)
                    if (target != null && target != getAccessibilityFocusedNode()) {
                        focusNavigator?.lastFocusedNodeIndex = focusNavigator?.findCurrentNodeIndex(nodes, target) ?: 0
                        focusNavigator?.setFocusAndShowOnScreen(target)
                        announceNode(target, TextToSpeech.QUEUE_ADD)
                    }
                }, 300)
            }

            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                val text = event.text.joinToString(" ").trim()
                if (text.isNotEmpty() && isTtsReady) {
                    val isDialogRecent = com.kapamilya.serena.navigation.SystemDialogHelper.isDialogHandlingActive()
                    val queueMode = if (isDialogRecent) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                    speak(text, queueMode)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> {
                val text = event.text.joinToString(" ").trim()
                val fromIndex = event.fromIndex
                val toIndex = event.toIndex
                if (text.isNotEmpty() && fromIndex >= 0 && fromIndex < text.length && isTtsReady) {
                    val end = if (toIndex in (fromIndex + 1)..text.length) toIndex else fromIndex + 1
                    val traversed = text.substring(fromIndex, end)
                    if (currentGranularity == GranularityMode.CHARACTERS || traversed.length == 1) {
                        val imeLang = getActiveImeSubtypeLocale().ifBlank { null }
                        val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(traversed[0], imeLang)
                        speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                    } else {
                        speak(traversed, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString(" ").trim()
                val addedCount = event.addedCount
                val removedCount = event.removedCount
                val beforeText = event.beforeText?.toString() ?: ""
                val sourceNode = try { event.source } catch (_: Exception) { null }
                val viewId = sourceNode?.viewIdResourceName?.lowercase() ?: ""
                val isPasswordField = event.isPassword || 
                        sourceNode?.isPassword == true ||
                        viewId.contains("pin") ||
                        viewId.contains("password") ||
                        viewId.contains("keyguard")

                if (isTtsReady) {
                    if (isPasswordField) {
                        if (addedCount > 0) {
                            soundHelper?.playClick()
                            val bulletText = if (addedCount == 1) {
                                getString(R.string.service_pin_bullet_single)
                            } else {
                                getString(R.string.service_pin_bullet_multi_fmt, addedCount)
                            }
                            speak(bulletText, TextToSpeech.QUEUE_FLUSH)
                        } else if (removedCount > 0) {
                            soundHelper?.playActionDone()
                            val delText = if (removedCount == 1) {
                                getString(R.string.service_pin_del_single)
                            } else {
                                getString(R.string.service_pin_del_multi_fmt, removedCount)
                            }
                            speak(delText, TextToSpeech.QUEUE_FLUSH)
                        }
                    } else if (addedCount > 0 && text.isNotEmpty()) {
                        val fromIndex = event.fromIndex
                        val addedText = if (fromIndex >= 0 && fromIndex + addedCount <= text.length) {
                            text.substring(fromIndex, fromIndex + addedCount)
                        } else {
                            text
                        }
                        val hasKanji = addedText.any { ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }
                        if (addedText.length == 1 && (currentGranularity == GranularityMode.CHARACTERS || hasKanji)) {
                            val imeLang = getActiveImeSubtypeLocale().ifBlank { null }
                            val (phonetic, phoneticLocale) = com.kapamilya.serena.ime.SerenaPhoneticEngine.getPhoneticReadingWithLocale(addedText[0], imeLang)
                            speak(phonetic, TextToSpeech.QUEUE_FLUSH, forcedLocale = phoneticLocale)
                        } else if (hasKanji && addedText.length in 1..6) {
                            val detail = com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.getKanjiDetail(addedText).let { d ->
                                if (d.isNotEmpty() && d != addedText) d else com.kapamilya.serena.ai.KanjiExplainerHelper.explainWord(addedText)
                            }
                            if (detail.isNotEmpty() && detail != addedText) {
                                speak("$addedText ($detail)", TextToSpeech.QUEUE_FLUSH)
                            } else {
                                speak(addedText, TextToSpeech.QUEUE_FLUSH)
                            }
                        } else {
                            speak(addedText, TextToSpeech.QUEUE_FLUSH)
                        }
                    } else if (removedCount > 0) {
                        val fromIndex = event.fromIndex
                        val deletedText = if (beforeText.isNotEmpty() && fromIndex >= 0 && fromIndex + removedCount <= beforeText.length) {
                            beforeText.substring(fromIndex, fromIndex + removedCount)
                        } else {
                            ""
                        }
                        if (deletedText.isNotEmpty()) {
                            val hasKanjiDel = deletedText.length == 1 && Character.UnicodeBlock.of(deletedText[0]) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                            val spokenDel = if (hasKanjiDel) {
                                val d = com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.getKanjiDetail(deletedText)
                                if (d.isNotEmpty() && d != deletedText) "$deletedText ($d)" else deletedText
                            } else {
                                deletedText
                            }
                            speak(getString(R.string.text_deleted_item, spokenDel), TextToSpeech.QUEUE_FLUSH)
                        } else {
                            speak(getString(R.string.text_deleted), TextToSpeech.QUEUE_FLUSH)
                        }
                    } else if (text.isNotEmpty()) {
                        speak(text, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }
        }
    } catch (se: SecurityException) {
        Log.w(TAG, "Critical Shield: SecurityException intercepted in onAccessibilityEvent: ${se.message}")
    } catch (cme: ConcurrentModificationException) {
        Log.w(TAG, "Critical Shield: ConcurrentModificationException intercepted in onAccessibilityEvent: ${cme.message}")
    } catch (t: Throwable) {
        Log.e(TAG, "Critical Shield: Unhandled exception safely handled in onAccessibilityEvent: ${t.message}", t)
    }
}

    private fun isCallRelatedPackage(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower.contains("dialer") ||
                lower.contains("incallui") ||
                lower.contains("phone") ||
                lower.contains("line") ||
                lower.contains("whatsapp") ||
                lower.contains("skype") ||
                lower.contains("teams") ||
                lower.contains("zoom") ||
                lower.contains("orca") ||
                lower.contains("kakao") ||
                lower.contains("telegram") ||
                lower.contains("viber") ||
                lower.contains("tencent.mm")
    }

    private fun getMessagingAppName(pkgName: String): String {
        val lower = pkgName.lowercase()
        return when {
            lower.contains("line") -> "LINE"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("skype") -> "Skype"
            lower.contains("teams") -> "Teams"
            lower.contains("zoom") -> "Zoom"
            lower.contains("orca") -> "Messenger"
            lower.contains("kakao") -> "カカオトーク"
            lower.contains("telegram") -> "Telegram"
            lower.contains("tencent.mm") -> "WeChat"
            else -> getString(R.string.service_call_app_phone)
        }
    }

    private var lastCallCheckTimeMs = 0L

    private fun checkCallState(pkgName: String) {
        val now = System.currentTimeMillis()
        if (now - lastCallCheckTimeMs < 1500) return
        lastCallCheckTimeMs = now

        val isCallPkg = isCallRelatedPackage(pkgName)
        callManager?.checkCallState(pkgName, rootInActiveWindow)
        if (callManager != null) return
        if (!isCallPkg && !isCallActive) return

        val root = rootInActiveWindow
        val windowText = if (root != null) parseCallerFromRootNode() else ""

        val isCurrentlyInCall = (
                windowText.contains("通話中") ||
                windowText.contains("通話時間") ||
                windowText.contains("保留") ||
                windowText.contains("ミュート") ||
                windowText.matches(Regex(".*\\d{1,2}:\\d{2}.*"))
        )

        if (isCurrentlyInCall && !isCallActive) {
            isCallActive = true
            activeCallApp = getMessagingAppName(pkgName)
            callStartTimeMs = System.currentTimeMillis()
            val calendar = Calendar.getInstance()
            val timeStr = getString(R.string.service_call_time_fmt, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            speak(getString(R.string.call_started_fmt, activeCallApp, timeStr), TextToSpeech.QUEUE_FLUSH)
            Log.i(TAG, "Call started ($activeCallApp) at $callStartTimeMs")
        } else if (!isCurrentlyInCall && isCallActive) {
            isCallActive = false
            val elapsedMs = (System.currentTimeMillis() - callStartTimeMs).coerceAtLeast(1000L)
            val durationText = formatDuration(elapsedMs)
            val appLabel = if (activeCallApp.isNotEmpty()) activeCallApp else getString(R.string.service_call_app_phone)
            val calendar = Calendar.getInstance()
            val timeStr = getString(R.string.service_call_time_fmt, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            AlphaTelemetryHelper.getInstance(this).recordCallCompleted(elapsedMs / 1000)
            speak(getString(R.string.call_ended_fmt, appLabel, durationText, timeStr), TextToSpeech.QUEUE_FLUSH)
            Log.i(TAG, "$appLabel ended. Duration: $durationText, EndTime: $timeStr")
            activeCallApp = ""
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val parts = mutableListOf<String>()
        if (hours > 0) parts.add(getString(R.string.service_unit_hours_fmt, hours))
        if (minutes > 0 || hours > 0) parts.add(getString(R.string.service_unit_minutes_fmt, minutes))
        parts.add(getString(R.string.service_unit_seconds_fmt, seconds))

        return parts.joinToString(" ")
    }

    private fun parseCallerFromRootNode(): String {
        val root = rootInActiveWindow ?: return ""
        val collectedText = mutableListOf<String>()
        collectAllNodeText(root, collectedText)
        return collectedText.filter { it.length > 1 }.joinToString(" ")
    }

    private fun collectAllNodeText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val t = getNodeText(node)
        if (t.isNotEmpty() && !list.contains(t)) {
            list.add(t)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodeText(child, list)
        }
    }

    private var lastFocusedWindowId: Int = -1

    private var lastAnnouncedFingerZone: String = ""
    private var lastFingerZoneTimeMs: Long = 0L
    private var lastImageCropAnalysisTimeMs: Long = 0L
    private var lastImageCropBoundsHash: Int = 0

    /**
     * 指が置かれている画面上の位置（左上・中央・右下など）を推測して自然な言葉で返す
     */
    fun estimateFingerPositionLabel(rect: android.graphics.Rect, screenWidth: Int, screenHeight: Int): String {
        if (screenWidth <= 0 || screenHeight <= 0 || rect.isEmpty) return ""
        val normX = (rect.centerX().toFloat() / screenWidth.toFloat()).coerceIn(0.0f, 1.0f)
        val normY = (rect.centerY().toFloat() / screenHeight.toFloat()).coerceIn(0.0f, 1.0f)
        val isJa = Locale.getDefault().language.lowercase() == "ja"

        return if (isJa) {
            val vLabel = when {
                normY < 0.18f -> "上端"
                normY < 0.38f -> "上部"
                normY <= 0.62f -> "中央"
                normY <= 0.82f -> "下部"
                else -> "下端"
            }
            val hLabel = when {
                normX < 0.33f -> "左"
                normX <= 0.67f -> "中央"
                else -> "右"
            }
            if (vLabel == "中央" && hLabel == "中央") {
                "画面中央"
            } else if (hLabel == "中央") {
                "画面${vLabel}"
            } else if (vLabel == "中央") {
                "画面${hLabel}"
            } else {
                "画面${vLabel}${hLabel}"
            }
        } else {
            val vLabel = when {
                normY < 0.20f -> "top"
                normY < 0.40f -> "upper"
                normY <= 0.60f -> "center"
                normY <= 0.80f -> "lower"
                else -> "bottom"
            }
            val hLabel = when {
                normX < 0.33f -> "left"
                normX <= 0.67f -> "center"
                else -> "right"
            }
            if (vLabel == "center" && hLabel == "center") "Screen center"
            else if (hLabel == "center") "Screen $vLabel"
            else if (vLabel == "center") "Screen $hLabel"
            else "Screen $vLabel $hLabel"
        }
    }

    /**
     * ノードが画像・写真・アイコン・グラフィック要素かどうかを判定
     */
    private fun isImageOrGraphicNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (cls.contains("imageview") || cls.contains("imagebutton") || cls.contains("image") ||
            cls.contains("draweeview") || cls.contains("photoview") ||
            viewId.contains("image") || viewId.contains("photo") || viewId.contains("thumb") ||
            viewId.contains("avatar") || viewId.contains("banner") || viewId.contains("icon")
        ) {
            return true
        }
        val role = getNodeRole(node)
        if (role == getString(R.string.role_image)) return true
        return false
    }

    /**
     * 切り抜いた画像ビットマップの主要な色彩・明るさ・構図特徴をリアルタイム解析
     */
    private fun analyzeBitmapVisualCharacteristics(bitmap: android.graphics.Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return ""
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var brightCount = 0
        var darkCount = 0
        var count = 0
        val stepX = (w / 12).coerceAtLeast(1)
        val stepY = (h / 12).coerceAtLeast(1)
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                rSum += r
                gSum += g
                bSum += b
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma > 200) brightCount++ else if (luma < 55) darkCount++
                count++
            }
        }
        if (count == 0) return ""
        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()
        val avgLuma = (avgR * 299 + avgG * 587 + avgB * 114) / 1000
        val isJa = Locale.getDefault().language.lowercase() == "ja"

        val colorName = if (isJa) {
            when {
                avgLuma > 225 -> "白基調の明るい"
                avgLuma < 35 -> "黒基調の落ち着いた"
                avgR > avgG + 45 && avgR > avgB + 45 -> "暖かみのある赤・オレンジ系の"
                avgG > avgR + 35 && avgG > avgB + 35 -> "鮮やかな緑系の"
                avgB > avgR + 35 && avgB > avgG + 25 -> "爽やかな青・水色系の"
                avgR > 170 && avgG > 150 && avgB < 100 -> "明るい黄色・ゴールド系の"
                avgR > 140 && avgB > 140 && avgG < 110 -> "紫・ピンク系の"
                else -> "バランスの取れた色合いの"
            }
        } else {
            when {
                avgLuma > 225 -> "bright white-toned"
                avgLuma < 35 -> "dark-toned"
                avgR > avgG + 45 && avgR > avgB + 45 -> "warm red/orange"
                avgG > avgR + 35 && avgG > avgB + 35 -> "vibrant green"
                avgB > avgR + 35 && avgB > avgG + 25 -> "cool blue"
                avgR > 170 && avgG > 150 && avgB < 100 -> "bright yellow/gold"
                avgR > 140 && avgB > 140 && avgG < 110 -> "purple/pink"
                else -> "balanced-color"
            }
        }

        val shapeRatio = if (isJa) {
            when {
                w > h * 1.5 -> "横長のバナー画像"
                h > w * 1.4 -> "縦長の写真・画像"
                w < 160 && h < 160 -> "アイコン画像"
                else -> "スクエア画像"
            }
        } else {
            when {
                w > h * 1.5 -> "wide banner image"
                h > w * 1.4 -> "portrait image"
                w < 160 && h < 160 -> "icon graphic"
                else -> "square image"
            }
        }
        return "$colorName$shapeRatio"
    }

    /**
     * 指を置いた場所にある画像要素を takeScreenshot でピンポイントに切り抜き、
     * 色彩・形状・画像内テキスト（OCR）・オンデバイスAI解説を音声で案内する
     */
    fun analyzeImageNodeUnderFinger(node: AccessibilityNodeInfo, forceAnnounce: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty || rect.width() < 24 || rect.height() < 24) return

        val now = System.currentTimeMillis()
        val boundsHash = "${node.windowId}:${rect.left}:${rect.top}:${rect.right}:${rect.bottom}".hashCode()
        if (!forceAnnounce) {
            if (boundsHash == lastImageCropBoundsHash && (now - lastImageCropAnalysisTimeMs) < 4000L) return
            if (now - lastImageCropAnalysisTimeMs < 1300L) return
        }
        lastImageCropBoundsHash = boundsHash
        lastImageCropAnalysisTimeMs = now

        val dm = resources.displayMetrics
        val posLabel = estimateFingerPositionLabel(rect, dm.widthPixels, dm.heightPixels)
        val shapeHint = visualAudioDescriptionHelper?.getVisualShapeDescription(node, getNodeText(node)) ?: ""

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val swBitmap = try {
                                val fullBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                val copied = fullBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                fullBitmap?.recycle()
                                copied
                            } finally {
                                try { hwBuffer.close() } catch (_: Exception) {}
                            }




                            if (swBitmap == null) return

                            val cropLeft = rect.left.coerceIn(0, (swBitmap.width - 1).coerceAtLeast(0))
                            val cropTop = rect.top.coerceIn(0, (swBitmap.height - 1).coerceAtLeast(0))
                            val cropW = rect.width().coerceAtMost(swBitmap.width - cropLeft).coerceAtLeast(1)
                            val cropH = rect.height().coerceAtMost(swBitmap.height - cropTop).coerceAtLeast(1)
                            val cropped = android.graphics.Bitmap.createBitmap(swBitmap, cropLeft, cropTop, cropW, cropH)
                            if (cropped != swBitmap) swBitmap.recycle()

                            val visualSummary = analyzeBitmapVisualCharacteristics(cropped)
                            val helper = ocrHelper
                            if (helper == null) {
                                try { cropped.recycle() } catch (_: Exception) {}
                                return
                            }
                            helper.recognizeTextFromBitmap(cropped) { ocrText ->
                                val cleanOcr = if (ocrText == getString(R.string.ocr_no_text_found) || ocrText == getString(R.string.ocr_error)) {
                                    ""
                                } else {
                                    ocrText.replace(Regex("[\r\n]+"), " ").trim()
                                }
                                serviceScope.launch {
                                    val isJa = Locale.getDefault().language.lowercase() == "ja"
                                    val descParts = mutableListOf<String>()
                                    if (forceAnnounce && posLabel.isNotEmpty()) {
                                        descParts.add(if (isJa) "${posLabel}の画像" else "Image at $posLabel")
                                    }
                                    if (shapeHint.isNotEmpty()) {
                                        descParts.add(shapeHint)
                                    } else if (visualSummary.isNotEmpty()) {
                                        descParts.add(visualSummary)
                                    }
                                    if (cleanOcr.isNotEmpty()) {
                                        descParts.add(if (isJa) "画像内の文字「${cleanOcr}」" else "Text in image: $cleanOcr")
                                    }
                                    val finalDesc = if (descParts.isNotEmpty()) {
                                        if (isJa) "画像解説: ${descParts.joinToString("、")}" else "Image description: ${descParts.joinToString(", ")}"
                                    } else {
                                        ""
                                    }
                                    try { cropped.recycle() } catch (_: Exception) {}
                                    if (finalDesc.isNotEmpty()) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            speak(finalDesc, if (forceAnnounce) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "analyzeImageNodeUnderFinger process error: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "takeScreenshot skipped/failed with code=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot exception: ${e.message}")
        }
    }

    fun announceNode(
        node: AccessibilityNodeInfo,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        includeFingerPosition: Boolean = false
    ) {
        val isKb = isKeyboardNode(node)
        val pkg = node.packageName?.toString() ?: currentActivePackage
        if (!isKb && !pkg.isNullOrEmpty() && !pkg.contains("inputmethod")) {
            currentActivePackage = pkg
        }
        if (isKb) {
            lastKeyboardHoverNode = node
        }

        val rawAnnouncement = buildNodeAnnouncement(node)
        if (rawAnnouncement.isBlank()) return

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val normalizedX = if (displayWidth > 0) rect.centerX().toFloat() / displayWidth else 0.5f
        val normalizedY = if (displayHeight > 0) rect.centerY().toFloat() / displayHeight else 0.5f

        val currentTime = System.currentTimeMillis()
        if (rawAnnouncement == lastSpokenText && (currentTime - lastSpokenTime) < 900) {
            return
        }

        val announcement = if (includeFingerPosition && !isKb && !rect.isEmpty) {
            val posLabel = estimateFingerPositionLabel(rect, displayWidth, displayHeight)
            val shouldPrependPos = posLabel.isNotEmpty() &&
                (posLabel != lastAnnouncedFingerZone || (currentTime - lastFingerZoneTimeMs) > 2500L)
            if (shouldPrependPos) {
                lastAnnouncedFingerZone = posLabel
                lastFingerZoneTimeMs = currentTime
                val comma = if (Locale.getDefault().language == "ja") "、" else ", "
                "$posLabel$comma$rawAnnouncement"
            } else {
                rawAnnouncement
            }
        } else {
            rawAnnouncement
        }

        lastSpokenText = rawAnnouncement
        lastSpokenTime = currentTime
        soundHelper?.playFocusMovePanned(normalizedX, normalizedY)

        val targetLocale = detectLanguage(rawAnnouncement, contextPackage = currentActivePackage, isKeyboard = isKb)
        speak(announcement, queueMode, pan = 0.0f, forcedLocale = targetLocale)

        // 外国語テキストのリアルタイム日本語翻訳読み上げ（原文の直後にキュー追加）
        try {
            instantTranslationHelper?.processTranslationIfNeeded(announcement) { translatedMsg ->
                speak(translatedMsg, TextToSpeech.QUEUE_ADD)
                brailleController?.displayAnnouncement(translatedMsg)
            }
        } catch (_: Exception) {}

        // 点字ディスプレイ（Braille Display）へリアルタイム出力
        try {
            brailleController?.displayNodeInfo(announcement)
        } catch (_: Exception) {}
    }

    private fun buildNodeAnnouncement(node: AccessibilityNodeInfo): String {
        var text = getNodeText(node)
        val role = getNodeRole(node)
        val state = getNodeState(node)
        val pkg = node.packageName?.toString() ?: ""
        val currentWinId = node.windowId

        val parts = mutableListOf<String>()

        val comma = if (Locale.getDefault().language == "ja") "、" else ", "
        val windowWord = getString(R.string.role_window)
        val lockScreenWord = getString(R.string.role_lock_screen)

        // 0. ウィンドウ遷移検知 (ウィンドウが切り替わった時にウィンドウ名やSystemUIを先頭でアナウンス)
        if (currentWinId != lastFocusedWindowId && currentWinId != -1) {
            lastFocusedWindowId = currentWinId
            val win = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    node.window ?: windows?.find { it.id == currentWinId }
                } else null
            } catch (_: Exception) { null }

            val winTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                win?.title?.toString()?.trim() ?: ""
            } else ""

            val winType = win?.type ?: -1
            val winName = when {
                winTitle.isNotEmpty() -> winTitle
                pkg.contains("systemui", ignoreCase = true) || winType == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SystemUI"
                winType == 4 /* TYPE_KEYGUARD */ || isKeyguardLocked() -> lockScreenWord
                else -> ""
            }
            if (winName.isNotEmpty()) {
                parts.add("$windowWord $winName")
            }
        }

        // 1. ビジュアル・オーディオ・ディスクリプション（形状・デザイン解説＆無名要素の推論）
        val shapeDesc = visualAudioDescriptionHelper?.getVisualShapeDescription(node, text) ?: ""
        if (text.isEmpty()) {
            // テキストが空（名前のないボタンや画像）の場合、AI推論で意味ある名前と言語表現を自動生成！
            val dm = resources.displayMetrics
            val omniInferred = omniScreenDeductionEngine?.deduceSingleNodeMeaning(node, dm.widthPixels, dm.heightPixels) ?: ""
            val inferred = if (omniInferred.isNotEmpty()) omniInferred else (visualAudioDescriptionHelper?.inferLabelForUnlabelledNode(node) ?: "")
            if (inferred.isNotEmpty()) {
                text = inferred
            }
        }

        // 2. 全日本語IME（Gboard, ATOK, Simeji, Wnn, Samsung, Xperia, あやめキーボード等）の変換候補バー・漢字詳細読み上げ拡張
        val winTypeForIme = try { node.window?.type } catch (_: Exception) { null }
        val isAnyImeSource = (winTypeForIme == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("gboard", ignoreCase = true) ||
                pkg.contains("latin", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true) ||
                pkg.contains("atok", ignoreCase = true) ||
                pkg.contains("simeji", ignoreCase = true) ||
                pkg.contains("mozc", ignoreCase = true) ||
                pkg.contains("wnn", ignoreCase = true) ||
                pkg.contains("honeyboard", ignoreCase = true) ||
                pkg.contains("ayame", ignoreCase = true) ||
                pkg.contains("markdownhelperkeyboard", ignoreCase = true)
        if (isAnyImeSource) {
            val hasCjkKanji = text.any { ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }
            val viewIdLower = node.viewIdResourceName?.lowercase() ?: ""
            val isCandidateNode = viewIdLower.contains("candidate") || viewIdLower.contains("suggestion") || hasCjkKanji
            if (text.isNotEmpty() && text.length in 1..8 && isCandidateNode && !text.contains("(")) {
                var detail = com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.getKanjiDetail(text)
                if ((detail.isEmpty() || detail == text) && hasCjkKanji) {
                    detail = com.kapamilya.serena.ai.KanjiExplainerHelper.explainWord(text)
                }
                if (detail.isNotEmpty() && detail != text) {
                    text = "$text ($detail)"
                }
            }
        }

        if (text.isNotEmpty()) {
            val formattedText = text.replace(Regex("[\\r\\n]+"), comma)
                .replace(Regex("${Regex.escape(comma)}+"), comma)
                .trim(comma[0], ' ')
            if (formattedText.isNotEmpty()) parts.add(formattedText)
        }

        // 3. アイコン形状解説（textが空で、視覚的デザインしか情報がない要素のみに限定付与）
        // 既に「Gmail, 4 notifications」などの明確なラベルがある場合は、視覚解説の重複付加で言語汚染・冗長化させない！
        if (text.isEmpty() && shapeDesc.isNotEmpty()) {
            parts.add(shapeDesc)
        }

        if (role.isNotEmpty()) parts.add(role)
        if (state.isNotEmpty()) parts.add(state)

        // コンテナやルートノード（text, role, state がすべて空）の場合、無意味な単体読み上げを抑止！
        if (text.isEmpty() && role.isEmpty() && state.isEmpty()) {
            return ""
        }

        // 4. 操作・使用ヒント（Action / Usage Hint）の動的インテリジェント付与
        val isKb = isKeyboardNode(node)
        if (isUsageHintsEnabled() && !isKb) {
            val hint = contextualActionHintHelper?.getActionHint(node, text, role) ?: ""
            if (hint.isNotEmpty()) {
                parts.add(hint)
            }
        }

        return parts.joinToString(comma)
    }

    private fun findCheckableOrSwitchNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("RadioButton", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) ||
            className.contains("CompoundButton", ignoreCase = true)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCheckableOrSwitchNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findProgressOrRangeNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 4) return null
        val className = node.className?.toString() ?: ""
        if (node.rangeInfo != null ||
            className.contains("ProgressBar", ignoreCase = true) ||
            className.contains("ProgressIndicator", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true) ||
            className.contains("Slider", ignoreCase = true)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findProgressOrRangeNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun getNodeText(node: AccessibilityNodeInfo): String {
        try {
            // 1. ノード自体の direct text
            var text = node.contentDescription?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                text = node.text?.toString()?.trim()
            }
            if (text.isNullOrEmpty()) {
                text = node.hintText?.toString()?.trim()
            }
            if (text.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                text = node.tooltipText?.toString()?.trim()
            }
            if (text.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                text = node.paneTitle?.toString()?.trim()
            }
            if (!text.isNullOrEmpty()) {
                return text
            }

            // 2. labeledBy があればそれを参照
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val labeledBy = node.labeledBy
                if (labeledBy != null) {
                    val labelText = labeledBy.contentDescription?.toString()?.trim() ?: labeledBy.text?.toString()?.trim() ?: ""
                    if (labelText.isNotEmpty()) return labelText
                }
            }

            // 3. Jetpack Compose & 階層UI: 深層再帰テキスト抽出エンジン（最大8階層まで完全走査）
            if (node.childCount > 0) {
                val deepTexts = mutableListOf<String>()
                collectDeepTextsForService(node, deepTexts, currentDepth = 0, maxDepth = 8)
                if (deepTexts.isNotEmpty()) {
                    return deepTexts.distinct().joinToString(" ")
                }
            }

            // 4. スイッチやチェックボックス、プログレスバー・スライダー単体でラベルがない場合、同一親行内の兄弟ノードからラベルを探索
            val target = findCheckableOrSwitchNode(node)
            val progressTarget = findProgressOrRangeNode(node)
            if (target != null || progressTarget != null || node.isClickable || node.isCheckable) {
                val parent = node.parent
                if (parent != null && parent.childCount in 2..8) {
                    val siblingTexts = mutableListOf<String>()
                    val parentDirectText = parent.contentDescription?.toString()?.trim() ?: parent.text?.toString()?.trim()
                    if (!parentDirectText.isNullOrEmpty()) {
                        siblingTexts.add(parentDirectText)
                    }
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChild(i) ?: continue
                        if (sibling == node || isSameNode(sibling, node)) continue
                        if (progressTarget != null && sibling.isClickable) continue
                        val st = sibling.contentDescription?.toString()?.trim() ?: sibling.text?.toString()?.trim()
                        if (!st.isNullOrEmpty() && !siblingTexts.contains(st)) {
                            siblingTexts.add(st)
                        }
                    }
                    if (siblingTexts.isNotEmpty()) {
                        return siblingTexts.joinToString(" ")
                    }
                }
            }

            // 5. AIスマート自動ラベリング（無名ボタンへのAI推論名付与）
            val aiInferred = aiAutoLabelHelper?.inferLabelForUnlabeledNode(node)
            if (!aiInferred.isNullOrEmpty()) {
                return aiInferred
            }

            // 6. 特定のキー・ボタンIDからの推定
            val inferred = inferLabelFromViewId(node.viewIdResourceName)
            if (inferred.isNotEmpty()) {
                return inferred
            }

            if (progressTarget != null) {
                return ""
            }

            if (node.isClickable || node.isCheckable || target != null) {
                val role = getNodeRole(node)
                return if (role.isNotEmpty()) role else getString(R.string.role_button)
            }

            return ""
        } catch (_: Exception) {
            return ""
        }
    }

    private fun inferLabelFromViewId(viewId: String?): String {
        if (viewId.isNullOrEmpty()) return ""
        val name = viewId.substringAfterLast(":id/").lowercase()
        return when {
            name.contains("expand_button") || name.contains("chevron") || name == "expand" -> getString(R.string.self_healing_more_options)
            name.contains("collapse_button") || name == "collapse" -> getString(R.string.self_healing_close)
            name.contains("clear_all") || name.contains("btn_clear_all") || name.contains("dismiss_all") -> getString(R.string.self_healing_delete)
            name.contains("settings_button") || name == "settings_gear" || name == "quick_settings" -> getString(R.string.self_healing_settings)
            name.contains("power_button") || name == "power" -> getString(R.string.service_power_menu)
            name.contains("edit_button") || name == "btn_edit" -> getString(R.string.self_healing_edit)
            name.contains("media_play") || name == "action_play" -> getString(R.string.self_healing_play)
            name.contains("media_pause") || name == "action_pause" -> getString(R.string.self_healing_pause)
            name.contains("media_prev") || name == "action_prev" -> getString(R.string.self_healing_prev)
            name.contains("media_next") || name == "action_next" -> getString(R.string.self_healing_next)
            name.contains("pinentry") || name.contains("pin_entry") || name.contains("pin_field") || name.contains("keyguard_pin") || name.contains("pin_view") -> getString(R.string.role_pin_entry)
            name.contains("passwordentry") || name.contains("password_entry") || name.contains("lockpassword") || name.contains("keyguard_password") -> getString(R.string.role_password_entry)
            name == "key0" || name == "button0" -> "0"
            name == "key1" || name == "button1" -> "1"
            name == "key2" || name == "button2" -> "2"
            name == "key3" || name == "button3" -> "3"
            name == "key4" || name == "button4" -> "4"
            name == "key5" || name == "button5" -> "5"
            name == "key6" || name == "button6" -> "6"
            name == "key7" || name == "button7" -> "7"
            name == "key8" || name == "button8" -> "8"
            name == "key9" || name == "button9" -> "9"
            name.contains("delete_button") || name.contains("backspace") || name.contains("btn_delete") -> getString(R.string.service_pin_del_single)
            name.contains("emergency_call_button") || name.contains("emergency") -> getString(R.string.service_call_app_phone)
            name.contains("cancel_button") || name.contains("btn_cancel") -> getString(R.string.self_healing_close)
            name.contains("enter_button") || name.contains("btn_ok") || name.contains("btn_done") -> getString(R.string.self_healing_confirm)
            name.contains("search_button") || name == "search" -> getString(R.string.self_healing_search)
            else -> ""
        }
    }

    private fun collectDeepTextsForService(
        node: AccessibilityNodeInfo,
        outList: MutableList<String>,
        currentDepth: Int,
        maxDepth: Int = 8
    ) {
        if (currentDepth >= maxDepth || outList.size >= 16) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val cd = child.contentDescription?.toString()?.trim()
            val txt = child.text?.toString()?.trim()
            val tooltip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) child.tooltipText?.toString()?.trim() else null

            val chosen = when {
                !cd.isNullOrEmpty() -> cd
                !txt.isNullOrEmpty() -> txt
                !tooltip.isNullOrEmpty() -> tooltip
                else -> null
            }
            if (!chosen.isNullOrEmpty() && !outList.contains(chosen)) {
                outList.add(chosen)
            }
            if (child.childCount > 0) {
                collectDeepTextsForService(child, outList, currentDepth + 1, maxDepth)
            }
            if (outList.size >= 16) break
        }
    }

    private fun handleProgressBarProgressChanged(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val progressNode = findProgressOrRangeNode(source) ?: source
            val className = progressNode.className?.toString() ?: event.className?.toString() ?: ""
            val isProgress = className.contains("ProgressBar", ignoreCase = true) ||
                className.contains("ProgressIndicator", ignoreCase = true) ||
                className.contains("SeekBar", ignoreCase = true) ||
                className.contains("Slider", ignoreCase = true) ||
                progressNode.rangeInfo != null
            val rangeInfo = progressNode.rangeInfo
            if (isProgress && rangeInfo != null && (rangeInfo.max - rangeInfo.min) > 0) {
                val currentPct = ((rangeInfo.current - rangeInfo.min) * 100 / (rangeInfo.max - rangeInfo.min)).toInt().coerceIn(0, 100)
                val rect = android.graphics.Rect()
                progressNode.getBoundsInScreen(rect)
                val stableId = "${progressNode.windowId}:${progressNode.viewIdResourceName ?: className}:${rect.top}:${rect.left}".hashCode()
                val now = System.currentTimeMillis()

                if (stableId == lastProgressNodeHash) {
                    val delta = kotlin.math.abs(currentPct - lastProgressPercent)
                    val isComplete = currentPct >= 100 && lastProgressPercent < 100
                    val isSignificantProgress = delta >= 10 && (now - lastProgressAnnounceTimeMs > 2000L)

                    if (isComplete) {
                        lastProgressPercent = 100
                        lastProgressAnnounceTimeMs = now
                        soundHelper?.playActionDone()
                        val label = getNodeText(progressNode)
                        val msg = if (label.isNotEmpty()) "$label ${getString(R.string.progress_complete)}" else getString(R.string.progress_complete)
                        speak(msg, TextToSpeech.QUEUE_ADD)
                    } else if (isSignificantProgress) {
                        lastProgressPercent = currentPct
                        lastProgressAnnounceTimeMs = now
                        soundHelper?.playScroll()
                        val label = getNodeText(progressNode)
                        val pctStr = getString(R.string.progress_percent, currentPct)
                        val msg = if (label.isNotEmpty()) "$label $pctStr" else pctStr
                        speak(msg, TextToSpeech.QUEUE_ADD)
                    }
                } else {
                    lastProgressNodeHash = stableId
                    lastProgressPercent = currentPct
                    lastProgressAnnounceTimeMs = now
                }
            } else if (isProgress && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val stateDesc = progressNode.stateDescription?.toString()?.trim() ?: ""
                val pctMatch = Regex("(\\d{1,3})\\s*%").find(stateDesc)
                val currentPct = pctMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
                if (currentPct != null) {
                    val rect = android.graphics.Rect()
                    progressNode.getBoundsInScreen(rect)
                    val stableId = "${progressNode.windowId}:${progressNode.viewIdResourceName ?: className}:${rect.top}:${rect.left}".hashCode()
                    val now = System.currentTimeMillis()
                    if (stableId == lastProgressNodeHash) {
                        val delta = kotlin.math.abs(currentPct - lastProgressPercent)
                        if (currentPct >= 100 && lastProgressPercent < 100) {
                            lastProgressPercent = 100
                            lastProgressAnnounceTimeMs = now
                            soundHelper?.playActionDone()
                            speak(getString(R.string.progress_complete), TextToSpeech.QUEUE_ADD)
                        } else if (delta >= 10 && (now - lastProgressAnnounceTimeMs > 2000L)) {
                            lastProgressPercent = currentPct
                            lastProgressAnnounceTimeMs = now
                            soundHelper?.playScroll()
                            speak(getString(R.string.progress_percent, currentPct), TextToSpeech.QUEUE_ADD)
                        }
                    } else {
                        lastProgressNodeHash = stableId
                        lastProgressPercent = currentPct
                        lastProgressAnnounceTimeMs = now
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun getNodeRole(node: AccessibilityNodeInfo): String {
        // キーボードキー（文字キー・特殊キー等）には冗長な「Button」「ボタン」ロールを付与しない
        if (isKeyboardNode(node)) {
            return ""
        }
        val target = findCheckableOrSwitchNode(node) ?: findProgressOrRangeNode(node) ?: node
        val className = target.className?.toString() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val hasExpand = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id }
        } else false
        val hasCollapse = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id }
        } else false
        val hasSetProgress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            target.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }
        } else false

        val isPin = viewId.contains("pin") || className.contains("pin", ignoreCase = true) ||
                className.contains("PasswordTextView", ignoreCase = true)
        val isPass = isPin || node.isPassword || className.contains("PasswordTextView", ignoreCase = true) ||
                viewId.contains("password") || viewId.contains("lockpassword")

        return when {
            className.contains("LauncherAppWidgetHostView", ignoreCase = true) ||
            className.contains("AppWidgetHostView", ignoreCase = true) ||
            className.contains("GlanceAppWidget", ignoreCase = true) ||
            viewId.contains("appwidget") -> getString(R.string.role_widget)
            hasExpand || viewId.contains("expand_button") || viewId.contains("chevron") -> getString(R.string.role_expand_button)
            hasCollapse || viewId.contains("collapse_button") -> getString(R.string.role_collapse_button)
            className.contains("Switch", ignoreCase = true) || className.contains("ToggleButton", ignoreCase = true) -> getString(R.string.role_switch)
            className.contains("CheckBox", ignoreCase = true) -> getString(R.string.role_checkbox)
            className.contains("RadioButton", ignoreCase = true) -> getString(R.string.role_radio_button)
            className.contains("SeekBar", ignoreCase = true) || className.contains("Slider", ignoreCase = true) -> getString(R.string.role_slider)
            className.contains("ProgressBar", ignoreCase = true) || className.contains("ProgressIndicator", ignoreCase = true) -> getString(R.string.role_progress_bar)
            target.rangeInfo != null -> if (hasSetProgress || target.isScrollable) getString(R.string.role_slider) else getString(R.string.role_progress_bar)
            className.contains("Button", ignoreCase = true) -> getString(R.string.role_button)
            node.isPassword || isPass || className.contains("PasswordTextView", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true) || node.isEditable -> {
                val inputType = node.inputType
                when {
                    isPin -> getString(R.string.role_pin_entry)
                    isPass -> getString(R.string.role_password_entry)
                    (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_NUMBER -> getString(R.string.role_number_entry)
                    (inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_PHONE -> getString(R.string.role_phone_entry)
                    (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> getString(R.string.role_email_entry)
                    (inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_URI -> getString(R.string.role_url_entry)
                    else -> getString(R.string.role_edit_text)
                }
            }
            className.contains("ImageView", ignoreCase = true) || className.contains("Image", ignoreCase = true) -> if (node.isClickable) getString(R.string.role_button) else getString(R.string.role_image)
            className.contains("Spinner", ignoreCase = true) -> getString(R.string.role_dropdown_list)
            className.contains("Tab", ignoreCase = true) || className.contains("TabItem", ignoreCase = true) || viewId.contains("tab") -> getString(R.string.role_tab)
            target.isCheckable -> getString(R.string.role_switch)
            className.contains("TextView", ignoreCase = true) -> if (node.isClickable) getString(R.string.role_button) else ""
            node.isClickable -> getString(R.string.role_button)
            else -> ""
        }
    }

    @Suppress("DEPRECATION")
    private fun getNodeState(node: AccessibilityNodeInfo): String {
        val target = findCheckableOrSwitchNode(node) ?: findProgressOrRangeNode(node) ?: node
        val states = mutableListOf<String>()
        val className = target.className?.toString() ?: ""
        val isSwitch = className.contains("Switch", ignoreCase = true) || className.contains("ToggleButton", ignoreCase = true)
        val isCheckBox = className.contains("CheckBox", ignoreCase = true)
        val isRadio = className.contains("RadioButton", ignoreCase = true)

        // 0. Android 11+ (API 30) stateDescription が明示的に設定されている場合は優先取得
        val explicitStateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else null

        // 1. 開閉・展開状態の判定（通知シェード、クイック設定、アコーディオンなど）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val hasExpandAction = node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id }
            val hasCollapseAction = node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id }
            if (hasExpandAction) {
                states.add(getString(R.string.state_collapsed))
            } else if (hasCollapseAction) {
                states.add(getString(R.string.state_expanded))
            }
        }

        // 2. チェック・スイッチ状態判定
        if (target.isCheckable || isSwitch || isCheckBox || isRadio) {
            if (isSwitch) {
                states.add(if (target.isChecked) getString(R.string.state_on) else getString(R.string.state_off))
            } else if (isCheckBox) {
                states.add(if (target.isChecked) getString(R.string.state_checked) else getString(R.string.state_not_checked))
            } else if (isRadio) {
                states.add(if (target.isChecked) getString(R.string.state_selected) else getString(R.string.state_not_selected))
            } else {
                states.add(if (target.isChecked) getString(R.string.state_on) else getString(R.string.state_off))
            }
        }

        // 3. プログレスバー / スライダーの進行状況・進捗率（Determinate / Indeterminate）判定
        val isProgress = className.contains("ProgressBar", ignoreCase = true) ||
                className.contains("ProgressIndicator", ignoreCase = true) ||
                node.className?.toString()?.contains("ProgressBar", ignoreCase = true) == true ||
                node.className?.toString()?.contains("ProgressIndicator", ignoreCase = true) == true
        val isSlider = className.contains("SeekBar", ignoreCase = true) ||
                className.contains("Slider", ignoreCase = true) ||
                node.className?.toString()?.contains("SeekBar", ignoreCase = true) == true ||
                node.className?.toString()?.contains("Slider", ignoreCase = true) == true
        val rangeInfo = target.rangeInfo ?: node.rangeInfo
        if (isProgress || isSlider || rangeInfo != null) {
            if (!explicitStateDesc.isNullOrEmpty()) {
                states.add(explicitStateDesc)
            } else if (rangeInfo != null && (rangeInfo.max - rangeInfo.min) > 0) {
                val pct = ((rangeInfo.current - rangeInfo.min) * 100 / (rangeInfo.max - rangeInfo.min)).toInt().coerceIn(0, 100)
                states.add(getString(R.string.progress_percent, pct))
            } else if (rangeInfo != null) {
                states.add("${rangeInfo.current.toInt()}")
            } else if (isProgress) {
                // 不確定（Indeterminate / くるくるローディング）のプログレスバー
                states.add(getString(R.string.state_in_progress))
            }
        } else if (!explicitStateDesc.isNullOrEmpty() && states.isEmpty()) {
            states.add(explicitStateDesc)
        }

        // 4. 選択状態（タブなど）
        val isTab = className.contains("Tab", ignoreCase = true) ||
                node.className?.toString()?.contains("Tab", ignoreCase = true) == true ||
                node.viewIdResourceName?.lowercase()?.contains("tab") == true
        val selectedStr = getString(R.string.state_selected)
        val notSelectedStr = getString(R.string.state_not_selected)
        if (node.isSelected || target.isSelected) {
            if (!states.contains(selectedStr)) {
                states.add(selectedStr)
            }
        } else if (isTab) {
            if (!states.contains(notSelectedStr) && !states.contains(selectedStr)) {
                states.add(notSelectedStr)
            }
        }

        // 5. 有効/無効の判定（クリック可能・展開可能・進行中要素に対して誤って「無効」と言わないよう防御）
        val isInteractive = node.isClickable || node.isCheckable || target.isClickable || target.isCheckable || isProgress || isSlider || rangeInfo != null
        if (!node.isEnabled && !target.isEnabled && !isInteractive) {
            states.add(getString(R.string.state_disabled))
        }

        return states.joinToString(" ")
    }

    fun handleMagicTapAction() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isRinging = audioManager?.mode == AudioManager.MODE_RINGTONE

        if (isRinging) {
            soundHelper?.playActionDone()
            speak(getString(R.string.call_answered), TextToSpeech.QUEUE_FLUSH)
            simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK)
            return
        }

        if (isCallActive || audioManager?.mode == AudioManager.MODE_IN_CALL || audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) {
            soundHelper?.playActionDone()
            speak(getString(R.string.call_ended), TextToSpeech.QUEUE_FLUSH)
            endCallDirectly()
            return
        }

        // メディアの再生・一時停止状態を厳密に判定
        val isPlaying = audioManager?.isMusicActive == true
        if (isPlaying) {
            soundHelper?.playActionDone()
            speak(getString(R.string.media_paused), TextToSpeech.QUEUE_FLUSH)
            simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        } else {
            soundHelper?.playActionDone()
            speak(getString(R.string.media_resumed), TextToSpeech.QUEUE_FLUSH)
            simulateMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun endCallDirectly(): Boolean {
        var terminated = false

        // 1. TelecomManager による直接切断 (Android 9.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            try {
                if (telecomManager != null && checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    terminated = telecomManager.endCall()
                    Log.i(TAG, "TelecomManager.endCall executed: $terminated")
                }
            } catch (e: Exception) {
                Log.e(TAG, "TelecomManager endCall error: ${e.message}")
            }
        }

        // 2. 画面上の「通話終了」「切断」ノード探索＆クリック
        val root = rootInActiveWindow
        if (root != null) {
            val endNodes = root.findAccessibilityNodeInfosByText("終了") +
                    root.findAccessibilityNodeInfosByText("切断") +
                    root.findAccessibilityNodeInfosByText("通話を終了") +
                    root.findAccessibilityNodeInfosByText("End call") +
                    root.findAccessibilityNodeInfosByText("Decline")
            for (node in endNodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    terminated = true
                    Log.i(TAG, "End call button clicked via accessibility node.")
                    break
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        terminated = true
                        Log.i(TAG, "End call parent button clicked.")
                        break
                    }
                    parent = parent.parent
                }
                if (terminated) break
            }
        }

        // 3. メディアキー & ヘッドセットフックシミュレーション
        simulateMediaKey(KeyEvent.KEYCODE_HEADSETHOOK)
        simulateMediaKey(KeyEvent.KEYCODE_ENDCALL)

        return terminated
    }

    private fun simulateMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager?.dispatchMediaKeyEvent(downEvent)
        audioManager?.dispatchMediaKeyEvent(upEvent)
    }

    val geminiNanoEngine by lazy { com.kapamilya.serena.ai.GeminiNanoEngine(this) }
    var currentActivePackage: String? = null

    fun getActiveImeSubtypeLocale(): String {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val subtype = imm?.currentInputMethodSubtype
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val tag = subtype?.languageTag
                if (!tag.isNullOrBlank()) tag else (subtype?.locale ?: "")
            } else {
                subtype?.locale ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectLanguage(
        text: String,
        contextPackage: String? = currentActivePackage,
        isKeyboard: Boolean = false
    ): Locale {
        val trimmed = text.trim()
        val systemLocale = Locale.getDefault()
        if (trimmed.isEmpty()) return systemLocale

        // 1. Gemini Nano / On-Device コンテキスト連動型自動言語判定エンジン
        val candidateLocale = geminiNanoEngine.detectContextLanguage(
            text = trimmed,
            contextPackage = contextPackage,
            isKeyboard = isKeyboard,
            imeSubtypeLocale = getActiveImeSubtypeLocale(),
            systemLocale = systemLocale
        )

        val candidateAvail = tts?.isLanguageAvailable(candidateLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (candidateAvail >= TextToSpeech.LANG_AVAILABLE) {
            return candidateLocale
        }

        // 2. ハングル、キリル文字等の特殊文字検出フォールバック
        val hasHangul = trimmed.matches(Regex(".*[\\uAC00-\\uD7AF\\u1100-\\u11FF].*"))
        if (hasHangul) {
            val koAvailability = tts?.isLanguageAvailable(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (koAvailability >= TextToSpeech.LANG_AVAILABLE) {
                return Locale.KOREAN
            }
        }

        val hasCyrillic = trimmed.matches(Regex(".*[\\u0400-\\u04FF].*"))
        if (hasCyrillic) {
            val ruLocale = Locale.forLanguageTag("ru-RU")
            val ruAvailability = tts?.isLanguageAvailable(ruLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (ruAvailability >= TextToSpeech.LANG_AVAILABLE) {
                return ruLocale
            }
        }

        // 3. システムデフォルト言語フォールバック
        val sysAvail = tts?.isLanguageAvailable(systemLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (sysAvail >= TextToSpeech.LANG_AVAILABLE) {
            return systemLocale
        }

        return Locale.ENGLISH
    }

    private var isSpeechPaused = false
    private var currentSpeakingUtterance: String? = null

    private var isMuted = false

    fun toggleSpeechMute() {
        if (!isMuted) {
            // ミュート（消音）へ移行
            soundHelper?.playActionDone()
            speak(getString(R.string.speech_muted), TextToSpeech.QUEUE_FLUSH)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isMuted = true
                stopSpeech()
                Toast.makeText(this, "🔇 " + getString(R.string.speech_muted), Toast.LENGTH_SHORT).show()
            }, 1200)
        } else {
            // ミュート解除へ移行
            isMuted = false
            soundHelper?.playActionDone()
            speak(getString(R.string.speech_unmuted), TextToSpeech.QUEUE_FLUSH)
            Toast.makeText(this, "🔊 " + getString(R.string.speech_unmuted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusFirstElement() {
        val root = rootInActiveWindow ?: return
        val nodes = collectAccessibleNodes(root)
        if (nodes.isNotEmpty()) {
            val firstNode = nodes.first()
            firstNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            soundHelper?.playFirstItemEdgeSound()
            announceNode(firstNode)
        }
    }

    private fun focusLastElement() {
        val root = rootInActiveWindow ?: return
        val nodes = collectAccessibleNodes(root)
        if (nodes.isNotEmpty()) {
            val lastNode = nodes.last()
            lastNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            soundHelper?.playLastItemEdgeSound()
            announceNode(lastNode)
        }
    }

    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        pan: Float = 0.0f,
        forcedLocale: Locale? = null
    ) {
        if (text.isBlank() || isMuted) return
        val effectiveQueueMode = if (isStartupGreetingSpeaking && !text.startsWith("Maligayang pagdating") && !text.startsWith("Magandang araw")) {
            TextToSpeech.QUEUE_ADD
        } else {
            queueMode
        }

        if (!isTtsReady || tts == null) {
            synchronized(pendingSpeechQueue) {
                if (effectiveQueueMode == TextToSpeech.QUEUE_FLUSH) {
                    pendingSpeechQueue.clear()
                }
                pendingSpeechQueue.add(Pair(text, effectiveQueueMode))
            }
            return
        }

        soundHelper?.requestDuckAudioFocus()

        val adaptedForDevice = com.kapamilya.serena.translation.SerenaLocalizationBridge.adaptToDeviceLanguage(text)
        val customSubstituted = com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.applyCustomReadings(adaptedForDevice)
        val rawTargetLocale = forcedLocale ?: detectLanguage(customSubstituted)
        val targetLocale = if (rawTargetLocale.language == "fil" || rawTargetLocale.language == "tl") {
            val filPh = Locale.forLanguageTag("fil-PH")
            val tlPh = Locale.forLanguageTag("tl-PH")
            when {
                (tts?.isLanguageAvailable(filPh) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE -> filPh
                (tts?.isLanguageAvailable(tlPh) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE -> tlPh
                else -> rawTargetLocale
            }
        } else {
            rawTargetLocale
        }
        val processedText = emojiHelper?.translateEmojiAndKaomoji(customSubstituted, targetLocale) ?: customSubstituted
        lastSpokenText = processedText
        lastSpokenTime = System.currentTimeMillis()
        currentSpeakingUtterance = processedText
        isSpeechPaused = false

        try {
            val currentLang = tts?.language
            val needLangSwitch = currentLang == null ||
                currentLang.language != targetLocale.language ||
                (targetLocale.country.isNotEmpty() && currentLang.country != targetLocale.country)
            if (needLangSwitch) {
                tts?.language = targetLocale
            }
            AlphaTelemetryHelper.getInstance(this).incrementTtsCount(targetLocale)
        } catch (_: Exception) {}

        val utteranceId = "serenaUtterance_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            val isWhispering = soundHelper?.isEffectiveWhisperMode() == true
            val vol = if (isWhispering) 0.55f else 1.0f
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)
            if (soundHelper?.isSpatialSoundstageEnabled == true && pan != 0.0f) {
                putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.coerceIn(-1.0f, 1.0f))
            }
        }
        val bridge = kapamilyaVoiceBridge
        val rate = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        val pitch = prefs.getFloat(KEY_SPEECH_PITCH, 1.0f)
        val handledByBridge = bridge?.speakMultilingual(
            rawText = processedText,
            primaryTts = tts,
            queueMode = effectiveQueueMode,
            rate = rate,
            pitch = pitch,
            params = params,
            forcedLocale = forcedLocale
        ) ?: false
        if (!handledByBridge) {
            val result = tts?.speak(processedText, effectiveQueueMode, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS speak returned ERROR for text: $processedText")
                tts?.speak(processedText, effectiveQueueMode, null, utteranceId)
            }
        }
    }

    fun toggleSpeechPauseResume() {
        if (tts?.isSpeaking == true) {
            isSpeechPaused = true
            soundHelper?.playActionDone()
            tts?.stop()
        } else if (isSpeechPaused && !currentSpeakingUtterance.isNullOrBlank()) {
            isSpeechPaused = false
            soundHelper?.playActionDone()
            speak(currentSpeakingUtterance!!, TextToSpeech.QUEUE_FLUSH)
        } else {
            val focusNode = getAccessibilityFocusedNode()
            if (focusNode != null) {
                soundHelper?.playActionDone()
                announceNode(focusNode)
            }
        }
    }

    fun stopSpeech() {
        kapamilyaVoiceBridge?.stop()
        tts?.stop()
    }

    override fun onInterrupt() {
        try {
            stopSpeech()
        } catch (t: Throwable) {
            Log.e(TAG, "Critical Shield: Error in onInterrupt safely handled: ${t.message}", t)
        }
    }

    fun dismissKeyguardViaOs() {
        unlockKeyguardOrShowBouncer()
    }

    private fun registerTimeTickReceiver() {
        if (timeTickReceiver != null) return
        timeTickReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> checkHourlyChime()
                    Intent.ACTION_SCREEN_OFF -> {
                        soundHelper?.playScreenLockChime()
                        val lockTagalog = "Naka-lock na ang screen, aking Kapamilya. Magpahinga ka muna, bantay lang ako rito!"
                        speak("$lockTagalog ${getString(R.string.screen_locked)}", TextToSpeech.QUEUE_FLUSH)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (isKeyguardLocked()) {
                            soundHelper?.playFocusMove()
                            speak(getString(R.string.lock_screen_guide), TextToSpeech.QUEUE_FLUSH)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                autoFocusPinKeypadIfPresent(force = true)
                            }, 250)
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        soundHelper?.playActionDone()
                        val unlockTagalog = "Na-unlock na ang screen, aking Kapamilya! Maligayang pagbabalik, nandito lang ako para sa iyo!"
                        speak("$unlockTagalog ${getString(R.string.screen_unlocked)}", TextToSpeech.QUEUE_FLUSH)
                    }
                    Intent.ACTION_USER_UNLOCKED -> {
                        Log.i(TAG, "Device unlocked from Direct Boot. Reloading storage, Kanji dictionary & preferred TTS.")
                        try {
                            val safeCtx = getSafeContext()
                            prefs = safeCtx.getSafeSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            com.kapamilya.serena.ime.SerenaFullKanjiDetailDictionary.init(this@SerenaScreenReaderService)
                            initTts(this@SerenaScreenReaderService, forceDefaultEngine = false)
                            soundHelper?.playActionDone()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling USER_UNLOCKED: ${e.message}")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeTickReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(timeTickReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerTimeTickReceiver error: ${e.message}")
        }
    }

    private fun unregisterTimeTickReceiver() {
        timeTickReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Unregister receiver error: ${e.message}")
            }
        }
        timeTickReceiver = null
    }

    fun checkHourlyChime() {
        val isEnabled = prefs.getBoolean(KEY_HOURLY_CHIME_ENABLED, true)
        if (!isEnabled) return

        val allowHalfHour = prefs.getBoolean(KEY_HOURLY_CHIME_HALF_HOUR, false)
        val calendar = Calendar.getInstance()
        val minute = calendar.get(Calendar.MINUTE)
        if (minute == 0) {
            triggerHourlyAnnouncement(calendar.get(Calendar.HOUR_OF_DAY), 0)
        } else if (allowHalfHour && minute == 30) {
            triggerHourlyAnnouncement(calendar.get(Calendar.HOUR_OF_DAY), 30)
        }
    }

    fun triggerHourlyAnnouncement(hour24: Int, minute: Int = 0) {
        val style = prefs.getString(KEY_CHIME_STYLE, CHIME_STYLE_NHK) ?: CHIME_STYLE_NHK
        when (style) {
            CHIME_STYLE_NHK -> soundHelper?.playNhkRadioChime()
            CHIME_STYLE_CUTE -> soundHelper?.playCuteBeepChime()
            CHIME_STYLE_BELL -> soundHelper?.playJapaneseBellChime()
            else -> soundHelper?.playNhkRadioChime()
        }

        val isAm = hour24 < 12
        val displayHour = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val periodStr = if (isAm) getString(R.string.main_time_am) else getString(R.string.main_time_pm)

        val announcement = if (minute == 30) {
            getString(R.string.service_hourly_chime_half_announcement_fmt, periodStr, displayHour)
        } else {
            getString(R.string.service_hourly_chime_announcement_fmt, periodStr, displayHour)
        }
        speak(announcement, TextToSpeech.QUEUE_FLUSH)
    }

    fun toggleChimeInterval() {
        val currentHalf = prefs.getBoolean(KEY_HOURLY_CHIME_HALF_HOUR, false)
        val nextHalf = !currentHalf
        prefs.edit().putBoolean(KEY_HOURLY_CHIME_HALF_HOUR, nextHalf).apply()
        soundHelper?.playActionDone()
        val msg = if (nextHalf) {
            getString(R.string.msg_hourly_chime_interval_30m)
        } else {
            getString(R.string.msg_hourly_chime_interval_1h)
        }
        speak(msg, TextToSpeech.QUEUE_FLUSH)
    }

    fun cycleChimeStyle() {
        val currentStyle = prefs.getString(KEY_CHIME_STYLE, CHIME_STYLE_NHK)
        val nextStyle = when (currentStyle) {
            CHIME_STYLE_NHK -> CHIME_STYLE_CUTE
            CHIME_STYLE_CUTE -> CHIME_STYLE_BELL
            else -> CHIME_STYLE_NHK
        }
        prefs.edit().putString(KEY_CHIME_STYLE, nextStyle).apply()
        val name = when (nextStyle) {
            CHIME_STYLE_NHK -> {
                soundHelper?.playNhkRadioChime()
                getString(R.string.service_chime_nhk)
            }
            CHIME_STYLE_CUTE -> {
                soundHelper?.playCuteBeepChime()
                getString(R.string.service_chime_pop)
            }
            else -> {
                soundHelper?.playJapaneseBellChime()
                getString(R.string.service_chime_temple)
            }
        }
        speak(getString(R.string.chime_sound_changed_fmt, name), TextToSpeech.QUEUE_FLUSH)
    }

    fun testSmartBatteryAnnouncement() {
        batteryHelper?.testSmartBatteryAnnouncement()
    }

    /**
     * ユーザーが意図して Kapamilya Serena をオフにした時（onUnbind / onDestroy）に
     * 終了チャイム＋タガログ語のシャットダウン挨拶を最後まで確実に鳴らし切る
     */
    private fun triggerShutdownFarewellIfNeeded() {
        if (hasSpokenShutdownGreeting) return
        hasSpokenShutdownGreeting = true
        try {
            soundHelper?.playSerenaShutdownChime()
            val farewell = "Hanggang sa muli, aking Kapamilya! Lagi lang nandito ang Kapamilya Serena para sa iyo. Maraming salamat, mahal kita, at mag-ingat ka palagi!"
            val filPh = Locale.forLanguageTag("fil-PH")
            val tlPh = Locale.forLanguageTag("tl-PH")
            val hasFil = (tts?.isLanguageAvailable(filPh) ?: -2) >= 0
            val hasTl = (tts?.isLanguageAvailable(tlPh) ?: -2) >= 0
            tts?.language = when {
                hasFil -> filPh
                hasTl -> tlPh
                else -> detectLanguage(farewell)
            }
            val spokenFarewell = if (hasFil || hasTl) {
                farewell
            } else {
                farewell.replace("Kapamilya", "Kah-pah-meel-yah")
            }
            tts?.speak(spokenFarewell, TextToSpeech.QUEUE_FLUSH, null, "serena_shutdown_greeting")
        } catch (e: Exception) {
            Log.w(TAG, "triggerShutdownFarewellIfNeeded error: ${e.message}")
        }
    }

    /**
     * 万が一の致命的クラッシュ発生時に SerenaApp から呼び出される緊急フェアウェル
     */
    fun speakShutdownGreetingOnCrash() {
        try {
            triggerShutdownFarewellIfNeeded()
            Thread.sleep(3200)
        } catch (_: Exception) {}
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Kapamilya Serena onUnbind: playing shutdown chime & Tagalog farewell.")
        triggerShutdownFarewellIfNeeded()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        triggerShutdownFarewellIfNeeded()
        try {
            sentinelEyesManager?.stopSentinelEyes()
            sentinelEyesManager?.release()
            sentinelEyesManager = null
            spatialSurroundingRadarHelper?.stopSurroundingRadar()
            spatialSurroundingRadarHelper = null
            osmValhallaNavHelper?.stopNavigation()
            osmValhallaNavHelper = null
            walkingNavigator?.stopTracking()
            walkingNavigator = null
            compassHelper?.stopListening()
            compassHelper = null
            colorAndLightHelper?.release()
            colorAndLightHelper = null
            spatialObstacleSonarHelper?.stopSonar()
            spatialObstacleSonarHelper = null
            screenCurtainHelper?.disableCurtain()
            screenCurtainHelper = null
            ocrHelper?.close()
            ocrHelper = null
            assistantHelper?.release()
            assistantHelper = null
            statusHelper?.release()
            statusHelper = null
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
        callManager?.release()
        callManager = null
        wifiConnectivityHelper?.stopMonitoring()
        wifiConnectivityHelper = null
        batteryHelper?.stop()
        batteryHelper = null
        soundRecognitionHelper?.stop()
        soundRecognitionHelper = null
        instantTranslationHelper?.close()
        instantTranslationHelper = null
        visualAudioDescriptionHelper = null
        fivePillarAiEngine?.release()
        fivePillarAiEngine = null
        streetIntersectionNavigator = null
        brailleController?.disconnect()
        brailleController = null
        fingerprintGuidanceHelper?.destroy()
        fingerprintGuidanceHelper = null
        shakeDetectorHelper?.stop()
        unregisterTimeTickReceiver()

        // シャットダウン挨拶の音声が途中でブツ切りにならないよう、TTSとSoundPoolの解放を5.5秒遅延させる
        val ttsToShutdown = tts
        val bridgeToShutdown = kapamilyaVoiceBridge
        val soundToRelease = soundHelper
        tts = null
        kapamilyaVoiceBridge = null
        soundHelper = null
        isTtsReady = false
        instance = null

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { bridgeToShutdown?.shutdown() } catch (_: Exception) {}
            try { ttsToShutdown?.shutdown() } catch (_: Exception) {}
            try { soundToRelease?.release() } catch (_: Exception) {}
        }, 5500L)

        Log.i(TAG, "serena ScreenReaderService destroyed (farewell audio scheduled for graceful completion).")
    }

    fun toggleLiveEnvironmentDescription() {
        try {
            soundHelper?.playMenuOpen()
            val intent = Intent(this, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "LIVE")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch LiveVisionActivity: ${e.message}")
            speak(getString(R.string.camera_live_launch_failed), TextToSpeech.QUEUE_FLUSH)
        }
    }

    fun showClipboardHistoryQuickly() {
        val lastText = clipboardHelper?.getHistory()?.firstOrNull() ?: getString(R.string.action_clipboard_empty)
        speak(getString(R.string.clipboard_latest_fmt, lastText), TextToSpeech.QUEUE_FLUSH)
    }

    fun showSerenaAssistantDialog() {
        soundHelper?.playMenuOpen()
        speak(getString(R.string.assistant_menu_opened), TextToSpeech.QUEUE_FLUSH)
        val items = listOf(
            serenaMenuItem("💬", getString(R.string.chat_title)) {
                openSerenaAiChat()
            },
            serenaMenuItem("🧠", getString(R.string.service_menu_assistant_summary)) {
                summarizeCurrentScreen()
            },
            serenaMenuItem("🌸", getString(R.string.service_menu_assistant_heartful)) {
                assistantHelper?.speakWarmHeartGreeting()
            },
            serenaMenuItem("🎙️", getString(R.string.service_menu_assistant_voice)) {
                assistantHelper?.startListening()
            },
            serenaMenuItem("📷", getString(R.string.service_menu_assistant_ocr)) {
                launchCameraOcr()
            },
            serenaMenuItem("🖼️", getString(R.string.service_menu_assistant_scenery)) {
                launchCameraObjectAnalysis()
            },
            serenaMenuItem("🔋", getString(R.string.service_menu_assistant_status)) {
                announceFullStatus()
            }
        )
        try {
            val dialog = serenaMenuDialog(this, false, items)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "showSerenaAssistantDialog error: ${e.message}")
        }
    }

    /**
     * Serena オンデバイスAI対話チャット画面を起動
     */
    fun openSerenaAiChat(screenContext: String? = null) {
        try {
            soundHelper?.playMenuOpen()
            val ctxText = screenContext ?: smartScreenSummaryEngine?.generateDetailedSummary()
            val intent = Intent(this, com.kapamilya.serena.ai.SerenaAiChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!ctxText.isNullOrBlank()) {
                    putExtra(com.kapamilya.serena.ai.SerenaAiChatActivity.EXTRA_SCREEN_CONTEXT, ctxText)
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SerenaAiChatActivity: ${e.message}")
        }
    }
}

private val AccessibilityNodeInfo.safeIsHeading: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isHeading else false



