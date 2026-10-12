import React from 'react';
import { ScrollView, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { App } from '../../app/store';
import type { PlexItem } from '../../api/plex';
import * as plex from '../../api/plex';
import { sourceTag } from '../../api/vod';
import { CastCircle, ChannelCard, PosterCard } from '../cards';
import { ActionButton, EmptyNote, TextField } from '../controls';
import { FocusRow } from '../focus';
import { type FocusSignal, useFocused, useFocusSignal } from '../focussignal';
import { GRID_CARD_WIDTH, GRID_COLUMN_GAP, GRID_COLUMNS, GRID_MARGIN, GRID_ROW_GAP } from '../grid';
import { hasItemMenu } from '../itemmenu';
import { HeroBackdrop } from '../surfaces';
import { Text } from '../text';
import { dp, type } from '../theme';
import { episodeLine, posterArt, type ScreenProps } from './home';

/*
 * Search (SearchScreen.kt): the box, then live channels first (a channel's name is
 * almost always what was being looked for), the people when the words are a person's
 * name, the films and shows six across, collections, Plex's other guesses, and the
 * people otherwise. With the box empty, what was searched for lately.
 */

const Heading = (props: { text: string }) => <Text style={[type.rowTitle, { paddingHorizontal: dp(4) }]}>{props.text}</Text>;

function Row(props: { children: React.ReactNode; gap?: number }) {
  return (
    <TVFocusGuideView autoFocus>
      <ScrollView horizontal showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: props.gap ?? dp(8), paddingVertical: dp(10), paddingHorizontal: dp(4) }} style={{ marginVertical: -dp(10), marginHorizontal: -dp(4) }}>
        {props.children}
      </ScrollView>
    </TVFocusGuideView>
  );
}

