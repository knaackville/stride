package io.stride.spikes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The centrepiece of the NordicTrack/iFit stock treadmill workout screen: a running track drawn as
 * an oval seen from directly overhead, lying flat like a floor, with a marker showing where the
 * runner currently is around the lap.
 *
 * ### The geometry
 *
 * Where the track *is* lives in [TrackGeometry]: a genuine ground plane with a constant-width lane
 * and a solved fit onto whatever box this view is given, drawn without a camera rather than as a
 * squashed 2D ellipse. That is the half of this surface that can be checked without a treadmill, so
 * it is kept separate and unit-tested. This class is the other half: what colour it all is.
 *
 * ### Where the rider is, on this view's own clock
 *
 * [positionSource] is asked fresh on every tick of this view's own timer (see [refreshPosition]),
 * not pushed by the host's poll of the distance register. [LapTracker.positionAt] is what makes that
 * worth doing: it extrapolates forward from the last confirmed reading at the machine's own reported
 * pace, so asking it more often than the host polls does not invent motion, it just draws the same
 * continuous estimate at a finer grain than the register updates. There is deliberately no tween
 * between one answer and the next — see [LapTracker]'s own doc for why a discrete jump-and-animate
 * model was replaced rather than tuned again.
 *
 * ### Cost
 *
 * Everything that only depends on size is computed once in [rebuildGeometry]: the sample tables,
 * the lane path, the lane markings, the start line, the shaders and the label metrics. [onDraw]
 * allocates nothing; it walks cached arrays and reuses instance Paints, Paths and Shaders.
 */
class TrackFloorView(context: Context) : View(context) {

    /**
     * [initialLap] seeds [lap] directly, bypassing [buildLaneShaders] — nothing has fit yet, so
     * there is no shader to rebuild. A second constructor rather than a default parameter on the
     * primary one, so this stays the plain `(Context)` view Android's own tooling looks for.
     */
    constructor(context: Context, initialLap: Int) : this(context) {
        lap = initialLap
    }

    /**
     * Where the rider is around the lap, 0f..1f from the start line, or null when nothing honest can
     * be drawn. Read straight from [positionSource] on every tick — see [refreshPosition] — so this
     * is always exactly what the extrapolation says *right now*, never a discrete jump partway
     * through an animation toward it.
     *
     * Null is drawn as an empty track — no marker, no completed band — never as zero. "We cannot
     * see how far you have run" and "you have run nothing" are different claims and a marker parked
     * on the start line makes the second one.
     */
    var progress: Float? = null
        private set

    /**
     * Which lap the rider is on, 1-based, as [LapTracker] counts them. Selects the colours.
     *
     * Starts at [initialLap] rather than always at 1, because the host builds a *new* view of this
     * class on every chrome rebuild — a goal being set, a video starting, the rails being hidden —
     * and a fresh view landing back on lap one's colour would repaint a workout seven laps in as one
     * that had just restarted. [OverlayService] is what remembers the number across that rebuild;
     * this class only remembers it across its own ticks, via [refreshPosition].
     *
     * Changing it rebuilds the lane and band shaders, which is why [refreshPosition] only does so
     * when the number actually moves — rebuilding three gradients on every tick for a number that
     * changes every few minutes would be pure waste.
     */
    var lap: Int = 1
        private set

    /**
     * Force the lap back to one, and repaint for it immediately.
     *
     * For the one case [refreshPosition] cannot handle on its own: a workout ending clears
     * [LapTracker]'s own anchor, so the next several ticks answer null rather than "lap one" — and a
     * track floor left up between workouts (the "always on" choice) would keep the *previous*
     * workout's colour showing under an empty loop until the new one's first reading happened to
     * land. Called by [OverlayService] at the exact moment it resets the tracker, rather than waited
     * on here.
     */
    fun resetLap() {
        if (lap == 1) return
        lap = 1
        buildLaneShaders()
        invalidate()
    }

    /**
     * Where to ask for the rider's live position. Set once, when the host creates this view.
     *
     * A function rather than a direct [LapTracker] reference so this class does not need to know
     * that type exists beyond [LapTracker.Position] — it only ever calls what it is handed, with the
     * current time. Assigning it (including to null, when the host tears the floor down) immediately
     * asks it once and starts or stops this view's own ticking; see [refreshPosition].
     */
    var positionSource: ((nowMs: Long) -> LapTracker.Position?)? = null
        set(value) {
            field = value
            refreshPosition()
            scheduleTick()
        }

