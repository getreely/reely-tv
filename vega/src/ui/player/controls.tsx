import React, { useEffect, useRef, useState } from 'react';
import { Image, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { PlexChapter } from '../../api/plex';
import { ActionButton } from '../controls';
import { Press, useAccent } from '../focus';
import { ChaptersGlyph, InfoGlyph, MoonGlyph, PauseGlyph, PlayGlyph, PlusGlyph, RestartGlyph, SkipGlyph, SpeakerGlyph, SubtitleGlyph } from '../glyphs';
import { Gradient, TransportButton } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';

/*
 * The player's controls (PlayerScreen.kt's Controls, Scrubber and PlayerClock): the bar,
 * then one row with what's playing on the left, the transport in the middle and the
 * options on the right; the time and when it ends at the top right.
 */

const SEEK_STEP_MS = 10_000;
/** How long after the last press the scrub is taken as where to go. */
const SCRUB_SETTLE_MS = 700;
const SCRUB_THUMB = dp(14);
const PREVIEW_WIDTH = dp(240);
/** The transport's smaller buttons: the skips and the options. */
const SMALL_BUTTON = 36;

export function clock(ms: number): string {
  if (ms <= 0) return '0:00';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const two = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${m}:${two(s)}`;
}

export function clockTime(at: number): string {
  const d = new Date(at);
  const h = d.getHours();
  return `${h % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}

/** How far one press of left or right moves the scrub, by how many came in a row. */
export function scrubStep(repeats: number): number {
  return repeats < 6 ? SEEK_STEP_MS : repeats < 20 ? 30_000 : 60_000;
}

/** Which chapter [positionMs] falls in: the last to have started by then. */
export function currentChapter(chapters: PlexChapter[], positionMs: number): number {
  let at = 0;
  chapters.forEach((c, i) => { if (c.startMs <= positionMs) at = i; });
  return at;
}

/** A scrub started from outside the bar: left or right with the controls down. */
export interface ScrubNudge { direction: 1 | -1; stepMs: number; serial: number }

export type PanelName = 'subtitles' | 'audio' | 'chapters' | 'sleep' | 'info' | 'find';

/**
 * Position, buffer and remaining time, and the only way to scrub. Left and right move
 * the scrub while it holds the cursor, faster the longer they're held; it goes there a
 * moment after the presses stop, or on OK. OK with no scrub plays or pauses.
 */
function Scrubber(props: {
  positionMs: number; durationMs: number; bufferedMs: number;
  barRef: React.RefObject<View | null>;
  onFocusChange: (focused: boolean) => void;
  onSeekTo: (ms: number) => void;
  onTogglePlay: () => void;
  onScrub: () => void;
  previewUrl: string | null;
  chapters: PlexChapter[];
  nudge: ScrubNudge | null;
  onNudgeUsed: () => void;
  /** The furthest the bar goes: now, live or behind it. */
  limitMs?: number | null;
}) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const [target, setTarget] = useState<number | null>(null);
  const [width, setWidth] = useState(1);
  const targetRef = useRef<number | null>(null);
  targetRef.current = target;
  const commit = useRef<ReturnType<typeof setTimeout> | null>(null);
  const repeats = useRef({ at: 0, direction: 0, count: 0 });
  const total = Math.max(1, props.durationMs);
  const furthest = Math.max(0, props.limitMs != null ? Math.min(props.limitMs, props.durationMs) : props.durationMs - 1_000);

  const settle = (to: number) => {
    if (commit.current) clearTimeout(commit.current);
    commit.current = setTimeout(() => {
      props.onSeekTo(to);
      setTarget(null);
    }, SCRUB_SETTLE_MS);
  };
  const step = (direction: 1 | -1, stepMs?: number) => {
    props.onScrub();
    const now = Date.now();
    const r = repeats.current;
    r.count = now - r.at < 400 && r.direction === direction ? r.count + 1 : 0;
    r.at = now;
    r.direction = direction;
    const next = Math.max(0, Math.min(furthest, (targetRef.current ?? props.positionMs) + direction * (stepMs ?? scrubStep(r.count))));
    setTarget(next);
    settle(next);
  };
  useEffect(() => () => { if (commit.current) clearTimeout(commit.current); }, []);

  // The press that brought the controls up is the first step of the scrub, used once.
  useEffect(() => {
    if (!props.nudge) return;
    props.onNudgeUsed();
    step(props.nudge.direction, props.nudge.stepMs);
  }, [props.nudge?.serial]);

  useTVEventHandler((event) => {
    if (!focused || event.eventKeyAction !== 0) return;
    if (event.eventType === 'left') step(-1);
    if (event.eventType === 'right') step(1);
  });

  const shown = target ?? props.positionMs;
  const played = Math.max(0, Math.min(1, shown / total));
  const buffered = Math.max(0, Math.min(1, props.bufferedMs / total));
  const track = focused ? dp(8) : dp(5);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(14) }}>
      <Text style={[type.label, { color: color.chalk, fontVariant: ['tabular-nums'] }]}>{clock(shown)}</Text>
      {/* The bar keeps the cursor: left and right are the scrub's, not the way out. */}
      <TVFocusGuideView trapFocusLeft trapFocusRight style={{ flex: 1 }}>
        <Press ref={props.barRef} focusKey="bar"
          onFocusChange={(on) => {
            // Leaving the bar mid-scrub still goes where it was taken.
            if (!on && targetRef.current != null) {
              if (commit.current) clearTimeout(commit.current);
              props.onSeekTo(targetRef.current);
              setTarget(null);
            }
            setFocused(on);
            props.onFocusChange(on);
          }}
          onPress={() => {
            const to = targetRef.current;
            if (to != null) {
              if (commit.current) clearTimeout(commit.current);
              props.onSeekTo(to);
              setTarget(null);
            } else props.onTogglePlay();
          }}
          style={{ height: SCRUB_THUMB, justifyContent: 'center' }}>
          {() => (
            <View onLayout={(e) => setWidth(e.nativeEvent.layout.width)} style={{ height: SCRUB_THUMB, justifyContent: 'center' }}>
              <View style={{ height: track, borderRadius: track / 2, overflow: 'hidden', backgroundColor: focused ? 'rgba(242, 244, 247, 0.28)' : 'rgba(242, 244, 247, 0.2)' }}>
                <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${buffered * 100}%`, backgroundColor: 'rgba(242, 244, 247, 0.22)' }} />
                <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${played * 100}%`, borderRadius: track / 2, backgroundColor: accent.color }} />
              </View>
              {focused ? (
                <View style={{
                  position: 'absolute', width: SCRUB_THUMB, height: SCRUB_THUMB, borderRadius: SCRUB_THUMB / 2, backgroundColor: color.chalk,
                  left: Math.max(0, Math.min(width - SCRUB_THUMB, width * played - SCRUB_THUMB / 2)),
                }} />
              ) : null}
              {focused && target != null ? (
                <ScrubPreview atMs={target} previewUrl={props.previewUrl} chapters={props.chapters} centreX={width * played} barWidth={width} />
              ) : null}
            </View>
          )}
        </Press>
      </TVFocusGuideView>
      <Text style={[type.label, { color: color.muted, fontVariant: ['tabular-nums'] }]}>{'−' + clock(Math.max(0, props.durationMs - shown))}</Text>
    </View>
  );
}

