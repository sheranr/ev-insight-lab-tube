/*
 * EV Insight Lab -Tube — modifications to AABrowser (GPL-3.0-or-later).
 * Added 2026-11-03: over-the-air self-update without the Play Store.
 *
 * Reads ota/version.json from GitHub Pages, downloads the newer APK, checks its SHA-256,
 * and hands it to Android's package installer. Android itself also refuses any APK that
 * is not signed with the same key as the installed app.
 */
package com.kododake.aabrowser.ev

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kododake.aabrowser.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateManager(private val activity: AppCompatActivity) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val minSupportedCode: Int
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val prefs = activity.getSharedPreferences("ev_update", AppCompatActivity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var pendingInstall: File? = null

    private val alive: Boolean get() = !activity.isFinishing && !activity.isDestroyed

    fun checkOnLaunch() {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < EvConfig.AUTO_CHECK_INTERVAL_MS) return
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast("Checking for updates…")
        Thread {
            val info = runCatching { fetchInfo() }.getOrNull()
            main.post {
                busy = false
                if (!alive) return@post
                if (info == null) {
                    if (manual) toast("Couldn't check for updates. Check the internet connection.")
                    return@post
                }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val installed = BuildConfig.VERSION_CODE
                when {
                    info.versionCode <= installed ->
                        if (manual) toast("You have the latest version (v${BuildConfig.VERSION_NAME}).")
                    !manual && installed >= info.minSupportedCode &&
                        prefs.getInt(KEY_SKIPPED, 0) == info.versionCode -> Unit
                    else -> showUpdateDialog(info, mandatory = installed < info.minSupportedCode)
                }
            }
        }.start()
    }

    /** Call from Activity.onResume: finishes an install that waited for the "unknown apps" permission. */
    fun onResume() {
        val file = pendingInstall ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            install(file)
        }
    }

    private fun fetchInfo(): UpdateInfo {
        val request = Request.Builder()
            .url(EvConfig.OTA_URL + "?t=" + System.currentTimeMillis())
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val json = JSONObject(response.body.string())
            return UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.optString("sha256").lowercase(),
                size = json.optLong("size", 0L),
                notes = json.optString("notes"),
                minSupportedCode = json.optInt("minSupportedCode", 0)
            )
        }
    }

    private fun showUpdateDialog(info: UpdateInfo, mandatory: Boolean) {
        val sizeText = if (info.size > 0) " (%.1f MB)".format(info.size / 1_048_576.0) else ""
        val message = buildString {
            append("Version ${info.versionName}$sizeText is ready. You have v${BuildConfig.VERSION_NAME}.")
            if (info.notes.isNotBlank()) append("\n\nWhat's new:\n${info.notes}")
            append("\n\nYour bookmarks and settings are kept.")
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(message)
            .setCancelable(!mandatory)
            .setPositiveButton("Update now") { _, _ -> download(info) }
        if (!mandatory) {
            builder.setNegativeButton("Later", null)
            builder.setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putInt(KEY_SKIPPED, info.versionCode).apply()
            }
        }
        builder.show()
    }

    private fun download(info: UpdateInfo) {
        if (info.sha256.length != 64) {
            toast("This update is missing its checksum, so it was not installed.")
            return
        }
        cancelled = false
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = info.size <= 0
            max = 100
        }
        val label = TextView(activity).apply {
            text = "Starting download…"
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(progress)
            addView(label)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading v${info.versionName}")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .show()

        Thread {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "EV-Insight-Lab-Tube-${info.versionCode}.apk")
            val result = runCatching {
                val request = Request.Builder().url(info.apkUrl).build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    val digest = MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    var lastShown = -1
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (cancelled) error("cancelled")
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                                if (pct != lastShown) {
                                    lastShown = pct
                                    main.post {
                                        if (pct >= 0) {
                                            progress.isIndeterminate = false
                                            progress.progress = pct
                                        }
                                        label.text = "%.1f MB".format(done / 1_048_576.0) +
                                            if (total > 0) " of %.1f MB".format(total / 1_048_576.0) else ""
                                    }
                                }
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual == info.sha256) { "checksum" }
                }
                target
            }
            main.post {
                runCatching { dialog.dismiss() }
                if (!alive) return@post
                result.onSuccess { install(it) }.onFailure { error ->
                    target.delete()
                    when (error.message) {
                        "cancelled" -> Unit
                        "checksum" -> toast("The download was damaged, so it was not installed. Please try again.")
                        else -> toast("Download failed. Check the internet connection and try again.")
                    }
                }
            }
        }.start()
    }

    private fun install(file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            AlertDialog.Builder(activity)
                .setTitle("One-time permission")
                .setMessage(
                    "To install its own updates, Android needs you to allow this app to install apps.\n\n" +
                        "Turn on \"Allow from this source\", then come back. The update continues automatically."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.packageName)
                            )
                        )
                    }
                }
                .setNegativeButton("Not now") { _, _ -> pendingInstall = null }
                .show()
            return
        }
        runCatching {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(activity.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(activity, UpdateInstallReceiver::class.java)
                    .setPackage(activity.packageName)
                val pending = PendingIntent.getBroadcast(
                    activity,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
            toast("Installing update…")
        }.onFailure {
            toast("Couldn't start the installer. Download the update from the website instead.")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_SKIPPED = "skipped_code"
    }
}
