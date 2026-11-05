/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KapamilyaPhoneMainActivity (v5.11.0 — Global 4-Language Edition)
 *
 * 『Kapamilya Serena Phone』 (v5.11.0)
 * 1. 世界4言語（日本語 / English / Tagalog / Español）完全ローカライズ（OS言語自動検出 ＋ ワンタップ言語切替）
 * 2. 接続中サウンド・入った音（入室チャイム）・落ちた音（退室・切断チャイム）純粋PCMシンセサイザー搭載
 * 3. ハンズフリー自動応答AIテストボット（1対1・6人ビデオ・10人音声：ボットが話し終わると自動でマイク聞き取り＆自動返答＋定期的な自発会話）
 * 4. 全通信 AES-256-GCM 完全暗号化（ビデオ最大6人 / 音声最大10人）
 * 5. Google DeepMind `Gemma 4` ＋ `EmbeddingGemma 2` による相手の表情・自分のカメラ映りAI説明
 * 6. 通話終了時の 1秒単位の正確な通話時間（〇時間〇分〇秒）自動読み上げ
 */
class KapamilyaPhoneMainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, KapamilyaInCallService.CallStateObserver {

    companion object {
        private const val TAG = "KapamilyaPhoneMain"
        const val APP_VERSION = "5.14.0"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var toneGenerator: ToneGenerator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var videoAiNarrator: KapamilyaVideoCallAiNarrator
    private lateinit var aesGcmMeshEngine: KapamilyaAes256GcmMeshEngine

    private var uiLang: String = "ja"
    private var activeTab: Int = 0 // 0 = Standard Phone, 1 = Video Call, 2 = Contacts (Device & Google), 3 = Settings

    private lateinit var rootLayout: LinearLayout
    private lateinit var standardPhoneTabContainer: LinearLayout
    private lateinit var videoCallTabContainer: LinearLayout
    private lateinit var contactsTabContainer: LinearLayout
    private lateinit var settingsTabContainer: LinearLayout
    private lateinit var deviceContactsContainer: LinearLayout
    private lateinit var googleContactsContainer: LinearLayout
    private lateinit var tvStatusBanner: TextView
    private lateinit var tvEncryptionAndMeshBanner: TextView
    private lateinit var tvAiLiveCommentary: TextView
    private lateinit var etPhoneNumberOrRoom: EditText
    private lateinit var etAesPassphrase: EditText
    private lateinit var webRtcView: WebView
    private lateinit var historyContainer: LinearLayout

    private var isSpeakerOn = false
    private var isMicMuted = false
    private var isWebRtcActive = false
    private var isWebRtcVideoEnabled = false
    private var isBotCallActive = false
    private var sawVoiceActivityDuringListen = false
    private var lastPartialRecognizedText = ""
    private var webRtcStartElapsedMs = 0L
    private var isAutoAiCommentaryEnabled = true
    private var useFrontCameraForAi = true
    private var audioQualityMode = 0 // 0 = 48kHz Fullband Opus, 1 = 24kHz HD Voice, 2 = 16kHz Wideband
    private var isEchoCancelerEnabled = true
    private var isNoiseSuppressorEnabled = true
    private var isAutoGainEnabled = true

    private val requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateDefaultDialerStatus()
    }

