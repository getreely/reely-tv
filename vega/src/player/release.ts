/*
 * Players being let go, video and sound alike, which a new player waits for before it
 * starts. The stick has one sound output: a show's theme music still being let go when
 * an episode opened held it, and the episode's Dolby sound, sent to the TV as it is,
 * couldn't have it ("failed to open playback ... Cannot allocate memory"), so the file
 * stopped and Plex was asked to convert it. A channel opened while the last one's player
 * was still being let go never started, for the same reason with the decoder.
 */

/** How long a new player waits for those being let go: past that, it starts regardless. */
const LET_GO_WAIT_MS = 3_000;

let lettingGo: Promise<unknown> = Promise.resolve();

/** [done] counted among the players being let go until it settles. */
export function letGoOf(done: Promise<unknown>): Promise<void> {
  const settled = done.then(() => undefined, () => undefined);
  lettingGo = Promise.all([lettingGo, settled]);
  return settled;
}

/**
 * Settles once every player being let go has been, or after a few seconds regardless.
 * Asked a turn later, so a screen going in the same moment a player comes (a show's
 * page, its theme music with it, as the episode opens) has said it's letting go first.
 */
export function playersLetGo(): Promise<void> {
  return new Promise<void>((turn) => setTimeout(() => turn(), 0)).then(() =>
    Promise.race([lettingGo.then(() => undefined, () => undefined), new Promise<void>((done) => setTimeout(() => done(), LET_GO_WAIT_MS))]));
}
