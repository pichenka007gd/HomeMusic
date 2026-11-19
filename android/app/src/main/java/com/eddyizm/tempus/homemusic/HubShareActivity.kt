package com.eddyizm.tempus.homemusic

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.ActivityHubShareBinding
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.DownloadRepair
import java.io.IOException

/**
 * Android «Поделиться» → HomeMusic (stage 4.4). The app uploads the shared files itself, through
 * the Hub API, and then opens the matching Hub screen:
 * screenshots → a new assistant task, audio files → the upload form, text / links → assistant.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class HubShareActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "HubShareActivity"
        private const val MAX_IMAGES_PER_TASK = 10
        private val URL_RE = Regex("https?://\\S+")
    }

    private lateinit var bind: ActivityHubShareBinding
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityHubShareBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.hubShareClose.setOnClickListener { finish() }

        if (!DownloadRepair.isUserAuthenticated()) {
            Toast.makeText(this, R.string.homemusic_share_login_first, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (savedInstanceState == null && !started) {
            started = true
            process(intent)
        }
    }

    private fun process(intent: Intent) {
        val uris = sharedUris(intent)
        val images = uris.filter { typeOf(it, intent).startsWith("image/") }
        val audio = uris.filter { typeOf(it, intent).let { t -> t.startsWith("audio/") || t == "application/ogg" } }
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

        if (images.isEmpty() && audio.isEmpty() && text.isEmpty()) {
            fail(getString(R.string.homemusic_share_unsupported))
            return
        }
        status(getString(R.string.homemusic_share_connecting))

        HomeMusic.io.execute {
            try {
                HubClient.ensureSession()
                var route: String? = null
                var toast: String? = null
                val errors = mutableListOf<String>()

                if (images.isNotEmpty()) {
                    var lastTask: HubAssistantTask? = null
                    images.chunked(MAX_IMAGES_PER_TASK).forEach { chunk ->
                        status(resources.getQuantityString(R.plurals.homemusic_share_sending_images, chunk.size, chunk.size))
                        lastTask = HubClient.assistantFromImages(contentResolver, chunk)
                    }
                    route = "/assistant/${lastTask!!.id}"
                    toast = getString(R.string.homemusic_share_images_sent)
                }

                if (audio.isNotEmpty()) {
                    var uploaded = 0
                    audio.forEachIndexed { i, uri ->
                        status(getString(R.string.homemusic_share_uploading, i + 1, audio.size))
                        try {
                            HubClient.uploadAudio(contentResolver, uri)
                            uploaded++
                        } catch (e: IOException) {
                            Log.w(TAG, "Upload failed for $uri", e)
                            errors.add(e.message ?: e.javaClass.simpleName)
                        }
                    }
                    if (uploaded > 0) {
                        // The upload form lists everything in staging, so it shows the new files.
                        route = "/upload"
                        toast = if (images.isNotEmpty()) getString(R.string.homemusic_share_images_sent)
                        else getString(R.string.homemusic_share_audio_sent, uploaded)
                    } else if (route == null) {
                        throw HubException(errors.firstOrNull() ?: getString(R.string.homemusic_share_failed))
                    }
                }

                if (images.isEmpty() && audio.isEmpty()) {
                    status(getString(R.string.homemusic_share_sending_link))
                    val m = URL_RE.find(text)
                    val url = m?.value
                    val task = if (url != null && text.length - url.length <= 1) {
                        HubClient.assistantFromText(text, url, urlOnly = true)
                    } else {
                        HubClient.assistantFromText(text, url)
                    }
                    route = "/assistant/${task.id}"
                }

                val message = (listOfNotNull(toast) + errors.map { getString(R.string.homemusic_share_partial_error, it) }).joinToString("\n")
                HomeMusic.main.post { openHub(route!!, message.ifBlank { null }) }
            } catch (e: Exception) {
                Log.w(TAG, "Share failed", e)
                HomeMusic.main.post { fail(e.message ?: getString(R.string.homemusic_share_failed)) }
            }
        }
    }

    private fun openHub(route: String, message: String?) {
        if (isFinishing || isDestroyed) return
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(HomeMusic.EXTRA_HUB_ROUTE, route)
        )
        finish()
    }

    private fun status(text: String) {
        HomeMusic.main.post {
            if (isFinishing || isDestroyed) return@post
            bind.hubShareText.text = text
        }
    }

    private fun fail(message: String) {
        bind.hubShareProgress.visibility = View.GONE
        bind.hubShareText.text = message
        bind.hubShareClose.visibility = View.VISIBLE
    }

    private fun typeOf(uri: Uri, intent: Intent): String =
        (contentResolver.getType(uri) ?: intent.type ?: "").lowercase()

    private fun sharedUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> parcelable<Uri>(intent, Intent.EXTRA_STREAM)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> parcelableList<Uri>(intent, Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        if (out.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) } }
        return out.distinct()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> parcelable(intent: Intent, key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(key, T::class.java) else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> parcelableList(intent: Intent, key: String): List<T>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(key, T::class.java) else intent.getParcelableArrayListExtra(key)
}
