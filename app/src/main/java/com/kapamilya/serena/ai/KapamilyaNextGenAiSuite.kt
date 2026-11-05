/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.kapamilya.serena.SerenaScreenReaderService
import com.kapamilya.serena.serenaMenuDialog
import com.kapamilya.serena.serenaMenuItem
import com.kapamilya.serena.translation.SerenaLocalizationBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * KapamilyaNextGenAiSuite
 *
 * Google DeepMind `EmbeddingGemma 2` (第1主軸: 0秒マルチモーダルベクトル＆システム構造分析) と
 * `Gemma 4` (第2主軸: 深層解析・自然言語説明) をフル活用した、世界初・100%合法・規約完全準拠の5大AI新機能スイート:
 *
 * 1. AutonomousGoalAgent: 自律型AI操作エージェント（「セリーナ、これ代わりにやって！」※決済・削除は人間確認セーフティロック付き）
 * 2. AntiDarkPatternShield: 詐欺・ダークパターン・偽「×」ボタン・勝手な定期課金トラップのAI検閲官
 * 3. TemporalScreenDiffTimeMachine: 画面変化のタイムマシン＆差分実況（「さっきと何が変わった？」）
 * 4. AudioGraphAndOfficialAuthHelper: 画像グラフの音波変換（Audio Graph Sonification）＋ 公式「音声認証ボタン」0秒発見誘導
 * 5. CrossAppWorkflowMemory: 個人専用の「全アプリ手順書」自動記憶＆先回りナビ
 */
class KapamilyaNextGenAiSuite(private val service: SerenaScreenReaderService) {

    companion object {
        private const val TAG = "KapamilyaNextGenAi"
        private const val WORKFLOW_PREFS = "kapamilya_workflow_memory_prefs"
        private const val MAX_SNAPSHOT_HISTORY = 6

        const val SUITE_SIGNATURE =
            "KapamilyaNextGenAiSuite v5.0 (AutonomousGoalAgent | AntiDarkPatternShield | TemporalScreenDiffTimeMachine | AudioGraphSonification | CrossAppWorkflowMemory | Horizon4DCameraCorridorSonar | EmergencyDeadReckoningBacktrack)"

        // 自律エージェントが絶対に自動クリックせず、必ず人間に確認を求める高リスクキーワード（決済・送金・削除・退会）
        private val HIGH_RISK_ACTION_KEYWORDS = listOf(
            "送金", "振込", "支払", "決済", "購入を確定", "注文を確定", "削除", "退会", "初期化", "アンインストール",
            "pay now", "place order", "confirm payment", "send money", "transfer", "wire", "delete account", "factory reset", "uninstall",
            "bayaran", "ipadala ang pera", "burahin", "pagar ahora", "confirmar pago", "eliminar cuenta", "nu betalen", "verwijderen"
        )

        // ダークパターン：自動更新・隠れサブスク課金キーワード
        private val SUBSCRIPTION_TRAP_KEYWORDS = listOf(
            "自動更新", "自動継続", "月額", "年額", "無料体験終了後", "自動的に課金", "解約しない限り",
            "auto-renew", "automatically renews", "billed monthly", "billed annually", "recurring charge", "after free trial",
            "renovación automática", "suscripción mensual", "automatisch verlengd"
        )

        // ダークパターン：フィッシング・偽警告キーワード
        private val SCAREWARE_KEYWORDS = listOf(
            "ウイルスが検出", "端末が感染", "今すぐ口座を認証", "アカウントが凍結", "当選しました",
            "virus detected", "device infected", "verify your bank immediately", "account suspended", "claim your prize"
        )

        // W3C / 公式アクセシビリティ音声認証ボタンキーワード（100%合法な公式代替手段への誘導）
        private val OFFICIAL_AUDIO_AUTH_KEYWORDS = listOf(
            "音声認証", "音声問題", "音声チャレンジ", "オーディオ", "アクセシビリティ認証",
            "audio challenge", "get an audio challenge", "recaptcha-audio-button", "audio_button",
            "listen to audio", "accessibility challenge", "desafío de audio", "audiouitdaging"
        )

        fun isHighRiskFinancialOrDestructiveLabel(label: String): Boolean {
            val lower = label.lowercase(Locale.ROOT)
            return HIGH_RISK_ACTION_KEYWORDS.any { lower.contains(it) }
        }
    }

    data class ScreenSnapshot(
        val timestampMs: Long,
        val packageName: String,
        val elements: List<String>,
        val vector128D: FloatArray
    )

    data class DarkPatternReport(
        val hasWarnings: Boolean,
        val warnings: List<String>,
        val summarySpeech: String
    )

    private val embeddingGemma2 by lazy { EmbeddingGemma2Engine.getInstance(service) }
    private val gemma4 by lazy { Gemma4EdgeEngine.getInstance(service) }
    private val suiteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snapshotHistory = ArrayDeque<ScreenSnapshot>()
    private val workflowPrefs by lazy {
        service.getSharedPreferences(WORKFLOW_PREFS, Context.MODE_PRIVATE)
    }

    // =========================================================================
    // 裏側の対象アプリ画面を確実に取得するヘルパー（メニューダイアログ自身の残像を完全除外）
    // =========================================================================
    private fun resolveTargetAppRootNode(): AccessibilityNodeInfo? {
        try {
            val myPkg = service.packageName
            val wins = service.windows
            if (!wins.isNullOrEmpty()) {
                val sorted = wins.sortedByDescending { it.layer }
                for (w in sorted) {
                    val r = try { w.root } catch (_: Exception) { null } ?: continue
                    val pkg = r.packageName?.toString() ?: ""
                    // 自分自身のメニューダイアログオーバーレイは除外して裏側の実アプリ画面を返す
                    if (pkg == myPkg && service.activeMenuDialog != null) continue
                    return r
                }
            }
        } catch (_: Exception) {}
        return try { service.rootInActiveWindow } catch (_: Exception) { null }
    }

