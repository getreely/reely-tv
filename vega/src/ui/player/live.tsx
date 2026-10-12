import React, { useEffect, useRef, useState } from 'react';
import { AppState as RNAppState, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState } from '../../app/store';
import type { PlexStream } from '../../api/plex';
import type { XtreamChannel } from '../../api/xtream';
import { isOnAt } from '../../api/xtream';
import { REFUSED, hasSpareCell, focusRects, gridRects, movedTile, nextPlace, tileOrder, withoutTile, MAX_TILES } from '../../core/multiview';
import { liveEngine, playLive, stopLive } from '../../player/live';
import { useEngine, VideoSurface, type Engine } from '../../player/engine';
import { ActionButton, Spinner } from '../controls';
import { Press, focusWhenReady } from '../focus';
import { ArrowGlyph, CrossGlyph, HeartGlyph, PlayGlyph, PlusGlyph, TilesGlyph } from '../glyphs';
import { MenuHeading, MenuItem, MenuLayer, MenuSection, useBack } from '../menus';
import { sheet } from '../surfaces';
import { Text } from '../text';
import { color, dp, SCREEN_HEIGHT, SCREEN_WIDTH, type } from '../theme';
import { timeRange } from '../screens/live';
import { Controls, PlayerClock, type PanelName, type ScrubNudge } from './controls';
import { GuideOverlay, type GuideMode } from './guide';
import { AudioPanel, SleepPanel, SubtitlesPanel, type Sleep } from './panels';
import { BACKWARD, FORWARD } from './vod';

/*
 * A channel full screen (PlayerScreen.kt, live). With the controls away, left and right
 * change channel and stay away, so a second press changes channel again; down raises the
 * guide over the picture; numbers tune a channel by its number; OK brings up the controls,
 * which are the film player's: the bar where the channel keeps an archive (back into it,
 * Go live to come back), the panels, a channel beside this one, and start over. Holding OK
 * opens the tile menu: another channel beside this one, up to four, in a grid or with the
 * one being heard large. Back puts away what's open, then a channel beside, then leaves.
 */

/** How long the controls stay up without a press. */
const CONTROLS_MS = 5_000;
const NOTICE_MS = 2_500;
/** A typed channel number tunes this long after its last digit, or on OK. */
const CHANNEL_ENTRY_MS = 2_000;
const CHANNEL_DIGITS = 5;
/** How close to now counts as live: a bar let go this near the live point stays live. */
const LIVE_SLACK_MS = 20_000;
const TILE_GAP = dp(3);
const SCREEN = { width: SCREEN_WIDTH, height: SCREEN_HEIGHT };
const NOT_PLAYING = "This channel isn't playing. It may be off air, or your provider's connection limit reached.";
const REFUSED_SAYS = "Your provider won't open this channel right now: it may be at its limit of connections. Close Reely on any other screen and try again.";
/** A dropped channel is tried this many more times before it's given up on. */
const RETRIES = 3;

const day = (epochSeconds: number) => new Date(epochSeconds * 1000).toLocaleDateString('en-US', { weekday: 'short' });

