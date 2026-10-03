package tv.reely.ui.touch

import androidx.compose.runtime.Composable
import tv.reely.ui.ReelyState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.screens.SettingsScreen

/**
 * Settings on a phone: the television's own rows and choices, in one column. One set of
 * settings for both, so neither can gain one the other hasn't got.
 */
@Composable
internal fun TouchSettings(viewModel: ReelyViewModel, state: ReelyState, onSwitchProfile: () -> Unit = {}) {
    SettingsScreen(
        plex = state.plex,
        live = state.live,
        guide = state.guide,
        prefs = state.prefs,
        onSignOutPlex = viewModel::signOutPlex,
        onSignOutXtream = viewModel::signOutXtream,
        onSwitchServer = viewModel::switchServer,
        onSwitchProfile = onSwitchProfile,
        requests = state.requests,
        onDisconnectReely = viewModel::disconnectReely,
        onToggleHomeRow = viewModel::toggleHomeRow,
        iptv = state.iptv,
        onSetIptvLibrary = viewModel::setIptvLibrary,
        onSetIptvWins = viewModel::setIptvWins,
        onRefreshIptv = viewModel::refreshIptvLibrary,
        onToggleFavourite = viewModel::toggleFavouriteLibrary,
        onToggleFormat = viewModel::toggleFormat,
        onNudgeSubtitleScale = viewModel::nudgeSubtitleScale,
        onToggleSubtitleBackground = viewModel::toggleSubtitleBackground,
        onNudgeUpNext = viewModel::nudgeUpNextSeconds,
        onToggleGuidePreview = viewModel::toggleGuidePreview,
        onToggleMultiviewLayout = viewModel::toggleMultiviewLayout,
        onToggleThemeMusic = viewModel::toggleThemeMusic,
        onToggleMatchFrameRate = viewModel::toggleMatchFrameRate,
        onToggleLargerBuffer = viewModel::toggleLargerBuffer,
        onToggleSkipIntros = viewModel::toggleSkipIntros,
        onToggleSkipCredits = viewModel::toggleSkipCredits,
        onSetAudioOutput = viewModel::setAudioOutput,
        onSetScreensaver = viewModel::setScreensaverMinutes,
        onNudgeThemeVolume = viewModel::nudgeThemeVolume,
        onSetPlaybackMode = viewModel::setPlaybackMode,
        onSetMaxBitrate = viewModel::setMaxBitrate,
        onRefreshChannels = viewModel::refreshLiveChannels,
        onRefreshGuide = { viewModel.refreshGuide(force = true) },
        update = state.update,
        updateUrl = viewModel.updateUrl,
        onCheckForUpdate = viewModel::checkForUpdate,
        onInstallUpdate = viewModel::installUpdate,
        onOpenInstaller = viewModel::openInstaller,
        compact = true,
    )
}