    /** Small line above the title, e.g. "LAP 3". Empty hides it. Invalidates on change. */
    var lapBadge: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Large label line, e.g. "¼ mile". Invalidates on change. */
    var lapTitle: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Small label line under it, e.g. "track length". Invalidates on change. */
    var lapSubtitle: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Overall opacity multiplier 0f..1f applied to everything drawn. Default 1f. */
    var dim: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    private companion object {
        /** Samples around the full lap. 288 keeps the outline smooth at console width. */
        const val SAMPLES = TrackGeometry.DEFAULT_SAMPLES

        /** Fraction of a lap the start/finish chequer covers. */
        const val CHEQUER_SPAN = 0.014f
        const val CHEQUER_COLUMNS = 6
        const val CHEQUER_ROWS = 2

        /** Below this much progress there is no band worth drawing, and the whole loop is lane. */
        const val BAND_EPSILON = 0.0005f

        /**
         * How often [refreshPosition] re-asks [positionSource].
         *
         * Smooth enough for a marker that covers a whole lap over minutes — a step this size is a
         * fraction of a screen pixel at any ordinary walking or running pace — and cheap enough that
         * ticking it while nothing is actually moving (a paused workout, an idle "always on" floor)
         * costs nothing worth measuring: [refreshPosition] skips the redraw entirely when the answer
         * has not changed.
         */
        const val TICK_MS = 150L
    }

