import type { PlexItem } from "../api/plex";
import { stableSort } from "./sort";

/*
 * Which search results are about what was typed, best first; as SearchMatch.kt. A match
 * has the words in its name; case, accents and punctuation don't count.
 */

export function words(text: string): string[] {
  return text
    .toLowerCase()
    .normalize("NFD")
    .replace(/\p{Mn}+/gu, "")
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
}

export function relevant<T extends Pick<PlexItem, "title" | "grandparentTitle"> & { titleSort?: string | null }>(query: string, items: T[]): T[] {
  const wanted = words(query);
  if (!wanted.length) return [];
  const joined = wanted.join("");
  const ranked = items
    .map((item) => ({ item, rank: rank(item, wanted, joined) }))
    .filter((x): x is { item: T; rank: number } => x.rank !== null);
  return stableSort(ranked, (a, b) => a.rank - b.rank).map((x) => x.item);
}

export function split<T extends PlexItem>(query: string, items: T[]): [T[], T[]] {
  const matches = relevant(query, items);
  const keys = new Set(matches.map((m) => (m.serverBase ?? "") + "|" + m.ratingKey));
  return [matches, items.filter((i) => !keys.has((i.serverBase ?? "") + "|" + i.ratingKey))];
}

function rank(item: { title: string; grandparentTitle?: string | null; titleSort?: string | null }, wanted: string[], joined: string): number | null {
  const ranks = [item.title, item.grandparentTitle, item.titleSort]
    .filter((n): n is string => !!n)
    .map((n) => rankName(words(n), wanted, joined))
    .filter((r): r is number => r !== null);
  return ranks.length ? Math.min(...ranks) : null;
}

function rankName(name: string[], wanted: string[], joined: string): number | null {
  if (!name.length) return null;
  const whole = name.join("");
  if (whole === joined) return 0;
  if (whole.startsWith(joined)) return 1;
  if (wanted.every((w) => name.some((n) => n.startsWith(w)))) return 2;
  let offset = 0;
  for (const w of name) {
    if (whole.startsWith(joined, offset)) return 3;
    offset += w.length;
  }
  return null;
}

/** Recent searches with [query] first, once. */
export function rememberedSearches(recent: string[], query: string, limit = 8): string[] {
  const text = query.trim().replace(/\s+/g, " ");
  if (!text) return recent;
  return [text, ...recent.filter((r) => r.toLowerCase() !== text.toLowerCase())].slice(0, limit);
}

/**
 * Every word typed is in [name], in any order: "hanks" or "tom hanks" names Tom Hanks.
 * What decides whether people lead a search or come last.
 */
export function namesAll(name: string, query: string): boolean {
  const typed = query.toLowerCase().split(/\s+/).filter(Boolean);
  const whole = name.toLowerCase();
  return typed.length > 0 && typed.every((w) => whole.includes(w));
}
