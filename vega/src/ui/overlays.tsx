import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Image, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import Svg, { Circle, Path, Rect } from '@amazon-devices/react-native-svg';
import type { PlexHomeUser } from '../api/plex';
import * as plex from '../api/plex';
import type { App, AppState } from '../app/store';
import { ActionButton, Spinner } from './controls';
import { Press, useAccent } from './focus';
import { useBack } from './menus';
import { Overlay } from './overlay';
import { Gradient, sheet } from './surfaces';
import { Text } from './text';
import { color, dp, type } from './theme';

/*
 * What comes up over the app (ProfilePicker.kt, Tour.kt, Screensaver.kt and
 * ReminderCard.kt): "Who's watching?", the remote's tour, the screensaver, and a
 * programme somebody asked to be reminded of starting.
 */

const fill = { position: 'absolute' as const, left: 0, right: 0, top: 0, bottom: 0 };

function Avatar(props: { user: PlexHomeUser; size: number; ring?: boolean }) {
  return (
    <View style={{ width: props.size, height: props.size, borderRadius: props.size / 2, overflow: 'hidden', backgroundColor: color.surfaceHigh,
      alignItems: 'center', justifyContent: 'center', borderWidth: props.ring ? dp(3) : 0, borderColor: color.chalk }}>
      {props.user.thumb ? <Image source={{ uri: props.user.thumb }} resizeMode="cover" style={{ width: '100%', height: '100%' }} />
        : <Text style={{ color: color.chalk, fontSize: props.size * 0.4, lineHeight: props.size * 0.48, fontWeight: '600' }}>{props.user.title.slice(0, 1).toUpperCase()}</Text>}
    </View>
  );
}

/** A padlock on a profile with a PIN. */
const LockBadge = () => (
  <View style={{ position: 'absolute', right: 0, bottom: 0, width: dp(30), height: dp(30), borderRadius: dp(15), backgroundColor: color.ink, alignItems: 'center', justifyContent: 'center' }}>
    <Svg width={dp(16)} height={dp(16)} viewBox="0 0 16 16">
      <Path d="M5 7 V5 a3 3 0 0 1 6 0 V7" stroke={color.chalk} strokeWidth={2} fill="none" />
      <Rect x={3} y={7} width={10} height={7} rx={2} fill={color.chalk} />
    </Svg>
  </View>
);

function ProfileTile(props: { user: PlexHomeUser; current: boolean; onPress: () => void }) {
  const accent = useAccent();
  const [focused, setFocused] = useState(false);
  const u = props.user;
  return (
    <Press preferred={props.current} onPress={props.onPress} onFocusChange={setFocused}
      style={{ width: dp(132), alignItems: 'center', gap: dp(12), transform: [{ scale: focused ? 1.06 : 1 }] }}>
      {() => (
        <>
          <View>
            <Avatar user={u} size={dp(112)} ring={focused} />
            {u.protected ? <LockBadge /> : null}
          </View>
          <Text numberOfLines={1} style={[type.body, { color: focused ? color.chalk : color.muted, fontWeight: focused ? '600' : '500' }]}>{u.title}</Text>
          <Text numberOfLines={1} style={[type.label, { color: props.current ? accent.color : color.faint }]}>
            {props.current ? 'Watching now' : u.admin ? 'Owner' : u.restricted ? 'Managed' : ' '}
          </Text>
        </>
      )}
    </Press>
  );
}

