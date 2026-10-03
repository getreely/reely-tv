import type { PlexItem } from "../../src/api/plex";

/** A Plex item with everything empty but what a test sets. */
export function item(fields: Partial<PlexItem> & { ratingKey: string }): PlexItem {
  return {
    title: fields.ratingKey,
    type: "movie",
    thumb: null,
    art: null,
    summary: null,
    year: null,
    index: null,
    parentIndex: null,
    parentRatingKey: null,
    parentTitle: null,
    grandparentRatingKey: null,
    grandparentTitle: null,
    grandparentThumb: null,
    durationMs: 1_000_000,
    viewOffsetMs: 0,
    leafCount: 0,
    viewedLeafCount: 0,
    viewCount: 0,
    addedAt: 0,
    lastViewedAt: 0,
    qualities: [],
    librarySectionId: null,
    serverBase: "http://a",
    ...fields,
  };
}
