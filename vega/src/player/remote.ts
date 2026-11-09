/*
 * The remote's play/pause, rewind and fast-forward are the app's own to act on (the
 * player screens' key handlers). Left alone, Amazon's player also opens a "player session"
 * with the system for any app whose manifest doesn't declare Vega Media Controls, and that
 * session acts on the same presses itself, straight on whichever player was made last:
 * a press on a paused film played it (the app) and paused it again (the session); rewind
 * and fast-forward jumped the player ten seconds under the scrub the app had started, and
 * in a stream that can't be sought in (HEVC through Plex) that jump stalled it.
 *
 * The switch is a static on the class both of Amazon's players extend, read as each
 * player is made ready, so it's thrown once, before the first of them.
 */
let owned = false;

/** [player] is either of Amazon's players, VideoPlayer or AudioPlayer: the class, not one of them. */
export function ownRemoteKeys(player: unknown) {
  if (owned) return;
  owned = true;
  try {
    const base = Object.getPrototypeOf(player) as { isPlayerSessionEnabled?: boolean } | null;
    if (base && typeof base.isPlayerSessionEnabled === 'boolean') base.isPlayerSessionEnabled = false;
    else console.warn('[reely] no player session switch in this player: the system may act on the remote too');
  } catch (e) {
    console.warn('[reely] player session', e);
  }
}

/**
 * Whether a play/pause press is one to act on: a key held a moment too long repeats, and
 * each repeat would undo the one before.
 */
const REPEAT_MS = 350;
let lastToggle = 0;

export function freshToggle(): boolean {
  const now = Date.now();
  if (now - lastToggle < REPEAT_MS) return false;
  lastToggle = now;
  return true;
}
