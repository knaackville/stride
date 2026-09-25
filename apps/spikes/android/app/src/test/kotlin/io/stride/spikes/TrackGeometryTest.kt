package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The track has to fill whatever box the overlay hands it and has to read as a track. Both are
 * geometry, so both can be checked here rather than by squinting at a treadmill.
 */
class TrackGeometryTest {

    private val padX = 8f
    private val padY = 6f

    private fun fitted(w: Float, h: Float) = TrackGeometry().also {
        assertTrue("fit($w, $h) should succeed", it.fit(w, h, padX, padY))
    }

    private class Bounds {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun add(x: Float, y: Float) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }

    private fun outerBounds(g: TrackGeometry): Bounds {
        val b = Bounds()
        for (i in 0..720) {
            g.project(i / 720f, 1f)
            b.add(g.x, g.y)
        }
        return b
    }

    /** Everything that gets drawn: the lane outline and the marker standing on it. */
    private fun drawnBounds(g: TrackGeometry): Bounds {
        val b = outerBounds(g)
        for (i in 0..720) {
            val u = i / 720f
            val pin = g.laneWidthAt(u) * TrackGeometry.PIN_SCALE
            g.project(u, 0f)
            val half = pin * TrackGeometry.PIN_HEAD_RADIUS
            b.add(g.x - half, g.y - pin * (TrackGeometry.PIN_HEAD_OFFSET + TrackGeometry.PIN_HEAD_RADIUS))
            b.add(g.x + half, g.y + pin * TrackGeometry.PIN_SHADOW_DROP)
        }
        return b
    }

    @Test
    fun `track fills the box it is given`() {
        for ((w, h) in listOf(1596f to 762f, 800f to 800f, 1020f to 300f)) {
            val b = drawnBounds(fitted(w, h))
            assertEquals("left edge of $w x $h", padX, b.minX, 0.5f)
            assertEquals("right edge of $w x $h", w - padX, b.maxX, 0.5f)
            assertEquals("top edge of $w x $h", padY, b.minY, 0.5f)
            assertEquals("bottom edge of $w x $h", h - padY, b.maxY, 0.5f)
        }
    }

    @Test
    fun `the marker never leaves the box`() {
        // The marker stands up out of the lane by more than a lane width, so fitting the lane on
        // its own puts the marker's head above the top edge for about a sixth of every lap — worst
        // on the far straight, where the lane is already touching the top of the box. What has to
        // fit is everything that gets drawn.
        for ((w, h) in listOf(1596f to 762f, 800f to 800f, 1020f to 300f, 1564f to 742f)) {
            val g = fitted(w, h)
            for (i in 0..720) {
                val u = i / 720f
                val pin = g.laneWidthAt(u) * TrackGeometry.PIN_SCALE
                g.project(u, 0f)
                val top = g.y - pin * (TrackGeometry.PIN_HEAD_OFFSET + TrackGeometry.PIN_HEAD_RADIUS)
                val bottom = g.y + pin * TrackGeometry.PIN_SHADOW_DROP
                val half = pin * TrackGeometry.PIN_HEAD_RADIUS
                assertTrue("marker head clipped at the top at u=$u in $w x $h", top >= 0f)
                assertTrue("marker clipped at the bottom at u=$u in $w x $h", bottom <= h)
                assertTrue("marker clipped at the left at u=$u in $w x $h", g.x - half >= 0f)
                assertTrue("marker clipped at the right at u=$u in $w x $h", g.x + half <= w)
            }
        }
    }

    @Test
    fun `refitting forgets the previous size`() {
        // The fit transform has to be reset to the identity before the solve measures anything,
        // or a resized overlay lays the track out against the box it had a moment ago.
        val g = TrackGeometry()
        assertTrue(g.fit(1596f, 762f, padX, padY))
        assertTrue(g.fit(640f, 480f, padX, padY))
        val b = drawnBounds(g)
        assertEquals(padX, b.minX, 0.5f)
        assertEquals(640f - padX, b.maxX, 0.5f)
        assertEquals(padY, b.minY, 0.5f)
        assertEquals(480f - padY, b.maxY, 0.5f)
    }

