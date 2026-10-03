import { useEffect, useRef, useState } from "preact/hooks";
import type { App, AppState, Route } from "../app/store";
import { isConnected } from "../app/store";
import { arrived, focus, rescue } from "./focus";
import { playDirect, Player } from "./player";
import { Detail, Home, Library, Profiles, SignIn } from "./screens";
import { Requests, RequestTitlePage } from "./requests";
import { Live, LivePlayer } from "./live";
import { collectionKey, ListPageView, personKey, Search } from "./search";
import { Settings } from "./settings";

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
    arrived();
    const t = setTimeout(rescue, 0);
    return () => clearTimeout(t);
  }, [state.route, state.playing == null, isConnected(state)]);

  const onPlay = (item: Parameters<App["play"]>[0], resume: boolean) => {
    const probe = video.current ?? document.createElement("video");
    const version = item.type === "movie" ? state.detail?.versionIndex ?? 0 : 0;
    void app.play(item, resume, (p) => playDirect(probe, p), item.type === "episode" ? state.detail?.episodes ?? [] : [], version);
  };

  if (state.playing) return <Player app={app} playing={state.playing} />;
  const watching = state.live.watching != null ? state.live.channels[state.live.watching] : null;
  if (watching) return <LivePlayer app={app} state={state} channel={watching} />;

  const connected = isConnected(state);
  const route = state.route;
  return (
    <div class="app">
      <nav class="tabs">
        <img class="brand" src="mark.svg" alt="Reely" />
        {TABS.map(([label, target]) => (
          <button
            key={label}
            class={"tab" + (sameTab(route, target) ? " on" : "")}
            data-focus
            onClick={() => app.navigate(target)}
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
          <Live app={app} state={state} />
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
    </div>
  );
}
