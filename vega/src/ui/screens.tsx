import React from 'react';
import { View } from 'react-native';
import type { App, AppState, Route } from '../app/store';
import type { PlexItem } from '../api/plex';
import type { RequestTitle } from '../api/reely';
import { Text } from './text';
import { dp, EDGE, type } from './theme';
import { Home } from './screens/home';
import { Library } from './screens/library';
import { Live } from './screens/live';
import { Detail } from './screens/detail';
import { Person, Playlist } from './screens/lists';
import { canDirect } from '../player/canplay';

/*
 * Which screen is up, by route (ReelyApp.kt's `when (route)`).
 */

/** Where a title opens: an episode in its show's page, at that episode. */
export function routeFor(item: PlexItem): Route {
  if (item.type === 'collection') return { name: 'collection', item };
  if (item.type === 'playlist') return { name: 'playlist', item };
  if (item.type === 'episode' && item.grandparentRatingKey) {
    return { name: 'detail', ratingKey: item.grandparentRatingKey, serverBase: item.serverBase, episodeKey: item.ratingKey };
  }
  return { name: 'detail', ratingKey: item.ratingKey, serverBase: item.serverBase };
}

function shuffled<T>(items: T[]): T[] {
  const out = items.slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

const Coming = (props: { name: string }) => (
  <View style={{ flex: 1, paddingHorizontal: EDGE, paddingTop: dp(20) }}>
    <Text style={type.headline}>{props.name}</Text>
  </View>
);

export function Screens(props: { app: App; state: AppState; open: (route: Route) => void; openMenu: (item: PlexItem) => void }) {
  const { app, state } = props;
  const open = (item: PlexItem) => props.open(routeFor(item));
  const openRequest = (title: RequestTitle) => props.open({ name: 'requestTitle', title });
  // A film plays the version chosen on its page; an episode, the rest of its season after it.
  const play = (item: PlexItem, resume: boolean, queue?: PlexItem[]) => {
    const version = item.type === 'movie' && state.detail?.detail?.ratingKey === item.ratingKey ? state.detail.versionIndex : 0;
    const rest = queue ?? (item.type === 'episode' ? state.detail?.episodes ?? [] : []);
    void app.play(item, resume, canDirect, rest, version);
  };
  const retry = () => {
    const r = state.route;
    if (r.name === 'detail') void app.openDetail(r.ratingKey, r.serverBase, r.episodeKey ?? null);
    if (r.name === 'collection') void app.openDetail(r.item.ratingKey, r.item.serverBase, null);
  };
  /** A list played in order, or shuffled, each on to the next. */
  const playAll = (items: PlexItem[], shuffle: boolean) => {
    const playable = items.filter((i) => i.type === 'movie' || i.type === 'episode');
    const queue = shuffle ? shuffled(playable) : playable;
    if (queue.length) void app.play(queue[0], false, canDirect, queue);
  };
  const screen = { app, state, open, openRequest, hold: props.openMenu, play, retry, openRoute: props.open };
  switch (state.route.name) {
    case 'home': return <Home {...screen} />;
    case 'library': return <Library {...screen} kind={state.route.kind} />;
    case 'detail': case 'collection': return <Detail {...screen} />;
    case 'search': return <Coming name="Search" />;
    case 'person': return <Person {...screen} person={state.route.person} />;
    case 'playlist': return <Playlist {...screen} item={state.route.item} playAll={playAll} />;
    case 'live': return <Live app={app} state={state} />;
    case 'requests': return <Coming name="Request" />;
    case 'requestTitle': return <Coming name={state.route.title.title} />;
    case 'settings': return <Coming name="Settings" />;
  }
}