/**
 * The picture at the scrub point, above the bar, as Plex's own player shows it: the
 * server's preview for that moment, or the chapter's, and the time under it. Just the
 * time, in a pill, when there's neither.
 */
function ScrubPreview(props: { atMs: number; previewUrl: string | null; chapters: PlexChapter[]; centreX: number; barWidth: number }) {
  const withPictures = props.chapters.filter((c) => c.thumbUrl);
  const chapterPicture = !props.previewUrl && withPictures.length ? withPictures[currentChapter(withPictures, props.atMs)]?.thumbUrl ?? null : null;
  // To the nearest two seconds, the spacing Plex makes them at, so coming back past the
  // same moment finds the picture already loaded.
  const picture = props.previewUrl ? props.previewUrl.replace('{ms}', String(Math.floor(props.atMs / 2_000) * 2_000)) : chapterPicture;
  const width = picture ? PREVIEW_WIDTH : dp(84);
  const height = picture ? (PREVIEW_WIDTH * 9) / 16 + dp(30) : dp(30);
  const left = Math.max(0, Math.min(props.barWidth - width, props.centreX - width / 2));
  return (
    <View pointerEvents="none" style={{
      position: 'absolute', left, top: -(height + dp(18)), width, height, borderRadius: dp(10), overflow: 'hidden',
      backgroundColor: 'rgba(24, 27, 32, 0.95)', borderWidth: dp(1), borderColor: 'rgba(242, 244, 247, 0.18)',
    }}>
      {picture ? <Image source={{ uri: picture }} resizeMode="cover" style={{ width: '100%', aspectRatio: 16 / 9, backgroundColor: color.ink }} /> : null}
      <View style={{ height: dp(30), alignItems: 'center', justifyContent: 'center' }}>
        <Text style={[type.label, { color: color.chalk, fontWeight: '600', fontVariant: ['tabular-nums'] }]}>{clock(props.atMs)}</Text>
      </View>
    </View>
  );
}