/** "Who's watching?": the people in the Plex Home, and a PIN pad where there is one. */
export function ProfilePicker(props: { app: App; state: AppState; onClose: () => void }) {
  const { app, state } = props;
  const [asking, setAsking] = useState<PlexHomeUser | null>(null);
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<PlexHomeUser | null>(null);
  useBack(() => {
    if (busy) return true;
    if (asking) {
      setAsking(null);
      setPin('');
      setError(null);
    } else props.onClose();
    return true;
  });
  const pick = async (user: PlexHomeUser, code: string | null) => {
    setBusy(user);
    const problem = await app.switchUser(user, code);
    setBusy(null);
    if (problem) {
      setError(problem);
      setPin('');
    } else props.onClose();
  };
  const typeDigit = (digit: string) => {
    if (!asking || pin.length >= 4) return;
    setError(null);
    const next = pin + digit;
    setPin(next);
    if (next.length === 4) void pick(asking, next);
  };
  // The remote's number keys type the PIN too.
  useTVEventHandler((event) => {
    if (!asking || event.eventKeyAction !== 0) return;
    const d = /^(?:digit|num|number)?_?(\d)$/i.exec(event.eventType)?.[1];
    if (d) typeDigit(d);
  });

  return (
    <Overlay>
      <View style={[fill, { zIndex: 70, backgroundColor: color.ink, alignItems: 'center', justifyContent: 'center' }]}>
        {busy ? (
          <View style={{ alignItems: 'center', gap: dp(20) }}>
            <Avatar user={busy} size={dp(112)} />
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(12) }}>
              <Spinner size={20} />
              <Text style={[type.body, { color: color.muted }]}>{`Switching to ${busy.title}…`}</Text>
            </View>
          </View>
        ) : asking ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(72) }}>
            <View style={{ width: dp(300), alignItems: 'center', gap: dp(18) }}>
              <Avatar user={asking} size={dp(96)} />
              <Text style={type.headline}>{asking.title}</Text>
              <Text style={type.meta}>Enter the PIN for this profile</Text>
              <View style={{ flexDirection: 'row', gap: dp(16) }}>
                {[0, 1, 2, 3].map((i) => (
                  <View key={i} style={{ width: dp(16), height: dp(16), borderRadius: dp(8), backgroundColor: i < pin.length ? color.chalk : 'transparent', borderWidth: dp(2), borderColor: color.chalk }} />
                ))}
              </View>
              <Text style={[type.label, { color: color.danger }]}>{error ?? ' '}</Text>
            </View>
            <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ gap: dp(12) }}>
              {[['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['Delete', '0', 'Cancel']].map((row, r) => (
                <View key={r} style={{ flexDirection: 'row', gap: dp(12) }}>
                  {row.map((k) => (
                    <PadKey key={k} label={k} preferred={k === '5'} onPress={() => {
                      if (k === 'Delete') setPin(pin.slice(0, -1));
                      else if (k === 'Cancel') { setAsking(null); setPin(''); setError(null); }
                      else typeDigit(k);
                    }} />
                  ))}
                </View>
              ))}
            </TVFocusGuideView>
          </View>
        ) : (
          <View style={{ alignItems: 'center', gap: dp(36) }}>
            <Text style={type.display}>Who's watching?</Text>
            <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ flexDirection: 'row', gap: dp(28) }}>
              {state.plex.homeUsers.map((u) => {
                const current = u.uuid === state.plex.user?.uuid;
                return (
                  <ProfileTile key={u.uuid} user={u} current={current}
                    onPress={() => { if (current) props.onClose(); else if (u.protected) setAsking(u); else void pick(u, null); }} />
                );
              })}
            </TVFocusGuideView>
            <Text style={[type.body, { color: error ? color.danger : color.muted }]}>{error ?? 'Each profile has its own libraries, watch history and Continue Watching.'}</Text>
          </View>
        )}
      </View>
    </Overlay>
  );
}

function PadKey(props: { label: string; preferred?: boolean; onPress: () => void }) {
  const [focused, setFocused] = useState(false);
  const word = props.label.length > 1;
  return (
    <Press preferred={props.preferred} onPress={props.onPress} onFocusChange={setFocused}
      style={{ width: dp(76), height: dp(56), borderRadius: dp(14), alignItems: 'center', justifyContent: 'center', backgroundColor: focused ? color.chalk : color.surfaceRaised }}>
      {() => <Text style={{ color: focused ? color.ink : color.chalk, fontSize: dp(word ? 14 : 22), lineHeight: dp(word ? 18 : 28), fontWeight: '600' }}>{props.label}</Text>}
    </Press>
  );
}

