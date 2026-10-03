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
}

let pressedAt = 0;
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

export function move(direction: "up" | "down" | "left" | "right"): boolean {
  const all = focusables();
  const active = document.activeElement as HTMLElement | null;
  if (!active || !all.includes(active)) {
    rescue();
    return true;
  }
  const others = all.filter((el) => el !== active);
  const at = nextIndex(active.getBoundingClientRect(), others.map((el) => el.getBoundingClientRect()), direction);
  if (at < 0) return false;
  focus(others[at]);
  return true;
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