export function Controls(props: {
  title: string; subtitle: string | null;
  playing: boolean; positionMs: number; durationMs: number; bufferedMs: number;
  canSkipBack: boolean; canSkipForward: boolean;
  /** Prev and next episode: only for an episode with others around it. */
  episodes: boolean;
  previewUrl: string | null; chapters: PlexChapter[];
  playRef: React.RefObject<View | null>; barRef: React.RefObject<View | null>;
  panelRefs: Record<'subtitles' | 'audio' | 'chapters' | 'sleep' | 'info', React.RefObject<View | null>>;
  onBarFocus: (focused: boolean) => void;
  onSeekTo: (ms: number) => void;
  onScrub: () => void;
  nudge: ScrubNudge | null; onNudgeUsed: () => void;
  onSkip: (delta: 1 | -1) => void;
  onTogglePlay: () => void;
  onOpen: (panel: PanelName) => void;
  sleeping: boolean;
  skipLabel: string | null; skipRef: React.RefObject<View | null>; onSkipPrompt: () => void; onSkipFocus: (focused: boolean) => void;
  onPress: () => void;
  /** A channel: LIVE by its name, and Multiview's button. */
  live?: boolean;
  /** Whether there's a bar: always, but for a live channel that can't be rewound. */
  showBar?: boolean;
  limitMs?: number | null;
  /** Behind live: back to the channel as it is now, where Skip Intro would sit. */
  onGoLive?: (() => void) | null;
  goLiveRef?: React.RefObject<View | null>;
  onGoLiveFocus?: (focused: boolean) => void;
  onAddChannel?: (() => void) | null;
  /** Live: the programme on now from its beginning, where the channel keeps an archive. */
  onStartOver?: (() => void) | null;
}) {
  const accent = useAccent();
  const press = (f: () => void) => () => { props.onPress(); f(); };
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}>
      <Gradient id="playerControls" vertical stops={[[0, color.ink, 0], [0.45, color.ink, 0.85], [1, color.ink, 0.97]]} />
      {/* Inside the television's safe area: 48 dp at the sides, 27 at the bottom. */}
      <View style={{ paddingHorizontal: dp(48), paddingTop: dp(20), paddingBottom: dp(24), gap: dp(12) }}>
        {/* Skip Intro, right-aligned above the bar, when there's one to offer. */}
        {props.onGoLive && !props.skipLabel ? (
          <View style={{ flexDirection: 'row', justifyContent: 'flex-end' }}>
            <ActionButton pressRef={props.goLiveRef} label="Go live" emphasised onPress={press(props.onGoLive)}
              onFocus={() => props.onGoLiveFocus?.(true)} onBlur={() => props.onGoLiveFocus?.(false)} />
          </View>
        ) : null}
        {props.skipLabel ? (
          <View style={{ flexDirection: 'row', justifyContent: 'flex-end' }}>
            <ActionButton pressRef={props.skipRef} label={props.skipLabel} emphasised compact onPress={props.onSkipPrompt} onFocus={() => props.onSkipFocus(true)} />
          </View>
        ) : null}
        {props.showBar !== false ? (
          <Scrubber positionMs={props.positionMs} durationMs={props.durationMs} bufferedMs={props.bufferedMs} barRef={props.barRef}
            onFocusChange={props.onBarFocus} onSeekTo={props.onSeekTo} onTogglePlay={props.onTogglePlay} onScrub={props.onScrub}
            previewUrl={props.previewUrl} chapters={props.chapters} nudge={props.nudge} onNudgeUsed={props.onNudgeUsed} limitMs={props.limitMs} />
        ) : null}
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          {/* What's playing, weighted as the options opposite, so the transport sits in the middle. */}
          <View style={{ flex: 1, paddingRight: dp(16) }}>
            <View style={{ flexDirection: 'row', alignItems: 'center' }}>
              {props.live ? (
                <Text style={[type.label, { color: color.ink, fontWeight: '700', letterSpacing: dp(1.2), marginRight: dp(8), borderRadius: dp(6), overflow: 'hidden', backgroundColor: accent.color, paddingHorizontal: dp(7), paddingVertical: dp(1) }]}>LIVE</Text>
              ) : null}
              <Text numberOfLines={1} style={[type.body, { fontWeight: '600', flexShrink: 1 }]}>{props.title}</Text>
            </View>
            {props.subtitle ? <Text numberOfLines={1} style={[type.label, { color: color.muted }]}>{props.subtitle}</Text> : null}
          </View>
          <TVFocusGuideView autoFocus style={{ flexDirection: 'row', alignItems: 'center', gap: dp(16) }}>
            {props.episodes ? (
              <TransportButton diameter={SMALL_BUTTON} enabled={props.canSkipBack} onPress={press(() => props.onSkip(-1))} glyph={(t) => <SkipGlyph color={t} forward={false} size={16} />} />
            ) : null}
            <TransportButton ref={props.playRef} diameter={44} filled onPress={press(props.onTogglePlay)}
              glyph={(t) => (props.playing ? <PauseGlyph color={t} size={20} /> : <PlayGlyph color={t} size={20} />)} />
            {props.episodes ? (
              <TransportButton diameter={SMALL_BUTTON} enabled={props.canSkipForward} onPress={press(() => props.onSkip(1))} glyph={(t) => <SkipGlyph color={t} forward size={16} />} />
            ) : null}
          </TVFocusGuideView>
          <View style={{ flex: 1, flexDirection: 'row', justifyContent: 'flex-end' }}>
            <TVFocusGuideView autoFocus style={{ flexDirection: 'row', alignItems: 'center', gap: dp(10) }}>
              {props.onAddChannel ? (
                <TransportButton diameter={SMALL_BUTTON} onPress={press(props.onAddChannel)} glyph={(t) => <PlusGlyph color={t} size={16} />} />
              ) : null}
              {props.chapters.length ? (
                <TransportButton ref={props.panelRefs.chapters} diameter={SMALL_BUTTON} onPress={press(() => props.onOpen('chapters'))} glyph={(t) => <ChaptersGlyph color={t} size={16} />} />
              ) : null}
              <TransportButton ref={props.panelRefs.subtitles} diameter={SMALL_BUTTON} onPress={press(() => props.onOpen('subtitles'))} glyph={(t) => <SubtitleGlyph color={t} size={16} />} />
              <TransportButton ref={props.panelRefs.audio} diameter={SMALL_BUTTON} onPress={press(() => props.onOpen('audio'))} glyph={(t) => <SpeakerGlyph color={t} size={16} />} />
              <TransportButton ref={props.panelRefs.sleep} diameter={SMALL_BUTTON} filled={props.sleeping} onPress={press(() => props.onOpen('sleep'))} glyph={(t) => <MoonGlyph color={t} size={16} />} />
              <TransportButton ref={props.panelRefs.info} diameter={SMALL_BUTTON} onPress={press(() => props.onOpen('info'))} glyph={(t) => <InfoGlyph color={t} size={16} />} />
              {props.onStartOver ? (
                <TransportButton diameter={SMALL_BUTTON} onPress={press(props.onStartOver)} glyph={(t) => <RestartGlyph color={t} size={16} />} />
              ) : null}
            </TVFocusGuideView>
          </View>
        </View>
      </View>
    </View>
  );
}

/**
 * The time, top right while the controls are up, and for a film or an episode when it
 * will finish: the two things somebody glancing at the controls late at night wants.
 */
export function PlayerClock(props: { leftMs: number | null }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(t);
  }, []);
  return (
    <View pointerEvents="none" style={{ position: 'absolute', left: 0, right: 0, top: 0 }}>
      <Gradient id="playerClock" vertical stops={[[0, color.ink, 0.6], [1, color.ink, 0]]} />
      <View style={{ paddingTop: dp(24), paddingRight: dp(48), paddingBottom: dp(56), alignItems: 'flex-end' }}>
        <Text style={{ color: color.chalk, fontSize: dp(26), lineHeight: dp(30), fontWeight: '600' }}>{clockTime(now)}</Text>
        {props.leftMs != null ? <Text style={[type.label, { color: color.muted }]}>{`Ends at ${clockTime(now + props.leftMs)}`}</Text> : null}
      </View>
    </View>
  );
}
