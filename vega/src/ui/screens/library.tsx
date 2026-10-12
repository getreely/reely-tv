import React, { useEffect, useRef, useState } from 'react';
import { FlatList, ScrollView, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { App, Kind } from '../../app/store';
import { isConnected, isIptvChoice } from '../../app/store';
import type { PlexItem } from '../../api/plex';
import * as plex from '../../api/plex';
import { sourceTag } from '../../api/vod';
import { formatDuration } from '../../core/quality';
import { PosterCard, PosterPlaceholder } from '../cards';
import { ChoicePanel, type ChoiceRequest } from '../choice';
import { type FocusSignal, useFocused, useFocusSignal } from '../focussignal';
import { Chip, EmptyNote, ErrorNote } from '../controls';
import { FocusRow, FocusWithinContext, Press, focusWhenReady, useAccent } from '../focus';
import { hasItemMenu } from '../itemmenu';
import { PosterRow, Rows } from '../rows';
import { HeroBackdrop, HeroText } from '../surfaces';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import { episodeLine, posterArt, type ScreenProps } from './home';
import { PlexSignIn } from './signin';

/*
 * A library tab (LibraryScreen.kt). Its own small home of rows (what you're part-way
 * through, what arrived, what's newly out), or the whole library as a grid with its sort
 * and filters and an A–Z rail, or its collections. Which one is the tab's menu's choice.
 */

/** A title, its details and three lines of summary. */
const HERO_HEIGHT = dp(156);
/** The grid's side padding, which a row reaches out over to the screen's edge. */
const PAGE_MARGIN = dp(36);
const COLUMNS = 6;
const COLUMN_GAP = dp(8);
const ROW_GAP = dp(12);
/** Fire TV dp: six across in the 888 between the margins. */
const CARD_WIDTH = (960 - 72 - 5 * 8) / COLUMNS;
/** How near the end of the grid, in titles, the next page is asked for. */
const LOAD_AHEAD = 48;
/** Three rows of a six-across grid: a screenful, and no more. */
const PLACEHOLDER_COUNT = 18;
/** Below this many titles a rail is more in the way than it helps. */
const RAIL_MIN_TITLES = 40;
const RAIL_WIDTH = dp(24);
const RAIL_LETTERS = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ'];

const SORTS: Array<[string, string]> = [
  ['titleSort:asc', 'A–Z'],
  ['addedAt:desc', 'Recently added'],
  ['originallyAvailableAt:desc', 'Newest releases'],
  ['originallyAvailableAt:asc', 'Oldest releases'],
  ['rating:desc', 'Critic rating'],
  ['audienceRating:desc', 'Audience rating'],
  ['lastViewedAt:desc', 'Recently watched'],
];

/** Which letter of the rail a title comes under: "#" for anything but A to Z. */
export function letterOf(item: PlexItem): string {
  const first = (item.titleSort ?? item.title).trim().charAt(0).toUpperCase();
  return first >= 'A' && first <= 'Z' ? first : '#';
}

export function Library(props: ScreenProps & { kind: Kind }) {
  const { app, state, kind } = props;
  const browse = state.browse[kind];
  const showingIptv = isIptvChoice(browse.choice);
  const focus = useFocusSignal<PlexItem>();
  const [choosing, setChoosing] = useState<ChoiceRequest | null>(null);
  const sortChip = useRef<View>(null);
  const decadeChip = useRef<View>(null);
  const grid = useRef<FlatList<PlexItem | null>>(null);
  const cardRefs = useRef(new Map<string, React.RefObject<View | null>>()).current;
  const header = useRef(0);
  const stride = useRef(0);

  // The provider's library is a grid only.
  const view = showingIptv ? 'grid' : browse.view;
  useEffect(() => focus.set(null), [view, browse.choice, focus]);
  // The first card takes the cursor arriving at a view, and not again: a re-sort or a
  // filter leaves it on the chip that asked for it.
  const viewKey = `${view}|${browse.choice ? `${browse.choice.baseUrl}|${browse.choice.section.key}` : ''}`;
  const landedOn = useRef<string | null>(null);
  const arriving = landedOn.current !== viewKey;
  const landed = () => { landedOn.current = viewKey; };

  if (!isConnected(state) && !showingIptv) return <PlexSignIn app={app} state={state} />;

  const hasLibrary = showingIptv || app.librariesOf(kind).length > 0;
  const kindTitle = kind === 'movie' ? 'Movies' : 'TV Shows';
  const sectionTitle = showingIptv ? `IPTV ${kindTitle}` : browse.choice?.section.title ?? null;

  // These rows belong to the library the tab is showing, not to every library of its kind.
  const here = (i: PlexItem) => {
    if (!browse.choice) return true;
    if (i.serverBase && i.serverBase !== browse.choice.baseUrl) return false;
    return !i.librarySectionId || i.librarySectionId === browse.choice.section.key;
  };
  const resumable = state.home.continueWatching.filter((i) => (kind === 'movie' ? i.type === 'movie' : i.type === 'episode') && here(i));
  const iptvNew = kind === 'movie' ? state.home.iptvMovies : state.home.iptvShows;
  // Arriving from the tabs, the tab's own home shows what it starts with in the hero.
  const fallback = view !== 'home' ? null
    : resumable[0] ?? (kind === 'movie' ? browse.added[0] : browse.addedShows[0]?.newest) ?? browse.released[0] ?? null;

  const image = (item: PlexItem, path: string | null | undefined) => app.image(item.serverBase, path, 300, 450);
  const hold = (item: PlexItem) => (hasItemMenu(item) ? () => props.hold(item) : undefined);
  let first = arriving;
  const isFirst = () => {
    const was = first;
    first = false;
    return was;
  };
  const poster = (prefix: string, item: PlexItem, title: string, subtitle: string | null, art: string | null | undefined) => (
    <PosterCard key={`${prefix}:${plex.listKey(item)}`} focusKey={`${prefix}:${plex.listKey(item)}`} first={isFirst()}
      title={title} subtitle={subtitle} image={image(item, art)} tag={sourceTag(item)}
      progress={plex.resumeFraction(item)} watched={plex.isWatched(item)}
      onFocus={() => { landed(); focus.set(item); }} onPress={() => props.open(item)} onHold={hold(item)} />
  );

  const heroBlock = <LibraryHero app={app} focus={focus} fallback={fallback} title={sectionTitle ?? kindTitle} />;

  const body = (() => {
    if (view === 'home') {
      return (
        <Rows style={{ flex: 1 }}>
          {resumable.length ? (
            <PosterRow title="Continue Watching">{resumable.map((i) => poster('c', i, plex.rowTitle(i), episodeLine(i), posterArt(i)))}</PosterRow>
          ) : null}
          {kind === 'movie' && browse.added.length ? (
            <PosterRow title="Recently Added">{browse.added.map((i) => poster('r', i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {kind === 'show' && browse.addedShows.length ? (
            <PosterRow title="Recently Added">
              {browse.addedShows.map((g) => {
                const key = `g:${g.serverBase}|${g.showRatingKey}`;
                return (
                  <PosterCard key={key} focusKey={key} first={isFirst()} title={g.showTitle}
                    subtitle={g.count > 1 ? `${g.count} new episodes` : plex.caption(g.newest)}
                    image={app.image(g.serverBase, g.thumb, 300, 450)} badge={g.count}
                    onFocus={() => { landed(); focus.set(g.newest); }} onPress={() => props.open(g.newest)} onHold={() => props.hold(g.newest)} />
                );
              })}
            </PosterRow>
          ) : null}
          {browse.released.length ? (
            <PosterRow title="Recently Released">{browse.released.map((i) => poster('n', i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {iptvNew.length ? (
            <PosterRow title="New on IPTV">{iptvNew.map((i) => poster('i', i, i.title, plex.caption(i), i.thumb))}</PosterRow>
          ) : null}
          {state.homeBusy && !resumable.length && !browse.released.length ? <EmptyNote text="Loading…" style={{ marginHorizontal: dp(40) }} /> : null}
        </Rows>
      );
    }

    if (view === 'collections') {
      const collections = browse.collections;
      return (
        <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: PAGE_MARGIN, paddingBottom: dp(160) }}>
          <View style={{ paddingTop: dp(12), paddingBottom: dp(2), gap: dp(10) }}>
            <Text style={[type.rowTitle, { paddingHorizontal: dp(4) }]}>Collections</Text>
            {collections && !collections.length ? (
              <EmptyNote text={`No collections in ${browse.choice?.section.title ?? 'this library'} yet. Collections made in Plex show up here.`} />
            ) : null}
          </View>
          <FocusRow>
            <TVFocusGuideView autoFocus style={{ flexDirection: 'row', flexWrap: 'wrap', columnGap: COLUMN_GAP, rowGap: ROW_GAP, paddingTop: ROW_GAP }}>
              {collections == null
                ? Array.from({ length: PLACEHOLDER_COUNT }, (_, i) => <PosterPlaceholder key={i} width={CARD_WIDTH} />)
                : collections.map((c, i) => (
                  <PosterCard key={plex.listKey(c)} focusKey={plex.listKey(c)} first={arriving && i === 0} width={CARD_WIDTH}
                    title={c.title} subtitle={plex.caption(c)} image={image(c, c.thumb)}
                    onFocus={() => { landed(); focus.set(c); }} onPress={() => props.open(c)} />
                ))}
            </TVFocusGuideView>
          </FocusRow>
        </ScrollView>
      );
    }

    // The whole library, six across.
    const filtered = browse.unwatched || !!browse.genre || !!browse.decade;
    const loading = showingIptv ? state.iptv.loading : browse.busy;
    const items: Array<PlexItem | null> = hasLibrary && loading && !browse.items.length
      ? Array.from({ length: PLACEHOLDER_COUNT }, () => null)
      : browse.items;
    const sorts = showingIptv ? SORTS.filter(([key]) => key !== 'audienceRating:desc') : SORTS;
    const sortLabel = (sorts.find(([key]) => key === browse.sort) ?? sorts[0])[1];
    const chooseSort = () => setChoosing({
      title: 'Sort by',
      options: sorts.map(([, label]) => ({ label })),
      selected: Math.max(0, sorts.findIndex(([key]) => key === browse.sort)),
      onPick: (i) => void app.setSort(kind, sorts[i][0]),
      returnTo: sortChip,
    });
    const chooseDecade = () => setChoosing({
      title: 'Decade',
      options: [{ label: 'All decades' }, ...browse.decades.map((d) => ({ label: d.title }))],
      selected: browse.decade ? browse.decades.findIndex((d) => d.id === browse.decade?.id) + 1 : 0,
      onPick: (i) => void app.setFilter(kind, { decade: i === 0 ? null : browse.decades[i - 1] }),
      returnTo: decadeChip,
    });
    const note = showingIptv && state.iptv.error && !browse.items.length ? <ErrorNote message={state.iptv.error} />
      : showingIptv && !state.iptv.loading && !browse.items.length && !filtered
        ? <EmptyNote text={kind === 'movie' ? "No movies from your IPTV provider that aren't already in Plex." : "No shows from your IPTV provider that aren't already in Plex."} />
      : !hasLibrary ? <EmptyNote text={`No ${kind === 'movie' ? 'movie' : 'TV'} library on ${state.plex.serverName ?? 'this server'}.`} />
      : !browse.busy && !browse.items.length && filtered ? <EmptyNote text="Nothing in this library matches those filters." />
      : null;

    const listHeader = (
      <View onLayout={(e) => { header.current = e.nativeEvent.layout.height; }} style={{ paddingTop: dp(12), paddingBottom: dp(2) + ROW_GAP, gap: dp(10) }}>
        <Text style={[type.rowTitle, { paddingHorizontal: dp(4) }]}>{sectionTitle ?? `All ${kindTitle}`}</Text>
        {/* Sort, then the watched filter, then genres: one row that runs off to the right,
            so a library with forty genres still fits. */}
        {hasLibrary ? (
          <TVFocusGuideView autoFocus>
            <FocusWithinContext.Provider value={() => { landed(); grid.current?.scrollToOffset({ offset: 0, animated: true }); }}>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: dp(8), paddingVertical: dp(4) }} style={{ marginVertical: -dp(4) }}>
              <Chip pressRef={sortChip} focusKey="chip:sort" label={`Sort · ${sortLabel}`} selected={browse.sort !== 'titleSort:asc'} onPress={chooseSort} />
              <Chip focusKey="chip:unwatched" label="Unwatched" selected={browse.unwatched} onPress={() => void app.setFilter(kind, { unwatched: !browse.unwatched })} />
              {browse.decades.length > 1 ? (
                <Chip pressRef={decadeChip} focusKey="chip:decade" label={browse.decade?.title ?? 'All decades'} selected={!!browse.decade} onPress={chooseDecade} />
              ) : null}
              {browse.genres.length ? (
                <>
                  <Chip focusKey="chip:genre:" label={showingIptv ? 'All categories' : 'All genres'} selected={!browse.genre} onPress={() => void app.setFilter(kind, { genre: null })} />
                  {browse.genres.map((g) => (
                    <Chip key={g.id} focusKey={`chip:genre:${g.id}`} label={g.title} selected={browse.genre?.id === g.id} onPress={() => void app.setFilter(kind, { genre: g })} />
                  ))}
                </>
              ) : null}
            </ScrollView>
            </FocusWithinContext.Provider>
          </TVFocusGuideView>
        ) : null}
        {browse.error ? <ErrorNote message={browse.error} /> : null}
        {note}
      </View>
    );

    const rows: Array<Array<PlexItem | null>> = [];
    for (let i = 0; i < items.length; i += COLUMNS) rows.push(items.slice(i, i + COLUMNS));
    const refFor = (key: string) => {
      let ref = cardRefs.get(key);
      if (!ref) cardRefs.set(key, (ref = React.createRef<View>()));
      return ref;
    };
    const showRail = browse.sort === 'titleSort:asc' && browse.letters.length > 1
      && browse.letters.reduce((n, l) => n + l.count, 0) >= RAIL_MIN_TITLES;
    const jump = async (letter: string) => {
      const at = await app.jumpTo(kind, letter);
      const item = app.state.browse[kind].items[at];
      if (at < 0 || !item) return;
      grid.current?.scrollToOffset({ offset: header.current + Math.floor(at / COLUMNS) * stride.current, animated: false });
      // Drawn once it's scrolled to: the cursor onto it as soon as it's there.
      let tries = 20;
      const land = () => {
        const ref = cardRefs.get(plex.listKey(item));
        if (ref?.current) {
          focusWhenReady(ref);
          return;
        }
        if (--tries > 0) setTimeout(land, 50);
      };
      land();
    };

    return (
      <View style={{ flex: 1 }}>
        <FocusRow>
          <FlatList
            ref={grid as unknown as React.RefObject<FlatList<Array<PlexItem | null>>>}
            data={rows}
            keyExtractor={(row, i) => (row[0] ? plex.listKey(row[0]) : `placeholder:${i}`)}
            ListHeaderComponent={listHeader}
            contentContainerStyle={{ paddingHorizontal: PAGE_MARGIN, paddingBottom: dp(160) }}
            showsVerticalScrollIndicator={false}
            initialNumToRender={4}
            windowSize={7}
            renderItem={({ item: row, index: r }) => (
              <View onLayout={r === 0 ? (e) => { stride.current = e.nativeEvent.layout.height + ROW_GAP; } : undefined}
                style={{ flexDirection: 'row', columnGap: COLUMN_GAP, marginBottom: ROW_GAP }}>
                {row.map((item, c) => {
                  const index = r * COLUMNS + c;
                  if (!item) return <PosterPlaceholder key={c} width={CARD_WIDTH} />;
                  const key = plex.listKey(item);
                  return (
                    <PosterCard key={key} focusKey={key} first={arriving && index === 0} width={CARD_WIDTH} pressRef={refFor(key)}
                      title={item.title} subtitle={plex.caption(item)} image={image(item, item.thumb)} tag={sourceTag(item)}
                      progress={plex.resumeFraction(item)} watched={plex.isWatched(item)}
                      onFocus={() => {
                        landed();
                        focus.set(item);
                        // The next page is in before it's reached.
                        const b = app.state.browse[kind];
                        if (index >= b.items.length - LOAD_AHEAD && b.total > b.items.length && !b.busy) void app.loadMore(kind);
                      }}
                      onPress={() => props.open(item)} onHold={hold(item)} />
                  );
                })}
              </View>
            )}
          />
        </FocusRow>
        {showRail ? (
          <LetterRail letters={browse.letters.map((l) => l.letter)} focus={focus} onJump={(l) => void jump(l)} />
        ) : null}
      </View>
    );
  })();

  return (
    <View style={{ flex: 1 }}>
      {heroBlock}
      <View style={{ flex: 1 }}>{body}</View>
      {choosing ? <ChoicePanel request={choosing} onClose={() => setChoosing(null)} /> : null}
    </View>
  );
}

/**
 * The A–Z rail down the right of a library in title order. Right from the grid's last
 * column reaches it; OK on a letter moves the cursor to that letter's first title. Letters
 * with nothing under them are shown, so the rail keeps its shape, but can't be chosen.
 */
/** The title with the cursor over its artwork, at the top of the tab: drawn again on its own as the cursor moves. */
function LibraryHero(props: { app: App; focus: FocusSignal<PlexItem>; fallback: PlexItem | null; title: string }) {
  const { app } = props;
  const hero = useFocused(props.focus) ?? props.fallback;
  return (
    <>
      <HeroBackdrop url={hero ? app.image(hero.serverBase, hero.art ?? hero.thumb, 1920, 1080) : null} />
      <View style={{ height: HERO_HEIGHT, paddingHorizontal: dp(40), paddingVertical: dp(8) }}>
        {hero ? (() => {
          // As on Home: an episode goes under its show's name, with its own title among the details.
          const isEpisode = hero.type === 'episode' && !!hero.grandparentTitle;
          const playable = hero.type === 'movie' || hero.type === 'episode';
          return (
            <HeroText
              title={isEpisode ? hero.grandparentTitle! : hero.title}
              facts={[plex.caption(hero), isEpisode ? hero.title : null, playable ? formatDuration(hero.durationMs) || null : null].filter((f): f is string => !!f)}
              summary={hero.summary}
              style={{ maxWidth: dp(700) }}
            />
          );
        })() : (
          <Text style={{ color: color.chalk, fontSize: dp(28), lineHeight: dp(34), fontWeight: '700' }}>{props.title}</Text>
        )}
      </View>
    </>
  );
}

function LetterRail(props: { letters: string[]; focus: FocusSignal<PlexItem>; onJump: (letter: string) => void }) {
  const accent = useAccent();
  // The letter of the title with the cursor: drawn again on its own as the cursor moves.
  const focused = useFocused(props.focus);
  const current = focused ? letterOf(focused) : null;
  // Plex names the letter as it has it; match it back up.
  const present = new Map(props.letters.map((l) => [l.toUpperCase(), l]));
  return (
    <TVFocusGuideView autoFocus style={{ position: 'absolute', right: dp(6), top: dp(10), bottom: dp(10), width: RAIL_WIDTH }}>
      {RAIL_LETTERS.map((letter) => {
        const available = present.get(letter);
        const label = (focused: boolean) => (
          <Text style={{
            color: focused ? color.ink : !available ? 'rgba(127, 135, 146, 0.5)' : letter === current ? accent.color : color.muted,
            fontSize: dp(11), lineHeight: dp(12), fontWeight: focused || letter === current ? '700' : '500',
          }}>{letter}</Text>
        );
        if (!available) {
          return <View key={letter} style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>{label(false)}</View>;
        }
        return (
          <RailLetter key={letter} onPress={() => props.onJump(available)}>{label}</RailLetter>
        );
      })}
    </TVFocusGuideView>
  );
}

function RailLetter(props: { onPress: () => void; children: (focused: boolean) => React.ReactNode }) {
  const [focused, setFocused] = useState(false);
  return (
    <Press onPress={props.onPress} onFocusChange={setFocused}
      style={{ flex: 1, borderRadius: dp(6), alignItems: 'center', justifyContent: 'center', backgroundColor: focused ? color.chalk : 'transparent' }}>
      {() => props.children(focused)}
    </Press>
  );
}
