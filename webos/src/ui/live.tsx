import { useEffect, useRef, useState } from "preact/hooks";
import type { XtreamChannel } from "../api/xtream";
import { isOnAt, progressAt } from "../api/xtream";
import type { App, AppState } from "../app/store";
import { onKeys } from "./focus";
import { Pill, Spinner, useRescue } from "./parts";

const time = (epoch: number) => new Date(epoch * 1000).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });

function Field(props: { label: string; value: string; secret?: boolean; onInput: (v: string) => void; autofocus?: boolean }) {
  return (
    <input class="field" data-focus data-autofocus={props.autofocus ? "" : undefined} placeholder={props.label} value={props.value}
      type={props.secret ? "password" : "text"} onInput={(e) => props.onInput((e.target as HTMLInputElement).value)} />
  );
}

/** Signing in to live TV: an Xtream login, or an M3U playlist. The same two as the Fire TV. */
function LiveSignIn(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const [playlist, setPlaylist] = useState(false);
  const [host, setHost] = useState("");
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [url, setUrl] = useState("");
  const [guide, setGuide] = useState("");
  useRescue([playlist]);
  const live = state.live;
  return (
    <div class="center">
      <h1 class="big">Watch live TV from your provider</h1>
      <p class="note">Sign in with the details your IPTV provider gave you. Reely doesn't provide channels.</p>
      <div class="actions">
        <Pill label="Server login" on={!playlist} onPress={() => setPlaylist(false)} />
        <Pill label="M3U playlist" on={playlist} onPress={() => setPlaylist(true)} />
      </div>
      {live.error ? <p class="note error">{live.error}</p> : null}
      {!playlist ? (
        <>
          <Field label="Server address" value={host} onInput={setHost} autofocus />
          <Field label="Username" value={user} onInput={setUser} />
          <Field label="Password" value={password} onInput={setPassword} secret />
          <Pill label={live.busy ? "Connecting…" : "Sign in"} primary onPress={() => void app.signInXtream(host, user, password)} />
        </>
      ) : (
        <>
          <Field label="Playlist address" value={url} onInput={setUrl} autofocus />
          <Field label="TV guide address (optional)" value={guide} onInput={setGuide} />
          <Pill label={live.busy ? "Loading…" : "Add playlist"} primary onPress={() => void app.signInPlaylist(url, guide)} />
        </>
      )}
    </div>
  );
}

export function Live(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const live = state.live;
  const [now, setNow] = useState(Math.floor(Date.now() / 1000));
  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 30_000);
    return () => clearInterval(t);
  }, []);
  useRescue([!!live.credentials, live.category?.id, live.channels.length > 0, live.categories.length > 0]);

  if (!live.credentials) return <LiveSignIn app={app} state={state} />;
  if (!live.category) {
    return (
      <div>
        {live.error ? <p class="note error" style={{ margin: "0 3rem" }}>{live.error}</p> : null}
        <div class="list">
          {app.shownCategories().map((c, i) => (
            <button key={c.id} class="list-row" data-focus data-autofocus={i === 0 ? "" : undefined} onClick={() => void app.openCategory(c)}>{c.name}</button>
          ))}
        </div>
        {live.busy && !live.categories.length ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
      </div>
    );
  }
  return (
    <div>
      <h2 class="page-title">{live.category.name}</h2>
      {live.busy && !live.channels.length ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
      <div class="list">
        {live.channels.map((ch, i) => {
          const listing = live.guide[ch.streamId] ?? [];
          const on = listing.find((p) => isOnAt(p, now));
          const next = listing.find((p) => p.start >= now);
          const fav = live.favorites.includes(ch.streamId);
          return (
            <button key={ch.streamId} class="channel" data-focus data-autofocus={i === 0 ? "" : undefined} onClick={() => app.watchChannel(i)}>
              <div class="logo">{ch.icon ? <img src={ch.icon} alt="" /> : ch.name.slice(0, 3)}</div>
              <div>
                <div class="name">{[ch.number > 0 ? ch.number : null, ch.name].filter(Boolean).join("  ")}{fav ? "  ♥" : ""}</div>
                {on ? <div class="facts">{on.title}</div> : null}
                {on ? <div class="scrub"><i style={{ width: `${(progressAt(on, now) ?? 0) * 100}%` }} /></div> : null}
                {next ? <div class="facts" style={{ color: "var(--faint)" }}>Next: {time(next.start)} {next.title}</div> : null}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * A channel, full screen. Up and down or Channel Up/Down change it; numbers tune one;
 * OK shows what's on; the green key favorites it; Back goes back to the list.
 */
export function LivePlayer(props: { app: App; state: AppState; channel: XtreamChannel }) {
  const { app, state, channel } = props;
  const video = useRef<HTMLVideoElement>(null);
  const [banner, setBanner] = useState(true);
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(true);
  const url = app.channelUrl(channel);

  useEffect(() => {
    const v = video.current;
    if (!v || !url) return;
    setError(null);
    setWaiting(true);
    v.src = url;
    v.load();
    void v.play().catch(() => undefined);
    setBanner(true);
    const hide = setTimeout(() => setBanner(false), 5_000);
    const ready = () => setWaiting(false);
    const fail = () => setError("This channel isn't playing. It may be off air, or your provider's connection limit reached.");
    v.addEventListener("playing", ready);
    v.addEventListener("error", fail);
    return () => {
      clearTimeout(hide);
      v.removeEventListener("playing", ready);
      v.removeEventListener("error", fail);
    };
  }, [url]);

  // Digits typed in a row tune that channel a moment after the last one.
  useEffect(() => {
    if (!typed) return;
    const t = setTimeout(() => {
      if (!app.tuneNumber(Number(typed))) setError(`No channel ${typed} in ${state.live.category?.name ?? "this list"}.`);
      setTyped("");
    }, 1_500);
    return () => clearTimeout(t);
  }, [typed]);

  useEffect(() => onKeys((a) => {
    if (typeof a === "object") { setTyped((t) => (t + a.digit).slice(0, 4)); return true; }
    switch (a) {
      case "stop": app.stopLive(); return true;
      case "back": return false; // the app's Back: off the channel, back to its list
      case "up": case "channelUp": app.stepChannel(-1); return true;
      case "down": case "channelDown": app.stepChannel(1); return true;
      case "ok": case "info": setBanner((b) => !b); return true;
      case "green": app.toggleFavorite(channel); setBanner(true); return true;
    }
    return true;
  }), [channel.streamId]);

  const now = Math.floor(Date.now() / 1000);
  const on = (state.live.guide[channel.streamId] ?? []).find((p) => isOnAt(p, now));
  const fav = state.live.favorites.includes(channel.streamId);
  return (
    <div class="player" data-layer>
      <video ref={video} class="video" playsInline />
      {waiting && !error ? <div class="player-wait"><Spinner /></div> : null}
      {error ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      {typed ? <div class="typed">{typed}</div> : null}
      <div class={"player-bar" + (banner ? " on" : "")}>
        <div class="player-title">{[channel.number > 0 ? channel.number : null, channel.name].filter(Boolean).join("  ")}{fav ? "  ♥" : ""}</div>
        {on ? <div class="facts">{on.title}  ·  {time(on.start)}–{time(on.stop)}</div> : null}
        <div class="facts" style={{ color: "var(--faint)" }}>Up and down change channel  ·  Green key: {fav ? "remove from" : "add to"} Favorites</div>
      </div>
    </div>
  );
}
