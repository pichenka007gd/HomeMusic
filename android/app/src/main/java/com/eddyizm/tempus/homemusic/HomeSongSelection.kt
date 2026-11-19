package com.eddyizm.tempus.homemusic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.R
import com.eddyizm.tempus.interfaces.StarCallback
import com.eddyizm.tempus.model.Download
import com.eddyizm.tempus.repository.FavoriteRepository
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.adapter.SongHorizontalAdapter
import com.eddyizm.tempus.ui.dialog.PlaylistChooserDialog
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.ExternalAudioReader
import com.eddyizm.tempus.util.ExternalAudioWriter
import com.eddyizm.tempus.util.FavoriteRegistry
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.NetworkUtil
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import java.util.Date
import java.util.function.Supplier
import kotlin.math.roundToInt

/**
 * Multi-select in a song list (SongListPageFragment: «Понравившиеся» and the other song pages). A long
 * press on a row picks it instead of opening the track menu, which stays on the row's ⋮ until the
 * first pick; while anything is picked a tap or a long press adds or drops a row, the ⋮ turns into
 * a check, and a bar in place of the page's toolbar acts on all the picked songs at once.
 */
@UnstableApi
object HomeSongSelection {
    /** Draws [song]'s row: a tint when picked and a check (empty or filled) in place of the ⋮ while picking. */
    @JvmStatic
    fun bindRow(row: View, more: ViewGroup, selection: SongSelection?, song: Child) {
        val icon = more.getChildAt(0) as? ImageView ?: return
        val picking = selection?.isActive == true
        val picked = picking && selection!!.isSelected(song)
        row.foreground = if (picked) {
            val accent = MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary, Color.GRAY)
            ColorDrawable(ColorUtils.setAlphaComponent(accent, 0x2E))
        } else null
        val size = ((if (picking) 22 else 18) * row.resources.displayMetrics.density).roundToInt()
        icon.layoutParams = icon.layoutParams.apply { width = size; height = size }
        when {
            picked -> {
                icon.setBackgroundResource(R.drawable.ic_homemusic_check_circle)
                icon.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary, Color.GRAY))
            }
            picking -> {
                icon.setBackgroundResource(R.drawable.ic_homemusic_circle_outline)
                icon.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            }
            else -> {
                icon.setBackgroundResource(R.drawable.ic_more_vert)
                icon.backgroundTintList = null
            }
        }
        more.contentDescription = row.context.getString(
            if (picking) R.string.homemusic_selection_toggle else R.string.homemusic_selection_more)
    }

    /**
     * Gives [adapter] a selection and puts its bar over [pageToolbar] while anything is picked.
     * [onListChanged] runs after picked songs leave the list (unliked on the «Понравившиеся» page)
     * or come back (undo), for the page's count.
     */
    @JvmStatic
    fun attach(
        fragment: Fragment,
        pageToolbar: MaterialToolbar,
        adapter: SongHorizontalAdapter,
        starredPage: Boolean,
        playbackContext: String,
        browser: Supplier<ListenableFuture<MediaBrowser>?>,
        onListChanged: Runnable,
    ): SongSelection {
        val selection = SongSelection()
        val context = pageToolbar.context
        val bar = MaterialToolbar(context).apply {
            setBackgroundColor(MaterialColors.getColor(pageToolbar, com.google.android.material.R.attr.colorSurface, Color.BLACK))
            setNavigationIcon(R.drawable.ic_close)
            setNavigationContentDescription(R.string.homemusic_selection_close)
            inflateMenu(R.menu.homemusic_song_selection)
            visibility = View.GONE
        }
        val parent = pageToolbar.parent as ViewGroup
        parent.addView(bar, parent.indexOfChild(pageToolbar) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pageToolbar.layoutParams.height))

        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = selection.clear()
        }
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)

        // Visible rows first, so play and queue follow the order on screen (sorted, filtered),
        // then the picks a search filter hides.
        fun picked() = selection.pick(adapter.items + adapter.allItems)

        fun refresh() {
            val songs = picked()
            val picking = selection.isActive
            bar.visibility = if (picking) View.VISIBLE else View.GONE
            pageToolbar.visibility = if (picking) View.GONE else View.VISIBLE
            back.isEnabled = picking
            bar.title = context.getString(R.string.homemusic_selection_count, songs.size)
            val navidrome = songs.filterNot { HubTracks.isHub(it.id) }
            val allLiked = songs.isNotEmpty() && songs.all(::isLiked)
            val likeAction = LikeAction.of(starredPage, allLiked)
            bar.menu.findItem(R.id.homemusic_selection_like).apply {
                isVisible = songs.isNotEmpty()
                setTitle(likeAction.title)
                setIcon(likeAction.icon)
            }
            bar.menu.findItem(R.id.homemusic_selection_download).isVisible = navidrome.any { !isOnPhone(context, it) }
            bar.menu.findItem(R.id.homemusic_selection_remove).isVisible = navidrome.any { isOnPhone(context, it) }
            bar.menu.findItem(R.id.homemusic_selection_playlist).isVisible = navidrome.isNotEmpty()
            listOf(R.id.homemusic_selection_play, R.id.homemusic_selection_play_next,
                R.id.homemusic_selection_queue)
                .forEach { bar.menu.findItem(it).isVisible = songs.isNotEmpty() }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, SongSelection.PAYLOAD)
        }
        selection.onChange = ::refresh

        bar.setNavigationOnClickListener { selection.clear() }
        bar.setOnMenuItemClickListener { item ->
            val songs = picked()
            val navidrome = songs.filterNot { HubTracks.isHub(it.id) }
            val activity = fragment.activity as? MainActivity
            when (item.itemId) {
                R.id.homemusic_selection_all -> selection.toggleAll(adapter.items)
                R.id.homemusic_selection_like -> {
                    if (songs.all(::isLiked)) unlike(fragment, adapter, songs, starredPage, onListChanged)
                    else setLiked(context, songs.filterNot(::isLiked), true)
                    selection.clear()
                }
                R.id.homemusic_selection_play -> {
                    MediaManager.startQueue(browser.get(), songs, 0, playbackContext)
                    activity?.setBottomSheetInPeek(true)
                    selection.clear()
                }
                R.id.homemusic_selection_play_next, R.id.homemusic_selection_queue -> {
                    MediaManager.enqueue(browser.get(), songs, item.itemId == R.id.homemusic_selection_play_next)
                    activity?.setBottomSheetInPeek(true)
                    selection.clear()
                }
                R.id.homemusic_selection_playlist -> {
                    val dialog = PlaylistChooserDialog()
                    dialog.arguments = Bundle().apply { putParcelableArrayList(Constants.TRACKS_OBJECT, ArrayList(navidrome)) }
                    dialog.show(fragment.parentFragmentManager, null)
                    selection.clear()
                }
                R.id.homemusic_selection_download -> {
                    download(context, navidrome.filterNot { isOnPhone(context, it) })
                    selection.clear()
                }
                R.id.homemusic_selection_remove -> {
                    val onPhone = navidrome.filter { isOnPhone(context, it) }
                    MaterialAlertDialogBuilder(context)
                        .setTitle(context.resources.getQuantityString(R.plurals.homemusic_selection_remove_title, onPhone.size, onPhone.size))
                        .setMessage(R.string.homemusic_selection_remove_message)
                        .setPositiveButton(R.string.homemusic_selection_remove_ok) { _, _ ->
                            removeFromPhone(context, onPhone)
                            selection.clear()
                        }
                        .setNegativeButton(R.string.homemusic_cancel, null)
                        .show()
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        if (starredPage) {
            // «Удалить из понравившихся» in a row's track menu.
            fragment.parentFragmentManager.setFragmentResultListener(HomeSongSheet.RESULT_REMOVE_FROM_LIKED, fragment.viewLifecycleOwner) { _, result ->
                @Suppress("DEPRECATION")
                val sent = result.getParcelable<Child>(Constants.TRACK_OBJECT) ?: return@setFragmentResultListener
                val song = adapter.allItems.firstOrNull { it.id == sent.id } ?: sent
                unlike(fragment, adapter, listOf(song), true, onListChanged)
            }
        }
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                selection.onChange = null
            }
        })
        refresh()
        return selection
    }

    /**
     * The bar's ♥ item: likes the picks unless all are liked, then unlikes them. On «Понравившиеся»
     * unliking deletes the rows from the page, so it is a bin there.
     */
    internal enum class LikeAction(val title: Int, val icon: Int) {
        LIKE(R.string.homemusic_selection_like, R.drawable.ic_favorites_outlined),
        UNLIKE(R.string.homemusic_selection_unlike, R.drawable.ic_favorite),
        DELETE(R.string.homemusic_liked_remove, R.drawable.ic_delete);

        companion object {
            fun of(starredPage: Boolean, allLiked: Boolean) = when {
                !allLiked -> LIKE
                starredPage -> DELETE
                else -> UNLIKE
            }
        }
    }

    private fun isLiked(song: Child) =
        FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, song.id, song.starred != null)

    private fun isOnPhone(context: Context, song: Child) =
        if (Preferences.getDownloadDirectoryUri() == null) DownloadUtil.getDownloadTracker(context).isDownloaded(song.id)
        else ExternalAudioReader.getUri(song) != null

    /** Unlikes [songs]; on «Понравившиеся» they leave the list at once. The snackbar can like them back. */
    private fun unlike(fragment: Fragment, adapter: SongHorizontalAdapter, songs: List<Child>, starredPage: Boolean, onListChanged: Runnable) {
        val context = fragment.requireContext()
        val before = adapter.allItems.toList()
        setLiked(context, songs, false)
        if (starredPage) {
            val gone = songs.map { it.id }.toSet()
            adapter.setItems(before.filterNot { it.id in gone })
            onListChanged.run()
        }
        val view = fragment.view ?: return
        val activity = fragment.activity as? MainActivity
        Snackbar.make(view, context.resources.getQuantityString(R.plurals.homemusic_selection_unliked, songs.size, songs.size), Snackbar.LENGTH_LONG)
            .setAnchorView(activity?.snackbarAnchor)
            .setAction(R.string.homemusic_undo) {
                setLiked(context, songs, true)
                if (starredPage && fragment.view != null) {
                    adapter.setItems(before)
                    onListChanged.run()
                }
            }
            .show()
    }

    /** Likes or unlikes [songs] the way the track menu's ♥ does, offline through the retry queue. */
    private fun setLiked(context: Context, songs: List<Child>, liked: Boolean) {
        val repository = FavoriteRepository()
        for (song in songs) {
            if (HubTracks.isHub(song.id)) {
                HubTracks.setLiked(context, song, liked)
            } else if (NetworkUtil.isOffline()) {
                repository.starLater(song.id, null, null, liked)
            } else {
                val retry = object : StarCallback {
                    override fun onError() = repository.starLater(song.id, null, null, liked)
                }
                if (liked) repository.star(song.id, null, null, retry) else repository.unstar(song.id, null, null, retry)
            }
            song.starred = if (liked) Date() else null
        }
        if (liked && !NetworkUtil.isOffline() && Preferences.isStarredSyncEnabled() && Preferences.getDownloadDirectoryUri() == null) {
            download(context, songs.filterNot { HubTracks.isHub(it.id) || isOnPhone(context, it) })
        }
    }

    private fun download(context: Context, songs: List<Child>) {
        if (songs.isEmpty()) return
        if (Preferences.getDownloadDirectoryUri() == null) {
            DownloadUtil.getDownloadTracker(context).download(MappingUtil.mapDownloads(songs), songs.map { Download(it) })
        } else {
            songs.forEach { ExternalAudioWriter.downloadToUserDirectory(context, it, null, null) }
        }
    }

    private fun removeFromPhone(context: Context, songs: List<Child>) {
        if (songs.isEmpty()) return
        if (Preferences.getDownloadDirectoryUri() == null) {
            DownloadUtil.getDownloadTracker(context).remove(MappingUtil.mapDownloads(songs), songs.map { Download(it) })
        } else {
            songs.forEach { ExternalAudioReader.delete(it) }
        }
    }
}
