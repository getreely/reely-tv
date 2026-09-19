package tv.reely.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.reely.core.SecureStore
import tv.reely.core.wrapIndex
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexItem
import tv.reely.plex.PlexSection
import tv.reely.plex.PlexSubtitle
import tv.reely.xtream.StreamFormat
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials
import java.util.UUID

/**
 * Movie libraries and show libraries are separate destinations, because that is how the
 * Plex client presents them and how people look for things.
 */
enum class LibraryKind(val plexType: String, val title: String) {
    MOVIES("movie", "Movies"),
    SHOWS("show", "TV Shows"),
}

data class Crumb(val title: String, val path: String)

/** One independent browse position, per library kind, so the two tabs don't disturb each other. */
data class BrowseState(
    val section: PlexSection? = null,
    val trail: List<Crumb> = emptyList(),
    val items: List<PlexItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

data class PlexState(
    val token: String? = null,
    val serverName: String? = null,
    val baseUrl: String? = null,
    val serverToken: String? = null,
    val sections: List<PlexSection> = emptyList(),
    val browse: Map<LibraryKind, BrowseState> = LibraryKind.entries.associateWith { BrowseState() },
    val linkCode: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = baseUrl != null && serverToken != null

    fun sectionsFor(kind: LibraryKind): List<PlexSection> = sections.filter { it.type == kind.plexType }

    fun browseFor(kind: LibraryKind): BrowseState = browse[kind] ?: BrowseState()
}

data class LiveState(
    val credentials: XtreamCredentials? = null,
    val account: XtreamAccount? = null,
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategory: XtreamCategory? = null,
    val channels: List<XtreamChannel> = emptyList(),
    val format: StreamFormat = StreamFormat.TS,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = credentials != null && account != null
}

data class Playback(
    val title: String,
    val subtitle: String?,
    val url: String,
    val isLive: Boolean,
    val channelIndex: Int = -1,
    val format: StreamFormat = StreamFormat.TS,
    val subtitles: List<PlexSubtitle> = emptyList(),
)

data class ReelyState(
    val restoring: Boolean = true,
    val plex: PlexState = PlexState(),
    val live: LiveState = LiveState(),
    val playback: Playback? = null,
)

class ReelyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SecureStore(application)

    private val _state = MutableStateFlow(ReelyState())
    val state: StateFlow<ReelyState> = _state.asStateFlow()

    private val clientId: String = store.get(SecureStore.PLEX_CLIENT_ID)
        ?: UUID.randomUUID().toString().also { store.put(SecureStore.PLEX_CLIENT_ID, it) }

    private var linkJob: Job? = null

    init {
        viewModelScope.launch {
            restorePlex()
            restoreLive()
            _state.update { it.copy(restoring = false) }
        }
    }

    // ---------------------------------------------------------------- Plex

    private suspend fun restorePlex() {
        val token = store.get(SecureStore.PLEX_TOKEN) ?: return
        val base = store.get(SecureStore.PLEX_SERVER_URI)
        val serverToken = store.get(SecureStore.PLEX_SERVER_TOKEN)
        updatePlex {
            it.copy(
                token = token,
                baseUrl = base,
                serverToken = serverToken,
                serverName = store.get(SecureStore.PLEX_SERVER_NAME),
            )
        }
        if (base != null && serverToken != null) loadSections() else connectServer(token)
    }

    fun startPlexLink() {
        if (linkJob?.isActive == true) return
        linkJob = viewModelScope.launch {
            updatePlex { it.copy(busy = true, error = null, linkCode = null) }
            val pin = runCatching { PlexApi.createPin(clientId) }.getOrElse { failure ->
                updatePlex { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            updatePlex { it.copy(busy = false, linkCode = pin.code) }

            // plex.tv expires a PIN after 15 minutes; stop looking well before that.
            repeat(150) {
                delay(2_000)
                val token = runCatching { PlexApi.claimPin(clientId, pin.id) }.getOrNull()
                if (token != null) {
                    store.put(SecureStore.PLEX_TOKEN, token)
                    updatePlex { it.copy(token = token, linkCode = null) }
                    connectServer(token)
                    return@launch
                }
            }
            updatePlex { it.copy(linkCode = null, error = "That link code expired. Start again.") }
        }
    }

    fun cancelPlexLink() {
        linkJob?.cancel()
        linkJob = null
        updatePlex { it.copy(linkCode = null, busy = false) }
    }

    private suspend fun connectServer(token: String) {
        updatePlex { it.copy(busy = true, error = null) }
        val servers = runCatching { PlexApi.servers(clientId, token) }.getOrElse { failure ->
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            return
        }
        if (servers.isEmpty()) {
            updatePlex {
                it.copy(
                    busy = false,
                    error = "That account can't see any Plex server. Ask the owner to share the library with it.",
                )
            }
            return
        }
        for (server in servers) {
            val base = PlexApi.firstReachable(server) ?: continue
            store.put(SecureStore.PLEX_SERVER_URI, base)
            store.put(SecureStore.PLEX_SERVER_TOKEN, server.accessToken)
            store.put(SecureStore.PLEX_SERVER_NAME, server.name)
            updatePlex {
                it.copy(
                    baseUrl = base,
                    serverToken = server.accessToken,
                    serverName = server.name,
                    busy = false,
                )
            }
            loadSections()
            return
        }
        updatePlex {
            it.copy(
                busy = false,
                error = "Found ${servers.size} server(s) but none answered. Is it awake and reachable?",
            )
        }
    }

    private suspend fun loadSections() {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return
        val token = plex.serverToken ?: return
        updatePlex { it.copy(busy = true, error = null) }
        val sections = runCatching { PlexApi.sections(base, token) }.getOrElse { failure ->
            updatePlex { it.copy(busy = false, error = failure.readable()) }
            return
        }
        updatePlex { it.copy(busy = false, sections = sections) }

        // Open the first library of each kind so both tabs have something in them.
        for (kind in LibraryKind.entries) {
            _state.value.plex.sectionsFor(kind).firstOrNull()?.let { openSection(kind, it) }
        }
    }

    fun openSection(kind: LibraryKind, section: PlexSection) {
        viewModelScope.launch {
            val plex = _state.value.plex
            val base = plex.baseUrl ?: return@launch
            val token = plex.serverToken ?: return@launch
            val path = "/library/sections/${section.key}/all"
            updateBrowse(kind) {
                it.copy(
                    busy = true,
                    error = null,
                    section = section,
                    trail = listOf(Crumb(section.title, path)),
                    items = emptyList(),
                )
            }
            loadItems(kind, base, token, path)
        }
    }

    fun openItem(kind: LibraryKind, item: PlexItem) {
        viewModelScope.launch {
            val plex = _state.value.plex
            val base = plex.baseUrl ?: return@launch
            val token = plex.serverToken ?: return@launch

            val childPath = item.childPath
            if (childPath != null) {
                updateBrowse(kind) {
                    it.copy(busy = true, error = null, trail = it.trail + Crumb(item.title, childPath))
                }
                loadItems(kind, base, token, childPath)
                return@launch
            }
            if (!item.isPlayable) return@launch

            updateBrowse(kind) { it.copy(busy = true, error = null) }
            val resolved = runCatching { PlexApi.playback(base, token, item.ratingKey) }.getOrElse { failure ->
                updateBrowse(kind) { it.copy(busy = false, error = failure.readable()) }
                return@launch
            }
            if (resolved == null) {
                updateBrowse(kind) {
                    it.copy(busy = false, error = "Plex returned no playable file for \"${item.title}\".")
                }
                return@launch
            }
            updateBrowse(kind) { it.copy(busy = false) }
            _state.update {
                it.copy(
                    playback = Playback(
                        title = item.title,
                        subtitle = item.subtitle,
                        url = resolved.url,
                        isLive = false,
                        subtitles = resolved.subtitles,
                    )
                )
            }
        }
    }

    /** True when there is somewhere to go back to inside this tab. */
    fun canGoUp(kind: LibraryKind): Boolean = _state.value.plex.browseFor(kind).trail.size > 1

    fun goUp(kind: LibraryKind) {
        viewModelScope.launch {
            val plex = _state.value.plex
            val browse = plex.browseFor(kind)
            if (browse.trail.size <= 1) return@launch
            val base = plex.baseUrl ?: return@launch
            val token = plex.serverToken ?: return@launch
            val trail = browse.trail.dropLast(1)
            updateBrowse(kind) { it.copy(busy = true, trail = trail) }
            loadItems(kind, base, token, trail.last().path)
        }
    }

    private suspend fun loadItems(kind: LibraryKind, base: String, token: String, path: String) {
        val items = runCatching { PlexApi.items(base, token, path) }.getOrElse { failure ->
            updateBrowse(kind) { it.copy(busy = false, error = failure.readable()) }
            return
        }
        updateBrowse(kind) { it.copy(busy = false, items = items) }
    }

    fun signOutPlex() {
        cancelPlexLink()
        store.remove(
            SecureStore.PLEX_TOKEN,
            SecureStore.PLEX_SERVER_URI,
            SecureStore.PLEX_SERVER_TOKEN,
            SecureStore.PLEX_SERVER_NAME,
        )
        _state.update { it.copy(plex = PlexState()) }
    }

    fun plexImageUrl(thumb: String?): String? {
        val plex = _state.value.plex
        val base = plex.baseUrl ?: return null
        val token = plex.serverToken ?: return null
        return PlexApi.imageUrl(base, token, thumb)
    }

    // ---------------------------------------------------------------- Live TV

    private suspend fun restoreLive() {
        val host = store.get(SecureStore.XTREAM_HOST) ?: return
        val username = store.get(SecureStore.XTREAM_USERNAME) ?: return
        val password = store.get(SecureStore.XTREAM_PASSWORD) ?: return
        connectXtream(XtreamCredentials(host, username, password), persist = false)
    }

    fun signInXtream(host: String, username: String, password: String) {
        val base = XtreamApi.normalizeBase(host)
        if (base.isEmpty() || username.isBlank() || password.isBlank()) {
            updateLive { it.copy(error = "Host, username and password are all required.") }
            return
        }
        viewModelScope.launch {
            connectXtream(XtreamCredentials(base, username.trim(), password), persist = true)
        }
    }

    private suspend fun connectXtream(credentials: XtreamCredentials, persist: Boolean) {
        updateLive { it.copy(busy = true, error = null) }
        val account = runCatching { XtreamApi.login(credentials) }.getOrElse { failure ->
            updateLive { it.copy(busy = false, error = failure.readable()) }
            return
        }
        if (persist) {
            store.put(SecureStore.XTREAM_HOST, credentials.base)
            store.put(SecureStore.XTREAM_USERNAME, credentials.username)
            store.put(SecureStore.XTREAM_PASSWORD, credentials.password)
        }
        val categories = runCatching { XtreamApi.liveCategories(credentials) }.getOrElse { failure ->
            updateLive {
                it.copy(busy = false, credentials = credentials, account = account, error = failure.readable())
            }
            return
        }
        updateLive {
            it.copy(
                busy = false,
                credentials = credentials,
                account = account,
                categories = categories,
                error = null,
            )
        }
        categories.firstOrNull()?.let { openCategory(it) }
    }

    fun openCategory(category: XtreamCategory) {
        viewModelScope.launch {
            val credentials = _state.value.live.credentials ?: return@launch
            updateLive { it.copy(busy = true, error = null, selectedCategory = category, channels = emptyList()) }
            val channels = runCatching { XtreamApi.liveChannels(credentials, category.id) }
                .getOrElse { failure ->
                    updateLive { it.copy(busy = false, error = failure.readable()) }
                    return@launch
                }
            updateLive { it.copy(busy = false, channels = channels) }
        }
    }

    fun playChannel(index: Int) {
        val live = _state.value.live
        val credentials = live.credentials ?: return
        val channel = live.channels.getOrNull(index) ?: return
        _state.update {
            it.copy(
                playback = Playback(
                    title = channel.name,
                    subtitle = live.selectedCategory?.name,
                    url = XtreamApi.streamUrl(credentials, channel, live.format),
                    isLive = true,
                    channelIndex = index,
                    format = live.format,
                )
            )
        }
    }

    /** Channel surfing from the player — the thing that decides whether this feels like a TV app. */
    fun stepChannel(delta: Int) {
        val playback = _state.value.playback ?: return
        if (!playback.isLive) return
        val channels = _state.value.live.channels
        if (channels.isEmpty()) return
        playChannel(wrapIndex(playback.channelIndex + delta, channels.size))
    }

    /** TS and HLS behave differently on recovery; the spike exists partly to find out how. */
    fun toggleFormat() {
        val next = if (_state.value.live.format == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS
        updateLive { it.copy(format = next) }
        val playback = _state.value.playback ?: return
        if (playback.isLive) playChannel(playback.channelIndex)
    }

    fun signOutXtream() {
        store.remove(SecureStore.XTREAM_HOST, SecureStore.XTREAM_USERNAME, SecureStore.XTREAM_PASSWORD)
        _state.update { it.copy(live = LiveState()) }
    }

    // ---------------------------------------------------------------- Playback

    fun stopPlayback() {
        _state.update { it.copy(playback = null) }
    }

    fun dismissBrowseError(kind: LibraryKind) = updateBrowse(kind) { it.copy(error = null) }

    fun dismissPlexError() = updatePlex { it.copy(error = null) }

    fun dismissLiveError() = updateLive { it.copy(error = null) }

    private fun updatePlex(block: (PlexState) -> PlexState) {
        _state.update { it.copy(plex = block(it.plex)) }
    }

    private fun updateBrowse(kind: LibraryKind, block: (BrowseState) -> BrowseState) {
        updatePlex { plex -> plex.copy(browse = plex.browse + (kind to block(plex.browseFor(kind)))) }
    }

    private fun updateLive(block: (LiveState) -> LiveState) {
        _state.update { it.copy(live = block(it.live)) }
    }
}

private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.java.simpleName + " while talking to the server")
