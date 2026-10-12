import { useEffect, useState } from 'react';
import { Engine } from './engine';

/*
 * The one player live TV uses, the guide's preview and the channel full screen alike
 * (the Fire TV's LivePlayer): choosing a channel moves where its picture is drawn rather
 * than opening a second connection to the provider, which may allow only one.
 */

let shared: Engine | null = null;
let url: string | null = null;
const listeners = new Set<() => void>();

/** Whoever draws the live picture, told the player has been let go: it draws the next one. */
function changed() {
  // After the drawing under way, not during it.
  setTimeout(() => listeners.forEach((l) => l()), 0);
}

export function liveEngine(): Engine {
  if (!shared) shared = new Engine();
  return shared;
}

/**
 * The live player, for a component that draws it, drawn again when it's replaced. Kept
 * from one drawing to the next, a guide's preview held on to a player that had been let
 * go: it showed that one's last picture, and the next channel opened with nowhere to be
 * drawn and never started.
 */
export function useLiveEngine(): Engine {
  const [, bump] = useState(0);
  useEffect(() => {
    const listener = () => bump((n) => n + 1);
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }, []);
  return liveEngine();
}

/** Plays [next] unless it's already what's playing. */
export function playLive(next: string) {
  if (url === next) return;
  url = next;
  void liveEngine().open(next);
}

/**
 * The channel left behind stops at once, the player kept for the next (the Fire TV's
 * guide stops its preview as the cursor moves, and plays the next once it rests).
 */
export function pauseLive() {
  if (url == null || !shared) return;
  url = null;
  try {
    shared.pause();
  } catch {
    // Not started.
  }
}

/** Nothing live playing: the player stopped, and let go, until it's wanted again. */
export function stopLive() {
  url = null;
  if (shared) {
    const gone = shared;
    shared = null;
    void gone.release();
    changed();
  }
}

export const liveUrl = () => url;
