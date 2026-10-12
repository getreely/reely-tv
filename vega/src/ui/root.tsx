import React, { useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, View } from 'react-native';
import { TVFocusGuideView, useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { App, AppState, Route } from '../app/store';
import { accentOf, isConnected } from '../app/store';
import type { PlexItem } from '../api/plex';
import { listKey } from '../api/plex';
import { ItemMenu } from './itemmenu';
import { ActionButton, EmptyNote } from './controls';
import { AccentContext, ScreenContext, ScreenMemory, focusWhenReady } from './focus';
import { useBack } from './menus';
import { TabMenu } from './tabmenu';
import { Text } from './text';
import { color, dp } from './theme';
import { TopBar, type TabId } from './topbar';
import { Screens, routeFor } from './screens';
import { BACKDROP_RISE } from './surfaces';
import { LivePlayer } from './player/live';
import { VodPlayer } from './player/vod';
import { ProfilePicker, ReminderCard, Screensaver, Tour, slidesFrom } from './overlays';
import { OverlayContext, OverlayHost, Overlays } from './overlay';

/*
 * The app around its screens (ReelyApp.kt): the top bar, the page under it, what Back
 * does, and where the cursor goes as screens change.
 */

/** The tab a route belongs to: the one it was opened from, for a page under a tab. */
export function tabOf(stack: Route[], route: Route): TabId {
  if (route.name === 'settings') return 'settings';
  const top = stack[0] ?? route;
  switch (top.name) {
    case 'search': return 'search';
    case 'library': return top.kind;
    case 'live': return 'live';
    case 'requests': case 'requestTitle': return 'requests';
    default: return 'home';
  }
}

const routeOfTab = (tab: TabId): Route =>
  tab === 'movie' || tab === 'show' ? { name: 'library', kind: tab }
    : tab === 'search' ? { name: 'search' }
    : tab === 'live' ? { name: 'live' }
    : tab === 'requests' ? { name: 'requests' }
    : tab === 'settings' ? { name: 'settings' }
    : { name: 'home' };

/** Identifies a screen for remembering where its cursor was. */
export function routeKey(route: Route, state: AppState): string {
  switch (route.name) {
    case 'library': return `library:${route.kind}:${state.browse[route.kind].view}`;
    case 'detail': return `detail:${route.serverBase}:${route.ratingKey}:${route.episodeKey ?? ''}`;
    case 'person': return `person:${route.person.id}`;
    case 'collection': return `collection:${route.item.ratingKey}`;
    case 'playlist': return `playlist:${route.item.ratingKey}`;
    case 'requestTitle': return `request:${route.title.kind}:${route.title.tmdbId}`;
    default: return route.name;
  }
}

/** Asked before the app closes, which a single press of Back shouldn't do. */
function ConfirmExit(props: { onStay: () => void; onLeave: () => void }) {
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', zIndex: 60 }}>
      <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight
        style={{ alignItems: 'center', gap: dp(10), borderRadius: dp(14), backgroundColor: 'rgba(8, 9, 11, 0.97)', borderWidth: dp(1), borderColor: color.line, padding: dp(24) }}>
        <Text style={{ color: color.chalk, fontSize: dp(18), lineHeight: dp(23), fontWeight: '600' }}>Close Reely?</Text>
        <View style={{ flexDirection: 'row', gap: dp(10) }}>
          <ActionButton label="Stay" emphasised preferred onPress={props.onStay} />
          <ActionButton label="Close" onPress={props.onLeave} />
        </View>
      </TVFocusGuideView>
    </View>
  );
}

/** Something that wouldn't start playing, said over the page it was started from. */
function PlayError(props: { message: string; onDismiss: () => void }) {
  useBack(() => {
    props.onDismiss();
    return true;
  });
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', zIndex: 60, backgroundColor: 'rgba(8, 9, 11, 0.6)' }}>
      <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight
        style={{ alignItems: 'center', gap: dp(14), borderRadius: dp(14), backgroundColor: 'rgba(8, 9, 11, 0.97)', borderWidth: dp(1), borderColor: color.line, padding: dp(24), maxWidth: dp(640) }}>
        <Text style={{ color: color.chalk, fontSize: dp(18), lineHeight: dp(24), textAlign: 'center' }}>{props.message}</Text>
        <ActionButton label="OK" emphasised preferred onPress={props.onDismiss} />
      </TVFocusGuideView>
    </View>
  );
}

export function useAppState(app: App): AppState {
  const [state, setState] = useState<AppState>(app.state);
  useEffect(() => app.subscribe(setState), [app]);
  return state;
}

