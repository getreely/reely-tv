package tv.reely.core

import tv.reely.plex.PlexItem
import java.text.Normalizer

/**
 * Which of a server's search results are really about what was typed, best first.
 *
 * Plex's search is loose on purpose: it matches words in descriptions, near spellings,
 * genres and titles related to the ones that match. Typing "nfl" put films and episodes
 * with nothing to do with football alongside the ones that were. Here a result is a match
 * when it has what was typed in its name: the film's title, the show's name or the
 * episode's own. Case, accents and punctuation don't count, so "spiderman" finds
 * "Spider-Man" and "amelie" finds "Amélie".
 *
 * The rest aren't thrown away. Plex's looseness is also what finds "interstelar" when it
 * was spelled wrong, so they follow the matches as other results, and when nothing matches
 * by name they are the results.
 */
object SearchMatch {

    /** The matches by name, best first, and everything else the server offered, in its order. */
    fun split(query: String, items: List<PlexItem>): Pair<List<PlexItem>, List<PlexItem>> {
        val matches = relevant(query, items)
        val keys = matches.map { it.listKey }.toSet()
        return matches to items.filter { it.listKey !in keys }
    }

    fun relevant(query: String, items: List<PlexItem>): List<PlexItem> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        val joined = wanted.joinToString("")
        return items
            .mapNotNull { item -> rank(item, wanted, joined)?.let { item to it } }
            // Stable: within a rank, the server's own order stands.
            .sortedBy { it.second }
            .map { it.first }
    }

    /** 0 an exact name, 1 a name starting with it, 2 every word starting a word, 3 from a later word. */
    private fun rank(item: PlexItem, wanted: List<String>, joined: String): Int? =
        listOfNotNull(item.title, item.grandparentTitle, item.titleSort)
            .mapNotNull { name -> rankName(words(name), wanted, joined) }
            .minOrNull()

    private fun rankName(name: List<String>, wanted: List<String>, joined: String): Int? {
        if (name.isEmpty()) return null
        val whole = name.joinToString("")
        return when {
            whole == joined -> 0
            whole.startsWith(joined) -> 1
            wanted.all { word -> name.any { it.startsWith(word) } } -> 2
            startsAtAWord(name, joined) -> 3
            else -> null
        }
    }

    /**
     * What was typed, run together, starting at the start of one of the name's words:
     * "ofsteel" in "Man of Steel". Not from the middle of a word, which is how "nfl" was
     * finding "Inflation".
     */
    private fun startsAtAWord(name: List<String>, joined: String): Boolean {
        val whole = name.joinToString("")
        var offset = 0
        for (word in name) {
            if (whole.startsWith(joined, offset)) return true
            offset += word.length
        }
        return false
    }

    /** Lower case, accents off, split on anything that isn't a letter or a digit. */
    internal fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
}
