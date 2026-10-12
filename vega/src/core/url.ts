/*
 * URL and URLSearchParams in full (React Native's own stop short: no searchParams, no
 * host or pathname), from the WHATWG reference implementation in plain JavaScript.
 *
 * This replaces react-native-url-polyfill, which reads NativeModules.BlobModule as it
 * loads. React Native 0.83 on Vega has no old bridge to read it from, so that read
 * aborted the app before its first frame.
 */
// @ts-expect-error The library ships no types; the shapes are declared in env.d.ts.
import { URL as WhatwgURL, URLSearchParams as WhatwgURLSearchParams } from 'whatwg-url-without-unicode';

const g = globalThis as unknown as { URL: unknown; URLSearchParams: unknown };
g.URL = WhatwgURL;
g.URLSearchParams = WhatwgURLSearchParams;