export function Root(props: { app: App }) {
  const { app } = props;
  const state = useAppState(app);
  const accent = accentOf(state.prefs.accent);
  const accentValue = useMemo(() => ({ color: accent.color, on: accent.on }), [accent.color, accent.on]);

  const tabRefs = useRef<Record<TabId, React.RefObject<View | null>>>({
    search: React.createRef(), home: React.createRef(), movie: React.createRef(), show: React.createRef(),
    live: React.createRef(), requests: React.createRef(), settings: React.createRef(),
  }).current;
  const tabX = useRef<Partial<Record<TabId, number>>>({});

  // Each screen's memory of where its cursor was, for as long as the app runs.
  const memories = useRef(new Map<string, ScreenMemory>()).current;
  const route = state.route;
  const key = routeKey(route, state);
  const memory = useMemo(() => {
    let m = memories.get(key);
    if (!m) {
      m = new ScreenMemory();
      // The first screen of all leaves the cursor on Home's tab, as the Fire TV starts.
      if (memories.size === 0) m.claim = false;
      memories.set(key, m);
    }
    return m;
  }, [key]);

  const [onTabs, setOnTabs] = useState(true);
  // Once the cursor is down in the page, what the page shows next (a category's guide,
  // say) may take it, as the Fire TV's content does once it's had focus.
  memory.onFocus = () => {
    setOnTabs(false);
    memory.claim = true;
  };
  const [confirmExit, setConfirmExit] = useState(false);
  const [menuFor, setMenuFor] = useState<'movie' | 'show' | null>(null);
  const [itemMenu, setItemMenu] = useState<PlexItem | null>(null);

  // A tab is chosen by the cursor arriving at it along the tabs, as the Fire TV's are:
  // the arrows, not the app putting the cursor there.
  const arrowAt = useRef(0);
  // When a button was last pressed, for the screensaver.
  const pressedAt = useRef(Date.now());
  useTVEventHandler((event) => {
    pressedAt.current = Date.now();
    if (event.eventKeyAction === 0 && ['left', 'right', 'up', 'down'].includes(event.eventType)) arrowAt.current = Date.now();
  });

  // "Who's watching?": from the avatar or Settings, and once straight after signing in to a Plex Home.
  const [choosingProfile, setChoosingProfile] = useState(false);
  useEffect(() => {
    if (!state.askWho) return;
    setChoosingProfile(true);
    app.askedWho();
  }, [state.askWho]);

  // The screensaver: after the minutes set without a button, never over something playing.
  const [saver, setSaver] = useState(false);
  useEffect(() => {
    const t = setInterval(() => {
      const s = app.state;
      const minutes = s.prefs.screensaverMinutes;
      const busy = s.playing != null || s.live.watching != null || s.live.due != null;
      if (minutes > 0 && !busy && Date.now() - pressedAt.current > minutes * 60_000) setSaver(true);
    }, 5_000);
    return () => clearInterval(t);
  }, [app]);

  // Reminders, looked at every little while wherever the app is.
  useEffect(() => {
    const t = setInterval(() => app.checkReminders(), 15_000);
    app.checkReminders();
    return () => clearInterval(t);
  }, [app]);

  const current = tabOf(state.stack, route);
  const focusTab = (tab: TabId = current) => focusWhenReady(tabRefs[tab]);

  const onFocusTab = (tab: TabId) => {
    setOnTabs(true);
    if (Date.now() - arrowAt.current > 400) return;
    if (tab === 'settings' || tab === current) return;
    setMenuFor(null);
    const next = routeOfTab(tab);
    // The page arrived at along the tabs leaves the cursor up there.
    const m = memories.get(routeKey(next, app.state));
    if (m) {
      m.claim = false;
      m.restoring = false;
    } else {
      const fresh = new ScreenMemory();
      fresh.claim = false;
      memories.set(routeKey(next, app.state), fresh);
    }
    app.navigate(next);
  };

  const onPressTab = (tab: TabId) => {
    if (tab === 'movie' || tab === 'show') {
      if (current !== tab) app.navigate(routeOfTab(tab));
      setMenuFor(tab);
      return;
    }
    if (tab !== current || route.name !== routeOfTab(tab).name) {
      const next = routeOfTab(tab);
      const m = memories.get(routeKey(next, app.state)) ?? new ScreenMemory();
      m.claim = true;
      m.restoring = false;
      memories.set(routeKey(next, app.state), m);
      app.navigate(next);
      return;
    }
    // OK on the tab of the page you're on: into the page.
    memory.claim = true;
  };

  /** Opening something from a page: the new page takes the cursor. */
  const open = (next: Route) => {
    const m = new ScreenMemory();
    memories.set(routeKey(next, app.state), m);
    app.navigate(next);
  };

  // Back, in order: close what's open; off a page opened from somewhere, back to it; from
  // a tab's own page, up to its tab; from the tabs, the question of leaving.
  useBack(() => {
    if (confirmExit) {
      setConfirmExit(false);
      focusTab();
      return true;
    }
    if (menuFor) {
      setMenuFor(null);
      focusTab(menuFor);
      return true;
    }
    if (route.name === 'settings') {
      if (onTabs) setConfirmExit(true);
      else focusTab('settings');
      return true;
    }
    if (state.stack.length > 1) {
      // The screen gone back to puts the cursor where it was.
      const previous = state.stack[state.stack.length - 2];
      const m = memories.get(routeKey(previous, app.state));
      if (m) m.restoring = true;
      app.goBack();
      return true;
    }
    if (onTabs) {
      setConfirmExit(true);
      return true;
    }
    if (route.name === 'library' && state.browse[route.kind].view !== 'home') {
      app.setLibraryView(route.kind, 'home');
      return true;
    }
    focusTab();
    return true;
  });

  // Start on Home's tab, as the Fire TV does.
  useEffect(() => {
    focusTab('home');
  }, []);

  const overlays = useRef(new Overlays()).current;
  const connected = isConnected(state);
  const watching = state.live.watching != null && !!state.live.channels[state.live.watching];
  const playing = state.playing;
  // Back from a film or an episode: the page it was played from, drawn afresh with the
  // cursor where it was, as the Fire TV puts it back on the episode.
  const [returns, setReturns] = useState(0);
  const wasPlaying = useRef(false);
  useEffect(() => {
    if (playing) wasPlaying.current = true;
    else if (wasPlaying.current) {
      wasPlaying.current = false;
      memory.restoring = true;
      setReturns((n) => n + 1);
    }
  }, [playing == null]);
  const away = watching || !!playing;
  return (
    <AccentContext.Provider value={accentValue}>
      <OverlayContext.Provider value={overlays}>
      <View style={{ flex: 1, backgroundColor: color.ink }}>
        <ScreenContext.Provider value={memory}>
          {/* Kept while a channel plays over it, so the guide is as it was on the way back,
              but out of the cursor's reach. */}
          <TVFocusGuideView trapFocusLeft trapFocusRight trapFocusDown
            style={{ position: 'absolute', left: 0, right: 0, top: BACKDROP_RISE, bottom: 0, display: away ? 'none' : 'flex' }}>
            {playing ? null : <Screens app={app} state={state} open={open} openMenu={setItemMenu} key={`${key}:${returns}`} />}
          </TVFocusGuideView>
        </ScreenContext.Provider>
        {(connected || route.name === 'settings') && !away ? (
          <TopBar current={current} tabRefs={tabRefs} onFocusTab={onFocusTab} onPressTab={onPressTab}
            onTabLayout={(tab, x) => { tabX.current[tab] = x; }}
            profile={state.plex.user} onProfile={state.plex.homeUsers.length > 1 ? () => setChoosingProfile(true) : null} />
        ) : null}
        {menuFor ? (
          <TabMenu app={app} state={state} kind={menuFor} left={tabX.current[menuFor] ?? dp(200)} top={BACKDROP_RISE}
            onClose={() => { const tab = menuFor; setMenuFor(null); focusTab(tab); }} />
        ) : null}
        {playing ? <VodPlayer app={app} state={state} playing={playing} /> : null}
        {state.playError && !playing ? <PlayError message={state.playError} onDismiss={() => app.dismissPlayError()} /> : null}
        {watching && !playing ? <LivePlayer app={app} state={state} channel={state.live.channels[state.live.watching!]} /> : null}
        {itemMenu ? (
          <ItemMenu app={app} item={itemMenu} onClose={() => setItemMenu(null)} open={(item) => open(routeFor(item))}
            inContinueWatching={state.home.continueWatching.some((i) => listKey(i) === listKey(itemMenu))} />
        ) : null}
        {state.live.due ? <ReminderCard app={app} state={state} /> : null}
        {connected && !state.prefs.tourSeen && !choosingProfile && !state.homeBusy && !playing && !watching ? <Tour onDone={() => app.finishTour()} /> : null}
        {choosingProfile ? <ProfilePicker app={app} state={state} onClose={() => { setChoosingProfile(false); focusTab(); }} /> : null}
        {saver && !playing && !watching ? <Screensaver slides={slidesFrom(app, state)} onWake={() => { pressedAt.current = Date.now(); setSaver(false); }} /> : null}
        <OverlayHost overlays={overlays} />
        {confirmExit ? <ConfirmExit onStay={() => { setConfirmExit(false); focusTab(); }} onLeave={() => BackHandler.exitApp()} /> : null}
      </View>
      </OverlayContext.Provider>
    </AccentContext.Provider>
  );
}

export { EmptyNote };
