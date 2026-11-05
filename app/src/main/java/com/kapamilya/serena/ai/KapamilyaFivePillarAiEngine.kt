/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.ai

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.kapamilya.serena.LiveVisionActivity
import com.kapamilya.serena.SerenaScreenReaderService
import com.kapamilya.serena.serenaMenuDialog
import com.kapamilya.serena.serenaMenuItem
import com.kapamilya.serena.translation.SerenaLocalizationBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * KapamilyaFivePillarAiEngine (v5.1.0)
 *
 * Pure-Tagalog Unified Menu Titles & Unconditional Tagalog Voice Greetings +
 * Highest-Quality Natural Multilingual TTS Auto-Routing:
 *
 * 1 & 4 (Unified + Legacy Live Commentary):
 *    `Mata ng Kapamilya: Live Tagapagsalaysay`
 *    - Full Video Scene + Burned-in Subtitle/Telop AI Audio Description
 *    - Zoom / Teams / Meet / Discord Screen-Share Live Whisper Commentator (Slides, Code, Browser, Excel, Terminal, Cursor)
 *    - Live Camera Commentary Integration
 *
 * 2: `Mata ng Kapamilya: Tagasuri ng Paninda`
 *    - E-Commerce & Flea Market Product Image Inspector (Size Charts, Ingredients/Expiry, Condition/Scratches)
 *
 * 3: `Bayanihan Gabay: Tagabasa ng Dokumento at Talaan`
 *    - Scanned Image PDF, Complex Merged-Cell Table Structurer & Interactive Blank Input Field Guide
 *
 * 5: `Kapamilya Bayanihan Link`
 *    - Android ⇄ Windows Zero-Cloud Local LAN P2P Bridge (Phone Camera OCR / 2FA OTP / Clipboard ⇄ PC Instant Sync)
 */
class KapamilyaFivePillarAiEngine(private val service: SerenaScreenReaderService) {

