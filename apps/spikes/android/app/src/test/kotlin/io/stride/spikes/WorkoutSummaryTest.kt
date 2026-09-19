package io.stride.spikes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the "workout complete" card belongs on screen.
 *
 * There was previously no card at all: "End workout" returned straight to "Start workout" with
 * nothing shown in between, so a rider who pressed it never saw anything confirming a workout had
 * actually just finished. [shouldShowWorkoutSummary] is the decision [OverlayService.workoutListener]
 * acts on; pinned here, pure, so every branch is checkable without a Service.
 */
class WorkoutSummaryTest {

    /** The feature, stated directly: ending a workout shows the card. */
    @Test
    fun `a genuine end shows the summary`() {
        assertTrue(
            shouldShowWorkoutSummary(
                state = WorkoutSession.State.STOPPING,
                ending = WorkoutSession.Ending.ENDED,
                stopEscalationActive = false,
            ),
        )
    }

    /**
     * The safety-key warning always wins.
     *
     * [OverlayService.showFixIt] tears down whatever card is already up before drawing a new one, so
     * showing the summary here would silently dismiss a warning telling the rider the belt's state is
     * still unconfirmed — the one card on this overlay that must never be swapped out from under them.
     */
    @Test
    fun `an unconfirmed stop withholds the summary`() {
        assertFalse(
            shouldShowWorkoutSummary(
                state = WorkoutSession.State.STOPPING,
                ending = WorkoutSession.Ending.ENDED,
                stopEscalationActive = true,
            ),
        )
    }

    /** An abandoned start was never a workout, so there is nothing to summarise. */
    @Test
    fun `an abandoned start shows nothing`() {
        assertFalse(
            shouldShowWorkoutSummary(
                state = WorkoutSession.State.IDLE,
                ending = WorkoutSession.Ending.ABANDONED,
                stopEscalationActive = false,
            ),
        )
    }

    /** The settle that follows a stop is the confirmation arriving, not a second ending. */
    @Test
    fun `the settle into idle after a stop shows nothing`() {
        assertFalse(
            shouldShowWorkoutSummary(
                state = WorkoutSession.State.IDLE,
                ending = WorkoutSession.Ending.ENDED,
                stopEscalationActive = false,
            ),
        )
    }

    /** No live transition — start, pause, resume — is an ending. */
    @Test
    fun `no live transition shows the summary`() {
        val live = listOf(
            WorkoutSession.State.STARTING,
            WorkoutSession.State.RUNNING,
            WorkoutSession.State.PAUSED,
        )
        for (state in live) {
            assertFalse(
                "$state must not show the summary",
                shouldShowWorkoutSummary(state, ending = null, stopEscalationActive = false),
            )
        }
    }
}
