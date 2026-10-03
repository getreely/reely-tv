import type { PlexItem } from "../api/plex";

/**
 * Continue Watching as Plex's own apps order it (ContinueWatching.kt): one entry per
 * show, the part-watched episode winning, most recently watched first.
 */
export function continueWatchingOrder(items: PlexItem[]): PlexItem[] {
  const kept: PlexItem[] = [];
  const showIndex = new Map<string, number>();
  for (const item of items) {
    const show = item.grandparentRatingKey;
    if (item.type !== "episode" || !show) {
      if (!kept.some((k) => k.serverBase === item.serverBase && k.ratingKey === item.ratingKey)) kept.push(item);
      continue;
    }
    const key = `${item.serverBase}|${show}`;
    const at = showIndex.get(key);
    if (at === undefined) {
      showIndex.set(key, kept.length);
      kept.push(item);
    } else if (kept[at].viewOffsetMs <= 0 && item.viewOffsetMs > 0) {
      kept[at] = item;
    }
  }
  return kept
    .map((item, order) => ({ item, order }))
    .sort((a, b) => recencyOf(b.item) - recencyOf(a.item) || a.order - b.order)
    .map((x) => x.item);
}

export const recencyOf = (item: PlexItem) => (item.lastViewedAt > 0 ? item.lastViewedAt : item.addedAt);
