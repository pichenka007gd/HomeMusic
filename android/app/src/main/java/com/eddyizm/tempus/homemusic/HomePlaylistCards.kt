package com.eddyizm.tempus.homemusic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.ItemLibraryAlbumBinding
import com.eddyizm.tempus.databinding.ItemLibraryCatalogueAlbumBinding
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.helper.recyclerview.CustomLinearSnapHelper
import com.eddyizm.tempus.helper.recyclerview.GridItemDecoration
import com.eddyizm.tempus.interfaces.ClickCallback
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.TileSizeManager

/**
 * Playlists as cover cards, the way albums look: a horizontal row on the Library screen and a grid
 * on its «Все» page. Tempus draws them as rows with a ⋮, which reads like a list of tracks; the
 * menu stays on a long press, as on album cards.
 */
object HomePlaylistCards {
    /** The Library section: one row of tiles, the same size and snapping as «Альбомы». */
    @JvmStatic
    fun row(recycler: RecyclerView, click: ClickCallback): HomePlaylistCardAdapter {
        recycler.layoutManager = LinearLayoutManager(recycler.context, LinearLayoutManager.HORIZONTAL, false)
        recycler.setHasFixedSize(true)
        // The section's list was laid out for full-width rows; the tiles start at the albums' 16dp.
        recycler.setPaddingRelative((16 * recycler.resources.displayMetrics.density).toInt(), recycler.paddingTop, recycler.paddingEnd, recycler.paddingBottom)
        val adapter = HomePlaylistCardAdapter(click, grid = false)
        recycler.adapter = adapter
        CustomLinearSnapHelper().attachToRecyclerView(recycler)
        return adapter
    }

    /** The «Все» page: a grid like the album catalogue's. */
    @JvmStatic
    fun grid(recycler: RecyclerView, click: ClickCallback): HomePlaylistCardAdapter {
        val context = recycler.context
        val spans = TileSizeManager.getInstance().getTileSpanCount(context)
        recycler.layoutManager = GridLayoutManager(context, spans)
        recycler.addItemDecoration(GridItemDecoration(spans, TileSizeManager.getInstance().getTileSpacing(context), false))
        recycler.setHasFixedSize(true)
        val side = (16 * recycler.resources.displayMetrics.density).toInt()
        recycler.setPaddingRelative(side, recycler.paddingTop, side, recycler.paddingBottom)
        val adapter = HomePlaylistCardAdapter(click, grid = true)
        recycler.adapter = adapter
        return adapter
    }

    /** The «Все» page's search: the same matching as the folder's artist search. */
    @JvmStatic
    fun filter(playlists: List<Playlist>, query: String): List<Playlist> =
        if (query.isBlank()) playlists else playlists.filter { HomeIndexSearch.matches(it.name.orEmpty(), query) }
}

class HomePlaylistCardAdapter(
    private val click: ClickCallback,
    private val grid: Boolean,
) : RecyclerView.Adapter<HomePlaylistCardAdapter.ViewHolder>(), Filterable {
    private var all: List<Playlist> = emptyList()
    private var shown: List<Playlist> = emptyList()
    private var query = ""

    fun setItems(playlists: List<Playlist>) {
        all = ArrayList(playlists)
        shown = HomePlaylistCards.filter(all, query)
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply { values = constraint?.toString().orEmpty() }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            query = results?.values as? String ?: ""
            shown = HomePlaylistCards.filter(all, query)
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (grid) {
            val b = ItemLibraryCatalogueAlbumBinding.inflate(inflater, parent, false)
            ViewHolder(b.root, b.albumCatalogueCoverImageView, b.albumNameLabel, b.artistNameLabel)
        } else {
            val b = ItemLibraryAlbumBinding.inflate(inflater, parent, false)
            val size = TileSizeManager.getInstance().getTileSizePx(parent.context)
            b.albumCoverImageView.layoutParams = b.albumCoverImageView.layoutParams.apply { width = size; height = size }
            ViewHolder(b.root, b.albumCoverImageView, b.albumNameLabel, b.artistNameLabel)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = shown[position]
        val res = holder.itemView.resources
        holder.title.text = playlist.name
        holder.subtitle.text = res.getQuantityString(R.plurals.homemusic_tracks_count, playlist.songCount, playlist.songCount)
        CustomGlideRequest.Builder
            .from(holder.itemView.context, playlist.coverArtId, CustomGlideRequest.ResourceType.Playlist)
            .build()
            .into(holder.cover)
    }

    inner class ViewHolder(root: View, val cover: ImageView, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(root) {
        init {
            title.isSelected = true
            root.setOnClickListener { click.onPlaylistClick(bundle()) }
            root.setOnLongClickListener {
                click.onPlaylistLongClick(bundle())
                true
            }
        }

        private fun bundle() = Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, shown[bindingAdapterPosition]) }
    }
}