type RemoteKey = 'none' | 'up' | 'select' | 'leftRight' | 'down';
const TOUR: Array<{ title: string; body: string; key: RemoteKey; hold?: boolean }> = [
  { title: 'Welcome to Reely', body: 'A quick look at getting around with your remote. It takes a minute, and you can skip it.', key: 'none' },
  { title: 'The top row', body: 'Press Up to reach the tabs: Search, Home, Movies, TV Shows, Live TV and Request. Settings is the gear on the right. Moving onto a tab opens it.', key: 'up' },
  { title: 'Hold OK for more', body: 'Hold OK on any poster for more: carry on or start again, mark it watched, or go to its page. On a show, it plays the next episode.', key: 'select', hold: true },
  { title: 'While you watch', body: "Left and Right skip back and forward, with a picture of where you'll land; hold them to go faster. Up or Down brings up the controls, with subtitles, audio and chapters on the right. Back puts them away.", key: 'leftRight' },
  { title: 'Live TV', body: 'Left and Right change channel, and Down opens the guide. Hold OK to put another channel beside the first, up to four. In the guide, hold OK on a channel to add it to Favorites, or to be reminded when something starts.', key: 'down' },
  { title: "Can't find something?", body: "The Request tab finds movies and shows your server doesn't have yet and asks for them. The first time, enter your Reely address there.", key: 'none' },
  { title: "You're all set", body: 'You can take this tour again from Settings, under About.', key: 'none' },
];

/**
 * The top of a Fire TV remote: the ring of arrows round OK, then Back, Home and Menu, then
 * rewind, play and fast-forward. Whatever the step is about is lit; a held button throbs.
 */
function RemoteDrawing(props: { lit: RemoteKey; pulse: boolean }) {
  const accent = useAccent();
  const beat = useRef(new Animated.Value(1)).current;
  const [alpha, setAlpha] = useState(1);
  useEffect(() => {
    if (!props.pulse) { setAlpha(1); return; }
    const loop = Animated.loop(Animated.sequence([
      Animated.timing(beat, { toValue: 0.45, duration: 700, easing: Easing.inOut(Easing.quad), useNativeDriver: false }),
      Animated.timing(beat, { toValue: 1, duration: 700, easing: Easing.inOut(Easing.quad), useNativeDriver: false }),
    ]));
    const id = beat.addListener(({ value }) => setAlpha(value));
    loop.start();
    return () => { loop.stop(); beat.removeListener(id); };
  }, [props.pulse]);
  const w = 120;
  const h = 220;
  const line = 'rgba(242, 244, 247, 0.35)';
  const on = accent.color;
  const ring = { x: w / 2, y: h * 0.3 };
  const outer = w * 0.36;
  const arrow = (dx: number, dy: number, lit: boolean) => {
    const s = w * 0.07;
    const tip = { x: ring.x + dx * outer * 0.72, y: ring.y + dy * outer * 0.72 };
    const back = { x: -dx * s, y: -dy * s };
    const side = { x: -dy * s, y: dx * s };
    return (
      <Path key={`${dx},${dy}`} d={`M ${tip.x + back.x + side.x} ${tip.y + back.y + side.y} L ${tip.x} ${tip.y} L ${tip.x + back.x - side.x} ${tip.y + back.y - side.y}`}
        stroke={lit ? on : line} strokeOpacity={lit ? alpha : 1} strokeWidth={s * 0.45} strokeLinecap="round" fill="none" />
    );
  };
  return (
    <Svg width={dp(w)} height={dp(h)} viewBox={`0 0 ${w} ${h}`}>
      <Rect x={1} y={1} width={w - 2} height={h - 2} rx={w * 0.42} fill="rgba(242, 244, 247, 0.08)" stroke={line} strokeWidth={w * 0.015} />
      <Circle cx={ring.x} cy={ring.y} r={outer} stroke={line} strokeWidth={w * 0.02} fill="none" />
      {arrow(0, -1, props.lit === 'up')}
      {arrow(0, 1, props.lit === 'down')}
      {arrow(-1, 0, props.lit === 'leftRight')}
      {arrow(1, 0, props.lit === 'leftRight')}
      <Circle cx={ring.x} cy={ring.y} r={outer * 0.42} stroke={props.lit === 'select' ? on : line} strokeWidth={w * 0.02}
        fill={props.lit === 'select' ? on : 'none'} fillOpacity={props.lit === 'select' ? alpha : 0} />
      {[h * 0.62, h * 0.78].map((y) => [0.26, 0.5, 0.74].map((x) => (
        <Circle key={`${x},${y}`} cx={w * x} cy={y} r={w * 0.075} stroke={line} strokeWidth={w * 0.02} fill="none" />
      )))}
    </Svg>
  );
}

