package com.kapamilya.serena.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * PackageInstaller Session API のコールバックを受信し、
 * 「このアプリを更新しますか？」確認ダイアログを自動起動するレシーバー（Phone用）
 */
class PhoneUpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneUpdateReceiver"
        const val ACTION_INSTALL_STATUS = "com.kapamilya.serena.phone.action.INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmIntent != null) {
                try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start confirmation activity: ${e.message}")
                }
            }
        }
    }
}

/**
 * KapamilyaDualEndpointUpdater (Kapamilya Serena Phone)
 * GitLab リポジトリ (https://gitlab.com/kapamilya/serena) と GitLab Pages (https://kapamilya.gitlab.io) の
 * 両方の回線速度を並列計測し、高速な回線を自動選択してアップデートを行うエンジン。
 */
object KapamilyaDualEndpointUpdater {
    private const val TAG = "PhoneDualUpdater"
    private const val GITLAB_REPO_MANIFEST_URL = "https://gitlab.com/kapamilya/serena/-/raw/main/latest.json"
    private const val GITLAB_PAGES_MANIFEST_URL = "https://kapamilya.gitlab.io/latest.json"
    private const val DEFAULT_APK_NAME = "app-kapamilya-serena-phone-release.apk"

    @Volatile
    private var pendingApkFile: File? = null

    private data class ProbeResult(
        val routeName: String,
        val latencyMs: Long,
        val body: String
    )

    private fun probeEndpoint(routeName: String, urlStr: String): ProbeResult? {
        val start = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$urlStr?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Kapamilya-Serena-Phone/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val ms = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                ProbeResult(routeName, ms, text)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed for $routeName: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val lParts = latest.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
            val cParts = current.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
            val len = maxOf(lParts.size, cParts.size)
            for (i in 0 until len) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun checkAndResumePendingInstall(activity: Activity, onSpeak: (String) -> Unit = {}): Boolean {
        val pending = pendingApkFile ?: return false
        if (!pending.exists() || pending.length() < 100_000L) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.packageManager.canRequestPackageInstalls()) {
            pendingApkFile = null
            installApkSession(activity, pending, onSpeak)
            return true
        }
        return false
    }

