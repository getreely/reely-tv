import { useRef, useSyncExternalStore } from 'react';

/*
 * What has the cursor, for the one part of a page that shows it (Home's hero, a library's
 * rail). Kept out of the page's own state: there, every press redrew every row and card
 * on the page, and on the stick each press waited on that.
 */
export class FocusSignal<T> {
  private value: T | null = null;
  private listeners = new Set<() => void>();
  get = () => this.value;
  set = (next: T | null) => {
    if (next === this.value) return;
    this.value = next;
    this.listeners.forEach((l) => l());
  };
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
}

/** A page's own signal, for as long as the page is up. */
export function useFocusSignal<T>(): FocusSignal<T> {
  const signal = useRef<FocusSignal<T> | null>(null);
  if (!signal.current) signal.current = new FocusSignal<T>();
  return signal.current;
}

/** What [signal] holds now; only the component that asks is drawn again when it changes. */
export function useFocused<T>(signal: FocusSignal<T>): T | null {
  return useSyncExternalStore(signal.subscribe, signal.get, signal.get);
}
