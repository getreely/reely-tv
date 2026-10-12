/*
 * URL and URLSearchParams in full (React Native's own stop short: no searchParams, no
 * host or pathname), from the WHATWG reference implementation in plain JavaScript.
 *
 * This replaces react-native-url-polyfill, which reads NativeModules.BlobModule as it
 * loads. React Native 0.83 on Vega has no old bridge to read it from, so that read
 * aborted the app before its first frame.
 *
 * URL.createObjectURL names Amazon's MediaSource as Amazon's player reads it back
 * (blob:///<id>?offset=0&size=0, its MediaPlayer.setSrcWithCustomHeaders): hls.js hands the
 * player its stream that way, and without it no stream starts. Anything else goes to the
 * runtime's own.
 */
// @ts-expect-error The library ships no types; the shapes are declared in env.d.ts.
import { URL as WhatwgURL, URLSearchParams as WhatwgURLSearchParams } from 'whatwg-url-without-unicode';

type ObjectUrls = { createObjectURL?: (o: unknown) => string; revokeObjectURL?: (url: string) => void };
const g = globalThis as unknown as { URL: ObjectUrls | undefined; URLSearchParams: unknown };
const runtime = g.URL;
const Full = WhatwgURL as ObjectUrls;

/** Amazon's MediaSource: it carries its number where a Blob carries its id. */
function mediaSourceId(o: unknown): number | null {
  const m = o as { addSourceBuffer?: unknown; data?: { blobId?: unknown } } | null;
  return m && typeof m.addSourceBuffer === 'function' && typeof m.data?.blobId === 'number' ? m.data.blobId : null;
}

Full.createObjectURL = (o: unknown) => {
  const id = mediaSourceId(o);
  if (id != null) return `blob:///${id}?offset=0&size=0`;
  if (!runtime?.createObjectURL) throw new Error('Cannot create URL for blob!');
  return runtime.createObjectURL(o);
};
Full.revokeObjectURL = (url: string) => {
  if (url.startsWith('blob:///')) return;
  runtime?.revokeObjectURL?.(url);
};
g.URL = Full;
g.URLSearchParams = WhatwgURLSearchParams;
