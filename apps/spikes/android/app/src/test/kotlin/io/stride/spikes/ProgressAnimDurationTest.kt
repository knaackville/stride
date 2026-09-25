package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pacing behind [TrackFloorView]'s marker animation, pulled out as a pure function because
 * [TrackFloorView] is a live [android.view.View] and cannot be instantiated here to test it — see
 * [progressAnimDurationMs]'s own doc for what this guards against: a hold-then-burst "pulse" when
 * the host's poll of the distance register slows down for a beat and then catches up.
 */
class ProgressAnimDurationTest {

    @Test
    fun `an ordinary gap is used as-is`() {
        // Both the host's fast poll (500ms, while the belt is confidently moving) and its slow one
        // (2000ms, whenever the console's state string briefly is not) fall inside the clamp, so
        // neither one gets stretched or squashed — which is the whole point: whatever the real gap
        // was, the marker should move at the pace that gap implies.
        assertEquals(500L, progressAnimDurationMs(500L))
        assertEquals(1000L, progressAnimDurationMs(1000L))
        assertEquals(2000L, progressAnimDurationMs(2000L))
    }

    @Test
    fun `a very short gap is floored so motion never flickers`() {
        assertEquals(MIN_PROGRESS_ANIM_MS, progressAnimDurationMs(0L))
        assertEquals(MIN_PROGRESS_ANIM_MS, progressAnimDurationMs(1L))
        assertEquals(MIN_PROGRESS_ANIM_MS, progressAnimDurationMs(MIN_PROGRESS_ANIM_MS - 1))
    }

    @Test
    fun `a very long gap is capped so a stale marker does not crawl`() {
        // A gap this long is already deep in LapTracker's own hold window; this ceiling exists for
        // the much more ordinary case of two slow polls landing back to back, not for that one.
        assertEquals(MAX_PROGRESS_ANIM_MS, progressAnimDurationMs(MAX_PROGRESS_ANIM_MS + 1))
        assertEquals(MAX_PROGRESS_ANIM_MS, progressAnimDurationMs(60_000L))
    }

    @Test
    fun `the bounds themselves are returned unchanged`() {
        assertEquals(MIN_PROGRESS_ANIM_MS, progressAnimDurationMs(MIN_PROGRESS_ANIM_MS))
        assertEquals(MAX_PROGRESS_ANIM_MS, progressAnimDurationMs(MAX_PROGRESS_ANIM_MS))
    }
}
