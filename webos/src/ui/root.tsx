import { useEffect, useRef, useState } from "preact/hooks";
import type { App, AppState, Route } from "../app/store";
import { accentOf, isConnected } from "../app/store";
import { arrived, arrowedOnto, focus, lastMoveAt, lastPressAt, rescue } from "./focus";
import { Screensaver, slidesFrom } from "./screensaver";
import { Tour } from "./tour";
import { playDirect, Player } from "./player";
import { Detail, Home, Library, Profiles, SignIn } from "./screens";
import { Requests, RequestTitlePage } from "./requests";
import { Live, LivePlayer, ReminderNotice } from "./live";
import { collectionKey, ListPageView, personKey, Search } from "./search";
import { Settings } from "./settings";
import { ItemMenu } from "./menu";
import { HoldContext, Pill } from "./parts";
import type { PlexItem } from "../api/plex";
import { listKey } from "../api/plex";
import { open } from "./screens";

const TABS: Array<[string, Route]> = [
  ["Home", { name: "home" }],
  ["Movies", { name: "library", kind: "movie" }],
  ["TV Shows", { name: "library", kind: "show" }],
  ["Live TV", { name: "live" }],
  ["Request", { name: "requests" }],
];

/** The Fire TV's glass and gear (Icons.kt), drawn the same way. */
function SearchGlyph() {
  return (
    <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true">
      <circle cx="42" cy="42" r="30" fill="none" stroke="currentColor" stroke-width="12" />
      <line x1="63" y1="63" x2="86" y2="86" stroke="currentColor" stroke-width="12" stroke-linecap="round" />
    </svg>
  );
}

function GearGlyph() {
  const teeth = Array.from({ length: 8 }, (_, i) => (i * Math.PI) / 4);
  return (
    <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true">
      <circle cx="50" cy="50" r="27" fill="none" stroke="currentColor" stroke-width="13" />
      {teeth.map((a) => (
        <line key={a} x1={50 + Math.cos(a) * 27} y1={50 + Math.sin(a) * 27} x2={50 + Math.cos(a) * 45} y2={50 + Math.sin(a) * 45}
          stroke="currentColor" stroke-width="12" stroke-linecap="round" />
      ))}
    </svg>
  );
}

/** The time at the far right, quietly, as on the Fire TV. */
function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 20_000);
    return () => clearInterval(t);
  }, []);
  return <span class="clock">{now.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</span>;
}

const sameTab = (a: Route, b: Route) => a.name === b.name && (a.name !== "library" || (b.name === "library" && a.kind === b.kind));

/** Leaving the app, as webOS wants it done: its own way back to the launcher, else closing. */
export function leave() {
  const webOS = (window as unknown as { webOS?: { platformBack?: () => void } }).webOS;
  if (webOS?.platformBack) webOS.platformBack();
  else window.close();
}

