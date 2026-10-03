import { useEffect, useRef, useState } from "preact/hooks";
import type { Programme, XtreamChannel } from "../api/xtream";
import { isOnAt, progressAt } from "../api/xtream";
import type { App, AppState } from "../app/store";
import { onKeys } from "./focus";
import { Pill, Spinner, useRescue, useReturnFocus } from "./parts";

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

export function Live(props: { app: App; state: AppState; guide?: boolean; onGuide?: (on: boolean) => void }) {
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
  const guide = props.guide ?? false;
  return (
    <div>
      <div class="toolbar">
        <h2 class="page-title inline">{live.category.name}</h2>
        <Pill label="Channels" on={!guide} onPress={() => props.onGuide?.(false)} />
        <Pill label="Guide" on={guide} onPress={() => props.onGuide?.(true)} />
      </div>
      {live.busy && !live.channels.length ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
      {guide ? <GuideGrid app={app} state={state} now={now} /> : (
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
      )}
    </div>
  );
}

/** The guide's window: from the half hour before the last one, three and a half hours. */
const WINDOW_BEFORE = 30 * 60;
const WINDOW = 210 * 60;
/** How wide a minute is in the grid. */
const REM_PER_MINUTE = 0.46;

/**
 * The guide as a grid, as on the Fire TV: channels down, time across. OK on what's on
 * watches it; on something that's over, from the archive, where the channel keeps one;
 * on something to come, a reminder.
 */
function GuideGrid(props: { app: App; state: AppState; now: number }) {
  const { app, state, now } = props;
  const live = state.live;
  const [focused, setFocused] = useState<{ channel: XtreamChannel; programme: Programme | null } | null>(null);
  const start = Math.floor(now / 1800) * 1800 - WINDOW_BEFORE;
  const end = start + WINDOW;
  const shown = live.channels.slice(0, 60);
  useEffect(() => { void app.loadTable(shown.slice(0, 30)); }, [live.category?.id, live.channels.length]);
  const x = (t: number) => ((Math.max(start, Math.min(end, t)) - start) / 60) * REM_PER_MINUTE;
  const press = (index: number, channel: XtreamChannel, p: Programme | null) => {
    if (!p || isOnAt(p, now)) app.watchChannel(index);
    else if (p.stop <= now) { if (app.canCatchUp(channel, p, now)) app.playCatchUp(index, p); }
    else app.toggleReminder(channel, p);
  };
  const hint = (channel: XtreamChannel, p: Programme | null) =>
    !p || isOnAt(p, now) ? "OK to watch" : p.stop <= now ? (app.canCatchUp(channel, p, now) ? "OK to watch it again" : "Over, and not in this channel's archive")
      : app.hasReminder(channel, p) ? "Reminder set  ·  OK to cancel it" : "OK to be reminded when it starts";
  const slots = [0, 1, 2, 3, 4, 5, 6].map((i) => start + i * 1800);
  return (
    <div class="guide">
      <div class="guide-about">
        {focused ? (
          <>
            <div class="name">{focused.programme?.title ?? focused.channel.name}</div>
            <div class="facts">
              {[focused.channel.name, focused.programme ? `${time(focused.programme.start)}–${time(focused.programme.stop)}` : null, hint(focused.channel, focused.programme)].filter(Boolean).join("  ·  ")}
            </div>
            {focused.programme?.description ? <div class="facts about-text">{focused.programme.description}</div> : null}
          </>
        ) : <div class="facts">Loading the guide…</div>}
      </div>
      <div class="guide-head">
        {slots.map((t) => <span key={t} style={{ left: `${x(t) + 13}rem` }}>{time(t)}</span>)}
        <i class="guide-now" style={{ left: `${x(now) + 13}rem` }} />
      </div>
      {shown.map((ch, index) => {
        const listing = (live.table[ch.streamId] ?? live.guide[ch.streamId] ?? []).filter((p) => p.stop > start && p.start < end);
        return (
          <div key={ch.streamId} class="guide-row">
            <div class="guide-channel">{[ch.number > 0 ? ch.number : null, ch.name].filter(Boolean).join("  ")}</div>
            <div class="guide-line">
              {listing.length ? listing.map((p) => (
                <button key={p.start} data-focus data-autofocus={index === 0 && isOnAt(p, now) ? "" : undefined}
                  class={"programme" + (isOnAt(p, now) ? " now" : p.stop <= now ? " past" : "") + (app.hasReminder(ch, p) ? " reminded" : "")}
                  style={{ left: `${x(p.start)}rem`, width: `${Math.max(0.6, x(p.stop) - x(p.start) - 0.2)}rem` }}
                  onFocus={() => setFocused({ channel: ch, programme: p })}
                  onClick={() => press(index, ch, p)}>
                  {app.hasReminder(ch, p) ? "⏰ " : ""}{p.title}
                </button>
              )) : (
                <button data-focus data-autofocus={index === 0 ? "" : undefined} class="programme now" style={{ left: "0rem", width: `${x(end) - 0.2}rem` }}
                  onFocus={() => setFocused({ channel: ch, programme: null })} onClick={() => press(index, ch, null)}>
                  {ch.name}
                </button>
              )}
            </div>
          </div>
        );
      })}
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
  const catchUp = state.live.catchUp;
  const url = catchUp ? catchUp.url : app.channelUrl(channel);
  const now = Math.floor(Date.now() / 1000);
  const on = (state.live.guide[channel.streamId] ?? []).find((p) => isOnAt(p, now));

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
      case "yellow":
        // Start over: this programme from its beginning, from the channel's archive.
        if (on && app.canCatchUp(channel, on)) app.playCatchUp(state.live.watching ?? 0, on);
        setBanner(true);
        return true;
      case "blue": app.goLive(); setBanner(true); return true;
      case "left": case "rewind": if (catchUp && video.current) video.current.currentTime = Math.max(0, video.current.currentTime - 10); setBanner(true); return true;
      case "right": case "forward": if (catchUp && video.current) video.current.currentTime += 10; setBanner(true); return true;
      case "playPause": if (catchUp && video.current) { if (video.current.paused) void video.current.play(); else video.current.pause(); } return true;
    }
    return true;
  }), [channel.streamId, catchUp?.url, on?.start]);
  const fav = state.live.favorites.includes(channel.streamId);
  const startOver = !catchUp && on && app.canCatchUp(channel, on);
  return (
    <div class="player" data-layer>
      <video ref={video} class="video" playsInline />
      {waiting && !error ? <div class="player-wait"><Spinner /></div> : null}
      {error ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      {typed ? <div class="typed">{typed}</div> : null}
      <div class={"player-bar" + (banner ? " on" : "")}>
        <div class="player-title">{[channel.number > 0 ? channel.number : null, channel.name].filter(Boolean).join("  ")}{fav ? "  ♥" : ""}</div>
        {catchUp ? <div class="facts">{catchUp.programme.title}  ·  {time(catchUp.programme.start)}–{time(catchUp.programme.stop)}  ·  From the archive</div>
          : on ? <div class="facts">{on.title}  ·  {time(on.start)}–{time(on.stop)}</div> : null}
        <div class="facts" style={{ color: "var(--faint)" }}>
          {[catchUp ? "Left and right skip  ·  Blue key: go live" : "Up and down change channel", startOver ? "Yellow key: start over" : null,
            `Green key: ${fav ? "remove from" : "add to"} Favorites`].filter(Boolean).join("  ·  ")}
        </div>
      </div>
    </div>
  );
}

/** "Starting now": a reminder from the guide, as the Fire TV puts it up. Watch, or Dismiss. */
export function ReminderNotice(props: { app: App; state: AppState }) {
  const due = props.state.live.due;
  useRescue([due?.start]);
  useReturnFocus();
  if (!due) return null;
  return (
    <div class="layer notice-layer" data-layer>
      <div class="notice">
        <div class="facts" style={{ color: "var(--accent)" }}>Starting now</div>
        <div class="player-title">{due.title}</div>
        <div class="facts">On {due.channelName}</div>
        <div class="actions">
          <Pill label="Watch" primary autofocus onPress={() => void props.app.watchReminder()} />
          <Pill label="Dismiss" onPress={() => props.app.dismissReminder()} />
        </div>
      </div>
    </div>
  );
}