export function LivePlayer(props: { app: App; state: AppState; channel: XtreamChannel }) {
  const { app, state, channel } = props;
  const live = state.live;
  const prefs = state.prefs;
  const engine = liveEngine();
  const catchUp = live.catchUp;
  const [controls, setControls] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [positionMs, setPositionMs] = useState(0);
  const [panel, setPanel] = useState<PanelName | null>(null);
  const [sleep, setSleep] = useState<Sleep>(null);
  const [guide, setGuide] = useState<GuideMode | null>(null);
  const [tileMenu, setTileMenu] = useState<number | null>(null);
  const [zoomed, setZoomed] = useState<number | null>(null);
  const [focusedPlace, setFocusedPlace] = useState(0);
  const [order, setOrder] = useState<number[]>([0]);
  const [typed, setTyped] = useState('');
  const [nudge, setNudge] = useState<ScrubNudge | null>(null);
  const lastPress = useRef(Date.now());
  const tries = useRef(0);
  const pausedAt = useRef<number | null>(null);
  const barFocused = useRef(false);
  const goLiveFocused = useRef(false);
  const rootRef = useRef<View>(null);
  const playRef = useRef<View>(null);
  const barRef = useRef<View>(null);
  const skipRef = useRef<View>(null);
  const goLiveRef = useRef<View>(null);
  const panelRefs = {
    subtitles: useRef<View>(null), audio: useRef<View>(null), chapters: useRef<View>(null), sleep: useRef<View>(null), info: useRef<View>(null),
  };

  const keepUp = () => { lastPress.current = Date.now(); };
  const showControls = () => { keepUp(); setControls(true); };
  const leave = () => {
    stopLive();
    app.stopLive();
  };

  // ---------------------------------------------------------------- The picture

  const url = catchUp?.url ?? app.channelUrl(channel);
  useEffect(() => {
    if (!url) return;
    setFailure(null);
    setBuffering(true);
    tries.current = 0;
    pausedAt.current = null;
    playLive(url);
  }, [url]);

  useEffect(() => {
    // The shared player may be under way already, its 'playing' said before this was listening.
    if (engine.isPlaying) {
      setPlaying(true);
      setBuffering(false);
    }
    const off = [
      engine.on('playing', () => { setPlaying(true); setBuffering(false); setFailure(null); tries.current = 0; }),
      engine.on('pause', () => setPlaying(false)),
      engine.on('waiting', () => setBuffering(true)),
      engine.on('ended', () => {
        // Rewound and caught up: live again. A programme from the guide, over: back to it.
        const c = app.state.live.catchUp;
        if (c?.behindLive) app.goLive();
        else if (c) leave();
      }),
      engine.onFailure((f) => {
        // A provider at its limit says so; anything else is tried again a few times.
        if (f.status != null && REFUSED.includes(f.status)) {
          setFailure(REFUSED_SAYS);
          return;
        }
        if (tries.current < RETRIES) {
          tries.current++;
          setTimeout(() => void engine.reload(), 3_000 * tries.current);
          return;
        }
        setFailure(NOT_PLAYING);
      }),
    ];
    return () => off.forEach((o) => o());
  }, [engine]);

  useEffect(() => {
    const t = setInterval(() => {
      setNowMs(Date.now());
      setPositionMs(engine.positionMs);
      if (!panel && Date.now() - lastPress.current > CONTROLS_MS) setControls(false);
    }, 500);
    return () => clearInterval(t);
  }, [engine, panel]);

  // Out of the app and back: a channel has moved on meanwhile, so it rejoins it; a
  // programme from the archive carries on from where it was, by the clock.
  useEffect(() => {
    let at: number | null = null;
    const sub = RNAppState.addEventListener('change', (next) => {
      if (next === 'background' || next === 'inactive') {
        const c = app.state.live.catchUp;
        at = c ? c.from + Math.floor(engine.positionMs / 1000) : null;
        engine.pause();
      } else if (next === 'active') {
        if (at != null) app.timeshiftTo(at);
        else void engine.reload();
        at = null;
      }
    });
    return () => sub.remove();
  }, [engine]);

  useEffect(() => {
    if (!sleep?.atMs) return;
    const t = setTimeout(leave, Math.max(0, sleep.atMs - Date.now()));
    return () => clearTimeout(t);
  }, [sleep]);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), NOTICE_MS);
    return () => clearTimeout(t);
  }, [notice]);

  // ---------------------------------------------------------------- Rewinding

  const now = Math.floor(nowMs / 1000);
  const listing = live.table[channel.streamId] ?? live.guide[channel.streamId] ?? [];
  const onNow = listing.find((p) => isOnAt(p, now)) ?? null;
  const startOver = !catchUp ? app.startOverProgramme(now) : null;

  // ---------------------------------------------------------------- Multiview

  const tiles = catchUp ? [] : live.multiview;
  const tileCount = tiles.length + 1;
  const focusLayout = prefs.multiviewLayout === 'focus';
  const hasSpare = hasSpareCell(tileCount, focusLayout);
  const slotCount = tileCount + (hasSpare ? 1 : 0);
  const addSlot = hasSpare ? tileCount : -1;
  const places = [...tileOrder(order, tileCount), ...(hasSpare ? [addSlot] : [])];
  const place = Math.min(focusedPlace, slotCount - 1);
  const focusedId = places[place] ?? 0;
  const single = slotCount === 1;
  const shownZoom = zoomed != null && zoomed < tileCount && tileCount > 1 ? zoomed : null;
  useEffect(() => { if (zoomed != null && shownZoom == null) setZoomed(null); }, [shownZoom]);

  // The tile being looked at is the one heard.
  useEffect(() => { engine.muted = !single && focusedId !== 0; }, [engine, single, focusedId]);
  useEffect(() => () => { engine.muted = false; }, [engine]);

  // The bar: the programme on now where the channel's archive can go back into it, or the
  // one playing from the archive. Not with several channels up: that's one picture's.
  const window = single ? catchUp?.programme ?? startOver : null;
  const spanMs = window ? (window.stop - window.start) * 1000 : 0;
  const edgeMs = window ? Math.max(0, Math.min(spanMs, nowMs - window.start * 1000)) : 0;
  const barMs = !window ? 0 : catchUp ? Math.min(spanMs, (catchUp.from - window.start) * 1000 + positionMs) : edgeMs;
  const seekTo = (to: number) => {
    keepUp();
    if (!window) return;
    if (!catchUp) {
      // Let go behind now: out of the archive from there. At now: stays live.
      if (to < edgeMs - LIVE_SLACK_MS) app.timeshiftTo(window.start + Math.floor(to / 1000));
    } else if (catchUp.behindLive && to >= edgeMs - LIVE_SLACK_MS) app.goLive();
    else app.timeshiftTo(window.start + Math.floor(to / 1000));
  };

  /**
   * Play and pause. Back from a pause on live television is back to now, unless the
   * channel keeps an archive: then it carries on from the moment it was paused.
   */
  const togglePlay = () => {
    keepUp();
    if (!engine.paused) {
      engine.pause();
      if (!catchUp) pausedAt.current = Date.now();
      return;
    }
    const at = pausedAt.current;
    pausedAt.current = null;
    if (!catchUp && at != null && startOver && Date.now() - at > LIVE_SLACK_MS) {
      if (app.timeshiftTo(Math.floor(at / 1000))) return;
    }
    if (!catchUp && at != null) void engine.reload();
    else engine.play();
  };

  // ---------------------------------------------------------------- Number entry

  const tuneTyped = (digits: string) => {
    setTyped('');
    const n = Number(digits);
    if (!digits || !Number.isFinite(n)) return;
    if (!app.tuneNumber(n)) setNotice(`There's no channel ${n}.`);
  };
  useEffect(() => {
    if (!typed) return;
    const t = setTimeout(() => tuneTyped(typed), CHANNEL_ENTRY_MS);
    return () => clearTimeout(t);
  }, [typed]);
  const typedMatch = typed ? live.channels.find((c) => c.number === Number(typed)) ?? null : null;

  // ---------------------------------------------------------------- Focus

  const busy = !!(guide || panel || tileMenu != null);
  const showing = controls && single && !guide;
  const closedPanel = useRef<PanelName | null>(null);
  const nudging = useRef(false);
  useEffect(() => {
    if (showing) return;
    barFocused.current = false;
    goLiveFocused.current = false;
  }, [showing]);
  useEffect(() => {
    if (busy) return;
    const cameFrom = closedPanel.current;
    closedPanel.current = null;
    if (nudging.current) {
      nudging.current = false;
      focusWhenReady(barRef);
      return;
    }
    if (cameFrom && showing && cameFrom !== 'find') {
      focusWhenReady(panelRefs[cameFrom]);
      return;
    }
    if (showing && !barFocused.current) focusWhenReady(catchUp?.behindLive ? goLiveRef : playRef);
    else if (!showing) focusWhenReady(rootRef);
  }, [showing, busy]);

  // Into the archive or back out of it, the button pressed (Start over, Go live) goes:
  // the cursor onto Go live behind live, Play otherwise, unless it's on the bar.
  const behind = !!catchUp?.behindLive;
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    goLiveFocused.current = false;
    if (showing && !busy && !barFocused.current) focusWhenReady(behind ? goLiveRef : playRef);
  }, [!!catchUp, behind]);

  const startScrub = (direction: 1 | -1, stepMs: number) => {
    if (!window) return;
    nudging.current = true;
    showControls();
    setNudge({ direction, stepMs, serial: (nudge?.serial ?? 0) + 1 });
    focusWhenReady(barRef);
  };
  const openPanel = (p: PanelName) => { keepUp(); setPanel(p); };
  const closePanel = () => { closedPanel.current = panel; setPanel(null); keepUp(); };
  const openGuide = (mode: GuideMode) => { setControls(false); setGuide(mode); };
  const closeGuide = () => setGuide(null);

  // ---------------------------------------------------------------- Keys

  const okPressed = () => {
    keepUp();
    if (typed) {
      tuneTyped(typed);
      return;
    }
    if (single) setControls(true);
    else if (focusedId === addSlot) openGuide({ kind: 'add' });
    // Fills the screen with this one and leaves the rest running behind it.
    else setZoomed(shownZoom === focusedId ? null : focusedId);
  };
  const held = () => {
    if (focusedId !== addSlot) setTileMenu(focusedId);
  };

  useTVEventHandler((event) => {
    if (event.eventKeyAction !== 0 || busy) return;
    const t = event.eventType;
    // Numbers type a channel: one picture on screen, live, nothing open over it.
    const digit = /^num_(\d)$/.exec(t);
    if (digit) {
      if (!single || catchUp) return;
      // Steering, not the controls: they go, so OK tunes what's typed.
      setControls(false);
      setTyped((was) => (was + digit[1]).slice(0, CHANNEL_DIGITS));
      return;
    }
    if (t === 'menu') {
      held();
      return;
    }
    if (t === 'info') {
      if (single) {
        showControls();
        openPanel('info');
      }
      return;
    }
    if ((t === 'channel_up' || t === 'channel_down') && single && !catchUp) {
      app.stepChannel(t === 'channel_up' ? 1 : -1);
      return;
    }
    if (t === 'playpause' || t === 'play' || t === 'pause') {
      togglePlay();
      if (single) showControls();
      return;
    }
    if (FORWARD.includes(t) || BACKWARD.includes(t)) {
      // Back into the archive where there's one; forward from live is nowhere.
      startScrub(FORWARD.includes(t) ? 1 : -1, 30_000);
      return;
    }
    if (!single && !controls) {
      const dx = t === 'left' ? -1 : t === 'right' ? 1 : 0;
      const dy = t === 'up' ? -1 : t === 'down' ? 1 : 0;
      if (!dx && !dy) return;
      // Zoomed in, the grid isn't there to move around.
      if (shownZoom != null) return;
      const to = nextPlace(slotCount, place, dx, dy, focusLayout);
      if (to != null) setFocusedPlace(to);
      // Off the edge: down still opens the guide. Sideways doesn't retune a tile walked past.
      else if (dy > 0) openGuide({ kind: 'browse' });
      return;
    }
    if (!controls) {
      if (!catchUp) {
        // Steering live television: left and right change channel, and the controls stay away.
        if (t === 'left') app.stepChannel(-1);
        else if (t === 'right') app.stepChannel(1);
        else if (t === 'down') openGuide({ kind: 'browse' });
        else if (t === 'up') showControls();
      } else if (t === 'left' || t === 'right') startScrub(t === 'right' ? 1 : -1, 10_000);
      else if (t === 'up' || t === 'down') showControls();
      return;
    }
    keepUp();
    // Up from the top of a programme's controls puts them away.
    if (t === 'up' && catchUp && ((barFocused.current && !catchUp.behindLive) || goLiveFocused.current)) setControls(false);
  });

  // Back, in order: a number being typed, a zoomed tile, a channel beside this one, the controls, then out.
  useBack(() => {
    if (typed) {
      setTyped('');
      return true;
    }
    if (shownZoom != null) {
      setZoomed(null);
      return true;
    }
    if (focusedId >= 1 && focusedId <= tiles.length) {
      closeTile(focusedId);
      return true;
    }
    if (controls && single) {
      setControls(false);
      return true;
    }
    leave();
    return true;
  });

  const closeTile = (tile: number) => {
    const next = withoutTile(places.filter((t) => t < tileCount), tile);
    setOrder(next);
    // Onto the main channel, wherever it's been moved to.
    setFocusedPlace(Math.max(0, next.indexOf(0)));
    app.removeFromMultiview(tile - 1);
  };

  // ---------------------------------------------------------------- Drawing

  const tileName = (tile: number) => (tile === 0 ? channel.name : tiles[tile - 1]?.name ?? '');
  const rects = shownZoom != null ? null : focusLayout ? focusRects(slotCount, place, SCREEN.width, SCREEN.height, TILE_GAP) : gridRects(slotCount, SCREEN.width, SCREEN.height, TILE_GAP);
  const rectOf = (tile: number) => {
    if (single) return { left: 0, top: 0, width: SCREEN.width, height: SCREEN.height };
    if (shownZoom != null) return shownZoom === tile ? { left: 0, top: 0, width: SCREEN.width, height: SCREEN.height } : { left: 0, top: 0, width: 0, height: 0 };
    return rects![places.indexOf(tile)] ?? { left: 0, top: 0, width: 0, height: 0 };
  };
  const number = channel.number > 0 ? `${channel.number}  ` : '';
  const title = catchUp ? catchUp.programme.title : number + channel.name;
  const subtitle = catchUp ? `${channel.name}  ·  ${day(catchUp.programme.start)} ${timeRange(catchUp.programme)}` : onNow ? `${onNow.title}  ·  ${timeRange(onNow)}` : null;
  const savedLabel = app.savedMultiviewLabel();
  const menuPlace = tileMenu != null ? places.indexOf(tileMenu) : -1;

  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#000000', zIndex: 40 }}>
      <Tile rect={rectOf(0)} name={single ? null : channel.name} focused={!single && shownZoom == null && focusedId === 0}>
        <VideoSurface engine={engine} style={{ flex: 1 }} captions />
      </Tile>
      {tiles.map((c, i) => (
        <ExtraTile key={c.streamId} app={app} channel={c} rect={rectOf(i + 1)} heard={focusedId === i + 1}
          focused={shownZoom == null && focusedId === i + 1} />
      ))}
      {hasSpare && shownZoom == null ? <AddTile rect={rects![places.indexOf(addSlot)]} focused={focusedId === addSlot} /> : null}

      {/* The picture holds the cursor while the controls are away: OK brings them up, a hold the tile menu. */}
      {!showing && !busy ? (
        <TVFocusGuideView trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
          <Press ref={rootRef} preferred onPress={okPressed} onHold={held} style={{ flex: 1 }}>
            {() => null}
          </Press>
        </TVFocusGuideView>
      ) : null}

      {buffering && !failure && single ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center' }}>
          <Spinner size={48} />
        </View>
      ) : null}

      {failure ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', gap: dp(16), backgroundColor: 'rgba(8, 9, 11, 0.6)' }}>
          <Text style={[type.body, { textAlign: 'center', maxWidth: dp(640) }]}>{failure}</Text>
          <ActionButton label="Try again" emphasised preferred onPress={() => { setFailure(null); tries.current = 0; void engine.reload(); }} />
        </View>
      ) : null}

      {/* At the top, away from the transport, and gone on its own once it's tuned. */}
      {typed ? (
        <View pointerEvents="none" style={[sheet(16), { position: 'absolute', left: dp(48), top: dp(27), minWidth: dp(200), maxWidth: dp(460), paddingHorizontal: dp(22), paddingVertical: dp(14), gap: dp(2) }]}>
          <Text style={type.display}>{typed}</Text>
          <Text numberOfLines={1} style={[type.body, { color: typedMatch ? color.chalk : color.muted }]}>{typedMatch?.name ?? 'No channel with this number'}</Text>
        </View>
      ) : null}

      {notice ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: dp(27), alignItems: 'center' }}>
          <View style={[sheet(16), { paddingHorizontal: dp(22), paddingVertical: dp(14) }]}>
            <Text style={type.meta}>{notice}</Text>
          </View>
        </View>
      ) : null}

      {showing ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
          <PlayerClock leftMs={catchUp && !catchUp.behindLive ? Math.max(0, spanMs - barMs) : null} />
          <Controls title={title} subtitle={subtitle} live={!catchUp} playing={playing}
            positionMs={barMs} durationMs={spanMs} bufferedMs={edgeMs} showBar={!!window} limitMs={edgeMs}
            canSkipBack canSkipForward episodes={!catchUp}
            previewUrl={null} chapters={[]}
            playRef={playRef} barRef={barRef} panelRefs={panelRefs}
            onBarFocus={(on) => { barFocused.current = on; if (on) goLiveFocused.current = false; }}
            onSeekTo={seekTo} onScrub={keepUp} nudge={nudge} onNudgeUsed={() => setNudge(null)}
            onSkip={(d) => { keepUp(); app.stepChannel(d); }}
            onTogglePlay={togglePlay} onOpen={openPanel} sleeping={!!sleep}
            skipLabel={null} skipRef={skipRef} onSkipPrompt={() => undefined} onSkipFocus={() => undefined}
            onGoLive={catchUp?.behindLive ? () => app.goLive() : null} goLiveRef={goLiveRef}
            onGoLiveFocus={(on) => { goLiveFocused.current = on; }}
            onAddChannel={!catchUp && tileCount < MAX_TILES ? () => openGuide({ kind: 'add' }) : null}
            onStartOver={startOver ? () => { keepUp(); app.startOver(); } : null}
            onPress={keepUp} />
        </View>
      ) : null}

      {guide ? (
        <GuideOverlay app={app} state={state} mode={guide} canAddTile={tileCount < MAX_TILES} onClose={closeGuide}
          onWatch={(category, channels, index, programme) => {
            closeGuide();
            setFocusedPlace(Math.max(0, places.indexOf(0)));
            app.watchIn(category, channels, index, programme);
          }}
          onPick={(c) => {
            const mode = guide;
            closeGuide();
            if (mode.kind === 'replace') app.replaceInMultiview(mode.tile, c);
            else if (mode.kind === 'add' || tileCount < MAX_TILES) app.addToMultiview(c);
          }} />
      ) : null}

      {panel === 'subtitles' ? (
        <SubtitlesPanel prefs={prefs} onClose={closePanel}
          streams={engine.subtitleTracks.length ? liveTracks(engine.subtitleTracks, engine.subtitleTrack) : liveCaptions(engine.captionTracks, engine.captionTrack)}
          none="This channel doesn't carry subtitles."
          onPick={(id) => {
            closePanel();
            // hls.js's subtitles when the stream lists any; otherwise the channel's own closed captions.
            if (engine.subtitleTracks.length) engine.subtitleTrack = Number(id) - 1;
            else engine.captionTrack = Number(id) - 1;
          }} onFind={null}
          onSize={(step) => app.nudgeSubtitleScale(step)} onBackground={() => app.setSubtitleBackground(!prefs.subtitleBackground)} />
      ) : null}
      {panel === 'audio' ? (
        <AudioPanel streams={liveTracks(engine.audioTracks, engine.audioTrack, true)} onClose={closePanel}
          onPick={(id) => { closePanel(); engine.audioTrack = Number(id) - 1; }} />
      ) : null}
      {panel === 'sleep' ? (
        <SleepPanel live sleep={sleep} onClose={closePanel}
          onPick={(minutes) => { closePanel(); setSleep(minutes ? { atMs: Date.now() + minutes * 60_000, endOfEpisode: false } : null); }} />
      ) : null}
      {panel === 'info' ? <LiveInfoPanel engine={engine} channel={channel} archive={!!catchUp} programme={catchUp?.programme.title ?? onNow?.title ?? null} onClose={closePanel} /> : null}

      {tileMenu != null ? (
        <MenuLayer onClose={() => setTileMenu(null)}>
          <MenuHeading title={tileName(tileMenu)} />
          {slotCount > 1 ? (
            <MenuItem preferred label="Full screen" icon={(c) => <PlayGlyph color={c} size={20} />} onPress={() => { setTileMenu(null); setZoomed(tileMenu); }} />
          ) : null}
          {tileCount < MAX_TILES && !catchUp ? (
            <MenuItem preferred={slotCount === 1} label="Add another channel" icon={(c) => <PlusGlyph color={c} size={18} />} onPress={() => { setTileMenu(null); openGuide({ kind: 'add' }); }} />
          ) : null}
          <MenuItem label="Replace channel" icon={(c) => <TilesGlyph color={c} size={20} />} onPress={() => { setTileMenu(null); openGuide({ kind: 'replace', tile: tileMenu }); }} />
          {tileCount > 1 && menuPlace > 0 ? (
            <MenuItem label="Move left" icon={(c) => <ArrowGlyph color={c} left size={20} />}
              onPress={() => { setTileMenu(null); setOrder(movedTile(places.filter((t) => t < tileCount), menuPlace, -1)); setFocusedPlace(menuPlace - 1); }} />
          ) : null}
          {tileCount > 1 && menuPlace >= 0 && menuPlace < tileCount - 1 ? (
            <MenuItem label="Move right" icon={(c) => <ArrowGlyph color={c} left={false} size={20} />}
              onPress={() => { setTileMenu(null); setOrder(movedTile(places.filter((t) => t < tileCount), menuPlace, 1)); setFocusedPlace(menuPlace + 1); }} />
          ) : null}
          {tileCount > 1 || savedLabel ? <MenuSection label="Saved channels" /> : null}
          {tileCount > 1 ? (
            <MenuItem label="Save these channels" icon={(c) => <HeartGlyph color={c} size={20} />}
              onPress={() => { setTileMenu(null); app.saveMultiview(places.filter((t) => t < tileCount)); setNotice('Saved. Hold OK on a channel to open them again.'); }} />
          ) : null}
          {savedLabel ? (
            <MenuItem label="Open saved" detail={savedLabel} icon={(c) => <TilesGlyph color={c} size={20} />}
              onPress={() => { setTileMenu(null); setOrder([0]); setFocusedPlace(0); void app.openSavedMultiview(); }} />
          ) : null}
          {/* The main tile is the player itself: closing it would be closing the screen. */}
          {tileMenu >= 1 && tileMenu <= tiles.length ? (
            <MenuItem label="Close channel" icon={(c) => <CrossGlyph color={c} size={20} />} onPress={() => { const t = tileMenu; setTileMenu(null); closeTile(t); }} />
          ) : null}
        </MenuLayer>
      ) : null}
    </View>
  );
}

