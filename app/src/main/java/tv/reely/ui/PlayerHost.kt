package tv.reely.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import tv.reely.ui.screens.PlayerScreen

/**
 * The player, wired to the app: the same on a television and on a phone, where [touch]
 * adds what a touch screen needs (see PlayerScreen). One place, so the two can't drift.
 */
@Composable
internal fun PlayerHost(viewModel: ReelyViewModel, state: ReelyState, touch: Boolean = false) {
    /*
     * The programme the live bar spans, asked again every little while as well as when
     * anything changes: programmes end with nothing else changing, and a bar left on the
     * last one pointed a rewind at the wrong programme.
     */
    val liveWindow by produceState(viewModel.liveWindow(), state.playback, state.guide.programmes, state.live) {
        while (true) {
            value = viewModel.liveWindow()
            delay(LIVE_WINDOW_REFRESH_MS)
        }
    }

    val playback = state.playback ?: return
    // On a phone, the guide over a channel is a sheet of channels; see TouchChannelSheet.
    var touchGuide by remember { mutableStateOf<tv.reely.core.GuideRequest?>(null) }
    CompositionLocalProvider(tv.reely.ui.screens.LocalTouchPlayer provides touch) {
        PlayerScreen(
            playback = playback,
            onSetAudioOutput = viewModel::setAudioOutput,
            liveWindow = liveWindow,
            onTimeshift = viewModel::timeshiftTo,
            onGoLive = viewModel::goLive,
            prefs = state.prefs,
            live = state.live,
            guide = state.guide,
            upNext = state.upNext,
            onExit = viewModel::stopPlayback,
            onEnded = viewModel::onPlaybackEnded,
            onCredits = viewModel::onCreditsReached,
            onPlayUpNext = viewModel::playUpNext,
            onDismissUpNext = viewModel::dismissUpNext,
            onToggleFavoriteChannel = viewModel::toggleFavoriteChannel,
            livePlayer = viewModel.livePlayer,
            onStepChannel = viewModel::stepChannel,
            onSelectChannel = viewModel::playChannel,
            onOpenCategory = viewModel::openCategory,
            multiview = state.multiview,
            onAddToMultiview = viewModel::addToMultiview,
            onRemoveTile = viewModel::removeFromMultiview,
            onClearTiles = viewModel::clearMultiview,
            onReplaceTile = viewModel::replaceInMultiview,
            onCollapseToChannel = viewModel::collapseToChannel,
            onSaveMultiview = viewModel::saveMultiview,
            savedMultiview = remember(state.savedMultiview, state.multiview, state.playback, state.live.recent) {
                viewModel.savedMultiviewLabel()
            },
            onOpenSavedMultiview = viewModel::openSavedMultiview,
            onStepEpisode = viewModel::stepEpisode,
            onDecodeFailure = viewModel::retryWithTranscode,
            onReopen = viewModel::reopenPlayback,
            onConvertAudio = viewModel::convertAudio,
            onToggleFormat = viewModel::toggleFormat,
            onReportProgress = viewModel::reportProgress,
            onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
            onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
            onSaveStreamChoice = { audio, subtitle -> viewModel.saveStreamChoice(audio, subtitle) },
            findChannel = viewModel::channelNumbered,
            onCatchUp = viewModel::playCatchUp,
            sleep = state.sleep,
            onSetSleep = viewModel::setSleepTimer,
            reminders = state.reminders,
            onToggleReminder = viewModel::toggleReminder,
            subtitleSearch = state.subtitleSearch,
            onFindSubtitles = viewModel::searchSubtitles,
            onAddSubtitle = viewModel::addSubtitle,
            onCloseSubtitleSearch = viewModel::closeSubtitleSearch,
            reminder = state.dueReminder,
            onWatchReminder = viewModel::watchReminder,
            onDismissReminder = viewModel::dismissReminder,
            onStartOver = viewModel.startOverProgramme()?.let { { viewModel.startOver() } },
            onTuneChannel = viewModel::tuneChannel,
            imageUrl = viewModel::plexImageUrl,
            logoUrl = viewModel::plexLogoUrl,
            modifier = Modifier.fillMaxSize(),
            touch = touch,
            onTouchGuide = if (touch) ({ touchGuide = it }) else null,
        )
        touchGuide?.let { request ->
            tv.reely.ui.touch.TouchChannelSheet(viewModel, state, request, onDismiss = { touchGuide = null })
        }
    }
}