export function Root(props: { app: App }) {
  const { app } = props;
  const [state, setState] = useState<AppState>(app.state);
  const [choosingProfile, setChoosingProfile] = useState(false);
  const [menuFor, setMenuFor] = useState<PlexItem | null>(null);
  // Kept here, so coming back from a channel finds the guide still up.
  const [guide, setGuide] = useState(false);
  const [saver, setSaver] = useState(false);
  // The screensaver: after the minutes set without a button, never over something playing.
  useEffect(() => {
    const t = setInterval(() => {
      const s = app.state;
      const minutes = s.prefs.screensaverMinutes;
      const busy = s.playing != null || s.live.watching != null || s.live.due != null;
      if (minutes > 0 && !busy && Date.now() - lastPressAt() > minutes * 60_000) setSaver(true);
    }, 5_000);
    return () => clearInterval(t);
  }, [app]);
  // Reminders, looked at every little while wherever the app is.
  useEffect(() => {
    const t = setInterval(() => app.checkReminders(), 15_000);
    app.checkReminders();
    return () => clearInterval(t);
  }, [app]);
  const video = useRef<HTMLVideoElement | null>(null);

  useEffect(() => app.subscribe(setState), [app]);
  // The colour picked in Settings, for everything drawn in the accent.
  useEffect(() => {
    const a = accentOf(state.prefs.accent);
    document.documentElement.style.setProperty("--accent", a.color);
    document.documentElement.style.setProperty("--on-accent", a.on);
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(a.color.slice(i, i + 2), 16));
    document.documentElement.style.setProperty("--accent-glow", `rgba(${r}, ${g}, ${b}, 0.3)`);
  }, [state.prefs.accent]);
  useEffect(() => {
    void app.start();
  }, [app]);
  // "Who's watching?" once, straight after signing in to a Plex Home.
  useEffect(() => {
    if (state.askWho) {
      setChoosingProfile(true);
      app.askedWho();
    }
  }, [state.askWho]);
  // Back from a page or the player: the cursor onto something on the screen arrived at.
  useEffect(() => {
    // Moving along the tabs opens each, and the cursor stays on the tabs.
    const alongTabs = (document.activeElement as HTMLElement | null)?.classList.contains("tab") && Date.now() - lastMoveAt() < 1000;
    if (alongTabs) return;
    arrived();
    const t = setTimeout(rescue, 0);
    return () => clearTimeout(t);
  }, [state.route, state.playing == null, isConnected(state)]);

  const direct = (p: Parameters<typeof playDirect>[1]) => playDirect(video.current ?? document.createElement("video"), p);
  const onPlay = (item: Parameters<App["play"]>[0], resume: boolean) => {
    const version = item.type === "movie" ? state.detail?.versionIndex ?? 0 : 0;
    void app.play(item, resume, direct, item.type === "episode" ? state.detail?.episodes ?? [] : [], version);
  };
  /** A list played in order (or shuffled), each on to the next. */
  const playAll = (items: PlexItem[], shuffle: boolean) => {
    const queue = shuffle ? shuffled(items) : items;
    if (queue.length) void app.play(queue[0], false, direct, queue);
  };
  const menuActions = {
    play: (item: PlexItem, resume: boolean) => void app.play(item, resume, direct),
    playNext: (show: PlexItem) => void app.nextEpisodeOf(show).then((n) => { if (n) void app.play(n.episode, true, direct, n.queue); }),
    setWatched: (item: PlexItem, watched: boolean) => void app.setItemWatched(item, watched),
    details: (item: PlexItem) => open(app, item),
    removeFromContinueWatching: menuFor && state.home.continueWatching.some((i) => listKey(i) === listKey(menuFor))
      ? (item: PlexItem) => void app.removeFromContinueWatching(item)
      : null,
  };

  if (saver && !state.playing && state.live.watching == null) return <Screensaver slides={slidesFrom(app, state)} onWake={() => setSaver(false)} />;
  if (state.playing) return <Player app={app} playing={state.playing} />;
  const watching = state.live.watching != null ? state.live.channels[state.live.watching] : null;
  if (watching) return <LivePlayer app={app} state={state} channel={watching} onGuide={() => { setGuide(true); app.stopLive(); }} />;

  const connected = isConnected(state);
  const route = state.route;
  return (
    <HoldContext.Provider value={setMenuFor}>
    <div class="app">
      <nav class="tabs">
        {/* Drawn here rather than as a picture, so it takes the colour picked in Settings. */}
        <svg class="brand" viewBox="43.1 30 22 46" role="img" aria-label="Reely">
          <path fill="var(--accent)" d="M43.19 66V30H52.72L52.99 37.19Q54.07 33.43 56.15 31.71Q58.23 30 61.52 30H64.81V38.33H61.52Q57.29 38.33 55.28 40.04Q53.26 41.75 53.26 45.78V66Z" />
          <path fill="var(--accent)" d="M47.5,72h13a2,2 0 0 1 2,2v0a2,2 0 0 1 -2,2h-13a2,2 0 0 1 -2,-2v0a2,2 0 0 1 2,-2z" />
        </svg>
        {connected ? (
          // Search first, as a glass, as on the Fire TV.
          <button class={"tab icon" + (route.name === "search" ? " on" : "")} data-focus aria-label="Search" onClick={() => app.navigate({ name: "search" })}>
            <SearchGlyph />
          </button>
        ) : null}
        {TABS.map(([label, target]) => (
          <button
            key={label}
            class={"tab" + (sameTab(route, target) ? " on" : "")}
            data-focus
            onClick={() => app.navigate(target)}
            // Moving onto a tab opens it, as the Fire TV's do.
            onFocus={(e) => { if (arrowedOnto(e.currentTarget) && !sameTab(app.state.route, target)) app.navigate(target); }}
          >
            {label}
          </button>
        ))}
        {connected ? (
          <span class="tabs-end">
            {state.plex.homeUsers.length > 1 ? (
              <button class="tab" data-focus onClick={() => setChoosingProfile(true)}>
                {state.plex.user?.title ?? "Profiles"}
              </button>
            ) : null}
            <button class={"tab icon" + (route.name === "settings" ? " on" : "")} data-focus aria-label="Settings" onClick={() => app.navigate({ name: "settings" })}>
              <GearGlyph />
            </button>
            <Clock />
          </span>
        ) : null}
      </nav>
      <main class="content" data-content>
        {!connected ? (
          <SignIn app={app} state={state} />
        ) : route.name === "home" ? (
          <Home app={app} state={state} />
        ) : route.name === "library" ? (
          <Library app={app} state={state} kind={route.kind} />
        ) : route.name === "detail" ? (
          <Detail app={app} state={state} onPlay={onPlay} />
        ) : route.name === "live" ? (
          <Live app={app} state={state} guide={guide} onGuide={setGuide} />
        ) : route.name === "requests" ? (
          <Requests app={app} state={state} />
        ) : route.name === "requestTitle" ? (
          <RequestTitlePage app={app} state={state} title={route.title} />
        ) : route.name === "search" ? (
          <Search app={app} state={state} />
        ) : route.name === "person" ? (
          <ListPageView app={app} state={state} title={route.person.name} listKey={personKey(route.person)} empty="Nothing they're in is in your libraries." />
        ) : route.name === "collection" ? (
          <ListPageView app={app} state={state} title={route.item.title} listKey={collectionKey(route.item)} empty="This collection is empty." />
        ) : route.name === "playlist" ? (
          <ListPageView app={app} state={state} title={route.item.title} listKey={`playlist:${listKey(route.item)}`} empty="This playlist is empty."
            actions={<>
              <Pill label="Play" primary autofocus onPress={() => playAll(state.list?.items ?? [], false)} />
              <Pill label="Shuffle" onPress={() => playAll(state.list?.items ?? [], true)} />
            </>} />
        ) : route.name === "settings" ? (
          <Settings app={app} state={state} onProfiles={() => setChoosingProfile(true)} />
        ) : (
          <Home app={app} state={state} />
        )}
      </main>
      {state.playError ? (
        <div class="layer" data-layer>
          <div class="panel">
            <p class="note error">{state.playError}</p>
            <button class="pill" data-focus data-autofocus ref={(el) => { if (el) focus(el); }} onClick={() => app.dismissPlayError()}>
              OK
            </button>
          </div>
        </div>
      ) : null}
      {choosingProfile ? <Profiles app={app} state={state} onClose={() => setChoosingProfile(false)} /> : null}
      {state.live.due ? <ReminderNotice app={app} state={state} /> : null}
      {/* The tour, once, when there's a library to get around. */}
      {connected && !state.prefs.tourSeen && !choosingProfile && !state.homeBusy ? <Tour onDone={() => app.finishTour()} /> : null}
      {menuFor ? (
        <ItemMenu item={menuFor} image={app.image(menuFor.serverBase, menuFor.art ?? menuFor.thumb, 960, 540)} actions={menuActions} onClose={() => setMenuFor(null)} />
      ) : null}
    </div>
    </HoldContext.Provider>
  );
}

/** A shuffled copy: each order as likely as any other. */
function shuffled<T>(items: T[]): T[] {
  const out = items.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}