/** A stream's tracks as the panels list them: ids from 1, the one on marked. */
function liveTracks(tracks: Array<{ id: number; name: string; lang?: string }>, on: number, audio = false): PlexStream[] {
  return tracks.map((t, i) => ({
    id: String(i + 1),
    label: t.name || (t.lang ? t.lang.toUpperCase() : `${audio ? 'Track' : 'Subtitles'} ${i + 1}`),
    selected: i === on,
  }) as unknown as PlexStream);
}

/** A channel's own closed captions (MPEG-TS carries them in the picture), as the Subtitles panel lists them. */
function liveCaptions(tracks: Array<{ id: number; name: string; lang?: string }>, on: number): PlexStream[] {
  return tracks.map((t, i) => ({
    id: String(i + 1),
    label: t.name || (tracks.length > 1 ? `Closed captions ${i + 1}` : 'Closed captions') + (t.lang ? ` (${t.lang.toUpperCase()})` : ''),
    selected: i === on,
  }) as unknown as PlexStream);
}

/** Where a tile is drawn: only the one being heard is outlined, with its name in the corner. */
function Tile(props: { rect: { left: number; top: number; width: number; height: number }; name: string | null; focused: boolean; children: React.ReactNode }) {
  const r = props.rect;
  const hidden = r.width <= 0 || r.height <= 0;
  return (
    <View pointerEvents="none" style={{ position: 'absolute', left: r.left, top: r.top, width: hidden ? 1 : r.width, height: hidden ? 1 : r.height, opacity: hidden ? 0 : 1, backgroundColor: '#000000', overflow: 'hidden' }}>
      {props.children}
      {props.name != null ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, borderWidth: props.focused ? dp(3) : 0, borderColor: color.chalk }}>
          <View style={{ position: 'absolute', left: dp(6), bottom: dp(6), maxWidth: '80%', borderRadius: dp(5), backgroundColor: 'rgba(8, 9, 11, 0.72)', paddingHorizontal: dp(7), paddingVertical: dp(3) }}>
            <Text numberOfLines={1} style={{ color: props.focused ? color.chalk : 'rgba(242, 244, 247, 0.7)', fontSize: dp(14), lineHeight: dp(19) }}>{props.name}</Text>
          </View>
        </View>
      ) : null}
    </View>
  );
}

