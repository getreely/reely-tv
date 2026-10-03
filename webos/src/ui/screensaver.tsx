import { useEffect, useState } from "preact/hooks";
import * as plex from "../api/plex";
import type { App, AppState } from "../app/store";
import { onKeys } from "./focus";

/*
 * The screensaver, as the Fire TV's: the library's artwork one picture at a time, drifting
 * slowly so nothing sits still on the screen, with what each is from and the time. Any
 * button puts it away, and that press does nothing else.
 */

export interface Slide {
  url: string;
  title: string;
  caption: string | null;
}

/** How long each picture stays up. */
export const SLIDE_MS = 12_000;

/** The artwork from Home: what's being watched, what's new, what's wanted. */
export function slidesFrom(app: App, state: AppState): Slide[] {
  const h = state.home;
  const items = [...h.continueWatching, ...h.recentMovies, ...h.recentEpisodes.map((g) => g.newest), ...h.watchlist, ...h.iptvMovies];
  const seen = new Set<string>();
  const out: Slide[] = [];
  for (const i of items) {
    const art = i.type === "episode" ? i.art ?? i.thumb : i.art;
    const url = art ? app.image(i.serverBase, art, 1920, 1080) : null;
    if (!url || seen.has(url)) continue;
    seen.add(url);
    out.push({ url, title: plex.rowTitle(i), caption: plex.caption(i) });
  }
  return out;
}

export function Screensaver(props: { slides: Slide[]; onWake: () => void }) {
  const [index, setIndex] = useState(0);
  const [now, setNow] = useState(new Date());
  useEffect(() => onKeys(() => { props.onWake(); return true; }), []);
  useEffect(() => {
    const t = setInterval(() => setIndex((i) => i + 1), SLIDE_MS);
    const c = setInterval(() => setNow(new Date()), 15_000);
    return () => { clearInterval(t); clearInterval(c); };
  }, []);
  const slide = props.slides.length ? props.slides[index % props.slides.length] : null;
  return (
    <div class="saver" data-layer onClick={props.onWake}>
      {slide ? (
        <div key={`${index}:${slide.url}`} class="saver-slide">
          <div class="saver-art" style={{ backgroundImage: `url("${slide.url}")` }} />
          <div class="saver-words">
            <div class="player-title">{slide.title}</div>
            {slide.caption ? <div class="facts">{slide.caption}</div> : null}
          </div>
        </div>
      ) : null}
      <div class="saver-clock">{now.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</div>
    </div>
  );
}
