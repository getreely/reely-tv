/*
 * Match frame rate, as on the Fire TV (MatchFrameRate.kt): the TV switched to the film's
 * own rate while it plays (24 frames a second rather than 60, say, so motion is smooth),
 * and back afterwards. Through Amazon's display API, which reads the display for any app
 * but switches it only for one the stick grants the display privilege. When it won't, that
 * is remembered and Settings says where the Fire TV's own setting is.
 */

import { optionalModule } from './native';

type GraphicsModule = typeof import('@amazon-devices/kepler-graphics');

let mod: GraphicsModule | null | undefined;
function load(): GraphicsModule | null {
  if (mod === undefined) {
    mod = optionalModule<GraphicsModule>('graphics');
  }
  return mod;
}

/** How long a switch is given to say whether it took. */
const COMMIT_WAIT_MS = 4_000;

/** The rate the TV was at before a film changed it, to go back to. */
let original: number | null = null;
/** Set once the stick has refused: there's no asking again. */
let refused = false;
const listeners = new Set<() => void>();

export const frameRateRefused = () => refused || load() == null;
export function onFrameRateChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * The display's rate (millihertz) that shows [fps] best: the same rate, or a whole
 * multiple of it (48 for 24, 50 for 25). Null when the display has neither.
 */
export function bestRate(fps: number, supported: number[]): number | null {
  const want = fps * 1000;
  for (const multiple of [1, 2]) {
    const target = want * multiple;
    const hit = supported.find((r) => Math.abs(r - target) <= target * 0.002);
    if (hit) return hit;
  }
  return null;
}

/** The TV to [fps]'s rate. Quietly nothing when it already is, can't, or won't. */
export async function matchFrameRate(fps: number | null): Promise<void> {
  const m = load();
  if (!m || refused || !fps) return;
  try {
    if (!m.DisplayController.isPrimaryDisplayConnected()) return;
    const config = m.Display.getCurrentConfig();
    const now = m.Display.getCurrentRefreshRateInMillihertz();
    const target = bestRate(fps, config.supportedRefreshRatesInMillihertz ?? []);
    if (!target || target === now) return;
    if (original == null) original = now;
    if (!(await apply(m, config, target))) original = null;
  } catch {
    refuse();
  }
}

/** Back to the rate the TV had before the film. */
export async function restoreFrameRate(): Promise<void> {
  const m = load();
  const back = original;
  original = null;
  if (!m || back == null || refused) return;
  try {
    await apply(m, m.Display.getCurrentConfig(), back);
  } catch {
    refuse();
  }
}

function refuse() {
  refused = true;
  listeners.forEach((l) => l());
}

/** Asks for the change and waits for the display to say it took; a failure is a refusal. */
function apply(m: GraphicsModule, config: ReturnType<GraphicsModule['Display']['getCurrentConfig']>, rate: number): Promise<boolean> {
  return new Promise((resolve) => {
    let sub: { unsubscribe(): void } | null = null;
    const done = (ok: boolean) => {
      clearTimeout(timer);
      sub?.unsubscribe();
      if (!ok) refuse();
      resolve(ok);
    };
    const timer = setTimeout(() => done(false), COMMIT_WAIT_MS);
    try {
      sub = m.DisplayManager.addListener({ onCommitSuccess: () => done(true), onCommitFailure: () => done(false) });
      m.DisplayManager.requestConfigChange(config, rate);
      m.DisplayManager.commitStates();
    } catch {
      done(false);
    }
  });
}
