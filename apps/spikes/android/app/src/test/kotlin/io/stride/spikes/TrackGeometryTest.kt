package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

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
        // at the top of the loop, where the lane is already touching the top of the box. What has
        // to fit is everything that gets drawn.
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
    fun `the lane is one width the whole way round`() {
        // The failure this guards against: building the inner edge by scaling the whole outline
        // down, which pinches the lane on the turns and fattens it on the straights, so the shape
        // stops reading as a track. With no camera left to weight one side of the loop over another
        // either, a genuinely constant-width lane now has to measure as *exactly* one width
        // everywhere, not just avoid the old pinch-and-balloon failure.
        val g = fitted(1596f, 762f)
        val first = g.laneWidthAt(0f)
        assertTrue("lane should not vanish", first > 1f)
        var u = 1f / 288f
        while (u <= 1f) {
            assertEquals("lane width changed at u=$u", first, g.laneWidthAt(u), 0.01f)
            u += 1f / 288f
        }
    }

    @Test
    fun `the lane's cross-section stays perpendicular to the direction of travel`() {
        // The skew this guards against: a depth-dependent camera used to scale the outer and inner
        // edge of the lane by different amounts at the same travel fraction, tilting the segment
        // between them off-square by as much as 37 degrees at the ends of the straights. With no
        // camera, the offset is built along each piece's own true geometric normal (see
        // [TrackGeometry.project]) and survives the uniform scale in [TrackGeometry.fit] unsheared,
        // so it has to come out perpendicular everywhere, not just closer to it.
        val g = fitted(1596f, 762f)
        val step = 1e-4f
        var u = 0f
        while (u < 1f) {
            val outer = g.also { it.project(u, 1f) }.let { it.x to it.y }
            val inner = g.also { it.project(u, -1f) }.let { it.x to it.y }
            val cutX = outer.first - inner.first
            val cutY = outer.second - inner.second

            // No clamping: travel wraps, so u - step and u + step are valid fractions either side
            // of the seam too, and a wrapped finite difference there is exactly as accurate as one
            // in the middle of the loop.
            val back = g.also { it.project(u - step, 0f) }.let { it.x to it.y }
            val ahead = g.also { it.project(u + step, 0f) }.let { it.x to it.y }
            val tangentX = ahead.first - back.first
            val tangentY = ahead.second - back.second

            val cutLen = hypot(cutX, cutY)
            val tangentLen = hypot(tangentX, tangentY)
            val cosAngle = (cutX * tangentX + cutY * tangentY) / (cutLen * tangentLen)
            assertEquals("cut line not square to travel at u=$u", 0f, cosAngle, 0.01f)
            u += 1f / 288f
        }
    }

    @Test
    fun `the start line sits at the midpoint of one straight`() {
        // A real track's start/finish stripe sits on a straight, not mid-turn -- see the reference
        // this shape was modelled on. Checked in ground units, which do not depend on any
        // particular box's scale and origin.
        val g = fitted(1596f, 762f)
        g.project(0f, 0f)
        assertEquals(0f, g.groundX, 0.0001f)
        assertEquals(1f, g.groundY, 0.0001f)
    }

    @Test
    fun `halfway round is the midpoint of the opposite straight`() {
        // The stadium is symmetric about both axes and the start sits on one of them, so half the
        // perimeter away has to land exactly on the other straight's own midpoint.
        val g = fitted(1596f, 762f)
        g.project(0.5f, 0f)
        assertEquals(0f, g.groundX, 0.0001f)
        assertEquals(-1f, g.groundY, 0.0001f)
    }

    @Test
    fun `travel visits both straights and both turns in order`() {
        // Cross-checks [TrackGeometry.project]'s piecewise formula against the same breakpoints
        // worked out independently here: half the straight length in, the first turn begins; that
        // turn's radius is 1, so its arc length is exactly the angle it sweeps, PI for a semicircle;
        // then the far straight, then the second turn, back to the start.
        val g = fitted(1596f, 762f)
        val half = g.straightLen / 2f
        val perimeter = 2f * g.straightLen + 2f * Math.PI.toFloat()
        fun at(distanceFromStart: Float) = distanceFromStart / perimeter

        g.project(at(half), 0f)
        assertEquals("end of the near straight (x)", half, g.groundX, 0.001f)
        assertEquals("end of the near straight (y)", 1f, g.groundY, 0.001f)

        g.project(at(half + Math.PI.toFloat()), 0f)
        assertEquals("far end of the right-hand turn (x)", half, g.groundX, 0.001f)
        assertEquals("far end of the right-hand turn (y)", -1f, g.groundY, 0.001f)

        g.project(at(half + Math.PI.toFloat() + g.straightLen), 0f)
        assertEquals("end of the far straight (x)", -half, g.groundX, 0.001f)
        assertEquals("end of the far straight (y)", -1f, g.groundY, 0.001f)

        g.project(at(half + 2f * Math.PI.toFloat() + g.straightLen), 0f)
        assertEquals("far end of the left-hand turn (x)", -half, g.groundX, 0.001f)
        assertEquals("far end of the left-hand turn (y)", 1f, g.groundY, 0.001f)
    }

    @Test
    fun `each turn is a real semicircle of radius one`() {
        // The centreline on a straight is exactly 1 unit from that straight's own line by
        // definition; the turns are the part actually worth checking. Each one's centre sits
        // straightLen/2 out from the middle on the x axis, so every centreline point on it should
        // be exactly 1 ground unit from that centre -- not merely curved, but a true circular arc.
        val g = fitted(1596f, 762f)
        val half = g.straightLen / 2f
        val perimeter = 2f * g.straightLen + 2f * Math.PI.toFloat()
        var s = half + 0.01f
        val turnEnd = half + Math.PI.toFloat() - 0.01f
        while (s < turnEnd) {
            g.project(s / perimeter, 0f)
            val distanceFromCentre = hypot(g.groundX - half, g.groundY)
            assertEquals("right turn radius at arc length $s", 1f, distanceFromCentre, 0.001f)
            s += 0.05f
        }
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

        // Halfway round should be the far side of the loop, not a point next door to the start. The
        // start sits on one straight and halfway sits on the other, directly across the infield —
        // opposite corners of an ellipse, but straight across the *short* axis of a stadium — so
        // "far" is judged against the shape's own height rather than a distance picked for the
        // wide loop this shape replaced.
        g.project(0.5f, 0f)
        val loopHeight = g.outerBottom - g.outerTop
        assertTrue(hypot(g.x - startX, g.y - startY) > loopHeight * 0.5f)
    }

    @Test
    fun `equal steps of travel cover equal ground`() {
        // Lap position arrives as a fraction of the lap's *distance* — LapTracker divides the
        // machine's own distance register by the lap length — so a step of travel has to be a step
        // of ground. On a stadium this is exact by construction (see [TrackGeometry.project]'s own
        // doc), but it is still worth pinning: a future change to the piecewise formula that got a
        // segment boundary wrong would show up here as an uneven step, not as a compiler error.
        //
        // Measured in ground units on purpose: screen distance is scaled by the box's own fit, which
        // has nothing to do with whether travel itself is evenly paced.
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