/** The tour, over whatever is on screen: Next and Back step through it; Skip, or Back on the first step, ends it. */
export function Tour(props: { onDone: () => void }) {
  const accent = useAccent();
  const [index, setIndex] = useState(0);
  const step = TOUR[index];
  const last = index === TOUR.length - 1;
  useBack(() => {
    if (index === 0) props.onDone(); else setIndex(index - 1);
    return true;
  });
  return (
    <Overlay>
      <View style={[fill, { zIndex: 65, backgroundColor: 'rgba(8, 9, 11, 0.82)', alignItems: 'center', justifyContent: 'center' }]}>
        <View style={{ width: dp(760), flexDirection: 'row', alignItems: 'center', gap: dp(36), borderRadius: dp(24), backgroundColor: color.surfaceRaised,
          borderWidth: dp(1), borderColor: 'rgba(242, 244, 247, 0.12)', paddingHorizontal: dp(36), paddingVertical: dp(30) }}>
          <RemoteDrawing lit={step.key} pulse={!!step.hold} />
          <View style={{ flex: 1, gap: dp(12) }}>
            <View style={{ flexDirection: 'row', gap: dp(6) }}>
              {TOUR.map((_, i) => (
                <View key={i} style={{ width: i === index ? dp(18) : dp(6), height: dp(6), borderRadius: dp(3), backgroundColor: i === index ? accent.color : 'rgba(242, 244, 247, 0.25)' }} />
              ))}
            </View>
            <Text style={type.headline}>{step.title}</Text>
            <Text style={[type.body, { color: color.muted }]}>{step.body}</Text>
            <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ flexDirection: 'row', gap: dp(12), paddingTop: dp(10) }}>
              <ActionButton key={`next:${index}`} label={last ? 'Start watching' : 'Next'} emphasised preferred onPress={() => (last ? props.onDone() : setIndex(index + 1))} />
              {index > 0 && !last ? <ActionButton label="Back" onPress={() => setIndex(index - 1)} /> : null}
              {!last ? <ActionButton label="Skip tour" onPress={props.onDone} /> : null}
            </TVFocusGuideView>
          </View>
        </View>
      </View>
    </Overlay>
  );
}

export interface Slide { url: string; title: string; caption: string | null }
const SLIDE_MS = 12_000;
const FADE_MS = 2_000;

/** The artwork from Home: what's being watched, what's new, what's wanted. */
export function slidesFrom(app: App, state: AppState): Slide[] {
  const h = state.home;
  const items = [...h.continueWatching, ...h.recentMovies, ...h.recentEpisodes.map((g) => g.newest), ...h.watchlist, ...h.iptvMovies];
  const seen = new Set<string>();
  const out: Slide[] = [];
  for (const i of items) {
    const art = i.type === 'episode' ? i.art ?? i.thumb : i.art;
    const url = art ? app.image(i.serverBase, art, 1920, 1080) : null;
    if (!url || seen.has(url)) continue;
    seen.add(url);
    out.push({ url, title: plex.rowTitle(i), caption: plex.caption(i) });
  }
  return out;
}

/**
 * The library's artwork one picture at a time, fading over and drifting closer so nothing
 * sits still, with what each is from and the time. Any button puts it away, and that press
 * does nothing else.
 */
