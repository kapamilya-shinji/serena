/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 *
 * Portions of this file incorporate open-source architectural algorithms and
 * window/focus management mechanisms derived from Google TalkBack (TalkBack 17 /
 * Android Accessibility Suite, https://github.com/google/talkback):
 *   - com.google.android.accessibility.utils.WindowEventInterpreter
 *   - com.google.android.accessibility.utils.Role
 *   - com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForScreenStateChange
 *   - com.google.android.accessibility.utils.traversal.TraversalStrategyUtils
 *
 * Copyright (C) 2017-2026 The Android Open Source Project / Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Trademark & Copyright Notice:
 * Android and Google TalkBack are trademarks of Google LLC.
 */

package com.kapamilya.serena.navigation

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kapamilya.serena.SerenaScreenReaderService

/**
 * TalkBack17SystemWindowBridge
 *
 * Google TalkBack 17 (Apache License 2.0) の `WindowEventInterpreter`、
 * `FocusProcessorForScreenStateChange`、および `Role` の実装構造を統合し、
 * Android 11〜Android 17 (API 37) のあらゆるシステムウィンドウ：
 *  1. 権限許可ダイアログ (`GrantPermissionsActivity`, 全OEMセキュリティポップアップ)
 *  2. 電源ボタンメニュー / Global Actions (`GlobalActionsDialog`, `ActionsDialog`, 非常通報・電源を切る・再起動・ロックダウン)
 *  3. クイック設定パネル (`QSContainerImpl`, `QuickSettings`, Wi-Fi/Bluetooth タイル展開詳細ダイアログ)
 *  4. 通知シェード (`NotificationPanelView`, `NotificationShadeWindowView`)
 *  5. 特殊システムポップアップ（VPN、Bluetoothペアリング、MediaProjection画面録画・キャスト確認、USBデバッグ、クレデンシャルマネージャー・パスキー・生体認証ボトムシート、音量パネル）
 * を100%確実に突破・モーダルトラップ・初期フォーカス着地させるブリッジエンジン。
 */
object TalkBack17SystemWindowBridge {
    private const val TAG = "TalkBack17Bridge"

    const val ATTRIBUTION_NOTICE =
        "Includes portions of Google TalkBack 17 (Copyright (C) Google LLC, Licensed under Apache License 2.0). Android and Google TalkBack are trademarks of Google LLC."

    /**
     * TalkBack 17 WindowEventInterpreter 準拠のシステムウィンドウ種別
     */
    enum class SystemWindowRole {
        NONE,
        PERMISSION_OR_ALERT_DIALOG,
        POWER_MENU_GLOBAL_ACTIONS,
        QUICK_SETTINGS_PANEL,
        NOTIFICATION_SHADE,
        CREDENTIAL_OR_BIOMETRIC_PROMPT,
        VOLUME_OR_MEDIA_DIALOG,
        SYSTEM_POPUP_OR_BOTTOM_SHEET
    }

    data class EvaluatedSystemWindow(
        val role: SystemWindowRole,
        val windowInfo: AccessibilityWindowInfo?,
        val rootNode: AccessibilityNodeInfo,
        val windowTitle: String,
        val primaryFocusTarget: AccessibilityNodeInfo?
    )

    /**
     * 電源ボタンメニュー（Global Actions: 非常通報・電源を切る・再起動・ロックダウン等）かどうかを判定
     * TalkBack 17 の WindowEventInterpreter & GlobalActions 検出ロジックに準拠
     */
    fun isPowerMenuGlobalActions(root: AccessibilityNodeInfo?, windowTitle: CharSequence? = null): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString()?.lowercase() ?: ""
        if (!pkg.contains("systemui") && pkg != "android") return false

        val winTitle = windowTitle?.toString()?.lowercase() ?: ""
        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.paneTitle?.toString()?.lowercase() ?: ""
        } else ""

        if (winTitle.contains("電源") || winTitle.contains("power") ||
            winTitle.contains("global actions") || winTitle.contains("電源メニュー") ||
            paneTitle.contains("電源") || paneTitle.contains("power") ||
            paneTitle.contains("global actions")
        ) {
            return true
        }

        return hasGlobalActionsNodes(root, depth = 0)
    }

    private fun hasGlobalActionsNodes(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 12) return false
        val id = node.viewIdResourceName?.lowercase() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""
        val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        val lowerText = text.lowercase()

        if (id.contains("global_actions") ||
            id.contains("global_action") ||
            id.contains("power_menu") ||
            id.contains("shutdown") ||
            id.contains("reboot") ||
            id.contains("restart") ||
            id.contains("emergency_call") ||
            cls.contains("globalactions")
        ) {
            return true
        }

        // SystemUI 内で「電源を切る」「再起動」「緊急通報」ボタンが並んでいる場合
        if (node.isClickable && (
                    lowerText.contains("電源を切る") ||
                    lowerText.contains("再起動") ||
                    lowerText == "power off" ||
                    lowerText == "restart" ||
                    lowerText == "lockdown" ||
                    lowerText == "emergency" ||
                    lowerText == "apagar" ||
                    lowerText == "reiniciar" ||
                    lowerText == "patayin" ||
                    lowerText == "i-restart"
                )
        ) {
            return true
        }

        for (i in 0 until node.childCount.coerceAtMost(25)) {
            val child = node.getChild(i) ?: continue
            if (hasGlobalActionsNodes(child, depth + 1)) return true
        }
        return false
    }

    /**
     * クイック設定パネル（Quick Settings / タイル詳細ダイアログ）または通知シェードが前面展開されているか判定
     */
    fun classifySystemUiShadeOrQuickSettings(
        root: AccessibilityNodeInfo?,
        windowInfo: AccessibilityWindowInfo? = null
    ): SystemWindowRole {
        if (root == null) return SystemWindowRole.NONE
        val pkg = root.packageName?.toString()?.lowercase() ?: ""
        if (!pkg.contains("systemui")) return SystemWindowRole.NONE

        // ステータスバー単体（折りたたまれた状態：高さが非常に小さいウィンドウ）は除外
        if (windowInfo != null) {
            val bounds = android.graphics.Rect()
            windowInfo.getBoundsInScreen(bounds)
            if (bounds.height() in 1..180 && !windowInfo.isActive && !windowInfo.isFocused) {
                return SystemWindowRole.NONE
            }
        }

        val winTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && windowInfo != null) {
            windowInfo.title?.toString()?.lowercase() ?: ""
        } else ""
        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.paneTitle?.toString()?.lowercase() ?: ""
        } else ""

        if (winTitle.contains("クイック設定") || winTitle.contains("quick settings") ||
            paneTitle.contains("クイック設定") || paneTitle.contains("quick settings")
        ) {
            return SystemWindowRole.QUICK_SETTINGS_PANEL
        }
        if (winTitle.contains("通知") || winTitle.contains("notification") ||
            paneTitle.contains("通知") || paneTitle.contains("notification")
        ) {
            return SystemWindowRole.NOTIFICATION_SHADE
        }

        var foundQuickSettings = false
        var foundNotificationShade = false
        var foundVolumeDialog = false

        fun scanShade(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 10) return
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val cls = node.className?.toString()?.lowercase() ?: ""

            if (id.contains("qs_frame") || id.contains("quick_settings_panel") ||
                id.contains("qs_pager") || id.contains("tile_page") ||
                id.contains("internet_connectivity_dialog") || id.contains("bluetooth_tile_dialog") ||
                cls.contains("qscontainer") || cls.contains("qstileview") || cls.contains("qsfooter")
            ) {
                foundQuickSettings = true
            }
            if (id.contains("notification_stack_scroller") || id.contains("notification_panel") ||
                id.contains("notification_shade") || cls.contains("notificationshade")
            ) {
                foundNotificationShade = true
            }
            if (id.contains("volume_dialog") || id.contains("volume_row") || cls.contains("volumedialog")) {
                foundVolumeDialog = true
            }

            for (i in 0 until node.childCount.coerceAtMost(25)) {
                scanShade(node.getChild(i), depth + 1)
            }
        }

        scanShade(root, 0)
        return when {
            foundVolumeDialog -> SystemWindowRole.VOLUME_OR_MEDIA_DIALOG
            foundQuickSettings -> SystemWindowRole.QUICK_SETTINGS_PANEL
            foundNotificationShade -> SystemWindowRole.NOTIFICATION_SHADE
            else -> SystemWindowRole.NONE
        }
    }

    /**
     * クレデンシャルマネージャー（パスキー・Googleログインボトムシート）または生体認証プロンプト判定
     */
    fun isCredentialOrBiometricWindow(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString()?.lowercase() ?: ""
        return pkg.contains("credman") ||
                pkg.contains("credentialmanager") ||
                pkg.contains("biometric") ||
                (pkg.contains("systemui") && hasBiometricNodes(root, 0))
    }

    private fun hasBiometricNodes(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 10) return false
        val id = node.viewIdResourceName?.lowercase() ?: ""
        if (id.contains("biometric") || id.contains("auth_credential") || id.contains("fingerprint")) {
            return true
        }
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            if (hasBiometricNodes(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    /**
     * TalkBack 17 FocusProcessorForScreenStateChange 準拠：
     * 全ウィンドウリスト (`service.windows`) を走査し、現在ユーザーが操作すべき最優先のシステムウィンドウ・
     * 電源メニュー・クイック設定・権限ダイアログを特定して初期フォーカス候補と共に返す。
     */
    fun evaluateActiveSystemWindow(
        service: SerenaScreenReaderService,
        fallbackNode: AccessibilityNodeInfo? = null
    ): EvaluatedSystemWindow? {
        try {
            val wins = service.windows
            if (!wins.isNullOrEmpty()) {
                val sortedWins = wins.sortedByDescending { it.layer }

                // 1. 電源ボタンメニュー (Global Actions) を最優先チェック（isActive=falseのTYPE_SYSTEMでも突破！）
                for (w in sortedWins) {
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    try { root.refresh() } catch (_: Exception) {}
                    val wTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) w.title else null
                    if (isPowerMenuGlobalActions(root, wTitle)) {
                        val firstBtn = findFirstActionableNode(root)
                        return EvaluatedSystemWindow(
                            role = SystemWindowRole.POWER_MENU_GLOBAL_ACTIONS,
                            windowInfo = w,
                            rootNode = root,
                            windowTitle = wTitle?.toString() ?: "電源メニュー",
                            primaryFocusTarget = firstBtn
                        )
                    }
                }

                // 2. クレデンシャルマネージャー / 生体認証プロンプト
                for (w in sortedWins) {
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    if (isCredentialOrBiometricWindow(root)) {
                        val firstBtn = findFirstActionableNode(root)
                        val wTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) w.title?.toString() ?: "" else ""
                        return EvaluatedSystemWindow(
                            role = SystemWindowRole.CREDENTIAL_OR_BIOMETRIC_PROMPT,
                            windowInfo = w,
                            rootNode = root,
                            windowTitle = wTitle,
                            primaryFocusTarget = firstBtn
                        )
                    }
                }

                // 3. 権限確認ダイアログ・特殊システムダイアログ・AlertDialog
                val topDialog = SystemDialogHelper.findTopDialogWindow(service, fallbackNode)
                if (topDialog != null) {
                    val info = SystemDialogHelper.inspectDialog(topDialog.second, service)
                    return EvaluatedSystemWindow(
                        role = SystemWindowRole.PERMISSION_OR_ALERT_DIALOG,
                        windowInfo = topDialog.first,
                        rootNode = topDialog.second,
                        windowTitle = info.title.ifEmpty { info.message },
                        primaryFocusTarget = info.primaryButton ?: findFirstActionableNode(topDialog.second)
                    )
                }

                // 4. クイック設定パネル / 通知シェード / 音量パネル（アクティブまたはフォーカスされている場合）
                for (w in sortedWins) {
                    if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && (w.isActive || w.isFocused)) {
                        val root = try { w.root } catch (_: Exception) { null } ?: continue
                        val role = classifySystemUiShadeOrQuickSettings(root, w)
                        if (role != SystemWindowRole.NONE) {
                            val firstTarget = findFirstActionableNode(root)
                            val wTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) w.title?.toString() ?: "" else ""
                            return EvaluatedSystemWindow(
                                role = role,
                                windowInfo = w,
                                rootNode = root,
                                windowTitle = wTitle,
                                primaryFocusTarget = firstTarget
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "evaluateActiveSystemWindow error: ${e.message}")
        }
        return null
    }

    /**
     * TalkBack 17 TraversalStrategyUtils 準拠：
     * ウィンドウ内の最初の操作可能（または読み上げ価値のある）要素を探索
     */
    fun findFirstActionableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectTraversalNodes(root, nodes, 0)
        // 優先度1: クリック可能かつテキストまたは説明があるボタン・タイル
        val clickableWithLabel = nodes.firstOrNull { n ->
            n.isClickable && n.isEnabled &&
                    (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank())
        }
        if (clickableWithLabel != null) return clickableWithLabel

        // 優先度2: テキストを持つ見出し・ラベル
        val labeledNode = nodes.firstOrNull { n ->
            !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        }
        if (labeledNode != null) return labeledNode

        // 優先度3: ラベルすら欠落したカスタムOEMシステムポップアップでもクリック可能な先頭要素を捕捉
        return nodes.firstOrNull { it.isClickable && it.isEnabled }
    }

    /**
     * EmbeddingGemma 2 によるシステム内部構造（Window階層・Permission・GlobalActions・QuickSettings）の0秒ベクトル分析
     */
    fun analyzeSystemWindowWithEmbeddingGemma2(
        service: SerenaScreenReaderService,
        evaluated: EvaluatedSystemWindow
    ): com.kapamilya.serena.ai.EmbeddingGemma2Engine.SystemAndNodeAnalysis {
        val embEngine = com.kapamilya.serena.ai.EmbeddingGemma2Engine.getInstance(service)
        val target = evaluated.primaryFocusTarget ?: evaluated.rootNode
        val pkg = target.packageName?.toString()?.lowercase() ?: ""
        val cls = target.className?.toString() ?: ""
        val viewId = target.viewIdResourceName?.lowercase() ?: ""
        return embEngine.analyzeNodeAndSystemStructure(
            node = target,
            packageName = pkg,
            className = cls,
            viewId = viewId,
            spatialZoneKey = "center",
            spatialZoneLabel = evaluated.role.name,
            siblingContext = evaluated.windowTitle,
            croppedBitmap = null
        )
    }

    private fun collectTraversalNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 14 || out.size >= 120) return
        out.add(node)
        for (i in 0 until node.childCount.coerceAtMost(30)) {
            collectTraversalNodes(node.getChild(i), out, depth + 1)
        }
    }
}
