import { actionOf, isDirection, type Action } from "../core/keys";
import { nextIndex } from "../core/spatial";

/*
 * The cursor. Anything that can take it carries data-focus; the arrows move it to the
 * nearest such thing that way, OK presses it. A dialog marks itself data-layer and the
 * cursor stays inside the topmost one, as the Fire TV keeps it inside a menu.
 */

export type KeyHandler = (action: Action) => boolean;

const handlers: KeyHandler[] = [];

/** A screen's own keys, tried before the cursor's; newest first. Returns the way to remove it. */
export function onKeys(handler: KeyHandler): () => void {
  handlers.unshift(handler);
  return () => {
    const at = handlers.indexOf(handler);
    if (at >= 0) handlers.splice(at, 1);
  };
}

/** Where the cursor can go now: inside the top layer, on screen, not hidden. */
export function focusables(root: ParentNode = document): HTMLElement[] {
  const layers = Array.from(root.querySelectorAll<HTMLElement>("[data-layer]"));
  const scope: ParentNode = layers.length ? layers[layers.length - 1] : root;
  return Array.from(scope.querySelectorAll<HTMLElement>("[data-focus]")).filter((el) => {
    if (el.getAttribute("aria-disabled") === "true") return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  });
}

export function focus(el: HTMLElement | null | undefined) {
  if (!el) return;
  el.focus({ preventScroll: true });
  // Chromium 68 takes the options form; "nearest" keeps rows from jumping about.
  el.scrollIntoView({ block: "nearest", inline: "nearest" });
  // Under a hero, as on the Fire TV: the row with the cursor at the top of the rows, the one
  // before it wholly out of sight rather than its names showing under the hero.
  const rows = el.closest<HTMLElement>(".hero-rows");
  const row = el.closest<HTMLElement>(".row");
  if (rows && row) {
    rows.scrollTop += row.getBoundingClientRect().top - rows.getBoundingClientRect().top;
    return;
  }
  // Far enough for it as it's drawn with the cursor on it, grown and ringed, with a little
  // room: "nearest" goes by its size before, and left the last line of an episode's name
  // and length off the bottom of the page.
  const page = scroller(el);
  if (!page) return;
  const r = el.getBoundingClientRect();
  const box = page.getBoundingClientRect();
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const room = r.height * 0.04 + rem * 1.2;
  if (r.bottom + room > box.bottom) page.scrollTop += r.bottom + room - box.bottom;
  else if (r.top - room < box.top) page.scrollTop -= box.top - (r.top - room);
}

/** The box [el] scrolls up and down in, if any. */
function scroller(el: HTMLElement): HTMLElement | null {
  for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
    const y = getComputedStyle(a).overflowY;
    if (y === "auto" || y === "scroll") return a;
  }
  return null;
}

let pressedAt = Date.now();

/** When a button was last pressed: how long the remote's been put down, for the screensaver. */
export const lastPressAt = () => pressedAt;
let arrivedAt = 0;

/** A new screen: until a key is pressed on it, the cursor belongs in its content. */
export function arrived() {
  arrivedAt = Date.now();
}

/**
 * The cursor somewhere sensible: the screen's first choice, else its first thing. As on
 * the Fire TV, a cursor that fell onto the tabs while the screen was still empty is put
 * into the screen when it fills — unless somebody has moved it since.
 */
export function rescue() {
  const active = document.activeElement as HTMLElement | null;
  const all = focusables();
  const content = all.filter((el) => el.closest("[data-content]") || el.closest("[data-layer]"));
  const settled = active && all.includes(active) && (content.includes(active) || pressedAt > arrivedAt || !content.length);
  if (settled) return;
  const pool = content.length ? content : all;
  focus(pool.find((el) => el.hasAttribute("data-autofocus")) ?? pool[0]);
}

let movedAt = 0;
let movedTo: HTMLElement | null = null;

/** When the cursor was last moved with the arrows. */
export const lastMoveAt = () => movedAt;

/** Whether the arrows just put the cursor on [el]: a tab opens when it's arrived at that way, and only then. */
export const arrowedOnto = (el: EventTarget | null) => el != null && el === movedTo && Date.now() - movedAt < 400;

