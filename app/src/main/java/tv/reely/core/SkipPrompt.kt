package tv.reely.core

/** What the skip prompt is offering, or nothing when there is nothing under the playhead. */
enum class SkipPrompt { INTRO, NEXT_EPISODE }

/**
 * Which prompt, if any, belongs on screen at this point in an episode.
 *
 * The half second off the end of the intro is deliberate: without it the button vanishes
 * under a thumb already on its way to OK, and the press lands on whatever took its place.
 *
 * Credits only offer the next episode when there is one to go to and nothing else is
 * already offering it — the Up Next screen counts, and two ways to do the same thing a few
 * pixels apart is worse than one.
 */
fun skipPromptAt(
    positionMs: Long,
    introStartMs: Long?,
    introEndMs: Long?,
    creditsStartMs: Long?,
    canSkipForward: Boolean,
    upNextShowing: Boolean,
): SkipPrompt? {
    if (introStartMs != null && introEndMs != null &&
        positionMs >= introStartMs && positionMs < introEndMs - INTRO_GRACE_MS
    ) {
        return SkipPrompt.INTRO
    }
    if (creditsStartMs != null && positionMs >= creditsStartMs && canSkipForward && !upNextShowing) {
        return SkipPrompt.NEXT_EPISODE
    }
    return null
}

/** How long before the end of the intro the button stops being offered. */
const val INTRO_GRACE_MS = 500L
