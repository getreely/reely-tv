import Foundation

/** Something playing: what, from where, and how. */
public struct Playing: Equatable, Sendable {
    public var item: PlexItem
    public var base: String
    public var token: String
    public var playback: PlexPlayback
    /// Plex's address for the file itself, or its converted stream.
    public var url: String
    public var direct: Bool
    /// Why Plex is converting it, for Playback info.
    public var reason: String?
    public var startMs: Int
    public var sessionId: String
    /// The rest of the season, for the next episode.
    public var queue: [PlexItem]
    /// Which copy of the title.
    public var mediaIndex: Int
    /// Text subtitles the app draws itself, with the size and background from Settings.
    public var textSubtitle: PlexSubtitle?
}

func randomHex(_ count: Int) -> String {
    String((0..<count).map { _ in "0123456789abcdef".randomElement()! })
}

extension ReelyStore {
    /// Plex's conversion, at the quality chosen in Settings.
    func converted(_ base: String, _ token: String, ratingKey: String, sessionId: String, mediaIndex: Int, subtitles: String, videoCodec: String?) -> String {
        let kbps = prefs.maxBitrateKbps
        let resolution = kbps >= 20_000 ? "3840x2160" : (kbps == 0 || kbps >= 8_000) ? "1920x1080" : "1280x720"
        return api.transcodeUrl(base, token, ratingKey: ratingKey, sessionId: sessionId, maxBitrateKbps: kbps, resolution: resolution,
                                mediaIndex: mediaIndex, subtitles: subtitles, subtitleSize: Int((prefs.subtitleScale * 100).rounded()), videoCodec: videoCodec)
    }

    /// A film or episode, from where it was left (or the top), with the rest of its season queued after it.
    public func play(_ item: PlexItem, resume: Bool = true, queue: [PlexItem] = [], mediaIndex: Int = 0) async {
        if item.isIptv { playIptv(item, resume: resume, queue: queue); return }
        opening = item
        defer { opening = nil }
        guard let base = plex.baseFor(item.serverBase), let token = plex.tokenFor(item.serverBase) else {
            playError = "Couldn't reach the server this is on."
            return
        }
        do {
            var found = try await api.playback(base, token, ratingKey: item.ratingKey, mediaIndex: mediaIndex)
            if found == nil, await relocateServers(), let b = plex.baseFor(item.serverBase), let t = plex.tokenFor(item.serverBase) {
                found = try await api.playback(b, t, ratingKey: item.ratingKey, mediaIndex: mediaIndex)
            }
            guard let raw = found else { throw HttpError("That file isn't on the server any more.") }
            startPlaying(item, base: plex.baseFor(item.serverBase) ?? base, token: plex.tokenFor(item.serverBase) ?? token,
                         playback: raw, resume: resume, queue: queue, mediaIndex: mediaIndex)
        } catch {
            // The server not answering where it was: looked for, and asked again where it is now.
            if await relocateServers(), let b = plex.baseFor(item.serverBase), let t = plex.tokenFor(item.serverBase),
               let raw = try? await api.playback(b, t, ratingKey: item.ratingKey, mediaIndex: mediaIndex) {
                startPlaying(item, base: b, token: t, playback: raw, resume: resume, queue: queue, mediaIndex: mediaIndex)
                return
            }
            playError = (error as? HttpError)?.message ?? "Couldn't play that. Try again."
        }
    }

    private func startPlaying(_ item: PlexItem, base: String, token: String, playback raw: PlexPlayback, resume: Bool, queue: [PlexItem], mediaIndex: Int) {
        let playback = atStart(raw, prefs.subtitlesAtStart)
        let sessionId = randomHex(12)
        // Text subtitles the app draws over the file as it is; picture ones (PGS) only Plex's conversion can put in.
        let (text, burn) = subtitlePlan(playback)
        var plan = PlaybackPlan.plan(playback, mode: prefs.playbackMode)
        if burn && prefs.playbackMode != .direct { plan = PlaybackPlan(direct: false, reason: "Plex draws these subtitles into the picture") }
        let url = plan.direct ? playback.url
            : converted(base, token, ratingKey: item.ratingKey, sessionId: sessionId, mediaIndex: mediaIndex, subtitles: text != nil ? "none" : "burn", videoCodec: playback.videoCodec)
        let nearEnd = item.durationMs > 0 && Double(item.viewOffsetMs) >= Double(item.durationMs) * 0.95
        let startMs = resume && item.viewOffsetMs > 0 && !nearEnd ? item.viewOffsetMs : 0
        playError = nil
        playing = Playing(item: item, base: base, token: token, playback: playback, url: url, direct: plan.direct, reason: plan.reason,
                          startMs: startMs, sessionId: sessionId, queue: queue, mediaIndex: mediaIndex, textSubtitle: text)
    }

    /// The file wouldn't play as it is: Plex converts it instead, from where it had got to.
    @discardableResult
    public func convert(positionMs: Int) -> Bool {
        // The provider's files have no Plex to convert them.
        guard var p = playing, p.direct, !p.item.isIptv else { return false }
        p.url = converted(p.base, p.token, ratingKey: p.item.ratingKey, sessionId: p.sessionId, mediaIndex: p.mediaIndex,
                          subtitles: p.textSubtitle != nil ? "none" : "burn", videoCodec: p.playback.videoCodec)
        p.direct = false
        p.reason = "This device couldn't play the file as it is"
        p.startMs = positionMs
        playing = p
        return true
    }