/** A tile's channel dropped is tried this many more times before it says so. */
const TILE_RETRIES = 3;

/**
 * A channel beside the main one, in a player of its own, heard only while it's looked at.
 * One that drops is tried again a few times; one the provider won't open, or that won't
 * play, says so on the tile rather than sitting black.
 */
function ExtraTile(props: { app: App; channel: XtreamChannel; rect: { left: number; top: number; width: number; height: number }; heard: boolean; focused: boolean }) {
  const engine: Engine = useEngine({ muted: true });
  const url = props.app.channelUrl(props.channel);
  const [failure, setFailure] = useState<string | null>(null);
  const attempts = useRef(0);
  useEffect(() => {
    if (url) void engine.open(url);
  }, [url]);
  useEffect(() => { engine.muted = !props.heard; }, [props.heard]);
  useEffect(() => {
    let retry: ReturnType<typeof setTimeout> | null = null;
    const off = [
      engine.on('playing', () => { attempts.current = 0; setFailure(null); }),
      engine.onFailure((f) => {
        if (f.status != null && REFUSED.includes(f.status)) setFailure("Your provider won't open another channel at once.");
        else if (attempts.current < TILE_RETRIES) {
          attempts.current++;
          retry = setTimeout(() => void engine.reload(), 3_000 * attempts.current);
        } else setFailure("This channel isn't playing.");
      }),
    ];
    return () => {
      off.forEach((o) => o());
      if (retry) clearTimeout(retry);
    };
  }, [engine]);
  return (
    <Tile rect={props.rect} name={props.channel.name} focused={props.focused}>
      <VideoSurface engine={engine} style={{ flex: 1 }} />
      {failure ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center' }}>
          <View style={[sheet(12), { maxWidth: dp(320), paddingHorizontal: dp(16), paddingVertical: dp(10) }]}>
            <Text style={type.meta}>{failure}</Text>
          </View>
        </View>
      ) : null}
    </Tile>
  );
}