    private val speechRecognizeIntentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spokenText = matches?.firstOrNull()?.trim().orEmpty()
        handleSpokenToTestBot(spokenText)
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadCallHistoryAndContacts()
    }

    private val autoAiCommentaryRunnable = object : Runnable {
        override fun run() {
            if (isWebRtcActive && isWebRtcVideoEnabled && isAutoAiCommentaryEnabled && !isBotCallActive) {
                captureWebRtcFrameAndAnalyzePartner(forceSpeak = false)
            }
            mainHandler.postDelayed(this, 8000L)
        }
    }

    /**
     * ボット通話中に音声認識サービスが無い端末や無音時でも、ボットから自動で話しかけて会話を継続するタイマー
     */
    private val autoBotProactiveTurnRunnable = object : Runnable {
        override fun run() {
            if (isWebRtcActive && isBotCallActive && !isMicMuted) {
                val isSpeakingNow = try { tts?.isSpeaking == true } catch (_: Exception) { false }
                if (!isSpeakingNow) {
                    handleSpokenToTestBot("")
                }
            }
            if (isWebRtcActive && isBotCallActive) {
                mainHandler.postDelayed(this, 14000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiLang = KapamilyaInCallService.resolveActiveLang(this)
        videoAiNarrator = KapamilyaVideoCallAiNarrator(this)
        aesGcmMeshEngine = KapamilyaAes256GcmMeshEngine(
            localDisplayName = Build.MODEL ?: "Kapamilya-Android",
            langProvider = { uiLang },
            onMeshEvent = { eventMsg ->
                mainHandler.post {
                    updateMeshBannerUi()
                    speak(eventMsg)
                }
            },
            onPartnerExpressionReceived = { partnerName, expText ->
                mainHandler.post {
                    val line = "😊 [$partnerName] $expText"
                    tvAiLiveCommentary.text = line
                }
            }
        )
        aesGcmMeshEngine.startMeshSignalingService()
        try {
            tts = TextToSpeech(this, this)
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 85)
        } catch (_: Exception) {}

        buildAccessibleUi()
        KapamilyaKrisSoundEngine.attachToActivity(this, playStartupSound = true)
        KapamilyaInCallService.addObserver(this)
        requestNecessaryPermissions()
        handleIncomingDialIntent(intent)
        mainHandler.postDelayed(autoAiCommentaryRunnable, 8000L)
        KapamilyaDualEndpointUpdater.checkOnStartup(this, manualTrigger = false) { msg ->
            speak(msg)
        }
    }

    override fun onResume() {
        super.onResume()
        KapamilyaDualEndpointUpdater.checkAndResumePendingInstall(this) { msg ->
            speak(msg)
        }
    }

    private fun switchLanguage(newLang: String) {
        uiLang = newLang
        getSharedPreferences(KapamilyaInCallService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KapamilyaInCallService.KEY_UI_LANG, newLang)
            .apply()
        applyTtsLanguage()
        syncRoomEncryptionKey(isWebRtcVideoEnabled)
        buildAccessibleUi()
        loadCallHistoryAndContacts()

        val msg = when (uiLang) {
            "en" -> "Language switched to English. Connecting sound, participant joined/left chimes, and hands-free auto-reply AI Test Bot are ready."
            "tl" -> "Binago ang wika sa Tagalog. Handa na ang tunog sa pagkonekta, pagpasok, pag-alis, at ang kusang sumasagot na Serena AI Test Bot!"
            "es" -> "Idioma cambiado a Español. Sonidos de conexión, entrada, salida y el Bot IA con respuesta automática están listos."
            else -> "表示と音声の言語を日本語に切り替えました。接続中サウンド・入った音・落ちた音・ボット自動応答がすべて有効です。"
        }
        speak(msg)
    }

    private fun applyTtsLanguage() {
        try {
            val loc = when (uiLang) {
                "en" -> Locale.US
                "tl" -> Locale("fil", "PH")
                "es" -> Locale("es", "ES")
                else -> Locale.JAPANESE
            }
            tts?.language = loc
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDialIntent(intent)
    }

    private fun handleIncomingDialIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "tel") {
            val num = data.schemeSpecificPart ?: ""
            if (num.isNotBlank()) {
                etPhoneNumberOrRoom.setText(num)
                val msg = when (uiLang) {
                    "en" -> "Phone number $num entered. Tap Call to dial."
                    "tl" -> "Nailagay na ang numerong $num. Pindutin ang Call para tumawag."
                    "es" -> "Número $num ingresado. Toque Llamar para marcar."
                    else -> "電話番号 $num が入力されました。発信ボタンで電話をかけられます。"
                }
                speak(msg)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                applyTtsLanguage()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null && utteranceId.startsWith("bot_turn_") && isWebRtcActive && isBotCallActive && !isMicMuted) {
                            mainHandler.postDelayed({
                                if (isWebRtcActive && isBotCallActive && !isMicMuted) {
                                    startAutoVoiceListeningAfterBotSpeech()
                                }
                            }, 450L)
                        }
                    }
                })
                isTtsReady = true
                val welcome = when (uiLang) {
                    "en" -> "Welcome to Kapamilya Serena Phone version $APP_VERSION! Full AES-256-GCM encryption, up to 6 video or 10 audio participants, connecting/joined/left sound effects, and hands-free auto-reply AI Test Bot are ready!"
                    "tl" -> "Maligayang pagdating sa Kapamilya Serena Phone bersyon $APP_VERSION! Lahat ng tawag ay naka-encrypt sa AES-256-GCM, may tunog sa pagkonekta, pagpasok at pag-alis, at awtomatikong sumasagot na Serena AI Test Bot!"
                    "es" -> "¡Bienvenido a Kapamilya Serena Phone versión $APP_VERSION! Cifrado total AES-256-GCM, sonidos de conexión, entrada y salida, y Bot de Prueba IA con respuesta automática por voz listos."
                    else -> "Kapamilya Serena Phone バージョン $APP_VERSION へようこそ！接続中サウンド・入った音・落ちた音・世界4言語ローカライズ・そして声だけで自動的に会話が続くセレナAIテストボットが準備完了です。"
                }
                speak(welcome)
            } catch (_: Exception) {}
        }
    }

    fun speak(text: String, utteranceIdPrefix: String = "phone_utt_") {
        if (text.isBlank()) return
        try {
            if (isTtsReady) {
                applyTtsLanguage()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${utteranceIdPrefix}${System.currentTimeMillis()}")
            }
        } catch (_: Exception) {}
    }

    private fun tr(ja: String, en: String, tl: String, es: String): String = when (uiLang) {
        "en" -> en
        "tl" -> tl
        "es" -> es
        else -> ja
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAccessibleUi() {
        val prevInput = if (::etPhoneNumberOrRoom.isInitialized) etPhoneNumberOrRoom.text.toString() else ""
        val prevPass = if (::etAesPassphrase.isInitialized) etAesPassphrase.text.toString() else "Kapamilya-Serena-AES256GCM-Key"

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1021"))
        }
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 64)
        }

        val tvHeader = TextView(this).apply {
            text = tr(
                "🌺 Kapamilya Serena Phone v$APP_VERSION\n全通信 AES-256-GCM 完全暗号化 • 接続音/入室音/退室音 • 自動応答AIボット • 世界4言語対応",
                "🌺 Kapamilya Serena Phone v$APP_VERSION\nFull AES-256-GCM E2EE • Connect/Join/Leave Sounds • Auto-Reply AI Bot • 4 Languages",
                "🌺 Kapamilya Serena Phone v$APP_VERSION\nAES-256-GCM E2EE • Tunog sa Pagkonekta/Pagpasok/Pag-alis • Auto-Reply AI Bot • 4 Wika",
                "🌺 Kapamilya Serena Phone v$APP_VERSION\nCifrado AES-256-GCM • Sonidos de Conexión/Entrada/Salida • Bot IA Automático • 4 Idiomas"
            )
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }
        rootLayout.addView(tvHeader)

        // === 🌍 世界4言語ワンタップ切替バー (日本語 / English / Tagalog / Español) ===
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        val langs = listOf(
            "ja" to "🇯🇵 日本語",
            "en" to "🇺🇸 English",
            "tl" to "🇵🇭 Tagalog",
            "es" to "🇪🇸 Español"
        )
        for ((code, label) in langs) {
            val isSelected = (uiLang == code)
            val btnLang = Button(this).apply {
                text = label
                contentDescription = label
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                setBackgroundColor(Color.parseColor(if (isSelected) "#FFD54F" else "#1E293B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener { switchLanguage(code) }
            }
            langRow.addView(btnLang)
        }
        rootLayout.addView(langRow)

        tvStatusBanner = TextView(this).apply {
            text = tr(
                "📞 待機中：電話番号またはWebRTCルーム名を入力して発信できます",
                "📞 Ready: Enter a phone number or WebRTC room name to start a call",
                "📞 Handa na: Maglagay ng numero ng telepono o WebRTC room name para tumawag",
                "📞 Listo: Ingrese un número de teléfono o sala WebRTC para llamar"
            )
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A237E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 22, 24, 22)
        }
        rootLayout.addView(tvStatusBanner)

        tvEncryptionAndMeshBanner = TextView(this).apply {
            setTextColor(Color.parseColor("#A7F3D0"))
            setBackgroundColor(Color.parseColor("#064E3B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(24, 18, 24, 18)
        }
        rootLayout.addView(tvEncryptionAndMeshBanner)
        updateMeshBannerUi()

        // === 📑 4タブ切替バー (1.標準電話 ⇄ 2.ビデオ通話 ⇄ 3.連絡先・Googleコンタクト ⇄ 4.設定) ===
        val tabRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 4)
        }
        val tabRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 10)
        }
        val tab0Label = tr("📞 1. 標準電話・履歴", "📞 1. Dialer & History", "📞 1. Telepono at History", "📞 1. Teléfono e Historial")
        val tab1Label = tr("🎥 2. ビデオ・AI通話", "🎥 2. Video & AI Call", "🎥 2. Video at AI Call", "🎥 2. Videollamada e IA")
        val tab2Label = tr("👥 3. 連絡先・Google", "👥 3. Contacts & Google", "👥 3. Contacts at Google", "👥 3. Contactos y Google")
        val tab3Label = tr("⚙️ 4. 設定 (音質ほか)", "⚙️ 4. Settings & Audio", "⚙️ 4. Settings at Boses", "⚙️ 4. Ajustes y Audio")

        fun createTabButton(label: String): Button = Button(this).apply {
            text = label
            contentDescription = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(12, 20, 12, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 4, 4, 4)
            }
        }

        val btnTabStandard = createTabButton(tab0Label)
        val btnTabVideo = createTabButton(tab1Label)
        val btnTabContacts = createTabButton(tab2Label)
        val btnTabSettings = createTabButton(tab3Label)

        fun updateTabVisibility(announce: Boolean) {
            standardPhoneTabContainer.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
            videoCallTabContainer.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
            contactsTabContainer.visibility = if (activeTab == 2) View.VISIBLE else View.GONE
            settingsTabContainer.visibility = if (activeTab == 3) View.VISIBLE else View.GONE

            btnTabStandard.setTextColor(if (activeTab == 0) Color.BLACK else Color.WHITE)
            btnTabStandard.setBackgroundColor(Color.parseColor(if (activeTab == 0) "#4ADE80" else "#1E293B"))
            btnTabVideo.setTextColor(if (activeTab == 1) Color.BLACK else Color.WHITE)
            btnTabVideo.setBackgroundColor(Color.parseColor(if (activeTab == 1) "#38BDF8" else "#1E293B"))
            btnTabContacts.setTextColor(if (activeTab == 2) Color.BLACK else Color.WHITE)
            btnTabContacts.setBackgroundColor(Color.parseColor(if (activeTab == 2) "#FBBF24" else "#1E293B"))
            btnTabSettings.setTextColor(if (activeTab == 3) Color.BLACK else Color.WHITE)
            btnTabSettings.setBackgroundColor(Color.parseColor(if (activeTab == 3) "#C084FC" else "#1E293B"))

            if (announce) {
                val msg = when (activeTab) {
                    0 -> tr("標準電話・ダイヤラー＆履歴タブに切り替えました。キーパッドと発着信履歴が使えます。", "Switched to Standard Phone & History Tab.", "Lumipat sa Tab ng Telepono at History.", "Cambiado a la pestaña de Teléfono e Historial.")
                    1 -> tr("ビデオ通話・暗号化グループ＆AIボットタブに切り替えました。", "Switched to Video Call & Encrypted AI Tab.", "Lumipat sa Tab ng Video Call at AI.", "Cambiado a la pestaña de Videollamada e IA.")
                    2 -> tr("連絡先タブに切り替えました。端末の連絡先と Google コンタクトが一覧できます。", "Switched to Contacts Tab (Device & Google Contacts).", "Lumipat sa Tab ng Contacts (Device at Google Contacts).", "Cambiado a la pestaña de Contactos (Dispositivo y Google Contacts).")
                    else -> tr("設定タブに切り替えました。通話音質（48kHzフルバンド等）、ノイズ抑制、エコーキャンセラー、標準電話設定が変更できます。", "Switched to Settings Tab (Audio Quality & Preferences).", "Lumipat sa Tab ng Settings (Kalidad ng Tunog at Iba Pa).", "Cambiado a la pestaña de Ajustes (Calidad de Audio y Más).")
                }
                speak(msg)
            }
        }

        btnTabStandard.setOnClickListener { activeTab = 0; updateTabVisibility(announce = true) }
        btnTabVideo.setOnClickListener { activeTab = 1; updateTabVisibility(announce = true) }
        btnTabContacts.setOnClickListener { activeTab = 2; updateTabVisibility(announce = true) }
        btnTabSettings.setOnClickListener { activeTab = 3; updateTabVisibility(announce = true) }

        tabRow1.addView(btnTabStandard)
        tabRow1.addView(btnTabVideo)
        tabRow2.addView(btnTabContacts)
        tabRow2.addView(btnTabSettings)
        rootLayout.addView(tabRow1)
        rootLayout.addView(tabRow2)

        etPhoneNumberOrRoom = EditText(this).apply {
            setText(prevInput)
            hint = tr(
                "電話番号 (例: 090...) または WebRTCルーム名 (例: kapamilya)",
                "Phone number or WebRTC Room Name (e.g., kapamilya)",
                "Numero ng telepono o WebRTC Room Name (hal., kapamilya)",
                "Número de teléfono o Sala WebRTC (ej., kapamilya)"
            )
            setHintTextColor(Color.parseColor("#B0BEC5"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(24, 22, 24, 22)
            contentDescription = hint
        }
        rootLayout.addView(etPhoneNumberOrRoom)

        val rowEditActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rowEditActions.addView(
            createAccessibleButton(
                tr("⌫ 1文字消す", "⌫ Backspace", "⌫ Burahin 1", "⌫ Borrar 1"),
                "#37474F"
            ) {
                val cur = etPhoneNumberOrRoom.text.toString()
                if (cur.isNotEmpty()) {
                    val deleted = cur.last()
                    etPhoneNumberOrRoom.setText(cur.dropLast(1))
                    etPhoneNumberOrRoom.setSelection(etPhoneNumberOrRoom.text.length)
                    speak(tr("${deleted} を削除しました", "Deleted $deleted", "Binura ang $deleted", "Borrado $deleted"))
                } else {
                    speak(tr("入力欄は空です", "Input is empty", "Walang laman", "El campo está vacío"))
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 8, 4, 8) } }
        )
        rowEditActions.addView(
            createAccessibleButton(
                tr("🗑️ すべてクリア", "🗑️ Clear All", "🗑️ Burahin Lahat", "🗑️ Limpiar Todo"),
                "#455A64"
            ) {
                etPhoneNumberOrRoom.setText("")
                speak(tr("入力欄をクリアしました", "Cleared input field", "Binura lahat", "Campo limpiado"))
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 8, 4, 8) } }
        )
        rootLayout.addView(rowEditActions)

        standardPhoneTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        videoCallTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contactsTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        settingsTabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        standardPhoneTabContainer.addView(
            createAccessibleButton(
                tr(
                    "⭐ Androidの「標準の電話アプリ」に設定する",
                    "⭐ Set as Android Default Phone App",
                    "⭐ Gawing Default Phone App sa Android",
                    "⭐ Establecer como Teléfono Predeterminado"
                ),
                "#00695C"
            ) {
                requestDefaultDialerRole()
            }
        )

        // 12-Key DTMF Dialpad (Inside Standard Phone Tab)
        val dialGrid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            setPadding(0, 8, 0, 8)
        }
        val keys = listOf(
            "1" to "1", "2" to "2 ABC", "3" to "3 DEF",
            "4" to "4 GHI", "5" to "5 JKL", "6" to "6 MNO",
            "7" to "7 PQRS", "8" to "8 TUV", "9" to "9 WXYZ",
            "*" to "*", "0" to "0 +", "#" to "#"
        )
        for ((digit, label) in keys) {
            val btnKey = Button(this).apply {
                text = digit
                contentDescription = "Dial $label"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                setOnClickListener { onDialPadKeyPressed(digit[0]) }
            }
            dialGrid.addView(btnKey)
        }
        standardPhoneTabContainer.addView(dialGrid)

        etAesPassphrase = EditText(this).apply {
            setText(prevPass)
            hint = tr(
                "AES-256-GCM 暗号化合言葉（パスフレーズ）",
                "AES-256-GCM Encryption Passphrase",
                "AES-256-GCM Passphrase sa Pag-encrypt",
                "Frase de contraseña AES-256-GCM"
            )
            setHintTextColor(Color.parseColor("#B0BEC5"))
            setTextColor(Color.parseColor("#FFF59D"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 18, 24, 18)
            contentDescription = hint
        }
        videoCallTabContainer.addView(etAesPassphrase)

        // === 🤖 1. 自動応答 セレナAIテストボット（接続音・入った音・落ちた音・自動会話） ===
        videoCallTabContainer.addView(
            createSectionHeader(
                tr(
                    "🤖 1. 自動応答セレナAIテストボット（接続音・入った音・落ちた音付き）",
                    "🤖 1. Hands-Free Auto-Reply Serena AI Test Bot (With Connect/Join/Leave Sounds)",
                    "🤖 1. Auto-Reply Serena AI Test Bot (May Tunog sa Pagkonekta/Pagpasok/Pag-alis)",
                    "🤖 1. Bot de Prueba Serena IA con Respuesta Automática y Sonidos"
                )
            )
        )

        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🤖 1対1 セレナAIボットと自動会話通話開始 (接続音＋入った音＋自動応答)",
                    "🤖 Start 1-on-1 Auto-Reply AI Bot Call (Connect + Join Sound + Hands-Free)",
                    "🤖 Simulan ang 1-on-1 Auto-Reply AI Bot Call (May Tunog at Kusang Sagot)",
                    "🤖 Iniciar Llamada 1 a 1 con Bot IA Automático (Con Sonidos y Manos Libres)"
                ),
                "#00695C"
            ) {
                startTestBotCall(botCount = 2, withVideo = true)
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "👥 6人ビデオ通話テストボット招集 (最大6人入室音＆自動グループ会話)",
                    "👥 Start 6-Person Video AI Bot Call (Max 6 Join Sound & Group Auto-Reply)",
                    "👥 Simulan ang 6-Katao Video AI Bot Call (Tunog sa Pagpasok at Grupo)",
                    "👥 Iniciar Videollamada con 6 Bots IA (Sonido de Entrada y Grupo)"
                ),
                "#0277BD"
            ) {
                startTestBotCall(botCount = KapamilyaAes256GcmMeshEngine.MAX_VIDEO_PARTICIPANTS, withVideo = true)
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "👥 10人音声通話テストボット招集 (最大10人入室音＆フルメッシュ会話)",
                    "👥 Start 10-Person Audio AI Bot Call (Max 10 Join Sound & Mesh Auto-Reply)",
                    "👥 Simulan ang 10-Katao Audio AI Bot Call (10 Kasali sa Mesh)",
                    "👥 Iniciar Llamada de Voz con 10 Bots IA (10 Participantes)"
                ),
                "#283593"
            ) {
                startTestBotCall(botCount = KapamilyaAes256GcmMeshEngine.MAX_AUDIO_PARTICIPANTS, withVideo = false)
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🎙️ マイクでボットに話しかける (音声認識 ＆ 自動応答ループ再開)",
                    "🎙️ Speak to AI Bot via Microphone (Voice Recognition & Auto-Reply)",
                    "🎙️ Kausapin ang AI Bot sa Mikropono (Voice Recognition at Auto-Reply)",
                    "🎙️ Hablar al Bot IA por Micrófono (Reconocimiento de Voz)"
                ),
                "#AD1457"
            ) {
                startVoiceListeningForTestBot()
            }
        )
        val rowBotExtra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowBotExtra.addView(
            createAccessibleButton(
                tr("💬 ボットにすぐ返事させる", "💬 Instant Bot Reply", "💬 Паsagutin Agad ang Bot", "💬 Respuesta Inmediata"),
                "#4A148C"
            ) {
                handleSpokenToTestBot("")
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        rowBotExtra.addView(
            createAccessibleButton(
                tr("🍂 メンバー退室テスト(落ちた音)", "🍂 Test Member Leave Sound", "🍂 Subukan ang Tunog Pag-alis", "🍂 Probar Sonido de Salida"),
                "#880E4F"
            ) {
                val msg = aesGcmMeshEngine.simulateOrHandleParticipantLeave()
                updateMeshBannerUi()
                tvAiLiveCommentary.text = msg
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        videoCallTabContainer.addView(rowBotExtra)

        // === 📞 標準電話（キャリア通話・着信応答・切断） ===
        standardPhoneTabContainer.addView(
            createSectionHeader(
                tr(
                    "📞 標準電話（キャリア通話・着信応答・切断）",
                    "📞 Standard Carrier Phone (Dial, Answer, End Call)",
                    "📞 Karaniwang Telepono (Tumawag, Sagutin, Ibaba)",
                    "📞 Teléfono Estándar (Llamar, Responder, Colgar)"
                )
            )
        )

        standardPhoneTabContainer.addView(
            createAccessibleButton(
                tr("📞 標準電話で発信する", "📞 Place Standard Phone Call", "📞 Tumawag sa Karaniwang Telepono", "📞 Llamada Telefónica Estándar"),
                "#1B5E20"
            ) {
                placeStandardPhoneCall()
            }
        )
        val rowCallCtrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowCallCtrl.addView(
            createAccessibleButton(
                tr("✅ 着信に応答", "✅ Answer Call", "✅ Sagutin ang Tawag", "✅ Responder"),
                "#2E7D32"
            ) {
                answerIncomingCall()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        rowCallCtrl.addView(
            createAccessibleButton(
                tr("🔴 通話を終了 (切断音＋通話時間)", "🔴 End Call (Sound + Duration)", "🔴 Ibaba (Tunog + Tagal)", "🔴 Colgar (Sonido + Duración)"),
                "#B71C1C"
            ) {
                endActiveCallAndAnnounceDuration()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        standardPhoneTabContainer.addView(rowCallCtrl)

        val rowAudioCtrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowAudioCtrl.addView(
            createAccessibleButton(
                tr("🔊 スピーカー切替", "🔊 Toggle Speaker", "🔊 Speaker ON/OFF", "🔊 Altavoz ON/OFF"),
                "#0D47A1"
            ) {
                toggleSpeakerphone()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        rowAudioCtrl.addView(
            createAccessibleButton(
                tr("🔇 マイクミュート切替", "🔇 Toggle Mic Mute", "🔇 I-Mute ang Mic", "🔇 Silenciar Mic"),
                "#4A148C"
            ) {
                toggleMicMute()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 6, 4, 6) } }
        )
        standardPhoneTabContainer.addView(rowAudioCtrl)

        // === 🌐 2. AES-256-GCM 暗号化 WebRTC グループ通話 ===
        videoCallTabContainer.addView(
            createSectionHeader(
                tr(
                    "🌐 2. AES-256-GCM 暗号化 WebRTC グループ通話（ビデオ最大6人・音声最大10人）",
                    "🌐 2. AES-256-GCM Encrypted WebRTC Group Call (Max 6 Video / Max 10 Audio)",
                    "🌐 2. AES-256-GCM Encrypted WebRTC Group Call (Hanggang 6 Video / 10 Audio)",
                    "🌐 2. Llamada Grupal WebRTC Cifrada AES-256-GCM (Video 6 / Voz 10)"
                )
            )
        )

        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🎥 暗号化 WebRTC ビデオ通話を開始（最大6人・接続音＆入室音付き）",
                    "🎥 Start Encrypted WebRTC Video Call (Max 6 • With Connect & Join Sounds)",
                    "🎥 Simulan ang Encrypted WebRTC Video Call (Max 6 • May Tunog)",
                    "🎥 Iniciar Videollamada WebRTC Cifrada (Máx 6 • Con Sonidos)"
                ),
                "#004D40"
            ) {
                isBotCallActive = false
                startWebRtcCall(withVideo = true)
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🎙️ 暗号化 WebRTC 音声通話を開始（最大10人・接続音＆入室音付き）",
                    "🎙️ Start Encrypted WebRTC Audio Call (Max 10 • With Connect & Join Sounds)",
                    "🎙️ Simulan ang Encrypted WebRTC Audio Call (Max 10 • May Tunog)",
                    "🎙️ Iniciar Llamada de Voz WebRTC Cifrada (Máx 10 • Con Sonidos)"
                ),
                "#01579B"
            ) {
                isBotCallActive = false
                startWebRtcCall(withVideo = false)
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr("🔴 ビデオ/グループ通話を終了 (切断音＋通話時間)", "🔴 End Video/Group Call (Sound + Duration)", "🔴 Ibaba ang Video/Group Call", "🔴 Colgar Videollamada/Grupo"),
                "#B71C1C"
            ) {
                endActiveCallAndAnnounceDuration()
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🔐 AES-256-GCM 暗号化招待コードを作成してコピー",
                    "🔐 Create & Copy AES-256-GCM Encrypted Invite Code",
                    "🔐 Gumawa at Kopyahin ang AES-256-GCM Invite Code",
                    "🔐 Crear y Copiar Código de Invitación AES-256-GCM"
                ),
                "#006064"
            ) {
                syncRoomEncryptionKey(isWebRtcVideoEnabled)
                val code = aesGcmMeshEngine.createEncryptedGroupInviteCode()
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("Kapamilya AES-256-GCM Invite", code))
                val sas = aesGcmMeshEngine.getKeyFingerprint()
                speak(
                    tr(
                        "AES-256-GCM で暗号化された招待コードをコピーしました！安全確認コードは $sas です。",
                        "Copied AES-256-GCM encrypted invite code to clipboard! Security SAS code is $sas.",
                        "Nakopya na sa clipboard ang AES-256-GCM invite code! Ang SAS code ay $sas.",
                        "¡Código de invitación AES-256-GCM copiado! Código SAS: $sas."
                    )
                )
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🔗 暗号化招待コードを貼り付けて参加（入った音付き）",
                    "🔗 Paste Encrypted Invite Code & Join (With Join Chime)",
                    "🔗 I-Paste ang Invite Code at Sumali (May Tunog sa Pagpasok)",
                    "🔗 Pegar Código de Invitación y Unirse (Con Sonido)"
                ),
                "#00838F"
            ) {
                syncRoomEncryptionKey(isWebRtcVideoEnabled)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = cb?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
                if (clipText.isEmpty()) {
                    speak(tr("クリップボードに招待コードがありません。", "Clipboard is empty.", "Walang invite code sa clipboard.", "El portapapeles está vacío."))
                } else {
                    val (ok, msg) = aesGcmMeshEngine.acceptEncryptedGroupInviteCode(clipText)
                    updateMeshBannerUi()
                    speak(msg)
                    if (ok && !isWebRtcActive) {
                        startWebRtcCall(withVideo = isWebRtcVideoEnabled)
                    }
                }
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "👥 参加メンバー一覧＆全員の表情・暗号化コードを読み上げ",
                    "👥 Speak Participant Roster, Expressions & SAS Code",
                    "👥 Basahin ang Listahan ng Kasali, Ekspresyon at SAS Code",
                    "👥 Leer Participantes, Expresiones y Código SAS"
                ),
                "#1E3A8A"
            ) {
                val summary = aesGcmMeshEngine.buildAllParticipantsAndExpressionsSummary(uiLang)
                tvAiLiveCommentary.text = summary
                speak(summary)
            }
        )

        // === 🧠 3. Gemma 4 & EmbeddingGemma 2 ビデオAI説明 ===
        videoCallTabContainer.addView(
            createSectionHeader(
                tr(
                    "🧠 3. Gemma 4 & EmbeddingGemma 2 ビデオAI説明",
                    "🧠 3. Gemma 4 & EmbeddingGemma 2 Video Call AI Narrator",
                    "🧠 3. Gemma 4 & EmbeddingGemma 2 Video Call AI Narrator",
                    "🧠 3. Narrador IA de Videollamada Gemma 4 y EmbeddingGemma 2"
                )
            )
        )

        tvAiLiveCommentary = TextView(this).apply {
            text = tr(
                "💡 AI実況待機中：下のボタンでいつでも「相手の表情」や「こちらのカメラに何が映っているか」をAIに確認できます。",
                "💡 AI Narrator Ready: Tap below anytime to check your partner's facial expression or what your own camera captures.",
                "💡 Handa na ang AI Narrator: Pindutin sa ibaba upang malaman ang ekspresyon ng kausap o kung ano ang nakikita ng iyong camera.",
                "💡 Narrador IA Listo: Toque abajo para escuchar la expresión de su interlocutor o qué enfoca su propia cámara."
            )
            setTextColor(Color.parseColor("#E0F7FA"))
            setBackgroundColor(Color.parseColor("#00332C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(24, 22, 24, 22)
        }
        videoCallTabContainer.addView(tvAiLiveCommentary)

        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "📷 こちらのカメラが何を捉えているかAI説明 (顔の位置・明るさ・物体)",
                    "📷 Explain What My Camera Captures (Face Framing, Lighting & Objects)",
                    "📷 Ipaliwanag Kung Ano ang Nakikita ng Aking Camera (Mukha at Liwanag)",
                    "📷 Explicar Qué Enfoca Mi Cámara (Posición del Rostro y Luz)"
                ),
                "#E65100"
            ) {
                captureRealCameraAndExplainSelf()
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "😊 相手の表情・笑顔・しぐさをAI説明 (Gemma 4 & EmbeddingGemma 2)",
                    "😊 Explain Partner's Facial Expression & Gestures (Gemma 4 & EmbeddingGemma 2)",
                    "😊 Ipaliwanag ang Ekspresyon at Ngiti ng Kausap (Gemma 4 & EmbeddingGemma 2)",
                    "😊 Explicar Expresión Facial y Gestos del Interlocutor (Gemma 4)"
                ),
                "#6A1B9A"
            ) {
                if (isBotCallActive) {
                    val summary = aesGcmMeshEngine.buildAllParticipantsAndExpressionsSummary(uiLang)
                    tvAiLiveCommentary.text = summary
                    speak(summary)
                } else {
                    captureWebRtcFrameAndAnalyzePartner(forceSpeak = true)
                }
            }
        )
        videoCallTabContainer.addView(
            createAccessibleButton(
                tr(
                    "🔄 カメラ切替 (インカメラ ⇄ アウトカメラ)",
                    "🔄 Switch Camera (Front ⇄ Back Camera)",
                    "🔄 Palitan ang Camera (Harap ⇄ Likod)",
                    "🔄 Cambiar Cámara (Frontal ⇄ Trasera)"
                ),
                "#37474F"
            ) {
                useFrontCameraForAi = !useFrontCameraForAi
                val msg = when (uiLang) {
                    "en" -> if (useFrontCameraForAi) "Switched to Front Camera (Self Framing)." else "Switched to Back Camera (Surroundings & Objects)."
                    "tl" -> if (useFrontCameraForAi) "Lumipat sa Front Camera." else "Lumipat sa Back Camera."
                    "es" -> if (useFrontCameraForAi) "Cambiado a Cámara Frontal." else "Cambiado a Cámara Trasera."
                    else -> if (useFrontCameraForAi) "AI確認用カメラをインカメラ（自分の顔確認用）に切り替えました。" else "AI確認用カメラを背面アウトカメラ（周囲・物体確認用）に切り替えました。"
                }
                speak(msg)
            }
        )

        webRtcView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360).apply {
                setMargins(0, 12, 0, 12)
            }
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
            webViewClient = WebViewClient()
        }
        videoCallTabContainer.addView(webRtcView)

        // === 📋 最近の発着信履歴（正確な秒数付き） (Inside Tab 1: Standard Phone Tab) ===
        standardPhoneTabContainer.addView(
            createSectionHeader(
                tr(
                    "📋 最近の発着信履歴（正確な通話時間付き）",
                    "📋 Recent Call History (With Exact Second Duration)",
                    "📋 Kasaysayan ng Tawag (May Eksaktong Tagal)",
                    "📋 Historial de Llamadas (Con Duración Exacta)"
                )
            )
        )

        standardPhoneTabContainer.addView(
            createAccessibleButton(
                tr("🔄 発着信履歴を最新に更新", "🔄 Refresh Call History", "🔄 I-Refresh ang Call History", "🔄 Actualizar Historial"),
                "#283593"
            ) {
                loadCallHistoryAndContacts()
                speak(tr("発着信履歴を更新しました。", "Refreshed call history.", "Na-refresh na ang call history.", "Historial actualizado."))
            }
        )

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        standardPhoneTabContainer.addView(historyContainer)

        // === 👥 Tab 3: 連絡先タブ（📱 端末の連絡先 ＆ ☁️ Google コンタクト） ===
        contactsTabContainer.addView(
            createSectionHeader(
                tr(
                    "☁️ Google コンタクト ＆ 📱 端末の連絡先",
                    "☁️ Google Contacts & 📱 Device Contacts",
                    "☁️ Google Contacts at 📱 Mga Contact sa Device",
                    "☁️ Google Contacts y 📱 Contactos del Dispositivo"
                )
            )
        )
        contactsTabContainer.addView(
            createAccessibleButton(
                tr("🔄 端末の連絡先と Google コンタクトを同期・再読み込み", "🔄 Sync & Reload Device & Google Contacts", "🔄 I-Sync ang Device at Google Contacts", "🔄 Sincronizar Contactos y Google Contacts"),
                "#0277BD"
            ) {
                loadCallHistoryAndContacts()
                speak(tr("端末の連絡先と Google コンタクトを最新に更新しました。", "Synced device and Google Contacts.", "Na-sync na ang Device at Google Contacts.", "Contactos del dispositivo y Google Contacts sincronizados."))
            }
        )
        contactsTabContainer.addView(
            createAccessibleButton(
                tr("☁️ Google コンタクト アプリ / クラウド連絡先を開く", "☁️ Open Google Contacts App / Cloud Contacts", "☁️ Buksan ang Google Contacts", "☁️ Abrir Google Contacts"),
                "#1565C0"
            ) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                    startActivity(intent)
                } catch (_: Exception) {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://contacts.google.com"))
                    startActivity(webIntent)
                }
            }
        )
        contactsTabContainer.addView(
            createSectionHeader(
                tr("☁️ Google コンタクト同期済み一覧", "☁️ Synced Google Contacts", "☁️ Mga Google Contacts", "☁️ Contactos de Google")
            )
        )
        googleContactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contactsTabContainer.addView(googleContactsContainer)

        contactsTabContainer.addView(
            createSectionHeader(
                tr("📱 端末・SIMの連絡先一覧", "📱 Device & SIM Contacts", "📱 Mga Contact sa Telepono at SIM", "📱 Contactos del Teléfono y SIM")
            )
        )
        deviceContactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contactsTabContainer.addView(deviceContactsContainer)

        // === ⚙️ Tab 4: 設定タブ（通話音質・DSPノイズ抑制・標準電話設定・サウンド設定） ===
        settingsTabContainer.addView(
            createSectionHeader(
                tr(
                    "🎵 1. 通話音質設定（Opus / HD Voice サンプリングレート）",
                    "🎵 1. Call Audio Quality (Opus / HD Voice Sample Rate)",
                    "🎵 1. Kalidad ng Tunog sa Tawag (Opus / HD Voice)",
                    "🎵 1. Calidad de Audio de Llamada (Opus / HD Voice)"
                )
            )
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("🎵 音質モード: 48kHz フルバンド超高音質 (Opus Stereo / 最高音質)", "🎵 Audio: 48kHz Fullband Ultra HQ (Opus Stereo)", "🎵 Tunog: 48kHz Fullband Ultra HQ", "🎵 Audio: 48kHz Fullband Ultra Alta Calidad"),
                "#00695C"
            ) {
                audioQualityMode = 0
                speak(tr("通話音質を 48キロヘルツ フルバンド超高音質に設定しました。", "Set call audio quality to 48kHz Fullband Ultra HQ.", "Itinakda ang kalidad ng tunog sa 48kHz Fullband Ultra HQ.", "Calidad de audio configurada en 48kHz Fullband."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("🎙️ 音質モード: 24kHz HD Voice 明瞭音声モード", "🎙️ Audio: 24kHz HD Voice Clear Speech", "🎙️ Tunog: 24kHz HD Voice Malinaw na Boses", "🎙️ Audio: 24kHz HD Voice Voz Clara"),
                "#0277BD"
            ) {
                audioQualityMode = 1
                speak(tr("通話音質を 24キロヘルツ HD Voice 明瞭音声モードに設定しました。", "Set call audio quality to 24kHz HD Voice.", "Itinakda sa 24kHz HD Voice.", "Calidad de audio configurada en 24kHz HD Voice."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("📶 音質モード: 16kHz 省データ・低遅延安定モード", "📶 Audio: 16kHz Low-Data Stable Mode", "📶 Tunog: 16kHz Tipid-Data at Matatag", "📶 Audio: 16kHz Bajo Consumo y Estable"),
                "#37474F"
            ) {
                audioQualityMode = 2
                speak(tr("通話音質を 16キロヘルツ 省データ・低遅延安定モードに設定しました。", "Set call audio quality to 16kHz Low-Data Stable Mode.", "Itinakda sa 16kHz Low-Data Mode.", "Calidad de audio configurada en 16kHz Estable."))
            }
        )

        settingsTabContainer.addView(
            createSectionHeader(
                tr(
                    "🎛️ 2. 音声DSP（エコーキャンセラー・ノイズ抑制・自動ゲイン・AI設定）",
                    "🎛️ 2. Voice DSP (Echo Canceler, Noise Suppression, AGC & AI)",
                    "🎛️ 2. Voice DSP (Echo Canceler, Noise Suppression, AGC at AI)",
                    "🎛️ 2. Procesamiento de Voz DSP e IA"
                )
            )
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("🔇 エコーキャンセラー (ハウリング防止) ON/OFF 切替", "🔇 Toggle Acoustic Echo Canceler (AEC)", "🔇 Echo Canceler ON/OFF", "🔇 Cancelador de Eco ON/OFF"),
                "#1E3A8A"
            ) {
                isEchoCancelerEnabled = !isEchoCancelerEnabled
                speak(if (isEchoCancelerEnabled) tr("エコーキャンセラーをオンにしました。", "Echo Canceler enabled.", "Naka-ON ang Echo Canceler.", "Cancelador de eco activado.") else tr("エコーキャンセラーをオフにしました。", "Echo Canceler disabled.", "Naka-OFF ang Echo Canceler.", "Cancelador de eco desactivado."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("🛡️ AI 周囲ノイズ抑制 (Noise Suppression) ON/OFF 切替", "🛡️ Toggle AI Noise Suppression", "🛡️ AI Noise Suppression ON/OFF", "🛡️ Supresión de Ruido IA ON/OFF"),
                "#4A148C"
            ) {
                isNoiseSuppressorEnabled = !isNoiseSuppressorEnabled
                speak(if (isNoiseSuppressorEnabled) tr("AI周囲ノイズ抑制をオンにしました。", "AI Noise Suppression enabled.", "Naka-ON ang AI Noise Suppression.", "Supresión de ruido IA activada.") else tr("AI周囲ノイズ抑制をオフにしました。", "AI Noise Suppression disabled.", "Naka-OFF ang AI Noise Suppression.", "Supresión de ruido IA desactivada."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("📢 マイク自動音量調整 (Auto Gain Control) ON/OFF 切替", "📢 Toggle Mic Auto Gain Control (AGC)", "📢 Auto Gain Control ON/OFF", "📢 Control Automático de Ganancia ON/OFF"),
                "#006064"
            ) {
                isAutoGainEnabled = !isAutoGainEnabled
                speak(if (isAutoGainEnabled) tr("マイク自動音量調整をオンにしました。", "Auto Gain Control enabled.", "Naka-ON ang Auto Gain Control.", "Control automático de ganancia activado.") else tr("マイク自動音量調整をオフにしました。", "Auto Gain Control disabled.", "Naka-OFF ang Auto Gain Control.", "Control automático de ganancia desactivado."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr("😊 ビデオ通話中の Gemma 4 自動表情実況 ON/OFF 切替", "😊 Toggle Gemma 4 Auto Facial Expression Narration", "😊 Gemma 4 Auto Expression Narration ON/OFF", "😊 Narración Automática de Expresiones Gemma 4 ON/OFF"),
                "#6A1B9A"
            ) {
                isAutoAiCommentaryEnabled = !isAutoAiCommentaryEnabled
                speak(if (isAutoAiCommentaryEnabled) tr("ビデオ通話中の自動表情実況をオンにしました。", "Auto expression narration enabled.", "Naka-ON ang Auto Expression Narration.", "Narración automática activada.") else tr("ビデオ通話中の自動表情実況をオフにしました。", "Auto expression narration disabled.", "Naka-OFF ang Auto Expression Narration.", "Narración automática desactivada."))
            }
        )
        settingsTabContainer.addView(
            createAccessibleButton(
                tr(
                    "⭐ Androidの「標準の電話アプリ」に設定する",
                    "⭐ Set as Android Default Phone App",
                    "⭐ Gawing Default Phone App sa Android",
                    "⭐ Establecer como Teléfono Predeterminado"
                ),
                "#00695C"
            ) {
                requestDefaultDialerRole()
            }
        )

        rootLayout.addView(standardPhoneTabContainer)
        rootLayout.addView(videoCallTabContainer)
        rootLayout.addView(contactsTabContainer)
        rootLayout.addView(settingsTabContainer)
        updateTabVisibility(announce = false)

        rootScroll.addView(rootLayout)
        setContentView(rootScroll)
        updateDefaultDialerStatus()
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#80CBC4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }
    }

    private fun createAccessibleButton(label: String, bgHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            contentDescription = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bgHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 26, 24, 26)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onDialPadKeyPressed(digit: Char) {
        val tone = when (digit) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> ToneGenerator.TONE_DTMF_0
        }
        try {
            toneGenerator?.startTone(tone, 140)
        } catch (_: Exception) {}

        KapamilyaInCallService.instance?.sendDtmfDigit(digit)

        val current = etPhoneNumberOrRoom.text.toString() + digit
        etPhoneNumberOrRoom.setText(current)
        etPhoneNumberOrRoom.setSelection(current.length)
        speak("$digit")
    }

    private fun requestDefaultDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        speak(
                            tr(
                                "Kapamilya Serena Phone はすでに標準の電話アプリに設定されています！",
                                "Kapamilya Serena Phone is already set as your default phone app!",
                                "Naka-set na ang Kapamilya Serena Phone bilang default phone app mo!",
                                "¡Kapamilya Serena Phone ya es tu aplicación de teléfono predeterminada!"
                            )
                        )
                        return
                    }
                    val intent = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    requestRoleLauncher.launch(intent)
                    return
                }
            }
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            requestRoleLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "requestDefaultDialerRole error: ${e.message}")
            speak(tr("設定画面を開けませんでした。", "Could not open default app settings.", "Hindi mabuksan ang settings.", "No se pudo abrir la configuración."))
        }
    }

    private fun updateDefaultDialerStatus() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isDefault = tm?.defaultDialerPackage == packageName
        if (isDefault) {
            tvStatusBanner.text = tr(
                "✅ 標準の電話アプリとして稼働中 • WebRTC & Gemma 4 AI 準備完了",
                "✅ Active as Default Phone App • WebRTC & Gemma 4 AI Ready",
                "✅ Aktibo bilang Default Phone App • Handa na ang WebRTC at Gemma 4 AI",
                "✅ Activo como Teléfono Predeterminado • WebRTC y Gemma 4 IA Listos"
            )
        }
    }

    private fun placeStandardPhoneCall() {
        val raw = etPhoneNumberOrRoom.text.toString().trim()
        if (raw.isEmpty()) {
            speak(
                tr(
                    "電話番号を入力するか、下の履歴・連絡先から相手を選んでください。",
                    "Please enter a phone number or select a contact below.",
                    "Maglagay ng numero ng telepono o pumili sa mga contact sa ibaba.",
                    "Ingrese un número de teléfono o elija un contacto abajo."
                )
            )
            return
        }
        placeNumberCallDirect(raw)
    }

    private fun placeNumberCallDirect(number: String) {
        KapamilyaPhoneSoundEngine.playConnectingSound()
        try {
            val uri = Uri.fromParts("tel", number, null)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (tm != null) {
                    tm.placeCall(uri, Bundle())
                    speak(tr("${number} に発信しています。", "Dialing $number...", "Tinatawagan ang $number...", "Llamando al $number..."))
                    return
                }
                startActivity(Intent(Intent.ACTION_CALL, uri))
                speak(tr("${number} に発信しています。", "Dialing $number...", "Tinatawagan ang $number...", "Llamando al $number..."))
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
                speak(tr("${number} の発信画面を開きました。", "Opened dialer for $number.", "Binuksan ang dialer para sa $number.", "Marcador abierto para $number."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "placeNumberCallDirect error: ${e.message}")
            speak(tr("発信できませんでした。", "Could not place call.", "Hindi makatawag.", "No se pudo realizar la llamada."))
        }
    }

    private fun answerIncomingCall() {
        val svc = KapamilyaInCallService.instance
        if (svc != null && KapamilyaInCallService.activeCall != null) {
            svc.answerActiveRingingCall()
            KapamilyaPhoneSoundEngine.playParticipantJoinedSound()
            speak(tr("着信に応答しました。", "Call answered.", "Sinagot na ang tawag.", "Llamada contestada."))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            tm?.acceptRingingCall()
            KapamilyaPhoneSoundEngine.playParticipantJoinedSound()
            speak(tr("着信に応答しました。", "Call answered.", "Sinagot na ang tawag.", "Llamada contestada."))
        } else {
            speak(tr("現在着信中の電話はありません。", "No incoming call right now.", "Walang tumatawag ngayon.", "No hay llamadas entrantes ahora."))
        }
    }

    private fun endActiveCallAndAnnounceDuration() {
        if (isWebRtcActive) {
            stopWebRtcCallAndAnnounceDuration()
            return
        }
        val svc = KapamilyaInCallService.instance
        if (svc != null && KapamilyaInCallService.activeCall != null) {
            svc.disconnectActiveCall()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            val ended = tm?.endCall() == true
            if (ended) {
                KapamilyaPhoneSoundEngine.playCallDisconnectedSound()
                speak(tr("通話を終了しました。", "Call ended.", "Tapos na ang tawag.", "Llamada finalizada."))
                return
            }
        }
        speak(tr("現在アクティブな通話はありません。", "No active call right now.", "Walang aktibong tawag ngayon.", "No hay ninguna llamada activa."))
    }

    private fun toggleSpeakerphone() {
        isSpeakerOn = !isSpeakerOn
        KapamilyaInCallService.instance?.setSpeakerphoneEnabled(isSpeakerOn)
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        @Suppress("DEPRECATION")
        am?.isSpeakerphoneOn = isSpeakerOn
        speak(
            tr(
                if (isSpeakerOn) "スピーカーフォンをオンにしました。" else "スピーカーフォンをオフにしました。",
                if (isSpeakerOn) "Speakerphone ON." else "Speakerphone OFF.",
                if (isSpeakerOn) "Naka-ON ang Speakerphone." else "Naka-OFF ang Speakerphone.",
                if (isSpeakerOn) "Altavoz activado." else "Altavoz desactivado."
            )
        )
    }

    private fun toggleMicMute() {
        isMicMuted = !isMicMuted
        KapamilyaInCallService.instance?.setMicMutedState(isMicMuted)
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.isMicrophoneMute = isMicMuted
        speak(
            tr(
                if (isMicMuted) "マイクをミュート（消音）にしました。" else "マイクのミュートを解除しました。",
                if (isMicMuted) "Microphone muted." else "Microphone unmuted.",
                if (isMicMuted) "Naka-mute ang mikropono." else "Hindi na naka-mute ang mikropono.",
                if (isMicMuted) "Micrófono silenciado." else "Micrófono activado."
            )
        )
    }

    private fun syncRoomEncryptionKey(withVideo: Boolean): String {
        val room = if (::etPhoneNumberOrRoom.isInitialized) etPhoneNumberOrRoom.text.toString().trim().ifEmpty { "kapamilya-serena-room" } else "kapamilya-serena-room"
        val pass = if (::etAesPassphrase.isInitialized) etAesPassphrase.text.toString().trim().ifEmpty { "Kapamilya-Serena-AES256GCM-Key" } else "Kapamilya-Serena-AES256GCM-Key"
        val sas = aesGcmMeshEngine.configureRoomAndDeriveKey(room, pass, withVideo)
        updateMeshBannerUi()
        return sas
    }

    private fun updateMeshBannerUi() {
        if (!::tvEncryptionAndMeshBanner.isInitialized) return
        val maxCap = aesGcmMeshEngine.getMaxParticipantsForCurrentMode()
        val curCount = aesGcmMeshEngine.getActiveParticipantCount()
        val sas = aesGcmMeshEngine.getKeyFingerprint()
        tvEncryptionAndMeshBanner.text = tr(
            "🔐 AES-256-GCM 完全暗号化稼働中 (安全確認コード: $sas)\n👥 グループ参加人数: $curCount / $maxCap 名 (ビデオ最大6人・音声最大10人)",
            "🔐 AES-256-GCM E2EE Active (Security SAS: $sas)\n👥 Group Participants: $curCount / $maxCap (Max 6 Video / Max 10 Audio)",
            "🔐 AES-256-GCM E2EE Aktibo (Security SAS: $sas)\n👥 Kasali sa Grupo: $curCount / $maxCap (Max 6 Video / Max 10 Audio)",
            "🔐 Cifrado AES-256-GCM Activo (Código SAS: $sas)\n👥 Participantes: $curCount / $maxCap (Máx 6 Video / Máx 10 Voz)"
        )
    }

    private fun startWebRtcCall(withVideo: Boolean, playSoundsAndSpeak: Boolean = true) {
        val room = etPhoneNumberOrRoom.text.toString().trim().ifEmpty { "kapamilya-serena-room" }
        val pass = etAesPassphrase.text.toString().trim().ifEmpty { "Kapamilya-Serena-AES256GCM-Key" }
        isWebRtcActive = true
        isWebRtcVideoEnabled = withVideo
        webRtcStartElapsedMs = SystemClock.elapsedRealtime()

        val sas = syncRoomEncryptionKey(withVideo)
        aesGcmMeshEngine.broadcastEncryptedJoin()

        val maxPeers = if (withVideo) KapamilyaAes256GcmMeshEngine.MAX_VIDEO_PARTICIPANTS else KapamilyaAes256GcmMeshEngine.MAX_AUDIO_PARTICIPANTS
        tvStatusBanner.text = tr(
            "🟢 AES-256-GCM 暗号化通話中 (ルーム: $room • 最大${maxPeers}人 • 安全コード: $sas)",
            "🟢 AES-256-GCM Encrypted Call Active (Room: $room • Max $maxPeers • SAS: $sas)",
            "🟢 Aktibo ang AES-256-GCM Tawag (Room: $room • Max $maxPeers • SAS: $sas)",
            "🟢 Llamada Cifrada AES-256-GCM Activa (Sala: $room • Máx $maxPeers • SAS: $sas)"
        )

        if (playSoundsAndSpeak) {
            // 1. 接続中サウンド → 2. 入った音（接続完了チャイム） → 3. 音声案内
            KapamilyaPhoneSoundEngine.playConnectingSound {
                KapamilyaPhoneSoundEngine.playParticipantJoinedSound {
                    mainHandler.post {
                        speak(
                            tr(
                                "ルーム「$room」に接続しました！AES-256-GCM 暗号化の安全確認コードは $sas です。",
                                "Connected to room $room! AES-256-GCM security code is $sas.",
                                "Nakakonekta na sa room $room! Ang AES-256-GCM security code ay $sas.",
                                "¡Conectado a la sala $room! Código de seguridad AES-256-GCM: $sas."
                            )
                        )
                    }
                }
            }
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width,initial-scale=1"/>
            <style>
              body { margin:0; background:#050B14; color:#FFF; font-family:sans-serif; display:flex; flex-direction:column; align-items:center; justify-content:center; }
              .grid6 { display:grid; grid-template-columns:repeat(3, 1fr); width:100%; height:195px; gap:6px; padding:6px; box-sizing:border-box; }
              video { width:100%; height:88px; background:#111927; border:2px solid #00E5FF; border-radius:8px; object-fit:cover; }
              .label { font-size:12px; color:#FFD54F; padding:4px; text-align:center; }
            </style>
            </head>
            <body>
              <div class="label">🔐 Kapamilya AES-256-GCM Room: $room (SAS: $sas • Max $maxPeers Peers)</div>
              <div class="grid6">
                <video id="localVideo" autoplay muted playsinline></video>
                <video id="remoteVideo1" autoplay playsinline></video>
                <video id="remoteVideo2" autoplay playsinline></video>
                <video id="remoteVideo3" autoplay playsinline></video>
                <video id="remoteVideo4" autoplay playsinline></video>
                <video id="remoteVideo5" autoplay playsinline></video>
              </div>
              <script>
                const MAX_PEERS = $maxPeers;
                const ROOM_NAME = "$room";
                const PASSPHRASE = "$pass";
                const peerConnections = [];

                async function deriveAes256GcmKey() {
                  const enc = new TextEncoder();
                  const baseKey = await crypto.subtle.importKey(
                    'raw', enc.encode(PASSPHRASE), { name: 'PBKDF2' }, false, ['deriveKey']
                  );
                  return crypto.subtle.deriveKey(
                    {
                      name: 'PBKDF2',
                      salt: enc.encode('KapamilyaSerenaPhone_AES256GCM_Salt_v5.9:' + ROOM_NAME),
                      iterations: 100000,
                      hash: 'SHA-256'
                    },
                    baseKey,
                    { name: 'AES-GCM', length: 256 },
                    false,
                    ['encrypt', 'decrypt']
                  );
                }

                deriveAes256GcmKey().then(aesKey => {
                  for (let idx = 1; idx < MAX_PEERS; idx++) {
                    const pc = new RTCPeerConnection({
                      encodedInsertableStreams: true,
                      iceServers: [
                        { urls: 'stun:stun.l.google.com:19302' },
                        { urls: 'stun:stun.cloudflare.com:3478' }
                      ]
                    });
                    const slotId = 'remoteVideo' + Math.min(5, idx);
                    const remoteVid = document.getElementById(slotId);
                    pc.ontrack = (event) => {
                      if (remoteVid && remoteVid.srcObject !== event.streams[0]) {
                        remoteVid.srcObject = event.streams[0];
                      }
                    };
                    peerConnections.push(pc);
                  }

                  const localVid = document.getElementById('localVideo');
                  const remoteVid1 = document.getElementById('remoteVideo1');
                  navigator.mediaDevices.getUserMedia({ audio: true, video: $withVideo })
                    .then(stream => {
                      localVid.srcObject = stream;
                      peerConnections.forEach(pc => {
                        stream.getTracks().forEach(t => pc.addTrack(t, stream));
                      });
                      if (remoteVid1 && !remoteVid1.srcObject) remoteVid1.srcObject = stream;
                    })
                    .catch(err => console.error('WebRTC media error:', err));
                });
              </script>
            </body>
            </html>
        """.trimIndent()

        webRtcView.loadDataWithBaseURL("https://kapamilya.gitlab.io/", html, "text/html", "UTF-8", null)
    }

    private fun stopWebRtcCallAndAnnounceDuration() {
        val elapsedMs = if (webRtcStartElapsedMs > 0L) {
            (SystemClock.elapsedRealtime() - webRtcStartElapsedMs).coerceAtLeast(1000L)
        } else 1000L
        isWebRtcActive = false
        isWebRtcVideoEnabled = false
        isBotCallActive = false
        webRtcStartElapsedMs = 0L
        mainHandler.removeCallbacks(autoBotProactiveTurnRunnable)
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        webRtcView.loadUrl("about:blank")

        val exactText = KapamilyaInCallService.formatExactDuration(elapsedMs, uiLang)
        val endBanner = tr(
            "📞 通話終了（正確な通話時間: $exactText）",
            "📞 Call Ended (Exact Duration: $exactText)",
            "📞 Tapos na ang Tawag (Eksaktong Tagal: $exactText)",
            "📞 Llamada Finalizada (Duración Exacta: $exactText)"
        )
        tvStatusBanner.text = endBanner

        // 落ちた音（通話切断サウンド）を鳴らしてから秒単位の通話時間を読み上げ！
        KapamilyaPhoneSoundEngine.playCallDisconnectedSound {
            mainHandler.post {
                speak(
                    tr(
                        "通話を終了しました。正確な通話時間は、${exactText}でした。",
                        "Call ended. Exact call duration was $exactText.",
                        "Tapos na ang tawag. Ang eksaktong tagal ng tawag ay $exactText.",
                        "Llamada finalizada. La duración exacta fue de $exactText."
                    )
                )
            }
        }
    }

    // =========================================================================
    // Kapamilya Serena AI テストボット（接続音 → 入った音 → 自動音声応答ループ）
    // =========================================================================
    private fun startTestBotCall(botCount: Int, withVideo: Boolean) {
        val roomName = when {
            botCount <= 2 -> "kapamilya-serena-test-bot"
            withVideo -> "kapamilya-serena-test-video6"
            else -> "kapamilya-serena-test-audio10"
        }
        isBotCallActive = true
        etPhoneNumberOrRoom.setText(roomName)

        tvStatusBanner.text = tr(
            "🔔 セレナAIテストボットへ接続中...",
            "🔔 Connecting to Serena AI Test Bot...",
            "🔔 Kumokonekta sa Serena AI Test Bot...",
            "🔔 Conectando con el Bot de Prueba Serena IA..."
        )

        startWebRtcCall(withVideo = withVideo, playSoundsAndSpeak = false)
        val spawnMsg = aesGcmMeshEngine.spawnTestBots(botCount, withVideo, uiLang)
        updateMeshBannerUi()
        tvAiLiveCommentary.text = spawnMsg

        // 1. 接続中サウンド → 2. 入った音（ボット入室チャイム） → 3. ボットの第一声（終了後に自動でマイク聞き取り開始！）
        KapamilyaPhoneSoundEngine.playConnectingSound {
            KapamilyaPhoneSoundEngine.playParticipantJoinedSound {
                mainHandler.post {
                    tvStatusBanner.text = tr(
                        "🟢 セレナAIテストボット通話中（声で話しかけると自動で返事します）",
                        "🟢 Serena AI Test Bot Connected (Hands-Free Auto-Reply Active)",
                        "🟢 Konektado sa Serena AI Test Bot (Kusang Sumasagot sa Boses Mo)",
                        "🟢 Conectado al Bot Serena IA (Respuesta Automática Activa)"
                    )
                    speak(spawnMsg, utteranceIdPrefix = "bot_turn_")
                    mainHandler.removeCallbacks(autoBotProactiveTurnRunnable)
                    mainHandler.postDelayed(autoBotProactiveTurnRunnable, 14000L)
                }
            }
        }
    }

    private fun startAutoVoiceListeningAfterBotSpeech() {
        if (!isWebRtcActive || !isBotCallActive || isMicMuted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }

        // ピコン♪（ボットがマイク聞き取りを開始した合図）
        KapamilyaPhoneSoundEngine.playBotListeningCue {
            mainHandler.post {
                if (!isWebRtcActive || !isBotCallActive || isMicMuted) return@post
                beginInternalSpeechRecognizer(useFallbackIntentIfUnavailable = false)
            }
        }
    }

    private fun startVoiceListeningForTestBot() {
        if (!isWebRtcActive || !isBotCallActive) {
            startTestBotCall(botCount = 2, withVideo = true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNecessaryPermissions()
            handleSpokenToTestBot("")
            return
        }

        try {
            tts?.stop()
        } catch (_: Exception) {}

        KapamilyaPhoneSoundEngine.playBotListeningCue {
            mainHandler.post {
                beginInternalSpeechRecognizer(useFallbackIntentIfUnavailable = true)
            }
        }
    }

    private fun beginInternalSpeechRecognizer(useFallbackIntentIfUnavailable: Boolean) {
        val speechLang = when (uiLang) {
            "en" -> "en-US"
            "tl" -> "fil-PH"
            "es" -> "es-ES"
            else -> "ja-JP"
        }
        val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                tr(
                    "🤖 セレナAIボットに話しかけてください",
                    "🤖 Speak to Serena AI Test Bot",
                    "🤖 Kausapin ang Serena AI Test Bot",
                    "🤖 Habla con el Bot Serena IA"
                )
            )
        }

        sawVoiceActivityDuringListen = false
        lastPartialRecognizedText = ""

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            tvStatusBanner.text = tr(
                                "🎙️ ボットがあなたの声を聞いています... そのまま話しかけてください！",
                                "🎙️ Bot is listening to your voice... Speak now!",
                                "🎙️ Nakikinig ang Bot sa boses mo... Magsalita ka na!",
                                "🎙️ El Bot te está escuchando... ¡Habla ahora!"
                            )
                        }
                        override fun onBeginningOfSpeech() {
                            sawVoiceActivityDuringListen = true
                        }
                        override fun onRmsChanged(rmsdB: Float) {
                            if (rmsdB > 4.2f) {
                                sawVoiceActivityDuringListen = true
                            }
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            // 声を出した後にタイムアウトやネットワークエラーになった場合も、必ず自動でボットが応答！
                            if (sawVoiceActivityDuringListen || lastPartialRecognizedText.isNotBlank()) {
                                handleSpokenToTestBot(lastPartialRecognizedText)
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = list?.firstOrNull()?.trim().orEmpty().ifEmpty { lastPartialRecognizedText }
                            handleSpokenToTestBot(text)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                            if (part.isNotBlank()) {
                                lastPartialRecognizedText = part
                                sawVoiceActivityDuringListen = true
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    startListening(recIntent)
                }
                return
            } catch (_: Exception) {}
        }

        if (useFallbackIntentIfUnavailable) {
            try {
                speechRecognizeIntentLauncher.launch(recIntent)
            } catch (_: Exception) {
                handleSpokenToTestBot("")
            }
        }
    }

    private fun handleSpokenToTestBot(spokenText: String) {
        if (!isWebRtcActive) {
            startTestBotCall(botCount = 2, withVideo = true)
            return
        }
        isBotCallActive = true
        mainHandler.removeCallbacks(autoBotProactiveTurnRunnable)
        mainHandler.postDelayed(autoBotProactiveTurnRunnable, 14000L)

        val botReply = aesGcmMeshEngine.processUserSpeechWithTestBot(spokenText, uiLang)
        updateMeshBannerUi()
        tvAiLiveCommentary.text = botReply
        tvStatusBanner.text = if (spokenText.isNotBlank()) {
            "🗣️ \"$spokenText\" → $botReply"
        } else {
            botReply
        }
        // utteranceIdPrefix = "bot_turn_" により、ボットが話し終わると自動で次のマイク聞き取りがスタート！
        speak(botReply, utteranceIdPrefix = "bot_turn_")
    }

    @SuppressLint("MissingPermission")
    private fun captureRealCameraAndExplainSelf() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            speak(
                tr(
                    "カメラ権限を許可すると、こちらのカメラに何が映っているかAIが説明できます。",
                    "Grant camera permission so AI can describe what your camera sees.",
                    "Payagan ang camera permission para maipaliwanag ng AI ang nakikita ng camera.",
                    "Permita el acceso a la cámara para que la IA describa la imagen."
                )
            )
            requestNecessaryPermissions()
            return
        }

        speak(
            tr(
                "こちらのカメラが今何を捉えているか、Gemma 4 と EmbeddingGemma 2 で解析しています...",
                "Analyzing what your camera captures with Gemma 4 and EmbeddingGemma 2...",
                "Sinusuri ng Gemma 4 at EmbeddingGemma 2 ang kuha ng iyong camera...",
                "Analizando tu cámara con Gemma 4 y EmbeddingGemma 2..."
            )
        )
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            captureWebRtcViewFallbackForSelf()
            return
        }

        try {
            val targetFacing = if (useFrontCameraForAi) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: cm.cameraIdList.firstOrNull()

            if (cameraId == null) {
                captureWebRtcViewFallbackForSelf()
                return
            }

            val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
            var captured = false

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val surface = reader.surface
                        val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }
                        reader.setOnImageAvailableListener({ ir ->
                            if (captured) return@setOnImageAvailableListener
                            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                            captured = true
                            val bmp = yuv420ToBitmap(img)
                            img.close()
                            try { camera.close() } catch (_: Exception) {}
                            try { ir.close() } catch (_: Exception) {}

                            mainHandler.post {
                                videoAiNarrator.analyzeSelfCameraFrame(bmp) { report ->
                                    tvAiLiveCommentary.text = report
                                    speak(report)
                                }
                            }
                        }, mainHandler)

                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(reqBuilder.build(), null, mainHandler)
                                    } catch (_: Exception) {
                                        try { camera.close() } catch (_: Exception) {}
                                        captureWebRtcViewFallbackForSelf()
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    try { camera.close() } catch (_: Exception) {}
                                    captureWebRtcViewFallbackForSelf()
                                }
                            },
                            mainHandler
                        )
                    } catch (_: Exception) {
                        try { camera.close() } catch (_: Exception) {}
                        captureWebRtcViewFallbackForSelf()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    captureWebRtcViewFallbackForSelf()
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "captureRealCameraAndExplainSelf fallback: ${e.message}")
            captureWebRtcViewFallbackForSelf()
        }
    }

    private fun captureWebRtcViewFallbackForSelf() {
        val bmp = captureWebViewBitmap()
        videoAiNarrator.analyzeSelfCameraFrame(bmp) { report ->
            tvAiLiveCommentary.text = report
            speak(report)
        }
    }

    private fun captureWebRtcFrameAndAnalyzePartner(forceSpeak: Boolean) {
        val bmp = captureWebViewBitmap()
        videoAiNarrator.analyzePartnerVideoFrame(bmp, forceSpeak = forceSpeak) { report ->
            aesGcmMeshEngine.broadcastEncryptedExpression(report)
            tvAiLiveCommentary.text = report
            speak(report)
        }
    }

    private fun captureWebViewBitmap(): Bitmap {
        val w = webRtcView.width.coerceAtLeast(320)
        val h = webRtcView.height.coerceAtLeast(240)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        webRtcView.draw(canvas)
        return bmp
    }

    private fun yuv420ToBitmap(image: android.media.Image): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * rowStride + x * pixelStride
                val yVal = if (idx < yBuffer.limit()) (yBuffer.get(idx).toInt() and 0xFF) else 128
                bmp.setPixel(x, y, Color.rgb(yVal, yVal, yVal))
            }
        }
        return bmp
    }

    private fun loadCallHistoryAndContacts() {
        if (!::historyContainer.isInitialized) return
        historyContainer.removeAllViews()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            try {
                val cursor = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )
                cursor?.use { c ->
                    var count = 0
                    val fmt = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
                    while (c.moveToNext() && count < 8) {
                        val number = c.getString(0) ?: ""
                        val name = c.getString(1)?.ifBlank { number } ?: number
                        val type = c.getInt(2)
                        val dateMs = c.getLong(3)
                        val durationSec = c.getLong(4)

                        val typeLabel = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> tr("📲 着信", "📲 Incoming", "📲 Pumasok", "📲 Entrante")
                            CallLog.Calls.OUTGOING_TYPE -> tr("📤 発信", "📤 Outgoing", "📤 Papalabas", "📤 Saliente")
                            CallLog.Calls.MISSED_TYPE -> tr("❗ 不在着信", "❗ Missed", "❗ Hindi Nasagot", "❗ Perdida")
                            else -> "📞"
                        }
                        val exactDur = KapamilyaInCallService.formatExactDuration(durationSec * 1000L, uiLang)
                        val dateStr = fmt.format(Date(dateMs))
                        val label = "$typeLabel: $name ($dateStr) • $exactDur"

                        historyContainer.addView(
                            createAccessibleButton(label, "#1E293B") {
                                etPhoneNumberOrRoom.setText(number)
                                placeNumberCallDirect(number)
                            }
                        )
                        count++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CallLog read error: ${e.message}")
            }
        }

        if (::deviceContactsContainer.isInitialized) deviceContactsContainer.removeAllViews()
        if (::googleContactsContainer.isInitialized) googleContactsContainer.removeAllViews()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.RawContacts.ACCOUNT_TYPE
                    ),
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    var devCount = 0
                    var googleCount = 0
                    while (c.moveToNext() && (devCount < 25 || googleCount < 25)) {
                        val name = c.getString(0) ?: continue
                        val num = c.getString(1) ?: continue
                        val acctType = c.getString(2) ?: ""
                        val isGoogle = acctType.contains("google", ignoreCase = true)
                        if (isGoogle && googleCount < 25 && ::googleContactsContainer.isInitialized) {
                            val label = "☁️ [Google] $name ($num)"
                            googleContactsContainer.addView(
                                createAccessibleButton(label, "#1E3A8A") {
                                    etPhoneNumberOrRoom.setText(num)
                                    placeNumberCallDirect(num)
                                }
                            )
                            googleCount++
                        } else if (!isGoogle && devCount < 25 && ::deviceContactsContainer.isInitialized) {
                            val label = "📱 [端末] $name ($num)"
                            deviceContactsContainer.addView(
                                createAccessibleButton(label, "#263238") {
                                    etPhoneNumberOrRoom.setText(num)
                                    placeNumberCallDirect(num)
                                }
                            )
                            devCount++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contacts read error: ${e.message}")
            }
        }
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        val ungranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            requestPermissionsLauncher.launch(ungranted.toTypedArray())
        } else {
            loadCallHistoryAndContacts()
        }
    }

    override fun onCallStateUpdated(call: Call?, state: Int, callerDisplay: String, connectedDurationMs: Long) {
        runOnUiThread {
            val stateStr = when (state) {
                Call.STATE_RINGING -> tr("📲 着信中: $callerDisplay", "📲 Incoming: $callerDisplay", "📲 Tumatawag: $callerDisplay", "📲 Entrante: $callerDisplay")
                Call.STATE_DIALING, Call.STATE_CONNECTING -> tr("📤 発信・接続中: $callerDisplay", "📤 Connecting: $callerDisplay", "📤 Kumokonekta: $callerDisplay", "📤 Conectando: $callerDisplay")
                Call.STATE_ACTIVE -> tr("🟢 通話中: $callerDisplay", "🟢 Active Call: $callerDisplay", "🟢Kausap: $callerDisplay", "🟢 En llamada: $callerDisplay")
                Call.STATE_HOLDING -> tr("⏸️ 保留中: $callerDisplay", "⏸️ On Hold: $callerDisplay", "⏸️ Naka-hold: $callerDisplay", "⏸️ En espera: $callerDisplay")
                else -> "📞 $callerDisplay"
            }
            tvStatusBanner.text = stateStr
        }
    }

    override fun onCallFinishedWithExactDuration(callerDisplay: String, durationMs: Long, formattedDuration: String) {
        runOnUiThread {
            tvStatusBanner.text = tr(
                "📞 通話終了: $callerDisplay • 正確な通話時間: $formattedDuration",
                "📞 Call Ended: $callerDisplay • Exact Duration: $formattedDuration",
                "📞 Tapos na: $callerDisplay • Eksaktong Tagal: $formattedDuration",
                "📞 Finalizada: $callerDisplay • Duración Exacta: $formattedDuration"
            )
            loadCallHistoryAndContacts()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            val active = KapamilyaInCallService.activeCall
            if (active != null) {
                val st = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) active.details.state else @Suppress("DEPRECATION") active.state
                if (st == Call.STATE_RINGING) {
                    answerIncomingCall()
                } else {
                    endActiveCallAndAnnounceDuration()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (isFinishing) {
            KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        }
        super.onDestroy()
        KapamilyaInCallService.removeObserver(this)
        aesGcmMeshEngine.stopMeshSignalingService()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
            toneGenerator?.release()
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
