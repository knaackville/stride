package io.stride.spikes

import kotlin.math.floor

/**
 * Turns the machine's cumulative distance reading into a position around one lap of the track.
 *
 * This exists as its own object, away from the overlay, for two reasons. It is the piece the track
 * floor is actually *about* — where the rider is on the loop — and it is pure arithmetic over a
 * reading that can go missing, which is exactly the shape of thing that should be tested on the JVM
 * rather than by squinting at a treadmill.
 *
 * ### Why a reading that goes missing is the interesting case
 *
 * [MachineLink] hands out `null` for anything older than its freshness window, so a single dropped
 * poll turns a perfectly good distance into "unknown" for a second. Reading that as *zero* — which
 * is what the first version of the track floor did — teleports the marker back to the start line
 * and then back out again, several times a workout, and makes the whole surface look broken.
 *
 * Holding the last position through a short gap is not a fabricated reading: the claim is only that
 * the rider was last seen there, which is true, and the marker is not moving while we make it. Past
 * [holdMs] the gap stops being a dropped poll and starts being a machine we are no longer talking
 * to, and the answer becomes null so the caller can draw an empty track rather than a stale one.
 *
 * ### The distance register is not fine-grained enough to animate directly
 *
 * On the X22i's direct link `CURRENT_DISTANCE` is integer metres (`docs/DIRECT_MACHINE_PROTOCOL.md`
 * §"Ending a workout writes zero twice"), so between two polls the register often reports either no
 * change at all or a whole extra metre, never the fraction a steady pace actually covered in that
 * gap. Pacing the marker's animation off the *timing* of those ticks — the previous fix here —
 * removes the jitter that comes from the host's own poll cadence sometimes slowing down, but it
 * cannot remove jitter that is already baked into the register's own quantisation: two consecutive
 * gaps of equal real duration can still imply different paces purely because one crossed a
 * whole-metre boundary and the other did not, and a rider running at a constant speed watches the
 * marker speed up and slow down with it.
 *
 * [positionAt] is the fix: it extrapolates forward from the last confirmed distance using the speed
 * the machine reported *alongside* it, which is a continuous reading rather than a quantised
 * counter, and is what [MachineLink.speedMph] already treats as safe to put in front of a rider for
 * exactly this "current pace" purpose (see the protocol doc's "the metric strip and its pace"). This
 * does not contradict distance never being integrated from speed as the *source of truth*: the
 * register is still what [sample] records as the position at the moment it was read, every single
 * poll, and [positionAt] only fills the gap *between* two such confirmations — for however long the
 * next poll takes, a few hundred milliseconds to a couple of seconds — never further, and never
 * without one behind it. A workout's overall distance is never accumulated by adding up speed here;
 * only the sliver between one confirmed reading and the next is.
 */
class LapTracker(
    private val lapMiles: Double,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {

    companion object {
        /**
         * How long a known position survives without a fresh distance reading.
         *
         * Comfortably longer than [MachineLink]'s freshness window plus a poll, so an ordinary
         * hiccup is invisible, and short enough that a machine that has actually gone away clears
         * the marker while the rider is still looking at the screen.
         */
        const val DEFAULT_HOLD_MS = 12_000L

        private const val MILES_PER_MPH_MS = 1.0 / 3_600_000.0
    }

    /** Where the rider is: [progress] around the current lap, and which [lap] that is. */
    data class Position(val progress: Float, val lap: Int)

    private var held: Position? = null
    private var heldAtMs: Long = 0L

    // The last confirmed distance reading, when [sample] recorded it, and the speed the machine
    // reported alongside it — what [positionAt] extrapolates forward from. Speed defaults to zero
    // rather than null: a caller that does not pass one is asking for a position that never moves
    // between confirmations, which is the honest answer when there is nothing to extrapolate with.
    private var anchorDistanceMiles: Double? = null
    private var anchorAtMs: Long = 0L
    private var anchorSpeedMph: Double = 0.0

    /**
     * The position implied by [distanceMiles], or the last one if the reading is briefly missing,
     * or null when there is nothing honest to draw.
     *
     * [speedMph] is recorded alongside the distance for [positionAt] to extrapolate from; it plays
     * no part in the position returned *here*, which is always read straight off [distanceMiles].
     */
    fun sample(distanceMiles: Double?, nowMs: Long, speedMph: Double? = null): Position? {
        if (lapMiles > 0.0 && distanceMiles != null && distanceMiles >= 0.0 && distanceMiles.isFinite()) {
            // Never let a fresh confirmation rewind what was already being shown. The only way this
            // can happen is [positionAt] having extrapolated a little past what the register goes on
            // to confirm — the speed reading overestimated the gap, or the register's own whole-metre
            // rounding lags a stride behind — and in that case the rider did not go backward, the
            // estimate was just a step ahead of the confirmation. See the class doc.
            val previousEstimate = anchorDistanceMiles?.let { it + extrapolatedMiles(nowMs) }
            val effective = if (previousEstimate != null && previousEstimate > distanceMiles) {
                previousEstimate
            } else {
                distanceMiles
            }
            val position = positionFor(effective)
            held = position
            heldAtMs = nowMs
            anchorDistanceMiles = effective
            anchorAtMs = nowMs
            anchorSpeedMph = speedMph?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            return position
        }
        val last = held ?: return null
        if (nowMs - heldAtMs in 0..holdMs) return last
        held = null
        return null
    }

    /**
     * Where the rider is *right now*, extrapolating forward from the last confirmed reading at the
     * speed the machine reported alongside it — see the class doc for why this exists and what it
     * is not. Cheap enough to call every animation frame: no allocation, a handful of arithmetic
     * operations, no trigonometry.
     *
     * Subject to the same [holdMs] expiry as [sample]'s own held value, measured from the same
     * anchor: a machine that has gone quiet does not get to keep extrapolating off its last known
     * speed forever.
     */
    fun positionAt(nowMs: Long): Position? {
        val distance = anchorDistanceMiles ?: return null
        val elapsed = nowMs - anchorAtMs
        if (elapsed !in 0..holdMs) return null
        return positionFor(distance + extrapolatedMiles(nowMs))
    }

    /** Forget the held position. Called when the session changes and the old one means nothing. */
    fun reset() {
        held = null
        heldAtMs = 0L
        anchorDistanceMiles = null
        anchorAtMs = 0L
        anchorSpeedMph = 0.0
    }

    /** Ground covered since the anchor was set, at the anchor's own recorded speed. */
    private fun extrapolatedMiles(nowMs: Long): Double =
        anchorSpeedMph * (nowMs - anchorAtMs).coerceAtLeast(0L) * MILES_PER_MPH_MS

    private fun positionFor(distanceMiles: Double): Position {
        val laps = distanceMiles / lapMiles
        val completed = floor(laps)
        // A rider standing exactly on a lap boundary is at the *start* of the next lap, not the
        // end of the one behind them, which is why the lap number is the count plus one.
        return Position(
            progress = (laps - completed).toFloat().coerceIn(0f, 1f),
            lap = (completed.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
