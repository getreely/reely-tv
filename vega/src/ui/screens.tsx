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

const Coming = (props: { name: string }) => (
  <View style={{ flex: 1, paddingHorizontal: EDGE, paddingTop: dp(20) }}>
    <Text style={type.headline}>{props.name}</Text>
  </View>
);

export function Screens(props: { app: App; state: AppState; open: (route: Route) => void; openMenu: (item: PlexItem) => void }) {
  const { app, state } = props;
  const open = (item: PlexItem) => props.open(routeFor(item));
  const openRequest = (title: RequestTitle) => props.open({ name: 'requestTitle', title });
  const screen = { app, state, open, openRequest, hold: props.openMenu };
  switch (state.route.name) {
    case 'home': return <Home {...screen} />;
    case 'library': return <Library {...screen} kind={state.route.kind} />;
    case 'detail': return <Coming name="Title" />;
    case 'search': return <Coming name="Search" />;
    case 'person': return <Coming name={state.route.person.name} />;
    case 'collection': case 'playlist': return <Coming name={state.route.item.title} />;
    case 'live': return <Live app={app} state={state} />;
    case 'requests': return <Coming name="Request" />;
    case 'requestTitle': return <Coming name={state.route.title.title} />;
    case 'settings': return <Coming name="Settings" />;
  }
}