    companion object {
        private const val TAG = "KapamilyaFivePillarAi"
        const val VERSION_SIGNATURE = "KapamilyaFivePillarAiEngine v5.1.0"
        const val BAYANIHAN_UDP_PORT = 45829
        private const val BAYANIHAN_MAGIC = "KAPAMILYA_LINK_V51|"

        // Official Pure-Tagalog Menu Titles (Zero Clutter, 100% Unified Across All Languages)
        const val MENU_TITLE_LIVE_TAGAPAGSALAYSAY = "🎬 Mata ng Kapamilya: Live Tagapagsalaysay"
        const val MENU_TITLE_TAGASURI_NG_PANINDA = "🛍️ Mata ng Kapamilya: Tagasuri ng Paninda"
        const val MENU_TITLE_TAGABASA_NG_DOKUMENTO = "📑 Bayanihan Gabay: Tagabasa ng Dokumento at Talaan"
        const val MENU_TITLE_BAYANIHAN_LINK = "🤝 Kapamilya Bayanihan Link"

        // Unconditional Tagalog Activation / Deactivation Voice Prompts
        const val GREETING_LIVE_ON =
            "Bukas na ang Mata ng Kapamilya Live Tagapagsalaysay! Ilalarawan ko na ang buong video, subtitle, screen share, at kamera para sa iyo, aking Kapamilya!"
        const val GREETING_LIVE_OFF =
            "Naka-hinto na ang Mata ng Kapamilya Live Tagapagsalaysay. Nandito lang ako anumang oras, aking Kapamilya!"
        const val GREETING_PANINDA_START =
            "Narito na ang Mata ng Kapamilya Tagasuri ng Paninda! Susuriin ko na ang larawan ng produkto, sukat, sangkap, at kondisyon para sa iyo, aking Kapamilya!"
        const val GREETING_DOKUMENTO_START =
            "Bukas na ang Bayanihan Gabay Tagabasa ng Dokumento at Talaan! Aayusin ko ang PDF, talahanayan, at mga patlang na susulatan para sa iyo, aking Kapamilya!"
        const val GREETING_LINK_ON =
            "Bukas na ang Kapamilya Bayanihan Link! Magkakaugnay na ang iyong telepono at PC, aking Kapamilya!"
        const val GREETING_LINK_OFF =
            "Naka-hinto na ang Kapamilya Bayanihan Link. Ingat lagi, aking Kapamilya!"
        const val GREETING_LINK_RECEIVED =
            "Natanggap na mula sa Kapamilya Bayanihan Link!"
        const val GREETING_LINK_SENT =
            "Naipadala na sa iyong Kapamilya Bayanihan Link!"

        private val SCREEN_SHARE_PACKAGES = listOf(
            "us.zoom", "com.microsoft.teams", "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings", "com.discord", "com.slack", "com.skype", "webex"
        )

        private val VIDEO_STREAM_PACKAGES = listOf(
            "com.google.android.youtube", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.netflix", "tv.abema", "jp.hamitv.tver", "tv.twitch", "com.twitter.android",
            "com.instagram.android", "com.amazon.avod", "org.videolan.vlc"
        )

        private val SIZE_KEYWORDS = listOf(
            "サイズ", "着丈", "身幅", "肩幅", "袖丈", "ウエスト", "ヒップ", "股下", "cm", "mm", "inch",
            "size", "length", "width", "chest", "shoulder", "waist", "sukat", "talla", "dimension"
        )

        private val INGREDIENT_KEYWORDS = listOf(
            "原材料", "成分", "内容量", "賞味期限", "消費期限", "アレルギー", "カロリー", "kcal", "栄養成分", "たんぱく質", "脂質", "炭水化物", "食塩相当量",
            "ingredients", "nutrition", "calories", "expiry", "best before", "allergen", "sangkap", "ingredientes"
        )

        private val CONDITION_KEYWORDS = listOf(
            "状態", "新品", "未使用", "美品", "傷", "汚れ", "スレ", "付属品", "箱あり", "動作確認", "ジャンク", "ランク",
            "condition", "brand new", "like new", "scratch", "stain", "used", "kondisyon", "estado"
        )

        private val BLANK_FIELD_KEYWORDS = listOf(
            "氏名", "お名前", "フリガナ", "ふりがな", "生年月日", "住所", "郵便番号", "電話番号", "メールアドレス",
            "署名", "押印", "印", "記入日", "年月日", "金額", "口座番号", "支店名", "備考", "チェック",
            "name", "signature", "date", "address", "phone", "email", "amount", "account", "pangalan", "lagda", "petsa", "tirahan", "nombre", "firma", "fecha"
        )

        private val OTP_REGEX = Regex("(?:認証コード|確認コード|ワンタイム|セキュリティコード|code|OTP|passcode|verification)[^0-9]{0,20}([0-9]{4,8})", RegexOption.IGNORE_CASE)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val embeddingGemma2 by lazy { EmbeddingGemma2Engine.getInstance(service) }
    private val textRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    // 1 & 4: Live Tagapagsalaysay State
    private val isLiveTagapagsalaysayActive = AtomicBoolean(false)
    private var lastFrameSignature = ""
    private var lastFrameVector: FloatArray? = null
    private var lastCommentaryTimeMs = 0L

    // 5: Kapamilya Bayanihan Link State
    private val isBayanihanLinkActive = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var lastReceivedPacketHash = 0

    private val liveCommentaryRunnable = object : Runnable {
        override fun run() {
            if (!isLiveTagapagsalaysayActive.get()) return
            performLiveTagapagsalaysayFrameTick()
            if (isLiveTagapagsalaysayActive.get()) {
                mainHandler.postDelayed(this, 2400L)
            }
        }
    }

    /**
     * Speaks the given Tagalog prompt unconditionally using the highest-quality natural Tagalog TTS (`fil-PH`).
     */
    fun speakUnconditionalTagalog(tagalogPrompt: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        val filLocale = Locale.forLanguageTag("fil-PH")
        service.speak(tagalogPrompt, queueMode, forcedLocale = filLocale)
    }

    // =========================================================================
    // 1 & 4 (Unified + Legacy Camera Commentary):
    // 🎬 Mata ng Kapamilya: Live Tagapagsalaysay
    // =========================================================================
    fun isLiveTagapagsalaysayRunning(): Boolean = isLiveTagapagsalaysayActive.get()

    fun toggleLiveTagapagsalaysay(launchCameraIfHome: Boolean = false) {
        if (isLiveTagapagsalaysayActive.get()) {
            stopLiveTagapagsalaysay()
        } else {
            startLiveTagapagsalaysay(launchCameraIfHome)
        }
    }

    fun startLiveTagapagsalaysay(launchCameraIfHome: Boolean = false) {
        isLiveTagapagsalaysayActive.set(true)
        lastFrameSignature = ""
        lastFrameVector = null
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_LIVE_ON, TextToSpeech.QUEUE_FLUSH)

        val pkg = service.currentActivePackage?.lowercase(Locale.ROOT) ?: ""
        val isHomeLauncher = pkg.contains("launcher") || pkg.contains("home")
        if (launchCameraIfHome && isHomeLauncher) {
            mainHandler.postDelayed({
                try {
                    val intent = Intent(service, LiveVisionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("MODE", "OBJECT")
                        putExtra("ALREADY_SPOKEN_TAGALOG", true)
                    }
                    service.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Camera fallback launch failed: ${e.message}")
                }
            }, 1800L)
        }

        mainHandler.removeCallbacks(liveCommentaryRunnable)
        mainHandler.postDelayed(liveCommentaryRunnable, 2200L)
    }

