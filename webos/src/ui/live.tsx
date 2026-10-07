import { clearSource, detach, setSource } from "./media";
import { isVega, onAway } from "../core/platform";
import { useEffect, useRef, useState, useLayoutEffect } from "preact/hooks";
import type { Programme, XtreamCategory, XtreamChannel } from "../api/xtream";
import { isOnAt, progressAt } from "../api/xtream";
import type { App, AppState } from "../app/store";
import { focus, onKeys } from "./focus";
import { Pill, Spinner, useRescue, useReturnFocus } from "./parts";
import { MAX_TILES, focusRects, gridRects, hasSpareCell, movedTile, nextPlace, tileOrder, withoutTile } from "../core/multiview";
import { AddTile, ExtraTile, SCREEN_H, SCREEN_W, TILE_GAP, Tile, TileMenu, useAspect } from "./multiview";

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

/** The guide's window: from the half hour before the last one, two and a half hours: as much as fits. */
const WINDOW_BEFORE = 30 * 60;
const WINDOW = 150 * 60;
/** How wide a minute is in the grid, as on the Fire TV. */
const REM_PER_MINUTE = 0.36;
/** The channels' column, tiles and the gap after them. */
const CHANNEL_REM = 10;

/** A channel's own color behind its logo, the same each time, as the Fire TV picks it. */
export function channelTint(id: string): string {
  const palette = ["#2C3563", "#7A3B42", "#2F5E52", "#6A4E2A", "#473469", "#2B5066", "#6B3559", "#3F5A2E"];
  let hash = 7;
  for (const c of id) hash = (Math.imul(hash, 31) + c.charCodeAt(0)) | 0;
  return palette[((hash % palette.length) + palette.length) % palette.length];
}

/**
 * A channel's name to stand in for its logo: the name itself, without a provider's region
 * or group prefix ("UK | ", "US: ", "[DE] ").
 */
export function channelLabel(name: string): string {
  const trimmed = name.trim();
  return trimmed.replace(/^(\[[^\]]{1,6}\]|[A-Z0-9]{2,4}\s*[|:\-])\s*/, "") || trimmed;
}

/**
 * The guide as a grid, as on the Fire TV: channels down, time across. OK on what's on
 * watches it; on something that's over, from the archive, where the channel keeps one;
 * on something to come, a reminder.
 */
