package com.eddyizm.tempus.homemusic

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.navigation.fragment.NavHostFragment
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.base.ApiResponse
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.util.FavoriteRegistry
import com.eddyizm.tempus.subsonic.models.Child
import java.util.function.Consumer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** HomeMusic additions to the Library screen: «Понравившиеся» (row and page cover) and hiding «Папки». */
object HomeLibrary {
    private val likedChanges = MutableLiveData(0L)

    /** Downloads can finish while the liked page is already open behind the player. */
    internal fun libraryChanged() {
        HomeMusic.main.post { likedChanges.value = (likedChanges.value ?: 0) + 1 }
    }

    @JvmStatic
    fun observeLikedChanges(fragment: Fragment, refresh: Runnable) {
        var previous = likedChanges.value
        likedChanges.observe(fragment.viewLifecycleOwner) { revision ->
            if (revision != previous) { previous = revision; refresh.run() }
        }
    }

    /** Refresh an existing list without first replacing it with a temporary empty list. */
    @JvmStatic
    fun refreshLikedSongs(result: Consumer<List<Child>>) {
        val generation = FavoriteRegistry.generation()
        if (HubConfig.isEnabled()) {
            val account = CatalogFavorites.currentAccount()
            HomeMusic.io.execute {
                runCatching {
                    loadLikedSongs(generation, FavoriteRegistry::generation, {
                        check(com.eddyizm.tempus.subsonic.utils.CacheUtil.isConnected())
                        checkNotNull(account)
                        HubClient.withAccountSession(account) {
                            HubClient.likedSongs().children(HubClient.baseUrl())
                        }
                    }, {
                        val response = App.getSubsonicClientInstance(false).getAlbumSongListClient().getStarred2().execute()
                        check(response.isSuccessful)
                        checkNotNull(response.body()?.subsonicResponse?.starred2).songs.orEmpty()
                    }) { songs ->
                        HomeMusic.main.post {
                            if (generation == FavoriteRegistry.generation() && account == CatalogFavorites.currentAccount()) {
                                result.accept(HomeOffline.songs(songs))
                            }
                        }
                    }
                }
            }
            return
        }
        App.getSubsonicClientInstance(false).getAlbumSongListClient().getStarred2().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (generation != FavoriteRegistry.generation() || !response.isSuccessful) return
                val starred = response.body()?.subsonicResponse?.starred2 ?: return
                result.accept(HomeOffline.songs(starred.songs.orEmpty()))
            }
            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {}
        })
    }

    /** The row opens the starred tracks, the list the heart button fills. */
    @JvmStatic
    fun bindLikedSongs(fragment: Fragment, row: View) {
        row.setOnClickListener {
            val bundle = Bundle().apply { putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED) }
            NavHostFragment.findNavController(fragment).navigate(R.id.songListPageFragment, bundle)
        }
    }

    /**
     * The starred list opens with a big «Понравившиеся» cover, like Spotify's Liked Songs: the
     * Library row's cover scaled up. Tempus's header is a fixed band with the title at its bottom,
     * so it grows to fit the cover instead of leaving that band empty.
     */
    @JvmStatic
    fun showLikedCover(header: ConstraintLayout, title: View) {
        val context = header.context
        val res = context.resources
        val density = res.displayMetrics.density
        // As wide as an album page's cover (64dp margins), but short enough to leave room
        // for the list in landscape.
        val sizeDp = minOf(res.configuration.screenWidthDp - 128, res.configuration.screenHeightDp * 2 / 5)
        val size = (sizeDp * density).toInt()
        // The heart takes the same share of the cover as in the 52dp Library row (14dp padding).
        val padding = size * 14 / 52
        val background = ContextCompat.getDrawable(context, R.drawable.bg_homemusic_liked)!!.mutate()
        (background as? GradientDrawable)?.cornerRadius = 12 * density

        val cover = ImageView(context).apply {
            id = View.generateViewId()
            this.background = background
            setImageResource(R.drawable.ic_favorite)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            elevation = 6 * density
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(cover, ConstraintLayout.LayoutParams(size, size))
        header.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT

        ConstraintSet().apply {
            clone(header)
            connect(cover.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, (8 * density).toInt())
            centerHorizontally(cover.id, ConstraintSet.PARENT_ID)
            connect(title.id, ConstraintSet.TOP, cover.id, ConstraintSet.BOTTOM)
            applyTo(header)
        }
    }

    /** Asked on every resume, so a track liked elsewhere is counted on return. */
    @JvmStatic
    fun refreshLikedCount(countView: TextView) {
        if (HubConfig.isEnabled()) {
            refreshLikedSongs { songs ->
                val count = songs.size
                countView.text = if (count == 0) countView.resources.getString(R.string.homemusic_liked_songs_hint)
                else countView.resources.getQuantityString(R.plurals.homemusic_tracks_count, count, count)
            }
            return
        }
        App.getSubsonicClientInstance(false).getAlbumSongListClient().getStarred2().enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (!response.isSuccessful) return
                val count = response.body()?.subsonicResponse?.starred2?.songs?.size ?: 0
                val res = countView.resources
                countView.text = if (count == 0) res.getString(R.string.homemusic_liked_songs_hint)
                else res.getQuantityString(R.plurals.homemusic_tracks_count, count, count)
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {}
        })
    }

    /**
     * Navidrome's library («Music Library») cannot be deleted from a player, so «remove» hides the
     * whole «Папки» section, the same as the «Показывать папки» setting, with an undo.
     */
    @JvmStatic
    fun confirmHideFolders(fragment: Fragment, folderName: String?, onHidden: Runnable, onRestored: Runnable) {
        val context = fragment.requireContext()
        val name = folderName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.library_title_music_folder)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.homemusic_folders_hide_title)
            .setMessage(context.getString(R.string.homemusic_folders_hide_message, "«$name»"))
            .setPositiveButton(R.string.homemusic_folders_hide) { _, _ ->
                Preferences.setMusicDirectorySectionVisible(false)
                onHidden.run()
                val view = fragment.view ?: return@setPositiveButton
                val activity = fragment.activity as? MainActivity
                Snackbar.make(view, R.string.homemusic_folders_hidden, Snackbar.LENGTH_LONG)
                    .setAnchorView(activity?.snackbarAnchor)
                    .setAction(R.string.homemusic_undo) {
                        Preferences.setMusicDirectorySectionVisible(true)
                        onRestored.run()
                    }
                    .show()
            }
            .setNegativeButton(R.string.homemusic_cancel, null)
            .show()
    }
}
