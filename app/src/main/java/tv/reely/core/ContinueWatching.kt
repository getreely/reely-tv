package tv.reely.core

import tv.reely.plex.PlexItem

/**
 * Continue Watching in the order Plex's own apps show it: most recently watched first.
 *
 * This used to sort by when each item was added to the library, which put a film added
 * last week ahead of the show watched an hour ago — the row looked random because it was
 * answering a different question.
 *
 * Two rules, taken from a working Plex client rather than guessed at:
 *
 * One entry per show. The two hubs this is built from overlap, and a show can appear as
 * both the episode part-way through and the next one up. The part-watched one wins,
 * because that is the one "continue" means.
 *
 * Newest activity first, by `lastViewedAt`. Something never played — the next episode of
 * a show, say — has none, and falls back to when it was added, so it still lands
 * somewhere sensible rather than sinking to the end. The sort is stable, so ties keep the
 * order the server gave them.
 */
fun continueWatchingOrder(items: List<PlexItem>): List<PlexItem> {
    val kept = mutableListOf<PlexItem>()
    val showIndex = mutableMapOf<Pair<String?, String>, Int>()
    for (item in items) {
        val show = item.grandparentRatingKey
        if (item.type != "episode" || show == null) {
            if (kept.none { it.serverBase == item.serverBase && it.ratingKey == item.ratingKey }) {
                kept += item
            }
            continue
        }
        // Rating keys are only unique within one server.
        val key = item.serverBase to show
        val at = showIndex[key]
        if (at == null) {
            showIndex[key] = kept.size
            kept += item
        } else if (kept[at].viewOffsetMs <= 0 && item.viewOffsetMs > 0) {
            kept[at] = item
        }
    }
    return kept.sortedByDescending(::recencyOf)
}

/** When this was last touched, as far as can be told. See [continueWatchingOrder]. */
fun recencyOf(item: PlexItem): Long = item.lastViewedAt.takeIf { it > 0 } ?: item.addedAt
