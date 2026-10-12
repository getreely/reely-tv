import React, { useMemo, useRef } from 'react';
import { Image, View } from 'react-native';
import type { App, AppState, HomeRowId, Route } from '../../app/store';
import { isConnected, requestBadge, shownRequestRows } from '../../app/store';
import type { EpisodeGroup } from '../../app/home';
import { homeIsEmpty } from '../../app/home';
import type { PlexItem } from '../../api/plex';
import * as plex from '../../api/plex';
import { sourceTag } from '../../api/vod';
import type { RequestTitle } from '../../api/reely';
import { formatDuration } from '../../core/quality';
import { PosterCard } from '../cards';
import { type FocusSignal, useFocused, useFocusSignal } from '../focussignal';
import { ActionButton, EmptyNote, ErrorNote } from '../controls';
import { hasItemMenu } from '../itemmenu';
import { PosterRow, Rows } from '../rows';
import { glass, HeroBackdrop, HeroText } from '../surfaces';
import { Text } from '../text';
import { color, dp, EDGE, type } from '../theme';
import { PlexSignIn } from './signin';

/*
 * Home (HomeScreen.kt): the focused title large at the top over its artwork, then rows:
 * Continue Watching, the newest episodes and films, the IPTV provider's newest, the
 * Watchlist, playlists, and from Reely what's trending and popular.
 */

/** Sized so a whole row fits beneath it, focus lift and safe area included. */
export const HERO_HEIGHT = dp(166);

/** An episode's show poster reads better in a row than its still. */
export const posterArt = (item: PlexItem) => (item.type === 'episode' ? item.grandparentThumb ?? item.thumb : item.thumb);

export const episodeLine = (item: PlexItem) =>
  item.type === 'episode' ? [plex.caption(item), item.title].filter(Boolean).join(' · ') : plex.caption(item);

/** Films and shows taken in turn, so neither crowds the other out. */
export function interleave<T>(lists: T[][]): T[] {
  const longest = Math.max(0, ...lists.map((l) => l.length));
  const out: T[] = [];
  for (let i = 0; i < longest; i++) for (const l of lists) if (i < l.length) out.push(l[i]);
  return out;
}

export interface ScreenProps {
  app: App;
  state: AppState;
  open: (item: PlexItem) => void;
  openRequest: (title: RequestTitle) => void;
  hold: (item: PlexItem) => void;
  /** Plays [item]: from where it was left, or the top; [queue] what follows it. */
  play: (item: PlexItem, resume: boolean, queue?: PlexItem[]) => void;
  /** Asks again for a page that couldn't be loaded. */
  retry: () => void;
  openRoute: (route: Route) => void;
}

/** "Dune is ready to watch": something asked for through Requests, now on the server. */
function ReadyCard(props: { app: App; title: RequestTitle; more: number }) {
  const { app, title } = props;
  return (
    <View style={[glass(), { marginHorizontal: EDGE, flexDirection: 'row', alignItems: 'center', gap: dp(16), paddingHorizontal: dp(16), paddingVertical: dp(12), alignSelf: 'flex-start' }]}>
      {title.poster ? <Image source={{ uri: title.poster }} resizeMode="cover" style={{ width: dp(48), height: dp(72), borderRadius: dp(6) }} /> : null}
      <View style={{ maxWidth: dp(520) }}>
        <Text style={[type.body, { fontWeight: '600' }]}>{`${title.title} is ready to watch`}</Text>
        <Text style={[type.label, { color: color.muted }]}>
          {props.more > 0 ? `You asked for it. And ${props.more === 1 ? '1 more' : `${props.more} more`} after this.` : "You asked for it, and it's here."}
        </Text>
      </View>
      <ActionButton label="Watch" emphasised onPress={() => void app.openReady(title)} />
      <ActionButton label="Dismiss" onPress={() => app.dismissReady(title)} />
    </View>
  );
}

export function Home(props: ScreenProps) {
  if (!isConnected(props.state)) return <PlexSignIn app={props.app} state={props.state} />;
  return <HomePage {...props} />;
}

/** The title with the cursor, large over its artwork: drawn again on its own as the cursor moves. */
function HomeHero(props: { app: App; focus: FocusSignal<PlexItem>; fallback: PlexItem | null }) {
  const { app } = props;
  const hero = useFocused(props.focus) ?? props.fallback;
  const isEpisode = hero?.type === 'episode' && !!hero.grandparentTitle;
  return (
    <>
      <HeroBackdrop url={hero ? app.image(hero.serverBase, hero.art ?? hero.thumb, 1920, 1080) : null} />
      <View style={{ height: HERO_HEIGHT, paddingHorizontal: EDGE, paddingVertical: dp(6) }}>
        {hero ? (
          <HeroText
            title={isEpisode ? hero.grandparentTitle! : hero.title}
            logo={hero.logo ? app.image(hero.serverBase, hero.logo, 720, 280) : null}
            facts={[plex.caption(hero), isEpisode ? hero.title : null, formatDuration(hero.durationMs) || null].filter((f): f is string => !!f)}
            summary={hero.summary}
            qualities={hero.qualities}
            style={{ maxWidth: dp(700) }}
          />
        ) : (
          <Text style={[type.display, { fontSize: dp(28), lineHeight: dp(34) }]}>Home</Text>
        )}
      </View>
    </>
  );
}

