package com.eddyizm.tempus.homemusic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.lang.ref.WeakReference
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * Stage 4.6: the Hub publishes the newest APK (`GET /app/latest.json` + `/app/latest.apk`); the
 * app compares versionCode and offers to install it through the system package installer.
 */
object HubUpdater {
    private const val TAG = "HubUpdater"
    private const val PREFS = "homemusic_updater"
    private const val KEY_SKIPPED = "skipped_version"
    private val downloading = AtomicBoolean(false)
    private val updateHttp: OkHttpClient by lazy {
        HubClient.http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
    }

    // The activity an automatic check runs for, until its dialog closes: coming back to the app
    // meanwhile must not stack a second dialog. Weak, so a rotation (new activity) checks again.
    // Main thread only.
    private var autoCheckFor: WeakReference<Activity>? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Automatic check, every time the app comes to the foreground. */
    @JvmStatic
    fun checkOnStart(activity: Activity) {
        val running = autoCheckFor?.get()
        if (running != null && !running.isFinishing && !running.isDestroyed) return
        autoCheckFor = WeakReference(activity)
        check(activity, manual = false)
    }

    /** [manual]: from settings — always answers, also when there is nothing new. */
    @JvmStatic
    fun check(activity: Activity, manual: Boolean) {
        if (manual) Toast.makeText(activity, R.string.homemusic_update_checking, Toast.LENGTH_SHORT).show()
        HomeMusic.io.execute {
            val result = runCatching {
                val release = HubClient.latestRelease()
                val source = File(activity.applicationInfo.sourceDir)
                val patch = if (release != null && release.versionCode > BuildConfig.VERSION_CODE &&
                    release.patches.orEmpty().any { it.baseVersionCode == BuildConfig.VERSION_CODE.toLong() && it.format == UpdateDelta.FORMAT } &&
                    source.length() in 1..UpdateDelta.MAX_DELTA_APK) {
                    runCatching { UpdateDelta.select(release, BuildConfig.VERSION_CODE.toLong(), UpdateDelta.sha256(source)) }.getOrNull()
                } else null
                Pair(release, patch)
            }
            HomeMusic.main.post {
                if (activity.isFinishing || activity.isDestroyed) {
                    if (!manual) autoCheckFor = null
                    return@post
                }
                var offered = false
                result.onFailure { e ->
                    Log.d(TAG, "Update check failed: ${e.message}")
                    if (manual) Toast.makeText(activity, activity.getString(R.string.homemusic_update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }.onSuccess { (release, patch) ->
                    val newer = release != null && release.versionCode > BuildConfig.VERSION_CODE
                    if (!newer) {
                        if (manual) Toast.makeText(activity, activity.getString(R.string.homemusic_update_latest, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    if (!manual && prefs(activity).getLong(KEY_SKIPPED, -1) == release!!.versionCode) return@onSuccess
                    offer(activity, release!!, patch, manual)
                    offered = true
                }
                if (!manual && !offered) autoCheckFor = null
            }
        }
    }

    private fun offer(activity: Activity, release: HubAppRelease, patch: HubUpdatePatch?, manual: Boolean) {
        val notes = release.notes?.takeIf { it.isNotBlank() }
        // On a metered network the size is shown and waiting for Wi-Fi is the first choice
        val metered = HomeTraffic.isMetered()
        val downloadSize = patch?.size ?: release.full?.size ?: release.size
        val size = if (downloadSize > 0) updateSize(activity, downloadSize) else null
        val message = activity.getString(R.string.homemusic_update_message, BuildConfig.VERSION_NAME, release.versionName ?: release.versionCode.toString()) +
            (notes?.let { "\n\n$it" } ?: "") +
            (if (metered) "\n\n" + activity.getString(R.string.homemusic_update_metered, size ?: "?") else "")
        val install = if (metered && size != null) activity.getString(R.string.homemusic_update_install_metered, size)
        else activity.getString(R.string.homemusic_update_install)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.homemusic_update_title)
            .setMessage(message)
            .setPositiveButton(install) { _, _ -> download(activity, release, patch) }
            .setNegativeButton(if (metered) R.string.homemusic_update_wait_wifi else R.string.homemusic_update_later, null)
        if (!manual) {
            builder.setNeutralButton(R.string.homemusic_update_skip) { _, _ ->
                prefs(activity).edit().putLong(KEY_SKIPPED, release.versionCode).apply()
            }
        }
        builder.setOnDismissListener { if (!manual) autoCheckFor = null }
        builder.show()
    }

    private fun download(activity: Activity, release: HubAppRelease, patch: HubUpdatePatch?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.homemusic_update_allow_install, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No unknown-sources settings screen", e)
            }
            return
        }

        if (!downloading.compareAndSet(false, true)) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_hub_update_progress, null)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.hub_update_progress)
        val label = view.findViewById<TextView>(R.id.hub_update_progress_text)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.homemusic_update_downloading)
            .setView(view)
            .setCancelable(false)
            .show()

