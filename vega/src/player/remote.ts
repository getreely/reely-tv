/*
 * One press, one change. Amazon's player keeps a "player session" with the system, and
 * through it the system plays, pauses and jumps the player itself: for Alexa ("pause",
 * "fast forward"), and also for the remote's own play/pause, rewind and fast-forward,
 * which the app's screens already act on as keys. Both acting on one press undid it: a
 * paused film played (the app) and was paused again (the session), and rewind and
 * fast-forward jumped the player under the scrub the app had started.
 *
 * So what the session asks of a player comes through the engine (engine.tsx), and is
 * dropped when the app has just acted on the same press as a key; a key is dropped when
 * the session has just acted on it. Asked by voice, with no key about, it's done.
 */

/** How close together a key and what the session asks count as the same press. */
const SAME_PRESS_MS = 800;
/** A play/pause key held a moment too long repeats, and each repeat would undo the one before. */
const REPEAT_MS = 350;

let toggleKeyAt = 0;
let jumpKeyAt = 0;
let sessionToggleAt = 0;

/** Whether a play/pause key press is one to act on. */
export function freshToggle(): boolean {
  const now = Date.now();
  if (now - toggleKeyAt < REPEAT_MS || now - sessionToggleAt < SAME_PRESS_MS) return false;
  toggleKeyAt = now;
  return true;
}

/** A rewind or fast-forward key, which the screen scrubs with. */
export function sawJumpKey() {
  jumpKeyAt = Date.now();
}

/** Whether the session's play or pause is one to act on: not the echo of a key just handled. */
export function sessionMayToggle(): boolean {
  const now = Date.now();
  if (now - toggleKeyAt < SAME_PRESS_MS) return false;
  sessionToggleAt = now;
  return true;
}

/** Whether the session's jump is one to act on: asked a moment after it came, so a key just behind it is seen. */
export function sessionMayJump(): boolean {
  return Date.now() - jumpKeyAt > SAME_PRESS_MS;
}
