import React, { useEffect, useRef, useState } from 'react';
import { Animated, AppState as RNAppState, Easing, View } from 'react-native';
import { useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { PlexItem, PlexOnlineSubtitle } from '../../api/plex';
import { caption } from '../../api/plex';
import { IPTV_SOURCE } from '../../api/vod';
import type { App, AppState, Playing } from '../../app/store';
import { REPORT_EVERY_MS } from '../../app/playback';
import { ask } from '../../core/http';
import { cueAt, parseSubtitles, type Cue } from '../../core/subtitles';
import { canDirect } from '../../player/canplay';
import { useEngine, VideoSurface } from '../../player/engine';
import { ActionButton, Spinner } from '../controls';
import { Press, focusWhenReady } from '../focus';
import { useBack } from '../menus';
import { sheet } from '../surfaces';
import { Text } from '../text';
import { dp, type } from '../theme';
import { Controls, PlayerClock, type PanelName, type ScrubNudge } from './controls';
import { AudioPanel, ChaptersPanel, FindSubtitlesPanel, InfoPanel, SleepPanel, SubtitlesPanel, type Finding, type Sleep } from './panels';
import { UpNextScreen, WINDOW_END, WINDOW_TOP, WINDOW_WIDTH } from './upnext';

/*
 * A film or an episode, full screen (PlayerScreen.kt, on demand). OK brings up the
 * controls; left and right with them down start a scrub on the bar, preview and all; Back
 * puts away what's open, then stops, telling Plex where it got to. Skip Intro over an
 * intro, Up Next when the credits start, as on the Fire TV. A dropped connection is picked
 * up again from where it stopped, a few times; a file the TV turns out not to play after
 * all is handed to Plex to convert, from the same point.
 */

const SEEK_STEP_MS = 10_000;
const CONTROLS_TIMEOUT_MS = 6_000;
const RECONNECT_TRIES = 3;
const RECONNECT_WAIT_MS = 3_000;
/** How near the end a connection that fails counts as the end. */
const END_GRACE_MS = 15_000;
const NOTICE_MS = 2_500;
/** How long before the end of the intro Skip Intro stops being offered. */
const INTRO_GRACE_MS = 500;

const episodeLine = (i: PlexItem) => [caption(i), i.title].filter(Boolean).join('  ·  ');

export function VodPlayer(props: { app: App; state: AppState; playing: Playing }) {
  const { app, state, playing } = props;
  const prefs = state.prefs;
  const engine = useEngine();
  const [positionMs, setPosition] = useState(playing.startMs);
  const [durationMs, setDuration] = useState(playing.item.durationMs);
  const [bufferedMs, setBuffered] = useState(0);
  const [isPlaying, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [controls, setControls] = useState(true);
  const [panel, setPanel] = useState<PanelName | null>(null);
  const [finding, setFinding] = useState<Finding | null>(null);
  const [sleep, setSleep] = useState<Sleep>(null);
  const [upNext, setUpNext] = useState<{ next: PlexItem; ended: boolean } | null>(null);
  const [nudge, setNudge] = useState<ScrubNudge | null>(null);
  const [cues, setCues] = useState<Cue[]>([]);
  const lastPress = useRef(Date.now());
  const barFocused = useRef(false);
  const skipFocused = useRef(false);
  const leaving = useRef(false);
  const drops = useRef(0);
  const stuckAt = useRef<number | null>(null);
  const introSkipped = useRef<string | null>(null);
  const creditsOffered = useRef<string | null>(null);
  const playRef = useRef<View>(null);
  const barRef = useRef<View>(null);
  const skipRef = useRef<View>(null);
  const rootRef = useRef<View>(null);
  const panelRefs = {
    subtitles: useRef<View>(null), audio: useRef<View>(null), chapters: useRef<View>(null), sleep: useRef<View>(null), info: useRef<View>(null),
  };
  const sleepRef = useRef<Sleep>(null);
  sleepRef.current = sleep;

  const key = playing.item.ratingKey;
  const playback = playing.playback;
  const queueAt = playing.queue.findIndex((q) => q.ratingKey === key);
  const previous = queueAt > 0 ? playing.queue[queueAt - 1] : null;
  const next = queueAt >= 0 ? playing.queue[queueAt + 1] ?? null : null;
  const episodes = playing.item.type === 'episode' && playing.queue.length > 1;
  const intro = playback.markers.find((m) => m.type === 'intro') ?? null;
  const credits = playback.markers.filter((m) => m.type === 'credits').sort((a, b) => b.startMs - a.startMs)[0] ?? null;
  const now = () => engine.positionMs || positionMs;
  const total = () => engine.durationMs || durationMs;

  const keepUp = () => { lastPress.current = Date.now(); };
  const showControls = () => { keepUp(); setControls(true); };

  const stop = () => {
    if (leaving.current) return;
    leaving.current = true;
    void app.stop(now(), total());
  };
  /** On to [item], the stop told to Plex first. */
  const playNext = (item: PlexItem) => {
    if (leaving.current) return;
    leaving.current = true;
    setUpNext(null);
    void app.report(now(), total(), 'stopped').then(() => app.play(item, false, canDirect, playing.queue));
  };
  const togglePlay = () => {
    keepUp();
    if (engine.paused) engine.play(); else engine.pause();
  };
  const seekTo = (ms: number) => {
    keepUp();
    const to = Math.max(0, Math.min(ms, Math.max(0, total() - 1_000)));
    engine.seek(to);
    setPosition(to);
  };

  // A new file, or the same one asked for afresh after a drop: loaded and started.
  const shownKey = useRef(key);
  useEffect(() => {
    leaving.current = false;
    if (shownKey.current !== key) {
      setUpNext(null);
      drops.current = 0;
      setPanel(null);
      showControls();
    }
    shownKey.current = key;
    setError(null);
    stuckAt.current = null;
    setBuffering(true);
    void engine.open(playing.url, playing.startMs);
  }, [playing.url, playing.attempt]);

  // Text subtitles, read once and drawn over the picture at the size and background chosen.
  const textSub = playing.textSubtitle;
  useEffect(() => {
    setCues([]);
    if (!textSub) return;
    let live = true;
    void ask(textSub.url, { timeoutMs: 30_000 })
      .then((r) => r.text())
      .then((text) => { if (live) setCues(parseSubtitles(text, textSub.codec)); })
      .catch(() => undefined);
    return () => { live = false; };
  }, [textSub?.url]);

  useEffect(() => {
    const ended = () => {
      if (leaving.current) return;
      // The sleep timer set for this episode's end ends it here.
      const following = sleepRef.current?.endOfEpisode ? null : app.nextInQueue();
      if (following) setUpNext({ next: following, ended: true });
      else stop();
    };
    const off = [
      engine.on('playing', () => { setPlaying(true); setBuffering(false); setError(null); drops.current = 0; }),
      engine.on('pause', () => {
        setPlaying(false);
        if (!leaving.current) void app.report(now(), total(), 'paused');
      }),
      engine.on('waiting', () => setBuffering(true)),
      engine.on('loadedmetadata', () => { if (engine.durationMs > 0) setDuration(engine.durationMs); }),
      engine.on('ended', ended),
      engine.onFailure((f) => {
        if (leaving.current) return;
        const at = now();
        if (f.kind === 'network') {
          // The last few seconds not arriving is the end, not a lost connection.
          const length = total();
          if (length > 0 && at >= length - END_GRACE_MS) {
            ended();
            return;
          }
          if (drops.current < RECONNECT_TRIES) {
            drops.current++;
            setError('Reconnecting…');
            setTimeout(() => { if (!leaving.current) void app.reopen(at); }, RECONNECT_WAIT_MS * drops.current);
          } else {
            stuckAt.current = at;
            setError(playing.base === IPTV_SOURCE
              ? 'Lost the connection to your IPTV provider. Press OK to try again.'
              : 'Lost the connection to your Plex server. Press OK to try again.');
          }
          return;
        }
        // The TV said it could and then couldn't: Plex converts it.
        if (app.convert(at)) return;
        setError(`This didn't play. Check the connection to your Plex server and try again. (${f.detail})`);
      }),
    ];
    return () => off.forEach((o) => o());
  }, [engine, playing.url]);

  // Where it is, read twice a second; Plex told every ten seconds while it plays.
  useEffect(() => {
    const t = setInterval(() => {
      setPosition(engine.positionMs);
      setBuffered(engine.bufferedMs);
      if (engine.durationMs > 0) setDuration(engine.durationMs);
      // They go away after a quiet spell, while something plays and nothing's open.
      if (!engine.paused && Date.now() - lastPress.current > CONTROLS_TIMEOUT_MS) setControls(false);
    }, 500);
    const report = setInterval(() => { if (!engine.paused && !leaving.current) void app.report(now(), total(), 'playing'); }, REPORT_EVERY_MS);
    return () => {
      clearInterval(t);
      clearInterval(report);
    };
  }, [engine, playing.url]);

  // Out of the app and back: the TV takes the decoder back while it's away, so it pauses
  // on leaving, tells Plex, and picks up again from there with a fresh stream.
  useEffect(() => {
    let wasPlaying = false;
    let at = 0;
    const sub = RNAppState.addEventListener('change', (next) => {
      if (next === 'background' || next === 'inactive') {
        if (leaving.current) return;
        wasPlaying = !engine.paused;
        at = now();
        engine.pause();
        void app.report(at, total(), 'paused');
      } else if (next === 'active' && wasPlaying) {
        wasPlaying = false;
        void app.reopen(at);
      }
    });
    return () => sub.remove();
  }, [engine]);

  // The sleep timer running out: out of the player, where it was saved, as Back does.
  useEffect(() => {
    if (!sleep?.atMs) return;
    const t = setTimeout(stop, Math.max(0, sleep.atMs - Date.now()));
    return () => clearTimeout(t);
  }, [sleep]);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), NOTICE_MS);
    return () => clearTimeout(t);
  }, [notice]);

  // Past the intro by itself, when Settings says so; once, so going back into it stays.
  const inIntro = !!intro && positionMs >= intro.startMs && positionMs < intro.endMs - INTRO_GRACE_MS;
  useEffect(() => {
    if (inIntro && prefs.skipIntros && intro && introSkipped.current !== key) {
      introSkipped.current = key;
      engine.seek(intro.endMs);
      setNotice('Intro skipped');
    }
  }, [inIntro]);

  // Up Next when the credits start, as in Plex; or straight on, when Settings says so. Once an episode.
  const inCredits = !!credits && positionMs >= credits.startMs;
  useEffect(() => {
    if (!inCredits || creditsOffered.current === key) return;
    creditsOffered.current = key;
    const following = app.nextInQueue();
    if (!following || sleepRef.current?.endOfEpisode) return;
    if (prefs.skipCredits) playNext(following);
    else setUpNext({ next: following, ended: false });
  }, [inCredits]);

  const prompt = inIntro ? 'intro' : inCredits && !!next && !upNext ? 'next' : null;
  const skipLabel = prompt === 'intro' ? 'Skip Intro' : prompt === 'next' ? 'Next Episode' : null;
  const skip = () => {
    keepUp();
    if (prompt === 'intro' && intro) {
      engine.seek(intro.endMs);
      setPosition(intro.endMs);
    } else if (next) playNext(next);
  };

  // The controls taken away (Up Next, or put away) take the cursor's place in them too:
  // a button that's gone isn't told it lost the cursor.
  const controlsUp = controls && !upNext;
  useEffect(() => {
    if (controlsUp) return;
    barFocused.current = false;
    skipFocused.current = false;
  }, [controlsUp]);

  // Where the cursor goes as things come and go: Up Next's Play, a panel's own choice,
  // Skip Intro, Play, or the picture itself, which takes the keys when nothing's up.
  const closedPanel = useRef<PanelName | null>(null);
  const nudging = useRef(false);
  useEffect(() => {
    if (upNext || panel) return;
    const cameFrom = closedPanel.current;
    closedPanel.current = null;
    if (nudging.current) {
      nudging.current = false;
      focusWhenReady(barRef);
      return;
    }
    if (cameFrom && controls) {
      focusWhenReady(panelRefs[cameFrom === 'find' ? 'subtitles' : cameFrom]);
      return;
    }
    if (skipLabel && !barFocused.current) focusWhenReady(skipRef);
    else if (controls && !barFocused.current) focusWhenReady(playRef);
    else if (!controls) focusWhenReady(rootRef);
  }, [controls, panel, !!upNext, !!skipLabel]);

  const startScrub = (direction: 1 | -1, stepMs: number) => {
    nudging.current = true;
    showControls();
    setNudge({ direction, stepMs, serial: (nudge?.serial ?? 0) + 1 });
    focusWhenReady(barRef);
  };
  const openPanel = (p: PanelName) => {
    keepUp();
    setPanel(p);
  };
  const closePanel = () => {
    closedPanel.current = panel;
    setPanel(null);
    setFinding(null);
    keepUp();
  };

  useTVEventHandler((event) => {
    if (event.eventKeyAction !== 0 || leaving.current) return;
    const t = event.eventType;
    if (upNext || panel) return;
    // Given up after the connection dropped: OK, or Play, tries again from there.
    if (stuckAt.current != null && (t === 'select' || t === 'playpause' || t === 'play')) {
      const at = stuckAt.current;
      stuckAt.current = null;
      drops.current = 0;
      setError('Reconnecting…');
      void app.reopen(at);
      return;
    }
    if (t === 'playpause' || t === 'play' || t === 'pause') {
      togglePlay();
      showControls();
      return;
    }
    if (t === 'fastforward' || t === 'rewind') {
      startScrub(t === 'fastforward' ? 1 : -1, 30_000);
      return;
    }
    if (!controls) {
      if (t === 'left' || t === 'right') startScrub(t === 'right' ? 1 : -1, SEEK_STEP_MS);
      else if (t === 'up' || t === 'down') showControls();
      return;
    }
    keepUp();
    // Up from the top of the controls puts them away, rather than waiting out the timeout.
    if (t === 'up' && ((barFocused.current && !skipLabel) || skipFocused.current)) setControls(false);
  });

  // Back, in order: what's open, Up Next, the controls, then out of the player.
  useBack(() => {
    if (panel) {
      closePanel();
      return true;
    }
    if (upNext) {
      if (upNext.ended) stop();
      else setUpNext(null);
      return true;
    }
    if (controls) {
      setControls(false);
      return true;
    }
    stop();
    return true;
  });

  // The picture, full screen, or shrunk into the corner while Up Next is up.
  const shrink = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(shrink, { toValue: upNext ? 1 : 0, duration: 450, easing: Easing.bezier(0.4, 0, 0.2, 1), useNativeDriver: false }).start();
  }, [!!upNext]);
  const windowHeight = (WINDOW_WIDTH * 9) / 16;
  const cue = cueAt(cues, positionMs);
  const title = playing.item.type === 'episode' ? playing.item.grandparentTitle ?? playing.item.title : playing.item.title;
  const subtitle = playing.item.type === 'episode' ? episodeLine(playing.item) : playing.item.year != null ? String(playing.item.year) : null;
  const showing = controls && !upNext;

  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#000000', zIndex: 40 }}>
      {upNext ? (
        <UpNextScreen item={upNext.next} still={app.image(upNext.next.serverBase, upNext.next.thumb ?? upNext.next.art, 1280, 720)}
          logo={null} nowTitle={subtitle ?? title} ended={upNext.ended} seconds={prefs.upNextSeconds}
          onPlay={() => playNext(upNext.next)} onDecline={() => { if (upNext.ended) stop(); else setUpNext(null); }} />
      ) : null}
      <Animated.View style={{
        position: 'absolute', zIndex: 1,
        left: shrink.interpolate({ inputRange: [0, 1], outputRange: [0, 1920 - WINDOW_END - WINDOW_WIDTH] }),
        top: shrink.interpolate({ inputRange: [0, 1], outputRange: [0, WINDOW_TOP] }),
        width: shrink.interpolate({ inputRange: [0, 1], outputRange: [1920, WINDOW_WIDTH] }),
        height: shrink.interpolate({ inputRange: [0, 1], outputRange: [1080, windowHeight] }),
      }}>
        <VideoSurface engine={engine} style={{ flex: 1 }} />
      </Animated.View>

      {/* The picture holds the cursor while nothing else is up: OK brings up the controls. */}
      {!showing && !upNext && !panel ? (
        <Press ref={rootRef} preferred={!skipLabel} onPress={showControls} style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, zIndex: 2 }}>
          {() => null}
        </Press>
      ) : null}

      {cue && !upNext ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: dp(80), right: dp(80), bottom: showing ? dp(170) : dp(48), alignItems: 'center', zIndex: 3 }}>
          <Text style={{
            color: '#FFFFFF', textAlign: 'center', fontSize: dp(26 * prefs.subtitleScale), lineHeight: dp(34 * prefs.subtitleScale), fontWeight: '500',
            textShadowColor: 'rgba(0, 0, 0, 0.9)', textShadowRadius: dp(4), textShadowOffset: { width: 0, height: dp(1) },
            backgroundColor: prefs.subtitleBackground ? 'rgba(0, 0, 0, 0.6)' : 'transparent', paddingHorizontal: prefs.subtitleBackground ? dp(10) : 0, borderRadius: dp(4),
          }}>{cue}</Text>
        </View>
      ) : null}

      {buffering && !error && !upNext ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', zIndex: 3 }}>
          <Spinner size={48} />
        </View>
      ) : null}

      {error && !upNext ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', zIndex: 4 }}>
          <View style={[sheet(), { maxWidth: dp(720), paddingHorizontal: dp(28), paddingVertical: dp(22) }]}>
            <Text style={type.body}>{error}</Text>
          </View>
        </View>
      ) : null}

      {notice && !upNext ? (
        <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: dp(27), alignItems: 'center', zIndex: 4 }}>
          <View style={[sheet(16), { paddingHorizontal: dp(22), paddingVertical: dp(14) }]}>
            <Text style={type.meta}>{notice}</Text>
          </View>
        </View>
      ) : null}

      {showing ? (
        <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, zIndex: 5 }}>
          <PlayerClock leftMs={total() > 0 ? Math.max(0, total() - positionMs) : null} />
          <Controls title={title} subtitle={subtitle} playing={isPlaying} positionMs={positionMs} durationMs={total()} bufferedMs={bufferedMs}
            canSkipBack={!!previous} canSkipForward={!!next} episodes={episodes}
            previewUrl={playback.previewUrl} chapters={playback.chapters}
            playRef={playRef} barRef={barRef} panelRefs={panelRefs}
            onBarFocus={(on) => { barFocused.current = on; if (on) skipFocused.current = false; }}
            onSeekTo={seekTo} onScrub={keepUp} nudge={nudge} onNudgeUsed={() => setNudge(null)}
            onSkip={(d) => { const to = d < 0 ? previous : next; if (to) playNext(to); }}
            onTogglePlay={togglePlay} onOpen={openPanel} sleeping={!!sleep}
            skipLabel={skipLabel} skipRef={skipRef} onSkipPrompt={skip} onSkipFocus={(on) => { skipFocused.current = on; }}
            onPress={keepUp} />
        </View>
      ) : null}

      {/* Skip Intro on its own, bottom right, with the controls away. */}
      {skipLabel && !showing && !upNext && !panel ? (
        <View style={{ position: 'absolute', right: dp(48), bottom: dp(27), zIndex: 5 }}>
          <ActionButton pressRef={skipRef} label={skipLabel} emphasised preferred onPress={skip} onFocus={() => { skipFocused.current = true; }} />
        </View>
      ) : null}

      {panel === 'subtitles' ? (
        <SubtitlesPanel streams={playback.subtitleStreams} prefs={prefs} onClose={closePanel}
          onPick={(id) => { closePanel(); void app.chooseStreams(undefined, id, now(), canDirect); }}
          onFind={playing.base !== IPTV_SOURCE ? () => {
            setPanel('find');
            setFinding({ language: 'en', results: null, error: null, adding: null });
            void app.findSubtitles('en').then((r) => setFinding((f) => (f ? { ...f, language: r.language, results: r.results, error: r.error } : f)));
          } : null}
          onSize={(step) => app.nudgeSubtitleScale(step)} onBackground={() => app.setSubtitleBackground(!prefs.subtitleBackground)} />
      ) : null}
      {panel === 'audio' ? (
        <AudioPanel streams={playback.audioStreams} onClose={closePanel}
          onPick={(id) => { closePanel(); void app.chooseStreams(id, undefined, now(), canDirect); }} />
      ) : null}
      {panel === 'chapters' ? (
        <ChaptersPanel chapters={playback.chapters} positionMs={positionMs} onClose={closePanel} onPick={(ms) => { closePanel(); seekTo(ms); }} />
      ) : null}
      {panel === 'sleep' ? (
        <SleepPanel sleep={sleep} onClose={closePanel}
          onPick={(minutes) => {
            closePanel();
            setSleep(minutes == null ? null : minutes === 0 ? { atMs: null, endOfEpisode: true } : { atMs: Date.now() + minutes * 60_000, endOfEpisode: false });
          }} />
      ) : null}
      {panel === 'info' ? <InfoPanel playing={playing} onClose={closePanel} /> : null}
      {panel === 'find' && finding ? (
        <FindSubtitlesPanel finding={finding} onClose={closePanel}
          onAdd={(s: PlexOnlineSubtitle) => {
            setFinding({ ...finding, adding: s.key });
            void app.addFoundSubtitle(s, finding.language, now(), canDirect).then((problem) => {
              if (problem) setFinding((f) => (f ? { ...f, adding: null, error: problem } : f));
              else closePanel();
            });
          }} />
      ) : null}
    </View>
  );
}