export function Screensaver(props: { slides: Slide[]; onWake: () => void }) {
  const [index, setIndex] = useState(0);
  const [now, setNow] = useState(Date.now());
  const fade = useRef(new Animated.Value(0)).current;
  const zoom = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    const t = setInterval(() => setIndex((i) => i + 1), SLIDE_MS);
    const c = setInterval(() => setNow(Date.now()), 15_000);
    return () => { clearInterval(t); clearInterval(c); };
  }, []);
  useEffect(() => {
    fade.setValue(0);
    zoom.setValue(1);
    Animated.timing(fade, { toValue: 1, duration: FADE_MS, useNativeDriver: true }).start();
    Animated.timing(zoom, { toValue: 1.08, duration: SLIDE_MS + FADE_MS, easing: Easing.linear, useNativeDriver: true }).start();
  }, [index]);
  // Any button wakes it: taken here, and the press goes no further.
  useTVEventHandler((event) => { if (event.eventKeyAction === 0) props.onWake(); });
  useBack(() => { props.onWake(); return true; });
  const slide = props.slides.length ? props.slides[index % props.slides.length] : null;
  const d = new Date(now);
  return (
    <Overlay>
      <View style={[fill, { zIndex: 90, backgroundColor: color.ink }]}>
        {/* Holds the cursor, so nothing under it moves. */}
        <Press preferred onPress={props.onWake} style={fill}>{() => null}</Press>
        {slide ? (
          <Animated.View pointerEvents="none" style={[fill, { opacity: fade }]}>
            <Animated.Image source={{ uri: slide.url }} resizeMode="cover" style={[fill, { transform: [{ scale: zoom }] }]} />
            <Gradient id="saverFoot" vertical stops={[[0.55, color.ink, 0], [1, color.ink, 0.85]]} />
            <View style={{ position: 'absolute', left: dp(56), bottom: dp(40), maxWidth: dp(640) }}>
              <Text numberOfLines={1} style={type.headline}>{slide.title}</Text>
              {slide.caption ? <Text numberOfLines={1} style={[type.body, { color: color.muted }]}>{slide.caption}</Text> : null}
            </View>
          </Animated.View>
        ) : null}
        <Text pointerEvents="none" style={[type.display, { position: 'absolute', right: dp(56), bottom: dp(36), fontSize: dp(44), lineHeight: dp(48) }]}>
          {`${d.getHours() % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')}`}
        </Text>
      </View>
    </Overlay>
  );
}

/** How long a reminder stays up unanswered. */
const REMINDER_SHOWN_MS = 2 * 60_000;

/** "Starting now": a programme somebody asked to be told about, with one press to watch it. */
export function ReminderCard(props: { app: App; state: AppState }) {
  const due = props.state.live.due;
  useEffect(() => {
    if (!due) return;
    const t = setTimeout(() => props.app.dismissReminder(), REMINDER_SHOWN_MS);
    return () => clearTimeout(t);
  }, [due?.start, due?.streamId]);
  useBack(() => { props.app.dismissReminder(); return true; }, !!due);
  if (!due) return null;
  return (
    <Overlay>
      <View style={{ position: 'absolute', top: dp(27), right: dp(48), zIndex: 80 }}>
        <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight
          style={[sheet(18), { maxWidth: dp(520), paddingHorizontal: dp(22), paddingVertical: dp(18), gap: dp(10) }]}>
          <Text style={[type.label, { color: color.muted }]}>Starting now</Text>
          <Text numberOfLines={2} style={type.headline}>{due.title}</Text>
          <Text numberOfLines={1} style={[type.body, { color: color.muted }]}>{`On ${due.channelName}`}</Text>
          <View style={{ flexDirection: 'row', gap: dp(12) }}>
            <ActionButton label="Watch" emphasised preferred onPress={() => void props.app.watchReminder()} />
            <ActionButton label="Dismiss" onPress={() => props.app.dismissReminder()} />
          </View>
        </TVFocusGuideView>
      </View>
    </Overlay>
  );
}
