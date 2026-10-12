import { Engine } from './engine';

/*
 * The one player live TV uses, the guide's preview and the channel full screen alike
 * (the Fire TV's LivePlayer): choosing a channel moves where its picture is drawn rather
 * than opening a second connection to the provider, which may allow only one.
 */

let shared: Engine | null = null;
let url: string | null = null;

export function liveEngine(): Engine {
  if (!shared) shared = new Engine();
  return shared;
}

/** Plays [next] unless it's already what's playing. */
export function playLive(next: string) {
  if (url === next) return;
  url = next;
  void liveEngine().open(next);
}

/** Nothing live playing: the player stopped, and let go, until it's wanted again. */
export function stopLive() {
  url = null;
  if (shared) {
    const gone = shared;
    shared = null;
    void gone.release();
  }
}

export const liveUrl = () => url;
