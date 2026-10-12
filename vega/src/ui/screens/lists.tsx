import React from 'react';
import { Image, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { PlexItem, PlexPerson } from '../../api/plex';
import * as plex from '../../api/plex';
import { formatDuration } from '../../core/quality';
import { ActionButton, EmptyNote, ErrorNote } from '../controls';
import { PosterGrid } from '../grid';
import { hasItemMenu } from '../itemmenu';
import { Text } from '../text';
import { color, dp, type } from '../theme';
import { episodeLine, posterArt, type ScreenProps } from './home';

/*
 * Pages that are a list of titles: someone from a cast and what else of theirs is on the
 * server (PersonScreen.kt), and a playlist (PlaylistScreen.kt).
 */

/** "4 films and 2 shows on your server". */
export function countLine(items: PlexItem[]): string {
  const films = items.filter((i) => i.type === 'movie').length;
  const shows = items.filter((i) => i.type === 'show').length;
  const parts = [films ? (films === 1 ? '1 film' : `${films} films`) : null, shows ? (shows === 1 ? '1 show' : `${shows} shows`) : null].filter(Boolean);
  return parts.length ? `${parts.join(' and ')} on your server` : 'Nothing on your server';
}

/** "12 items · 5h 20m". */
export function playlistLine(items: PlexItem[]): string {
  const count = items.length === 1 ? '1 item' : `${items.length} items`;
  const length = formatDuration(items.reduce((n, i) => n + i.durationMs, 0));
  return [count, length].filter(Boolean).join('  ·  ');
}

const hold = (props: ScreenProps) => (item: PlexItem) => (hasItemMenu(item) ? () => props.hold(item) : undefined);

export function Person(props: ScreenProps & { person: PlexPerson }) {
  const { app, state, person } = props;
  const list = state.list;
  const busy = !list || list.busy;
  const photo = app.image(person.serverBase, person.thumb, 224, 224);
  const header = (
    <View style={{ paddingLeft: dp(4), paddingBottom: dp(14) + dp(12), gap: dp(10) }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: dp(24) }}>
        <View style={{ width: dp(112), height: dp(112), borderRadius: dp(56), overflow: 'hidden', backgroundColor: color.surfaceHigh, alignItems: 'center', justifyContent: 'center' }}>
          {photo ? <Image source={{ uri: photo }} resizeMode="cover" style={{ width: '100%', height: '100%' }} />
            : <Text style={{ color: color.faint, fontSize: dp(40), lineHeight: dp(44) }}>{person.name.slice(0, 1).toUpperCase()}</Text>}
        </View>
        <View style={{ gap: dp(6) }}>
          <Text style={type.display}>{person.name}</Text>
          <Text style={[type.body, { color: color.muted }]}>{busy ? 'Looking through your libraries…' : countLine(list.items)}</Text>
        </View>
      </View>
      {list?.error ? <ErrorNote message={list.error} />
        : !busy && !list.items.length ? <EmptyNote text={`Nothing else with ${person.name} is on this server.`} /> : null}
    </View>
  );
  return (
    <PosterGrid items={list?.items ?? []} placeholders={busy ? 12 : undefined} header={header}
      subtitle={(i) => (i.year != null ? String(i.year) : plex.caption(i))}
      image={(i) => app.image(i.serverBase, i.thumb, 300, 450)} onPress={props.open} onHold={hold(props)} />
  );
}

export function Playlist(props: ScreenProps & { item: PlexItem; playAll: (items: PlexItem[], shuffle: boolean) => void }) {
  const { app, state, item } = props;
  const list = state.list;
  const busy = !list || list.busy;
  const items = list?.items ?? [];
  const playable = items.some(plex.isPlayable);
  const header = (
    <View style={{ paddingLeft: dp(4), paddingBottom: dp(14) + dp(12), gap: dp(8) }}>
      <Text style={type.display}>{item.title}</Text>
      <Text style={[type.body, { color: color.muted }]}>{busy ? 'Loading…' : playlistLine(items)}</Text>
      {playable ? (
        <TVFocusGuideView autoFocus style={{ flexDirection: 'row', gap: dp(10), paddingTop: dp(6) }}>
          <ActionButton label="Play" emphasised first focusKey="play" onPress={() => props.playAll(items, false)} />
          <ActionButton label="Shuffle" focusKey="shuffle" onPress={() => props.playAll(items, true)} />
        </TVFocusGuideView>
      ) : null}
      {list?.error ? <ErrorNote message={list.error} /> : !busy && !items.length ? <EmptyNote text="This playlist is empty." /> : null}
    </View>
  );
  return (
    <PosterGrid items={items} placeholders={busy ? 12 : undefined} header={header} claim={!playable}
      title={plex.rowTitle} subtitle={(i) => episodeLine(i) ?? plex.caption(i)}
      image={(i) => app.image(i.serverBase, posterArt(i), 300, 450)} onPress={props.open} onHold={hold(props)} />
  );
}