    private val density: Float = resources.displayMetrics.density

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0xD6, 0xD0, 0xFF)
        strokeWidth = 1.6f * resources.displayMetrics.density
    }
    private val bandEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Lap 1's edge. Replaced by [buildLaneShaders] as the lap rotates; this is only what the
        // paint holds before the first fit, when nothing is drawn anyway.
        color = Color.rgb(0xCF, 0xFF, 0xF4)
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        strokeWidth = 1.6f * resources.displayMetrics.density
    }
    private val chequerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x04, 0x08, 0x0C)
    }
    private val markerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Deliberately fixed while the lane and band rotate through [LapPalette]. The marker is the
        // rider, not the lap: a pin that changed colour with the ground it stands on would be the
        // one thing on this surface that is always the same shade as its own background.
        color = Color.rgb(0x5C, 0xE8, 0xD2)
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.16f
        color = Color.rgb(0x7A, 0xEA, 0xD6)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0xE6, 0xE2, 0xFC)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x9A, 0x96, 0xBA)
    }

    // Design alphas are held separately so repeated [dim] writes multiply against the design value
    // each frame instead of compounding — 0.5 twice would otherwise fade everything to nothing.
    private val baseAlphas = intArrayOf(255, 255, 120, 90, 60, 235, 255, 90, 255, 255, 255, 255, 255)
    private val dimmable = arrayOf(
        lanePaint, bandPaint, outerRimPaint, bandEdgePaint, dashPaint, chequerPaint,
        scrimPaint, shadowPaint, markerBodyPaint, markerInnerPaint, badgePaint, titlePaint,
        subtitlePaint,
    )

    private val lanePath = Path()
    private val bandPath = Path()
    private val remainderPath = Path()
    private val dashPath = Path()
    private val chequerPath = Path()
    private val markerPath = Path()
    private val fontMetrics = Paint.FontMetrics()

    // Screen-space sample tables, indexed by travel fraction from the start line. Built once per
    // size so a frame is a walk over arrays rather than 1700 trig calls.
    private val outerX = FloatArray(SAMPLES + 1)
    private val outerY = FloatArray(SAMPLES + 1)
    private val innerX = FloatArray(SAMPLES + 1)
    private val innerY = FloatArray(SAMPLES + 1)

    private val geometry = TrackGeometry()
    private var geometryReady = false

    // The vertical span the lane and band gradients are stretched over. Cached from the last fit
    // because a lap change has to rebuild those two shaders without a size change to hang off.
    private var shaderTop = 0f
    private var shaderBottom = 0f

    private var badgeY = 0f
    private var titleY = 0f
    private var subtitleY = 0f

    private val tick = object : Runnable {
        override fun run() {
            refreshPosition()
            scheduleTick()
        }
    }

    /** (Re)arm [tick], or leave it stopped when there is nothing to ask. */
    private fun scheduleTick() {
        removeCallbacks(tick)
        if (positionSource != null) postDelayed(tick, TICK_MS)
    }

    /**
     * Ask [positionSource] where the rider is right now, and redraw only if the answer actually
     * changed.
     *
     * The "did it change" check is what keeps a paused workout — reported speed zero, the same
     * distance extrapolating to the same position tick after tick — from redrawing every 150ms for
     * no visible reason; motion is the case that is supposed to look continuous, not stillness.
     */
    private fun refreshPosition() {
        val position = positionSource?.invoke(SystemClock.uptimeMillis())
        val nextLap = position?.lap ?: lap
        if (nextLap != lap) {
            lap = nextLap
            buildLaneShaders()
        }
        val next = position?.progress
        lapBadge = position?.let { "LAP ${it.lap}" } ?: ""
        if (next == progress) return
        progress = next
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A detached view that keeps reposting its own tick keeps the whole overlay window alive
        // for nothing.
        removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        if (!geometryReady) return
        applyDim()

        // Lane and band are drawn as two disjoint regions rather than one over the other. They used
        // to overlap, and that is what made a lap boundary still read as a reset even with the
        // colours rotating: the completed side of the loop was band-composited-over-lane, so the
        // instant the band was promoted to be the base colour it lost the lane underneath it and
        // the whole loop thinned. Disjoint regions promote pixel-for-pixel. See LapPalette.
        val covered = progress ?: 0f
        if (covered <= BAND_EPSILON) {
            canvas.drawPath(lanePath, lanePaint)
        } else {
            buildLapPaths(covered)
            canvas.drawPath(remainderPath, lanePaint)
            canvas.drawPath(bandPath, bandPaint)
            canvas.drawPath(bandPath, bandEdgePaint)
        }
        canvas.drawPath(lanePath, outerRimPaint)
        canvas.drawPath(dashPath, dashPaint)
        canvas.drawPath(chequerPath, chequerPaint)
        drawInfield(canvas)
        if (progress != null) drawMarker(canvas, covered)
    }

    /**
     * Ask the geometry to fit the new size, fill the screen-space sample tables from it, and build
     * everything else that only depends on size. Called on every size change and nowhere else.
     */
    private fun rebuildGeometry(w: Int, h: Int) {
        geometryReady = false
        if (w <= 0 || h <= 0) return
        if (!geometry.fit(w.toFloat(), h.toFloat(), 8f * density, 6f * density)) return

        for (i in 0..SAMPLES) {
            val u = i.toFloat() / SAMPLES
            geometry.project(u, 1f)
            outerX[i] = geometry.x
            outerY[i] = geometry.y
            geometry.project(u, -1f)
            innerX[i] = geometry.x
            innerY[i] = geometry.y
        }

        buildLanePath()
        buildDashPath()
        buildChequerPath()
        shaderTop = geometry.outerTop
        shaderBottom = geometry.outerBottom
        buildLaneShaders()
        buildScrimShader()

        geometryReady = true
        layoutLabels()
    }

    /** Outer contour forward, inner contour backward: one closed ring with a genuine hole. */
    private fun buildLanePath() {
        lanePath.reset()
        lanePath.moveTo(outerX[0], outerY[0])
        for (i in 1..SAMPLES) lanePath.lineTo(outerX[i], outerY[i])
        for (i in SAMPLES downTo 0) lanePath.lineTo(innerX[i], innerY[i])
        lanePath.close()
    }

    /** Broken centre line. Track markings are what stop a coloured ring reading as a progress bar. */
    private fun buildDashPath() {
        dashPath.reset()
        val stride = 8
        var i = 0
        while (i < SAMPLES) {
            geometry.project(i.toFloat() / SAMPLES, 0f)
            dashPath.moveTo(geometry.x, geometry.y)
            val end = min(i + stride / 2, SAMPLES)
            geometry.project(end.toFloat() / SAMPLES, 0f)
            dashPath.lineTo(geometry.x, geometry.y)
            i += stride
        }
    }

    /** The painted start/finish stripe, as chequered squares laid across the lane. */
    private fun buildChequerPath() {
        chequerPath.reset()
        for (row in 0 until CHEQUER_ROWS) {
            for (col in 0 until CHEQUER_COLUMNS) {
                if ((row + col) % 2 != 0) continue
                val u0 = -CHEQUER_SPAN / 2f + CHEQUER_SPAN * row / CHEQUER_ROWS
                val u1 = u0 + CHEQUER_SPAN / CHEQUER_ROWS
                val s0 = -1f + 2f * col / CHEQUER_COLUMNS
                val s1 = -1f + 2f * (col + 1) / CHEQUER_COLUMNS
                geometry.project(u0, s0)
                chequerPath.moveTo(geometry.x, geometry.y)
                geometry.project(u0, s1)
                chequerPath.lineTo(geometry.x, geometry.y)
                geometry.project(u1, s1)
                chequerPath.lineTo(geometry.x, geometry.y)
                geometry.project(u1, s0)
                chequerPath.lineTo(geometry.x, geometry.y)
                chequerPath.close()
            }
        }
    }

    /**
     * Translucent, and brightest toward the bottom of the screen.
     *
     * A flat opaque band reads as a plastic ring lying on the glass; a vertical light gradient,
     * fading up toward the top of the loop and letting whatever is playing underneath show through
     * there, is what keeps it reading as ground rather than a sticker. [LapPalette] names its stops
     * `near`/`mid`/`far` from when a low camera made the bottom of the screen the literally nearer
     * side of the loop; with that camera gone the names are legacy, but the lighting they describe
     * still earns its place on its own as a look. [LapPalette] holds every colour to that shape, so
     * a lap eight laps in still looks the same way.
     *
     * Rebuilt on a size change *and* on a lap change, and on nothing else. It allocates three
     * shaders, so a version of this that ran per tick would be allocating in a loop that is
     * otherwise documented as allocation-free.
     */
    private fun buildLaneShaders() {
        // A fit that has not happened yet, or a degenerate one, gives a zero-height gradient — and
        // LinearGradient with equal endpoints paints the last colour flat across the whole loop.
        if (shaderBottom <= shaderTop) return
        val lane = LapPalette.lane(lap)
        val band = LapPalette.band(lap)
        lanePaint.shader = verticalFill(lane)
        bandPaint.shader = verticalFill(band)
        // Safe to write the colour even though [applyDim] owns this paint's alpha: applyDim runs
        // first on every frame and re-asserts it from the design table, so only the hue survives.
        bandEdgePaint.color = band.edge
    }

    private fun verticalFill(fill: LapPalette.Fill): LinearGradient = LinearGradient(
        0f, shaderTop, 0f, shaderBottom,
        intArrayOf(fill.far, fill.mid, fill.near),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )

    /**
     * The infield scrim: the one thing here that is deliberately not translucent at its centre.
     *
     * Text over an album grid or a poster wall needs something opaque behind it. Purely a function
     * of size, so unlike the lane and band this never has to be rebuilt for a lap change.
     */
    private fun buildScrimShader() {
        val scrimRadius = max(geometry.infieldWidth, geometry.infieldHeight) * 0.42f
        if (scrimRadius > 0f) {
            scrimPaint.shader = RadialGradient(
                geometry.infieldCenterX,
                geometry.infieldCenterY,
                scrimRadius,
                intArrayOf(0xD8060B10.toInt(), 0x8C060B10.toInt(), 0x00060B10),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    /**
     * Size the infield copy against the hole it sits in, not against the view.
     *
     * The hole is measured, and its centre sits below the view's centre: the marker reaches much
     * further above its point than its shadow drops beneath it (see [TrackGeometry.PIN_HEAD_OFFSET]
     * against [TrackGeometry.PIN_SHADOW_DROP]), so fitting the whole drawn shape into the box —
     * marker included — reserves extra headroom above the lane, and centering that combined shape
     * pushes the lane, and the hole in the middle of it, down. Text placed on the view's centre line
     * sat inside the lane rather than the hole.
     */
    private fun layoutLabels() {
        if (!geometryReady || geometry.infieldHeight <= 0f) return
        badgePaint.textSize = geometry.infieldHeight * 0.078f
        subtitlePaint.textSize = geometry.infieldHeight * 0.088f
        titlePaint.textSize = geometry.infieldHeight * 0.215f

        // The title is the only line long enough to reach the lane. Shrink rather than clip: a
        // truncated "¼ mile" is a different claim about the track.
        val room = geometry.infieldWidth * 0.78f
        if (lapTitle.isNotEmpty()) {
            val measured = titlePaint.measureText(lapTitle)
            if (measured > room) titlePaint.textSize *= room / measured
        }

        badgeY = geometry.infieldCenterY - geometry.infieldHeight * 0.20f
        titleY = geometry.infieldCenterY + geometry.infieldHeight * 0.01f
        subtitleY = geometry.infieldCenterY + geometry.infieldHeight * 0.18f
    }

    /**
     * Cut the loop at [covered] into the part this lap has run and the part it has not.
     *
     * Both walk the same cached sample tables and meet exactly on the projected cut, so the two
     * fills tile the ring without overlapping and without a gap. Reuses two instance Paths because
     * this runs on every draw.
     */
    private fun buildLapPaths(covered: Float) {
        val last = (covered * SAMPLES).toInt().coerceIn(0, SAMPLES)
        geometry.project(covered, 1f)
        val cutOuterX = geometry.x
        val cutOuterY = geometry.y
        geometry.project(covered, -1f)
        val cutInnerX = geometry.x
        val cutInnerY = geometry.y

        bandPath.reset()
        bandPath.moveTo(outerX[0], outerY[0])
        for (i in 1..last) bandPath.lineTo(outerX[i], outerY[i])
        bandPath.lineTo(cutOuterX, cutOuterY)
        bandPath.lineTo(cutInnerX, cutInnerY)
        for (i in last downTo 0) bandPath.lineTo(innerX[i], innerY[i])
        bandPath.close()

        remainderPath.reset()
        remainderPath.moveTo(cutOuterX, cutOuterY)
        for (i in last + 1..SAMPLES) remainderPath.lineTo(outerX[i], outerY[i])
        for (i in SAMPLES downTo last + 1) remainderPath.lineTo(innerX[i], innerY[i])
        remainderPath.lineTo(cutInnerX, cutInnerY)
        remainderPath.close()
    }

    private fun drawInfield(canvas: Canvas) {
        if (scrimPaint.shader != null) {
            canvas.drawCircle(
                geometry.infieldCenterX,
                geometry.infieldCenterY,
                max(geometry.infieldWidth, geometry.infieldHeight) * 0.42f,
                scrimPaint,
            )
        }
        if (lapBadge.isNotEmpty()) drawCentred(canvas, lapBadge, badgePaint, badgeY)
        if (lapTitle.isNotEmpty()) drawCentred(canvas, lapTitle, titlePaint, titleY)
        if (lapSubtitle.isNotEmpty()) drawCentred(canvas, lapSubtitle, subtitlePaint, subtitleY)
    }

    private fun drawCentred(canvas: Canvas, text: String, paint: Paint, centreY: Float) {
        paint.getFontMetrics(fontMetrics)
        canvas.drawText(text, geometry.infieldCenterX, centreY - (fontMetrics.ascent + fontMetrics.descent) / 2f, paint)
    }

    /**
     * A map-pin marker standing on the lane at travel fraction [p], sized by the lane it stands in.
     *
     * With no camera, [TrackGeometry.laneWidthAt] comes out the same everywhere around the loop, so
     * this reads off the lane's real width rather than a hardcoded constant purely so the marker
     * keeps tracking it if that width, [TrackGeometry.DEFAULT_LANE_HALF], or the fit ever changes —
     * not to carry any depth cue, which a straight overhead view has none of. It does not rotate to
     * face the direction of travel; it is a location pin, not a compass.
     */
    private fun drawMarker(canvas: Canvas, p: Float) {
        val pin = geometry.laneWidthAt(p) * TrackGeometry.PIN_SCALE
        if (pin <= 0f) return
        geometry.project(p, 0f)
        val mx = geometry.x
        val my = geometry.y

        val half = pin * TrackGeometry.PIN_HALF_WIDTH
        val drop = pin * TrackGeometry.PIN_SHADOW_DROP
        val headR = pin * TrackGeometry.PIN_HEAD_RADIUS
        val headY = my - pin * TrackGeometry.PIN_HEAD_OFFSET

        canvas.drawOval(mx - half, my - drop, mx + half, my + drop, shadowPaint)
        // Body: a triangle from the ground point up to the head, capped by the head circle.
        markerPath.reset()
        markerPath.moveTo(mx, my)
        markerPath.lineTo(mx - half, my - pin * 0.58f)
        markerPath.lineTo(mx + half, my - pin * 0.58f)
        markerPath.close()
        canvas.drawPath(markerPath, markerBodyPaint)
        canvas.drawCircle(mx, headY, headR, markerBodyPaint)
        canvas.drawCircle(mx, headY, headR * 0.42f, markerInnerPaint)
    }

    private fun applyDim() {
        for (i in dimmable.indices) {
            dimmable[i].alpha = (baseAlphas[i] * dim).toInt().coerceIn(0, 255)
        }
    }
}
