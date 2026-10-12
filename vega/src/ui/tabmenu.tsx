import React from 'react';
import { View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import type { App, AppState, Kind, LibraryView } from '../app/store';
import { isIptvChoice } from '../app/store';
import type { LibraryChoice } from '../app/home';
import { MenuItem, MenuSection } from './menus';
import { color, dp, SCREEN_WIDTH } from './theme';

/*
 * The panel a library's tab drops when it's chosen (TabMenu.kt): where in the library to
 * go, its home, the whole library or its collections, and which library to use. It hangs
 * under its tab, pulled back from the right edge if it would run off.
 */

export const TAB_MENU_WIDTH = dp(300);

export function TabMenu(props: { app: App; state: AppState; kind: Kind; left: number; top: number; onClose: () => void }) {
  const { app, state, kind } = props;
  const browse = state.browse[kind];
  const title = kind === 'movie' ? 'Movies' : 'TV Shows';
  const libraries = app.librariesOf(kind);
  const onIptv = isIptvChoice(browse.choice);
  const go = (view: LibraryView) => {
    // The provider's library is a grid only: its home and collections are a Plex library's.
    if (onIptv) {
      const plexOne = libraries.find((l) => !isIptvChoice(l));
      if (plexOne) void app.openLibrary(kind, plexOne);
    }
    app.setLibraryView(kind, view);
    props.onClose();
  };
  const pick = (choice: LibraryChoice) => {
    void app.openLibrary(kind, choice);
    app.setLibraryView(kind, 'grid');
    props.onClose();
  };
  // Which server a library is on, only when there's more than one to tell apart.
  const named = (l: LibraryChoice) => (isIptvChoice(l) ? `IPTV ${title}` : state.plex.servers.length > 1 ? `${l.section.title} — ${l.serverName}` : l.section.title);
  const left = Math.max(0, Math.min(props.left, SCREEN_WIDTH - TAB_MENU_WIDTH));
  return (
    <View style={{ position: 'absolute', left, top: props.top + dp(4), width: TAB_MENU_WIDTH, zIndex: 20, borderRadius: dp(16), overflow: 'hidden', backgroundColor: 'rgba(16, 18, 22, 0.97)', borderWidth: dp(1), borderColor: color.glassEdge, padding: dp(10) }}>
      <TVFocusGuideView autoFocus trapFocusUp trapFocusDown trapFocusLeft trapFocusRight style={{ gap: dp(2) }}>
        <MenuSection label={`In ${title}`} />
        <MenuItem label="Home" checked={!onIptv && browse.view === 'home'} preferred={!onIptv && browse.view === 'home'} onPress={() => go('home')} />
        <MenuItem label="Library" checked={!onIptv && browse.view === 'grid'} preferred={!onIptv && browse.view === 'grid'} onPress={() => go('grid')} />
        <MenuItem label="Collections" checked={!onIptv && browse.view === 'collections'} preferred={!onIptv && browse.view === 'collections'} onPress={() => go('collections')} />
        {libraries.length > 1 ? (
          <>
            <MenuSection label="Libraries" />
            {libraries.map((l) => (
              <MenuItem key={l.baseUrl + l.section.key} label={named(l)} checked={browse.choice === l && (onIptv || browse.view === 'grid')} preferred={onIptv && browse.choice === l} onPress={() => pick(l)} />
            ))}
          </>
        ) : null}
      </TVFocusGuideView>
    </View>
  );
}
