/*
 * URL and URLSearchParams in full (React Native's own stop short: no searchParams, no
 * host or pathname), from the WHATWG reference implementation in plain JavaScript.
 *
 * This replaces react-native-url-polyfill, which reads NativeModules.BlobModule as it
 * loads. React Native 0.83 on Vega has no old bridge to read it from, so that read
 * aborted the app before its first frame.
 *
 * URL.createObjectURL stays the runtime's own: Amazon's player gives hls.js a MediaSource
 * that only it knows how to name (blob:…), and without it no stream starts.
 */
// @ts-expect-error The library ships no types; the shapes are declared in env.d.ts.
import { URL as WhatwgURL, URLSearchParams as WhatwgURLSearchParams } from 'whatwg-url-without-unicode';

type ObjectUrls = { createObjectURL?: (o: unknown) => string; revokeObjectURL?: (url: string) => void };
const g = globalThis as unknown as { URL: ObjectUrls | undefined; URLSearchParams: unknown };
const runtime = g.URL;
const Full = WhatwgURL as ObjectUrls;
Full.createObjectURL = (o: unknown) => {
  if (!runtime?.createObjectURL) throw new Error('Cannot create URL for blob!');
  return runtime.createObjectURL(o);
};
Full.revokeObjectURL = (url: string) => runtime?.revokeObjectURL?.(url);
g.URL = Full;
g.URLSearchParams = WhatwgURLSearchParams;
