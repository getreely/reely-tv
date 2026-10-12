import type { PlexPlayback } from '../api/plex';
import { checkPlayback, fallbackPlan, type Verdict } from './formats';

/*
 * The questions app.play asks of this TV (formats.ts): whether a title plays as it is,
 * and, for one that said it would and then didn't, what Plex should make of it instead.
 */
export interface PlaybackCheck {
  check: (p: PlexPlayback) => Promise<Verdict>;
  fallback: typeof fallbackPlan;
}

export const canDirect: PlaybackCheck = { check: checkPlayback, fallback: fallbackPlan };
