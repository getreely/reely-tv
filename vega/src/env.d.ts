// What Hermes has that React Native's types don't say.
declare function atob(data: string): string;
declare function btoa(data: string): string;

// URL and URLSearchParams in full, as react-native-url-polyfill (loaded first, in index.js)
// makes them: React Native's own types stop short.
interface URL {
  readonly origin: string;
  protocol: string;
  host: string;
  hostname: string;
  port: string;
  pathname: string;
  search: string;
  hash: string;
  readonly searchParams: URLSearchParams;
}
interface URLSearchParams {
  get(name: string): string | null;
  set(name: string, value: string): void;
  has(name: string): boolean;
  delete(name: string): void;
  forEach(callback: (value: string, key: string) => void): void;
}

// Amazon's build of hls.js (scripts/hlsjs.sh), and its polyfills: built, not kept here.
declare module '*/hls.mjs' {
  const lib: unknown;
  export = lib;
}
declare module '*/hls/polyfills/DocumentPolyfill' { const P: { install(): void }; export default P; }
declare module '*/hls/polyfills/ElementPolyfill' { const P: { install(): void }; export default P; }
declare module '*/hls/polyfills/MiscPolyfill' { const P: { install(): void }; export default P; }
declare module '*/hls/polyfills/TextDecoderPolyfill' { const P: { install(): void }; export default P; }
declare module '*/hls/polyfills/W3CMediaPolyfill' { const P: { install(): void }; export default P; }
