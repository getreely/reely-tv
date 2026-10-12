import type { KeyValue } from "../api/vod";

/** The Web Storage calls the app's Store uses: memory here, with persist.ts writing it to the stick. */
export interface Storage {
  readonly length: number;
  clear(): void;
  getItem(key: string): string | null;
  key(index: number): string | null;
  removeItem(key: string): void;
  setItem(key: string, value: string): void;
}

/**
 * What the app keeps on the stick: sign-ins, settings, where IPTV titles were left. Read
 * and written at once, from memory; what's kept on the stick is loaded into it at start
 * (persist.ts) and written behind. Reads and writes that fail are no worse than nothing kept.
 */
export class Store implements KeyValue {
  constructor(private prefix = "reely:", private backing: Storage | null = new MemoryStorage()) {}

  get(key: string): string | null {
    try {
      return this.backing?.getItem(this.prefix + key) ?? null;
    } catch {
      return null;
    }
  }

  set(key: string, value: string): void {
    try {
      this.backing?.setItem(this.prefix + key, value);
    } catch {
      /* full: kept for this run only */
    }
  }

  remove(key: string): void {
    try {
      this.backing?.removeItem(this.prefix + key);
    } catch {
      /* nothing to do */
    }
  }

  json<T>(key: string, fallback: T): T {
    const raw = this.get(key);
    if (raw == null) return fallback;
    try {
      return JSON.parse(raw) as T;
    } catch {
      return fallback;
    }
  }

  setJson(key: string, value: unknown): void {
    this.set(key, JSON.stringify(value));
  }
}

/** A Storage held in memory: for tests, and what persist.ts fills from the stick. */
export class MemoryStorage implements Storage {
  private values = new Map<string, string>();
  get length() {
    return this.values.size;
  }
  clear() {
    this.values.clear();
  }
  getItem(key: string) {
    return this.values.has(key) ? this.values.get(key)! : null;
  }
  key(index: number) {
    return Array.from(this.values.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
  setItem(key: string, value: string) {
    this.values.set(key, String(value));
  }
}

/** This stick's own id for Plex, made once and kept. */
export function clientId(store: Store): string {
  let id = store.get("clientId");
  if (!id) {
    id = "reely-vega-" + randomHex(16);
    store.set("clientId", id);
  }
  return id;
}

export function randomHex(bytes: number): string {
  const out: string[] = [];
  const webCrypto = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }).crypto;
  const random = webCrypto?.getRandomValues ? webCrypto.getRandomValues(new Uint8Array(bytes)) : null;
  for (let i = 0; i < bytes; i++) out.push((random ? random[i] : Math.floor(Math.random() * 256)).toString(16).padStart(2, "0"));
  return out.join("");
}
