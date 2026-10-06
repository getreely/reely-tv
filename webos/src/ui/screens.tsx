import { useEffect, useRef, useState } from "preact/hooks";
import * as plex from "../api/plex";
import type { PlexHomeUser, PlexItem } from "../api/plex";
import { groupKey } from "../app/home";
import type { App, AppState, Kind } from "../app/store";
import { isIptvChoice, THEME_LEVELS } from "../app/store";
import { formatDuration } from "../core/quality";
import { focus, onKeys } from "./focus";
import { Card, Pill, Qr, Row, Spinner, useRescue, useReturnFocus } from "./parts";
import { PersonButton } from "./search";
import { ChoicePanel, type ChoiceRequest } from "./settings";

const episodeLine = (i: PlexItem) =>
  i.type === "episode" ? [i.parentIndex != null ? `S${i.parentIndex}` : null, i.index != null ? `E${i.index}` : null, i.title].filter(Boolean).join(" · ") : plex.caption(i);

/** Opening a title: a show's page for an episode, landing on that episode. */
export function open(app: App, item: PlexItem) {
  if (item.type === "collection") {
    app.navigate({ name: "collection", item });
  } else if (item.type === "playlist") {
    app.navigate({ name: "playlist", item });
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
        <h1 class="big">{session.finding ? "Finding a Plex server" : "Couldn't reach a Plex server"}</h1>
        <p class="note">
          {session.finding
            ? "You're signed in. Looking for the servers you can use, your own or shared with you, at home first, then over the internet."
            : session.error ?? "Any server this account owns or has been given access to will do. Make sure it's on."}
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
  const hidden = state.prefs.hiddenRows;
  const home = {
    continueWatching: hidden.includes("continueWatching") ? [] : state.home.continueWatching,
    recentEpisodes: hidden.includes("recentEpisodes") ? [] : state.home.recentEpisodes,
    recentMovies: hidden.includes("recentMovies") ? [] : state.home.recentMovies,
    watchlist: hidden.includes("watchlist") ? [] : state.home.watchlist,
    playlists: hidden.includes("playlists") ? [] : state.home.playlists,
    iptvMovies: hidden.includes("iptvMovies") ? [] : state.home.iptvMovies,
    iptvShows: hidden.includes("iptvShows") ? [] : state.home.iptvShows,
  };
  useRescue([home.continueWatching.length, home.recentMovies.length]);
  const img = (i: PlexItem, w: number, h: number, path = i.thumb) => app.image(i.serverBase, path, w, h);
  let first = true;
  const auto = () => {
    const was = first;
    first = false;
    return was;
  };
  const ready = state.requests.ready[0] ?? null;
  // What the hero shows: the card with the cursor, else what the page starts with.
  const [focused, setFocused] = useState<PlexItem | null>(null);
  const seen = (i: PlexItem) => () => setFocused(i);
  const hero = focused ?? home.continueWatching[0] ?? home.recentEpisodes[0]?.newest ?? home.recentMovies[0] ?? null;
  return (
    <div class="with-hero">
      <HomeHero app={app} item={hero} fallback="Home" />
      <div class="hero-rows">
      {ready ? (
        <div class="ready">
          {ready.poster ? <img src={ready.poster} alt="" /> : null}
          <div class="ready-words">
            <div class="name">{ready.title} is ready to watch</div>
            <div class="facts">
              {state.requests.ready.length > 1
                ? `You asked for it. And ${state.requests.ready.length === 2 ? "1 more" : `${state.requests.ready.length - 1} more`} after this.`
                : "You asked for it, and it's here."}
            </div>
          </div>
          <Pill label="Watch" primary onPress={() => void app.openReady(ready)} />
          <Pill label="Dismiss" onPress={() => app.dismissReady(ready)} />
        </div>
      ) : null}
      {state.homeError ? <p class="note error" style={{ margin: "0 3rem" }}>{state.homeError}</p> : null}
      {home.continueWatching.length ? (
        <Row title="Continue Watching">
          {home.continueWatching.map((i) => (
            <Card
              key={plex.listKey(i)}
              item={i} onFocus={seen(i)}
              autofocus={auto()}
              title={plex.rowTitle(i)}
              sub={episodeLine(i)}
              // A poster, as the Fire TV's Continue Watching is: the show's, for an episode.
              image={img(i, 300, 450, i.type === "episode" ? i.grandparentThumb ?? i.thumb : i.thumb)}
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
              item={g.newest}
              onFocus={seen(g.newest)}
            />
          ))}
        </Row>
      ) : null}
      {home.recentMovies.length ? (
        <Row title="Recently Added Movies">
          {home.recentMovies.map((i) => (
            <Card key={plex.listKey(i)} item={i} onFocus={seen(i)} autofocus={auto()} title={i.title} sub={plex.caption(i)} image={img(i, 300, 450)}
              progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {home.watchlist.length ? (
        <Row title="Watchlist">
          {home.watchlist.map((i) => (
            <Card key={`w:${plex.listKey(i)}`} item={i} onFocus={seen(i)} autofocus={auto()} title={i.title} sub={plex.caption(i)} image={img(i, 300, 450)}
              watched={plex.isWatched(i)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {home.iptvMovies.length ? (
        <Row title="New Movies on IPTV">
          {home.iptvMovies.map((i) => (
            <Card key={`im:${i.ratingKey}`} item={i} onFocus={seen(i)} autofocus={auto()} title={i.title} sub={plex.caption(i)} image={img(i, 300, 450)}
              progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {home.iptvShows.length ? (
        <Row title="New Shows on IPTV">
          {home.iptvShows.map((i) => (
            <Card key={`is:${i.ratingKey}`} item={i} onFocus={seen(i)} autofocus={auto()} title={i.title} sub={plex.caption(i)} image={img(i, 300, 450)}
              watched={plex.isWatched(i)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {home.playlists.length ? (
        <Row title="Playlists">
          {home.playlists.map((i) => (
            <Card key={plex.listKey(i)} onFocus={seen(i)} title={i.title} sub={i.leafCount === 1 ? "1 item" : `${i.leafCount} items`} image={img(i, 300, 450)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
      {state.homeBusy && !home.continueWatching.length && !home.recentMovies.length ? <div class="center" style={{ height: "20rem" }}><Spinner /></div> : null}
      </div>
    </div>
  );
}

export function Library(props: { app: App; state: AppState; kind: Kind }) {
  const { app, state, kind } = props;
  const browse = state.browse[kind];
  const libraries = app.librariesOf(kind);
  const [choice, setChoice] = useState<ChoiceRequest | null>(null);
  // What the hero on the tab's home shows: the card with the cursor, else its first title.
  const [focused, setFocused] = useState<PlexItem | null>(null);
  useRescue([browse.items.length > 0, browse.choice, browse.view, browse.released.length > 0, browse.added.length > 0, browse.addedShows.length > 0, browse.collections != null]);
  if (!libraries.length) {
    return <div class="center"><p class="note">No {kind === "movie" ? "movie" : "TV"} library on {state.plex.serverName ?? "this server"}.</p></div>;
  }
  const sorts: Array<[string, string]> = [["titleSort:asc", "A–Z"], ["addedAt:desc", "Recently added"], ["originallyAvailableAt:desc", "Newest releases"], ["rating:desc", "Critic rating"]];
  const jump = async (letter: string) => {
    const at = await app.jumpTo(kind, letter);
    if (at >= 0) setTimeout(() => focus(document.querySelectorAll<HTMLElement>(".grid .card")[at]), 0);
  };
  // The provider's library is a grid only; Plex's open on the tab's own home, as the Fire TV's do.
  const view = isIptvChoice(browse.choice) ? "grid" : browse.view;
  const libraryPills = libraries.length > 1
    ? libraries.map((l) => (
        // With more than one server, which one each library is on: two called Movies otherwise look the same.
        <Pill key={l.serverName + l.section.key} label={state.plex.servers.length > 1 && !isIptvChoice(l) ? `${l.section.title} · ${l.serverName}` : l.section.title}
          on={browse.choice === l} onPress={() => void app.openLibrary(kind, l)} />
      ))
    : null;
  const views = isIptvChoice(browse.choice) ? null : (
    <>
      <Pill label="Home" on={view === "home"} onPress={() => app.setLibraryView(kind, "home")} />
      <Pill label="All" on={view === "grid"} onPress={() => app.setLibraryView(kind, "grid")} />
      <Pill label="Collections" on={view === "collections"} onPress={() => app.setLibraryView(kind, "collections")} />
    </>
  );
  if (view === "home") {
    // This library's: what's part-watched in it, and what's newly arrived in it.
    const inLibrary = (i: PlexItem) => !browse.choice || ((!i.serverBase || i.serverBase === browse.choice.baseUrl) && (!i.librarySectionId || i.librarySectionId === browse.choice.section.key));
    const resumable = state.home.continueWatching.filter((i) => (kind === "movie" ? i.type === "movie" : i.type === "episode") && inLibrary(i));
    const iptvNew = kind === "movie" ? state.home.iptvMovies : state.home.iptvShows;
    let first = true;
    const auto = () => { const was = first; first = false; return was; };
    const poster = (i: PlexItem, key: string) => (
      <Card key={key} item={i} onFocus={() => setFocused(i)} autofocus={auto()} title={plex.rowTitle(i)} sub={i.type === "episode" ? episodeLine(i) : plex.caption(i)}
        image={app.image(i.serverBase, i.type === "episode" ? i.grandparentThumb ?? i.thumb : i.thumb, 300, 450)}
        progress={plex.resumeFraction(i)} watched={plex.isWatched(i)} onPress={() => open(app, i)} />
    );
    const hero = focused ?? resumable[0] ?? (kind === "movie" ? browse.added[0] : browse.addedShows[0]?.newest) ?? browse.released[0] ?? null;
    return (
      <div class="with-hero">
        <HomeHero app={app} item={hero} fallback={kind === "movie" ? "Movies" : "TV Shows"} />
        <div class="hero-rows">
        <div class="toolbar">{views}{libraryPills}</div>
        {resumable.length ? <Row title="Continue Watching">{resumable.map((i) => poster(i, `c:${plex.listKey(i)}`))}</Row> : null}
        {kind === "movie" && browse.added.length ? <Row title="Recently Added">{browse.added.map((i) => poster(i, `r:${plex.listKey(i)}`))}</Row> : null}
        {kind === "show" && browse.addedShows.length ? (
          <Row title="Recently Added">
            {browse.addedShows.map((g) => (
              <Card key={`r:${groupKey(g)}`} item={g.newest} onFocus={() => setFocused(g.newest)} autofocus={auto()} title={g.showTitle} sub={g.count > 1 ? `${g.count} new episodes` : plex.caption(g.newest)}
                image={app.image(g.serverBase, g.thumb, 300, 450)} badge={g.count} onPress={() => open(app, g.newest)} />
            ))}
          </Row>
        ) : null}
        {browse.released.length ? <Row title="Recently Released">{browse.released.map((i) => poster(i, `n:${plex.listKey(i)}`))}</Row> : null}
        {iptvNew.length ? <Row title="New on IPTV">{iptvNew.map((i) => poster(i, `i:${plex.listKey(i)}`))}</Row> : null}
        {browse.collections == null ? <div class="center" style={{ height: "12rem" }}><Spinner /></div> : null}
        </div>
      </div>
    );
  }
  if (view === "collections") {
    const collections = browse.collections;
    return (
      <div>
        <div class="toolbar">{views}{libraryPills}</div>
        {collections == null ? <div class="center" style={{ height: "12rem" }}><Spinner /></div>
          : !collections.length ? <p class="note" style={{ margin: "1rem 3rem" }}>No collections in {browse.choice?.section.title ?? "this library"} yet. Collections made in Plex show up here.</p>
          : (
            <div class="grid">
              {collections.map((c, i) => (
                <Card key={plex.listKey(c)} autofocus={i === 0} title={c.title} sub={plex.caption(c)} image={app.image(c.serverBase, c.thumb, 300, 450)} onPress={() => open(app, c)} />
              ))}
            </div>
          )}
      </div>
    );
  }
  return (
    <div>
      <div class="toolbar">
        {views}
        {libraryPills}
      </div>
      {/* As on the Fire TV: sort, the watched filter, the decade, then the genres, in one row
          that runs off to the right. Sort and decade open their lists from the right. */}
      <div class="toolbar chips">
        <Pill
          label={`Sort · ${(sorts.find(([key]) => key === browse.sort) ?? sorts[0])[1]}`}
          on={browse.sort !== "titleSort:asc"}
          onPress={() => setChoice({
            title: "Sort by",
            options: sorts.map(([value, label]) => ({ value, label })),
            selected: Math.max(0, sorts.findIndex(([key]) => key === browse.sort)),
            onPick: (i) => void app.setSort(kind, sorts[i][0]),
          })}
        />
        <Pill label="Unwatched" on={browse.unwatched} onPress={() => void app.setFilter(kind, { unwatched: !browse.unwatched })} />
        {browse.decades.length > 1 ? (
          <Pill
            label={browse.decade ? browse.decade.title : "All decades"}
            on={!!browse.decade}
            onPress={() => setChoice({
              title: "Decade",
              options: [{ value: null, label: "All decades" }, ...browse.decades.map((d) => ({ value: d.id, label: d.title }))],
              selected: browse.decade ? browse.decades.findIndex((d) => d.id === browse.decade?.id) + 1 : 0,
              onPick: (i) => void app.setFilter(kind, { decade: i === 0 ? null : browse.decades[i - 1] }),
            })}
          />
        ) : null}
        {browse.genres.length ? (
          <>
            <Pill label={isIptvChoice(browse.choice) ? "All categories" : "All genres"} on={!browse.genre} onPress={() => void app.setFilter(kind, { genre: null })} />
            {browse.genres.map((g) => (
              <Pill key={g.id} label={g.title} on={browse.genre?.id === g.id} onPress={() => void app.setFilter(kind, { genre: g })} />
            ))}
          </>
        ) : null}
      </div>
      {browse.sort === "titleSort:asc" && browse.letters.length > 1 ? (
        <div class="letters">
          {browse.letters.map((l) => (
            <button key={l.letter} class="letter" data-focus onClick={() => void jump(l.letter)}>{l.letter}</button>
          ))}
        </div>
      ) : null}
      {choice ? <ChoicePanel request={choice} onClose={() => setChoice(null)} /> : null}
      {!browse.busy && !browse.items.length && !browse.error ? <p class="note" style={{ margin: "1rem 3rem" }}>Nothing here matches. Try fewer filters.</p> : null}
      {browse.error ? <p class="note error" style={{ margin: "0 3rem" }}>{browse.error}</p> : null}
      <div class="grid">
        {browse.items.map((i, index) => (
          <Card
            key={plex.listKey(i)}
            item={i}
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
  const level = state.prefs.themeLevel;
  const theme = show && level >= 0 ? app.mediaUrl(page.serverBase, d.theme) : null;
  const logo = d.logo ? app.image(page.serverBase, d.logo, 900, 320) : null;
  const watchedNow = target ? plex.isWatched(target) : d.viewCount > 0;
  const play = (resume: boolean) => { const i = playItem(); if (i) props.onPlay(i, resume); };
  return (
    // As the Fire TV's: the title's picture behind the whole page, its name or logo, scores,
    // details and quality, what it's about, round buttons, then the season's episodes as pictures.
    <div class="title-page">
      {theme ? <ThemeMusic url={theme} volume={THEME_LEVELS[level][1]} /> : null}
      <div class="screen-backdrop" style={backdrop ? { backgroundImage: `url("${backdrop}")` } : undefined} />
      <div class="title-hero">
        {logo ? <img class="title-logo" src={logo} alt={d.title} /> : <h1 class="title-name">{d.title}</h1>}
        <div class="title-facts">
          {d.rating != null ? <span class="score critic">★ {d.rating.toFixed(1)}</span> : null}
          {d.audienceRating != null ? <span class="score audience">{Math.round(d.audienceRating * 10)}%</span> : null}
          {d.contentRating ? <span class="score">{d.contentRating}</span> : null}
          {titleFacts(d, show).map((f) => <span key={f}>{f}</span>)}
          {d.qualities.map((q) => <span key={q} class="quality">{q}</span>)}
        </div>
        {target ? (
          <div class="title-next">
            {[resumeFrom > 0 ? "Continue" : "Up next", [target.parentIndex != null ? `S${target.parentIndex}` : null, target.index != null ? `E${target.index}` : null].filter(Boolean).join(" · "), target.title]
              .filter(Boolean)
              .join("  ·  ")}
          </div>
        ) : null}
        {d.summary ? <div class="summary">{d.summary}</div> : null}
        {page.error ? <p class="note error">{page.error}</p> : null}
        {d.type !== "collection" ? (
          <div class="round-actions">
            <RoundButton label={resumeFrom > 0 ? "Resume" : "Play"} glyph="play" primary autofocus onPress={() => play(true)} />
            {resumeFrom > 0 ? <RoundButton label="Restart" glyph="restart" onPress={() => play(false)} /> : null}
            {!show || target ? <RoundButton label={watchedNow ? "Unwatch" : "Watched"} glyph="check" on={watchedNow} onPress={() => void app.toggleWatched()} /> : null}
            {d.guid ? <RoundButton label="Watchlist" glyph={state.plex.watchlist.has(d.guid) ? "saved" : "save"} on={state.plex.watchlist.has(d.guid)} onPress={() => void app.toggleWatchlist()} /> : null}
            {page.trailers.length ? (
              <RoundButton label="Trailer" glyph="film" onPress={() => {
                const t = page.trailers[0];
                props.onPlay({ ...emptyItem(t.ratingKey, t.title, "clip"), serverBase: page.serverBase, durationMs: t.durationMs }, false);
              }} />
            ) : null}
          </div>
        ) : null}
        {!show && d.versions.length > 1 ? (
          <div class="actions">
            {d.versions.map((v, i) => <Pill key={i} label={v.label} on={page.versionIndex === i} onPress={() => app.chooseVersion(i)} />)}
          </div>
        ) : null}
      </div>
      {page.seasons.length > 1 ? (
        <div class="toolbar">
          {page.seasons.map((s) => (
            <Pill key={s.ratingKey} label={s.title} on={page.season?.ratingKey === s.ratingKey} onPress={() => void app.selectSeason(s)} />
          ))}
        </div>
      ) : null}
      {show && page.episodes.length ? (
        <Row title={page.season?.title ?? "Episodes"}>
          {page.episodes.map((e) => (
            <Card
              key={e.ratingKey}
              item={e}
              wide
              title={[e.index != null ? `${e.index}.` : null, e.title].filter(Boolean).join(" ")}
              sub={formatDuration(e.durationMs) || null}
              image={app.image(e.serverBase ?? page.serverBase, e.thumb, 480, 270)}
              progress={plex.resumeFraction(e)}
              watched={plex.isWatched(e)}
              onPress={() => props.onPlay(e, true)}
            />
          ))}
        </Row>
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
            <Card key={plex.listKey(i)} item={i} title={i.title} sub={plex.caption(i)} image={app.image(i.serverBase, i.thumb, 300, 450)} onPress={() => open(app, i)} />
          ))}
        </Row>
      ) : null}
    </div>
  );
}

/** A show's theme, quietly, while its page is up; it stops when the page goes. */
function ThemeMusic(props: { url: string; volume: number }) {
  const audio = useRef<HTMLAudioElement>(null);
  useEffect(() => {
    const a = audio.current;
    if (!a) return;
    a.volume = props.volume;
    void a.play().catch(() => undefined);
    return () => a.pause();
  }, [props.url]);
  useEffect(() => { if (audio.current) audio.current.volume = props.volume; }, [props.volume]);
  return <audio ref={audio} src={props.url} />;
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
  useReturnFocus();
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

/**
 * The top of Home and of the Movies and TV Shows homes, as on the Fire TV: what has the
 * cursor, or what the page starts with while the cursor is still on the tabs. Its picture
 * fills the screen behind everything; its name, or its logo, its details and a few lines
 * about it sit above the rows.
 */
export function HomeHero(props: { app: App; item: PlexItem | null; fallback: string }) {
  const { app, item } = props;
  const art = item ? app.image(item.serverBase, item.art ?? item.thumb, 1920, 1080) : null;
  if (!item) {
    return (
      <div class="home-hero">
        <h1>{props.fallback}</h1>
      </div>
    );
  }
  // An episode is introduced by its show, with its own title among the details.
  const isEpisode = item.type === "episode" && !!item.grandparentTitle;
  const logo = item.logo ? app.image(item.serverBase, item.logo, 720, 280) : null;
  const facts = [plex.caption(item), isEpisode ? item.title : null, formatDuration(item.durationMs) || null].filter(Boolean);
  return (
    <div class="home-hero">
      <div class="screen-backdrop" style={art ? { backgroundImage: `url("${art}")` } : undefined} />
      {logo ? <img class="hero-logo" src={logo} alt={isEpisode ? item.grandparentTitle! : item.title} /> : <h1>{isEpisode ? item.grandparentTitle : item.title}</h1>}
      <div class="facts">
        {facts.join("  ·  ")}
        {item.qualities.map((q) => <span key={q} class="quality">{q}</span>)}
      </div>
      {item.summary ? <p class="summary">{item.summary}</p> : null}
    </div>
  );
}

/** The details after the scores: the year, then a show's seasons or a film's running time, and the studio. */
function titleFacts(d: plex.PlexDetail, show: boolean): string[] {
  const minutes = d.durationMs > 0 ? Math.floor(d.durationMs / 60_000) : 0;
  const length = show
    ? (d.childCount > 0 ? (d.childCount === 1 ? "1 season" : `${d.childCount} seasons`) : null)
    : (minutes > 0 ? (minutes >= 60 ? `${Math.floor(minutes / 60)}h ${minutes % 60}m` : `${minutes}m`) : null);
  return [d.year != null ? String(d.year) : null, length, d.studio].filter((f): f is string => !!f);
}

type Glyph = "play" | "restart" | "check" | "save" | "saved" | "film";

/** A round button with its name under it, as the Fire TV's title page has them. */
function RoundButton(props: { label: string; glyph: Glyph; primary?: boolean; on?: boolean; autofocus?: boolean; onPress: () => void }) {
  return (
    <button class={"round" + (props.primary ? " primary" : "") + (props.on ? " on" : "")} data-focus data-autofocus={props.autofocus ? "" : undefined} aria-label={props.label} onClick={props.onPress}>
      <span class="disc"><RoundGlyph glyph={props.glyph} /></span>
      <span class="label">{props.label}</span>
    </button>
  );
}

function RoundGlyph(props: { glyph: Glyph }) {
  const stroke = { fill: "none", stroke: "currentColor", "stroke-width": 9, "stroke-linecap": "round" as const, "stroke-linejoin": "round" as const };
  switch (props.glyph) {
    case "play":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><path d="M34 22 L78 50 L34 78 Z" fill="currentColor" /></svg>;
    case "restart":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><path d="M30 34 A28 28 0 1 1 26 62" {...stroke} /><path d="M22 20 L30 36 L46 30" {...stroke} /></svg>;
    case "check":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><path d="M24 52 L42 70 L76 32" {...stroke} /></svg>;
    case "save":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><path d="M50 24 V76 M24 50 H76" {...stroke} /></svg>;
    case "saved":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><path d="M30 22 H70 V80 L50 66 L30 80 Z" fill="currentColor" /></svg>;
    case "film":
      return <svg class="glyph" viewBox="0 0 100 100" aria-hidden="true"><rect x="20" y="26" width="60" height="48" rx="8" {...stroke} /><path d="M44 40 L60 50 L44 60 Z" fill="currentColor" /></svg>;
  }
}
