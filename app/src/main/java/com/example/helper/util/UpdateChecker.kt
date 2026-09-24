package com.example.helper.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val REPO = "mdkdw1-ui/royal2"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_VERSION = "last_version"

    fun getCurrentVersion(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun checkAndUpdate(context: Context, onStatus: (String) -> Unit) {
        val currentVersion = getCurrentVersion(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode != 200) {
                    withContext(Dispatchers.Main) { onStatus("❌ 서버 응답 ${conn.responseCode}") }
                    return@launch
                }

                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tagName = json.getString("tag_name")
                val releaseName = json.optString("name", tagName)
                val assets = json.getJSONArray("assets")

                var apkUrl: String? = null
                var apkSize = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        apkSize = a.getLong("size")
                        break
                    }
                }

                if (apkUrl == null) {
                    withContext(Dispatchers.Main) { onStatus("❌ APK 없음") }
                    return@launch
                }

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastVer = prefs.getString(KEY_LAST_VERSION, "")

                if (lastVer == tagName) {
                    withContext(Dispatchers.Main) {
                        onStatus("✅ 최신 버전 (현재: $currentVersion, 원격: $releaseName)")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    onStatus("⬇️ 다운로드 중 (${currentVersion} → ${releaseName}, ${apkSize / 1024 / 1024}MB)")
                }

                downloadAndInstall(context, apkUrl, tagName, onStatus, prefs)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatus("❌ 오류: ${e.message}") }
            }
        }
    }

    private fun downloadAndInstall(
        context: Context,
        url: String,
        version: String,
        onStatus: (String) -> Unit,
        prefs: android.content.SharedPreferences
    ) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("OOXOO 헬퍼 업데이트")
                setDescription("새 버전 $version 다운로드")
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/update.apk")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }

            val downloadId = dm.enqueue(request)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id != downloadId) return
                    try { context.unregisterReceiver(this) } catch (e: Exception) {}

                    prefs.edit().putString(KEY_LAST_VERSION, version).apply()
                    onStatus("✅ 다운로드 완료, 설치 화면 여는 중...")

                    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
                    val apkFile = File(dir, "update.apk")
                    installApk(context, apkFile)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            onStatus("❌ 다운로드 실패: ${e.message}")
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
