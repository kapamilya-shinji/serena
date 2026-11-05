package com.kapamilya.serena.navigation

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.kapamilya.serena.R
import com.kapamilya.serena.SerenaScreenReaderService

/**
 * SystemDialogHelper
 * 権限確認ダイアログ（Google Pixel / AOSP / Samsung One UI / Xiaomi MIUI・HyperOS / OPPO ColorOS /
 * Vivo・iQOO / Huawei・Honor / Transsion 等 全OEM対応）、システムアラート（画面録画・キャスト、USBデバッグ等）、
 * アプリ設定・確認ポップアップ等の自動検知・完全モーダルトラップ・詳細情報読み上げ・主要操作ボタン自動フォーカスエンジン。
 */
object SystemDialogHelper {
    private const val TAG = "SystemDialogHelper"

    data class DialogInfo(
        val isPermission: Boolean,
        val isSpecialSystem: Boolean,
        val packageName: String,
        val title: String,
        val message: String,
        val primaryButton: AccessibilityNodeInfo?,
        val dialogRoot: AccessibilityNodeInfo?
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingDialogRunnable: Runnable? = null
    private var lastHandledDialogKey: String = ""

    @Volatile
    var lastHandledTimeMs: Long = 0L

    /**
     * 純粋な権限確認・システム権限マネージャーパッケージ判定
     */
    fun isPurePermissionPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p == "com.google.android.permissioncontroller" ||
                p == "com.android.permissioncontroller" ||
                p == "com.android.packageinstaller" ||
                p == "com.google.android.packageinstaller" ||
                p == "com.samsung.android.permissioncontroller" ||
                p == "com.samsung.android.packageinstaller" ||
                p == "com.miui.securitypermission" ||
                p == "com.miui.guardprovider" ||
                p == "com.coloros.securitypermission" ||
                p == "com.oplus.securitypermission" ||
                p == "com.vivo.permissionmanager" ||
                p.contains("permissioncontroller") ||
                p.contains("packageinstaller")
    }