/** The spare cell in a grid that isn't full: somewhere obvious to put another channel. */
function AddTile(props: { rect: { left: number; top: number; width: number; height: number } | undefined; focused: boolean }) {
  const r = props.rect;
  if (!r) return null;
  const tint = props.focused ? color.chalk : 'rgba(242, 244, 247, 0.5)';
  return (
    <View pointerEvents="none" style={{
      position: 'absolute', left: r.left, top: r.top, width: r.width, height: r.height, alignItems: 'center', justifyContent: 'center', gap: dp(8),
      backgroundColor: 'rgba(8, 9, 11, 0.6)', borderWidth: props.focused ? dp(3) : dp(1), borderColor: props.focused ? color.chalk : 'rgba(242, 244, 247, 0.22)',
    }}>
      <PlusGlyph color={tint} size={34} />
      <Text style={[type.label, { color: tint }]}>Add a channel</Text>
    </View>
  );
}

/** What's playing and how, for a channel: said plainly, nothing to press but Close. */
function LiveInfoPanel(props: { engine: Engine; channel: XtreamChannel; archive: boolean; programme: string | null; onClose: () => void }) {
  const size = props.engine.videoSize;
  const tracks = props.engine.audioTracks;
  const sound = tracks[props.engine.audioTrack]?.name ?? (tracks.length ? tracks[0].name : null);
  const rows: Array<[string, string | null]> = [
    ['Quality', props.archive ? 'From the archive' : 'Live'],
    ['Channel', props.channel.name],
    ['On now', props.programme],
    ['Picture', size ? `${size.width} × ${size.height}${size.height >= 2000 ? '  ·  4K' : size.height >= 1000 ? '  ·  HD' : ''}` : null],
    ['Sound', sound],
  ];
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuHeading title="Playback info" />
      <View style={{ gap: dp(14), paddingLeft: dp(4) }}>
        {rows.filter(([, v]) => v).map(([label, value]) => (
          <View key={label} style={{ flexDirection: 'row', justifyContent: 'space-between', gap: dp(16) }}>
            <Text style={type.meta}>{label}</Text>
            <Text numberOfLines={1} style={[type.meta, { color: color.chalk, flexShrink: 1, textAlign: 'right' }]}>{value}</Text>
          </View>
        ))}
      </View>
      <View style={{ height: dp(16) }} />
      <MenuItem label="Close" preferred onPress={props.onClose} />
    </MenuLayer>
  );
}
