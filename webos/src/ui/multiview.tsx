import { Fragment, type ComponentChildren } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { REFUSED, type Rect } from "../core/multiview";
import { isVega, onAway } from "../core/platform";
import { clearSource, detach, setSource, statusOf } from "./media";
import { MenuIcon, type MenuIconName } from "./menu";
import { focus, onKeys } from "./focus";

/*
 * Multiview, on Vega: several channels on the screen at once, as on the Fire TV
 * (MultiView.kt). The tiles are laid out in a nominal 1920 by 1080 and drawn in shares of
 * the screen, so they fit whatever the WebView's size.
 */

export const SCREEN_W = 1920;
export const SCREEN_H = 1080;
/** The line between tiles. */
export const TILE_GAP = 4;

/** [r] as a share of the screen, for a style. */
export const placed = (r: Rect) => ({
  left: `${(r.left / SCREEN_W) * 100}%`,
  top: `${(r.top / SCREEN_H) * 100}%`,
  width: `${(r.width / SCREEN_W) * 100}%`,
  height: `${(r.height / SCREEN_H) * 100}%`,
});

/** The shape of what [video] is showing: sixteen by nine until its first frame says. */
export function useAspect(video: { current: HTMLVideoElement | null }): number {
  const [aspect, setAspect] = useState(16 / 9);
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    const read = () => setAspect(v.videoWidth > 0 && v.videoHeight > 0 ? v.videoWidth / v.videoHeight : 16 / 9);
    v.addEventListener("loadedmetadata", read);
    v.addEventListener("resize", read);
    return () => {
      v.removeEventListener("loadedmetadata", read);
      v.removeEventListener("resize", read);
    };
  }, []);
  return aspect;
}

/**
 * A tile: its picture, and once there's more than one, the channel's name, and an outline
 * on the one being heard. The outline follows the picture rather than the cell: in a two-way
 * split the picture is a band across the middle of a tall cell, and a ring on the cell stood
 * well off it.
 */
export function Tile(props: { rect: Rect; zoomed: boolean; framed: boolean; name: string; heard: boolean; aspect: number; children: ComponentChildren; message?: string | null }) {
  const { rect, zoomed, framed, aspect } = props;
  const byWidth = rect.width / aspect <= rect.height;
  const pictureW = byWidth ? rect.width : rect.height * aspect;
  const pictureH = byWidth ? rect.width / aspect : rect.height;
  return (
    <div class={"tile" + (zoomed ? " zoomed" : "")} style={placed(zoomed ? { left: 0, top: 0, width: SCREEN_W, height: SCREEN_H } : rect)}>
      {props.children}
      {framed && !zoomed ? (
        <div class={"tile-frame" + (props.heard ? " heard" : "")}
          style={{ width: `${(pictureW / rect.width) * 100}%`, height: `${(pictureH / rect.height) * 100}%` }}>
          <span class="tile-name">{props.name}</span>
        </div>
      ) : null}
      {props.message ? <div class="tile-message"><p>{props.message}</p></div> : null}
    </div>
  );
}

const TILE_RETRIES = 3;
const TILE_RETRY_MS = 3_000;

/**
 * One of the channels beside the main one, playing for as long as it's up. Only the one
 * with the cursor on it is heard. A channel that drops is tried a few more times; one the
 * provider won't open (too many at once, most often) or that won't play is said so on it.
 */
