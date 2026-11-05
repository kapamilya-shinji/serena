package com.kapamilya.serena.recorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kapamilya Serena Recorder v5.5.0 — Main Accessible Studio Activity
 * Features:
 * - Forcibly Embedded Google DeepMind "EmbeddingGemma 2 + Gemma 4" Dual-Core AI Engine
 *   (256-dim L2 Acoustic-Semantic Vector Matcher + AI Vocal Producer Review + 3-Language Liner Notes)
 * - 48kHz Background Vocal Recording with Screen-Reader Silence Sync
 * - ITU-R BS.1770-4 / EBU R128 Integrated Loudness Normalization (-14.0 LUFS & -1.0 dBTP True-Peak Limiter)
 * - On-Device Vocal Pitch (F0) & Register Spectrum Analyzer (Chest / Mix / Head / Whistle boundaries & H1-H2)
 * - Lossless RFC 9639 FLAC (.flac), WAV (.wav), and M4A (.m4a) Encoding + Universal Decoder
 * - 3000 x 3000 px RGB JPEG Random & AI-Guided Store-Ready Cover Art Generator
 * Exclusive Copyright & IP Owner: Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 */
class RecorderMainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var uiLang: String = "ja"

    private fun tr(ja: String, en: String, tl: String, es: String): String = when (uiLang) {
        "en" -> en
        "tl" -> tl
        "es" -> es
        else -> ja
    }

    private var recorderService: KapamilyaRecorderForegroundService? = null
    private var serviceBound = false

    private lateinit var tvStatusBanner: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvVocalReportBox: TextView
    private lateinit var etSongTitle: EditText
    private lateinit var etArtistName: EditText
    private lateinit var spinnerCodec: Spinner
    private lateinit var cbAutoTrimTts: CheckBox
    private lateinit var cbRemoveClicks: CheckBox
    private lateinit var cbItuLoudnessNormalize: CheckBox
    private lateinit var cbHallReverb: CheckBox
    private lateinit var cbCoverTitleOverlay: CheckBox

    private lateinit var btnRecordToggle: Button
    private lateinit var btnImportAndMaster: Button
    private lateinit var btnGenerateCover: Button
    private lateinit var btnSpeakVocalAnalysis: Button
    private lateinit var btnSpeakGemmaProducer: Button
    private lateinit var btnPreviewToggle: Button
    private lateinit var btnShareLatest: Button

    private var lastSavedAudioResult: KapamilyaStorageAndPermissionManager.SavedMediaResult? = null
    private var lastSavedCoverResult: KapamilyaStorageAndPermissionManager.SavedMediaResult? = null
    private var lastVocalAnalysisSpoken: String = ""
    private var lastGemmaResult: KapamilyaRecorderGemmaDualCoreEngine.GemmaDualCoreVocalResult? = null
    private var previewPlayer: MediaPlayer? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? KapamilyaRecorderForegroundService.LocalBinder
            recorderService = binder?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissionStatusUI(announce = true)
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshPermissionStatusUI(announce = true)
    }

    private val openAudioDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            processImportedAudioUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kapamilya_recorder_prefs", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("ui_lang", null)
        uiLang = savedLang ?: when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "en" -> "en"
            "tl", "fil" -> "tl"
            "es" -> "es"
            else -> "ja"
        }
        tts = TextToSpeech(this, this)
        buildAccessibleStudioUI()
        KapamilyaKrisSoundEngine.attachToActivity(this, playStartupSound = true)

        if (!KapamilyaStorageAndPermissionManager.hasAllRuntimePermissions(this)) {
            permissionLauncher.launch(KapamilyaStorageAndPermissionManager.getRequiredRuntimePermissions())
        } else {
            refreshPermissionStatusUI(announce = false)
        }
        KapamilyaDualEndpointUpdater.checkOnStartup(this, manualTrigger = false) { msg ->
            speakPolite(msg)
        }
    }

    override fun onResume() {
        super.onResume()
        KapamilyaDualEndpointUpdater.checkAndResumePendingInstall(this) { msg ->
            speakPolite(msg)
        }
    }

    private fun applyTtsLanguage() {
        try {
            tts?.language = when (uiLang) {
                "en" -> Locale.US
                "tl" -> Locale("fil", "PH")
                "es" -> Locale("es", "ES")
                else -> Locale.JAPANESE
            }
        } catch (_: Exception) {}
    }

    private fun switchLanguage(newLang: String) {
        uiLang = newLang
        getSharedPreferences("kapamilya_recorder_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("ui_lang", newLang)
            .apply()
        applyTtsLanguage()
        buildAccessibleStudioUI()
        refreshPermissionStatusUI(announce = false)
        speakPolite(
            tr(
                "表示と音声の言語を日本語に切り替えました！",
                "Switched display and voice language to English!",
                "Binago ang wika ng display at boses sa Tagalog!",
                "¡Idioma de pantalla y voz cambiado a Español!"
            )
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsLanguage()
            ttsReady = true
            speakPolite(
                tr(
                    "Kapamilya Serena Recorder バージョン 5.9.0 へようこそ！世界4言語ローカライズ、Google DeepMind の Gemma 4 と EmbeddingGemma 2 二刀流コア、国際標準 ITU-R BS.1770-4 ラウドネスノーマライズ、音域スペクトラム解析、FLAC書き出しと3000ピクセルJPGジャケット生成が全部ワンタップで使えるよ！",
                    "Welcome to Kapamilya Serena Recorder version 5.9.0! Global 4-language localization, Google DeepMind Gemma 4 & EmbeddingGemma 2 dual-core AI, ITU-R BS.1770-4 loudness normalization, vocal spectrum analysis, lossless FLAC export, and 3000px cover art generation are ready!",
                    "Maligayang pagdating sa Kapamilya Serena Recorder bersyon 5.9.0! May 4 na wika, Google DeepMind Gemma 4 at EmbeddingGemma 2 AI, ITU-R BS.1770-4 loudness normalization, vocal register analysis, FLAC export, at 3000px cover art generator!",
                    "¡Bienvenido a Kapamilya Serena Recorder versión 5.9.0! Localización en 4 idiomas, IA de doble núcleo Gemma 4 y EmbeddingGemma 2, normalización ITU-R BS.1770-4, análisis vocal, exportación FLAC y portadas de 3000px listos."
                )
            )
        }
    }

    private fun speakPolite(text: String) {
        runOnUiThread {
            tvStatusBanner.text = text
            tvStatusBanner.contentDescription = text
            if (ttsReady && !KapamilyaRecorderForegroundService.isCurrentlyRecording()) {
                applyTtsLanguage()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_recorder_utt")
            }
        }
    }

    private fun buildAccessibleStudioUI() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 12, 24))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        val tvHeader = TextView(this).apply {
            text = tr(
                "Kapamilya Serena Recorder v5.8.0\n世界4言語対応 • Gemma 4 ＋ EmbeddingGemma 2 • ITU-R BS.1770-4 ラウドネス • 音域解析 • FLAC＆3000pxジャケット生成",
                "Kapamilya Serena Recorder v5.8.0\n4 Languages • Gemma 4 + EmbeddingGemma 2 • ITU-R BS.1770-4 Loudness • Vocal Analyzer • FLAC & 3000px Cover Art",
                "Kapamilya Serena Recorder v5.8.0\n4 Wika • Gemma 4 + EmbeddingGemma 2 • ITU-R BS.1770-4 Loudness • Vocal Analyzer • FLAC at 3000px Cover Art",
                "Kapamilya Serena Recorder v5.8.0\n4 Idiomas • Gemma 4 + EmbeddingGemma 2 • Loudness ITU-R BS.1770-4 • Analizador Vocal • FLAC y Portada 3000px"
            )
            setTextColor(Color.rgb(255, 222, 115))
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(tvHeader)

        // === 🌍 世界4言語ワンタップ切替バー (日本語 / English / Tagalog / Español) ===
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
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
                setBackgroundColor(if (isSelected) Color.rgb(255, 213, 79) else Color.rgb(30, 41, 59))
                textSize = 13f
                setTypeface(Typeface.DEFAULT_BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { switchLanguage(code) }
            }
            langRow.addView(btnLang)
        }
        root.addView(langRow)

        tvStatusBanner = TextView(this).apply {
            text = tr(
                "準備完了：Gemma 4 ＋ EmbeddingGemma 2 二刀流コア＆48kHzロスレス録音スタジオ待機中。",
                "Ready: Gemma 4 + EmbeddingGemma 2 Dual-Core & 48kHz Lossless Recording Studio Standby.",
                "Handa na: Gemma 4 + EmbeddingGemma 2 Dual-Core at 48kHz Lossless Recording Studio.",
                "Listo: Estudio de Grabación sin Pérdida 48kHz con IA Gemma 4 + EmbeddingGemma 2."
            )
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(20, 34, 66))
            textSize = 16f
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        root.addView(tvStatusBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        tvPermissionStatus = TextView(this).apply {
            text = KapamilyaStorageAndPermissionManager.getPermissionStatusSummary(this@RecorderMainActivity)
            setTextColor(Color.rgb(165, 245, 210))
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvPermissionStatus)

        val btnGrantPermissions = createAccessibleButton(
            label = tr(
                "1. マイク・音楽・画像・通知権限を一括確認＆許可する",
                "1. Verify & Grant Mic, Audio, Image & Notification Permissions",
                "1. Suriin at Payagan ang Pahintulot sa Mic, Audio, Larawan at Abiso",
                "1. Verificar y Conceder Permisos de Micrófono, Audio, Imagen y Notificación"
            ),
            bgColor = Color.rgb(24, 92, 145),
            textColor = Color.WHITE
        ) {
            if (!KapamilyaStorageAndPermissionManager.hasAllRuntimePermissions(this)) {
                permissionLauncher.launch(KapamilyaStorageAndPermissionManager.getRequiredRuntimePermissions())
            } else {
                refreshPermissionStatusUI(announce = true)
            }
        }
        root.addView(btnGrantPermissions, buttonLayoutParams())

        val btnAllFilesAccess = createAccessibleButton(
            label = tr(
                "オプション：全ファイルアクセス権限（SDカード直接読み書き）設定を開く",
                "Optional: Open All Files Access Permission (Direct SD Card Read/Write)",
                "Opsyonal: Buksan ang All Files Access Permission (Direktang SD Card)",
                "Opcional: Abrir Permiso de Acceso a Todos los Archivos (Tarjeta SD)"
            ),
            bgColor = Color.rgb(36, 62, 98),
            textColor = Color.rgb(255, 232, 155)
        ) {
            if (KapamilyaStorageAndPermissionManager.hasAllFilesAccess()) {
                speakPolite(tr("全ファイルアクセス権限はすでに有効だよ！MusicフォルダとPicturesフォルダへ直接保存できます。", "All Files Access permission is already enabled!", "Aktibo na ang All Files Access permission!", "¡El permiso de acceso a todos los archivos ya está activo!"))
            } else {
                speakPolite(tr("全ファイルアクセス権限の設定画面を開くよ。スイッチをオンにして戻ってきてね。", "Opening All Files Access settings.", "Binubuksan ang All Files Access settings.", "Abriendo configuración de acceso a todos los archivos."))
                allFilesAccessLauncher.launch(KapamilyaStorageAndPermissionManager.buildAllFilesAccessIntent(this))
            }
        }
        root.addView(btnAllFilesAccess, buttonLayoutParams())

        val tvMetaLabel = createSectionLabel(
            tr(
                "曲タイトル・アーティスト名・出力コーデック・ラウドネス設定",
                "Song Title, Artist Name, Output Codec & Loudness Settings",
                "Pamagat ng Kanta, Pangalan ng Artist, Output Codec at Loudness",
                "Título de Canción, Artista, Códec de Salida y Sonoridad"
            )
        )
        root.addView(tvMetaLabel)

        etSongTitle = EditText(this).apply {
            hint = tr(
                "曲名を入力（例：スルメビジョウ / マグナカルタのつぎびと）",
                "Enter Song Title",
                "Ilagay ang Pamagat ng Kanta",
                "Ingrese el Título de la Canción"
            )
            setHintTextColor(Color.rgb(160, 175, 200))
            setText("Vocal_Master_Take")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 30, 52))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        root.addView(etSongTitle, buttonLayoutParams())

        etArtistName = EditText(this).apply {
            hint = tr("アーティスト名", "Artist Name", "Pangalan ng Artist", "Nombre del Artista")
            setHintTextColor(Color.rgb(160, 175, 200))
            setText("Shinji Sakiyama")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 30, 52))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        root.addView(etArtistName, buttonLayoutParams())

        val codecOptions = arrayOf(
            tr("FLAC (.flac) — 48kHz ロスレス可逆圧縮（一番おすすめ・配信ストア対応）", "FLAC (.flac) — 48kHz Lossless Compression (Store Recommended)", "FLAC (.flac) — 48kHz Lossless Compression (Inirerekomenda)", "FLAC (.flac) — 48kHz Sin Pérdida (Recomendado para Tiendas)"),
            tr("WAV (.wav) — 48kHz 16-bit 非圧縮リニアPCM", "WAV (.wav) — 48kHz 16-bit Uncompressed Linear PCM", "WAV (.wav) — 48kHz 16-bit Uncompressed PCM", "WAV (.wav) — 48kHz 16-bit PCM Sin Comprimir"),
            tr("M4A (.m4a) — AAC 256kbps 高音質圧縮", "M4A (.m4a) — AAC 256kbps High Quality", "M4A (.m4a) — AAC 256kbps Mataas na Kalidad", "M4A (.m4a) — AAC 256kbps Alta Calidad")
        )
        spinnerCodec = Spinner(this).apply {
            setBackgroundColor(Color.rgb(28, 42, 74))
            adapter = ArrayAdapter(
                this@RecorderMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                codecOptions
            )
            setSelection(0)
        }
        root.addView(spinnerCodec, buttonLayoutParams())

        cbAutoTrimTts = createAccessibleCheckBox(
            label = tr(
                "録音開始・停止時のスクリーンリーダー読み上げ声＆ボタン打鍵音を自動トリミング消去する",
                "Auto-trim screen reader speech & button tap sounds at start/end of recording",
                "Kusang alisin ang boses ng screen reader at tunog ng pindot sa simula/wakas",
                "Recortar automáticamente voz del lector de pantalla y clics al iniciar/detener"
            ),
            checked = true
        )
        root.addView(cbAutoTrimTts)

        cbRemoveClicks = createAccessibleCheckBox(
            label = tr(
                "歌唱中のキーボード打鍵音・クリックノイズ除去＆80Hzローカット＋ボーカルコンプレッサーを適用",
                "Apply keyboard click removal, 80Hz low-cut filter & vocal compressor",
                "Alisin ang click noise at ilapat ang 80Hz low-cut filter at vocal compressor",
                "Eliminar clics de teclado y aplicar filtro de 80Hz y compresor vocal"
            ),
            checked = true
        )
        root.addView(cbRemoveClicks)

        cbItuLoudnessNormalize = createAccessibleCheckBox(
            label = tr(
                "国際標準規格 ITU-R BS.1770-4 / EBU R128 ラウドネスノーマライズ（-14.0 LUFS ＆ -1.0 dBTP トゥルーピーク制限）を適用",
                "Apply ITU-R BS.1770-4 / EBU R128 Loudness Normalization (-14.0 LUFS & -1.0 dBTP True-Peak)",
                "Ilapat ang ITU-R BS.1770-4 / EBU R128 Loudness Normalization (-14.0 LUFS at -1.0 dBTP)",
                "Aplicar Normalización ITU-R BS.1770-4 / EBU R128 (-14.0 LUFS y -1.0 dBTP)"
            ),
            checked = true
        )
        root.addView(cbItuLoudnessNormalize)

        cbHallReverb = createAccessibleCheckBox(
            label = tr(
                "ナチュラル・アコースティックホールリバーブ（軽い響き）を追加する",
                "Add Natural Acoustic Hall Reverb",
                "Magdagdag ng Natural Acoustic Hall Reverb",
                "Añadir Reverberación Acústica Natural de Sala"
            ),
            checked = false
        )
        root.addView(cbHallReverb)

        cbCoverTitleOverlay = createAccessibleCheckBox(
            label = tr(
                "3000×3000px ジャケット画像に曲名と Shinji Sakiyama の文字を入れる",
                "Overlay Song Title & Artist Name onto 3000x3000px Cover Art",
                "Ilagay ang Pamagat ng Kanta at Pangalan ng Artist sa 3000x3000px Cover Art",
                "Incluir Título y Nombre del Artista en la Portada de 3000x3000px"
            ),
            checked = true
        )
        root.addView(cbCoverTitleOverlay)

        val tvActionLabel = createSectionLabel(
            tr(
                "レコーディング・マスタリング・Gemma 4 / EmbeddingGemma 2 解析・3000pxジャケット生成",
                "Recording, Mastering, Gemma 4 / EmbeddingGemma 2 Analysis & 3000px Cover Art",
                "Pag-record, Mastering, Pagsusuri ng Gemma 4 / EmbeddingGemma 2 at 3000px Cover Art",
                "Grabación, Masterización, Análisis Gemma 4 / EmbeddingGemma 2 y Portada 3000px"
            )
        )
        root.addView(tvActionLabel)

        btnRecordToggle = createAccessibleButton(
            label = tr(
                "2. アカペラ録音開始（読み上げ終了待ち＋バックグラウンド48kHz録音）",
                "2. Start Vocal Recording (Wait for TTS Silence + Background 48kHz Recording)",
                "2. Simulan ang Pag-record ng Boses (48kHz Background Recording)",
                "2. Iniciar Grabación Vocal (Espera Silencio TTS + Grabación 48kHz)"
            ),
            bgColor = Color.rgb(185, 28, 52),
            textColor = Color.WHITE
        ) {
            onRecordToggleClicked()
        }
        root.addView(btnRecordToggle, buttonLayoutParams())

        btnImportAndMaster = createAccessibleButton(
            label = tr(
                "3. 既存の音声ファイル（FLAC / WAV / MP3 / M4A / OGG / OPUS）を選んでノイズ除去・ラウドネス調整＆Gemma 4音域解析",
                "3. Import Audio File (FLAC/WAV/MP3/M4A/OGG/OPUS) for Denoising, ITU Loudness & Gemma 4 Analysis",
                "3. Pumili ng Audio File (FLAC/WAV/MP3/M4A/OGG/OPUS) para sa Mastering at Gemma 4 Analysis",
                "3. Importar Archivo de Audio (FLAC/WAV/MP3/M4A/OGG/OPUS) para Masterizar y Analizar con Gemma 4"
            ),
            bgColor = Color.rgb(22, 118, 92),
            textColor = Color.WHITE
        ) {
            speakPolite(
                tr(
                    "マスタリングとGemma 4音域解析をしたい音声ファイルを選んでね。FLAC、WAV、MP3、M4A、OGG、OPUSすべて対応してるよ！",
                    "Please select an audio file for mastering and Gemma 4 vocal analysis.",
                    "Pumili ng audio file para sa mastering at pagsusuri ng Gemma 4.",
                    "Seleccione un archivo de audio para masterizar y analizar con Gemma 4."
                )
            )
            openAudioDocumentLauncher.launch(arrayOf("audio/*"))
        }
        root.addView(btnImportAndMaster, buttonLayoutParams())

        btnGenerateCover = createAccessibleButton(
            label = tr(
                "4. 3000×3000ピクセル JPGジャケット生成（EmbeddingGemma 2 音響テーマ連動＋ランダム生成・音声解説付き）",
                "4. Generate 3000x3000px JPG Cover Art (EmbeddingGemma 2 Acoustic Theme + Voice Description)",
                "4. Gumawa ng 3000x3000px JPG Cover Art (May Paliwanag sa Boses)",
                "4. Generar Portada JPG de 3000x3000px (Con Descripción por Voz)"
            ),
            bgColor = Color.rgb(142, 88, 16),
            textColor = Color.WHITE
        ) {
            generateAndSaveRandom3000Cover()
        }
        root.addView(btnGenerateCover, buttonLayoutParams())

        btnSpeakVocalAnalysis = createAccessibleButton(
            label = tr(
                "5. ボーカル音域（チェスト／ミックス／ヘッド／ホイッスル限界）＆ラウドネス解析レポートを読み上げる",
                "5. Speak Vocal Range (Chest/Mix/Head/Whistle) & Loudness Analysis Report",
                "5. Basahin ang Ulat sa Vocal Range (Chest/Mix/Head/Whistle) at Loudness",
                "5. Leer Informe de Rango Vocal (Chest/Mix/Head/Whistle) y Sonoridad"
            ),
            bgColor = Color.rgb(16, 108, 128),
            textColor = Color.WHITE
        ) {
            if (lastVocalAnalysisSpoken.isBlank()) {
                speakPolite(
                    tr(
                        "まだ解析レポートがないよ。先に録音するか、既存の音声ファイルを読み込んでマスタリングを実行してね！",
                        "No analysis report yet. Please record or import an audio file first!",
                        "Wala pang ulat. Mag-record muna o mag-import ng audio file!",
                        "Aún no hay informe. ¡Grabe o importe un archivo de audio primero!"
                    )
                )
            } else {
                speakPolite(lastVocalAnalysisSpoken)
            }
        }
        root.addView(btnSpeakVocalAnalysis, buttonLayoutParams())

        btnSpeakGemmaProducer = createAccessibleButton(
            label = tr(
                "6. Gemma 4 ＋ EmbeddingGemma 2 ボーカルディレクター講評＆3言語ライナーノーツを読み上げ＆クリップボードへコピー",
                "6. Speak & Copy Gemma 4 + EmbeddingGemma 2 Vocal Director Review & 3-Language Liner Notes",
                "6. Basahin at Kopyahin ang Gemma 4 Vocal Director Review at 3-Wikang Liner Notes",
                "6. Leer y Copiar Reseña del Director Vocal Gemma 4 y Notas en 3 Idiomas"
            ),
            bgColor = Color.rgb(112, 36, 138),
            textColor = Color.WHITE
        ) {
            val gemma = lastGemmaResult
            if (gemma == null) {
                speakPolite(
                    tr(
                        "まだGemma 4のボーカルディレクター講評がないよ。先に録音するか既存の音声ファイルを読み込んでね！",
                        "No Gemma 4 vocal director review yet. Please record or import an audio file first!",
                        "Wala pang Gemma 4 review. Mag-record muna o mag-import ng audio file!",
                        "Aún no hay reseña de Gemma 4. ¡Grabe o importe un archivo primero!"
                    )
                )
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = "${gemma.linerNotesJa}\n\n${gemma.linerNotesEn}\n\n${gemma.linerNotesTl}"
                cm.setPrimaryClip(ClipData.newPlainText("Kapamilya Liner Notes", clipText))
                speakPolite("${gemma.spokenProducerSummaryJa} " + tr("日本語・英語・タガログ語の3言語ライナーノーツをクリップボードにコピーしたよ！", "Copied 3-language liner notes to clipboard!", "Nakopya na sa clipboard ang 3-wikang liner notes!", "¡Notas en 3 idiomas copiadas al portapapeles!"))
            }
        }
        root.addView(btnSpeakGemmaProducer, buttonLayoutParams())

        btnPreviewToggle = createAccessibleButton(
            label = tr(
                "7. マスタリング済み音源をプレビュー再生 / 停止",
                "7. Preview Play / Stop Mastered Audio",
                "7. Pakinggan / Ihinto ang Mastered Audio",
                "7. Reproducir / Detener Vista Previa del Audio Masterizado"
            ),
            bgColor = Color.rgb(54, 72, 128),
            textColor = Color.WHITE
        ) {
            togglePreviewPlayback()
        }
        root.addView(btnPreviewToggle, buttonLayoutParams())

        btnShareLatest = createAccessibleButton(
            label = tr(
                "8. 完成した音源・3000pxジャケットを共有（DistroKid / Drive / メール等へ送る）",
                "8. Share Mastered Audio & 3000px Cover Art (DistroKid / Drive / Mail)",
                "8. I-share ang Natapos na Audio at 3000px Cover Art",
                "8. Compartir Audio Masterizado y Portada de 3000px"
            ),
            bgColor = Color.rgb(88, 42, 138),
            textColor = Color.WHITE
        ) {
            shareLatestOutputs()
        }
        root.addView(btnShareLatest, buttonLayoutParams())

        tvVocalReportBox = TextView(this).apply {
            text = tr(
                "【Gemma 4 × EmbeddingGemma 2 ＆ ボーカルスペクトラム・ITU-R BS.1770-4 ラウドネス解析レポート欄】\n録音または音声ファイル読み込みを行うと、ここに基本周波数（F0）、チェスト／ミックス／ヘッド／ホイッスル限界音域、倍音比（H1-H2）、ラウドネス（LUFS / dBTP）、および Gemma 4 ボーカル講評＆3言語ライナーノーツが表示されます。",
                "[Gemma 4 x EmbeddingGemma 2 Vocal Spectrum & ITU-R BS.1770-4 Loudness Report]\nRecord or import an audio file to display fundamental frequency (F0), Chest/Mix/Head/Whistle register boundaries, H1-H2 harmonics, LUFS/dBTP loudness, and 3-language liner notes.",
                "[Ulat ng Gemma 4 x EmbeddingGemma 2 Vocal Spectrum at ITU-R BS.1770-4 Loudness]\nMag-record o mag-import ng audio file upang makita ang F0, Chest/Mix/Head/Whistle range, LUFS/dBTP loudness, at 3-wikang liner notes.",
                "[Informe de Espectro Vocal Gemma 4 x EmbeddingGemma 2 y Sonoridad ITU-R BS.1770-4]\nGrabe o importe un archivo para ver F0, registros vocales, sonoridad LUFS/dBTP y notas en 3 idiomas."
            )
            setTextColor(Color.rgb(220, 240, 255))
            setBackgroundColor(Color.rgb(16, 24, 44))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        root.addView(tvVocalReportBox, buttonLayoutParams())

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshPermissionStatusUI(announce: Boolean) {
        val summary = KapamilyaStorageAndPermissionManager.getPermissionStatusSummary(this)
        tvPermissionStatus.text = summary
        tvPermissionStatus.contentDescription = summary
        if (announce) {
            speakPolite("権限ステータスを更新したよ。$summary")
        }
    }

    private fun onRecordToggleClicked() {
        if (!KapamilyaRecorderForegroundService.isCurrentlyRecording()) {
            if (!KapamilyaStorageAndPermissionManager.hasMicrophonePermission(this)) {
                speakPolite("録音するにはマイク権限が必要だよ。権限許可ダイアログを出すね！")
                permissionLauncher.launch(KapamilyaStorageAndPermissionManager.getRequiredRuntimePermissions())
                return
            }

            stopPreviewPlayback()

            val svcIntent = Intent(this, KapamilyaRecorderForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

            btnRecordToggle.text = "■ 録音停止 ＆ 自動ノイズ除去・ラウドネス調整・Gemma 4音域解析実行"
            btnRecordToggle.setBackgroundColor(Color.rgb(220, 120, 12))
            tvStatusBanner.text = "録音開始待機中（スクリーンリーダーの読み上げが止まってから自動でマイクを開きます）..."

            Thread {
                var waitCount = 0
                while (recorderService == null && waitCount < 30) {
                    Thread.sleep(50)
                    waitCount++
                }
                recorderService?.startVocalRecording(delayMsForScreenReaderSilence = 1100L) {
                    runOnUiThread {
                        tvStatusBanner.text = "● 48kHz ロスレス録音中！画面をロックしても裏でそのまま歌えます。歌い終わったら「録音停止」を押してね。"
                    }
                }
            }.start()
        } else {
            btnRecordToggle.isEnabled = false
            tvStatusBanner.text = "録音を停止し、打鍵音除去・ITU-R BS.1770-4 ラウドネス調整・Gemma 4音域解析を行っています..."
            Thread {
                val rawSamples = recorderService?.stopVocalRecording()
                    ?: KapamilyaRecorderForegroundService.getLastRecordedSamples()
                if (serviceBound) {
                    try {
                        unbindService(serviceConnection)
                    } catch (_: Exception) {}
                    serviceBound = false
                }
                runOnUiThread {
                    btnRecordToggle.text = "2. アカペラ録音開始（読み上げ終了待ち＋バックグラウンド48kHz録音）"
                    btnRecordToggle.setBackgroundColor(Color.rgb(185, 28, 52))
                    btnRecordToggle.isEnabled = true
                }
                if (rawSamples.isEmpty()) {
                    speakPolite("録音データが空だったよ。もう一度録音してみてね。")
                    return@Thread
                }
                masterAndExportSamples(rawSamples, sourceLabel = "マイク録音テイク")
            }.start()
        }
    }

    private fun processImportedAudioUri(uri: Uri) {
        stopPreviewPlayback()
        speakPolite("音声ファイルを読み込んで、48キロヘルツデコード・ノイズ除去・ITU-R BS.1770-4 ラウドネス調整・Gemma 4音域解析を開始するよ。少し待っててね！")
        Thread {
            try {
                val rawSamples = KapamilyaFlacAndCodecEngine.decodeAudioUriToMono48kHz(this, uri)
                if (rawSamples.isEmpty()) {
                    speakPolite("音声ファイルのデコードに失敗したか、データが空だったよ。")
                    return@Thread
                }
                masterAndExportSamples(rawSamples, sourceLabel = "読み込み音声ファイル")
            } catch (e: Exception) {
                speakPolite("音声ファイルの読み込み中にエラーが起きたよ：${e.message}")
            }
        }.start()
    }

    private fun masterAndExportSamples(rawSamples: FloatArray, sourceLabel: String) {
        try {
            val trimTts = cbAutoTrimTts.isChecked
            val removeClicks = cbRemoveClicks.isChecked
            val lufsNorm = cbItuLoudnessNormalize.isChecked
            val hallReverb = cbHallReverb.isChecked
            val songTitle = etSongTitle.text?.toString()?.trim().orEmpty().ifBlank { "Vocal_Master_Take" }
            val artistName = etArtistName.text?.toString()?.trim().orEmpty().ifBlank { "Shinji Sakiyama" }
            val safeFileStem = songTitle.replace(Regex("[^a-zA-Z0-9_\\-\\p{L}\\p{N}]"), "_").ifBlank { "Vocal_Take" }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // Save raw backup WAV first
            KapamilyaStorageAndPermissionManager.saveAudioToPublicMusic(
                context = this,
                fileName = "${safeFileStem}_RAW_${timestamp}.wav",
                mimeType = "audio/wav",
                title = "$songTitle (Raw)"
            ) { out ->
                KapamilyaFlacAndCodecEngine.writeWav(rawSamples, KapamilyaFlacAndCodecEngine.SAMPLE_RATE, out)
            }

            // Run Vocal Mastering + ITU-R BS.1770-4 Loudness Normalizer + Pitch/Register Spectrum Analyzer
            val mastered = KapamilyaVocalMasteringEngine.masterVocalTake(
                rawSamples = rawSamples,
                sampleRate = KapamilyaFlacAndCodecEngine.SAMPLE_RATE,
                trimStartEnd = trimTts,
                removeClicksAndNoise = removeClicks,
                applyHallReverb = hallReverb,
                enableLufsNormalize = lufsNorm,
                targetLufs = -14.0
            )

            // Run Google DeepMind EmbeddingGemma 2 + Gemma 4 Dual-Core Vocal Producer & Liner Notes Engine
            val gemmaResult = KapamilyaRecorderGemmaDualCoreEngine.analyzeWithGemma4AndEmbeddingGemma2(
                context = this,
                songTitle = songTitle,
                artistName = artistName,
                pitchReport = mastered.pitchReport,
                inputLufs = mastered.inputIntegratedLufs,
                outputLufs = mastered.outputIntegratedLufs,
                outputDbtp = mastered.outputTruePeakDbtp
            )
            lastGemmaResult = gemmaResult

            val codecChoice = spinnerCodec.selectedItemPosition
            val savedResult = when (codecChoice) {
                0 -> {
                    KapamilyaStorageAndPermissionManager.saveAudioToPublicMusic(
                        context = this,
                        fileName = "${safeFileStem}_MASTER_${timestamp}.flac",
                        mimeType = "audio/flac",
                        title = songTitle
                    ) { out ->
                        KapamilyaFlacAndCodecEngine.writeFlac(
                            samples = mastered.samples,
                            sampleRate = KapamilyaFlacAndCodecEngine.SAMPLE_RATE,
                            title = songTitle,
                            artist = artistName,
                            out = out
                        )
                    }
                }
                1 -> {
                    KapamilyaStorageAndPermissionManager.saveAudioToPublicMusic(
                        context = this,
                        fileName = "${safeFileStem}_MASTER_${timestamp}.wav",
                        mimeType = "audio/wav",
                        title = songTitle
                    ) { out ->
                        KapamilyaFlacAndCodecEngine.writeWav(
                            samples = mastered.samples,
                            sampleRate = KapamilyaFlacAndCodecEngine.SAMPLE_RATE,
                            out = out
                        )
                    }
                }
                else -> {
                    val tempM4a = File(cacheDir, "temp_master_${timestamp}.m4a")
                    KapamilyaFlacAndCodecEngine.writeM4aFile(
                        samples = mastered.samples,
                        sampleRate = KapamilyaFlacAndCodecEngine.SAMPLE_RATE,
                        outputFile = tempM4a
                    )
                    val res = KapamilyaStorageAndPermissionManager.saveAudioToPublicMusic(
                        context = this,
                        fileName = "${safeFileStem}_MASTER_${timestamp}.m4a",
                        mimeType = "audio/mp4",
                        title = songTitle
                    ) { out ->
                        tempM4a.inputStream().use { it.copyTo(out) }
                    }
                    tempM4a.delete()
                    res
                }
            }

            lastSavedAudioResult = savedResult
            val extLabel = when (codecChoice) {
                0 -> "ロスレスFLAC"
                1 -> "リニアPCM WAV"
                else -> "高音質M4A"
            }
            val origSecStr = String.format(Locale.US, "%.1f", mastered.originalDurationSec)
            val mastSecStr = String.format(Locale.US, "%.1f", mastered.masteredDurationSec)
            val inLufsStr = String.format(Locale.US, "%.1f", mastered.inputIntegratedLufs)
            val outLufsStr = String.format(Locale.US, "%.1f", mastered.outputIntegratedLufs)
            val peakDbtpStr = String.format(Locale.US, "%.1f", mastered.outputTruePeakDbtp)

            val fullReportText = buildString {
                appendLine("【ITU-R BS.1770-4 / EBU R128 ラウドネス＆トゥルーピーク計測結果】")
                appendLine("・処理前ラウドネス: $inLufsStr LUFS  ->  マスタリング後: $outLufsStr LUFS (True Peak: $peakDbtpStr dBTP)")
                appendLine("・保存先: ${savedResult.displayPath}")
                appendLine()
                appendLine(mastered.pitchReport.detailedTextReport)
                appendLine(gemmaResult.fullFormattedReport)
            }

            try {
                val appDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir, KapamilyaStorageAndPermissionManager.PUBLIC_SUBFOLDER)
                if (!appDir.exists()) appDir.mkdirs()
                File(appDir, "${safeFileStem}_VocalAnalysis_${timestamp}.txt").writeText(fullReportText, Charsets.UTF_8)
            } catch (_: Exception) {}

            val spokenSummary = "${sourceLabel}のマスタリングと${extLabel}書き出しが完了したよ！" +
                "長さは${origSecStr}秒から不要ノイズを除去して${mastSecStr}秒。" +
                "ラウドネスは${inLufsStr} LUFS から、配信基準の ${outLufsStr} LUFS（トゥルーピーク ${peakDbtpStr} dBTP）に調整したよ。" +
                mastered.pitchReport.spokenSummaryJa + " " +
                gemmaResult.spokenProducerSummaryJa

            lastVocalAnalysisSpoken = spokenSummary
            runOnUiThread {
                tvVocalReportBox.text = fullReportText
                tvVocalReportBox.contentDescription = spokenSummary
            }
            speakPolite(spokenSummary)
        } catch (e: Exception) {
            speakPolite("マスタリング中にエラーが発生したよ：${e.message}")
        }
    }

    private fun generateAndSaveRandom3000Cover() {
        speakPolite("EmbeddingGemma 2 の音響ベクトルと暗号乱数シードを使って、3000×3000ピクセルの配信ストア用JPGジャケットを生成してるよ。少し待っててね！")
        Thread {
            try {
                val songTitle = etSongTitle.text?.toString()?.trim().orEmpty()
                val artistName = etArtistName.text?.toString()?.trim().orEmpty().ifBlank { "Shinji Sakiyama" }
                val includeOverlay = cbCoverTitleOverlay.isChecked
                val safeStem = songTitle.replace(Regex("[^a-zA-Z0-9_\\-\\p{L}\\p{N}]"), "_").ifBlank { "Cover" }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "${safeStem}_Cover_3000x3000_${timestamp}.jpg"

                // Use EmbeddingGemma 2 matched theme on first generation if available, or random
                val preferredTheme = if (lastSavedCoverResult == null) lastGemmaResult?.matchedCoverThemeId else null

                var coverInfo: KapamilyaCoverArtGenerator.GeneratedCoverInfo? = null
                val savedCover = KapamilyaStorageAndPermissionManager.saveCoverJpegToPublicPictures(
                    context = this,
                    fileName = fileName
                ) { out ->
                    coverInfo = KapamilyaCoverArtGenerator.generateRandomCoverJpeg3000(
                        outStream = out,
                        songTitle = songTitle,
                        artistName = artistName,
                        includeTextOverlay = includeOverlay,
                        preferredThemeId = preferredTheme
                    )
                }

                lastSavedCoverResult = savedCover
                val info = coverInfo
                speakPolite(
                    "3000×3000ピクセルのJPGジャケットが完成したよ！" +
                    (info?.descriptionJa ?: "") +
                    " 保存先は ${savedCover.displayPath} だよ！"
                )
            } catch (e: Exception) {
                speakPolite("ジャケット画像の生成中にエラーが起きたよ：${e.message}")
            }
        }.start()
    }

    private fun togglePreviewPlayback() {
        val current = previewPlayer
        if (current != null && current.isPlaying) {
            stopPreviewPlayback()
            speakPolite("プレビュー再生を停止したよ。")
            return
        }
        val audioRes = lastSavedAudioResult
        if (audioRes == null || !audioRes.localCacheFile.exists()) {
            speakPolite("まだマスタリング済みの音源がないよ。先に録音するか、既存の音声ファイルを読み込んでね！")
            return
        }
        try {
            tts?.stop()
            val mp = MediaPlayer().apply {
                setDataSource(audioRes.localCacheFile.absolutePath)
                setOnCompletionListener {
                    runOnUiThread {
                        tvStatusBanner.text = "プレビュー再生が完了しました。"
                    }
                }
                prepare()
                start()
            }
            previewPlayer = mp
            tvStatusBanner.text = "♪ マスタリング済み音源をプレビュー再生中: ${audioRes.localCacheFile.name}"
        } catch (e: Exception) {
            speakPolite("プレビュー再生を開始できなかったよ：${e.message}")
        }
    }

    private fun stopPreviewPlayback() {
        try {
            previewPlayer?.stop()
        } catch (_: Exception) {}
        try {
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
    }

    private fun shareLatestOutputs() {
        val uris = ArrayList<Uri>()
        lastSavedAudioResult?.contentUri?.let { uris.add(it) }
        lastSavedCoverResult?.contentUri?.let { uris.add(it) }

        if (uris.isEmpty()) {
            speakPolite("共有できる音源またはジャケットがまだ作られていないよ。先に録音・マスタリングかジャケット生成を実行してね！")
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "マスタリング音源＆3000pxジャケットを共有"))
    }

    private fun createSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(255, 215, 100))
            textSize = 17f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun createAccessibleCheckBox(label: String, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun createAccessibleButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            contentDescription = label
            setBackgroundColor(bgColor)
            setTextColor(textColor)
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            val p = dp(14)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        if (isFinishing) {
            KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        }
        stopPreviewPlayback()
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            serviceBound = false
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
