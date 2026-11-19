package com.eddyizm.tempus.ui.fragment

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Fade
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.InnerFragmentPlayerCoverBinding
import com.eddyizm.tempus.homemusic.QueueAhead
import com.eddyizm.tempus.model.Download
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.components.NowPlayingArtworkPager
import com.eddyizm.tempus.ui.dialog.PlaylistChooserDialog
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.ExternalAudioWriter
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.ArrayList

@OptIn(markerClass = [UnstableApi::class])
class PlayerCoverFragment : Fragment() {

    private var bind: InnerFragmentPlayerCoverBinding? = null
    private lateinit var playerBottomSheetViewModel: PlayerBottomSheetViewModel
    private var mediaBrowserListenableFuture: ListenableFuture<MediaBrowser>? = null
    private var mediaBrowser: MediaBrowser? = null

    private val handler = Handler(Looper.getMainLooper())

    private var queueItems by mutableStateOf<List<Child>>(emptyList())
    private var localQueue: List<Child> = emptyList()
    private var currentPlayingIndex by mutableIntStateOf(0)
    private var isRadioStation by mutableStateOf(false)
    private var radioStationArtworkUri by mutableStateOf<Uri?>(null)
    private var radioStationCoverArtId by mutableStateOf<String?>(null)

    // HomeMusic (QueueAhead): the pages follow the play order of the player (shuffle included), page i is window
    // playOrder[i]; a loading page after the last track while continuous play gets the next ones
    private var playOrder: IntArray = IntArray(0)
    private var loadingTail by mutableStateOf(false)
    private var waitingNext by mutableStateOf(false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = InnerFragmentPlayerCoverBinding.inflate(inflater, container, false)
        bind = binding

        playerBottomSheetViewModel = ViewModelProvider(requireActivity())[PlayerBottomSheetViewModel::class.java]

        binding.nowPlayingArtworkPager.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NowPlayingArtworkPager(
                    queue = queueItems,
                    currentIndex = currentPlayingIndex,
                    isRadio = isRadioStation,
                    radioArtworkUri = radioStationArtworkUri,
                    radioCoverArtId = radioStationCoverArtId,
                    loadingTail = loadingTail,
                    waitingNext = waitingNext,
                    onPageSelected = pageSelected@ { page ->
                        if (page == queueItems.size && loadingTail && !LanRemoteSession.isActive()) {
                            QueueAhead.requestNext()
                            return@pageSelected
                        }
                        if (page in queueItems.indices) {
                            if (LanRemoteSession.isActive()) {
                                if (LanRemoteSession.queueIsCurrent()) LanRemoteSession.routeEdit("select", page, page)
                                return@pageSelected
                            }
                            currentPlayingIndex = page
                            val browser = mediaBrowser
                            val window = playOrder.getOrNull(page)
                            if (browser != null && window != null && window < browser.mediaItemCount &&
                                browser.getMediaItemAt(window).mediaId == queueItems[page].id) {
                                browser.seekToDefaultPosition(window)
                                browser.play()
                            } else if (browser != null && browser.mediaItemCount > 0) {
                                // the queue changed under the swipe: show where the player is
                                updatePlaybackState(browser.mediaMetadata, browser.currentMediaItemIndex)
                            } else {
                                mediaBrowserListenableFuture?.let { future ->
                                    MediaManager.startQueue(future, queueItems, page, "queue")
                                }
                            }
                        }
                    },
                    onCoverClick = {
                        toggleOverlayVisibility(true)
                    }
                )
            }
        }

        initOverlay()
        initInnerButton()
        observeQueue()

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        initializeBrowser()
        bindMediaController()
        toggleOverlayVisibility(false)
    }

    override fun onStop() {
        releaseBrowser()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        bind = null
    }

    private fun observeQueue() {
        playerBottomSheetViewModel.getQueueSong().observe(viewLifecycleOwner) { queue ->
            localQueue = queue?.map { it as Child } ?: emptyList()
            // the saved queue until the player is there, then the player's own order
            if (!LanRemoteSession.isActive()) mediaBrowser?.let { refreshOrder(it) } ?: run { queueItems = localQueue }
        }
        QueueAhead.waiting.observe(viewLifecycleOwner) {
            waitingNext = it == true
            mediaBrowser?.let { browser -> refreshOrder(browser) } // nothing came: no loading page
        }
        LanRemoteSession.queueState().observe(viewLifecycleOwner) { if (LanRemoteSession.isActive()) refreshRemoteArtwork() }
        LanRemoteSession.state().observe(viewLifecycleOwner) { state ->
            if (state.active) refreshRemoteArtwork()
            else {
                queueItems = localQueue
                mediaBrowser?.let { updatePlaybackState(it.mediaMetadata, it.currentMediaItemIndex) }
            }
        }
        playerBottomSheetViewModel.getLiveMedia().observe(viewLifecycleOwner) { song ->
            if (!LanRemoteSession.isActive() && mediaBrowser == null && song != null && queueItems.isNotEmpty()) {
                val idx = queueItems.indexOfFirst { it.id == song.id }
                if (idx >= 0 && idx != currentPlayingIndex) {
                    currentPlayingIndex = idx
                }
            }
        }
    }

    private fun initTapButtonHideTransition() {
        bind?.let { binding ->
            binding.nowPlayingTapButton.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages(null)
            val runnable = Runnable {
                bind?.nowPlayingTapButton?.visibility = View.GONE
            }
            handler.postDelayed(runnable, 10000)
        }
    }

    private fun refreshRemoteArtwork() {
        val state = LanRemoteSession.current()
        isRadioStation = false
        queueItems = if (LanRemoteSession.queueIsCurrent()) LanRemoteSession.queueItems()
            else if (state.count > 0) listOf(LanRemoteSession.currentSong()) else emptyList()
        currentPlayingIndex = if (LanRemoteSession.queueIsCurrent()) state.index.coerceAtLeast(0) else 0
    }

    private fun initOverlay() {
        bind?.let { binding ->
            binding.nowPlayingSongCoverButtonGroup.setOnClickListener { toggleOverlayVisibility(false) }
            binding.nowPlayingTapButton.setOnClickListener { toggleOverlayVisibility(true) }
        }
    }

    private fun toggleOverlayVisibility(isVisible: Boolean) {
        val binding = bind ?: return
        val transition = Fade().apply {
            duration = 200
            addTarget(binding.nowPlayingSongCoverButtonGroup)
        }

        TransitionManager.beginDelayedTransition(binding.root, transition)
        binding.nowPlayingSongCoverButtonGroup.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.nowPlayingTapButton.visibility = if (isVisible) View.GONE else View.VISIBLE

        binding.innerButtonBottomRight.visibility = if (Preferences.isSyncronizationEnabled()) View.VISIBLE else View.GONE
        binding.innerButtonBottomRightAlternative.visibility = if (Preferences.isSyncronizationEnabled()) View.GONE else View.VISIBLE

        if (!isVisible) {
            initTapButtonHideTransition()
        }
    }

    private fun initInnerButton() {
        playerBottomSheetViewModel.getLiveMedia().observe(viewLifecycleOwner) { song ->
            val binding = bind ?: return@observe
            if (song != null) {
                binding.innerButtonTopLeft.setOnClickListener {
                    if (Preferences.getDownloadDirectoryUri() == null) {
                        DownloadUtil.getDownloadTracker(requireContext()).download(
                            MappingUtil.mapDownload(song),
                            Download(song)
                        )
                    } else {
                        ExternalAudioWriter.downloadToUserDirectory(requireContext(), song)
                    }
                }

                binding.innerButtonTopRight.setOnClickListener {
                    val tracks = ArrayList<Child>().apply { add(song) }
                    val bundle = Bundle().apply {
                        putParcelableArrayList(Constants.TRACKS_OBJECT, tracks)
                    }
                    val dialog = PlaylistChooserDialog().apply {
                        arguments = bundle
                    }
                    dialog.show(requireActivity().supportFragmentManager, null)
                }

                binding.innerButtonBottomLeft.setOnClickListener {
                    playerBottomSheetViewModel.getMediaInstantMix(viewLifecycleOwner, song).observe(viewLifecycleOwner) { media ->
                        val future = mediaBrowserListenableFuture
                        if (future != null && media != null) {
                            MediaManager.enqueue(future, media, true)
                        }
                    }
                }

                binding.innerButtonBottomRight.setOnClickListener {
                    if (playerBottomSheetViewModel.savePlayQueue()) {
                        Snackbar.make(requireView(), R.string.player_queue_save_queue_success, Snackbar.LENGTH_LONG).show()
                    }
                }

                binding.innerButtonBottomRightAlternative.setOnClickListener {
                    val playerBottomSheetFragment = requireActivity().supportFragmentManager
                        .findFragmentByTag("PlayerBottomSheet") as? PlayerBottomSheetFragment
                    playerBottomSheetFragment?.goToLyricsPage()
                }
            }
        }
    }

    private fun initializeBrowser() {
        mediaBrowserListenableFuture = MediaBrowser.Builder(
            requireContext(),
            SessionToken(requireContext(), ComponentName(requireContext(), MediaService::class.java))
        ).buildAsync()
    }

    private fun releaseBrowser() {
        mediaBrowserListenableFuture?.let { MediaBrowser.releaseFuture(it) }
        mediaBrowserListenableFuture = null
        mediaBrowser = null
    }

    private fun bindMediaController() {
        mediaBrowserListenableFuture?.let { future ->
            future.addListener({
                try {
                    val browser = future.get()
                    mediaBrowser = browser
                    setMediaBrowserListener(browser)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, MoreExecutors.directExecutor())
        }
    }

    private fun setMediaBrowserListener(browser: MediaBrowser) {
        updatePlaybackState(browser.mediaMetadata, browser.currentMediaItemIndex)

        browser.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updatePlaybackState(mediaMetadata, browser.currentMediaItemIndex)
                toggleOverlayVisibility(false)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackState(browser.mediaMetadata, browser.currentMediaItemIndex)
                toggleOverlayVisibility(false)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED)) refreshOrder(browser)
            }
        })
    }

    private fun updatePlaybackState(mediaMetadata: MediaMetadata, itemIndex: Int) {
        if (LanRemoteSession.isActive()) return
        val extras = mediaMetadata.extras
        val isRadio = extras != null && Constants.MEDIA_TYPE_RADIO == extras.getString("type")

        isRadioStation = isRadio
        radioStationArtworkUri = mediaMetadata.artworkUri
        radioStationCoverArtId = extras?.getString("coverArtId")

        mediaBrowser?.let { browser ->
            if (browser.mediaItemCount > 0) {
                refreshOrder(browser)
                return
            }
        }
        val mediaId = mediaBrowser?.currentMediaItem?.mediaId ?: extras?.getString("id")
        if (mediaId != null && queueItems.isNotEmpty()) {
            val idx = queueItems.indexOfFirst { it.id == mediaId }
            if (idx >= 0) {
                currentPlayingIndex = idx
                return
            }
        }
        currentPlayingIndex = itemIndex.coerceAtLeast(0)
    }

    /** The pages from the player: its tracks in play order, the current one, the loading page. */
    private fun refreshOrder(browser: MediaBrowser) {
        if (LanRemoteSession.isActive()) return
        val order = QueueAhead.playOrder(browser)
        playOrder = order
        queueItems = order.map { MappingUtil.mapToChild(browser.getMediaItemAt(it)) ?: Child(browser.getMediaItemAt(it).mediaId) }
        currentPlayingIndex = order.indexOf(browser.currentMediaItemIndex).coerceAtLeast(0)
        loadingTail = !isRadioStation && QueueAhead.showsLoadingTail(browser)
    }
}
