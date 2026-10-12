// Amazon's react-native-kepler, as far as the screens use it, for the browser preview:
// focus guides (trapping and remembering), remote keys, and the focus manager. Arrow keys
// move focus as Vega's focus engine would; Escape is Back.
import React, { useEffect, useRef } from 'react';
import { View } from 'react-native-web';
import { nextIndex, type Direction } from './spatial';

export type HWEvent = { eventType: string; eventKeyAction?: number };

const KEYS: Record<string, string> = {
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'select', Escape: 'back',
  MediaPlayPause: 'playpause', ' ': 'playpause', MediaRewind: 'rewind', MediaFastForward: 'fastforward',
};

const listeners = new Set<(e: HWEvent) => void>();

export function useTVEventHandler(handler: (e: HWEvent) => void) {
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => {
    const l = (e: HWEvent) => latest.current(e);
    listeners.add(l);
    return () => { listeners.delete(l); };
  }, []);
}

const focusables = (root: ParentNode = document) =>
  Array.from(root.querySelectorAll<HTMLElement>('[tabindex="0"]')).filter((el) => {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
  });

/** The guide trapping the cursor, if the focused element is in one: the innermost. */
const trapOf = (el: Element | null) => el?.closest<HTMLElement>('[data-trap="1"]') ?? null;

/** Which element each remembering guide last had focused. */
const remembered = new WeakMap<HTMLElement, HTMLElement>();

function move(direction: Direction) {
  const active = document.activeElement as HTMLElement | null;
  const trap = trapOf(active);
  // The topmost trap on screen holds the cursor even when it's outside: a menu just opened.
  const traps = Array.from(document.querySelectorAll<HTMLElement>('[data-trap="1"]'));
  const scope = trap ?? traps[traps.length - 1] ?? document;
  const all = focusables(scope);
  if (!active || active === document.body || !all.includes(active)) {
    all[0]?.focus();
    return;
  }
  const others = all.filter((el) => el !== active);
  const at = nextIndex(active.getBoundingClientRect(), others.map((el) => el.getBoundingClientRect()), direction);
  if (at < 0) return;
  let target = others[at];
  // A guide trapping this direction keeps the cursor in it.
  const held = active.closest<HTMLElement>(`[data-trap-${direction}="1"]`);
  if (held && !held.contains(target)) return;
  // Into a remembering guide from outside it: the element it last had.
  const guide = target.closest<HTMLElement>('[data-autofocus="1"]');
  if (guide && !guide.contains(active)) {
    const last = remembered.get(guide);
    const first = all.find((el) => guide.contains(el));
    if (last && last.isConnected && all.includes(last)) target = last;
    else if (first) target = first;
  }
  target.focus();
  target.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

if (typeof window !== 'undefined') {
  document.addEventListener('focusin', (e) => {
    let el = (e.target as HTMLElement).closest<HTMLElement>('[data-autofocus="1"]');
    while (el) {
      remembered.set(el, e.target as HTMLElement);
      el = el.parentElement?.closest<HTMLElement>('[data-autofocus="1"]') ?? null;
    }
  });
  window.addEventListener('keydown', (e) => {
    const type = KEYS[e.key];
    if (!type) return;
    listeners.forEach((l) => l({ eventType: type, eventKeyAction: 0 }));
    if (type === 'up' || type === 'down' || type === 'left' || type === 'right') {
      if ((e.target as HTMLElement).tagName === 'INPUT' && (type === 'left' || type === 'right')) return;
      e.preventDefault();
      move(type);
    }
  });
  window.addEventListener('keyup', (e) => {
    const type = KEYS[e.key];
    if (type) listeners.forEach((l) => l({ eventType: type, eventKeyAction: 1 }));
    // OK presses what has the cursor, on its release, as Vega's Pressable does.
    const active = document.activeElement as HTMLElement | null;
    if (e.key === 'Enter' && active && active.getAttribute('tabindex') === '0' && active.tagName !== 'INPUT') active.click();
  });
  // hasTVPreferredFocus: a newly drawn element marked preferred takes focus, as on the stick.
  const seen = new WeakSet<Element>();
  new MutationObserver(() => {
    const fresh = Array.from(document.querySelectorAll('#tv-preferred')).filter((el) => !seen.has(el));
    fresh.forEach((el) => seen.add(el));
    const last = fresh[fresh.length - 1] as HTMLElement | undefined;
    if (last) setTimeout(() => last.focus(), 0);
  }).observe(document.documentElement, { subtree: true, childList: true });
}

export const TVFocusGuideView = React.forwardRef<unknown, any>(function TVFocusGuideView(props, ref) {
  const { autoFocus, trapFocusUp, trapFocusDown, trapFocusLeft, trapFocusRight, destinations, safePadding, ...rest } = props;
  const trap = trapFocusUp && trapFocusDown && trapFocusLeft && trapFocusRight;
  const dataSet = {
    trap: trap ? '1' : '0', autofocus: autoFocus ? '1' : '0',
    trapUp: trapFocusUp ? '1' : '0', trapDown: trapFocusDown ? '1' : '0', trapLeft: trapFocusLeft ? '1' : '0', trapRight: trapFocusRight ? '1' : '0',
  };
  return <View ref={ref} {...rest} dataSet={dataSet} />;
});

export const FocusManager = {
  focus(target: any) {
    const el = (target?.current ?? target) as HTMLElement | null;
    el?.focus?.();
  },
  blur(target: any) {
    const el = (target?.current ?? target) as HTMLElement | null;
    el?.blur?.();
  },
  getFocused: () => undefined,
};
