package com.eddyizm.tempus.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.util.RadioCoverArtDownloader
import kotlin.math.absoluteValue

@Composable
fun NowPlayingArtworkPager(
    queue: List<Child>,
    currentIndex: Int,
    isRadio: Boolean = false,
    radioArtworkUri: Uri? = null,
    radioCoverArtId: String? = null,
    // HomeMusic (QueueAhead): a loading page after the last track while continuous play gets the next ones;
    // waitingNext — the user went there, so the pager stays on it
    loadingTail: Boolean = false,
    waitingNext: Boolean = false,
    onPageSelected: (Int) -> Unit = {},
    onCoverClick: () -> Unit = {}
) {
    val currentTrackIndex by rememberUpdatedState(currentIndex)
    val currentOnPageSelected by rememberUpdatedState(onPageSelected)
    val currentOnCoverClick by rememberUpdatedState(onCoverClick)

    val tail = loadingTail && !isRadio && queue.isNotEmpty()
    val count = if (isRadio || queue.isEmpty()) 1 else queue.size + if (tail) 1 else 0
    val currentCount by rememberUpdatedState(count)
    val safeIndex = when {
        isRadio -> 0
        tail && waitingNext -> queue.size
        else -> currentIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    val pagerState = rememberPagerState(
        initialPage = safeIndex,
        pageCount = { count }
    )
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var userSwiped by remember { mutableStateOf(false) }

    LaunchedEffect(isDragged) {
        if (isDragged) {
            userSwiped = true
        }
    }

    // Programmatic smooth scroll when track index changes externally (e.g. next/prev buttons, notification, end-of-track).
    // Guarded by safeIndex != pagerState.targetPage to avoid interrupting manual swipes.
    LaunchedEffect(safeIndex) {
        if (!isDragged && safeIndex in 0 until count && safeIndex != pagerState.targetPage) {
            pagerState.animateScrollToPage(
                page = safeIndex,
                animationSpec = tween(durationMillis = 300)
            )
        }
    }

    // Capture user swipe settlement and trigger track change only when user physically swiped.
    // HomeMusic: the swipe is over once the pager stops, even back on the same page; a flag left over from such a
    // swipe used to turn the next programmatic scroll (track change) into a "swipe" back to the previous track.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (scrolling || !userSwiped) return@collect
            userSwiped = false
            val page = pagerState.currentPage
            if (!isRadio && page in 0 until currentCount && page != currentTrackIndex) {
                currentOnPageSelected(page)
            }
        }
    }

    val cornerRadius = if (Preferences.isCornerRoundingEnabled()) {
        Preferences.getRoundedCornerSize().dp
    } else {
        0.dp
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 0.dp),
        pageSpacing = 16.dp,
        userScrollEnabled = !isRadio && count > 1,
        key = { page ->
            if (isRadio) "radio" else "${queue.getOrNull(page)?.id ?: "loading"}_$page"
        }
    ) { page ->
        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
        val scale = lerp(0.92f, 1.0f, 1f - pageOffset.coerceIn(0f, 1f))
        val alpha = lerp(0.7f, 1.0f, 1f - pageOffset.coerceIn(0f, 1f))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(RoundedCornerShape(cornerRadius))
                .clickable {
                    if (page == pagerState.currentPage) {
                        onCoverClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isRadio) {
                RadioArtwork(artworkUri = radioArtworkUri, coverArtId = radioCoverArtId)
            } else if (page >= queue.size) {
                LoadingArtwork()
            } else {
                val song = queue.getOrNull(page)
                SongArtwork(coverArtId = song?.coverArtId, title = song?.title)
            }
        }
    }
}

@Composable
private fun SongArtwork(
    coverArtId: String?,
    title: String?
) {
    val appContext = LocalContext.current.applicationContext
    var bitmap by remember(coverArtId) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(coverArtId) {
        if (coverArtId.isNullOrEmpty()) {
            bitmap = null
            return@DisposableEffect onDispose {}
        }

        val url = CustomGlideRequest.createUrl(coverArtId, Preferences.getImageSize())
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }

        Glide.with(appContext)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(CustomGlideRequest.DEFAULT_DISK_CACHE_STRATEGY)
            .into(target)

        onDispose {
            Glide.with(appContext).clear(target)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_placeholder_song),
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

/** The next track that is not in the queue yet (HomeMusic QueueAhead): a pulsing placeholder. */
@Composable
private fun LoadingArtwork() {
    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Gray.copy(alpha = pulse)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.ic_placeholder_song),
                contentDescription = null,
                modifier = Modifier.size(96.dp).alpha(0.5f)
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                text = stringResource(R.string.homemusic_player_next_loading),
                style = TextStyle(color = Color.Gray, fontSize = 15.sp, textAlign = TextAlign.Center)
            )
        }
    }
}

@Composable
private fun RadioArtwork(
    artworkUri: Uri?,
    coverArtId: String?
) {
    val appContext = LocalContext.current.applicationContext
    var bitmap by remember(artworkUri, coverArtId) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(artworkUri, coverArtId) {
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                bitmap = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                bitmap = null
            }
        }

        var request = Glide.with(appContext)
            .asBitmap()
            .load(artworkUri)
            .apply(CustomGlideRequest.createRequestOptions(appContext, coverArtId, CustomGlideRequest.ResourceType.Radio))

        request = RadioCoverArtDownloader.applyLocalFileSignature(request, artworkUri)
        request.into(target)

        onDispose {
            Glide.with(appContext).clear(target)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_placeholder_radio),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}