    /**
     * OEMセキュリティ・デバイス管理パッケージ判定（権限プロンプトのホスト元）
     */
    fun isOemSecurityPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p.contains("securitycenter") ||
                p.contains("safecenter") ||
                p.contains("safetycenter") ||
                p.contains("systemmanager") ||
                p.contains("phonemaster") ||
                p.contains("securitycore") ||
                p == "com.iqoo.secure" ||
                p == "com.lbe.security.miui" ||
                p == "com.hihonor.systemmanager" ||
                p == "com.lenovo.safecenter"
    }

    /**
     * 権限管理・セキュリティ関連パッケージ判定（全Android OEM網羅）
     */
    fun isPermissionPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return isPurePermissionPackage(p) ||
                isOemSecurityPackage(p) ||
                p.contains("permission") ||
                p.contains("securitypermission")
    }

    /**
     * 特殊システムダイアログ（VPN接続確認、Bluetooth、証明書、ファイルアクセス等）判定
     */
    fun isSpecialSystemDialogPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return isPurePermissionPackage(p) ||
                p == "com.android.vpndialogs" ||
                p == "com.android.companiondevicemanager" ||
                p == "com.android.certinstaller" ||
                p == "com.google.android.certinstaller" ||
                p == "com.android.bluetooth" ||
                p == "com.google.android.documentsui" ||
                p == "com.android.documentsui"
    }

    /**
     * Settingsアプリ（com.android.settings）配下の権限・特殊アクセス確認ダイアログ判定
     */
    fun isSettingsConfirmationDialog(node: AccessibilityNodeInfo?, className: String = ""): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        if (pkg != "com.android.settings") return false

        if (className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("Confirm", ignoreCase = true) ||
            className.contains("Prompt", ignoreCase = true)
        ) {
            return true
        }

        return hasDialogIndicators(node, depth = 0)
    }

    /**
     * SystemUI配下のシステムダイアログ（画面キャスト/録画許可、USB接続許可等）判定
     */
    fun isSystemUiDialog(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        if (!pkg.contains("systemui") && pkg != "android") return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        // 通常の通知シェード・ステータスバー・ロック画面枠コンテナ・クイック設定は除外
        if (viewId.contains("notification_shade") || viewId.contains("keyguard") ||
            viewId.contains("notification_panel") || viewId.contains("status_bar") ||
            viewId.contains("quick_settings") || viewId.contains("qs_panel")
        ) {
            return false
        }

        return hasDialogIndicators(node, depth = 0)
    }

    /**
     * ノード内にダイアログ特有の要素（alertTitle, message, 肯定/否定ボタン等）が存在するか探索
     * 最大深さ15階層まで確実に走査（Android標準AlertDialog / PermissionControllerの深い階層に対応）
     */
    fun hasDialogIndicators(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 15) return false
        val id = node.viewIdResourceName?.lowercase() ?: ""
        val cls = node.className?.toString() ?: ""

        if (id.contains("alerttitle") || id.contains("alert_title") ||
            id.contains("permission_message") || id.contains("permission_grant_title") ||
            id.contains("permission_grant_dialog") || id.contains("grant_dialog") ||
            id.contains("grant_singleton") ||
            id.contains("permission_icon") || id.contains("permission_rationale") ||
            id.contains("dialog_title") ||
            id.endsWith(":id/button1") || id.endsWith(":id/button2") || id.endsWith(":id/button3") ||
            id.contains("permission_allow_button") ||
            id.contains("permission_allow_foreground_only_button") ||
            id.contains("permission_allow_one_time_button") ||
            id.contains("permission_allow_always_button") ||
            id.contains("permission_allow_all_button") ||
            id.contains("permission_allow_selected_button") ||
            id.contains("permission_deny_button") ||
            id.contains("permission_dont_allow_button") ||
            id.contains("button_start") || id.contains("btn_confirm") ||
            id.contains("btn_positive") || id.contains("btn_negative") ||
            id.contains("buttonpanel") ||
            id.contains("media_projection") || id.contains("remember_choice") ||
            cls.contains("AlertDialog", ignoreCase = true) ||
            cls.contains("DialogTitle", ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until node.childCount.coerceAtMost(25)) {
            val child = node.getChild(i) ?: continue
            if (hasDialogIndicators(child, depth + 1)) return true
        }
        return false
    }

    /**
     * 汎用ダイアログ判定
     */
    fun isGeneralDialog(node: AccessibilityNodeInfo?, className: String = ""): Boolean {
        if (node == null) return false
        if (className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("PopupWindow", ignoreCase = true) ||
            className.contains("AlertActivity", ignoreCase = true) ||
            className.contains("PermissionActivity", ignoreCase = true) ||
            className.contains("GrantPermissionsActivity", ignoreCase = true) ||
            className.contains("ConfirmActivity", ignoreCase = true)
        ) {
            return true
        }
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Dialog", ignoreCase = true) || cls.contains("AlertDialog", ignoreCase = true)) {
            return true
        }
        val id = node.viewIdResourceName?.lowercase() ?: ""
        if (id.contains("alerttitle") || id.contains("dialog_root") || id.contains("grant_dialog") || id.contains("grant_singleton")) {
            return true
        }
        return hasDialogIndicators(node, depth = 0)
    }

    fun isDialogHandlingActive(): Boolean {
        val now = System.currentTimeMillis()
        return pendingDialogRunnable != null || (now - lastHandledTimeMs) < 2500L
    }

    private fun getRootAndRefresh(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        var curr: AccessibilityNodeInfo = node
        var guard = 0
        while (curr.parent != null && guard < 30) {
            curr = curr.parent ?: break
            guard++
        }
        try { curr.refresh() } catch (_: Exception) {}
        return curr
    }

    /**
     * 最前面のダイアログウィンドウを検出して取得
     */
    fun findTopDialogWindow(
        service: SerenaScreenReaderService,
        fallbackSource: AccessibilityNodeInfo? = null
    ): Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>? {
        try {
            val wins = service.windows
            if (!wins.isNullOrEmpty()) {
                val sortedWins = wins.sortedByDescending { it.layer }

                // 1. 純粋な権限確認・特殊システムダイアログのウィンドウを最優先
                for (w in sortedWins) {
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    try { root.refresh() } catch (_: Exception) {}
                    val pkg = root.packageName?.toString() ?: ""
                    if (isPurePermissionPackage(pkg) || isSpecialSystemDialogPackage(pkg)) {
                        return Pair(w, root)
                    }
                }

                // 2. OEMセキュリティパッケージ（MIUI Security, ColorOS SafeCenter等）でダイアログ要素を含む場合
                for (w in sortedWins) {
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (isOemSecurityPackage(pkg)) {
                        if (isGeneralDialog(root) || hasDialogIndicators(root)) {
                            return Pair(w, root)
                        }
                    }
                }

                // 3. SystemUI / Android OS / Settings のシステムダイアログ・電源ボタンメニュー（GlobalActions）
                for (w in sortedWins) {
                    val root = try { w.root } catch (_: Exception) { null } ?: continue
                    val wTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) w.title else null
                    if (TalkBack17SystemWindowBridge.isPowerMenuGlobalActions(root, wTitle) ||
                        TalkBack17SystemWindowBridge.isCredentialOrBiometricWindow(root) ||
                        isSystemUiDialog(root) ||
                        isSettingsConfirmationDialog(root)
                    ) {
                        return Pair(w, root)
                    }
                }

                // 4. アプリ内ダイアログ（すべての前面ウィンドウを走査！ステータスバー等のシステム枠はスキップ）
                for (w in sortedWins) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION || w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val root = try { w.root } catch (_: Exception) { null } ?: continue
                        val pkg = root.packageName?.toString()?.lowercase() ?: ""
                        if (pkg.contains("systemui") && !isSystemUiDialog(root)) continue
                        if (isGeneralDialog(root) || hasDialogIndicators(root)) {
                            return Pair(w, root)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findTopDialogWindow error: ${e.message}")
        }

        // 5. rootInActiveWindow および fallbackSource（ルート昇格＆リフレッシュ済み）による直接フォールバック捕捉
        val activeRoot = service.rootInActiveWindow?.also { try { it.refresh() } catch (_: Exception) {} }
        val fallbackRoot = getRootAndRefresh(fallbackSource)
        val candidates = listOfNotNull(activeRoot, fallbackRoot)
        for (cand in candidates) {
            val pkg = cand.packageName?.toString() ?: ""
            if (isPurePermissionPackage(pkg) ||
                isSpecialSystemDialogPackage(pkg) ||
                isSystemUiDialog(cand) ||
                isSettingsConfirmationDialog(cand) ||
                isGeneralDialog(cand) ||
                hasDialogIndicators(cand)
            ) {
                return Pair(null, cand)
            }
        }

        return null
    }

    /**
     * 多言語対応のポジティブ・肯定アクションボタン文字列判定
     */
    private fun isPositiveActionText(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return false
        return t == "許可" || t == "許可する" || t == "すべて許可" || t == "アプリの使用時のみ" ||
                t == "アプリの使用中のみ許可" || t == "アプリの使用中のみ" || t == "常に許可" ||
                t == "今回のみ" || t == "今回のみ許可" || t == "有効にする" || t == "設定" ||
                t == "開く" || t == "同意する" || t == "同意して続行" || t == "ペア設定" ||
                t == "インストール" || t == "更新" || t == "接続" || t == "ok" || t == "はい" ||
                t == "開始" || t == "決定" || t == "今すぐ開始" || t == "続行" ||
                t.contains("選択した写真") || t.contains("すべて許可") ||
                t.contains("正確な位置情報") || t.contains("おおよその位置情報") ||
                t == "allow" || t == "allow all" || t == "while using the app" || t == "only this time" ||
                t == "always allow" || t == "always" || t == "yes" || t == "start now" ||
                t == "confirm" || t == "continue" || t == "grant" || t == "enable" ||
                t == "install" || t == "update" || t == "agree" || t == "pair" || t == "connect" ||
                t == "permitir" || t == "mientras la app esté en uso" || t == "solo esta vez" ||
                t == "aceptar" || t == "sí" || t == "iniciar ahora" ||
                t == "payagan" || t == "habang ginagamit ang app" || t == "sa pagkakataong ito lang" ||
                t == "oo" || t == "simulan ngayon" || t == "kumpirmahin" ||
                t == "toestaan" || t == "bij gebruik van app" || t == "alleen deze keer" ||
                t == "altijd toestaan" || t == "akkoord" || t == "ja" || t == "nu starten"
    }

    private fun isDialogContainerNode(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.lowercase() ?: ""
        val cls = n.className?.toString() ?: ""
        if (id.contains("grant_singleton") || id.contains("grant_dialog") ||
            id.contains("permission_grant_dialog") ||
            id.contains("parentpanel") || id.contains("contentpanel") ||
            id.contains("buttonpanel") || id.contains("toppanel") ||
            id.contains("custompanel") || id.contains("scrim") ||
            id.contains("content_container") || id.contains("button_group") ||
            id.contains("design_bottom_sheet")
        ) {
            return true
        }
        if (n.childCount > 0 && (
                cls.contains("FrameLayout", ignoreCase = true) ||
                cls.contains("LinearLayout", ignoreCase = true) ||
                cls.contains("RelativeLayout", ignoreCase = true) ||
                cls.contains("ConstraintLayout", ignoreCase = true) ||
                cls.contains("ScrollView", ignoreCase = true) ||
                cls.contains("ViewGroup", ignoreCase = true)
            )
        ) {
            // もしクラスがレイアウトコンテナで、配下にクリック可能な子ボタンがあるなら自身はボタンではない
            for (i in 0 until n.childCount.coerceAtMost(15)) {
                val c = n.getChild(i) ?: continue
                if (c.isClickable || c.className?.toString()?.contains("Button", ignoreCase = true) == true) {
                    return true
                }
            }
            // viewIdがボタン系でなくテキストも直接持たない大枠コンテナは除外
            val hasDirectText = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
            if (!hasDirectText && !id.contains("button") && !id.contains("btn")) {
                return true
            }
        }
        return false
    }

    /**
     * ダイアログノード群を解析し、タイトル・本文・主要アクションボタンを精密抽出
     */
    fun inspectDialog(dialogRoot: AccessibilityNodeInfo, service: SerenaScreenReaderService): DialogInfo {
        try { dialogRoot.refresh() } catch (_: Exception) {}
        val pkg = dialogRoot.packageName?.toString() ?: ""
        val isPerm = isPurePermissionPackage(pkg) ||
                (isOemSecurityPackage(pkg) && (isGeneralDialog(dialogRoot) || hasDialogIndicators(dialogRoot)))
        val isSpecial = isSpecialSystemDialogPackage(pkg) || isSystemUiDialog(dialogRoot)

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(dialogRoot, allNodes)

        var titleText = ""
        var messageText = ""

        // 1. タイトル探索
        // 優先度A: alertTitle, permission_message (Android 11-16で質問文が設定されるID), dialog_title, title
        val titleNode = allNodes.find { n ->
            val id = n.viewIdResourceName?.lowercase() ?: ""
            (id.contains("alerttitle") || id.contains("alert_title") ||
                    id.contains("permission_message") || id.contains("permission_grant_title") ||
                    id.contains("dialog_title") || id.contains("header_title") ||
                    id.contains("title_template") ||
                    id.endsWith(":id/title")) &&
                    service.getNodeText(n).trim().isNotEmpty()
        } ?: allNodes.find { n ->
            // 優先度B: 見出し(Heading)属性 または DialogTitle クラス
            ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && n.isHeading) ||
                    (!n.isClickable && n.className?.toString()?.contains("DialogTitle", ignoreCase = true) == true)) &&
                    service.getNodeText(n).trim().isNotEmpty()
        } ?: allNodes.find { n ->
            // 優先度C: クリック・チェック不能な先頭のテキストノード（コンテナは除外）
            !n.isClickable && !n.isCheckable && !isDialogContainerNode(n) &&
                    n.childCount == 0 && service.getNodeText(n).trim().isNotEmpty()
        }

        if (titleNode != null) {
            titleText = service.getNodeText(titleNode).trim()
        }

        if (titleText.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pane = dialogRoot.paneTitle?.toString()?.trim()
            if (!pane.isNullOrEmpty() && !pane.equals(" alert", ignoreCase = true)) {
                titleText = pane
            }
        }

        // 2. 本文・説明メッセージ探索
        val msgNode = allNodes.find { n ->
            val id = n.viewIdResourceName?.lowercase() ?: ""
            (id.contains("message") || id.contains("permission_grant_explanation") ||
                    id.contains("permission_rationale_message") || id.contains("permission_detail_message") ||
                    id.contains("desc") || id.contains("description") ||
                    id.contains("summary") || id.contains("body")) &&
                    n != titleNode &&
                    service.getNodeText(n).trim().isNotEmpty() &&
                    service.getNodeText(n).trim() != titleText
        } ?: allNodes.find { n ->
            !n.isClickable && !n.isCheckable && !isDialogContainerNode(n) &&
                    n.childCount == 0 && n != titleNode &&
                    service.getNodeText(n).trim().isNotEmpty() &&
                    service.getNodeText(n).trim() != titleText
        }

        if (msgNode != null) {
            messageText = service.getNodeText(msgNode).trim()
        }

        // 3. 主要アクションボタンの決定（grant_singleton や grant_dialog 等の外側枠は完全除外！）
        val candidateButtons = allNodes.filter { !isDialogContainerNode(it) }

        // 優先度A: 権限許可（「アプリの使用中のみ」「常に許可」「許可」「すべて許可」「今回のみ」）
        val rawPrimaryBtn = candidateButtons.find { n ->
            val id = n.viewIdResourceName?.lowercase() ?: ""
            (n.isClickable || hasClickableButtonAncestor(n)) && (
                    id.contains("permission_allow_foreground_only_button") ||
                    id.contains("permission_allow_always_button") ||
                    id.contains("permission_allow_button") ||
                    id.contains("permission_allow_all_button") ||
                    id.contains("permission_allow_selected_button") ||
                    id.contains("allow_foreground") ||
                    id.contains("allow_always") ||
                    id.contains("allow_all") ||
                    id.contains("allow_button") ||
                    id.contains("permission_allow_one_time_button")
            )
        } ?: candidateButtons.find { n ->
            // 優先度B: 標準ダイアログ button1 (OK / Positive / 開始)
            val id = n.viewIdResourceName?.lowercase() ?: ""
            (n.isClickable || hasClickableButtonAncestor(n)) && (
                    id.endsWith(":id/button1") ||
                    id.contains("button_start") ||
                    id.contains("btn_confirm") ||
                    id.contains("btn_ok") ||
                    id.contains("btn_positive") ||
                    id.contains("confirm_button")
            )
        } ?: candidateButtons.find { n ->
            // 優先度C: テキスト内容によるポジティブボタン推論
            val t = service.getNodeText(n)
            (n.isClickable || hasClickableButtonAncestor(n)) && isPositiveActionText(t)
        } ?: candidateButtons.find { n ->
            // 優先度D: その他のクリック可能なButtonクラス要素（否定ボタンしか無い場合でもボタンへフォーカス）
            val id = n.viewIdResourceName?.lowercase() ?: ""
            n.isClickable && (
                n.className?.toString()?.contains("Button", ignoreCase = true) == true ||
                id.contains("permission_deny") || id.contains("permission_dont_allow") ||
                id.endsWith(":id/button2") || id.endsWith(":id/button3")
            )
        }

        // 子テキストノードが選ばれた場合、クリック可能なボタン親ノードへと昇格（ただし grant_singleton 等の大枠には昇格させない）
        var primaryBtn = rawPrimaryBtn
        if (primaryBtn != null && !primaryBtn.isClickable) {
            var parent = primaryBtn.parent
            while (parent != null) {
                if (parent.isClickable && !isDialogContainerNode(parent)) {
                    primaryBtn = parent
                    break
                }
                parent = parent.parent
            }
        }

        return DialogInfo(
            isPermission = isPerm,
            isSpecialSystem = isSpecial,
            packageName = pkg,
            title = titleText,
            message = messageText,
            primaryButton = primaryBtn,
            dialogRoot = dialogRoot
        )
    }

    private fun hasClickableButtonAncestor(node: AccessibilityNodeInfo): Boolean {
        var p = node.parent
        while (p != null) {
            if (p.isClickable && !isDialogContainerNode(p)) return true
            p = p.parent
        }
        return false
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > 15 || list.size >= 150) return
        list.add(node)
        for (i in 0 until node.childCount.coerceAtMost(30)) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list, depth + 1)
        }
    }

    /**
     * 権限ダイアログ内で連続して次の権限確認へ切り替わった際（TYPE_WINDOW_CONTENT_CHANGED）のハンドラ
     */
    fun onPermissionContentChanged(
        service: SerenaScreenReaderService,
        sourceNode: AccessibilityNodeInfo?
    ) {
        if (pendingDialogRunnable != null) return
        val topDialog = findTopDialogWindow(service, sourceNode)
        val dialogRoot = topDialog?.second ?: getRootAndRefresh(sourceNode) ?: service.rootInActiveWindow ?: return
        val pkg = dialogRoot.packageName?.toString() ?: ""
        if (!isPermissionPackage(pkg) && !isSpecialSystemDialogPackage(pkg)) return

        val info = inspectDialog(dialogRoot, service)
        if (info.title.isEmpty() && info.message.isEmpty()) return
        val dialogKey = "${info.packageName}:${info.title}:${info.message}"
        if (dialogKey != lastHandledDialogKey) {
            announceDialogWithRetry(service, attempt = 1, fallbackNode = dialogRoot)
        }
    }

    /**
     * バックオフリトライ付きのダイアログ検知・読み上げ・フォーカス実行
     * (Attempt 1: 80ms, Attempt 2: 220ms, Attempt 3: 400ms, Attempt 4: 650ms)
     */
    fun announceDialogWithRetry(
        service: SerenaScreenReaderService,
        attempt: Int = 1,
        fallbackNode: AccessibilityNodeInfo? = null
    ) {
        pendingDialogRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDialogRunnable = null

        val delays = longArrayOf(80L, 220L, 400L, 650L)
        val delayMs = if (attempt - 1 in delays.indices) delays[attempt - 1] else 250L

        val runnable = Runnable {
            pendingDialogRunnable = null
            try {
                // 最前面ダイアログウィンドウを探索
                val topDialog = findTopDialogWindow(service, fallbackNode)
                val dialogRoot = topDialog?.second ?: getRootAndRefresh(fallbackNode) ?: service.rootInActiveWindow

                if (dialogRoot != null) {
                    val pkg = dialogRoot.packageName?.toString() ?: ""
                    val isPerm = isPurePermissionPackage(pkg) ||
                            (isOemSecurityPackage(pkg) && (isGeneralDialog(dialogRoot) || hasDialogIndicators(dialogRoot)))
                    val isSpecial = isSpecialSystemDialogPackage(pkg) || isSystemUiDialog(dialogRoot) || isSettingsConfirmationDialog(dialogRoot)
                    val isGen = isGeneralDialog(dialogRoot) || hasDialogIndicators(dialogRoot)

                    if (isPerm || isSpecial || isGen) {
                        val info = inspectDialog(dialogRoot, service)
                        val hasTextContent = info.title.isNotEmpty() || info.message.isNotEmpty()
                        // 権限ダイアログでは、ViewModelによる非同期バインド完了（質問テキスト＋許可ボタンの両方）を最大Attempt 3まで待つ！
                        val isReady = if (isPerm && attempt < 4) {
                            hasTextContent && info.primaryButton != null
                        } else {
                            hasTextContent || info.primaryButton != null
                        }

                        if (isReady) {
                            val now = System.currentTimeMillis()
                            val dialogKey = "${info.packageName}:${info.title}:${info.message}"
                            if (dialogKey == lastHandledDialogKey && (now - lastHandledTimeMs) < 1500L) {
                                // フォーカスのみ確実に適用
                                info.primaryButton?.let { btn ->
                                    service.focusNavigator?.setFocusAndShowOnScreen(btn)
                                }
                                return@Runnable
                            }

                            lastHandledDialogKey = dialogKey
                            lastHandledTimeMs = now

                            // アナウンス文の組み立て
                            val tag = if (info.isPermission) {
                                service.getString(R.string.permission_dialog_tag)
                            } else {
                                service.getString(R.string.dialog_tag_confirmation)
                            }

                            val title = if (info.title.isNotEmpty()) {
                                info.title
                            } else if (info.isPermission) {
                                service.getString(R.string.permission_dialog_default_msg)
                            } else {
                                service.getString(R.string.dialog_default_title)
                            }

                            val fullAnnouncement = if (info.message.isNotEmpty() && info.message != title) {
                                service.getString(R.string.dialog_announcement_with_title_and_message_fmt, tag, title, info.message)
                            } else {
                                service.getString(R.string.dialog_announcement_fmt, tag, title)
                            }

                            service.soundHelper?.playFocusMove()

                            if (service.isTtsReady) {
                                service.speak(fullAnnouncement, TextToSpeech.QUEUE_FLUSH)
                            }

                            // 主要アクションボタンにフォーカスを設定
                            // （フォーカスイベント時に QUEUE_ADD でボタン読み上げが行われるよう lastHandledTimeMs で保護！）
                            val targetBtn = info.primaryButton
                            if (targetBtn != null) {
                                service.focusNavigator?.setFocusAndShowOnScreen(targetBtn)

                                // 安全策: 450ms経過してもアクセシビリティフォーカスイベントが届いていない場合は直接追加アナウンス
                                mainHandler.postDelayed({
                                    if (service.lastFocusTimeMs < now && service.isTtsReady) {
                                        service.announceNode(targetBtn, TextToSpeech.QUEUE_ADD)
                                    }
                                }, 450L)
                            }
                            return@Runnable
                        }
                    }
                }

                // まだノードが描画完了していない場合、リトライ
                if (attempt < 4) {
                    announceDialogWithRetry(service, attempt + 1, fallbackNode)
                } else {
                    // 全リトライ完了後も要素が取れなかった場合のフォールバックヒント
                    if (service.isTtsReady) {
                        val activePkg = dialogRoot?.packageName?.toString() ?: service.rootInActiveWindow?.packageName?.toString() ?: ""
                        if (isPermissionPackage(activePkg)) {
                            service.speak(service.getString(R.string.permission_dialog_hint), TextToSpeech.QUEUE_FLUSH)
                        } else {
                            service.speak(service.getString(R.string.dialog_hint), TextToSpeech.QUEUE_FLUSH)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "announceDialogWithRetry error: ${t.message}", t)
            }
        }

        pendingDialogRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }
}