        HomeMusic.io.execute {
            val result = runCatching {
                val report: (Long, Long) -> Unit = { done, total ->
                    HomeMusic.main.post {
                        if (done < 0) {
                            progress.isIndeterminate = true
                            label.setText(R.string.homemusic_update_preparing)
                        } else if (total > 0) {
                            progress.isIndeterminate = false
                            progress.setProgressCompat((done * 100 / total).toInt(), true)
                            label.text = "${updateSize(activity, done)} из ${updateSize(activity, total)}"
                        } else {
                            label.text = updateSize(activity, done)
                        }
                    }
                }
                try {
                    fetchApk(activity.applicationContext, release, patch, report)
                } catch (e: Exception) {
                    // Unmetered: the full APK is fetched at once; metered: the failure goes to the user (below)
                    if (UpdateDelta.fallback(patch != null, HomeTraffic.isMetered()) != UpdateDelta.Fallback.FULL_NOW) throw e
                    Log.w(TAG, "Patch failed, downloading the full APK: ${e.message}")
                    fetchApk(activity.applicationContext, release, null, report)
                }
            }
            downloading.set(false)
            HomeMusic.main.post {
                if (dialog.isShowing) dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { install(activity, it) }
                    .onFailure { e ->
                        if (UpdateDelta.fallback(patch != null, HomeTraffic.isMetered()) == UpdateDelta.Fallback.ASK_FULL) {
                            val size = release.full?.size ?: release.size
                            MaterialAlertDialogBuilder(activity)
                                .setMessage(activity.getString(R.string.homemusic_update_full_fallback, updateSize(activity, size)))
                                .setPositiveButton(R.string.homemusic_update_full_download) { _, _ -> download(activity, release, null) }
                                .setNegativeButton(R.string.homemusic_update_later, null).show()
                        } else {
                            Toast.makeText(activity, activity.getString(R.string.homemusic_update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
            }
        }
    }

    private fun updateSize(context: Context, bytes: Long): String =
        if (bytes < 1024 * 1024) context.getString(R.string.homemusic_update_size_kib, bytes / 1024.0)
        else context.getString(R.string.homemusic_update_size_mib, bytes / (1024.0 * 1024))

    private fun fetchApk(context: Context, release: HubAppRelease, patch: HubUpdatePatch?, onProgress: (Long, Long) -> Unit): File {
        val full = release.full?.takeIf { UpdateDelta.validFile(it, "apk") }
        val entry = patch ?: full
        val path = entry?.url ?: "/app/latest.apk"
        if (entry == null && release.url != null && release.url != "/app/latest.apk") throw HubException("Неверная ссылка")
        val limit = if (patch != null) UpdateDelta.MAX_PATCH else UpdateDelta.MAX_APK
        val expectedSize = entry?.size ?: release.size
        val expectedHash = entry?.sha256 ?: release.sha256
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val inputFile = File.createTempFile("download-", ".tmp", dir)
        val outputFile = File.createTempFile("update-", ".apk", dir)
        try {
            val url = (HubClient.baseUrl() + "/").toHttpUrl().resolve(path) ?: throw HubException("Неверная ссылка")
            updateHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw HubClient.error(response)
                val body = response.body ?: throw HubException("Пустой ответ Хаба")
                if (body.contentLength() > limit) throw HubException("Слишком большой файл")
                var done = 0L
                var lastReport = 0L
                body.byteStream().use { input -> inputFile.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        done += count
                        if (done > limit || (expectedSize > 0 && done > expectedSize)) throw HubException("Слишком большой файл")
                        out.write(buffer, 0, count)
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            onProgress(done, expectedSize)
                        }
                    }
                } }
                onProgress(done, expectedSize)
                if (expectedSize > 0 && done != expectedSize) throw HubException("Неверный размер обновления")
            }
            if (!expectedHash.isNullOrBlank() && !UpdateDelta.sha256(inputFile).equals(expectedHash, true)) throw HubException("Контрольная сумма не совпала")
            if (patch != null) {
                onProgress(-1, 0)
                UpdateDelta.reconstruct(File(context.applicationInfo.sourceDir), inputFile, outputFile, patch, full ?: throw HubException("Нет описания полного APK"))
            } else {
                if (!outputFile.delete() || !inputFile.renameTo(outputFile)) throw HubException("Не удалось сохранить APK")
            }
            val targetSize = full?.size ?: release.size
            val targetHash = full?.sha256 ?: release.sha256
            if (outputFile.length() > UpdateDelta.MAX_APK || (targetSize > 0 && outputFile.length() != targetSize) ||
                (!targetHash.isNullOrBlank() && !UpdateDelta.sha256(outputFile).equals(targetHash, true))) throw HubException("Повреждённый APK")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
            val info = context.packageManager.getPackageArchiveInfo(outputFile.path, flags) ?: throw HubException("Скачанный файл — не APK")
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            if (info.packageName != context.packageName || code != release.versionCode ||
                (release.versionName != null && info.versionName != release.versionName)) throw HubException("Неверная версия APK")
            @Suppress("DEPRECATION")
            val current = context.packageManager.getPackageInfo(context.packageName, flags)
            @Suppress("DEPRECATION")
            val actualSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
            @Suppress("DEPRECATION")
            val currentSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) current.signingInfo?.apkContentsSigners else current.signatures
            if (actualSignatures.isNullOrEmpty() || currentSignatures.isNullOrEmpty() || actualSignatures.toSet() != currentSignatures.toSet()) throw HubException("Подпись APK не совпадает")
            return outputFile
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        } finally {
            inputFile.delete()
        }
    }

    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.homemusic.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.homemusic_update_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