function HomePage(props: ScreenProps) {
  const { app, state } = props;
  const focus = useFocusSignal<PlexItem>();
  // The page's actions as they are now, without making the rows anew when they're new functions.
  const actions = useRef(props);
  actions.current = props;
  const home = state.home;
  const hiddenRows = state.prefs.hiddenRows;
  const hidden = new Set<HomeRowId>(hiddenRows);
  const shows = (id: HomeRowId) => !hidden.has(id);
  // Arriving from the tabs, the hero shows what the page starts with rather than nothing.
  const fallback = (shows('continueWatching') ? home.continueWatching[0] : undefined)
    ?? (shows('recentEpisodes') ? home.recentEpisodes[0]?.newest : undefined)
    ?? (shows('recentMovies') ? home.recentMovies[0] : undefined)
    ?? null;
  // The rows, made again only when what's in them changes: not as the cursor moves.
  const rows = useMemo(() => {
    const image = (item: PlexItem, path: string | null | undefined) => app.image(item.serverBase, path, 300, 450);
    let first = true;
    const isFirst = () => {
      const was = first;
      first = false;
      return was;
    };
    const poster = (item: PlexItem, title: string, subtitle: string | null, art: string | null | undefined) => (
      <PosterCard key={plex.listKey(item)} focusKey={plex.listKey(item)} first={isFirst()}
        title={title} subtitle={subtitle} image={image(item, art)} tag={sourceTag(item)}
        progress={plex.resumeFraction(item)} watched={plex.isWatched(item)}
        onFocus={() => focus.set(item)} onPress={() => actions.current.open(item)}
        onHold={hasItemMenu(item) ? () => actions.current.hold(item) : undefined} />
    );
    const group = (g: EpisodeGroup) => (
      <PosterCard key={`g:${g.serverBase}|${g.showRatingKey}`} focusKey={`g:${g.serverBase}|${g.showRatingKey}`} first={isFirst()}
        title={g.showTitle} subtitle={g.count > 1 ? `${g.count} new episodes` : plex.caption(g.newest)}
        image={app.image(g.serverBase, g.thumb, 300, 450)} badge={g.count}
        onFocus={() => focus.set(g.newest)} onPress={() => actions.current.open(g.newest)} onHold={() => actions.current.hold(g.newest)} />
    );
    const requestRows = shownRequestRows(state.requests);
    const reelyRow = (title: string, ids: string[]) => {
      const titles = interleave(ids.map((id) => requestRows.find((r) => r.id === id)?.titles ?? []));
      if (!titles.length) return null;
      return (
        <PosterRow key={title} title={title}>
          {titles.map((t) => (
            <PosterCard key={`${t.kind}:${t.tmdbId}`} focusKey={`${t.kind}:${t.tmdbId}`} first={isFirst()}
              title={t.title} subtitle={t.year != null ? String(t.year) : null} image={t.poster} tag={requestBadge(state.requests, t) ?? null}
              onPress={() => actions.current.openRequest(t)} />
          ))}
        </PosterRow>
      );
    };
    const ready = state.requests.ready[0];
    return (
        <Rows style={{ flex: 1 }}>
          {state.homeError ? <ErrorNote message={state.homeError} style={{ marginHorizontal: EDGE }} /> : null}
          {ready ? <ReadyCard app={app} title={ready} more={state.requests.ready.length - 1} /> : null}
          {home.continueWatching.length && shows('continueWatching') ? (
            <PosterRow title="Continue Watching">
              {home.continueWatching.map((i) => poster(i, plex.rowTitle(i), episodeLine(i), posterArt(i)))}
            </PosterRow>
          ) : null}
          {home.recentEpisodes.length && shows('recentEpisodes') ? (
            <PosterRow title="Recently Added Episodes">{home.recentEpisodes.map(group)}</PosterRow>
          ) : null}
          {home.recentMovies.length && shows('recentMovies') ? (
            <PosterRow title="Recently Added Movies">{home.recentMovies.map((i) => poster(i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {home.iptvMovies.length && shows('iptvMovies') ? (
            <PosterRow title="New Movies on IPTV">{home.iptvMovies.map((i) => poster(i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {home.iptvShows.length && shows('iptvShows') ? (
            <PosterRow title="New Shows on IPTV">{home.iptvShows.map((i) => poster(i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {home.watchlist.length && shows('watchlist') ? (
            <PosterRow title="Watchlist">{home.watchlist.map((i) => poster(i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {home.playlists.length && shows('playlists') ? (
            <PosterRow title="Playlists">
              {home.playlists.map((p) => (
                <PosterCard key={plex.listKey(p)} focusKey={plex.listKey(p)} first={isFirst()} title={p.title}
                  subtitle={p.leafCount === 1 ? '1 item' : `${p.leafCount} items`} image={image(p, p.thumb)}
                  onFocus={() => focus.set(p)} onPress={() => actions.current.open(p)} />
              ))}
            </PosterRow>
          ) : null}
          {reelyRow('Trending', ['movies', 'shows'])}
          {reelyRow('Popular', ['popularMovies', 'popularShows'])}
          {homeIsEmpty(home) && !state.homeBusy ? (
            <EmptyNote text="Nothing to show yet. Watch something and it will appear here." style={{ marginHorizontal: EDGE, marginVertical: dp(20) }} />
          ) : null}
        </Rows>
    );
  }, [app, home, hiddenRows, state.requests, state.homeError, state.homeBusy, focus]);
  return (
    <View style={{ flex: 1 }}>
      <HomeHero app={app} focus={focus} fallback={fallback} />
      {rows}
    </View>
  );
}
