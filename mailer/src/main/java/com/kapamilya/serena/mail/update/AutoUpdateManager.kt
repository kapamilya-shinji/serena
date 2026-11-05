package com.kapamilya.serena.mail.update

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
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kapamilya.serena.mail.BuildConfig
import com.kapamilya.serena.mail.R
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * AutoUpdateManager (Serena Mail)
 *
 * 全盲の視覚障害者ユーザーが外部ブラウザやファイル管理アプリで迷うことなく、
 * アプリ内からワンタップで Serena Mail の最新バージョンを自動検出・ダウンロード・更新できる
 * アクセシブル・オートアップデーター。
 */
class AutoUpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MailAutoUpdate"
        private const val GITLAB_REPO_MANIFEST_URL = "https://gitlab.com/kapamilya/serena/-/raw/main/latest.json"
        private const val GITLAB_PAGES_MANIFEST_URL = "https://kapamilya.gitlab.io/latest.json"
        private const val EXPECTED_APK_NAME = "app-kapamilya-serena-mail-release.apk"
        private const val LEGACY_APK_NAME = "app-serena-mail-release.apk"
        const val PREFS_NAME = "serena_mail_update_prefs"
        const val KEY_LAST_CHECK_TIME = "last_update_check_time"
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
                setRequestProperty("User-Agent", "Kapamilya-Serena-Mail/${BuildConfig.VERSION_NAME}")
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
            if (it.exists() && it.length() > 100_000L) return it
        }
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val path = prefs.getString(KEY_PENDING_APK_PATH, null)
            if (!path.isNullOrEmpty()) {
                val f = File(path)
                if (f.exists() && f.length() > 100_000L) {
                    pendingInstallApkFile = f
                    return f
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * GitLab リポジトリと GitLab Pages の両方の回線速度をリアルタイム計測し、
     * 高速な方のサーバーを自動選択して Kapamilya Serena Mail の最新リリース情報を取得します。
     */
    fun checkForUpdate(silent: Boolean = false, onResult: (UpdateInfo?) -> Unit) {
        val appContext = context.applicationContext
        if (!silent) {
            val checkMsg = appContext.getString(R.string.update_checking)
            Toast.makeText(appContext, checkMsg, Toast.LENGTH_SHORT).show()
        }

        thread(name = "SerenaMailUpdateChecker") {
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

    /**
     * セマンティックバージョニング比較 (例: 2.6.0 > 2.5.9)
     */
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
     * 不明なアプリのインストール許可後に復帰した際、保留中の更新インストールを即座に再開します。
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
     * Serena Mail の最新 APK をバックグラウンドダウンロードし、完了時に自動でインストールを起動します。
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

        thread(name = "SerenaMailApkDownloader") {
            var conn: HttpURLConnection? = null
            try {
                var currentUrl = downloadUrl
                var redirects = 0

                while (redirects < 8) {
                    val u = URL(currentUrl)
                    conn = (u.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 20000
                        readTimeout = 60000
                        setRequestProperty("User-Agent", "Serena-Mail/${BuildConfig.VERSION_NAME}")
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
                        conn = null
                        if (location.isNullOrEmpty()) break
                        currentUrl = URL(u, location).toString()
                        redirects++
                    } else {
                        break
                    }
                }

                val finalConn = conn ?: throw Exception("Connection could not be established")
                if (finalConn.responseCode != HttpURLConnection.HTTP_OK) {
                    try { finalConn.errorStream?.close() } catch (_: Exception) {}
                    throw Exception("HTTP ${finalConn.responseCode}")
                }

                finalConn.inputStream.buffered(64 * 1024).use { input ->
                    tempFile.outputStream().buffered(64 * 1024).use { output ->
                        input.copyTo(output, 64 * 1024)
                        output.flush()
                    }
                }

                if (tempFile.length() < 100_000L) {
                    val len = tempFile.length()
                    tempFile.delete()
                    throw Exception("Downloaded APK is incomplete ($len bytes)")
                }

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
                    installApk(destinationFile)
                }
            } catch (e: Exception) {
                try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
                Log.e(TAG, "Failed to download update APK: ${e.message}", e)
                Handler(Looper.getMainLooper()).post {
                    val errorMsg = appContext.getString(R.string.update_download_failed, e.message ?: "")
                    onStatus?.invoke(errorMsg)
                    Toast.makeText(appContext, errorMsg, Toast.LENGTH_LONG).show()
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!appContext.packageManager.canRequestPackageInstalls()) {
                    savePendingApkPath(file)
                    isWaitingForUnknownSourcesPermission = true
                    showUnknownSourcesPermissionDialog()
                    return
                }
            }

            isWaitingForUnknownSourcesPermission = false
            savePendingApkPath(null)

            val launchMsg = appContext.getString(R.string.update_launching_installer)
            Toast.makeText(appContext, launchMsg, Toast.LENGTH_SHORT).show()

            launchSystemPackageInstaller(appContext, file)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package installer: ${e.message}", e)
            val failMsg = appContext.getString(R.string.update_install_failed, e.message ?: "")
            Toast.makeText(appContext, failMsg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showUnknownSourcesPermissionDialog() {
        val appContext = context.applicationContext
        val permMsg = appContext.getString(R.string.update_permission_required)
        Toast.makeText(appContext, permMsg, Toast.LENGTH_LONG).show()

        val openSettingsAction = {
            try {
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivitySafely(manageIntent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivitySafely(fallbackIntent)
            }
        }

        val act = activeActivityRef?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            try {
                AlertDialog.Builder(act)
                    .setTitle(act.getString(R.string.update_dialog_title, ""))
                    .setMessage(permMsg)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        openSettingsAction()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        isWaitingForUnknownSourcesPermission = false
                    }
                    .show()
                return
            } catch (_: Exception) {}
        }

        openSettingsAction()
    }

    private fun startActivitySafely(intent: Intent) {
        val act = activeActivityRef?.get()
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            act.startActivity(intent)
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
        var session: PackageInstaller.Session? = null
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
            session = packageInstaller.openSession(sessionId)

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
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            return true
        } catch (e: Exception) {
            try { session?.abandon() } catch (_: Exception) {}
            Log.w(TAG, "PackageInstaller session failed: ${e.message}")
            return false
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }

    /**
     * アプリ内更新案内ダイアログの表示
     */
    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        setActiveActivity(activity)
        val title = activity.getString(R.string.update_dialog_title, info.latestVersion)
        val message = activity.getString(
            R.string.update_dialog_message,
            info.currentVersion,
            info.latestVersion,
            info.releaseNotes.take(300) + if (info.releaseNotes.length > 300) "..." else ""
        )

        try {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.update_btn_install_now) { _, _ ->
                    startDownloadAndInstall(info.downloadUrl) { statusMsg ->
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.findViewById<View>(android.R.id.content)?.announceForAccessibility(statusMsg)
                        }
                    }
                }
                .setNeutralButton(R.string.update_btn_open_github) { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://kapamilya.gitlab.io"))
                        activity.startActivity(browserIntent)
                    } catch (_: Exception) {}
                }
                .setNegativeButton(R.string.update_btn_later, null)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show update dialog: ${e.message}")
        }
    }
}