function GuideGrid(props: {
  app: App; state: AppState; now: number;
  /** Over a channel that's playing: these channels (another category's, perhaps), a header of its own, OK and a held OK the overlay's. */
  overlay?: {
    channels: XtreamChannel[]; category: string;
    press: (index: number, channel: XtreamChannel, p: Programme | null) => void;
    hold: (index: number, channel: XtreamChannel, p: Programme | null, from: HTMLElement) => void;
    /** Opened to put a channel in Multiview: what OK does, "Add" or "Replace with". */
    verb?: string;
  };
}) {
  const { app, state, now, overlay } = props;
  const live = state.live;
  const [focused, setFocused] = useState<{ channel: XtreamChannel; programme: Programme | null } | null>(null);
  const start = Math.floor(now / 1800) * 1800 - WINDOW_BEFORE;
  const end = start + WINDOW;
  const shown = (overlay ? overlay.channels : live.channels).slice(0, 60);
  useEffect(() => { void app.loadTable(shown.slice(0, 30)); }, [shown.map((ch) => ch.streamId).join(",")]);
  const x = (t: number) => ((Math.max(start, Math.min(end, t)) - start) / 60) * REM_PER_MINUTE;
  const press = (index: number, channel: XtreamChannel, p: Programme | null) => {
    if (overlay) overlay.press(index, channel, p);
    else if (!p || isOnAt(p, now)) app.watchChannel(index);
    else if (p.stop <= now) { if (app.canCatchUp(channel, p, now)) app.playCatchUp(index, p); }
    else app.toggleReminder(channel, p);
  };
  const hint = (channel: XtreamChannel, p: Programme | null) =>
    overlay?.verb ? `OK to ${overlay.verb.toLowerCase()} this channel  ·  hold OK for more`
    : p && p.stop <= now ? (app.canCatchUp(channel, p, now) ? "OK to watch it again" : "Over, and not in this channel's archive")
      // Over a channel, as on the Fire TV: OK watches; a reminder is in the held OK's menu.
      : overlay ? "OK to watch  ·  hold OK for more"
        : !p || isOnAt(p, now) ? "OK to watch"
          : app.hasReminder(channel, p) ? "Reminder set  ·  OK to cancel it" : "OK to be reminded when it starts";
  const slots = [0, 1, 2, 3, 4, 5, 6].map((i) => start + i * 1800);
  // The highlighted channel, playing beside what's on it, a moment after the cursor stops on it.
  const [previewing, setPreviewing] = useState<XtreamChannel | null>(null);
  useEffect(() => {
    if (!state.prefs.guidePreview || !focused) return;
    const t = setTimeout(() => setPreviewing(focused.channel), 800);
    return () => clearTimeout(t);
  }, [focused?.channel.streamId, state.prefs.guidePreview]);
  const previewUrl = state.prefs.guidePreview && previewing ? app.channelUrl(previewing) : null;
  const current = focused?.channel.streamId;
  const nowFirst = () => {
    const row = document.querySelector<HTMLElement>(`.guide-row[data-channel="${current ?? shown[0]?.streamId}"] .programme.now`)
      ?? document.querySelector<HTMLElement>(".guide .programme.now");
    if (row) focus(row);
  };
  function grid() {
    return (
      <div class="guide-grid">
        <div class="guide-head">
          {slots.filter((t) => t < end).map((t) => <span key={t} style={{ left: `${x(t) + CHANNEL_REM}rem` }}>{time(t)}</span>)}
        </div>
        <i class="guide-now" style={{ left: `${x(now) + CHANNEL_REM}rem` }} />
        {shown.map((ch, index) => {
          const listing = (live.table[ch.streamId] ?? live.guide[ch.streamId] ?? []).filter((p) => p.stop > start && p.start < end);
          const fav = live.favorites.includes(ch.streamId);
          return (
            <div key={ch.streamId} class={"guide-row" + (current === ch.streamId ? " current" : "")} data-channel={ch.streamId}>
              <div class="guide-channel" style={{ background: channelTint(String(ch.streamId)) }}>
                {ch.icon ? <img src={ch.icon} alt={ch.name} /> : <span>{channelLabel(ch.name)}</span>}
                {fav ? <b class="guide-fav">♥</b> : null}
              </div>
              <div class="guide-line">
                {listing.length ? listing.map((p) => {
                  const replay = p.stop <= now && app.canCatchUp(ch, p, now);
                  const reminded = app.hasReminder(ch, p);
                  const slot = `${time(p.start)} – ${time(p.stop)}`;
                  return (
                    <button key={p.start} data-focus data-autofocus={!overlay && index === 0 && isOnAt(p, now) ? "" : undefined} ref={holdable(index, ch, p)}
                      class={"programme" + (isOnAt(p, now) ? " now" : p.stop <= now ? (replay ? " replay" : " past") : "") + (reminded ? " reminded" : "")}
                      style={{ left: `${x(p.start)}rem`, width: `${Math.max(0.6, x(p.stop) - x(p.start) - 0.3)}rem` }}
                      onFocus={() => setFocused({ channel: ch, programme: p })}
                      onClick={() => press(index, ch, p)}>
                      <span class="programme-title">{p.title}</span>
                      <span class="programme-slot">{replay ? `Watch again  ·  ${slot}` : reminded ? `Reminder set  ·  ${slot}` : slot}</span>
                    </button>
                  );
                }) : (
                  <button data-focus data-autofocus={!overlay && index === 0 ? "" : undefined} ref={holdable(index, ch, null)} class="programme now empty" style={{ left: "0rem", width: `${x(end) - 0.3}rem` }}
                    onFocus={() => setFocused({ channel: ch, programme: null })} onClick={() => press(index, ch, null)}>
                    <span class="programme-title">No guide data</span>
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    );
  }
  // A held OK on a programme, over a channel: its menu. The listener goes on once; what it
  // opens is this render's.
  const latest = useRef(overlay);
  latest.current = overlay;
  const holdable = (index: number, channel: XtreamChannel, p: Programme | null) => (el: HTMLElement | null) => {
    if (!el || !overlay) return;
    const held = el as HTMLElement & { holding?: () => void };
    if (!held.holding) el.addEventListener("hold", () => held.holding?.());
    held.holding = () => latest.current?.hold(index, channel, p, el);
  };
  if (overlay) {
    return (
      <div class="guide">
        <div class="guide-about">
          <div class="guide-category">{overlay.category}</div>
          {focused ? (
            <>
              <div class="name">{overlay.verb ? `${overlay.verb}: ${focused.channel.name}` : focused.programme?.title ?? focused.channel.name}</div>
              <div class="facts">
                {[overlay.verb ? focused.programme?.title : focused.channel.name, focused.programme ? `${time(focused.programme.start)} – ${time(focused.programme.stop)}` : null, hint(focused.channel, focused.programme)].filter(Boolean).join("  ·  ")}
              </div>
            </>
          ) : <div class="facts">Loading the guide…</div>}
        </div>
        {grid()}
      </div>
    );
  }
  return (
    <div class="guide">
      <div class="guide-top">
        <div class="guide-about">
          <div class="guide-category">{live.category?.name ?? ""}</div>
          {focused ? (
            <>
              <div class="name">{focused.programme?.title ?? focused.channel.name}</div>
              <div class="facts">
                {[focused.channel.name, focused.programme ? `${time(focused.programme.start)} – ${time(focused.programme.stop)}` : null, hint(focused.channel, focused.programme)].filter(Boolean).join("  ·  ")}
              </div>
              {focused.programme?.description ? <div class="facts about-text">{focused.programme.description}</div> : null}
            </>
          ) : <div class="facts">Loading the guide…</div>}
          <div class="toolbar flush">
            <Pill label="Now" onPress={nowFirst} />
            <Pill label={live.busy ? "Loading…" : "Refresh"} onPress={() => void app.refreshGuide()} />
          </div>
        </div>
        {state.prefs.guidePreview ? (
          <div class="guide-preview">{previewUrl ? <PreviewVideo url={previewUrl} /> : null}</div>
        ) : null}
      </div>
      {grid()}
    </div>
  );
}

/**
 * The guide over a channel that keeps playing behind it, as on the Fire TV: down from the
 * channel. The categories are a row above, up from the first channel; another category's
 * channels are browsed here without changing what's playing until one is chosen. OK
 * watches (what's over, from the archive); a held OK has the channel's menu; Back closes it.
 */
function GuideOverlay(props: {
  app: App; state: AppState; playing: XtreamChannel; onClose: () => void;
  /** Multiview, on Vega: OK puts the channel beside what's playing, or in tile [replaces]. */
  pick?: { replaces: number | null } | null;
  /** Whether Multiview is on offer here at all, and whether there's room for another channel. */
  multiview?: { canAdd: boolean; add: (channel: XtreamChannel, replaces: number | null) => void };
}) {
  const { app, state, playing, onClose, pick, multiview } = props;
  const live = state.live;
  const [now, setNow] = useState(Math.floor(Date.now() / 1000));
  useEffect(() => {
    const t = setInterval(() => setNow(Math.floor(Date.now() / 1000)), 30_000);
    return () => clearInterval(t);
  }, []);
  const [browse, setBrowse] = useState<{ category: XtreamCategory | null; channels: XtreamChannel[]; loading: boolean }>(
    { category: live.category, channels: live.channels, loading: false });
  const [menu, setMenu] = useState<{ index: number; channel: XtreamChannel; programme: Programme | null; from: HTMLElement } | null>(null);
  const categories = app.shownCategories();

  // On the channel that's playing, at what's on now.
  const landOn = (streamId: number | null) => {
    const el = (streamId != null ? document.querySelector<HTMLElement>(`.guide-overlay .guide-row[data-channel="${streamId}"] .programme.now`) : null)
      ?? document.querySelector<HTMLElement>(".guide-overlay .guide-row .programme.now")
      ?? document.querySelector<HTMLElement>(".guide-overlay .programme");
    if (el) focus(el);
  };
  // Before it's drawn: a press as soon as it's up finds the cursor already there.
  useLayoutEffect(() => { landOn(playing.streamId); }, []);

  const choose = async (category: XtreamCategory) => {
    if (category.id === browse.category?.id) return;
    setBrowse({ category, channels: [], loading: true });
    const channels = await app.channelsOf(category).catch(() => [] as XtreamChannel[]);
    setBrowse((b) => (b.category === category ? { category, channels, loading: false } : b));
    void app.loadGuide(channels.slice(0, 40));
  };
  // Another category's channels in: the cursor goes to the first.
  useEffect(() => { if (!browse.loading && browse.channels !== live.channels) landOn(null); }, [browse.channels]);

  const watch = (index: number, channel: XtreamChannel, p: Programme | null) => {
    // Opened to add or replace a tile: OK puts the channel there, whatever's on it. The main
    // tile is the player's own channel, so replacing it is changing channel, as here.
    if (pick && multiview) {
      if (pick.replaces !== 0) {
        onClose();
        multiview.add(channel, pick.replaces);
        return;
      }
      p = null;
    }
    const over = p != null && p.stop <= now;
    if (over && !app.canCatchUp(channel, p!, now)) return;
    onClose();
    if (browse.channels !== live.channels && browse.category) app.watchIn(browse.category, browse.channels, index, over ? p : null);
    else if (over) app.playCatchUp(index, p!);
    else app.watchChannel(index);
  };

  // Back: the menu, then the guide. Everything else is the cursor's, inside the guide.
  // Read as it is at the press: a press straight after the menu went found it still open.
  const menuNow = useRef(menu);
  menuNow.current = menu;
  useEffect(() => onKeys((a) => {
    if (a !== "back") return false;
    const open = menuNow.current;
    if (open) {
      menuNow.current = null;
      setMenu(null);
      focus(open.from);
    } else onClose();
    return true;
  }), []);

  const upcoming = menu && menu.programme && menu.programme.start > now ? menu.programme : null;
  const firstOption = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (menu) focus(firstOption.current); }, [menu]);
  const closeMenu = () => { const from = menu?.from; menuNow.current = null; setMenu(null); if (from) focus(from); };
  return (
    <div class="guide-overlay" data-layer>
      {categories.length > 1 ? (
        <div class="toolbar chips guide-cats">
          {categories.map((c) => <Pill key={c.id} label={c.name} on={c.id === browse.category?.id} onPress={() => void choose(c)} />)}
        </div>
      ) : null}
      {browse.channels.length ? (
        <GuideGrid app={app} state={state} now={now}
          overlay={{ channels: browse.channels, category: browse.category?.name ?? "", press: watch, hold: (index, channel, programme, from) => setMenu({ index, channel, programme, from }),
            verb: pick && multiview ? (pick.replaces != null ? "Replace with" : "Add") : undefined }} />
      ) : (
        <div class="guide"><div class="guide-about">
          <div class="guide-category">{browse.category?.name ?? ""}</div>
          {browse.loading ? <Spinner /> : <div class="facts">Nothing in this category  ·  pick another above</div>}
        </div></div>
      )}
      {menu ? (
        <div class="options guide-menu" data-layer>
          <h3>{menu.channel.name}</h3>
          <button ref={firstOption} class="option" data-focus onClick={() => {
            menuNow.current = null;
            setMenu(null);
            onClose();
            if (browse.channels !== live.channels && browse.category) app.watchIn(browse.category, browse.channels, menu.index);
            else app.watchChannel(menu.index);
          }}>Watch this channel</button>
          {multiview ? (
            // Replacing a tile never adds one, so only adding is held to the limit.
            <button class="option" data-focus onClick={() => {
              if (!multiview.canAdd && pick?.replaces == null) return;
              menuNow.current = null;
              setMenu(null);
              onClose();
              multiview.add(menu.channel, pick?.replaces ?? null);
            }}>
              <span class="option-text">
                {pick?.replaces != null ? "Put it in that tile" : "Add beside what's playing"}
                {!multiview.canAdd && pick?.replaces == null ? <span class="option-note">You can watch up to four channels at once.</span> : null}
              </span>
            </button>
          ) : null}
          {upcoming ? (
            <button class="option" data-focus onClick={() => { app.toggleReminder(menu.channel, upcoming); closeMenu(); }}>
              {app.hasReminder(menu.channel, upcoming) ? "Cancel the reminder" : "Remind me"}<span class="facts">{upcoming.title}</span>
            </button>
          ) : null}
          <button class="option" data-focus onClick={() => { app.toggleFavorite(menu.channel); closeMenu(); }}>
            {live.favorites.includes(menu.channel.streamId) ? "Remove from Favorites" : "Add to Favorites"}
          </button>
        </div>
      ) : null}
    </div>
  );
}

const NOT_PLAYING = "This channel isn't playing. It may be off air, or your provider's connection limit reached.";

/**
 * A channel, full screen. Up and down or Channel Up/Down change it; numbers tune one;
 * OK shows what's on; the green key favorites it; Back goes back to the list.
 *
 * On Vega, as on the Fire TV, other channels can play beside it (Multiview): holding OK
 * has the tile's menu, the arrows walk the tiles and only the one with the cursor on it is
 * heard, OK makes a tile full screen and back again, and Back closes the tile the cursor's
 * on before it leaves the channel.
 */
export function LivePlayer(props: { app: App; state: AppState; channel: XtreamChannel }) {
  const { app, state, channel } = props;
  const vega = isVega();
  // The guide over the channel: while it's up, the keys are its. Opened from a tile, it
  // says what OK will do there: add a channel beside, or put one in that tile.
  const [guide, setGuide] = useState<{ pick: { replaces: number | null } | null } | null>(null);
  const guideUp = useRef(false);
  guideUp.current = guide != null;
  const video = useRef<HTMLVideoElement>(null);
  const aspect = useAspect(video);
  const [banner, setBanner] = useState(true);
  // Each key: the banner's few seconds start again.
  const [poke, setPoke] = useState(0);
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(true);
  const catchUp = state.live.catchUp;
  const url = catchUp ? catchUp.url : app.channelUrl(channel);
  const now = Math.floor(Date.now() / 1000);
  const on = (state.live.guide[channel.streamId] ?? []).find((p) => isOnAt(p, now));

  // Multiview. Tile 0 is this channel, in the video above; the rest are the store's.
  const tiles = vega && !catchUp ? state.live.multiview : [];
  const tileCount = tiles.length + 1;
  const focusLayout = state.prefs.multiviewLayout === "focus";
  const spare = hasSpareCell(tileCount, focusLayout);
  const slotCount = tileCount + (spare ? 1 : 0);
  const addSlot = spare ? tileCount : -1;
  // The place on screen the cursor's on; `order` is which tile is in each place.
  const [focusedPlace, setFocusedPlace] = useState(0);
  const place = focusedPlace < slotCount ? focusedPlace : 0;
  const [order, setOrder] = useState<number[]>([0]);
  const places = [...tileOrder(order, tileCount), ...(spare ? [addSlot] : [])];
  const focusedId = places[place] ?? place;
  // One tile filling the screen, the rest still running behind it; held by which tile it is.
  const [zoomedTile, setZoomed] = useState<number | null>(null);
  const zoomed = zoomedTile != null && tileCount > 1 && zoomedTile <= tiles.length ? zoomedTile : null;
  const [tileMenu, setTileMenu] = useState<number | null>(null);
  const multi = slotCount > 1;
  const rects = focusLayout
    ? focusRects(slotCount, place, SCREEN_W, SCREEN_H, TILE_GAP)
    : gridRects(slotCount, SCREEN_W, SCREEN_H, TILE_GAP);
  const rectOf = (tile: number) => rects[places.indexOf(tile)] ?? rects[0];
  // Only the tile with the cursor on it is heard.
  const mainHeard = !multi || focusedId === 0;
  useEffect(() => { if (video.current) video.current.muted = !mainHeard; }, [mainHeard]);
  // A channel changed: the cursor goes to it, wherever it's been moved to.
  useEffect(() => { setFocusedPlace(Math.max(0, tileOrder(order, tileCount).indexOf(0))); }, [channel.streamId]);

  useEffect(() => {
    const v = video.current;
    if (!v || !url) return;
    setError(null);
    setWaiting(true);
    setSource(v, url);
    void v.play().catch(() => undefined);
    setBanner(true);
    setPoke((n) => n + 1);
    const ready = () => setWaiting(false);
    const fail = () => setError(NOT_PLAYING);
    v.addEventListener("playing", ready);
    v.addEventListener("error", fail);
    return () => {
      v.removeEventListener("playing", ready);
      v.removeEventListener("error", fail);
    };
  }, [url]);
  // hls.js or mpegts.js, on Vega: stopped with the channel, or it goes on fetching.
  useEffect(() => () => { if (video.current) detach(video.current); }, []);
  /*
   * Out of the app and back, on Vega: the TV takes the decoder back while it's away, and a
   * channel can't be picked up where it was anyway. It stops on leaving and joins the
   * channel again, as it is now, on return.
   */
  useEffect(() => {
    if (!vega) return;
    return onAway((away) => {
      const v = video.current;
      if (!v || !url) return;
      if (away) clearSource(v);
      else {
        setWaiting(true);
        setSource(v, url);
        void v.play().catch(() => undefined);
      }
    });
  }, [url]);

  /*
   * The banner goes a few seconds after the last key, however it came up. It used to go
   * only once, after a channel started: brought back by a key, it stayed up for good.
   */
  useEffect(() => {
    if (!banner || guide) return;
    const t = setTimeout(() => setBanner(false), 5_000);
    return () => clearTimeout(t);
  }, [banner, guide, poke]);

  // Digits typed in a row tune that channel a moment after the last one.
  useEffect(() => {
    if (!typed) return;
    const t = setTimeout(() => {
      if (!app.tuneNumber(Number(typed))) setError(`No channel ${typed} in ${state.live.category?.name ?? "this list"}.`);
      setTyped("");
    }, 1_500);
    return () => clearTimeout(t);
  }, [typed]);

  /** Closes tile [tile] (1 or more): the main channel keeps the cursor, wherever it is. */
  const closeTile = (tile: number) => {
    const kept = withoutTile(places.filter((t) => t < tileCount), tile);
    setOrder(kept);
    setFocusedPlace(Math.max(0, kept.indexOf(0)));
    app.removeFromMultiview(tile - 1);
  };
  /** OK on the screen: the banner with one channel; with several, a tile full screen and back, or the spare cell's guide. */
  const press = () => {
    setPoke((n) => n + 1);
    if (!multi) setBanner((b) => !b);
    else if (place === addSlot) setGuide({ pick: { replaces: null } });
    else setZoomed(zoomed === focusedId ? null : focusedId);
  };
  const hold = () => {
    if (catchUp || place === addSlot) return;
    setTileMenu(focusedId);
  };

  // The keys read what's on screen as it is at the press.
  const now$ = useRef({ multi, zoomed, focusedId, place, slotCount, focusLayout, tileMenu, tiles: tiles.length, closeTile });
  now$.current = { multi, zoomed, focusedId, place, slotCount, focusLayout, tileMenu, tiles: tiles.length, closeTile };
  useEffect(() => onKeys((a) => {
    if (guideUp.current) return false;
    const m = now$.current;
    // The tile menu has the cursor, and its own Back.
    if (m.tileMenu != null) return false;
    setPoke((n) => n + 1);
    if (typeof a === "object") { setTyped((t) => (t + a.digit).slice(0, 4)); return true; }
    if (a === "back") {
      // Out of a full-screen tile, back to the others; then the tile the cursor's on goes.
      if (m.zoomed != null) { setZoomed(null); return true; }
      if (m.focusedId >= 1 && m.focusedId <= m.tiles) { m.closeTile(m.focusedId); return true; }
      return false; // the app's Back: off the channel, back to its list
    }
    // With several up, the arrows walk the tiles. Only a press off the edge does anything
    // else: down opens the guide; sideways never retunes a tile somebody was walking past.
    if (m.multi && (a === "up" || a === "down" || a === "left" || a === "right")) {
      if (m.zoomed != null) return true;
      const dx = a === "left" ? -1 : a === "right" ? 1 : 0;
      const dy = a === "up" ? -1 : a === "down" ? 1 : 0;
      const next = nextPlace(m.slotCount, m.place, dx, dy, m.focusLayout);
      if (next != null) setFocusedPlace(next);
      else if (dy > 0) setGuide({ pick: null });
      return true;
    }
    switch (a) {
      case "stop": app.stopLive(); return true;
      // As on the Fire TV: left and right change channel (or skip, in the archive), down opens the guide.
      case "channelUp": app.stepChannel(-1); return true;
      case "channelDown": app.stepChannel(1); return true;
      case "down": setGuide({ pick: null }); setBanner(false); return true;
      // On Vega, OK is the screen's own button's, below, so a held OK can be told from a press.
      case "ok": if (vega) return false; setBanner((b) => !b); return true;
      case "up": case "info": setBanner((b) => !b); return true;
      case "green": app.toggleFavorite(channel); setBanner(true); return true;
      case "yellow":
        // Start over: this programme from its beginning, from the channel's archive.
        if (on && app.canCatchUp(channel, on)) app.playCatchUp(state.live.watching ?? 0, on);
        setBanner(true);
        return true;
      case "blue": app.goLive(); setBanner(true); return true;
      case "left": case "rewind":
        if (catchUp && video.current) video.current.currentTime = Math.max(0, video.current.currentTime - 10);
        else if (a === "left") app.stepChannel(-1);
        setBanner(true);
        return true;
      case "right": case "forward":
        if (catchUp && video.current) video.current.currentTime += 10;
        else if (a === "right") app.stepChannel(1);
        setBanner(true);
        return true;
      case "playPause": if (catchUp && video.current) { if (video.current.paused) void video.current.play(); else video.current.pause(); } return true;
    }
    return true;
  }), [channel.streamId, catchUp?.url, on?.start]);

  // OK held on the screen, on Vega: the tile's menu. The listener goes on once; what it does is this render's.
  const held = useRef(hold);
  held.current = hold;
  const screen = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const el = screen.current;
    if (!el) return;
    const onHold = () => held.current();
    el.addEventListener("hold", onHold);
    return () => el.removeEventListener("hold", onHold);
  }, [vega]);
  // Back on the screen once a menu or the guide has gone, so OK is heard again.
  useEffect(() => { if (vega && tileMenu == null && !guide) focus(screen.current); }, [tileMenu, guide]);

  const fav = state.live.favorites.includes(channel.streamId);
  const startOver = !catchUp && on && app.canCatchUp(channel, on);
  // The banner is one channel's: with several up, only over the main one full screen.
  const bannerShown = !multi || zoomed === 0;
  const menuPlace = tileMenu != null ? places.indexOf(tileMenu) : -1;
  const nameOf = (tile: number) => (tile === 0 ? channel.name : tiles[tile - 1]?.name ?? "");
  return (
    <div class="player" data-layer>
      <Tile rect={multi ? rectOf(0) : rects[0]} zoomed={zoomed === 0} framed={multi} name={channel.name} heard={mainHeard} aspect={aspect}
        message={multi && zoomed !== 0 ? (error === NOT_PLAYING ? "This channel isn't playing." : error) : null}>
        <video ref={video} class="video" playsInline />
      </Tile>
      {tiles.map((ch, i) => {
        const tileUrl = app.channelUrl(ch);
        return tileUrl ? (
          <ExtraTile key={ch.streamId} url={tileUrl} name={ch.name} rect={rectOf(i + 1)} heard={focusedId === i + 1} zoomed={zoomed === i + 1} />
        ) : null;
      })}
      {spare ? <AddTile rect={rects[places.indexOf(addSlot)]} focused={place === addSlot} /> : null}
      {vega ? <button ref={screen} class="player-ok" data-focus data-autofocus aria-label={channel.name} onClick={press} /> : null}
      {/* Under the guide, only the channel: what's said over it otherwise would be under the guide's words. */}
      {waiting && !error && !guide && (!multi || zoomed === 0) ? <div class="player-wait"><Spinner /></div> : null}
      {error && !guide && (!multi || zoomed === 0) ? <div class="player-wait"><p class="note error">{error}</p></div> : null}
      {typed ? <div class="typed">{typed}</div> : null}
      {guide ? (
        <GuideOverlay app={app} state={state} playing={channel} onClose={() => setGuide(null)} pick={guide.pick}
          multiview={vega && !catchUp ? {
            canAdd: tileCount < MAX_TILES,
            add: (picked, replaces) => {
              if (replaces != null) app.replaceInMultiview(replaces, picked);
              else app.addToMultiview(picked);
            },
          } : undefined} />
      ) : null}
      {tileMenu != null ? (
        <TileMenu
          choices={{
            name: nameOf(tileMenu),
            canMaximize: multi,
            canAdd: tileCount < MAX_TILES,
            // Never into the spare cell: that stays last, where it's looked for.
            canMoveBack: tileCount > 1 && menuPlace > 0,
            canMoveOn: tileCount > 1 && menuPlace >= 0 && menuPlace < tileCount - 1,
            canSave: tileCount > 1,
            saved: app.savedMultiviewLabel(),
            canClose: tileMenu >= 1 && tileMenu <= tiles.length,
          }}
          actions={{
            maximize: () => { setTileMenu(null); setZoomed(tileMenu); },
            add: () => { setTileMenu(null); setGuide({ pick: { replaces: null } }); },
            replace: () => { setTileMenu(null); setGuide({ pick: { replaces: tileMenu } }); },
            move: (delta) => {
              setTileMenu(null);
              setOrder(movedTile(places.filter((t) => t < tileCount), menuPlace, delta));
              // The cursor goes with the tile, so moving it again is one more hold.
              setFocusedPlace(menuPlace + delta);
            },
            save: () => { setTileMenu(null); app.saveMultiview(places.filter((t) => t < tileCount)); },
            openSaved: () => { setTileMenu(null); setOrder([0]); setFocusedPlace(0); setZoomed(null); void app.openSavedMultiview(); },
            close: () => { setTileMenu(null); closeTile(tileMenu); },
            cancel: () => setTileMenu(null),
          }} />
      ) : null}
      {guide || !bannerShown ? null : (
        <div class={"player-bar" + (banner ? " on" : "")}>
          <div class="player-title">{[channel.number > 0 ? channel.number : null, channel.name].filter(Boolean).join("  ")}{fav ? "  ♥" : ""}</div>
          {catchUp ? <div class="facts">{catchUp.programme.title}  ·  {time(catchUp.programme.start)}–{time(catchUp.programme.stop)}  ·  From the archive</div>
            : on ? <div class="facts">{on.title}  ·  {time(on.start)}–{time(on.stop)}</div> : null}
          <div class="facts" style={{ color: "var(--faint)" }}>
            {[multi ? "OK or Back: all the channels" : catchUp ? "Left and right skip  ·  Blue key: go live" : "Left and right change channel  ·  Down: the guide", startOver ? "Yellow key: start over" : null,
              `Green key: ${fav ? "remove from" : "add to"} Favorites`, vega && !catchUp && !multi ? "Hold OK: more channels" : null].filter(Boolean).join("  ·  ")}
          </div>
        </div>
      )}
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

/** The channel the cursor's on, small, in the guide: played as the full-screen channel is. */
function PreviewVideo(props: { url: string }) {
  const ref = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    setSource(v, props.url);
    void v.play().catch(() => undefined);
    return () => clearSource(v);
  }, [props.url]);
  return <video ref={ref} autoPlay playsInline />;
}
