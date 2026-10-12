import React from 'react';
import type { App } from '../app/store';
import type { PlexItem } from '../api/plex';
import * as plex from '../api/plex';
import { formatDuration } from '../core/quality';
import { canDirect } from '../player/canplay';
import { CheckGlyph, CrossGlyph, InfoGlyph, PlayGlyph, RestartGlyph } from './glyphs';
import { MenuArtHeader, MenuItem, MenuLayer } from './menus';

/*
 * What holding OK on a title offers (ItemMenu.kt): play it or carry on, start it over,
 * mark it, open its page, and take it off Continue Watching. A show or season has no one
 * file to play from here, so it plays its next episode.
 */

/** Whether a title has anything a menu could offer. */
export const hasItemMenu = (item: PlexItem) => ['movie', 'episode', 'show', 'season'].includes(item.type);

/** The line under a title in its menu: what it is, how long, how far along. */
export function menuMeta(item: PlexItem): string | null {
  const fraction = plex.resumeFraction(item);
  const left = fraction != null ? Math.max(1, Math.floor((item.durationMs - item.viewOffsetMs) / 60_000)) : null;
  const parts = [
    item.type === 'episode' ? plex.caption(item) : item.year != null ? String(item.year) : null,
    item.type === 'show' ? (item.leafCount > 0 ? (item.leafCount === 1 ? '1 episode' : `${item.leafCount} episodes`) : null)
      : item.type === 'season' ? plex.caption(item)
      : left == null ? formatDuration(item.durationMs) || null : null,
    left != null ? `${left} min left` : plex.isWatched(item) ? 'Watched' : null,
  ].filter((p): p is string => !!p);
  return parts.length ? parts.join('  ·  ') : null;
}

export function ItemMenu(props: { app: App; item: PlexItem; onClose: () => void; open: (item: PlexItem) => void; inContinueWatching: boolean }) {
  const { app, item } = props;
  const resumable = (plex.resumeFraction(item) ?? 0) > 0;
  const watched = plex.isWatched(item);
  const done = (f: () => void) => () => { props.onClose(); f(); };
  const picture = item.type === 'episode' ? item.thumb ?? item.art : item.art ?? item.thumb;
  const meta = menuMeta(item);
  return (
    <MenuLayer onClose={props.onClose}>
      <MenuArtHeader
        title={item.type === 'episode' ? item.title : plex.rowTitle(item)}
        meta={item.type === 'episode' ? [item.grandparentTitle, meta].filter(Boolean).join('  ·  ') : meta}
        image={app.image(item.serverBase, picture, 640, 360)}
        progress={plex.resumeFraction(item)}
      />
      {(item.type === 'show' || item.type === 'season') && item.leafCount > 0 ? (
        <MenuItem preferred label={item.viewedLeafCount === 0 ? 'Play first episode' : watched ? 'Play from the start' : 'Play next episode'}
          icon={(c) => <PlayGlyph color={c} size={20} />}
          onPress={done(() => void app.nextEpisodeOf(item).then((n) => { if (n) void app.play(n.episode, true, canDirect, n.queue); }))} />
      ) : null}
      {plex.isPlayable(item) ? (
        <>
          <MenuItem preferred label={resumable ? 'Resume' : 'Play'} icon={(c) => <PlayGlyph color={c} size={20} />}
            onPress={done(() => void app.play(item, true, canDirect))} />
          {resumable ? <MenuItem label="Play from the beginning" icon={(c) => <RestartGlyph color={c} size={20} />} onPress={done(() => void app.play(item, false, canDirect))} /> : null}
        </>
      ) : null}
      <MenuItem preferred={!plex.isPlayable(item) && !(item.type === 'show' || item.type === 'season')} label={watched ? 'Mark as unwatched' : 'Mark as watched'}
        icon={(c) => <CheckGlyph color={c} size={20} />} onPress={done(() => void app.setItemWatched(item, !watched))} />
      <MenuItem label={item.type === 'episode' ? `Go to ${item.grandparentTitle ?? 'the show'}` : 'Details'}
        icon={(c) => <InfoGlyph color={c} size={20} />} onPress={done(() => props.open(item))} />
      {props.inContinueWatching ? (
        <MenuItem label="Remove from Continue Watching" icon={(c) => <CrossGlyph color={c} size={20} />} onPress={done(() => void app.removeFromContinueWatching(item))} />
      ) : null}
    </MenuLayer>
  );
}