export function Search(props: ScreenProps) {
  const { app, state } = props;
  const search = state.search;
  const focus = useFocusSignal<PlexItem>();
  const open = (item: PlexItem) => {
    app.rememberSearch();
    props.open(item);
  };
  const hold = (item: PlexItem) => (hasItemMenu(item) ? () => props.hold(item) : undefined);
  const poster = (prefix: string, item: PlexItem) => (
    <PosterCard key={`${prefix}:${plex.listKey(item)}`} focusKey={`${prefix}:${plex.listKey(item)}`} width={GRID_CARD_WIDTH}
      title={plex.rowTitle(item)} subtitle={episodeLine(item)} image={app.image(item.serverBase, posterArt(item), 300, 450)} tag={sourceTag(item)}
      progress={plex.resumeFraction(item)} watched={plex.isWatched(item)}
      onFocus={() => focus.set(item)} onPress={() => open(item)} onHold={hold(item)} />
  );
  const grid = (prefix: string, items: PlexItem[]) => {
    const rows: PlexItem[][] = [];
    for (let i = 0; i < items.length; i += GRID_COLUMNS) rows.push(items.slice(i, i + GRID_COLUMNS));
    return (
      <FocusRow>
        <TVFocusGuideView autoFocus style={{ gap: GRID_ROW_GAP }}>
          {rows.map((row, r) => (
            <View key={r} style={{ flexDirection: 'row', columnGap: GRID_COLUMN_GAP }}>{row.map((i) => poster(prefix, i))}</View>
          ))}
        </TVFocusGuideView>
      </FocusRow>
    );
  };

  const people = search.people.length ? (
    <View style={{ gap: dp(8), paddingBottom: dp(10) }}>
      <Heading text="People" />
      <Row gap={dp(16)}>
        {search.people.map((person) => (
          <CastCircle key={person.id} focusKey={`person:${person.id}`} name={person.name} image={app.image(person.serverBase, person.thumb, 160, 160)}
            onPress={() => { app.rememberSearch(); props.openRoute({ name: 'person', person }); }} />
        ))}
      </Row>
    </View>
  ) : null;

  const counts = [
    search.results.length ? `${search.results.length + search.more.length} in your library` : null,
    search.channels.length ? `${search.channels.length} live channels` : null,
    search.people.length ? (search.people.length === 1 ? '1 person' : `${search.people.length} people`) : null,
    search.collections.length ? (search.collections.length === 1 ? '1 collection' : `${search.collections.length} collections`) : null,
  ].filter(Boolean).join('  ·  ');
  const nothing = !search.results.length && !search.channels.length && !search.people.length && !search.collections.length;

  return (
    <View style={{ flex: 1 }}>
      <SearchBackdrop app={app} focus={focus} />
      <ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: GRID_MARGIN, paddingTop: dp(20), paddingBottom: dp(160), gap: dp(12) }}>
        <View style={{ gap: dp(10), paddingBottom: dp(8), paddingHorizontal: dp(4) }}>
          <TextField label="Search" value={search.query} onChange={(q) => void app.setQuery(q)} focusKey="search-box" first
            placeholder="Movies, shows, people, collections and channels" onSubmit={() => app.rememberSearch()} style={{ maxWidth: dp(620) }} />
          {search.busy ? <EmptyNote text="Searching…" />
            : !search.query.trim() && search.recent.length ? (
              <View style={{ gap: dp(8) }}>
                <Heading text="Recent searches" />
                <Row gap={dp(10)}>
                  {search.recent.map((words) => <ActionButton key={words} focusKey={`recent:${words}`} label={words} onPress={() => void app.setQuery(words)} />)}
                  <ActionButton focusKey="recent:clear" label="Clear" onPress={() => app.clearRecentSearches()} />
                </Row>
              </View>
            )
            : !search.query.trim() ? <EmptyNote text="Search movies, shows, people, collections and live channels." />
            : nothing ? <EmptyNote text={search.unreachable ? "Couldn't reach your Plex server to search. Try again in a moment." : `Nothing matched "${search.query}".`} />
            : <EmptyNote text={counts} />}
        </View>

        {search.channels.length ? (
          <View style={{ gap: dp(8), paddingBottom: dp(10) }}>
            <Heading text="Live TV" />
            <Row>
              {search.channels.map((c) => (
                <ChannelCard key={c.streamId} name={c.name} number={c.number} logo={c.icon} onPress={() => { app.rememberSearch(); app.watchFound(c); }} />
              ))}
            </Row>
          </View>
        ) : null}

        {search.peopleFirst ? people : null}

        {search.results.length ? (
          <View style={{ gap: dp(12) }}>
            <View style={{ paddingTop: dp(4) }}><Heading text="Movies and shows" /></View>
            {grid('result', search.results)}
          </View>
        ) : null}

        {/* After the titles themselves: typing "bond" is usually after a film, and the collection of all of them is the next best thing. */}
        {search.collections.length ? (
          <View style={{ gap: dp(8), paddingTop: dp(14), paddingBottom: dp(10) }}>
            <Heading text="Collections" />
            <FocusRow>
              <Row>
                {search.collections.map((c) => (
                  <PosterCard key={`collection:${plex.listKey(c)}`} focusKey={`collection:${plex.listKey(c)}`} title={c.title} subtitle={plex.caption(c)}
                    image={app.image(c.serverBase, c.thumb, 300, 450)} tag={sourceTag(c)} onFocus={() => focus.set(c)} onPress={() => open(c)} />
                ))}
              </Row>
            </FocusRow>
          </View>
        ) : null}

        {search.more.length ? (
          <View style={{ gap: dp(12) }}>
            <View style={{ paddingTop: dp(14) }}><Heading text="Other results" /></View>
            {grid('more', search.more)}
          </View>
        ) : null}

        {!search.peopleFirst ? people : null}
      </ScrollView>
    </View>
  );
}

/** The focused result's artwork behind the page: drawn again on its own as the cursor moves. */
function SearchBackdrop(props: { app: App; focus: FocusSignal<PlexItem> }) {
  const focused = useFocused(props.focus);
  return <HeroBackdrop url={focused ? props.app.image(focused.serverBase, focused.art ?? focused.thumb, 1920, 1080) : null} />;
}
