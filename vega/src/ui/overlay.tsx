import React, { createContext, useContext, useEffect, useLayoutEffect, useReducer, useRef } from 'react';

/*
 * Drawing over the whole app from inside a page. A page sits under the top bar, and
 * nothing in it can draw over its parent, but the Fire TV's menus cover the full height
 * of the screen: an [Overlay] hands what it holds to the [OverlayHost] at the top of the
 * app, which draws it there. Its state stays with the component that made it.
 */

export class Overlays {
  readonly entries = new Map<number, React.ReactNode>();
  private listeners = new Set<() => void>();
  private next = 0;
  id() { return this.next++; }
  set(id: number, node: React.ReactNode) {
    this.entries.set(id, node);
    this.listeners.forEach((l) => l());
  }
  remove(id: number) {
    if (!this.entries.delete(id)) return;
    this.listeners.forEach((l) => l());
  }
  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }
}

export const OverlayContext = createContext<Overlays | null>(null);

/** Where overlays are drawn: last in the app, over the top bar. */
export function OverlayHost(props: { overlays: Overlays }) {
  const [, redraw] = useReducer((n: number) => n + 1, 0);
  useEffect(() => props.overlays.subscribe(redraw), [props.overlays]);
  return <>{[...props.overlays.entries].map(([id, node]) => <React.Fragment key={id}>{node}</React.Fragment>)}</>;
}

/** [children], drawn over the whole app; in place when there's no host. */
export function Overlay(props: { children: React.ReactNode }) {
  const overlays = useContext(OverlayContext);
  const id = useRef<number | null>(null);
  if (overlays && id.current == null) id.current = overlays.id();
  useLayoutEffect(() => {
    if (overlays && id.current != null) overlays.set(id.current, props.children);
  });
  useLayoutEffect(() => () => {
    if (overlays && id.current != null) overlays.remove(id.current);
  }, [overlays]);
  return overlays ? null : <>{props.children}</>;
}
