/*
 * clearTimeout and clearInterval as a browser has them: a handle that's already gone, or
 * never was, is let be. Vega's throw instead ("clearTimeout called with an invalid
 * handle"), and hls.js clears its timers that way as each segment arrives: the throw
 * ended its handler there, and no segment ever reached the player.
 */
type Clear = (handle?: unknown) => void;
const g = globalThis as unknown as { clearTimeout: Clear; clearInterval: Clear };

function forgiving(clear: Clear): Clear {
  return (handle?: unknown) => {
    if (handle == null) return;
    try {
      clear(handle);
    } catch {
      // Gone already.
    }
  };
}

// Should the runtime not let them be replaced, the app still starts, as it did before.
try {
  g.clearTimeout = forgiving(g.clearTimeout.bind(globalThis));
  g.clearInterval = forgiving(g.clearInterval.bind(globalThis));
  // hls.js calls them as self.clearTimeout; self and window are the global here, but in case not.
  for (const name of ['self', 'window'] as const) {
    const other = (globalThis as unknown as Record<string, Partial<typeof g> | undefined>)[name];
    if (other && other !== (globalThis as unknown)) {
      other.clearTimeout = g.clearTimeout;
      other.clearInterval = g.clearInterval;
    }
  }
} catch (e) {
  console.warn('[reely] timers left as they are', e);
}