    fun checkOnStartup(activity: Activity, manualTrigger: Boolean = false, onSpeak: (String) -> Unit = {}) {
        val mainHandler = Handler(Looper.getMainLooper())
        thread(name = "PhoneDualRouteUpdateCheck") {
            try {
                val fPages = CompletableFuture.supplyAsync { probeEndpoint("GitLab Pages", GITLAB_PAGES_MANIFEST_URL) }
                val fRepo = CompletableFuture.supplyAsync { probeEndpoint("GitLab Repository", GITLAB_REPO_MANIFEST_URL) }
                val pagesProbe = try { fPages.get() } catch (_: Exception) { null }
                val repoProbe = try { fRepo.get() } catch (_: Exception) { null }

                val winner = when {
                    pagesProbe != null && repoProbe != null ->
                        if (pagesProbe.latencyMs <= repoProbe.latencyMs) pagesProbe else repoProbe
                    pagesProbe != null -> pagesProbe
                    repoProbe != null -> repoProbe
                    else -> null
                }

                if (winner == null) {
                    if (manualTrigger) {
                        mainHandler.post {
                            Toast.makeText(activity, "更新サーバーに接続できませんでした。", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@thread
                }

                val root = JSONObject(winner.body)
                val appEntry = root.optJSONObject("apps")?.optJSONObject(BuildConfig.APPLICATION_ID)
                val latestVer = appEntry?.optString("version", "")?.removePrefix("v")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: BuildConfig.VERSION_NAME
                val currentVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
                val notes = appEntry?.optString("notes", "") ?: ""
                val repoUrl = appEntry?.optString("gitlab_repo_url", "") ?: ""
                val pagesUrl = appEntry?.optString("gitlab_pages_url", "") ?: ""
                val chosenUrl = (if (winner.routeName == "GitLab Pages" && pagesUrl.isNotEmpty()) pagesUrl else repoUrl)
                    .ifEmpty { "https://gitlab.com/api/v4/projects/kapamilya%2Fserena/packages/generic/kapamilya-serena/5.14.0/$DEFAULT_APK_NAME" }

                val hasUpdate = isNewerVersion(latestVer, currentVer)
                mainHandler.post {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (hasUpdate) {
                        val msg = "新しいバージョン v$latestVer が見つかりました（高速回線自動選択: ${winner.routeName} ${winner.latencyMs}ms）。今すぐ自動更新しますか？"
                        onSpeak(msg)
                        AlertDialog.Builder(activity)
                            .setTitle("🚀 Kapamilya Serena Phone 自動更新 (v$latestVer)")
                            .setMessage("現在: v$currentVer → 最新: v$latestVer\n自動選択された高速回線: ${winner.routeName} (${winner.latencyMs}ms)\n\n$notes")
                            .setPositiveButton("今すぐ高速更新") { _, _ ->
                                downloadAndInstallApk(activity, chosenUrl, winner.routeName, onSpeak)
                            }
                            .setNeutralButton("公式サイトを開く") { _, _ ->
                                try {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kapamilya.gitlab.io")))
                                } catch (_: Exception) {}
                            }
                            .setNegativeButton("後で", null)
                            .show()
                    } else if (manualTrigger) {
                        val upToDateMsg = "お使いの Kapamilya Serena Phone (v$currentVer) は最新です（高速回線: ${winner.routeName} ${winner.latencyMs}ms）。"
                        onSpeak(upToDateMsg)
                        Toast.makeText(activity, upToDateMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check error: ${e.message}")
            }
        }
    }

    private fun downloadAndInstallApk(activity: Activity, downloadUrl: String, routeName: String, onSpeak: (String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        onSpeak("$routeName から最新版のダウンロードを開始しました。")
        Toast.makeText(activity, "⬇️ $routeName から最新版をダウンロード中...", Toast.LENGTH_SHORT).show()

        thread(name = "PhoneApkDownloader") {
            var conn: HttpURLConnection? = null
            try {
                val outDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir, "updates")
                if (!outDir.exists()) outDir.mkdirs()
                val tmpFile = File(outDir, "$DEFAULT_APK_NAME.tmp")
                val apkFile = File(outDir, DEFAULT_APK_NAME)
                if (tmpFile.exists()) tmpFile.delete()

                var currentUrl = downloadUrl
                var redirects = 0
                while (redirects < 6) {
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "Kapamilya-Serena-Phone/${BuildConfig.VERSION_NAME}")
                    }
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location") ?: break
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        conn.disconnect()
                        redirects++
                    } else {
                        break
                    }
                }

                val finalConn = conn ?: throw IllegalStateException("Connection is null")
                if (finalConn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${finalConn.responseCode}")
                }
                finalConn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
                if (tmpFile.length() < 100_000L) {
                    tmpFile.delete()
                    throw IllegalStateException("Downloaded APK is too small")
                }
                if (apkFile.exists()) apkFile.delete()
                tmpFile.renameTo(apkFile)

                mainHandler.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        installApkSession(activity, apkFile, onSpeak)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed, falling back to browser: ${e.message}")
                mainHandler.post {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                    } catch (_: Exception) {}
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun installApkSession(activity: Activity, apkFile: File, onSpeak: (String) -> Unit) {
        var session: PackageInstaller.Session? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
                pendingApkFile = apkFile
                onSpeak("アプリの自動更新を完了するため、不明なアプリのインストール権限を許可してください。")
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                activity.startActivity(permIntent)
                return
            }
            val appContext = activity.applicationContext
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            apkFile.inputStream().use { input ->
                session.openWrite("package", 0, apkFile.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(appContext, PhoneUpdateInstallReceiver::class.java).apply {
                action = PhoneUpdateInstallReceiver.ACTION_INSTALL_STATUS
                setPackage(appContext.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(appContext, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
            onSpeak("ダウンロードが完了しました。更新ボタンを押してインストールを完了してください。")
        } catch (e: Exception) {
            try { session?.abandon() } catch (_: Exception) {}
            Log.e(TAG, "Session install error: ${e.message}")
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }
}
