import Hls from "hls.js";
import mpegts from "mpegts.js";
import type { LoaderCallbacks, LoaderConfiguration, LoaderContext, LoaderStats } from "hls.js";
import { shellFetch } from "../core/bridge";
import { askViaShell, askedByShell } from "../core/http";
import { isVega } from "../core/platform";

/*
 * What a <video> plays. An LG TV's own player opens HLS and MPEG-TS by itself; the Fire TV's
 * Vega WebView (Chromium) opens neither, so there hls.js plays HLS (Plex's conversions, a
 * provider's .m3u8 channels and films) and mpegts.js plays a channel sent as bare MPEG-TS.
 * Either one going wrong for good shows as the video's own error, so the players' handling
 * of a dropped connection (a fresh stream, then Try again) works the same everywhere.
 */

interface Attached {
  destroy(): void;
}

const attached = new WeakMap<HTMLVideoElement, Attached>();
/** Why the last attached player gave up: "network" for a dropped connection, else "media". */
const failures = new WeakMap<HTMLVideoElement, "network" | "media">();
/** The status the server answered with when the attached player gave up on it, if it said. */
const statuses = new WeakMap<HTMLVideoElement, number>();

const isHls = (url: string) => /\.m3u8(\?|#|$)/i.test(url);
const isTs = (url: string) => /\.ts(\?|#|$)/i.test(url);

/** Stop whatever is attached to [v], if anything. */
export function detach(v: HTMLVideoElement) {
  const a = attached.get(v);
  if (!a) return;
  attached.delete(v);
  try {
    a.destroy();
  } catch {
    // Already gone.
  }
}

/** Why [v]'s attached player gave up, when it did: a dropped connection counts as the network. */
export const failureOf = (v: HTMLVideoElement) => failures.get(v) ?? null;

/** The HTTP status that ended [v]'s stream, when the attached player saw one: 403, say, for a provider at its limit. */
export const statusOf = (v: HTMLVideoElement) => statuses.get(v) ?? null;

/** Whether the video failed because the connection did: its own error, or the attached player's. */
export const droppedConnection = (v: HTMLVideoElement) =>
  v.error?.code === MediaError.MEDIA_ERR_NETWORK || failures.get(v) === "network";

function fail(v: HTMLVideoElement, why: "network" | "media", status?: number) {
  failures.set(v, why);
  if (typeof status === "number" && status > 0) statuses.set(v, status);
  detach(v);
  v.dispatchEvent(new Event("error"));
}

/** Plays [url] in [v]: the TV's own player where it opens it, else hls.js or mpegts.js. */
export function setSource(v: HTMLVideoElement, url: string) {
  detach(v);
  failures.delete(v);
  statuses.delete(v);
  // Only on Vega: an LG TV plays both itself, as it always has.
  const vega = isVega();
  if (vega && isHls(url) && !v.canPlayType("application/vnd.apple.mpegurl") && Hls.isSupported()) {
    v.removeAttribute("src");
    const hls = new Hls({ enableWorker: true, backBufferLength: 30, loader: ShellLoader as unknown as typeof Hls.DefaultConfig.loader });
    let recovered = false;
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (!data.fatal) return;
      // A decode hiccup gets one try at carrying on, as hls.js suggests; the network, and
      // anything after that, is the player's to handle with a fresh stream.
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) {
        recovered = true;
        hls.recoverMediaError();
        return;
      }
      fail(v, data.type === Hls.ErrorTypes.NETWORK_ERROR ? "network" : "media", data.response?.code);
    });
    hls.loadSource(url);
    hls.attachMedia(v);
    attached.set(v, hls);
    return;
  }
  if (vega && isTs(url) && !v.canPlayType("video/mp2t") && mpegts.isSupported()) {
    v.removeAttribute("src");
    // A channel is live; a programme from its archive (catch-up, Start over) is recorded,
    // and skipping about in it needs mpegts.js to know.
    const live = !/\/timeshift\//.test(url);
    const player = mpegts.createPlayer({ type: "mpegts", isLive: live, url }, { enableWorker: true, lazyLoad: false, liveBufferLatencyChasing: false });
    player.on(mpegts.Events.ERROR, (type: string, _detail: string, info?: { code?: number }) =>
      fail(v, type === mpegts.ErrorTypes.NETWORK_ERROR ? "network" : "media", info?.code));
    player.attachMediaElement(v);
    player.load();
    attached.set(v, { destroy: () => { player.pause(); player.unload(); player.detachMediaElement(); player.destroy(); } });
    return;
  }
  v.src = url;
  v.load();
}

/** Nothing playing in [v]: whatever is attached stopped, and the TV's player let go. */
export function clearSource(v: HTMLVideoElement) {
  detach(v);
  failures.delete(v);
  statuses.delete(v);
  v.removeAttribute("src");
  v.load();
}

type Callbacks = LoaderCallbacks<LoaderContext>;
// hls.js's own loader, to build on: its type is a constructor of an interface, not a class.
const Base = Hls.DefaultConfig.loader as unknown as new (config: unknown) => {
  stats: LoaderStats;
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: Callbacks): void;
};

/*
 * hls.js's own loader, with the Vega shell behind it: a provider's server that won't
 * answer the page (it doesn't allow other sites) is asked by the shell instead, playlists
 * and pieces of video alike, and from then on for that server. Servers that answer the
 * page are asked directly, as before.
 */
class ShellLoader extends Base {
  load(context: LoaderContext, config: LoaderConfiguration, callbacks: Callbacks) {
    if (askedByShell(context.url)) return this.viaShell(context, callbacks);
    super.load(context, config, {
      ...callbacks,
      onError: (error, ctx, details, stats) => {
        // Status 0: nothing came back the page could see, which is how a refusal looks.
        if (error.code !== 0) return callbacks.onError(error, ctx, details, stats);
        askViaShell(context.url);
        this.viaShell(context, callbacks);
      },
    });
  }

  private viaShell(context: LoaderContext, callbacks: Callbacks) {
    const stats = this.stats;
    const binary = context.responseType === "arraybuffer";
    const headers: Record<string, string> = {};
    if (context.rangeEnd) headers.Range = `bytes=${context.rangeStart ?? 0}-${context.rangeEnd - 1}`;
    stats.loading.start = stats.loading.start || performance.now();
    shellFetch(context.url, { headers }, binary)
      .then(async (response) => {
        if (!response.ok) {
          callbacks.onError({ code: response.status, text: response.statusText }, context, null, stats);
          return;
        }
        const data = binary ? await response.arrayBuffer() : await response.text();
        const now = performance.now();
        stats.loading.first = stats.loading.first || now;
        stats.loading.end = now;
        stats.loaded = stats.total = typeof data === "string" ? data.length : data.byteLength;
        callbacks.onSuccess({ url: context.url, data }, stats, context, null);
      })
      .catch(() => callbacks.onError({ code: 0, text: "Couldn't connect" }, context, null, stats));
  }
}
