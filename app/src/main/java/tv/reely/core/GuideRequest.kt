package tv.reely.core

/**
 * Why the guide is up, and what choosing a channel in it will do.
 *
 * These were three independent flags on the player, set by hand at five places that
 * raised the guide and cleared at two of the places that took it away. A guide opened to
 * replace a tile and then left by pressing up kept pointing at that tile, so the next
 * channel chosen — from the spare cell, meaning "add a fourth" — was routed to a replace
 * of a slot that did not need one, and nothing happened at all.
 *
 * Holding the three together means opening the guide has to say what it is for, and
 * closing it cannot half-forget.
 */
data class GuideRequest(
    val open: Boolean = false,
    /** True when a channel is going beside what is playing rather than replacing it. */
    val adds: Boolean = false,
    /** The tile a chosen channel goes into, when the guide was opened to retune one. */
    val replaces: Int? = null,
) {
    /** What to call the thing OK is about to do, said plainly. */
    val verb: String get() = if (replaces != null) "Replace with" else "Add"

    companion object {
        val Closed = GuideRequest()

        /** Just looking: OK changes channel. */
        fun browse(hasChannels: Boolean) = GuideRequest(open = hasChannels)

        /** OK puts the channel in a new tile beside the one playing. */
        fun add(hasChannels: Boolean) = GuideRequest(open = hasChannels, adds = true)

        /** OK puts the channel into the tile already there. */
        fun replace(slot: Int, hasChannels: Boolean) =
            GuideRequest(open = hasChannels, adds = true, replaces = slot)
    }
}