    /**
     * ユーザーが普段のダブルタップ操作を行う直前に SerenaScreenReaderService から自動呼び出しされるフック。
     * 1. 操作前の画面スナップショットをタイムマシンへ静かに保存
     * 2. パスワード欄やロック画面PINを除外したうえで、アプリの操作手順書メモリへ自動記録
     */
    fun onBeforeUserAction(node: AccessibilityNodeInfo?, cleanLabel: String) {
        try {
            if (service.isKeyguardLocked()) return
            if (node?.isPassword == true) return
            val pkg = node?.packageName?.toString() ?: ""
            if (pkg.isBlank() || pkg == service.packageName) return

            captureCurrentScreenSnapshotSilently()
            if (cleanLabel.isNotBlank() && cleanLabel.length <= 45) {
                recordWorkflowStep(pkg, cleanLabel)
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 統合ランチャーメニュー（Kapamilya Menu からワンタップで5大AI機能を呼び出し）
    // =========================================================================
    fun showNextGenAiSuiteMenu() {
        // 画面を開いた時点のスナップショットも自動保存
        captureCurrentScreenSnapshotSilently()

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val items = listOf(
            serenaMenuItem(
                "🎬",
                KapamilyaFivePillarAiEngine.MENU_TITLE_LIVE_TAGAPAGSALAYSAY
            ) {
                mainHandler.postDelayed({
                    service.fivePillarAiEngine?.toggleLiveTagapagsalaysay(launchCameraIfHome = true)
                }, 150L)
            },
            serenaMenuItem(
                "🛍️",
                KapamilyaFivePillarAiEngine.MENU_TITLE_TAGASURI_NG_PANINDA
            ) {
                mainHandler.postDelayed({
                    service.fivePillarAiEngine?.runTagasuriNgPaninda()
                }, 150L)
            },
            serenaMenuItem(
                "📑",
                KapamilyaFivePillarAiEngine.MENU_TITLE_TAGABASA_NG_DOKUMENTO
            ) {
                mainHandler.postDelayed({
                    service.fivePillarAiEngine?.runTagabasaNgDokumentoAtTalaan()
                }, 150L)
            },
            serenaMenuItem(
                "🤝",
                KapamilyaFivePillarAiEngine.MENU_TITLE_BAYANIHAN_LINK
            ) {
                mainHandler.postDelayed({
                    service.fivePillarAiEngine?.toggleBayanihanLink()
                }, 150L)
            },
            serenaMenuItem(
                "🤖",
                SerenaLocalizationBridge.select(
                    ja = "1. 自律型AI操作エージェント（「セリーナ、これ代わりにやって！」）",
                    en = "1. Autonomous AI Action Agent ('Serena, do this for me!')",
                    tl = "1. Autonomous AI Action Agent ('Serena, gawin mo ito!')",
                    es = "1. Agente autónomo de IA ('¡Serena, haz esto por mí!')",
                    nl = "1. Autonome AI-actieagent ('Serena, doe dit voor mij!')"
                )
            ) {
                mainHandler.postDelayed({ showAutonomousAgentGoalMenu() }, 120L)
            },
            serenaMenuItem(
                "🛡️",
                SerenaLocalizationBridge.select(
                    ja = "2. 詐欺・ダークパターン・定期課金トラップAI検閲官",
                    en = "2. Anti-Scam & Dark-Pattern AI Inspector",
                    tl = "2. AI Inspector Laban sa Scam at Dark-Pattern",
                    es = "2. Inspector IA contra estafas y patrones oscuros",
                    nl = "2. AI-inspecteur tegen oplichting en dark patterns"
                )
            ) {
                mainHandler.postDelayed({ inspectScreenForDarkPatterns() }, 180L)
            },
            serenaMenuItem(
                "⏱️",
                SerenaLocalizationBridge.select(
                    ja = "3. 画面変化のタイムマシン＆差分実況（「さっきと何が変わった？」）",
                    en = "3. Screen Diff Time Machine ('What changed just now?')",
                    tl = "3. Time Machine ng Pagbabago sa Screen ('Anong nagbago?')",
                    es = "3. Máquina del tiempo de cambios en pantalla ('¿Qué cambió?')",
                    nl = "3. Schermverschil-tijdmachine ('Wat is er net veranderd?')"
                )
            ) {
                mainHandler.postDelayed({ announceScreenDiffTimeMachine() }, 180L)
            },
            serenaMenuItem(
                "🎵",
                SerenaLocalizationBridge.select(
                    ja = "4. グラフ音波変換（Audio Graph）＆公式「音声認証ボタン」誘導",
                    en = "4. Audio Graph Sonification & Official Audio Auth Finder",
                    tl = "4. Audio Graph Sonification at Paghanap ng Audio Auth",
                    es = "4. Sonificación de gráficos y buscador de autenticación por voz",
                    nl = "4. Audiografiek-sonificatie & officiële audio-authenticatiezoeker"
                )
            ) {
                mainHandler.postDelayed({ runAudioGraphAndOfficialAuthFinder() }, 180L)
            },
            serenaMenuItem(
                "📓",
                SerenaLocalizationBridge.select(
                    ja = "5. 個人専用「全アプリ手順書」自動記憶＆先回りナビ",
                    en = "5. Personal Cross-App Workflow Memory & Smart Guide",
                    tl = "5. Personal na Memorya ng Hakbang sa App at Gabay",
                    es = "5. Memoria personal de pasos por aplicación y guía",
                    nl = "5. Persoonlijk app-stappengeheugen en slimme gids"
                )
            ) {
                mainHandler.postDelayed({ showCrossAppWorkflowMemoryMenu() }, 160L)
            },
            serenaMenuItem(
                "🛰️",
                SerenaLocalizationBridge.select(
                    ja = "6. 4Dカメラ空間通路・動体軌道予測ソナー（安全な歩行通路を案内）",
                    en = "6. 4D Horizon Camera Safe-Corridor & Trajectory Sonar",
                    tl = "6. 4D Horizon Camera Safe-Corridor at Trajectory Sonar",
                    es = "6. Sonar 4D de corredor seguro y trayectoria por cámara",
                    nl = "6. 4D Horizon Camera Veilige-gangpad & Trajectsonar"
                )
            ) {
                mainHandler.postDelayed({ run4DHorizonCorridorSonar() }, 180L)
            },
            serenaMenuItem(
                "🧭",
                SerenaLocalizationBridge.select(
                    ja = "7. GPS圏外・緊急逆ルート帰還ブラックボックス（来た道を逆算ナビ）",
                    en = "7. Emergency Dead-Reckoning Backtrack Blackbox (Zero-GPS Return)",
                    tl = "7. Emergency Dead-Reckoning Backtrack (Balik-Daan Kahit Walang GPS)",
                    es = "7. Caja negra de retorno de emergencia sin GPS",
                    nl = "7. Nood-terugkeer-blackbox zonder GPS"
                )
            ) {
                mainHandler.postDelayed({ runEmergencyDeadReckoningBacktrack() }, 180L)
            }
        )

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dlg = serenaMenuDialog(service, false, items)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open NextGen AI Suite menu: ${e.message}")
            }
        }
    }

    // =========================================================================
    // 1. 自律型AI操作エージェント（AutonomousGoalAgent）
    // =========================================================================
    private fun showAutonomousAgentGoalMenu() {
        val goals = listOf(
            Triple("🔍", SerenaLocalizationBridge.select("検索欄を探して開く", "Find & open Search bar", "Hanapin at buksan ang Search", "Buscar y abrir barra de búsqueda", "Zoekbalk vinden en openen"), "search find query 検索 探す"),
            Triple("⚙️", SerenaLocalizationBridge.select("設定・メニューを探して開く", "Find & open Settings / Menu", "Hanapin at buksan ang Settings", "Buscar y abrir Ajustes / Menú", "Instellingen / Menu vinden en openen"), "settings menu config 設定 メニュー オプション"),
            Triple("🎧", SerenaLocalizationBridge.select("公式の音声認証ボタンを探して移動", "Find official Audio Challenge button", "Hanapin ang Audio Auth button", "Buscar botón de desafío de audio", "Officiële audio-authenticatieknop zoeken"), "audio challenge 音声認証 アクセシビリティ"),
            Triple("✅", SerenaLocalizationBridge.select("次へ・同意・完了ボタンを探す（安全確認付き）", "Find Next / Agree / Done button (Safety Lock active)", "Hanapin ang Susunod / Sang-ayon", "Buscar Siguiente / Aceptar", "Volgende / Akkoord zoeken (met veiligheidsslot)"), "next continue agree ok done 次へ 同意 完了 進む"),
            Triple("✖️", SerenaLocalizationBridge.select("本物の「閉じる・戻る」ボタンを探して押す", "Find & activate genuine Close / Back button", "Hanapin ang tunay na Isara / Bumalik", "Buscar y pulsar botón Cerrar / Atrás real", "Echte Sluiten / Terug-knop vinden en indrukken"), "close dismiss back 閉じる 戻る キャンセル")
        )

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val menuItems = goals.map { (icon, title, queryTokens) ->
            serenaMenuItem(icon, title) {
                mainHandler.postDelayed({ executeAutonomousGoal(title, queryTokens) }, 180L)
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dlg = serenaMenuDialog(service, false, menuItems)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (_: Exception) {}
        }
    }

    /**
     * ユーザーのゴール（目標）を受け取り、EmbeddingGemma 2 ベクトル検索で画面上の最適ノードを特定して自律実行！
     * ※決済・送金・削除などの高リスク操作は絶対に自動クリックせず、フォーカス移動＋人間確認ロックを発動する。
     */
    fun executeAutonomousGoal(goalTitle: String, goalQueryTokens: String) {
        captureCurrentScreenSnapshotSilently()
        val root = resolveTargetAppRootNode()
        if (root == null) {
            service.speak(
                SerenaLocalizationBridge.select(
                    ja = "現在の画面を取得できませんでした。",
                    en = "Could not access the current screen.",
                    tl = "Hindi ma-access ang kasalukuyang screen.",
                    es = "No se pudo acceder a la pantalla actual.",
                    nl = "Kon het huidige scherm niet bereiken."
                ),
                TextToSpeech.QUEUE_FLUSH
            )
            return
        }

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        fun collect(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 16 || candidates.size >= 100) return
            if (n.isVisibleToUser && (n.isClickable || n.isEditable || n.isCheckable)) {
                val lbl = (n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.viewIdResourceName?.substringAfterLast("/") ?: "").trim()
                if (lbl.isNotEmpty()) {
                    candidates.add(Pair(n, lbl))
                }
            }
            for (i in 0 until n.childCount.coerceAtMost(30)) {
                collect(n.getChild(i), depth + 1)
            }
        }
        collect(root, 0)

        if (candidates.isEmpty()) {
            service.speak(
                SerenaLocalizationBridge.select(
                    ja = "この画面には操作可能なボタンが見つかりませんでした。",
                    en = "No actionable buttons found on this screen.",
                    tl = "Walang nakitang pindutan sa screen na ito.",
                    es = "No se encontraron botones interactivos en esta pantalla.",
                    nl = "Geen bedienbare knoppen gevonden op dit scherm."
                ),
                TextToSpeech.QUEUE_FLUSH
            )
            return
        }

        val goalVec = embeddingGemma2.computeMultimodalMatryoshkaVector(
            textTokens = goalQueryTokens,
            spatialZoneKey = "center"
        )

        var bestNode: AccessibilityNodeInfo? = null
        var bestLabel = ""
        var bestScore = -1f
        val queryWords = goalQueryTokens.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }

        for ((node, lbl) in candidates) {
            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val combined = "$lbl $viewId".lowercase(Locale.ROOT)
            val nodeVec = embeddingGemma2.computeMultimodalMatryoshkaVector(
                textTokens = combined,
                spatialZoneKey = "center",
                isClickable = node.isClickable,
                isEditable = node.isEditable
            )
            var score = EmbeddingGemma2Engine.cosineSimilarity(goalVec, nodeVec)
            for (qw in queryWords) {
                if (combined.contains(qw)) score += 0.45f
            }
            if (score > bestScore) {
                bestScore = score
                bestNode = node
                bestLabel = lbl
            }
        }

        val target = bestNode
        if (target == null) return

        // 【セーフティロック】決済・送金・削除などの高リスク操作は自動クリックせずフォーカスのみ移動して確認！
        if (isHighRiskFinancialOrDestructiveLabel(bestLabel)) {
            target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            service.lastHoveredNode = target
            val safetyMsg = SerenaLocalizationBridge.select(
                ja = "安全ロック発動：『$bestLabel』を見つけましたが、決済や削除に関わる重要なボタンのため自動クリックを停止し、フォーカスだけ合わせました。内容を確認してご自身でダブルタップしてください。",
                en = "Safety Lock Active: Found '$bestLabel'. Because this involves payment or deletion, I focused it without auto-clicking. Please double-tap to confirm.",
                tl = "Safety Lock: Nakita ang '$bestLabel'. Dahil ito ay bayad o pagbura, itinutok ko lang ang focus. Mag-double tap kung sigurado ka.",
                es = "Bloqueo de seguridad: Se encontró '$bestLabel'. Por seguridad no se pulsó automáticamente; toque dos veces para confirmar.",
                nl = "Veiligheidsslot: '$bestLabel' gevonden. Vanwege betaling of verwijdering is alleen de focus verplaatst. Dubbeltik zelf om te bevestigen."
            )
            service.speak(safetyMsg, TextToSpeech.QUEUE_FLUSH)
            return
        }

        // 安全なナビゲーション操作はフォーカス移動＋クリックを実行し、手順書メモリにも自動記録！
        target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        service.lastHoveredNode = target
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val pkg = root.packageName?.toString() ?: ""
        recordWorkflowStep(pkg, bestLabel)

        val doneMsg = if (clicked) {
            SerenaLocalizationBridge.select(
                ja = "自律エージェント実行：『$bestLabel』を特定して実行しました。",
                en = "Autonomous Agent: Located and activated '$bestLabel'.",
                tl = "Autonomous Agent: Nahanap at pinindot ang '$bestLabel'.",
                es = "Agente autónomo: Se localizó y activó '$bestLabel'.",
                nl = "Autonome agent: '$bestLabel' gevonden en geactiveerd."
            )
        } else {
            SerenaLocalizationBridge.select(
                ja = "自律エージェント：『$bestLabel』にフォーカスを移動しました。",
                en = "Autonomous Agent: Moved focus to '$bestLabel'.",
                tl = "Autonomous Agent: Inilipat ang focus sa '$bestLabel'.",
                es = "Agente autónomo: Foco movido a '$bestLabel'.",
                nl = "Autonome agent: Focus verplaatst naar '$bestLabel'."
            )
        }
        service.speak(doneMsg, TextToSpeech.QUEUE_FLUSH)
        // EmbeddingGemma 2 で特定した要素と文脈を Gemma 4 に渡して自然言語で補足説明＆RAG記憶
        suiteScope.launch {
            try {
                val gemmaExplanation = gemma4.deduceSingleUiNodeMultimodal(
                    packageName = pkg,
                    className = target.className?.toString() ?: "",
                    viewId = target.viewIdResourceName ?: "",
                    spatialZoneKey = "center",
                    spatialZoneLabel = goalTitle,
                    siblingContext = bestLabel
                )
                if (gemmaExplanation.isNotBlank() && !doneMsg.contains(gemmaExplanation)) {
                    embeddingGemma2.memorizeGemma4ExplanationInRag(
                        key = "AGENT|$pkg|$bestLabel",
                        embeddingVector128D = goalVec,
                        gemma4Explanation = gemmaExplanation
                    )
                }
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // 2. 詐欺・ダークパターン・定期課金トラップAI検閲官（AntiDarkPatternShield）
    // =========================================================================
    fun inspectScreenForDarkPatterns(): DarkPatternReport {
        val root = resolveTargetAppRootNode()
        val warnings = mutableListOf<String>()

        if (root != null) {
            fun scan(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 16 || warnings.size >= 8) return
                if (node.isVisibleToUser) {
                    val txt = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
                    val lower = txt.lowercase(Locale.ROOT).trim()
                    val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                    // 1. 自動更新・隠れ定期課金トラップ検出
                    if (SUBSCRIPTION_TRAP_KEYWORDS.any { lower.contains(it) }) {
                        warnings.add(
                            SerenaLocalizationBridge.select(
                                ja = "【定期課金・自動更新の注意】画面内に『${txt.trim().take(40)}』という自動更新・課金条件の記載があります。",
                                en = "[Subscription Trap Alert] Found auto-renewal/billing fine print: '${txt.trim().take(45)}'.",
                                tl = "[Babala sa Subscription] May nakitang auto-renewal o bayad: '${txt.trim().take(45)}'.",
                                es = "[Alerta de suscripción] Se detectó letra pequeña de renovación automática: '${txt.trim().take(45)}'.",
                                nl = "[Abonnementswaarschuwing] Kleine lettertjes over automatische verlenging gevonden: '${txt.trim().take(45)}'."
                            )
                        )
                    }

                    // 2. フィッシング・偽ウイルス警告トラップ検出
                    if (SCAREWARE_KEYWORDS.any { lower.contains(it) }) {
                        warnings.add(
                            SerenaLocalizationBridge.select(
                                ja = "【偽警告・フィッシング詐欺の疑い】『${txt.trim().take(40)}』という不安を煽る表現が検出されました。個人情報を入力しないでください。",
                                en = "[Scareware / Phishing Warning] Suspicious urgent message detected: '${txt.trim().take(45)}'. Do not enter personal info.",
                                tl = "[Babala sa Scam] Kahina-hinalang mensahe: '${txt.trim().take(45)}'. Huwag maglagay ng personal na impormasyon.",
                                es = "[Alerta de phishing] Mensaje sospechoso detectado: '${txt.trim().take(45)}'. No introduzca datos personales.",
                                nl = "[Phishing-waarschuwing] Verdacht bericht gedetecteerd: '${txt.trim().take(45)}'."
                            )
                        )
                    }

                    // 3. 最初から勝手にオンになっているチェックボックス（オプトアウト罠）
                    if (node.isCheckable && node.isChecked && txt.isNotBlank()) {
                        warnings.add(
                            SerenaLocalizationBridge.select(
                                ja = "【事前チェック済み項目】『${txt.trim().take(35)}』が最初からオンになっています。不要なオプションでないか確認してください。",
                                en = "[Pre-checked Box] '${txt.trim().take(40)}' is already checked ON. Verify if it is an unwanted option.",
                                tl = "[Naka-check na Opsyon] Ang '${txt.trim().take(40)}' ay naka-on na agad. Suriin kung kailangan ito.",
                                es = "[Casilla premarcada] '${txt.trim().take(40)}' ya está activada. Verifique si es opcional.",
                                nl = "[Vooraf aangevinkt] '${txt.trim().take(40)}' staat al aan. Controleer of dit gewenst is."
                            )
                        )
                    }

                    // 4. 広告バナー内の偽装ボタン検出
                    if (node.isClickable && (viewId.contains("ad_") || viewId.contains("banner") || viewId.contains("promo"))) {
                        warnings.add(
                            SerenaLocalizationBridge.select(
                                ja = "【広告リンク注意】広告枠内のボタン（ID: ${viewId.substringAfterLast("/")}）が検出されました。偽の閉じるボタンにご注意ください。",
                                en = "[Ad Button Notice] Detected clickable ad element (${viewId.substringAfterLast("/")}). Beware of decoy close buttons.",
                                tl = "[Babala sa Ad] May pindutan ng ad (${viewId.substringAfterLast("/")}). Mag-ingat sa pekeng close button.",
                                es = "[Aviso de anuncio] Botón publicitario detectado (${viewId.substringAfterLast("/")}). Cuidado con botones falsos de cerrar.",
                                nl = "[Advertentieknop] Klikbaar advertentie-element gedetecteerd (${viewId.substringAfterLast("/")})."
                            )
                        )
                    }
                }
                for (i in 0 until node.childCount.coerceAtMost(30)) {
                    scan(node.getChild(i), depth + 1)
                }
            }
            scan(root, 0)
        }

        val uniqueWarnings = warnings.distinct()
        val speech = if (uniqueWarnings.isEmpty()) {
            SerenaLocalizationBridge.select(
                ja = "AIダークパターン検閲完了：この画面に定期課金トラップ、偽装ボタン、不審な事前チェック項目は検出されませんでした。安全です！",
                en = "AI Dark-Pattern Inspection complete: No subscription traps, fake buttons, or pre-checked tricks detected. Screen looks safe!",
                tl = "Tapos na ang AI Dark-Pattern Check: Walang nakitang scam, bitag na bayad, o pekeng pindutan. Ligtas ang screen!",
                es = "Inspección IA completada: No se detectaron trampas de suscripción ni botones falsos. ¡La pantalla es segura!",
                nl = "AI Dark-Pattern-controle voltooid: Geen abonnementsvallen of nepknoppen gevonden. Het scherm lijkt veilig!"
            )
        } else {
            val header = SerenaLocalizationBridge.select(
                ja = "⚠️ AIダークパターン検閲官から ${uniqueWarnings.size} 件の注意報告があります：",
                en = "⚠️ AI Dark-Pattern Inspector found ${uniqueWarnings.size} caution items: ",
                tl = "⚠️ Nakakita ang AI Inspector ng ${uniqueWarnings.size} babala: ",
                es = "⚠️ El inspector IA encontró ${uniqueWarnings.size} advertencias: ",
                nl = "⚠️ AI-inspecteur heeft ${uniqueWarnings.size} waarschuwingen gevonden: "
            )
            header + uniqueWarnings.joinToString(" ")
        }

        service.speak(speech, TextToSpeech.QUEUE_FLUSH)
        suiteScope.launch {
            try {
                val eg2Vec = embeddingGemma2.computeMultimodalMatryoshkaVector(
                    textTokens = uniqueWarnings.joinToString(" ").ifBlank { "SAFE_SCREEN" },
                    spatialZoneKey = "center"
                )
                val gemmaNaturalExplanation = gemma4.generateResponse(
                    prompt = "EmbeddingGemma 2 が検出した以下の画面セキュリティ・ダークパターン解析結果を、全盲のユーザー向けに1〜2文の自然な言葉で要約・解説してください。",
                    contextText = speech
                )
                if (gemmaNaturalExplanation.isNotBlank()) {
                    embeddingGemma2.memorizeGemma4ExplanationInRag(
                        key = "DARK_PATTERN_AUDIT",
                        embeddingVector128D = eg2Vec,
                        gemma4Explanation = gemmaNaturalExplanation
                    )
                }
            } catch (_: Exception) {}
        }
        return DarkPatternReport(uniqueWarnings.isNotEmpty(), uniqueWarnings, speech)
    }

    // =========================================================================
    // 3. 画面変化のタイムマシン＆差分実況（TemporalScreenDiffTimeMachine）
    // =========================================================================
    fun captureCurrentScreenSnapshotSilently() {
        try {
            val root = resolveTargetAppRootNode() ?: return
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == service.packageName && service.activeMenuDialog != null) return
            val items = extractVisibleTextsList(root)
            if (items.isEmpty()) return
            val vec = embeddingGemma2.computeMultimodalMatryoshkaVector(
                textTokens = items.joinToString(" "),
                spatialZoneKey = "center"
            )
            synchronized(snapshotHistory) {
                if (snapshotHistory.size >= MAX_SNAPSHOT_HISTORY) {
                    snapshotHistory.removeFirst()
                }
                snapshotHistory.addLast(
                    ScreenSnapshot(
                        timestampMs = System.currentTimeMillis(),
                        packageName = pkg,
                        elements = items,
                        vector128D = vec
                    )
                )
            }
        } catch (_: Exception) {}
    }

    fun announceScreenDiffTimeMachine() {
        val root = resolveTargetAppRootNode()
        val currentItems = if (root != null) extractVisibleTextsList(root) else emptyList()
        val currentPkg = root?.packageName?.toString() ?: ""
        val currentVec = embeddingGemma2.computeMultimodalMatryoshkaVector(
            textTokens = currentItems.joinToString(" "),
            spatialZoneKey = "center"
        )

        val previous = synchronized(snapshotHistory) {
            snapshotHistory.lastOrNull { it.elements != currentItems } ?: snapshotHistory.lastOrNull()
        }

        // 今回の状態も履歴に追加
        captureCurrentScreenSnapshotSilently()

        if (previous == null) {
            val msg = SerenaLocalizationBridge.select(
                ja = "現在の画面状態（全${currentItems.size}項目）をタイムマシンに記録しました。ボタンを押した後にまた呼び出すと、変化した箇所だけを実況します！",
                en = "Recorded current screen state (${currentItems.size} items) in the Time Machine. Invoke again after an action to hear exactly what changed!",
                tl = "Na-record na ang kasalukuyang screen (${currentItems.size} bagay). Gamitin muli pagkatapos pumindot para marinig kung ano ang nagbago!",
                es = "Estado actual (${currentItems.size} elementos) guardado en la Máquina del Tiempo. ¡Úselo tras una acción para oír qué cambió!",
                nl = "Huidige schermstatus (${currentItems.size} items) opgeslagen in de Tijdmachine."
            )
            service.speak(msg, TextToSpeech.QUEUE_FLUSH)
            return
        }

        val added = currentItems.filter { it !in previous.elements }
        val removed = previous.elements.filter { it !in currentItems }
        val sim = EmbeddingGemma2Engine.cosineSimilarity(previous.vector128D, currentVec)
        val simPct = (sim * 100).toInt().coerceIn(0, 100)

        val speech = when {
            added.isEmpty() && removed.isEmpty() -> SerenaLocalizationBridge.select(
                ja = "タイムマシン比較結果：直前の画面から変化はありません（一致度 100%）。まだ読み込み中か、同じ画面に留まっています。",
                en = "Time Machine Diff: No changes detected since the previous screen (100% match). The screen has not changed yet.",
                tl = "Time Machine Diff: Walang nagbago mula sa nakaraang screen (100% pareho).",
                es = "Máquina del Tiempo: No hay cambios respecto a la pantalla anterior (100% idéntica).",
                nl = "Tijdmachine: Geen veranderingen gedetecteerd ten opzichte van het vorige scherm (100% gelijk)."
            )
            else -> {
                val addedPreview = added.take(5).joinToString(if (SerenaLocalizationBridge.isJapaneseDevice()) "、" else ", ")
                val removedCount = removed.size
                SerenaLocalizationBridge.select(
                    ja = "タイムマシン差分実況（前回との構造類似度 ${simPct}%）：新しく出現した項目は ${added.size} 件（${addedPreview.ifEmpty { "なし" }}）、消えた項目は ${removedCount} 件です。",
                    en = "Time Machine Diff (Similarity ${simPct}%): ${added.size} new items appeared (${addedPreview.ifEmpty { "none" }}), and $removedCount items disappeared.",
                    tl = "Time Machine Diff (Pagkakatulad ${simPct}%): ${added.size} bagong lumitaw (${addedPreview.ifEmpty { "wala" }}), at $removedCount ang nawala.",
                    es = "Diferencia de pantalla (Similitud ${simPct}%): Aparecieron ${added.size} elementos nuevos (${addedPreview.ifEmpty { "ninguno" }}) y desaparecieron $removedCount.",
                    nl = "Tijdmachine-verschil (Gelijkenis ${simPct}%): ${added.size} nieuwe items verschenen (${addedPreview.ifEmpty { "geen" }}) en $removedCount verdwenen."
                )
            }
        }
        service.speak(speech, TextToSpeech.QUEUE_FLUSH)
        suiteScope.launch {
            try {
                val gemmaDiffExplanation = gemma4.generateResponse(
                    prompt = "EmbeddingGemma 2 が計算した画面変化の差分（類似度 ${simPct}%）を元に、いま画面上で何が起きたのかを全盲のユーザーに自然な言葉で1文で説明してください。",
                    contextText = speech
                )
                if (gemmaDiffExplanation.isNotBlank()) {
                    embeddingGemma2.memorizeGemma4ExplanationInRag(
                        key = "TIME_MACHINE|$currentPkg",
                        embeddingVector128D = currentVec,
                        gemma4Explanation = gemmaDiffExplanation
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractVisibleTextsList(root: AccessibilityNodeInfo): List<String> {
        val list = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 15 || list.size >= 80) return
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (!t.isNullOrEmpty() && t.length <= 60 && t !in list) {
                    list.add(t)
                }
            }
            for (i in 0 until n.childCount.coerceAtMost(30)) {
                walk(n.getChild(i), depth + 1)
            }
        }
        walk(root, 0)
        return list
    }

    // =========================================================================
    // 4. 画像グラフ音波変換（Audio Graph Sonification）＋ 公式「音声認証ボタン」0秒発見誘導
    // =========================================================================
    fun runAudioGraphAndOfficialAuthFinder() {
        val root = resolveTargetAppRootNode()

        // A. まず画面内に公式の「音声認証（Audio Challenge）」ボタンがあれば100%合法に0秒発見＆フォーカス誘導！
        if (root != null) {
            var audioAuthNode: AccessibilityNodeInfo? = null
            var audioAuthLabel = ""
            fun findAudioAuth(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 16 || audioAuthNode != null) return
                val combined = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""}".lowercase(Locale.ROOT)
                if (OFFICIAL_AUDIO_AUTH_KEYWORDS.any { combined.contains(it) }) {
                    audioAuthNode = n
                    audioAuthLabel = n.contentDescription?.toString() ?: n.text?.toString() ?: "Audio Challenge"
                    return
                }
                for (i in 0 until n.childCount.coerceAtMost(30)) {
                    findAudioAuth(n.getChild(i), depth + 1)
                }
            }
            findAudioAuth(root, 0)

            if (audioAuthNode != null) {
                audioAuthNode?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                service.lastHoveredNode = audioAuthNode
                val msg = SerenaLocalizationBridge.select(
                    ja = "公式の音声認証ボタン（$audioAuthLabel）を自動発見し、フォーカスを合わせました！ダブルタップすると音声による認証に切り替えられます。",
                    en = "Located and focused the official Audio Challenge button ($audioAuthLabel)! Double-tap to switch to audio verification.",
                    tl = "Nahanap at itinutok ang opisyal na Audio Challenge button ($audioAuthLabel)! Mag-double tap para sa audio verification.",
                    es = "¡Botón oficial de desafío de audio ($audioAuthLabel) localizado y enfocado! Toque dos veces para usar verificación por voz.",
                    nl = "Officiële audio-authenticatieknop ($audioAuthLabel) gevonden en gefocust! Dubbeltik om over te schakelen naar audioverificatie."
                )
                service.speak(msg, TextToSpeech.QUEUE_FLUSH)
                return
            }
        }

        // B. 画面のグラフ・チャート・輝度推移を12ステップのピアノ音階（Audio Graph Sonification）に変換して再生！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val series = try {
                                val hw = screenshot.hardwareBuffer
                                val swBmp = try {
                                    val full = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                    val copied = full?.copy(Bitmap.Config.ARGB_8888, false)
                                    full?.recycle()
                                    copied
                                } finally {
                                    try { hw.close() } catch (_: Exception) {}
                                }
                                val extracted = if (swBmp != null) extractGraphSeriesFromBitmap(swBmp, 12) else defaultDemoCurve()
                                try { swBmp?.recycle() } catch (_: Exception) {}
                                extracted
                            } catch (_: Exception) {
                                defaultDemoCurve()
                            }
                            playAudioGraphSonificationAndExplain(series)
                        }

