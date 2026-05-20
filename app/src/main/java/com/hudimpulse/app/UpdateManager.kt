package com.hudimpulse.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "UpdateManager"

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val apkUrl: String,
)

object UpdateManager {

    private val API_URL =
        "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var checking: Boolean = false
    @Volatile private var downloading: Boolean = false

    fun checkForUpdate(context: Context) {
        if (checking || downloading) return
        val appContext = context.applicationContext
        executor.submit {
            checking = true
            try {
                val info = fetchLatestRelease() ?: return@submit
                if (isNewer(info.version, BuildConfig.VERSION_NAME)) {
                    Log.i(TAG, "Update available: ${info.version} (current: ${BuildConfig.VERSION_NAME})")
                    downloadAndInstall(appContext, info)
                } else {
                    Log.d(TAG, "Already on latest version (${BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            } finally {
                checking = false
            }
        }
    }

    private fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        if (downloading) return
        downloading = true
        try {
            val cacheDir = File(context.cacheDir, "apk").also { it.mkdirs() }
            val apkFile = File(cacheDir, "hud-impulse-update.apk")

            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes — trying silent install")

            if (tryShizukuInstall(apkFile.absolutePath)) {
                Log.i(TAG, "Silent install OK — restarting process")
                Thread.sleep(300)
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }

            Log.i(TAG, "Silent install indisponível — abrindo instalador do sistema")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}")
        } finally {
            downloading = false
        }
    }

    private fun tryShizukuInstall(apkPath: String): Boolean {
        return try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku binder not alive — skipping silent install")
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted — skipping silent install")
                return false
            }
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).also { it.isAccessible = true }

            val process = newProcessMethod.invoke(
                null,
                arrayOf("pm", "install", "-r", "-t", apkPath),
                null as Array<String>?,
                null as String?,
            ) as Process

            val output = process.inputStream.bufferedReader().readText()
            val errOut = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            Log.i(TAG, "pm install exit=$exit stdout=${output.trim()} stderr=${errOut.trim()}")
            exit == 0 && output.trim().startsWith("Success")
        } catch (e: Exception) {
            Log.w(TAG, "tryShizukuInstall exception: ${e.message}")
            false
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "HudImpulse/${BuildConfig.VERSION_NAME}")
        if (conn.responseCode != 200) {
            Log.w(TAG, "GitHub API returned ${conn.responseCode}")
            return null
        }
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        val version = tag.trimStart('v')

        val assets = json.optJSONArray("assets") ?: return null
        val apkUrl = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
            ?: return null

        return ReleaseInfo(tag, version, apkUrl)
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        val len = maxOf(c.size, b.size)
        for (i in 0 until len) {
            val cv = c.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (cv > bv) return true
            if (cv < bv) return false
        }
        return false
    }
}
