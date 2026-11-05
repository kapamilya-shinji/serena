package com.kapamilya.serena.update

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kapamilya.serena.BuildConfig
import com.kapamilya.serena.R
import com.kapamilya.serena.SerenaScreenReaderService
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * AutoUpdateManager
 *
 * 全盲のユーザーがブラウザやファイルマネージャーで迷うことなく、
 * アプリ内から1タップで最新バージョンの検出・自動ダウンロード・インストールを行える
 * インテリジェント・アクセシブル・アップデートエンジン。
 */
class AutoUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "AutoUpdateManager"
        private const val GITLAB_REPO_MANIFEST_URL = "https://gitlab.com/kapamilya/serena/-/raw/main/latest.json"
        private const val GITLAB_PAGES_MANIFEST_URL = "https://kapamilya.gitlab.io/latest.json"
        private const val EXPECTED_APK_NAME = "app-kapamilya-serena-release.apk"
        private const val LEGACY_APK_NAME = "app-serena-release.apk"
        const val PREFS_NAME = "serena_update_prefs"
        const val KEY_LAST_CHECK_TIME = "last_update_check_time"
        const val KEY_IGNORED_VERSION = "ignored_version"
        private const val KEY_PENDING_APK_PATH = "pending_install_apk_path"

        @Volatile
        private var instance: AutoUpdateManager? = null

        @Volatile
        var pendingInstallApkFile: File? = null

        @Volatile
        var isWaitingForUnknownSourcesPermission: Boolean = false

        @Volatile
        private var activeActivityRef: WeakReference<Activity>? = null

        fun setActiveActivity(activity: Activity?) {
            activeActivityRef = if (activity != null) WeakReference(activity) else null
        }

        fun getInstance(context: Context): AutoUpdateManager {
            if (context is Activity) {
                setActiveActivity(context)
            }
            return instance ?: synchronized(this) {
                instance ?: AutoUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val releaseDate: String
    )

    private fun savePendingApkPath(file: File?) {
        pendingInstallApkFile = file
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (file != null) {
                prefs.edit().putString(KEY_PENDING_APK_PATH, file.absolutePath).apply()
            } else {
                prefs.edit().remove(KEY_PENDING_APK_PATH).apply()
            }
        } catch (_: Exception) {}
    }

    private fun getSavedPendingApkFile(): File? {
        pendingInstallApkFile?.let {
            if (it.exists() && it.length() > 1_000_000L) return it
        }
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val path = prefs.getString(KEY_PENDING_APK_PATH, null)
            if (!path.isNullOrEmpty()) {
                val f = File(path)
                if (f.exists() && f.length() > 1_000_000L) {
                    pendingInstallApkFile = f
                    return f
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * GitHub Releases API を非同期で照会し、最新リリースの有無を判定します。
     */
    private data class EndpointProbeResult(
        val routeName: String,
        val latencyMs: Long,
        val jsonBody: String
    )

    private fun probeManifestEndpoint(routeName: String, urlStr: String): EndpointProbeResult? {
        val startMs = android.os.SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$urlStr?t=${System.currentTimeMillis()}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Kapamilya-Serena-ScreenReader/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val elapsed = (android.os.SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                EndpointProbeResult(routeName, elapsed, body)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed for $routeName ($urlStr): ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * GitLab リポジトリと GitLab Pages の両方の回線速度をリアルタイム計測し、
     * 高速な方のサーバーを自動選択して最新リリースの有無を判定します。
     */
    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        thread(name = "SerenaUpdateChecker") {
            try {
                val pagesProbe = probeManifestEndpoint("GitLab Pages", GITLAB_PAGES_MANIFEST_URL)
                val repoProbe = probeManifestEndpoint("GitLab Repository", GITLAB_REPO_MANIFEST_URL)

                val winner = when {
                    pagesProbe != null && repoProbe != null ->
                        if (pagesProbe.latencyMs <= repoProbe.latencyMs) pagesProbe else repoProbe
                    pagesProbe != null -> pagesProbe
                    repoProbe != null -> repoProbe
                    else -> null
                }

                if (winner == null) {
                    Handler(Looper.getMainLooper()).post { onResult(null) }
                    return@thread
                }

                val json = JSONObject(winner.jsonBody)
                val appsObj = json.optJSONObject("apps")
                val appEntry = appsObj?.optJSONObject(BuildConfig.APPLICATION_ID)

                val latestVer = appEntry?.optString("version", "")?.removePrefix("v")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: json.optString("tag_name", "").removePrefix("v").trim()
                val tagName = if (latestVer.startsWith("v")) latestVer else "v$latestVer"
                val rawNotes = appEntry?.optString("notes", "")?.takeIf { it.isNotEmpty() }
                    ?: json.optString("body", "")
                val releaseDate = json.optString("updated_at", json.optString("published_at", ""))

                val repoDlUrl = appEntry?.optString("gitlab_repo_url", "") ?: ""
                val pagesDlUrl = appEntry?.optString("gitlab_pages_url", "") ?: ""
                var downloadUrl = if (winner.routeName == "GitLab Pages" && pagesDlUrl.isNotEmpty()) pagesDlUrl else repoDlUrl
                if (downloadUrl.isEmpty()) {
                    downloadUrl = "https://gitlab.com/api/v4/projects/kapamilya%2Fserena/packages/generic/kapamilya-serena/5.14.0/$EXPECTED_APK_NAME"
                }

                val latestClean = latestVer.removePrefix("v").trim()
                val currentClean = BuildConfig.VERSION_NAME.removePrefix("v").trim()
                val hasUpdate = isNewerVersion(latestClean, currentClean)

                val info = UpdateInfo(
                    isUpdateAvailable = hasUpdate,
                    latestVersion = tagName,
                    currentVersion = "v${BuildConfig.VERSION_NAME}",
                    releaseNotes = "【高速回線自動選択: ${winner.routeName} (${winner.latencyMs}ms)】\n$rawNotes",
                    downloadUrl = downloadUrl,
                    releaseDate = releaseDate
                )

                Handler(Looper.getMainLooper()).post { onResult(info) }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for update: ${e.message}")
                Handler(Looper.getMainLooper()).post { onResult(null) }
            }
        }
    }
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestBase = latest.substringBefore("-")
            val currentBase = current.substringBefore("-")
            val latestParts = latestBase.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentBase.split(".").map { it.toIntOrNull() ?: 0 }
            val length = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until length) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * 設定画面等から権限付与後に復帰した際、保留中のアップデートインストールを即座に自動再開します。
     */
    fun checkAndResumePendingInstall(): Boolean {
        val pending = getSavedPendingApkFile() ?: return false
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appContext.packageManager.canRequestPackageInstalls()) {
                Log.i(TAG, "Permission granted! Resuming pending install: ${pending.absolutePath}")
                isWaitingForUnknownSourcesPermission = false
                savePendingApkPath(null)
                Handler(Looper.getMainLooper()).post {
                    installApk(pending)
                }
                return true
            }
        }
        return false
    }

    private fun getUpdateStorageDirectory(appContext: Context): File {
        val extDir = try {
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            null
        }
        val targetDir = if (extDir != null) {
            File(extDir, "updates")
        } else {
            File(appContext.filesDir, "updates")
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * 最新の APK をバックグラウンドで高速ダウンロードし、完了時に自動でインストール画面へ遷移します。
     */
    fun startDownloadAndInstall(downloadUrl: String, onStatus: ((String) -> Unit)? = null) {
        val appContext = context.applicationContext
        val updateDir = getUpdateStorageDirectory(appContext)
        val destinationFile = File(updateDir, EXPECTED_APK_NAME)
        val tempFile = File(updateDir, "$EXPECTED_APK_NAME.tmp")
        try {
            if (tempFile.exists()) tempFile.delete()
        } catch (_: Exception) {}

        val startMsg = appContext.getString(R.string.update_download_started)
        onStatus?.invoke(startMsg)
        Toast.makeText(appContext, startMsg, Toast.LENGTH_SHORT).show()
        if (SerenaScreenReaderService.isServiceRunning()) {
            SerenaScreenReaderService.instance?.speak(startMsg, TextToSpeech.QUEUE_FLUSH)
        }

        thread(name = "SerenaApkDownloader") {
            try {
                var currentUrl = downloadUrl
                var conn: HttpURLConnection? = null
                var redirects = 0

                while (redirects < 8) {
                    val u = URL(currentUrl)
                    conn = (u.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 20000
                        readTimeout = 60000
                        setRequestProperty("User-Agent", "Serena-ScreenReader/${BuildConfig.VERSION_NAME}")
                        setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                    }
                    val status = conn.responseCode
                    if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 ||
                        status == 308
                    ) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location.isNullOrEmpty()) break
                        currentUrl = location
                        redirects++
                    } else {
                        break
                    }
                }

                val finalConn = conn ?: throw Exception("Connection could not be established")
                if (finalConn.responseCode != HttpURLConnection.HTTP_OK) {
                    val errCode = finalConn.responseCode
                    finalConn.disconnect()
                    throw Exception("HTTP $errCode")
                }

                val totalBytes = finalConn.contentLengthLong
                var downloadedBytes = 0L
                var lastAnnouncedMilestone = 0

                finalConn.inputStream.buffered(64 * 1024).use { input ->
                    tempFile.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0L) {
                                val pct = ((downloadedBytes * 100L) / totalBytes).toInt()
                                val milestone = when {
                                    pct >= 75 && lastAnnouncedMilestone < 75 -> 75
                                    pct >= 50 && lastAnnouncedMilestone < 50 -> 50
                                    pct >= 25 && lastAnnouncedMilestone < 25 -> 25
                                    else -> 0
                                }
                                if (milestone > 0) {
                                    lastAnnouncedMilestone = milestone
                                    val progressMsg = appContext.getString(R.string.progress_percent, milestone)
                                    Handler(Looper.getMainLooper()).post {
                                        onStatus?.invoke(progressMsg)
                                        if (SerenaScreenReaderService.isServiceRunning()) {
                                            SerenaScreenReaderService.instance?.speak(progressMsg, TextToSpeech.QUEUE_ADD)
                                        }
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.length() < 1_000_000L) {
                    try { finalConn.disconnect() } catch (_: Exception) {}
                    tempFile.delete()
                    throw Exception("Downloaded APK is incomplete (${tempFile.length()} bytes)")
                }

                try { finalConn.disconnect() } catch (_: Exception) {}
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                if (!tempFile.renameTo(destinationFile)) {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                }
                destinationFile.setReadable(true, false)

                Log.i(TAG, "APK downloaded successfully: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")

                Handler(Looper.getMainLooper()).post {
                    val finishMsg = appContext.getString(R.string.update_download_finished)
                    onStatus?.invoke(finishMsg)
                    Toast.makeText(appContext, finishMsg, Toast.LENGTH_SHORT).show()
                    if (SerenaScreenReaderService.isServiceRunning()) {
                        SerenaScreenReaderService.instance?.speak(finishMsg, TextToSpeech.QUEUE_FLUSH)
                    }
                    installApk(destinationFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update APK: ${e.message}", e)
                Handler(Looper.getMainLooper()).post {
                    val errorMsg = appContext.getString(R.string.update_download_failed, e.message ?: "")
                    onStatus?.invoke(errorMsg)
                    Toast.makeText(appContext, errorMsg, Toast.LENGTH_LONG).show()
                    if (SerenaScreenReaderService.isServiceRunning()) {
                        SerenaScreenReaderService.instance?.speak(errorMsg, TextToSpeech.QUEUE_FLUSH)
                    }
                }
            }
        }
    }

    /**
     * Android 8.0+ 不明なアプリのインストール許可確認とシステム標準 PackageInstaller の強制起動
     */
    fun installApk(file: File) {
        val appContext = context.applicationContext
        try {
            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "APK file does not exist or is empty: ${file.absolutePath}")
                return
            }
            file.setReadable(true, false)

            // Android 8.0+ 未知のアプリ提供元の許可チェック
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!appContext.packageManager.canRequestPackageInstalls()) {
                    savePendingApkPath(file)
                    isWaitingForUnknownSourcesPermission = true
                    showUnknownSourcesPermissionDialog(file)
                    return
                }
            }

            isWaitingForUnknownSourcesPermission = false
            savePendingApkPath(null)

            val launchMsg = appContext.getString(R.string.update_launching_installer)
            Toast.makeText(appContext, launchMsg, Toast.LENGTH_SHORT).show()
            if (SerenaScreenReaderService.isServiceRunning()) {
                SerenaScreenReaderService.instance?.speak(launchMsg, TextToSpeech.QUEUE_FLUSH)
            }

            // Android 標準パッケージインストーラー画面を最前面に確実に起動
            launchSystemPackageInstaller(appContext, file)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer: ${e.message}", e)
            val failMsg = appContext.getString(R.string.update_install_failed, e.message ?: "")
            Toast.makeText(appContext, failMsg, Toast.LENGTH_LONG).show()
            if (SerenaScreenReaderService.isServiceRunning()) {
                SerenaScreenReaderService.instance?.speak(failMsg, TextToSpeech.QUEUE_FLUSH)
            }
        }
    }

    /**
     * 不明なアプリのインストールが未許可の場合、警告ダイアログを出して設定画面へ誘導します。
     */
    private fun showUnknownSourcesPermissionDialog(file: File) {
        val appContext = context.applicationContext
        val permMsg = appContext.getString(R.string.update_permission_required)
        Toast.makeText(appContext, permMsg, Toast.LENGTH_LONG).show()
        if (SerenaScreenReaderService.isServiceRunning()) {
            SerenaScreenReaderService.instance?.speak(permMsg, TextToSpeech.QUEUE_FLUSH)
        }

        val openSettingsAction = {
            try {
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivitySafely(manageIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivitySafely(fallbackIntent)
            }
        }

        val act = activeActivityRef?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            AlertDialog.Builder(act)
                .setTitle(act.getString(R.string.update_dialog_title, ""))
                .setMessage(permMsg)
                .setCancelable(false)
                .setPositiveButton(R.string.disclosure_agree) { _, _ ->
                    openSettingsAction()
                }
                .setNegativeButton(R.string.disclosure_cancel) { _, _ ->
                    isWaitingForUnknownSourcesPermission = false
                }
                .show()
            return
        }

        val service = SerenaScreenReaderService.instance
        if (service != null) {
            try {
                val dialog = AlertDialog.Builder(service, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(service.getString(R.string.update_dialog_title, ""))
                    .setMessage(permMsg)
                    .setCancelable(false)
                    .setPositiveButton(R.string.disclosure_agree) { _, _ ->
                        openSettingsAction()
                    }
                    .setNegativeButton(R.string.disclosure_cancel) { _, _ ->
                        isWaitingForUnknownSourcesPermission = false
                    }
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                dialog.show()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Overlay permission dialog failed, opening settings directly: ${e.message}")
            }
        }

        openSettingsAction()
    }

    private fun startActivitySafely(intent: Intent) {
        val act = activeActivityRef?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            act.startActivity(intent)
            return
        }
        val service = SerenaScreenReaderService.instance
        if (service != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.applicationContext.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchSystemPackageInstaller(appContext: Context, file: File) {
        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )

        // 1. ACTION_VIEW (application/vnd.android.package-archive) — CLEAR_TOP を付けずに起動
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, appContext.packageName)
        }

        grantUriPermissionToInstallers(appContext, viewIntent, apkUri)

        try {
            startActivitySafely(viewIntent)
            Log.i(TAG, "Launched PackageInstaller via ACTION_VIEW for ${file.name}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_VIEW PackageInstaller failed: ${e.message}, trying ACTION_INSTALL_PACKAGE")
        }

        // 2. フォールバック: ACTION_INSTALL_PACKAGE
        val installPkgIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, appContext.packageName)
        }

        grantUriPermissionToInstallers(appContext, installPkgIntent, apkUri)

        try {
            startActivitySafely(installPkgIntent)
            Log.i(TAG, "Launched PackageInstaller via ACTION_INSTALL_PACKAGE for ${file.name}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_INSTALL_PACKAGE failed: ${e.message}, trying PackageInstaller.Session")
        }

        // 3. 最終フォールバック: PackageInstaller Session API
        if (!tryInstallViaPackageInstallerSession(appContext, file)) {
            throw Exception("All PackageInstaller launch methods failed")
        }
    }

    private fun grantUriPermissionToInstallers(appContext: Context, intent: Intent, apkUri: Uri) {
        try {
            val resInfoList = appContext.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                val pkg = resolveInfo.activityInfo.packageName
                appContext.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val knownInstallers = listOf(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.miui.packageinstaller"
            )
            for (pkg in knownInstallers) {
                try {
                    appContext.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "grantUriPermissionToInstallers warning: ${e.message}")
        }
    }

    private fun tryInstallViaPackageInstallerSession(appContext: Context, file: File): Boolean {
        try {
            val packageInstaller = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(file.length())
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            file.inputStream().buffered(64 * 1024).use { input ->
                session.openWrite("package", 0, file.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }

            val intent = Intent(appContext, AutoUpdateInstallReceiver::class.java).apply {
                action = AutoUpdateInstallReceiver.ACTION_INSTALL_STATUS
                setPackage(appContext.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller session failed: ${e.message}")
            return false
        }
    }

    /**
     * アプリ内またはアクセシビリティサービスからの更新確認＆警告ダイアログ表示
     */
    fun showUpdateDialog(activityContext: Context, info: UpdateInfo) {
        if (activityContext is Activity) {
            setActiveActivity(activityContext)
        }
        val title = activityContext.getString(R.string.update_dialog_title, info.latestVersion)
        val message = activityContext.getString(
            R.string.update_dialog_message,
            info.currentVersion,
            info.latestVersion,
            info.releaseNotes.take(300) + if (info.releaseNotes.length > 300) "..." else ""
        )

        if (SerenaScreenReaderService.isServiceRunning()) {
            SerenaScreenReaderService.instance?.speak("$title. $message", TextToSpeech.QUEUE_FLUSH)
        }

        if (activityContext is Activity && !activityContext.isFinishing && !activityContext.isDestroyed) {
            AlertDialog.Builder(activityContext)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.update_btn_install_now) { _, _ ->
                    startDownloadAndInstall(info.downloadUrl)
                }
                .setNeutralButton(R.string.update_btn_open_github) { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kapamilya.gitlab.io"))
                        activityContext.startActivity(browserIntent)
                    } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.update_btn_later, null)
                .show()
            return
        }

        val service = SerenaScreenReaderService.instance
        if (service != null) {
            try {
                val dialog = AlertDialog.Builder(service, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.update_btn_install_now) { _, _ ->
                        startDownloadAndInstall(info.downloadUrl)
                    }
                    .setNeutralButton(R.string.update_btn_open_github) { _, _ ->
                        try {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kapamilya.gitlab.io")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            service.startActivity(browserIntent)
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton(R.string.update_btn_later, null)
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                dialog.show()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Overlay update dialog failed, starting download directly: ${e.message}")
            }
        }

        startDownloadAndInstall(info.downloadUrl)
    }
}
