package com.eddyizm.tempus.homemusic

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eddyizm.tempus.helper.recyclerview.CustomLinearSnapHelper
import com.eddyizm.tempus.interfaces.ClickCallback
import com.eddyizm.tempus.ui.adapter.AlbumAdapter

/**
 * «Понравившиеся альбомы» on Главная as cover cards, like «Альбомы» in the Library and «Недавно
 * добавленные» on Главная. Tempus pages them as rows of five with dots, which reads like tracks.
 */
object HomeAlbumCards {
    @JvmStatic
    fun row(recycler: RecyclerView, click: ClickCallback): AlbumAdapter {
        recycler.layoutManager = LinearLayoutManager(recycler.context, LinearLayoutManager.HORIZONTAL, false)
        recycler.setHasFixedSize(true)
        // The rows were full width; the tiles start at the other album rows' 16dp.
        val density = recycler.resources.displayMetrics.density
        recycler.setPaddingRelative((16 * density).toInt(), recycler.paddingTop, (8 * density).toInt(), recycler.paddingBottom)
        val adapter = AlbumAdapter(click)
        recycler.adapter = adapter
        CustomLinearSnapHelper().attachToRecyclerView(recycler)
        return adapter
    }
}
