import { useEffect } from "preact/hooks";
import * as plex from "../api/plex";
import type { PlexItem } from "../api/plex";
import { onKeys } from "./focus";
import { useRescue, useReturnFocus } from "./parts";

/*
 * A poster's menu, from holding OK on it: the Fire TV's, down the right of the screen.
 * Play or resume, from the beginning, the next episode of a show, watched or not, the
 * title's page, and off Continue Watching.
 */

export interface MenuActions {
  play: (item: PlexItem, resume: boolean) => void;
  playNext: (show: PlexItem) => void;
  setWatched: (item: PlexItem, watched: boolean) => void;
  details: (item: PlexItem) => void;
  removeFromContinueWatching: ((item: PlexItem) => void) | null;
}

export function ItemMenu(props: { item: PlexItem; image: string | null; actions: MenuActions; onClose: () => void }) {
  const { item, actions } = props;
  useEffect(() => onKeys((a) => { if (a === "back") { props.onClose(); return true; } return false; }), []);
  useRescue([]);
  useReturnFocus();
  const run = (f: () => void) => () => { props.onClose(); f(); };
  const resumable = (plex.resumeFraction(item) ?? 0) > 0;
  const watched = plex.isWatched(item);
  const entries: Array<[string, () => void]> = [];
  if ((item.type === "show" || item.type === "season") && item.leafCount > 0) {
    entries.push([item.viewedLeafCount === 0 ? "Play first episode" : watched ? "Play from the start" : "Play next episode", () => actions.playNext(item)]);
  }
  if (plex.isPlayable(item)) {
    entries.push([resumable ? "Resume" : "Play", () => actions.play(item, true)]);
    if (resumable) entries.push(["Play from the beginning", () => actions.play(item, false)]);
  }
  entries.push([watched ? "Mark as unwatched" : "Mark as watched", () => actions.setWatched(item, !watched)]);
  entries.push([item.type === "episode" ? `Go to ${item.grandparentTitle ?? "the show"}` : "Details", () => actions.details(item)]);
  if (actions.removeFromContinueWatching) {
    const remove = actions.removeFromContinueWatching;
    entries.push(["Remove from Continue Watching", () => remove(item)]);
  }
  return (
    <div class="layer menu-shade" data-layer>
      <div class="options item-menu">
        {props.image ? <div class="menu-art" style={{ backgroundImage: `url("${props.image}")` }} /> : null}
        <div class="player-title">{plex.rowTitle(item)}</div>
        <div class="facts">{item.type === "episode" ? item.title : plex.caption(item)}</div>
        <div class="menu-list">
          {entries.map(([label, f], i) => (
            <button key={label} class="option" data-focus data-autofocus={i === 0 ? "" : undefined} onClick={run(f)}>
              <span class="tick" />
              {label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