    fun stopLiveTagapagsalaysay() {
        if (!isLiveTagapagsalaysayActive.getAndSet(false)) return
        mainHandler.removeCallbacks(liveCommentaryRunnable)
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_LIVE_OFF, TextToSpeech.QUEUE_FLUSH)
    }

    private fun performLiveTagapagsalaysayFrameTick() {
        if (!isLiveTagapagsalaysayActive.get()) return
        val now = System.currentTimeMillis()
        if (now - lastCommentaryTimeMs < 2000L) return

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
        val pkg = (root?.packageName?.toString() ?: service.currentActivePackage ?: "").lowercase(Locale.ROOT)
        val isScreenShare = SCREEN_SHARE_PACKAGES.any { pkg.contains(it) }
        val isVideoStream = VIDEO_STREAM_PACKAGES.any { pkg.contains(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            if (!isLiveTagapagsalaysayActive.get()) {
                                try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                                return
                            }
                            val swBmp = try {
                                val hw = screenshot.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                hw.close()
                                copy
                            } catch (_: Exception) { null }

                            if (swBmp != null) {
                                analyzeLiveFrameBitmap(swBmp, root, isScreenShare, isVideoStream)
                            } else {
                                analyzeLiveAccessibilityFallback(root, isScreenShare, isVideoStream)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            analyzeLiveAccessibilityFallback(root, isScreenShare, isVideoStream)
                        }
                    }
                )
                return
            } catch (_: Exception) {}
        }
        analyzeLiveAccessibilityFallback(root, isScreenShare, isVideoStream)
    }

    private fun analyzeLiveFrameBitmap(
        bitmap: Bitmap,
        root: AccessibilityNodeInfo?,
        isScreenShare: Boolean,
        isVideoStream: Boolean
    ) {
        try {
            val visualDesc = sampleFrameVisualAtmosphere(bitmap)
            val img = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(img)
                .addOnSuccessListener { visionText ->
                    try { bitmap.recycle() } catch (_: Exception) {}
                    if (!isLiveTagapagsalaysayActive.get()) return@addOnSuccessListener

                    val blocks = visionText.textBlocks
                    val h = img.height.coerceAtLeast(1)
                    val telopLines = mutableListOf<String>()
                    val mainLines = mutableListOf<String>()

                    for (b in blocks) {
                        val txt = b.text.trim().replace("\n", " ")
                        if (txt.isEmpty()) continue
                        val box = b.boundingBox
                        if (box != null && box.centerY() >= (h * 0.65f)) {
                            telopLines.add(txt)
                        } else {
                            mainLines.add(txt)
                        }
                    }

                    val combinedSig = (telopLines + mainLines.take(4)).joinToString(" | ") + " :: $visualDesc"
                    val vec = embeddingGemma2.computeMultimodalMatryoshkaVector(
                        textTokens = combinedSig,
                        spatialZoneKey = if (isScreenShare) "screenshare" else "video"
                    )
                    val prevVec = lastFrameVector
                    val sim = if (prevVec != null) EmbeddingGemma2Engine.cosineSimilarity(prevVec, vec) else 0f

                    if (combinedSig == lastFrameSignature || (prevVec != null && sim > 0.96f)) {
                        return@addOnSuccessListener
                    }
                    lastFrameSignature = combinedSig
                    lastFrameVector = vec
                    lastCommentaryTimeMs = System.currentTimeMillis()

                    val commentary = buildLiveCommentaryUtterance(
                        isScreenShare = isScreenShare,
                        isVideoStream = isVideoStream,
                        visualDesc = visualDesc,
                        telopLines = telopLines,
                        mainLines = mainLines
                    )
                    if (commentary.isNotBlank() && !service.isSpeaking()) {
                        service.speak(commentary, TextToSpeech.QUEUE_ADD)
                    }
                }
                .addOnFailureListener {
                    try { bitmap.recycle() } catch (_: Exception) {}
                }
        } catch (_: Exception) {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    private fun analyzeLiveAccessibilityFallback(
        root: AccessibilityNodeInfo?,
        isScreenShare: Boolean,
        isVideoStream: Boolean
    ) {
        if (root == null || !isLiveTagapagsalaysayActive.get()) return
        val texts = mutableListOf<String>()
        collectVisibleTexts(root, texts, 0)
        val sig = texts.take(6).joinToString(" / ")
        if (sig.isBlank() || sig == lastFrameSignature) return
        lastFrameSignature = sig
        lastCommentaryTimeMs = System.currentTimeMillis()

        val prefix = when {
            isScreenShare -> SerenaLocalizationBridge.select(
                ja = "【画面共有実況】",
                en = "[Screen Share] ",
                tl = "[Screen Share] ",
                es = "[Pantalla compartida] ",
                nl = "[Schermdeling] "
            )
            isVideoStream -> SerenaLocalizationBridge.select(
                ja = "【AI副音声】",
                en = "[AI Audio Description] ",
                tl = "[AI Audio Description] ",
                es = "[Audiodescripción IA] ",
                nl = "[AI-audiodescriptie] "
            )
            else -> SerenaLocalizationBridge.select(
                ja = "【ライブ実況】",
                en = "[Live Commentary] ",
                tl = "[Live Tagapagsalaysay] ",
                es = "[Narración en vivo] ",
                nl = "[Live verslag] "
            )
        }
        if (!service.isSpeaking()) {
            service.speak("$prefix $sig", TextToSpeech.QUEUE_ADD)
        }
    }

    private fun buildLiveCommentaryUtterance(
        isScreenShare: Boolean,
        isVideoStream: Boolean,
        visualDesc: String,
        telopLines: List<String>,
        mainLines: List<String>
    ): String {
        val telopStr = telopLines.take(2).joinToString(" ")
        val mainStr = mainLines.take(3).joinToString(" / ")

        return if (isScreenShare) {
            val contentType = when {
                mainStr.contains("fun ") || mainStr.contains("class ") || mainStr.contains("public ") || mainStr.contains("{") ->
                    SerenaLocalizationBridge.select("ソースコード・エディタ画面", "Code Editor", "Code Editor", "Editor de código", "Code-editor")
                mainStr.contains("http") || mainStr.contains("www.") || mainStr.contains("検索") ->
                    SerenaLocalizationBridge.select("Webブラウザ画面", "Web Browser", "Web Browser", "Navegador web", "Webbrowser")
                mainStr.contains("合計") || mainStr.contains("%") || mainStr.contains("¥") || mainStr.contains("$") ->
                    SerenaLocalizationBridge.select("Excel・数値表・グラフ画面", "Spreadsheet / Chart", "Spreadsheet / Chart", "Hoja de cálculo / Gráfico", "Spreadsheet / Grafiek")
                else ->
                    SerenaLocalizationBridge.select("共有スライド・画面", "Shared Screen / Slide", "Shared Screen / Slide", "Pantalla / Diapositiva compartida", "Gedeeld scherm / Dia")
            }
            SerenaLocalizationBridge.select(
                ja = "【画面共有耳打ち実況：$contentType】映像状態：$visualDesc。" +
                        (if (mainStr.isNotEmpty()) "表示内容：$mainStr。" else "") +
                        (if (telopStr.isNotEmpty()) "下部テキスト：$telopStr" else ""),
                en = "[Screen Share Whisper: $contentType] Visual: $visualDesc. " +
                        (if (mainStr.isNotEmpty()) "Content: $mainStr. " else "") +
                        (if (telopStr.isNotEmpty()) "Bottom text: $telopStr" else ""),
                tl = "[Screen Share: $contentType] Biswal: $visualDesc. $mainStr $telopStr",
                es = "[Pantalla compartida: $contentType] Visual: $visualDesc. $mainStr $telopStr",
                nl = "[Schermdeling: $contentType] Visueel: $visualDesc. $mainStr $telopStr"
            )
        } else {
            SerenaLocalizationBridge.select(
                ja = "【AI副音声】映像：$visualDesc。" +
                        (if (telopStr.isNotEmpty()) "字幕テロップ：『$telopStr』。" else "") +
                        (if (mainStr.isNotEmpty()) "画面内文字：$mainStr" else ""),
                en = "[AI Audio Description] Scene: $visualDesc. " +
                        (if (telopStr.isNotEmpty()) "Subtitle: '$telopStr'. " else "") +
                        (if (mainStr.isNotEmpty()) "On-screen text: $mainStr" else ""),
                tl = "[AI Audio Description] Eksena: $visualDesc. Subtitle: $telopStr. $mainStr",
                es = "[Audiodescripción IA] Escena: $visualDesc. Subtítulo: '$telopStr'. $mainStr",
                nl = "[AI-audiodescriptie] Scène: $visualDesc. Ondertitel: '$telopStr'. $mainStr"
            )
        }
    }

    private fun sampleFrameVisualAtmosphere(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 4 || h <= 4) return "標準画面"
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var centerMotionContrast = 0L
        var count = 0
        val stepX = (w / 12).coerceAtLeast(1)
        val stepY = (h / 12).coerceAtLeast(1)
        var x = stepX / 2
        while (x < w) {
            var y = stepY / 2
            while (y < h) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                rSum += r
                gSum += g
                bSum += b
                if (x in (w / 4)..(w * 3 / 4) && y in (h / 4)..(h * 3 / 4)) {
                    centerMotionContrast += maxOf(r, g, b) - minOf(r, g, b)
                }
                count++
                y += stepY
            }
            x += stepX
        }
        if (count == 0) return "標準映像"
        val avgR = (rSum / count).toInt()
        val avgG = (gSum / count).toInt()
        val avgB = (bSum / count).toInt()
        val luma = (avgR * 299 + avgG * 587 + avgB * 114) / 1000
        val toneJa = when {
            luma >= 185 -> "明るく鮮明なハイキー映像"
            luma <= 65 -> "落ち着いたダークトーン・夜景または暗転シーン"
            avgR > avgB + 35 -> "暖色系・人物クローズアップまたは温かみのあるシーン"
            avgB > avgR + 35 -> "寒色系・屋外またはクールなスライド画面"
            else -> "バランスの取れたスタジオ・標準映像"
        }
        val toneEn = when {
            luma >= 185 -> "bright high-key scene"
            luma <= 65 -> "dark low-light scene"
            avgR > avgB + 35 -> "warm-toned close-up scene"
            avgB > avgR + 35 -> "cool-toned outdoor or slide view"
            else -> "balanced standard scene"
        }
        return SerenaLocalizationBridge.select(
            ja = toneJa,
            en = toneEn,
            tl = toneEn,
            es = toneEn,
            nl = toneEn
        )
    }

    // =========================================================================
    // 2: 🛍️ Mata ng Kapamilya: Tagasuri ng Paninda
    // (AI E-Commerce & Flea Market Product Image Inspector)
    // =========================================================================
    fun runTagasuriNgPaninda() {
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_PANINDA_START, TextToSpeech.QUEUE_FLUSH)

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
        val nodeTexts = mutableListOf<String>()
        if (root != null) collectVisibleTexts(root, nodeTexts, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val swBmp = try {
                                val hw = screenshot.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                hw.close()
                                copy
                            } catch (_: Exception) { null }

                            if (swBmp != null) {
                                val visualColorTone = sampleFrameVisualAtmosphere(swBmp)
                                val img = InputImage.fromBitmap(swBmp, 0)
                                textRecognizer.process(img)
                                    .addOnSuccessListener { visionText ->
                                        try { swBmp.recycle() } catch (_: Exception) {}
                                        val ocrLines = visionText.textBlocks.flatMap { b -> b.lines.map { it.text.trim() } }
                                        val allLines = (ocrLines + nodeTexts).distinct()
                                        val report = buildProductInspectionReport(allLines, visualColorTone)
                                        service.speak(report, TextToSpeech.QUEUE_ADD)
                                        sendOverBayanihanLinkIfActive("PANINDA", report)
                                    }
                                    .addOnFailureListener {
                                        try { swBmp.recycle() } catch (_: Exception) {}
                                        val report = buildProductInspectionReport(nodeTexts, "標準カラー")
                                        service.speak(report, TextToSpeech.QUEUE_ADD)
                                    }
                            } else {
                                val report = buildProductInspectionReport(nodeTexts, "標準カラー")
                                service.speak(report, TextToSpeech.QUEUE_ADD)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val report = buildProductInspectionReport(nodeTexts, "標準カラー")
                            service.speak(report, TextToSpeech.QUEUE_ADD)
                        }
                    }
                )
                return
            } catch (_: Exception) {}
        }

        val report = buildProductInspectionReport(nodeTexts, "標準カラー")
        service.speak(report, TextToSpeech.QUEUE_ADD)
    }

    private fun buildProductInspectionReport(allLines: List<String>, visualTone: String): String {
        val clean = allLines.map { it.trim() }.filter { it.isNotEmpty() }
        val sizes = clean.filter { line -> SIZE_KEYWORDS.any { kw -> line.contains(kw, ignoreCase = true) } }.take(4)
        val ingredients = clean.filter { line -> INGREDIENT_KEYWORDS.any { kw -> line.contains(kw, ignoreCase = true) } }.take(4)
        val conditions = clean.filter { line -> CONDITION_KEYWORDS.any { kw -> line.contains(kw, ignoreCase = true) } }.take(4)
        val prices = clean.filter { it.contains("¥") || it.contains("円") || it.contains("$") || it.contains("₱") || it.contains("税込") }.take(2)
        val headline = clean.firstOrNull { it.length >= 4 } ?: "商品情報"

        val sizePartJa = if (sizes.isNotEmpty()) "【サイズ・寸法表】${sizes.joinToString(" / ")}。" else "【サイズ・寸法】画像内に特記サイズ表はありません。"
        val ingPartJa = if (ingredients.isNotEmpty()) "【原材料・成分・期限】${ingredients.joinToString(" / ")}。" else ""
        val condPartJa = if (conditions.isNotEmpty()) "【商品の状態・傷・付属品】${conditions.joinToString(" / ")}。" else "【外観・色味】$visualTone。"
        val pricePartJa = if (prices.isNotEmpty()) "【価格表示】${prices.joinToString(" / ")}。" else ""

        return SerenaLocalizationBridge.select(
            ja = "【AI商品画像鑑定結果】対象：『$headline』。$pricePartJa $sizePartJa $ingPartJa $condPartJa",
            en = "[AI Product Inspection] Item: '$headline'. " +
                    (if (prices.isNotEmpty()) "Price: ${prices.joinToString(", ")}. " else "") +
                    (if (sizes.isNotEmpty()) "Size/Dimensions: ${sizes.joinToString("; ")}. " else "") +
                    (if (ingredients.isNotEmpty()) "Ingredients/Expiry: ${ingredients.joinToString("; ")}. " else "") +
                    (if (conditions.isNotEmpty()) "Condition: ${conditions.joinToString("; ")}. " else "Visual tone: $visualTone."),
            tl = "[Pagsusuri ng Paninda] Produkto: '$headline'. Sukat: ${sizes.joinToString(", ")}. Sangkap: ${ingredients.joinToString(", ")}. Kondisyon: ${conditions.joinToString(", ")}.",
            es = "[Inspección de Producto IA] Artículo: '$headline'. Tallas: ${sizes.joinToString(", ")}. Ingredientes: ${ingredients.joinToString(", ")}. Estado: ${conditions.joinToString(", ")}.",
            nl = "[AI Productinspectie] Artikel: '$headline'. Afmetingen: ${sizes.joinToString(", ")}. Staat: ${conditions.joinToString(", ")}."
        )
    }

    // =========================================================================
    // 3: 📑 Bayanihan Gabay: Tagabasa ng Dokumento at Talaan
    // (Scanned Image PDF, Complex Table Structurer & Blank Field Guide)
    // =========================================================================
    data class StructuredDocItem(
        val icon: String,
        val spokenLabel: String,
        val centerX: Float,
        val centerY: Float,
        val isBlankField: Boolean
    )

    fun runTagabasaNgDokumentoAtTalaan() {
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_DOKUMENTO_START, TextToSpeech.QUEUE_FLUSH)

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val swBmp = try {
                                val hw = screenshot.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                hw.close()
                                copy
                            } catch (_: Exception) { null }

                            if (swBmp != null) {
                                val img = InputImage.fromBitmap(swBmp, 0)
                                textRecognizer.process(img)
                                    .addOnSuccessListener { visionText ->
                                        try { swBmp.recycle() } catch (_: Exception) {}
                                        val structured = buildStructuredDocumentFromOcrAndTree(visionText.textBlocks.flatMap { it.lines }, root)
                                        presentStructuredDocumentDialog(structured)
                                    }
                                    .addOnFailureListener {
                                        try { swBmp.recycle() } catch (_: Exception) {}
                                        val structured = buildStructuredDocumentFromTreeOnly(root)
                                        presentStructuredDocumentDialog(structured)
                                    }
                            } else {
                                val structured = buildStructuredDocumentFromTreeOnly(root)
                                presentStructuredDocumentDialog(structured)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val structured = buildStructuredDocumentFromTreeOnly(root)
                            presentStructuredDocumentDialog(structured)
                        }
                    }
                )
                return
            } catch (_: Exception) {}
        }

        val structured = buildStructuredDocumentFromTreeOnly(root)
        presentStructuredDocumentDialog(structured)
    }

    private fun buildStructuredDocumentFromOcrAndTree(
        ocrLines: List<com.google.mlkit.vision.text.Text.Line>,
        root: AccessibilityNodeInfo?
    ): List<StructuredDocItem> {
        val items = mutableListOf<StructuredDocItem>()

        if (ocrLines.isNotEmpty()) {
            val sorted = ocrLines.sortedWith { a, b ->
                val ba = a.boundingBox ?: Rect()
                val bb = b.boundingBox ?: Rect()
                if (abs(ba.centerY() - bb.centerY()) <= 28) {
                    ba.left.compareTo(bb.left)
                } else {
                    ba.centerY().compareTo(bb.centerY())
                }
            }

            // Group lines into logical rows by Y proximity
            val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
            for (line in sorted) {
                val box = line.boundingBox ?: continue
                val lastRow = rows.lastOrNull()
                val lastY = lastRow?.firstOrNull()?.boundingBox?.centerY() ?: -999
                if (lastRow != null && abs(box.centerY() - lastY) <= 32) {
                    lastRow.add(line)
                } else {
                    rows.add(mutableListOf(line))
                }
            }

            for ((rowIdx, row) in rows.take(25).withIndex()) {
                val rowText = row.mapIndexed { colIdx, l ->
                    if (row.size > 1) "${colIdx + 1}列: ${l.text.trim()}" else l.text.trim()
                }.joinToString(" | ")

                val firstBox = row.first().boundingBox ?: Rect(500, 500, 600, 550)
                val lastBox = row.last().boundingBox ?: firstBox
                val isBlankCandidate = BLANK_FIELD_KEYWORDS.any { kw -> rowText.contains(kw, ignoreCase = true) }

                if (isBlankCandidate) {
                    val targetX = (lastBox.right + 120).toFloat()
                    val targetY = lastBox.centerY().toFloat()
                    val label = SerenaLocalizationBridge.select(
                        ja = "【記入欄・空欄ガイド ${rowIdx + 1}行目】$rowText （ダブルタップで記入枠の座標をタップ）",
                        en = "[Blank Field Row ${rowIdx + 1}] $rowText (Double-tap to click input box)",
                        tl = "[Patlang na Susulatan Row ${rowIdx + 1}] $rowText",
                        es = "[Campo para rellenar Fila ${rowIdx + 1}] $rowText",
                        nl = "[Invulveld Rij ${rowIdx + 1}] $rowText"
                    )
                    items.add(StructuredDocItem("✍️", label, targetX, targetY, true))
                } else {
                    val label = SerenaLocalizationBridge.select(
                        ja = "${rowIdx + 1}行目：$rowText",
                        en = "Row ${rowIdx + 1}: $rowText",
                        tl = "Hanay ${rowIdx + 1}: $rowText",
                        es = "Fila ${rowIdx + 1}: $rowText",
                        nl = "Rij ${rowIdx + 1}: $rowText"
                    )
                    items.add(StructuredDocItem("📑", label, firstBox.centerX().toFloat(), firstBox.centerY().toFloat(), false))
                }
            }
        }

        if (items.isEmpty()) {
            return buildStructuredDocumentFromTreeOnly(root)
        }
        return items
    }

    private fun buildStructuredDocumentFromTreeOnly(root: AccessibilityNodeInfo?): List<StructuredDocItem> {
        val items = mutableListOf<StructuredDocItem>()
        if (root == null) return items
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collectNodes(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 18 || nodes.size >= 40) return
            val txt = (n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.contentDescription?.toString()?.trim() ?: "").trim()
            if (txt.isNotEmpty() || n.isEditable) {
                nodes.add(n)
            }
            for (i in 0 until n.childCount.coerceAtMost(25)) {
                collectNodes(n.getChild(i), depth + 1)
            }
        }
        collectNodes(root, 0)

        for ((idx, n) in nodes.withIndex()) {
            val rect = Rect()
            n.getBoundsInScreen(rect)
            val txt = (n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.contentDescription?.toString()?.trim()
                ?: "未記入の入力欄").trim()
            val isBlank = n.isEditable || BLANK_FIELD_KEYWORDS.any { txt.contains(it, ignoreCase = true) }
            val label = if (isBlank) {
                SerenaLocalizationBridge.select(
                    ja = "【記入欄 ${idx + 1}】$txt （ダブルタップで入力欄へ移動）",
                    en = "[Input Field ${idx + 1}] $txt (Double-tap to focus)",
                    tl = "[Patlang ${idx + 1}] $txt",
                    es = "[Campo ${idx + 1}] $txt",
                    nl = "[Veld ${idx + 1}] $txt"
                )
            } else {
                "${idx + 1}行目：$txt"
            }
            items.add(
                StructuredDocItem(
                    icon = if (isBlank) "✍️" else "📑",
                    spokenLabel = label,
                    centerX = rect.centerX().toFloat(),
                    centerY = rect.centerY().toFloat(),
                    isBlankField = isBlank
                )
            )
        }
        return items
    }

    private fun presentStructuredDocumentDialog(items: List<StructuredDocItem>) {
        if (items.isEmpty()) {
            val emptyMsg = SerenaLocalizationBridge.select(
                ja = "書類または表のテキストが見つかりませんでした。",
                en = "No document or table text found on this screen.",
                tl = "Walang nakitang teksto ng dokumento o talahanayan.",
                es = "No se encontró texto de documento o tabla.",
                nl = "Geen document- of tabeltekst gevonden."
            )
            service.speak(emptyMsg, TextToSpeech.QUEUE_ADD)
            return
        }

        val blankCount = items.count { it.isBlankField }
        val summarySpeech = SerenaLocalizationBridge.select(
            ja = "書類・表のAI構造化が完了しました。全${items.size}行、うち記入欄候補は${blankCount}箇所です。1番目：${items[0].spokenLabel}",
            en = "Document & Table structured: ${items.size} rows, including $blankCount blank/input fields. First: ${items[0].spokenLabel}",
            tl = "Naayos na ang dokumento: ${items.size} hanay, $blankCount patlang na susulatan. Una: ${items[0].spokenLabel}",
            es = "Documento estructurado: ${items.size} filas, $blankCount campos para rellenar. Primero: ${items[0].spokenLabel}",
            nl = "Document gestructureerd: ${items.size} rijen, $blankCount invulvelden."
        )
        service.speak(summarySpeech, TextToSpeech.QUEUE_ADD)
        sendOverBayanihanLinkIfActive("DOKUMENTO", items.joinToString("\n") { it.spokenLabel })

        val menuItems = items.map { docItem ->
            serenaMenuItem(docItem.icon, docItem.spokenLabel) {
                mainHandler.postDelayed({
                    service.performCoordinateTap(docItem.centerX, docItem.centerY)
                }, 220L)
            }
        }

        mainHandler.post {
            try {
                val dlg = serenaMenuDialog(service, false, menuItems)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (e: Exception) {
                Log.w(TAG, "Structured document dialog error: ${e.message}")
            }
        }
    }

    // =========================================================================
    // 5: 🤝 Kapamilya Bayanihan Link
    // (Android ⇄ Windows Direct Local LAN P2P Bridge)
    // =========================================================================
    fun isBayanihanLinkRunning(): Boolean = isBayanihanLinkActive.get()

    fun toggleBayanihanLink(lastSpokenText: String? = null) {
        if (isBayanihanLinkActive.get()) {
            // If already active and user selects from menu or copies text, send latest text immediately or toggle off
            if (!lastSpokenText.isNullOrBlank()) {
                sendOverBayanihanLinkIfActive("CLIPBOARD", lastSpokenText, announceSent = true)
            } else {
                stopBayanihanLink()
            }
        } else {
            startBayanihanLink(lastSpokenText)
        }
    }

    fun startBayanihanLink(initialPayload: String? = null) {
        if (isBayanihanLinkActive.getAndSet(true)) return
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_LINK_ON, TextToSpeech.QUEUE_FLUSH)

        engineScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(BAYANIHAN_UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                udpSocket = socket
                val buf = ByteArray(8192)
                while (isBayanihanLinkActive.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    if (msg.startsWith(BAYANIHAN_MAGIC)) {
                        handleIncomingBayanihanPacket(msg.removePrefix(BAYANIHAN_MAGIC))
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "Bayanihan Link listener stopped: ${e.message}")
            }
        }

        if (!initialPayload.isNullOrBlank()) {
            sendOverBayanihanLinkIfActive("CLIPBOARD", initialPayload, announceSent = false)
        }
    }

    fun stopBayanihanLink() {
        if (!isBayanihanLinkActive.getAndSet(false)) return
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
        service.soundHelper?.playActionDone()
        speakUnconditionalTagalog(GREETING_LINK_OFF, TextToSpeech.QUEUE_FLUSH)
    }

    fun onNotificationOrTextObservedForOtp(text: String) {
        if (!isBayanihanLinkActive.get() || text.isBlank()) return
        val match = OTP_REGEX.find(text) ?: return
        val otpCode = match.groupValues.getOrNull(1) ?: return
        if (otpCode.isNotEmpty()) {
            sendOverBayanihanLinkIfActive("OTP", otpCode, announceSent = true)
        }
    }

    fun onCameraOcrOrObjectResult(text: String) {
        if (!isBayanihanLinkActive.get() || text.isBlank()) return
        sendOverBayanihanLinkIfActive("CAMERA_OCR", text, announceSent = false)
    }

    fun sendOverBayanihanLinkIfActive(kind: String, content: String, announceSent: Boolean = false) {
        if (!isBayanihanLinkActive.get() || content.isBlank()) return
        engineScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("source", "android")
                    put("kind", kind)
                    put("content", content.take(3500))
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                val payload = (BAYANIHAN_MAGIC + json).toByteArray(StandardCharsets.UTF_8)
                val hash = json.hashCode()
                lastReceivedPacketHash = hash // Prevent self-echo

                DatagramSocket().use { sender ->
                    sender.broadcast = true
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    sender.send(DatagramPacket(payload, payload.size, broadcastAddr, BAYANIHAN_UDP_PORT))
                }
                if (announceSent) {
                    mainHandler.post {
                        speakUnconditionalTagalog(GREETING_LINK_SENT, TextToSpeech.QUEUE_ADD)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bayanihan Link send error: ${e.message}")
            }
        }
    }

    private fun handleIncomingBayanihanPacket(jsonStr: String) {
        try {
            val hash = jsonStr.hashCode()
            if (hash == lastReceivedPacketHash) return
            lastReceivedPacketHash = hash

            val obj = JSONObject(jsonStr)
            val source = obj.optString("source", "")
            if (source == "android") return
            val content = obj.optString("content", "").trim()
            if (content.isEmpty()) return

            mainHandler.post {
                try {
                    val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("KapamilyaBayanihanLink", content))
                    service.soundHelper?.playActionDone()
                    speakUnconditionalTagalog(GREETING_LINK_RECEIVED, TextToSpeech.QUEUE_FLUSH)
                    service.speak(content, TextToSpeech.QUEUE_ADD)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 16 || out.size >= 35) return
        val t = (node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.trim() ?: "").trim()
        if (t.isNotEmpty()) out.add(t)
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            collectVisibleTexts(node.getChild(i), out, depth + 1)
        }
    }

    fun release() {
        isLiveTagapagsalaysayActive.set(false)
        isBayanihanLinkActive.set(false)
        mainHandler.removeCallbacks(liveCommentaryRunnable)
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
    }
}
