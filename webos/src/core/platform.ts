/*
 * Which TV this is running on. The same app runs on LG TVs (webOS) and, inside a WebView
 * in a small React Native shell, on Fire TV's Vega OS. The shell puts its messenger on
 * the page before anything else runs, so asking is enough.
 */

interface Shell {
  postMessage(message: string): void;
}

/** The Vega shell's messenger, when there is one. */
export function shell(): Shell | null {
  if (typeof window === "undefined") return null;
  return (window as unknown as { ReactNativeWebView?: Shell }).ReactNativeWebView ?? null;
}

/** Running inside the Vega app's WebView. */
export const isVega = (): boolean => shell() !== null;

/**
 * [changed] when the app goes away (Home, another app) and when it comes back, once each:
 * the page's own visibility, or the shell's word for it (it says so too, in case the
 * WebView doesn't). Returns the way to stop listening.
 */
export function onAway(changed: (away: boolean) => void): () => void {
  let away = typeof document !== "undefined" && document.hidden;
  const set = (now: boolean) => {
    if (now === away) return;
    away = now;
    changed(now);
  };
  const visibility = () => set(document.hidden);
  const shellSaid = (event: Event) => set((event as CustomEvent<string>).detail === "away");
  document.addEventListener("visibilitychange", visibility);
  document.addEventListener("reely-app-state", shellSaid);
  return () => {
    document.removeEventListener("visibilitychange", visibility);
    document.removeEventListener("reely-app-state", shellSaid);
  };
}