    /**
     * Another sound track or subtitles, kept with Plex as the Fire TV keeps them, playing on
     * from [positionMs]. Nil leaves that one as it is; subtitles "0" turns them off.
     */
    public func chooseStreams(audioId: String?, subtitleId: String?, positionMs: Int) async {
        guard let p = playing else { return }
        if let part = p.playback.partId { _ = await api.selectStream(p.base, p.token, partId: part, audioStreamId: audioId, subtitleStreamId: subtitleId) }
        var playback = p.playback
        if let audioId { playback.audioStreams = playback.audioStreams.map { var s = $0; s.selected = s.id == audioId; return s } }
        if let subtitleId { playback.subtitleStreams = playback.subtitleStreams.map { var s = $0; s.selected = s.id == subtitleId; return s } }
        guard playing?.sessionId == p.sessionId else { return }
        let (text, burn) = subtitlePlan(playback)
        let otherSound = playback.audioStreams.count > 1 && !(playback.audioStreams.first?.selected ?? true) && playback.audioStreams.contains(where: \.selected)
        let mode = prefs.playbackMode
        let asIs = mode == .transcode ? false : !burn && !otherSound && (mode == .direct || PlaybackPlan.plan(playback, mode: mode).direct)
        if !p.direct { await api.stopTranscode(p.base, p.token, sessionId: p.sessionId) }
        // Only text subtitles changed, and the file plays as it is either way: the words change over the picture.
        if asIs && p.direct && playback.url == p.url {
            var next = p
            next.playback = playback
            next.textSubtitle = text
            playing = next
            return
        }
        let sessionId = randomHex(12)
        var next = p
        next.playback = playback
        next.url = asIs ? playback.url : converted(p.base, p.token, ratingKey: p.item.ratingKey, sessionId: sessionId, mediaIndex: p.mediaIndex,
                                                    subtitles: text != nil ? "none" : "burn", videoCodec: playback.videoCodec)
        next.direct = asIs
        next.reason = asIs ? nil : (burn ? "Plex draws these subtitles into the picture" : "Plex is changing the sound track")
        next.startMs = positionMs
        next.sessionId = sessionId
        next.textSubtitle = text
        playing = next
    }

    /// Where playback is, told to the server: what keeps Continue Watching right everywhere.
    public func report(positionMs: Int, durationMs: Int, state: String, _ which: Playing? = nil) async {
        guard let p = which ?? playing, p.item.type != "clip" else { return }
        if p.item.isIptv { noteIptvProgress(p.item, positionMs: positionMs, durationMs: durationMs); return }
        await api.reportTimeline(p.base, p.token, ratingKey: p.item.ratingKey, positionMs: positionMs,
                                 durationMs: durationMs > 0 ? durationMs : p.item.durationMs, state: state, sessionId: p.sessionId)
    }

    public func stop(positionMs: Int, durationMs: Int) async {
        guard let p = playing else { return }
        playing = nil
        await report(positionMs: positionMs, durationMs: durationMs, state: "stopped", p)
        if !p.direct { await api.stopTranscode(p.base, p.token, sessionId: p.sessionId) }
        if p.item.type == "clip" { return }
        if p.item.isIptv { composeHome() } else { await refreshHome() }
        if detail != nil { await refreshDetail(watched: p.item) }
    }

    /// The episode after this one in its season, if there is one.
    public var nextInQueue: PlexItem? {
        guard let p = playing, let at = p.queue.firstIndex(where: { $0.ratingKey == p.item.ratingKey }), p.queue.indices.contains(at + 1) else { return nil }
        return p.queue[at + 1]
    }

    /// On to the next episode: this one told as stopped where it got to, the next from the top.
    public func playNext(positionMs: Int, durationMs: Int) async {
        guard let p = playing, let next = nextInQueue else { return }
        await report(positionMs: positionMs, durationMs: durationMs, state: "stopped", p)
        if !p.direct { await api.stopTranscode(p.base, p.token, sessionId: p.sessionId) }
        await play(next, resume: false, queue: p.queue)
    }

    public func dismissPlayError() { playError = nil }

    /// Subtitles online for what's playing, in the device's language, found by the Plex server.
    public func findSubtitles(language: String) async -> (results: [PlexOnlineSubtitle], error: String?) {
        guard let p = playing else { return ([], "Nothing's playing.") }
        do { return (try await api.searchSubtitles(p.base, p.token, ratingKey: p.item.ratingKey, language: language), nil) }
        catch { return ([], "Couldn't look for subtitles. Try again.") }
    }

    /// Has the server fetch [subtitle] and add it to the file, then plays on with it.
    public func addFoundSubtitle(_ subtitle: PlexOnlineSubtitle, language: String, positionMs: Int) async -> String? {
        guard let p = playing else { return nil }
        let added = await api.addSubtitle(p.base, p.token, ratingKey: p.item.ratingKey, subtitle: subtitle, language: language)
        let fresh = added ? (try? await api.playback(p.base, p.token, ratingKey: p.item.ratingKey, mediaIndex: p.mediaIndex)) ?? nil : nil
        let before = Set(p.playback.subtitleStreams.map(\.id))
        guard let fresh, let newOne = fresh.subtitleStreams.first(where: { !before.contains($0.id) }), playing?.sessionId == p.sessionId else {
            return "Plex couldn't add those subtitles. Try another."
        }
        var now = playing!
        now.playback.subtitles = fresh.subtitles
        now.playback.subtitleStreams = fresh.subtitleStreams
        playing = now
        await chooseStreams(audioId: nil, subtitleId: newOne.id, positionMs: positionMs)
        return nil
    }

    /// The trailer for the page open, from the start.
    public func playTrailer() async {
        guard let page = detail, let trailer = page.trailers.first else { return }
        await play(PlexItem(ratingKey: trailer.ratingKey, title: trailer.title, type: "clip", durationMs: trailer.durationMs, serverBase: page.serverBase), resume: false)
    }
}
