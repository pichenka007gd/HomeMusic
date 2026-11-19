package com.eddyizm.tempus.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Answers
import org.mockito.Mockito.mockConstruction
import org.mockito.stubbing.Answer
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(Parameterized::class)
class TrackSkipPlaybackTest(private val skipCommand: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "transport command {0}")
        fun commands() = listOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
    }

    private val context = mock<Context>()
    private val service = mock<BaseMediaService>()
    private val session = mock<MediaSession>()
    private val controller = mock<MediaSession.ControllerInfo>()
    private val player = mock<Player>()

    @Before
    fun pausedSong() {
        whenever(context.getString(any())).thenReturn("control")
        whenever(session.player).thenReturn(player)
        whenever(player.currentMediaItem).thenReturn(MediaItem.Builder().setMediaId("next-song").build())
        whenever(player.playbackState).thenReturn(Player.STATE_READY)
    }

    private fun interaction(vararg commands: Int) {
        // Commands uses Android's SparseBooleanArray, which is a stub in plain JVM tests.
        val commandSet = mock<Player.Commands>(defaultAnswer = Answer { call ->
            if (call.method.name == "containsAny") {
                (call.rawArguments[0] as IntArray).any { it in commands }
            } else Answers.RETURNS_DEFAULTS.answer(call)
        })
        mockConstruction(SessionCommand::class.java).use {
            BaseSessionCallback(context, service).onPlayerInteractionFinished(
                session, controller, commandSet
            )
        }
    }

    @Test
    fun skippingAPausedSongStartsPlayback() {
        interaction(skipCommand)
        verify(player).play()
        verify(player, never()).prepare()
    }

    @Test
    fun skippingAStoppedSongPreparesBeforePlaying() {
        whenever(player.playbackState).thenReturn(Player.STATE_IDLE)
        interaction(skipCommand)
        inOrder(player) {
            verify(player).prepare()
            verify(player).play()
        }
    }

    @Test
    fun skippingWhileBufferingStartsPlaybackWhenReady() {
        whenever(player.playbackState).thenReturn(Player.STATE_BUFFERING)
        interaction(skipCommand)
        verify(player).play()
        verify(player, never()).prepare()
    }

    @Test
    fun anAlreadyPlayingSongKeepsPlaying() {
        whenever(player.playWhenReady).thenReturn(true)
        interaction(skipCommand)
        verify(player, never()).play()
        verify(player, never()).prepare()
    }

    @Test
    fun anEmptyQueueDoesNotStartPlayback() {
        whenever(player.currentMediaItem).thenReturn(null)
        interaction(skipCommand)
        verify(player, never()).play()
        verify(player, never()).prepare()
    }

    @Test
    fun anExplicitPauseInTheSameBatchWins() {
        interaction(skipCommand, Player.COMMAND_PLAY_PAUSE)
        verify(player, never()).play()
    }

    @Test
    fun anExplicitStopInTheSameBatchWins() {
        interaction(skipCommand, Player.COMMAND_STOP)
        verify(player, never()).play()
    }

    @Test
    fun positionSeeksAndQueueRestorationStayPaused() {
        interaction(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_PREPARE)
        verify(player, never()).play()
    }
}
