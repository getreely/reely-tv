package tv.reely.core

/** Wraps an index into `0 until size`, in both directions. Returns 0 for an empty list. */
fun wrapIndex(value: Int, size: Int): Int =
    if (size <= 0) 0 else ((value % size) + size) % size

/**
 * Steps through a list that has one extra "off" position at -1, wrapping at both ends.
 * Used for subtitle tracks, where off is a real choice and not an absence of one.
 */
fun cycleWithOff(requested: Int, count: Int): Int {
    if (count <= 0) return -1
    val span = count + 1
    return ((((requested + 1) % span) + span) % span) - 1
}
