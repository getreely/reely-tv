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
    /// How many times it's been asked for afresh after the connection dropped; see reopen.
    public var attempt = 0
}

func randomHex(_ count: Int) -> String {
    String((0..<count).map { _ in "0123456789abcdef".randomElement()! })
}

extension ReelyStore {
    /// Plex's conversion, at the quality chosen in Settings.
    func converted(_ base: String, _ token: String, ratingKey: String, sessionId: String, mediaIndex: Int, subtitles: String, videoCodec: String?,
                   streams: PlexPlayback? = nil) -> String {
        let kbps = prefs.maxBitrateKbps
        let resolution = kbps >= 20_000 ? "3840x2160" : (kbps == 0 || kbps >= 8_000) ? "1920x1080" : "1280x720"
        return api.transcodeUrl(base, token, ratingKey: ratingKey, sessionId: sessionId, maxBitrateKbps: kbps, resolution: resolution,
                                mediaIndex: mediaIndex, subtitles: subtitles, subtitleSize: Int((prefs.subtitleScale * 100).rounded()), videoCodec: videoCodec,
                                audioStreamId: streams?.audioStreams.first(where: \.selected)?.id,
                                // "0" is none: Plex draws nothing in rather than what it had before.
                                subtitleStreamId: streams.map { $0.subtitleStreams.first(where: \.selected)?.id ?? "0" })
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
            await queueRestOfShow()
        } catch {
            // The server not answering where it was: looked for, and asked again where it is now.
            if await relocateServers(), let b = plex.baseFor(item.serverBase), let t = plex.tokenFor(item.serverBase),
               let raw = try? await api.playback(b, t, ratingKey: item.ratingKey, mediaIndex: mediaIndex) {
                startPlaying(item, base: b, token: t, playback: raw, resume: resume, queue: queue, mediaIndex: mediaIndex)
                await queueRestOfShow()
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
            : converted(base, token, ratingKey: item.ratingKey, sessionId: sessionId, mediaIndex: mediaIndex, subtitles: burn ? "burn" : "none", videoCodec: playback.videoCodec, streams: playback)
        let nearEnd = item.durationMs > 0 && Double(item.viewOffsetMs) >= Double(item.durationMs) * 0.95
        let startMs = resume && item.viewOffsetMs > 0 && !nearEnd ? item.viewOffsetMs : 0
        playError = nil
        playing = Playing(item: item, base: base, token: token, playback: playback, url: url, direct: plan.direct, reason: plan.reason,
                          startMs: startMs, sessionId: sessionId, queue: queue, mediaIndex: mediaIndex, textSubtitle: text)
    }

    /**
     * An episode started from Home, Continue Watching, search or a poster's menu came with
     * nothing after it, and the last of a season had nothing either: so no Up Next, and the
     * player simply closed at the end. The whole show is asked for instead, every season in
     * order, as the Fire TV's Up Next goes on into the next season.
     */
    func queueRestOfShow() async {
        guard let p = playing, p.item.type == "episode", nextInQueue == nil, let show = p.item.grandparentRatingKey,
              let episodes = try? await api.episodes(p.base, p.token, of: show),
              episodes.contains(where: { $0.ratingKey == p.item.ratingKey }) else { return }
        // Something else started meanwhile.
        guard playing?.sessionId == p.sessionId else { return }
        playing?.queue = episodes
    }

    /// The file wouldn't play as it is: Plex converts it instead, from where it had got to.
    @discardableResult
    public func convert(positionMs: Int) -> Bool {
        // The provider's files have no Plex to convert them.
        guard var p = playing, p.direct, !p.item.isIptv else { return false }
        p.url = converted(p.base, p.token, ratingKey: p.item.ratingKey, sessionId: p.sessionId, mediaIndex: p.mediaIndex,
                          subtitles: subtitlePlan(p.playback).burn ? "burn" : "none", videoCodec: p.playback.videoCodec, streams: p.playback)
        p.direct = false
        p.reason = "This device couldn't play the file as it is"
        p.startMs = positionMs
        playing = p
        return true
    }

    /**
     * The connection dropped part-way. The same address again is no use when Plex has
     * ended the session behind it — every try fails the same way, while Plex's own app
     * carries on — so the title is asked for again as a new session, through the server's
     * current address (looked for again if it doesn't answer), the same way it was
     * playing, from [positionMs]. The provider's files are simply loaded again.
     */
    public func reopen(positionMs: Int) async {
        guard let p = playing else { return }
        var next = p
        next.startMs = positionMs
        next.attempt += 1
        if !p.item.isIptv {
            var base = p.base, token = p.token
            var found = try? await api.playback(base, token, ratingKey: p.item.ratingKey, mediaIndex: p.mediaIndex)
            if found == nil, await relocateServers(), let b = plex.baseFor(p.item.serverBase), let t = plex.tokenFor(p.item.serverBase) {
                base = b
                token = t
                found = try? await api.playback(b, t, ratingKey: p.item.ratingKey, mediaIndex: p.mediaIndex)
            }
            if !p.direct { await api.stopTranscode(p.base, p.token, sessionId: p.sessionId) }
            // Something else started meanwhile.
            guard playing?.sessionId == p.sessionId else { return }
            let sessionId = randomHex(12)
            next.base = base
            next.token = token
            next.sessionId = sessionId
            next.url = p.direct ? found?.url ?? p.url
                : converted(base, token, ratingKey: p.item.ratingKey, sessionId: sessionId, mediaIndex: p.mediaIndex,
                            subtitles: subtitlePlan(p.playback).burn ? "burn" : "none", videoCodec: p.playback.videoCodec, streams: p.playback)
        }
        guard playing?.sessionId == p.sessionId else { return }
        playing = next
    }

    /**
     * Another sound track or subtitles, kept with Plex as the Fire TV keeps them, playing on
     * from [positionMs]. Nil leaves that one as it is; subtitles "0" turns them off.
     */
    public func chooseStreams(audioId: String?, subtitleId: String?, positionMs: Int) async {
        guard var p = playing else { return }
        func chosen(_ given: PlexPlayback) -> PlexPlayback {
            var playback = given
            if let audioId { playback.audioStreams = playback.audioStreams.map { var s = $0; s.selected = s.id == audioId; return s } }
            if let subtitleId { playback.subtitleStreams = playback.subtitleStreams.map { var s = $0; s.selected = s.id == subtitleId; return s } }
            return playback
        }
        // The choice shows at once — ticked in the panel, named in Playback info — while the
        // stream is changed behind it.
        p.playback = chosen(p.playback)
        playing = p
        if let part = p.playback.partId { _ = await api.selectStream(p.base, p.token, partId: part, audioStreamId: audioId, subtitleStreamId: subtitleId) }
        /*
         * Then read the title again, as starting it afresh does — which is how a choice made
         * part-way through used to take effect only after leaving and coming back. The
         * choice is laid over what comes back, in case the server hasn't caught up.
         */
        let fresh = p.item.isIptv ? nil : try? await api.playback(p.base, p.token, ratingKey: p.item.ratingKey, mediaIndex: p.mediaIndex)
        let playback = chosen(fresh ?? p.playback)
        guard playing?.sessionId == p.sessionId else { return }
        let (text, burn) = subtitlePlan(playback)
        let otherSound = playback.audioStreams.count > 1 && !(playback.audioStreams.first?.selected ?? true) && playback.audioStreams.contains(where: \.selected)
        let mode = prefs.playbackMode
        let asIs = mode == .transcode ? false : !burn && !otherSound && (mode == .direct || PlaybackPlan.plan(playback, mode: mode).direct)
        // Only text subtitles changed, and the file plays as it is either way: the words change over the picture.
        if asIs && p.direct && playback.url == p.url {
            var next = p
            next.playback = playback
            next.textSubtitle = text
            playing = next
            return
        }
        /*
         * Otherwise as leaving and starting again does, since that's what worked: the old
         * conversion stopped first, a moment for Plex to let go of it — a new one asked for
         * at once could be handed the old one's picture, subtitles and all — and then a new
         * one from where it got to. The player isn't to take the old one stopping under it
         * for a dropped connection.
         */
        replacingStream = true
        defer { replacingStream = false }
        /*
         * The player lets go of the old stream first (it watches replacingStream): while it
         * was still asking for pieces of it, Plex kept that conversion going and handed it
         * to the new request too, subtitles as they were.
         */
        try? await Task.sleep(nanoseconds: 300_000_000)
        /*
         * And the old one told as stopped, as leaving the player tells it. Until it hears
         * that, Plex keeps the sitting going and serves the new conversion the old one's
         * subtitles: a new stream, a moment's black, and the same words on it.
         */
        await report(positionMs: positionMs, durationMs: p.item.durationMs, state: "stopped", p)
        if !p.direct {
            await api.stopTranscode(p.base, p.token, sessionId: p.sessionId)
        }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        guard playing?.sessionId == p.sessionId else { return }
        let sessionId = randomHex(12)
        var next = p
        next.playback = playback
        next.url = asIs ? playback.url : converted(p.base, p.token, ratingKey: p.item.ratingKey, sessionId: sessionId, mediaIndex: p.mediaIndex,
                                                    subtitles: burn ? "burn" : "none", videoCodec: playback.videoCodec, streams: playback)
        next.direct = asIs
        next.reason = asIs ? nil : (burn ? "Plex draws these subtitles into the picture" : "Plex is changing the sound track")
        next.startMs = positionMs
        next.sessionId = sessionId
        next.textSubtitle = text
        next.attempt += 1
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
