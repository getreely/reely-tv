import { useEffect, useState } from "preact/hooks";
import * as plex from "../api/plex";
import type { PlexHomeUser, PlexItem } from "../api/plex";
import { groupKey } from "../app/home";
import type { App, AppState, Kind } from "../app/store";
import { formatDuration } from "../core/quality";
import { onKeys } from "./focus";
import { Card, Pill, Qr, Row, Spinner, useRescue } from "./parts";
import { PersonButton } from "./search";

const episodeLine = (i: PlexItem) =>
  i.type === "episode" ? [i.parentIndex != null ? `S${i.parentIndex}` : null, i.index != null ? `E${i.index}` : null, i.title].filter(Boolean).join(" · ") : plex.caption(i);

/** Opening a title: a show's page for an episode, landing on that episode. */
export function open(app: App, item: PlexItem) {
  if (item.type === "collection") {
    app.navigate({ name: "collection", item });
  } else if (item.type === "episode" && item.grandparentRatingKey) {
    app.navigate({ name: "detail", ratingKey: item.grandparentRatingKey, serverBase: item.serverBase, episodeKey: item.ratingKey });
  } else {
    app.navigate({ name: "detail", ratingKey: item.ratingKey, serverBase: item.serverBase });
  }
}

export function SignIn(props: { app: App; state: AppState }) {
  const { signIn, plex: session } = props.state;
  useRescue([signIn.code, session.token, session.finding]);
  if (session.token && !session.baseUrl) {
    return (
      <div class="center">
        <h1 class="big">{session.finding ? "Finding your Plex server" : "Can't find your Plex server"}</h1>
        <p class="note">
          {session.finding
            ? "You're signed in. Looking for your server, at home first, then over the internet."
            : session.error ?? "Make sure Plex Media Server is running and signed in to the same account."}
        </p>
        {session.finding ? <Spinner /> : (
          <div class="actions">
            <Pill label="Try again" primary autofocus onPress={() => void props.app.connect(session.token!)} />
            <Pill label="Use a different Plex account" onPress={() => props.app.signOut()} />
          </div>
        )}
      </div>
    );
  }
  if (!signIn.code) {
    return (
      <div class="center">
        <h1 class="big">Sign in to watch your library</h1>
        <p class="note">Your movies and shows from Plex, including libraries shared with you.</p>
        {signIn.error ? <p class="note error">{signIn.error}</p> : null}
        {signIn.busy ? <Spinner /> : <Pill label="Sign in with Plex" primary autofocus onPress={() => void props.app.startSignIn()} />}
      </div>
    );
  }
  return (
    <div class="center">
      <div class="signin">
        {signIn.url ? (
          <div>
            <Qr text={signIn.url} />
            <p class="note">Scan with your phone</p>
          </div>
        ) : null}
        <div>
          <p class="note">On your phone or computer, go to</p>
          <h1 class="big">plex.tv/link</h1>
          <p class="note">and enter</p>
          <div class="code" data-testid="code">{signIn.code}</div>
        </div>
      </div>
      <Pill label="Cancel" autofocus onPress={() => props.app.cancelSignIn()} />
    </div>
  );
}