export function ExtraTile(props: { url: string; name: string; rect: Rect; heard: boolean; zoomed: boolean }) {
  const video = useRef<HTMLVideoElement>(null);
  const aspect = useAspect(video);
  const [failure, setFailure] = useState<string | null>(null);
  const attempts = useRef(0);
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    attempts.current = 0;
    setFailure(null);
    let retry: ReturnType<typeof setTimeout> | undefined;
    const start = () => {
      setSource(v, props.url);
      void v.play().catch(() => undefined);
    };
    const playing = () => {
      attempts.current = 0;
      setFailure(null);
    };
    const failed = () => {
      const status = statusOf(v);
      if (status != null && REFUSED.includes(status)) setFailure("Your provider won't open another channel at once.");
      else if (attempts.current < TILE_RETRIES) {
        attempts.current++;
        retry = setTimeout(start, TILE_RETRY_MS * attempts.current);
      } else setFailure("This channel isn't playing.");
    };
    v.addEventListener("playing", playing);
    v.addEventListener("error", failed);
    start();
    // Out of the app and back: let go of the stream while away, and join it again on return.
    const away = isVega() ? onAway((gone) => { if (gone) { clearTimeout(retry); clearSource(v); } else start(); }) : undefined;
    return () => {
      clearTimeout(retry);
      away?.();
      v.removeEventListener("playing", playing);
      v.removeEventListener("error", failed);
      detach(v);
    };
  }, [props.url]);
  useEffect(() => {
    if (video.current) video.current.muted = !props.heard;
  }, [props.heard]);
  return (
    <Tile rect={props.rect} zoomed={props.zoomed} framed name={props.name} heard={props.heard} aspect={aspect} message={failure}>
      <video ref={video} class="video" playsInline muted={!props.heard} />
    </Tile>
  );
}

/** The spare cell: somewhere obvious to put another channel. */
export function AddTile(props: { rect: Rect; focused: boolean }) {
  return (
    <div class={"tile add-tile" + (props.focused ? " heard" : "")} style={placed(props.rect)}>
      <span class="add-plus" aria-hidden="true">+</span>
      <span>Add a channel</span>
    </div>
  );
}

export interface TileMenuChoices {
  name: string;
  /** False with one channel up, where the tile already fills the screen. */
  canMaximize: boolean;
  /** False once four channels are up, which is all the screen holds. */
  canAdd: boolean;
  canMoveBack: boolean;
  canMoveOn: boolean;
  canSave: boolean;
  /** The saved set, when there's one that isn't what's already up. */
  saved: string | null;
  /** The main channel is the player itself: closing it would be closing the screen. */
  canClose: boolean;
}

export interface TileMenuActions {
  maximize: () => void;
  add: () => void;
  replace: () => void;
  move: (delta: number) => void;
  save: () => void;
  openSaved: () => void;
  close: () => void;
  cancel: () => void;
}

/**
 * Holding OK on a tile, as on the Fire TV: full screen, another channel, a different one
 * here, moving it, the saved set, or closing it. Back puts it away.
 */
export function TileMenu(props: { choices: TileMenuChoices; actions: TileMenuActions }) {
  const { choices: c, actions: a } = props;
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => { focus(first.current); }, []);
  useEffect(() => onKeys((key) => {
    if (key !== "back") return false;
    a.cancel();
    return true;
  }), []);
  const rows: Array<{ label: string; icon: MenuIconName; run: () => void; detail?: string; section?: string }> = [];
  if (c.canMaximize) rows.push({ label: "Full screen", icon: "play", run: a.maximize });
  if (c.canAdd) rows.push({ label: "Add another channel", icon: "plus", run: a.add });
  rows.push({ label: "Replace channel", icon: "tiles", run: a.replace });
  // Left and right, as the tiles run in both layouts: the focus layout is a line, the grid reads across then down.
  if (c.canMoveBack) rows.push({ label: "Move left", icon: "left", run: () => a.move(-1) });
  if (c.canMoveOn) rows.push({ label: "Move right", icon: "right", run: () => a.move(1) });
  if (c.canSave) rows.push({ label: "Save these channels", icon: "heart", run: a.save, section: "Saved channels" });
  if (c.saved) rows.push({ label: "Open saved", icon: "tiles", run: a.openSaved, detail: c.saved, section: c.canSave ? undefined : "Saved channels" });
  if (c.canClose) rows.push({ label: "Close channel", icon: "cross", run: a.close });
  return (
    <div class="options tile-menu" data-layer>
      <h3>{c.name}</h3>
      {rows.map((row, i) => (
        <Fragment key={row.label}>
          {row.section ? <h3 class="menu-section">{row.section}</h3> : null}
          <button ref={i === 0 ? first : undefined} class="option" data-focus onClick={row.run}>
            <span class="tick"><MenuIcon name={row.icon} /></span>
            <span class="option-text">{row.label}{row.detail ? <span class="option-note">{row.detail}</span> : null}</span>
          </button>
        </Fragment>
      ))}
    </div>
  );
}
