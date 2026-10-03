import type { KeyValue } from "../api/vod";

/**
 * What the app keeps on the TV: sign-ins, settings, where IPTV titles were left. The
 * browser's localStorage, which a webOS app keeps across restarts; reads and writes that
 * fail (full, or switched off) are no worse than nothing kept.
 */
export class Store implements KeyValue {
  constructor(private prefix = "reely:", private backing: Storage | null = safeLocalStorage()) {}

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

function safeLocalStorage(): Storage | null {
  try {
    return typeof localStorage !== "undefined" ? localStorage : null;
  } catch {
    return null;
  }
}

/** A Storage held in memory: for tests, and a browser that won't keep anything. */
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

/** This TV's own id for Plex, made once and kept. */
export function clientId(store: Store): string {
  let id = store.get("clientId");
  if (!id) {
    id = "reely-lg-" + randomHex(16);
    store.set("clientId", id);
  }
  return id;
}

export function randomHex(bytes: number): string {
  const out: string[] = [];
  const random = typeof crypto !== "undefined" && crypto.getRandomValues ? crypto.getRandomValues(new Uint8Array(bytes)) : null;
  for (let i = 0; i < bytes; i++) out.push((random ? random[i] : Math.floor(Math.random() * 256)).toString(16).padStart(2, "0"));
  return out.join("");
}