export function move(direction: "up" | "down" | "left" | "right"): boolean {
  const all = focusables();
  const active = document.activeElement as HTMLElement | null;
  if (!active || !all.includes(active)) {
    rescue();
    return true;
  }
  // Not what's scrolled out of sight in something the cursor isn't in: up from the tabs
  // isn't into a row scrolled away above the page. Within the rows, a row scrolled away is
  // the next one all the same, and comes into sight.
  const others = all.filter((el) => el !== active && !hiddenFrom(el, active));
  const from = active.getBoundingClientRect();
  // Up and down stay in the page while it has anything that way, and only then go to the
  // tabs, as on the Fire TV: a row still sliding into place under the tabs (the rows
  // scroll smoothly) left the tabs looking nearer than the row above, and up skipped it.
  let pool = others;
  const page = active.closest("[data-content]");
  if (page && (direction === "up" || direction === "down")) {
    const within = others.filter((el) => page.contains(el));
    if (nextIndex(from, within.map((el) => el.getBoundingClientRect()), direction) >= 0) pool = within;
  }
  // A page in columns (Settings: the sections, and their rows) marks each data-column: up and
  // down stay in the one the cursor's in, past a group's heading too, and never cross into
  // the other; off its top, the tabs.
  const column = active.closest("[data-column]");
  if (column && (direction === "up" || direction === "down")) {
    const same = pool.filter((el) => column.contains(el));
    pool = nextIndex(from, same.map((el) => el.getBoundingClientRect()), direction) >= 0
      ? same
      : others.filter((el) => !el.closest("[data-column]"));
  }
  const at = nextIndex(from, pool.map((el) => el.getBoundingClientRect()), direction);
  if (at < 0) return false;
  let target = pool[at];
  // Up into the tabs lands on the tab that's open, as on the Fire TV, not whichever is
  // nearest: otherwise going up would open another one.
  if (target.classList.contains("tab") && !active.classList.contains("tab")) {
    const open = document.querySelector<HTMLElement>(".tabs .tab.on");
    if (open && all.includes(open)) target = open;
  }
  movedAt = Date.now();
  movedTo = target;
  focus(target);
  return true;
}

/** Whether [el] is scrolled wholly out of sight in a scrolling box [from] isn't in. */
function hiddenFrom(el: HTMLElement, from: HTMLElement): boolean {
  const r = el.getBoundingClientRect();
  for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
    if (a.contains(from)) return false;
    const style = getComputedStyle(a);
    if (style.overflowX === "visible" && style.overflowY === "visible") continue;
    const box = a.getBoundingClientRect();
    if (r.bottom <= box.top || r.top >= box.bottom || r.right <= box.left || r.left >= box.right) return true;
  }
  return false;
}

/** How long OK is held before it's a hold rather than a press, as on the Fire TV. */
export const HOLD_MS = 450;

/** OK going down on something: pressed when it comes up, unless it was held. */
let okDown: { el: HTMLElement; at: number; held: boolean } | null = null;

export function installKeys(onUnhandledBack: () => void) {
  document.addEventListener(
    "keyup",
    (event) => {
      if (actionOf(event) !== "ok" || !okDown) return;
      const { el, held } = okDown;
      okDown = null;
      if (!held && document.activeElement === el && el.isConnected) {
        event.preventDefault();
        el.click();
      }
    },
    true,
  );
  document.addEventListener(
    "keydown",
    (event) => {
      const action = actionOf(event);
      if (!action) return;
      pressedAt = Date.now();
      // OK held down: the remote repeats it. Past HOLD_MS it's a hold, once, for whatever
      // takes one (a poster's menu); nothing else hears the repeats.
      if (action === "ok" && event.repeat) {
        event.preventDefault();
        if (okDown && !okDown.held && Date.now() - okDown.at >= HOLD_MS) {
          okDown.held = true;
          okDown.el.dispatchEvent(new CustomEvent("hold", { bubbles: true }));
        }
        return;
      }
      for (const handler of handlers.slice()) {
        if (handler(action)) {
          event.preventDefault();
          return;
        }
      }
      if (isDirection(action)) {
        // A text box keeps left and right for its own cursor.
        const typing = document.activeElement instanceof HTMLInputElement && (action === "left" || action === "right");
        if (typing) return;
        event.preventDefault();
        move(action);
      } else if (action === "ok") {
        // Pressed before the cursor had landed (the moment the app opened, say): it lands,
        // and the press counts on what it landed on.
        if (!(document.activeElement as HTMLElement | null)?.hasAttribute("data-focus")) rescue();
        const active = document.activeElement as HTMLElement | null;
        if (active && active.hasAttribute("data-focus") && !(active instanceof HTMLInputElement)) {
          event.preventDefault();
          // Pressed when OK comes up; held long enough, it's a hold instead.
          okDown = { el: active, at: Date.now(), held: false };
        }
      } else if (action === "back") {
        event.preventDefault();
        onUnhandledBack();
      }
    },
    true,
  );
}