    @Test
    fun `lane width varies with distance and nothing else`() {
        // The failure this guards against: building the inner edge by scaling the whole ellipse
        // down, which pinches the lane at the ends of the straights and fattens it at the sides,
        // so the shape stops reading as a track. With a constant-width lane the only thing that
        // can change the apparent width is depth, so width falls away from the near side of the
        // loop to the far side. The tolerance is for the measurement rather than the shape: the
        // two lane edges sit at slightly different depths, so the chord between them runs about a
        // percent long where the lane is most steeply angled to the camera.
        val g = fitted(1596f, 762f)
        var previous = Float.MAX_VALUE
        var u = 0.25f
        while (u <= 0.75f) {
            val width = g.laneWidthAt(u)
            assertTrue("lane should never vanish at u=$u", width > 1f)
            assertTrue("lane widened going away from the rider at u=$u", width <= previous * 1.02f)
            previous = width
            u += 1f / 288f
        }
    }

    @Test
    fun `the ends of the loop are wider than the far side`() {
        // Sharper version of the same guard. The ends of the straights sit at middle depth, so
        // they have to be drawn wider than the far side of the loop. Scaling an ellipse to make
        // the inner edge does the exact opposite: it pinches the ends to their narrowest.
        val g = fitted(1596f, 762f)
        assertTrue(g.laneWidthAt(0f) > g.laneWidthAt(0.75f) * 1.3f)
        assertTrue(g.laneWidthAt(0.5f) > g.laneWidthAt(0.75f) * 1.3f)
        assertTrue(g.laneWidthAt(0.25f) > g.laneWidthAt(0f) * 1.3f)
    }

    @Test
    fun `opposite ends of the straights are the same width`() {
        // Left and right extremes sit at the same depth, so they must project to the same width.
        val g = fitted(1596f, 762f)
        assertEquals(g.laneWidthAt(0f), g.laneWidthAt(0.5f), 0.01f)
    }

    @Test
    fun `near side of the loop is drawn larger than the far side`() {
        val g = fitted(1596f, 762f)
        assertTrue(
            "perspective should make the near straight wider than the far one",
            g.laneWidthAt(0.25f) > g.laneWidthAt(0.75f) * 1.5f,
        )
    }

    @Test
    fun `travel runs from the start line toward the rider`() {
        // Start at the left end of the loop, then come down the near straight before going away
        // again. The start line is not the leftmost *pixel*, because perspective swings the near
        // side of the loop wider than the ends; it is the left end of the ground ellipse, which
        // sits at the same depth as the halfway point.
        val g = fitted(1596f, 762f)
        g.project(0f, 0f)
        val startX = g.x
        val startY = g.y
        g.project(0.5f, 0f)
        val halfX = g.x
        val halfY = g.y
        g.project(0.25f, 0f)
        val nearY = g.y
        g.project(0.75f, 0f)
        val farY = g.y

        assertTrue("a quarter lap in should be nearer the rider", nearY > startY)
        assertTrue("three quarters in should be further away", farY < startY)
        assertEquals("start and halfway sit at the same depth", startY, halfY, 0.5f)
        assertTrue("start line should be on the left", startX < g.infieldCenterX)
        assertTrue("halfway should be on the right", halfX > g.infieldCenterX)
    }

    @Test
    fun `a lap is one trip around and ends where it started`() {
        val g = fitted(1596f, 762f)
        g.project(0f, 0f)
        val startX = g.x
        val startY = g.y
        g.project(1f, 0f)
        assertEquals(startX, g.x, 0.01f)
        assertEquals(startY, g.y, 0.01f)

        // Halfway round should be the far side of the loop, not a point next door to the start.
        g.project(0.5f, 0f)
        assertTrue(hypot(g.x - startX, g.y - startY) > 1000f)
    }