export function Home(props: { app: App; state: AppState }) {
  const { app, state } = props;
  const home = state.home;
  useRescue([home.continueWatching.length, home.recentMovies.length]);
  const img = (i: PlexItem, w: number, h: number, path = i.thumb) => app.image(i.serverBase, path, w, h);
  let first = true;
  const auto = () => {
    const was = first;
    first = false;
    return was;
  };
  return (
    <div>
      {state.homeError ? <p class="note error" style={{ margin: "0 3rem" }}>{state.homeError}</p> : null}
      {home.continueWatching.length ? (
        <Row title="Continue Watching">
          {home.continueWatching.map((i) => (
            <Card
              key={plex.listKey(i)}
              wide
              autofocus={auto()}
              title={plex.rowTitle(i)}
              sub={episodeLine(i)}
              image={img(i, 480, 270, i.art ?? i.thumb)}
              progress={plex.resumeFraction(i)}
              onPress={() => open(app, i)}
            />
          ))}
        </Row>
      ) : null}
      {home.recentEpisodes.length ? (
        <Row title="Recently Added Episodes">
          {home.recentEpisodes.map((g) => (
            <Card
              key={groupKey(g)}
              autofocus={auto()}
              title={g.showTitle}
              sub={g.count > 1 ? `${g.count} new episodes` : plex.caption(g.newest)}
              image={app.image(g.serverBase, g.thumb, 300, 450)}
              badge={g.count}
              onPress={() => open(app, g.newest)}
            />
          ))}
        </Row>
      ) : null}
      {home.recentMovies.length ? (
        <Row title="Recently Added Movies">
          {home.recentMovies.map((i) => (
            <Card key={plex.listKey(i)} autofocus={auto()} title={i.title} sub={plex.caption(i)} image={img(i, 300, 450)}
              progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {home.playlists.length ? (
        <Row title="Playlists">
          {home.playlists.map((i) => (
            <Card key={plex.listKey(i)} title={i.title} sub={i.leafCount === 1 ? "1 item" : `${i.leafCount} items`} image={img(i, 300, 450)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {state.homeBusy && !home.continueWatching.length && !home.recentMovies.length ? <div class="center" style={{ height: "20rem" }}><Spinner /></div> : null}
    </div>
  );
}

export function Library(props: { app: App; state: AppState; kind: Kind }) {
  const { app, state, kind } = props;
  const browse = state.browse[kind];
  const libraries = app.librariesOf(kind);
  useRescue([browse.items.length > 0, browse.choice]);
  if (!libraries.length) {
    return <div class="center"><p class="note">No {kind === "movie" ? "movie" : "TV"} library on {state.plex.serverName ?? "this server"}.</p></div>;
  }
  const sorts: Array<[string, string]> = [["titleSort:asc", "A–Z"], ["addedAt:desc", "Recently added"], ["originallyAvailableAt:desc", "Newest releases"], ["rating:desc", "Critic rating"]];
  return (
    <div>
      <div class="toolbar">
        {libraries.length > 1
          ? libraries.map((l) => (
              <Pill key={l.serverName + l.section.key} label={l.section.title} on={browse.choice === l} onPress={() => void app.openLibrary(kind, l)} />
            ))
          : null}
        {sorts.map(([key, label]) => (
          <Pill key={key} label={label} on={browse.sort === key} onPress={() => void app.setSort(kind, key)} />
        ))}
      </div>
      {browse.error ? <p class="note error" style={{ margin: "0 3rem" }}>{browse.error}</p> : null}
      <div class="grid">
        {browse.items.map((i, index) => (
          <Card
            key={plex.listKey(i)}
            autofocus={index === 0}
            title={i.title}
            sub={plex.caption(i)}
            image={app.image(i.serverBase, i.thumb, 300, 450)}
            progress={plex.resumeFraction(i)}
            watched={plex.isWatched(i)}
            onPress={() => open(app, i)}
          />
        ))}
      </div>
      {browse.busy ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
      <MoreWhenNear app={app} kind={kind} count={browse.items.length} more={browse.total > browse.items.length && !browse.busy} />
    </div>
  );
}

/** The next page when the cursor nears the end of what's loaded. */
function MoreWhenNear(props: { app: App; kind: Kind; count: number; more: boolean }) {
  useEffect(() => {
    if (!props.more) return;
    const check = () => {
      const cards = document.querySelectorAll(".grid .card");
      const at = Array.prototype.indexOf.call(cards, document.activeElement);
      if (at >= 0 && at >= props.count - 24) void props.app.loadMore(props.kind);
    };
    document.addEventListener("focusin", check);
    return () => document.removeEventListener("focusin", check);
  }, [props.count, props.more]);
  return null;
}

export function Detail(props: { app: App; state: AppState; onPlay: (item: PlexItem, resume: boolean) => void }) {
  const { app, state } = props;
  const page = state.detail;
  useRescue([page?.detail != null, page?.focused?.ratingKey]);
  if (!page || (!page.detail && page.busy)) return <div class="center"><Spinner /></div>;
  if (!page.detail) return <div class="center"><p class="note error">{page.error ?? "Couldn't load that title."}</p></div>;
  const d = page.detail;
  const show = plex.isShow(d);
  const target = show ? page.focused : null;
  const resumeFrom = target ? target.viewOffsetMs : show ? 0 : d.viewOffsetMs;
  const playItem = (): PlexItem | null =>
    target ?? (show ? null : { ...emptyItem(d.ratingKey, d.title, d.type), serverBase: page.serverBase, durationMs: d.durationMs, viewOffsetMs: d.viewOffsetMs });
  const backdrop = app.image(page.serverBase, d.art ?? d.thumb, 1920, 1080);
  return (
    <div>
      <div class="hero">
        <div class="backdrop" style={backdrop ? { backgroundImage: `url("${backdrop}")` } : undefined} />
        <h1>{d.title}</h1>
        <div class="facts">{[plex.facts(d), d.qualities.join(" · ")].filter(Boolean).join("  ·  ")}</div>
        {target ? (
          <div class="facts" style={{ color: "var(--chalk)" }}>
            {[resumeFrom > 0 ? "Continue" : "Up next", [target.parentIndex != null ? `S${target.parentIndex}` : null, target.index != null ? `E${target.index}` : null].filter(Boolean).join(" · "), target.title]
              .filter(Boolean)
              .join("  ·  ")}
          </div>
        ) : null}
        {d.type !== "collection" ? (
          <div class="actions">
            <Pill label={resumeFrom > 0 ? "Resume" : "Play"} primary autofocus onPress={() => { const i = playItem(); if (i) props.onPlay(i, true); }} />
            {resumeFrom > 0 ? <Pill label="Restart" onPress={() => { const i = playItem(); if (i) props.onPlay(i, false); }} /> : null}
          </div>
        ) : null}
        {d.summary ? <div class="summary">{d.summary}</div> : null}
      </div>
      {page.seasons.length > 1 ? (
        <div class="toolbar">
          {page.seasons.map((s) => (
            <Pill key={s.ratingKey} label={s.title} on={page.season?.ratingKey === s.ratingKey} onPress={() => void app.selectSeason(s)} />
          ))}
        </div>
      ) : null}
      {show ? (
        <div class="episodes">
          {page.episodes.map((e) => (
            <button key={e.ratingKey} class="episode" data-focus onClick={() => props.onPlay(e, true)}>
              <div class="art">
                {app.image(e.serverBase ?? page.serverBase, e.thumb, 400, 225) ? <img src={app.image(e.serverBase ?? page.serverBase, e.thumb, 400, 225)!} alt="" /> : null}
                {plex.isWatched(e) ? <span class="check">✓</span> : null}
                {plex.resumeFraction(e) && !plex.isWatched(e) ? <span class="progress"><i style={{ width: `${Math.round(plex.resumeFraction(e)! * 100)}%` }} /></span> : null}
              </div>
              <div>
                <div class="name">{[e.index != null ? `${e.index}.` : null, e.title].filter(Boolean).join(" ")}</div>
                <div class="facts">{formatDuration(e.durationMs)}</div>
                {e.summary ? <div class="about">{e.summary}</div> : null}
              </div>
            </button>
          ))}
        </div>
      ) : null}
      {d.roles.length ? (
        <section class="row">
          <h2>Cast</h2>
          <div class="strip">
            {d.roles.slice(0, 30).map((r, i) => (
              <PersonButton key={`${r.id ?? r.name}:${i}`} app={app} person={{ name: r.name, thumb: r.thumb, serverBase: page.serverBase }} role={r.role}
                onPress={() => { if (r.id) app.navigate({ name: "person", person: { id: r.id, name: r.name, thumb: r.thumb, serverBase: page.serverBase } }); }} />
            ))}
          </div>
        </section>
      ) : null}
      {page.related.length ? (
        <Row title="More like this">
          {page.related.map((i) => (
            <Card key={plex.listKey(i)} title={i.title} sub={plex.caption(i)} image={app.image(i.serverBase, i.thumb, 300, 450)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
    </div>
  );
}

function emptyItem(ratingKey: string, title: string, type: string): PlexItem {
  return {
    ratingKey, title, type, thumb: null, art: null, summary: null, year: null, index: null, parentIndex: null, parentRatingKey: null,
    parentTitle: null, grandparentRatingKey: null, grandparentTitle: null, grandparentThumb: null, durationMs: 0, viewOffsetMs: 0,
    leafCount: 0, viewedLeafCount: 0, viewCount: 0, addedAt: 0, lastViewedAt: 0, qualities: [], librarySectionId: null, serverBase: null,
  };
}

/** "Who's watching?": the people in the Plex Home, a PIN on a keypad where there is one. */
export function Profiles(props: { app: App; state: AppState; onClose: () => void }) {
  const { app, state } = props;
  const [asking, setAsking] = useState<PlexHomeUser | null>(null);
  const [pin, setPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<PlexHomeUser | null>(null);
  useRescue([asking, busy]);
  useEffect(
    () =>
      onKeys((a) => {
        if (a === "back") {
          if (asking) { setAsking(null); setPin(""); setError(null); } else props.onClose();
          return true;
        }
        if (asking && typeof a === "object") { type(String(a.digit)); return true; }
        return false;
      }),
    [asking, pin],
  );
  const pick = async (user: PlexHomeUser, code: string | null) => {
    setBusy(user);
    const problem = await app.switchUser(user, code);
    setBusy(null);
    if (problem) { setError(problem); setPin(""); } else props.onClose();
  };
  const type = (digit: string) => {
    if (!asking || pin.length >= 4) return;
    setError(null);
    const next = pin + digit;
    setPin(next);
    if (next.length === 4) void pick(asking, next);
  };
  return (
    <div class="layer" data-layer>
      <div class="panel">
        {busy ? (
          <>
            <Spinner />
            <p class="note">Switching to {busy.title}…</p>
          </>
        ) : asking ? (
          <>
            <h1 class="big">{asking.title}</h1>
            <p class="note">Enter the PIN for this profile</p>
            <div class="dots">{[0, 1, 2, 3].map((i) => <i key={i} class={i < pin.length ? "on" : ""} />)}</div>
            <p class="note error">{error ?? " "}</p>
            <div class="keypad">
              {"123456789".split("").map((d) => (
                <button key={d} class="key" data-focus data-autofocus={d === "5" ? "" : undefined} onClick={() => type(d)}>{d}</button>
              ))}
              <button class="key small" data-focus onClick={() => setPin(pin.slice(0, -1))}>Delete</button>
              <button class="key" data-focus onClick={() => type("0")}>0</button>
              <button class="key small" data-focus onClick={() => { setAsking(null); setPin(""); setError(null); }}>Cancel</button>
            </div>
          </>
        ) : (
          <>
            <h1 class="big">Who's watching?</h1>
            <div class="people">
              {state.plex.homeUsers.map((u) => {
                const current = u.uuid === state.plex.user?.uuid;
                return (
                  <button key={u.uuid} class="person" data-focus data-autofocus={current ? "" : undefined}
                    onClick={() => { if (current) props.onClose(); else if (u.protected) setAsking(u); else void pick(u, null); }}>
                    <div class="avatar">{u.thumb ? <img src={u.thumb} alt="" /> : u.title.slice(0, 1)}</div>
                    <div>{u.title}{u.protected ? " 🔒" : ""}</div>
                    <div class="facts" style={current ? { color: "var(--accent)" } : undefined}>{current ? "Watching now" : u.admin ? "Owner" : u.restricted ? "Managed" : " "}</div>
                  </button>
                );
              })}
            </div>
            <p class="note">{error ?? "Each profile has its own libraries, watch history and Continue Watching."}</p>
          </>
        )}
      </div>
    </div>
  );
}