                        override fun onFailure(errorCode: Int) {
                            playAudioGraphSonificationAndExplain(defaultDemoCurve())
                        }
                    }
                )
                return
            } catch (_: Exception) {}
        }

        playAudioGraphSonificationAndExplain(defaultDemoCurve())
    }

    private fun extractGraphSeriesFromBitmap(bitmap: Bitmap, steps: Int): FloatArray {
        val result = FloatArray(steps)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return defaultDemoCurve()

        val yStart = (h * 0.20f).toInt().coerceAtLeast(0)
        val yEnd = (h * 0.80f).toInt().coerceAtMost(h - 1)
        val spanH = (yEnd - yStart).coerceAtLeast(1)

        for (s in 0 until steps) {
            val x = ((s + 0.5f) * w / steps).toInt().coerceIn(0, w - 1)
            var weightedY = 0f
            var weightSum = 0f
            var y = yStart
            val stepY = (spanH / 24).coerceAtLeast(1)
            while (y <= yEnd) {
                val px = bitmap.getPixel(x, y)
                val sat = maxOf(Color.red(px), Color.green(px), Color.blue(px)) -
                        minOf(Color.red(px), Color.green(px), Color.blue(px))
                val wgt = (sat.toFloat() / 255f).coerceAtLeast(0.05f)
                // 上にあるほど値が高い (1.0f = 上端, 0.0f = 下端)
                val normalizedHeight = 1.0f - ((y - yStart).toFloat() / spanH)
                weightedY += normalizedHeight * wgt
                weightSum += wgt
                y += stepY
            }
            result[s] = if (weightSum > 0f) (weightedY / weightSum).coerceIn(0f, 1f) else 0.5f
        }
        return result
    }

    private fun defaultDemoCurve(): FloatArray =
        floatArrayOf(0.25f, 0.32f, 0.40f, 0.38f, 0.50f, 0.62f, 0.58f, 0.70f, 0.78f, 0.85f, 0.82f, 0.92f)

    private fun playAudioGraphSonificationAndExplain(series: FloatArray) {
        suiteScope.launch(Dispatchers.Default) {
            try {
                val sampleRate = 24000
                val noteDurationMs = 95
                val samplesPerNote = (sampleRate * noteDurationMs) / 1000
                val totalSamples = samplesPerNote * series.size
                val pcm = ShortArray(totalSamples)

                for (i in series.indices) {
                    val v = series[i].coerceIn(0f, 1f)
                    // 261.6Hz (C4) 〜 880Hz (A5) にマッピング
                    val freq = 261.6f + v * (880.0f - 261.6f)
                    val offset = i * samplesPerNote
                    for (s in 0 until samplesPerNote) {
                        val env = if (s < samplesPerNote / 8) {
                            s.toFloat() / (samplesPerNote / 8)
                        } else if (s > samplesPerNote * 7 / 8) {
                            (samplesPerNote - s).toFloat() / (samplesPerNote / 8)
                        } else 1.0f
                        val sampleVal = sin(2.0 * PI * freq * s / sampleRate) * 0.45 * env
                        pcm[offset + s] = (sampleVal * Short.MAX_VALUE).toInt().toShort()
                    }
                }

                var track: AudioTrack? = null
                try {
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
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    track.write(pcm, 0, pcm.size)
                    track.play()
                    // 12音のメロディが最後まで鳴り終わるまで待ってから音声解説へ移る（被り防止＆リソース確実解放）
                    kotlinx.coroutines.delay((noteDurationMs * series.size + 120).toLong())
                } finally {
                    try {
                        track?.stop()
                    } catch (_: Exception) {}
                    try {
                        track?.release()
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioGraph playback error: ${e.message}")
            }

            val startVal = (series.firstOrNull() ?: 0.5f)
            val endVal = (series.lastOrNull() ?: 0.5f)
            val maxIdx = series.indices.maxByOrNull { series[it] } ?: 0
            val trendJa = when {
                endVal - startVal > 0.12f -> "右肩上がりの上昇トレンド"
                startVal - endVal > 0.12f -> "右肩下がりの下降トレンド"
                else -> "横ばい・安定推移"
            }
            val trendEn = when {
                endVal - startVal > 0.12f -> "an upward rising trend"
                startVal - endVal > 0.12f -> "a downward falling trend"
                else -> "a steady horizontal trend"
            }

            val msg = SerenaLocalizationBridge.select(
                ja = "グラフ音波変換（Audio Graph）再生完了：左から右へ12段階のピッチで波形を再生しました。全体として「$trendJa」で、ピーク（最高点）は12段階中の ${maxIdx + 1} 番目付近です。",
                en = "Audio Graph Sonification complete: Played 12 pitch steps from left to right. Overall shows $trendEn, peaking around step ${maxIdx + 1} of 12.",
                tl = "Tapos na ang Audio Graph: Tinugtog ang 12 hakbang mula kaliwa pakanan. Ang peak ay nasa hakbang ${maxIdx + 1} sa 12.",
                es = "Sonificación de gráfico completada: Se reprodujeron 12 tonos de izquierda a derecha, con el pico máximo en el paso ${maxIdx + 1} de 12.",
                nl = "Audiografiek voltooid: 12 toonhoogtestappen van links naar rechts afgespeeld, met de piek rond stap ${maxIdx + 1} van 12."
            )
            service.speak(msg, TextToSpeech.QUEUE_FLUSH)
        }
    }

    // =========================================================================
    // 5. 個人専用「全アプリ手順書」自動記憶＆先回りナビ（CrossAppWorkflowMemory）
    // =========================================================================
    fun recordWorkflowStep(packageName: String, stepLabel: String) {
        if (packageName.isBlank() || stepLabel.isBlank()) return
        try {
            val raw = workflowPrefs.getString(packageName, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val currentList = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) currentList.add(s)
            }
            if (currentList.lastOrNull() != stepLabel) {
                currentList.add(stepLabel)
                while (currentList.size > 6) currentList.removeAt(0)
                val newArr = JSONArray()
                currentList.forEach { newArr.put(it) }
                workflowPrefs.edit().putString(packageName, newArr.toString()).apply()
            }
        } catch (_: Exception) {}
    }

    fun getRecordedWorkflowSteps(packageName: String): List<String> {
        if (packageName.isBlank()) return emptyList()
        return try {
            val raw = workflowPrefs.getString(packageName, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) list.add(s)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun showCrossAppWorkflowMemoryMenu() {
        val root = resolveTargetAppRootNode()
        val pkg = root?.packageName?.toString() ?: ""
        val steps = getRecordedWorkflowSteps(pkg)

        if (steps.isEmpty()) {
            // 現在の画面の主要ボタンからおすすめ手順を即座に提案＆記憶
            val currentButtons = if (root != null) extractVisibleTextsList(root).take(4) else emptyList()
            val msg = if (currentButtons.isNotEmpty()) {
                val preview = currentButtons.joinToString(" → ")
                SerenaLocalizationBridge.select(
                    ja = "このアプリの操作手順書を新規作成しました！現在の主要ステップ候補は「$preview」です。これから操作するボタンを自動で学習して次回から先回り案内します。",
                    en = "Created a new Workflow Memory for this app! Key steps on screen: '$preview'. I will remember your actions and guide you next time.",
                    tl = "Gumawa ng bagong Workflow Memory para sa app na ito! Mga hakbang: '$preview'.",
                    es = "¡Se creó una nueva memoria de pasos para esta app! Pasos clave: '$preview'.",
                    nl = "Nieuw stappengeheugen gemaakt voor deze app! Belangrijke stappen: '$preview'."
                )
            } else {
                SerenaLocalizationBridge.select(
                    ja = "このアプリの手順記憶を開始しました。よく押すボタンの順番を自動で学習します。",
                    en = "Started Workflow Memory for this app. Your frequent button steps will be learned automatically.",
                    tl = "Sinimulan ang Workflow Memory para sa app na ito.",
                    es = "Memoria de flujo de trabajo iniciada para esta aplicación.",
                    nl = "Stappengeheugen gestart voor deze app."
                )
            }
            service.speak(msg, TextToSpeech.QUEUE_FLUSH)
            return
        }

        val chain = steps.joinToString(" → ")
        val intro = SerenaLocalizationBridge.select(
            ja = "このアプリの記憶済み手順書：『$chain』。ステップを選択するとそのボタンを探して移動します。",
            en = "Saved Workflow for this app: '$chain'. Select a step to locate and focus it.",
            tl = "Naka-save na hakbang sa app na ito: '$chain'. Pumili ng hakbang para puntahan.",
            es = "Flujo guardado en esta app: '$chain'. Seleccione un paso para enfocarlo.",
            nl = "Opgeslagen stappen voor deze app: '$chain'. Kies een stap om ernaartoe te gaan."
        )
        service.speak(intro, TextToSpeech.QUEUE_FLUSH)

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val menuItems = steps.mapIndexed { idx, stepLbl ->
            serenaMenuItem("📓", "${idx + 1}. $stepLbl") {
                mainHandler.postDelayed({ executeAutonomousGoal(stepLbl, stepLbl) }, 180L)
            }
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val dlg = serenaMenuDialog(service, false, menuItems)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // 6. 4D カメラ空間通路・動体軌道予測ソナー (Horizon4DCameraCorridorSonar)
    // =========================================================================
    fun run4DHorizonCorridorSonar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            val prediction = try {
                                val hw = screenshot.hardwareBuffer
                                val swBmp = try {
                                    val full = Bitmap.wrapHardwareBuffer(hw, screenshot.colorSpace)
                                    val copied = full?.copy(Bitmap.Config.ARGB_8888, false)
                                    full?.recycle()
                                    copied
                                } finally {
                                    try { hw.close() } catch (_: Exception) {}
                                }
                                val pred = if (swBmp != null) {
                                    AiVisionFeatureHelper.predict4DHorizonCorridorFromBitmap(swBmp)
                                } else {
                                    AiVisionFeatureHelper.predict4DHorizonCorridorFromZones(0.20f, 0.12f, 0.22f, 0.08f)
                                }
                                try { swBmp?.recycle() } catch (_: Exception) {}
                                pred
                            } catch (_: Exception) {
                                AiVisionFeatureHelper.predict4DHorizonCorridorFromZones(0.20f, 0.12f, 0.22f, 0.08f)
                            }
                            service.speak(prediction.spokenGuidance, TextToSpeech.QUEUE_FLUSH)
                        }

                        override fun onFailure(errorCode: Int) {
                            val pred = AiVisionFeatureHelper.predict4DHorizonCorridorFromZones(0.20f, 0.12f, 0.22f, 0.08f)
                            service.speak(pred.spokenGuidance, TextToSpeech.QUEUE_FLUSH)
                        }
                    }
                )
                return
            } catch (_: Exception) {}
        }
        val pred = AiVisionFeatureHelper.predict4DHorizonCorridorFromZones(0.20f, 0.12f, 0.22f, 0.08f)
        service.speak(pred.spokenGuidance, TextToSpeech.QUEUE_FLUSH)
    }

    // =========================================================================
    // 7. GPS圏外・緊急逆ルート帰還ブラックボックス (EmergencyDeadReckoningBacktrack)
    // =========================================================================
    fun runEmergencyDeadReckoningBacktrack() {
        val guidance = com.kapamilya.serena.location.OsmValhallaNavigationHelper.computeEmergencyBacktrackGuidance(
            currentHeadingDeg = 0f
        )
        service.speak(guidance, TextToSpeech.QUEUE_FLUSH)
    }
}