    @Test
    fun `equal steps of travel cover equal ground`() {
        // Lap position arrives as a fraction of the lap's *distance* — LapTracker divides the
        // machine's own distance register by the lap length — so a step of travel has to be a step
        // of ground, not a step of angle. On this ellipse a degree is worth about two and a half
        // times as much ground on the straights as it is round the ends, which is what used to make
        // the marker crawl down the near straight and whip round the bend at a steady pace.
        //
        // Measured in ground units on purpose: screen distance is perspective-weighted by
        // construction, so it cannot tell a parameterisation bug from a camera doing its job.
        for ((w, h) in listOf(1596f to 762f, 800f to 800f, 1020f to 300f)) {
            val g = fitted(w, h)
            var shortest = Float.MAX_VALUE
            var longest = 0f
            g.project(0f, 0f)
            var previousX = g.groundX
            var previousY = g.groundY
            for (i in 1..288) {
                g.project(i / 288f, 0f)
                val step = hypot(g.groundX - previousX, g.groundY - previousY)
                if (step < shortest) shortest = step
                if (step > longest) longest = step
                previousX = g.groundX
                previousY = g.groundY
            }
            assertTrue("no ground covered in $w x $h", shortest > 0f)
            assertEquals("uneven travel in $w x $h", 1f, longest / shortest, 0.02f)
        }
    }

    @Test
    fun `the four extremes still land on quarter laps`() {
        // The ellipse is symmetric about both axes, so equal distance and equal angle agree exactly
        // at the quarter points however the rest of the lap is parameterised. Everything that fits
        // the track to its box leans on that, and so does the reading of "halfway round".
        val g = fitted(1596f, 762f)
        for (u in listOf(0f, 0.25f, 0.5f, 0.75f)) {
            g.project(u, 0f)
            val theta = TrackGeometry.START_ANGLE - u * 2f * Math.PI.toFloat()
            assertEquals("x of the quarter point at u=$u", g.groundRx * cos(theta), g.groundX, 0.002f)
            assertEquals("y of the quarter point at u=$u", sin(theta), g.groundY, 0.002f)
        }
    }

    @Test
    fun `travel wraps past the start line`() {
        // The start/finish chequer is laid out across the line, so it asks for travel fractions
        // just below zero. Those have to come out just short of a full lap, not clamped onto it.
        val g = fitted(1596f, 762f)
        g.project(-0.01f, 0f)
        val beforeX = g.x
        val beforeY = g.y
        g.project(0.99f, 0f)
        assertEquals(beforeX, g.x, 0.01f)
        assertEquals(beforeY, g.y, 0.01f)
    }

    @Test
    fun `infield is a real hole inside the lane`() {
        val g = fitted(1596f, 762f)
        val outer = outerBounds(g)
        assertTrue(g.infieldWidth > 0f)
        assertTrue(g.infieldHeight > 0f)
        assertTrue(g.infieldWidth < outer.maxX - outer.minX)
        assertTrue(g.infieldHeight < outer.maxY - outer.minY)
        assertTrue(g.infieldCenterX - g.infieldWidth / 2f > outer.minX)
        assertTrue(g.infieldCenterX + g.infieldWidth / 2f < outer.maxX)
        assertTrue(g.infieldCenterY - g.infieldHeight / 2f > outer.minY)
        assertTrue(g.infieldCenterY + g.infieldHeight / 2f < outer.maxY)
    }

    @Test
    fun `outer top and bottom bracket the lane`() {
        // These drive the lane's shaders, so they track the lane itself and not the marker's reach.
        val g = fitted(1596f, 762f)
        val outer = outerBounds(g)
        assertEquals(outer.minY, g.outerTop, 0.5f)
        assertEquals(outer.maxY, g.outerBottom, 0.5f)
    }

    @Test
    fun `a box with no room is not ready`() {
        val g = TrackGeometry()
        assertFalse(g.fit(0f, 0f, padX, padY))
        assertFalse(g.ready)
        assertFalse(g.fit(10f, 400f, padX, padY))
        assertFalse(g.ready)
    }
}
