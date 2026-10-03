import { useEffect, useRef, useState } from "preact/hooks";
import type { App, AppState, Route } from "../app/store";
import { isConnected } from "../app/store";
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
  ["Requests", { name: "requests" }],
];

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
        <img class="brand" src="mark.svg" alt="Reely" />
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
            <button class={"tab" + (route.name === "search" ? " on" : "")} data-focus onClick={() => app.navigate({ name: "search" })}>
              Search
            </button>
            {state.plex.homeUsers.length > 1 ? (
              <button class="tab" data-focus onClick={() => setChoosingProfile(true)}>
                {state.plex.user?.title ?? "Profiles"}
              </button>
            ) : null}
            <button class={"tab" + (route.name === "settings" ? " on" : "")} data-focus onClick={() => app.navigate({ name: "settings" })}>
              Settings
            </button>
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
