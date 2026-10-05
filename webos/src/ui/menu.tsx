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
  const entries: Array<[string, MenuIconName, () => void]> = [];
  if ((item.type === "show" || item.type === "season") && item.leafCount > 0) {
    entries.push([item.viewedLeafCount === 0 ? "Play first episode" : watched ? "Play from the start" : "Play next episode", "play", () => actions.playNext(item)]);
  }
  if (plex.isPlayable(item)) {
    entries.push([resumable ? "Resume" : "Play", "play", () => actions.play(item, true)]);
    if (resumable) entries.push(["Play from the beginning", "restart", () => actions.play(item, false)]);
  }
  entries.push([watched ? "Mark as unwatched" : "Mark as watched", "check", () => actions.setWatched(item, !watched)]);
  entries.push([item.type === "episode" ? `Go to ${item.grandparentTitle ?? "the show"}` : "Details", "info", () => actions.details(item)]);
  if (actions.removeFromContinueWatching) {
    const remove = actions.removeFromContinueWatching;
    entries.push(["Remove from Continue Watching", "cross", () => remove(item)]);
  }
  return (
    <div class="layer menu-shade" data-layer>
      <div class="options item-menu">
        {props.image ? <div class="menu-art" style={{ backgroundImage: `url("${props.image}")` }} /> : null}
        <div class="player-title">{plex.rowTitle(item)}</div>
        <div class="facts">{item.type === "episode" ? item.title : plex.caption(item)}</div>
        <div class="menu-list">
          {entries.map(([label, icon, f], i) => (
            <button key={label} class="option" data-focus data-autofocus={i === 0 ? "" : undefined} onClick={run(f)}>
              <span class="tick"><MenuIcon name={icon} /></span>
              {label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

type MenuIconName = "play" | "restart" | "check" | "info" | "cross";

/** Each choice's icon, as the Fire TV's menu has them. */
function MenuIcon(props: { name: MenuIconName }) {
  const stroke = { fill: "none", stroke: "currentColor", "stroke-width": 9, "stroke-linecap": "round" as const, "stroke-linejoin": "round" as const };
  const body = props.name === "play" ? <path d="M34 22 L78 50 L34 78 Z" fill="currentColor" />
    : props.name === "restart" ? <><path d="M30 34 A28 28 0 1 1 26 62" {...stroke} /><path d="M22 20 L30 36 L46 30" {...stroke} /></>
    : props.name === "check" ? <path d="M24 52 L42 70 L76 32" {...stroke} />
    : props.name === "info" ? <><circle cx="50" cy="50" r="32" {...stroke} /><path d="M50 46 V68" {...stroke} /><circle cx="50" cy="33" r="5" fill="currentColor" /></>
    : <path d="M30 30 L70 70 M70 30 L30 70" {...stroke} />;
  return <svg class="menu-icon" viewBox="0 0 100 100" aria-hidden="true">{body}</svg>;
}
