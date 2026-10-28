package com.pianocanvas.midi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.pianocanvas.midi.data.EditorPrefs
import com.pianocanvas.midi.data.ViewportState
import com.pianocanvas.midi.editor.HoverInfo
import com.pianocanvas.midi.editor.HoverZone
import com.pianocanvas.midi.editor.RollHost
import com.pianocanvas.midi.model.GridUnit
import com.pianocanvas.midi.model.MeterMap
import com.pianocanvas.midi.model.Note
import com.pianocanvas.midi.model.Project
import com.pianocanvas.midi.model.isBlackKey
import com.pianocanvas.midi.model.noteName
import com.pianocanvas.midi.model.snapTick
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tablet-first piano roll.
 *
 * The view deliberately owns its gestures and drawing instead of delegating to
 * Compose: stylus hover, pointer-level hysteresis and one-undo-per-gesture
 * editing do not map cleanly onto stock gesture detectors. The editor state is
 * pushed in with [syncState]; while a finger/stylus drag is in progress the
 * view previews locally and calls the host only once on release.
 */
class PianoRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var host: RollHost? = null
        set(value) {
            field = value
            invalidate()
        }

    // ------------------------------------------------------------------
    // State mirrored from the editor
    // ------------------------------------------------------------------

    private var project: Project? = null
    private var notesByPitch: Map<Int, List<Note>> = emptyMap()
    private var prefs = EditorPrefs()
    private var selectionIds: Set<Long> = emptySet()
    private var viewportEpoch = Int.MIN_VALUE
    private var playing = false
    private var meter: MeterMap? = null

    // ------------------------------------------------------------------
    // Viewport (dp-based so rotation/density changes keep the same view)
    // ------------------------------------------------------------------

    private var startTick = 0.0
    private var dpPerTick = 0.14f
    private var lowestPitch = 48f
    private var rowHeightDp = 20f

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale

    private val keyboardWidth = 58f * density
    private val rulerHeight = 30f * density
    private val loopLaneHeight = 16f * density
    private val topHeight get() = rulerHeight + loopLaneHeight
    private val resizeHandle = 22f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val contentLeft get() = keyboardWidth
    private val contentTop get() = topHeight
    private val contentRight get() = width.toFloat()
    private val contentBottom get() = height.toFloat()
    private val tickScale get() = dpPerTick * density
    private val rowPx get() = rowHeightDp * density

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * scaledDensity

    // ------------------------------------------------------------------
    // Paints and palette
    // ------------------------------------------------------------------

    private val colBg = 0xFF0E1116.toInt()
    private val colPanel = 0xFF141A21.toInt()
    private val colPanelRaised = 0xFF1A222B.toInt()
    private val colRowWhite = 0xFF161D25.toInt()
    private val colRowBlack = 0xFF11161C.toInt()
    private val colGrid = 0xFF1F2933.toInt()
    private val colBeat = 0xFF2A3744.toInt()
    private val colBar = 0xFF3B4E60.toInt()
    private val colNote = 0xFFF2B65A.toInt()
    private val colSelected = 0xFF3ED6C4.toInt()
    private val colText = 0xFFE7EDF4.toInt()
    private val colDim = 0xFF93A1B1.toInt()
    private val colFaint = 0xFF5D6B7A.toInt()
    private val colPlayhead = 0xFFFF6F6A.toInt()
    private val colLoop = 0xFF2BA99A.toInt()
    private val colWhiteKey = 0xFFD9E1E9.toInt()
    private val colBlackKey = 0xFF1B232C.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sp(10f)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sp(8.5f)
    }
    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(10f)
    }
    private val noteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(9f)
    }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmpRect = RectF()
    private val loopRect = RectF()
    private val hudRect = RectF()
    private val path = Path()

    // ------------------------------------------------------------------
    // Gesture state
    // ------------------------------------------------------------------

    private enum class Mode {
        NONE,
        PENDING_EMPTY,
        PENDING_NOTE,
        PAN,
        PINCH,
        DRAW,
        DRAG_MOVE,
        DRAG_RESIZE,
        MARQUEE,
        RULER_SEEK,
        LOOP_DRAG,
        KEY_PREVIEW,
        ERASE,
    }

    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTick = 0.0
    private var downPitch = 48
    private var gestureMoved = false
    private var longPressFired = false

    private var panStartTick = 0.0
    private var panStartLowest = 0f
    private var panAxis = 0 // 0 unknown, 1 horizontal, 2 vertical

    private var pinchStartSpanX = 1f
    private var pinchStartSpanY = 1f
    private var pinchStartDpPerTick = 0.14f
    private var pinchStartRowHeight = 20f
    private var pinchAnchorTick = 0.0
    private var pinchAnchorPitch = 48f

    private var downNoteId: Long? = null
    private var downZone = HoverZone.EMPTY
    private var activeNoteIds: Set<Long> = emptySet()
    private var dragOriginals: List<Note> = emptyList()
    private var dragPrimary: Note? = null
    private var dragDownTick = 0.0
    private var dragDownPitch = 48
    private var dragPreview: List<Note>? = null

    private var drawStartTick = 0
    private var drawPitch = 48
    private var drawEndTick = 0

    private var marqueeStartX = 0f
    private var marqueeStartY = 0f
    private var marqueeEndX = 0f
    private var marqueeEndY = 0f
    private var marqueeSelection: Set<Long> = emptySet()

    private var loopDragHandle = 0 // 1 start, 2 end, 3 new range
    private var loopDragStart = 0
    private var loopDragEnd = 0
    private var loopDragging = false

    private var keyPreviewPitch: Int? = null

    private var lastTapNoteId: Long? = null
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // ------------------------------------------------------------------
    // Hover state
    // ------------------------------------------------------------------

    private var hoverActive = false
    private var hoverPitch: Int? = null
    private var hoverNoteId: Long? = null
    private var hoverZone = HoverZone.EMPTY
    private var hoverRawTick = 0
    private var ghostStart = 0
    private var ghostPitch = 48
    private var ghostDuration = 0
    private var lastHoverSignature: List<Any?> = emptyList()
    private var hoverClearScheduled = false

    // ------------------------------------------------------------------
    // Playback / frame state
    // ------------------------------------------------------------------

    private var frameScheduled = false
    private var viewportDirty = false
    private var lastIdleTick = -1.0
    private var lastPreviewPitch = -1
    private var lastPreviewAt = 0L
    private var scrollHintUntil = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable { onLongPress() }

    private val hoverClearRunnable = Runnable {
        hoverClearScheduled = false
        if (hoverActive) clearHover(sendToHost = true)
    }

    private val idleTickRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val tick = host?.playheadTick() ?: 0.0
            if (abs(tick - lastIdleTick) > 0.01) {
                lastIdleTick = tick
                invalidate()
            }
            handler.postDelayed(this, 280)
        }
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (playing) {
            val tick = host?.playheadTick() ?: 0.0
            if (prefs.autoScroll) followPlayhead(tick)
            invalidate()
            ensureFrame()
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun syncState(
        project: Project?,
        prefs: EditorPrefs,
        selection: Set<Long>,
        viewport: ViewportState,
        epoch: Int,
        playing: Boolean,
    ) {
        val first = this.project == null && project != null
        val projectChanged = project !== this.project
        this.project = project
        this.prefs = prefs
        this.selectionIds = selection
        this.playing = playing

        if (projectChanged) {
            rebuildMeter()
            notesByPitch = project?.notes?.groupBy { it.pitch } ?: emptyMap()
        }

        if (first || epoch != viewportEpoch) {
            viewportEpoch = epoch
            startTick = viewport.startTick
            dpPerTick = viewport.dpPerTick
            lowestPitch = viewport.lowestPitch
            rowHeightDp = viewport.rowHeightDp
            dragPreview = null
            loopDragging = false
            clampViewport()
            viewportDirty = false
        }

        if (playing) {
            ensureFrame()
        } else if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
            if (viewportDirty) publishViewport()
            viewportDirty = false
        }

        val h = hoverNoteId
        if (h != null && project?.noteById(h) == null) {
            clearHover(sendToHost = true)
        }
        invalidate()
    }

    fun zoomHorizontal(factor: Float) {
        val center = startTick + visibleTicks() / 2.0
        dpPerTick = (dpPerTick * factor).coerceIn(MIN_DP_PER_TICK, MAX_DP_PER_TICK)
        startTick = center - visibleTicks() / 2.0
        afterViewportEdit()
    }

    fun zoomVertical(factor: Float) {
        val centerPitch = lowestPitch + visibleRows() / 2f
        rowHeightDp = (rowHeightDp * factor).coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT)
        lowestPitch = centerPitch - visibleRows() / 2f
        afterViewportEdit()
    }

    fun followPlayheadNow() {
        val tick = host?.playheadTick() ?: return
        startTick = (tick - visibleTicks() * 0.15).coerceAtLeast(0.0)
        clampViewport()
        invalidate()
    }

    fun resetViewportHelp() {
        scrollHintUntil = SystemClock.uptimeMillis() + 1200
        invalidate()
    }

    /** Clears the local ghost/preview when the editor dismisses the hover toolbar. */
    fun dismissHover() {
        clearHover(sendToHost = false)
    }

    /**
     * Fits the whole song into the roll: horizontal zoom covers the last note
     * plus a little breathing room, vertical zoom covers the used pitch range
     * with padding. Used by the "全体表示" dock button.
     */
    fun fitToSong() {
        val p = project ?: return
        val widthPx = contentRight - contentLeft
        val heightPx = contentBottom - contentTop
        if (widthPx <= dp(40f) || heightPx <= dp(40f)) return

        val end = max(p.songEndTick(), p.ppq * 4).coerceAtLeast(1)
        val spanTicks = end + p.ppq * 1.5
        val widthDp = widthPx / density
        dpPerTick = (widthDp / spanTicks).toFloat()
            .coerceIn(MIN_DP_PER_TICK, MAX_DP_PER_TICK)
        startTick = 0.0

        if (p.notes.isNotEmpty()) {
            val minPitch = p.notes.minOf { it.pitch }
            val maxPitch = p.notes.maxOf { it.pitch }
            val padding = 3
            val rows = (maxPitch - minPitch + 1 + padding * 2).coerceAtLeast(10)
            val heightDp = heightPx / density
            rowHeightDp = (heightDp / rows).coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT)
            val center = (minPitch + maxPitch) / 2f
            lowestPitch = center - visibleRows() / 2f
        }

        afterViewportEdit()
    }

    /** Scrolls the view by whole bars, keeping the current zoom. */
    fun scrollByBars(bars: Int) {
        if (bars == 0) return
        val m = meter ?: return
        val currentBar = m.barAt(startTick.toInt().coerceAtLeast(0))
        val target = (currentBar + bars).coerceAtLeast(0)
        startTick = m.barStartTick(target).toDouble()
        afterViewportEdit()
    }

    /** Vertically centers the used pitch range without touching the time zoom. */
    fun fitPitches() {
        val p = project ?: return
        if (p.notes.isEmpty()) return
        val minPitch = p.notes.minOf { it.pitch }
        val maxPitch = p.notes.maxOf { it.pitch }
        val center = (minPitch + maxPitch) / 2f
        lowestPitch = center - visibleRows() / 2f
        afterViewportEdit()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFocusable = true
        handler.post(idleTickRunnable)
        if (playing) ensureFrame()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        clearHover(sendToHost = true)
        publishViewport()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampViewport()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------
    // Coordinate helpers
    // ------------------------------------------------------------------

    private fun tickToX(tick: Double): Float =
        (contentLeft + (tick - startTick) * tickScale).toFloat()

    private fun tickToX(tick: Int): Float = tickToX(tick.toDouble())

    private fun xToTick(x: Float): Double =
        startTick + (x - contentLeft) / tickScale.toDouble()

    private fun pitchToTop(pitch: Int): Float =
        contentBottom - (pitch - lowestPitch + 1f) * rowPx

    private fun pitchAtY(y: Float): Int =
        floor(lowestPitch + (contentBottom - y) / rowPx).toInt().coerceIn(0, 127)

    private fun visibleTicks(): Double =
        ((contentRight - contentLeft) / tickScale).toDouble().coerceAtLeast(1.0)

    private fun visibleRows(): Float =
        ((contentBottom - contentTop) / rowPx).coerceAtLeast(1f)

    private fun clampViewport() {
        rowHeightDp = rowHeightDp.coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT)
        dpPerTick = dpPerTick.coerceIn(MIN_DP_PER_TICK, MAX_DP_PER_TICK)
        val p = project
        val visible = visibleTicks()
        val end = (p?.songEndTick() ?: 0).toDouble()
        val maxStart = max(end + (p?.ppq ?: 480) * 16.0, visible * 0.5)
        startTick = startTick.coerceIn(0.0, maxStart)
        val rows = visibleRows()
        val maxLow = (128f - rows).coerceAtLeast(0f)
        lowestPitch = lowestPitch.coerceIn(0f, maxLow)
    }

    private fun afterViewportEdit() {
        clampViewport()
        scrollHintUntil = SystemClock.uptimeMillis() + 900
        viewportDirty = true
        publishViewport()
        invalidate()
    }

    private fun publishViewport() {
        host?.onViewportSettled(
            ViewportState(
                startTick = startTick,
                dpPerTick = dpPerTick,
                lowestPitch = lowestPitch,
                rowHeightDp = rowHeightDp,
            )
        )
    }

    private fun rebuildMeter() {
        val p = project
        meter = p?.let { MeterMap(it.ppq, it.timeSignatures, it.timeSignature) }
    }

    private fun inputDurationTicks(): Int {
        val p = project ?: return 480
        val unit = if (prefs.inputDuration == GridUnit.OFF) GridUnit.QUARTER else prefs.inputDuration
        return unit.ticks(p.ppq).coerceAtLeast(1)
    }

    private fun snapIfEnabled(tick: Int): Int {
        val p = project ?: return tick
        return if (prefs.snapEnabled) snapTick(tick, prefs.grid, p.ppq) else tick
    }

    private fun ensureFrame() {
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun followPlayhead(tick: Double) {
        if (contentRight <= contentLeft) return
        val visible = visibleTicks()
        val left = startTick
        val right = left + visible
        if (tick < left || tick > left + visible * 0.82) {
            startTick = (tick - visible * 0.16).coerceAtLeast(0.0)
            clampViewport()
            viewportDirty = true
        }
    }

    // ------------------------------------------------------------------
    // Touch input
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (project == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) beginPinch(event)
            }
            MotionEvent.ACTION_MOVE -> updateTouch(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> endTouch(event)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun beginTouch(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        handler.removeCallbacks(longPressRunnable)
        clearHover(sendToHost = true)
        activePointerId = event.getPointerId(0)
        downX = event.x
        downY = event.y
        lastX = downX
        lastY = downY
        gestureMoved = false
        longPressFired = false
        downTick = xToTick(downX)
        downPitch = pitchAtY(downY)
        dragPreview = null
        marqueeSelection = emptySet()

        when {
            downX < contentLeft && downY >= topHeight -> {
                mode = Mode.KEY_PREVIEW
                keyPreviewPitch = downPitch
                host?.previewNote(downPitch, prefs.inputVelocity)
            }
            downY < rulerHeight -> {
                mode = Mode.RULER_SEEK
                host?.seekTo(xToTick(downX).coerceAtLeast(0.0))
            }
            downY < topHeight -> {
                mode = Mode.LOOP_DRAG
                beginLoopDrag(downX)
            }
            else -> beginRollTouch(event)
        }
        invalidate()
    }

    private fun beginRollTouch(event: MotionEvent) {
        val p = project ?: return
        val stylus = isStylus(event)
        val note = findNoteAt(event.x, event.y)

        if (isEraser(event)) {
            if (note != null) {
                mode = Mode.ERASE
                downNoteId = note.id
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } else {
                mode = Mode.NONE
            }
            return
        }

        if (note != null) {
            downNoteId = note.id
            downZone = zoneFor(note, event.x, event.y, null)
            activeNoteIds =
                if (note.id in selectionIds && selectionIds.size > 1) selectionIds
                else setOf(note.id)
            dragOriginals = p.notes.filter { it.id in activeNoteIds }
            dragPrimary = note
            dragDownTick = xToTick(event.x)
            dragDownPitch = pitchAtY(event.y)
            mode = Mode.PENDING_NOTE
            // Long-press toggles selection for both finger and pen: the pen
            // keeps precise drag editing, but can still build a multi-selection.
            handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            return
        }

        downNoteId = null
        downPitch = pitchAtY(event.y)
        val snapped = snapIfEnabled(xToTick(event.x).toInt()).coerceAtLeast(0)
        if (stylus) {
            mode = Mode.DRAW
            drawStartTick = snapped
            drawPitch = downPitch
            drawEndTick = snapped + inputDurationTicks()
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            mode = Mode.PENDING_EMPTY
            handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun updateTouch(event: MotionEvent) {
        if (mode == Mode.PINCH) {
            updatePinch(event)
            return
        }
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        val x = event.getX(index)
        val y = event.getY(index)
        lastX = x
        lastY = y
        val dx = x - downX
        val dy = y - downY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance > touchSlop) gestureMoved = true

        when (mode) {
            Mode.PENDING_EMPTY -> {
                if (distance > touchSlop) {
                    cancelLongPressTimer()
                    beginPan()
                    applyPan(dx, dy)
                }
            }
            Mode.PENDING_NOTE -> {
                if (distance > touchSlop) {
                    cancelLongPressTimer()
                    val stylus = isStylus(event)
                    if (!prefs.fingerEditing && !stylus) {
                        beginPan()
                        applyPan(dx, dy)
                    } else {
                        beginNoteDrag()
                        applyNoteDrag(x, y)
                    }
                }
            }
            Mode.PAN -> applyPan(dx, dy)
            Mode.DRAW -> {
                val tick = snapIfEnabled(xToTick(x).toInt()).coerceAtLeast(0)
                drawEndTick = max(drawStartTick + 1, tick)
                scrollHintUntil = SystemClock.uptimeMillis() + 350
            }
            Mode.DRAG_MOVE, Mode.DRAG_RESIZE -> applyNoteDrag(x, y)
            Mode.MARQUEE -> updateMarquee(x, y)
            Mode.RULER_SEEK -> host?.seekTo(xToTick(x).coerceAtLeast(0.0))
            Mode.LOOP_DRAG -> updateLoopDrag(x)
            Mode.KEY_PREVIEW -> {
                val pitch = pitchAtY(y)
                if (pitch != keyPreviewPitch) {
                    keyPreviewPitch = pitch
                    host?.previewNote(pitch, prefs.inputVelocity)
                }
            }
            else -> Unit
        }
        invalidate()
    }

    private fun endTouch(event: MotionEvent) {
        cancelLongPressTimer()
        when (mode) {
            Mode.PENDING_EMPTY -> {
                if (prefs.fingerEditing) {
                    val tick = snapIfEnabled(downTick.toInt()).coerceAtLeast(0)
                    host?.addNoteAt(downPitch, tick, inputDurationTicks())
                }
            }
            Mode.PENDING_NOTE -> {
                val id = downNoteId
                if (id != null) {
                    val current = host?.selection() ?: selectionIds
                    if (id !in current) host?.setRollSelection(setOf(id))
                    val now = SystemClock.uptimeMillis()
                    val editAllowed = prefs.fingerEditing || isStylus(event)
                    if (editAllowed &&
                        lastTapNoteId == id &&
                        now - lastTapAt <= 350 &&
                        kotlin.math.sqrt(
                            (downX - lastTapX) * (downX - lastTapX) +
                                (downY - lastTapY) * (downY - lastTapY)
                        ) < dp(48f)
                    ) {
                        // Double-tap deletes the note. Numeric editing stays
                        // available from the dock and the hover toolbar.
                        host?.deleteNoteByDoubleTap(id)
                        lastTapNoteId = null
                    } else {
                        project?.noteById(id)?.let { host?.previewNote(it.pitch, it.velocity) }
                        lastTapNoteId = id
                        lastTapAt = now
                        lastTapX = downX
                        lastTapY = downY
                    }
                }
            }
            Mode.DRAW -> {
                val start = drawStartTick.coerceAtLeast(0)
                val end = max(start + 1, drawEndTick)
                host?.addNoteAt(drawPitch, start, end - start)
            }
            Mode.DRAG_MOVE, Mode.DRAG_RESIZE -> {
                val preview = dragPreview
                if (preview != null && preview != project?.notes) host?.commitRollNotes(preview)
                dragPreview = null
                publishViewport()
            }
            Mode.PAN -> {
                scrollHintUntil = SystemClock.uptimeMillis() + 900
                publishViewport()
            }
            Mode.MARQUEE -> host?.setRollSelection(marqueeSelection)
            Mode.LOOP_DRAG -> {
                if (loopDragging && gestureMoved) host?.setLoopRange(loopDragStart, loopDragEnd)
                loopDragging = false
            }
            Mode.ERASE -> downNoteId?.let { host?.deleteNotes(setOf(it)) }
            else -> Unit
        }
        // A completed tap (as opposed to a pan, pinch or edit drag) counts as
        // a click for accessibility services.
        if (!gestureMoved && mode != Mode.PAN && mode != Mode.PINCH) performClick()
        mode = Mode.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        keyPreviewPitch = null
        downNoteId = null
        dragOriginals = emptyList()
        dragPrimary = null
        dragPreview = null
        handler.removeCallbacks(hoverClearRunnable)
        hoverClearScheduled = true
        handler.postDelayed(hoverClearRunnable, 60)
        invalidate()
    }

    private fun cancelGesture() {
        cancelLongPressTimer()
        mode = Mode.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragPreview = null
        dragOriginals = emptyList()
        dragPrimary = null
        keyPreviewPitch = null
        loopDragging = false
        downNoteId = null
        invalidate()
    }

    private fun handlePointerUp(event: MotionEvent) {
        if (mode != Mode.PINCH) return
        val remaining = event.pointerCount - 1
        if (remaining >= 2) {
            val lifted = event.actionIndex
            val indices = (0 until event.pointerCount).filter { it != lifted }.take(2)
            if (indices.size == 2) {
                initPinch(
                    event.getX(indices[0]), event.getY(indices[0]),
                    event.getX(indices[1]), event.getY(indices[1]),
                )
            }
        } else {
            mode = Mode.NONE
            activePointerId = MotionEvent.INVALID_POINTER_ID
            publishViewport()
            invalidate()
        }
    }

    private fun beginPan() {
        mode = Mode.PAN
        panStartTick = startTick
        panStartLowest = lowestPitch
        panAxis = 0
    }

    private fun applyPan(dx: Float, dy: Float) {
        if (panAxis == 0 && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            panAxis = if (abs(dx) >= abs(dy)) 1 else 2
        }
        when (panAxis) {
            1 -> startTick = panStartTick - dx / tickScale.toDouble()
            2 -> lowestPitch = panStartLowest + dy / rowPx
            else -> {
                startTick = panStartTick - dx / tickScale.toDouble()
                lowestPitch = panStartLowest + dy / rowPx
            }
        }
        clampViewport()
        scrollHintUntil = SystemClock.uptimeMillis() + 350
        viewportDirty = true
    }

    private fun beginPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        dragPreview = null
        cancelLongPressTimer()
        clearHover(sendToHost = true)
        initPinch(
            event.getX(0), event.getY(0),
            event.getX(1), event.getY(1),
        )
    }

    private fun initPinch(x0: Float, y0: Float, x1: Float, y1: Float) {
        mode = Mode.PINCH
        activePointerId = MotionEvent.INVALID_POINTER_ID
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        pinchStartSpanX = max(abs(x1 - x0), dp(24f))
        pinchStartSpanY = max(abs(y1 - y0), dp(24f))
        pinchStartDpPerTick = dpPerTick
        pinchStartRowHeight = rowHeightDp
        pinchAnchorTick = xToTick(cx)
        pinchAnchorPitch = pitchAtY(cy).toFloat()
        scrollHintUntil = SystemClock.uptimeMillis() + 900
    }

    private fun updatePinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val spanX = max(abs(x1 - x0), dp(24f))
        val spanY = max(abs(y1 - y0), dp(24f))

        val hScale = if (pinchStartSpanX > dp(48f)) {
            (spanX / pinchStartSpanX).coerceIn(0.4f, 2.5f)
        } else 1f
        val vScale = if (pinchStartSpanY > dp(48f)) {
            (spanY / pinchStartSpanY).coerceIn(0.4f, 2.5f)
        } else 1f

        dpPerTick = (pinchStartDpPerTick * hScale).coerceIn(MIN_DP_PER_TICK, MAX_DP_PER_TICK)
        rowHeightDp = (pinchStartRowHeight * vScale).coerceIn(MIN_ROW_HEIGHT, MAX_ROW_HEIGHT)

        startTick = pinchAnchorTick - (cx - contentLeft) / tickScale.toDouble()
        lowestPitch = pinchAnchorPitch - (contentBottom - cy) / rowPx
        clampViewport()
        scrollHintUntil = SystemClock.uptimeMillis() + 700
        viewportDirty = true
    }

    // ------------------------------------------------------------------
    // Note editing
    // ------------------------------------------------------------------

    private fun beginNoteDrag() {
        val p = project ?: return
        val id = downNoteId ?: return
        dragOriginals = p.notes.filter { it.id in activeNoteIds }
        dragPrimary = p.noteById(id)
        if (dragOriginals.isEmpty() || dragPrimary == null) return
        dragDownTick = xToTick(downX)
        dragDownPitch = pitchAtY(downY)
        mode =
            if (downZone == HoverZone.NOTE_START || downZone == HoverZone.NOTE_END)
                Mode.DRAG_RESIZE
            else Mode.DRAG_MOVE
        val current = host?.selection() ?: selectionIds
        if (current != activeNoteIds) host?.setRollSelection(activeNoteIds)
    }

    private fun applyNoteDrag(x: Float, y: Float) {
        val p = project ?: return
        val primary = dragPrimary ?: return
        if (dragOriginals.isEmpty()) return
        val rawTick = xToTick(x)

        if (mode == Mode.DRAG_MOVE) {
            val rawDelta = (rawTick - dragDownTick).roundToInt()
            var newStart = primary.startTick + rawDelta
            if (prefs.snapEnabled) {
                newStart = snapTick(newStart, prefs.grid, p.ppq)
            }
            var deltaTick = newStart - primary.startTick
            val minStart = dragOriginals.minOf { it.startTick }
            if (minStart + deltaTick < 0) deltaTick = -minStart

            var deltaPitch = pitchAtY(y) - dragDownPitch
            val minPitch = dragOriginals.minOf { it.pitch }
            val maxPitch = dragOriginals.maxOf { it.pitch }
            deltaPitch = deltaPitch.coerceIn(-minPitch, 127 - maxPitch)

            val moved = HashMap<Long, Note>(dragOriginals.size * 2)
            dragOriginals.forEach { n ->
                moved[n.id] = n.copy(
                    startTick = (n.startTick + deltaTick).coerceAtLeast(0),
                    pitch = (n.pitch + deltaPitch).coerceIn(0, 127),
                )
            }
            dragPreview = p.notes.map { moved[it.id] ?: it }
            val shown = moved[primary.id]
            if (shown != null) {
                maybePreview(shown.pitch, shown.velocity)
            }
        } else if (mode == Mode.DRAG_RESIZE) {
            val deltaRaw = rawTick - dragDownTick
            val map = HashMap<Long, Note>(dragOriginals.size * 2)
            if (downZone == HoverZone.NOTE_START) {
                var newStart = primary.startTick + deltaRaw.roundToInt()
                if (prefs.snapEnabled) {
                    newStart = snapTick(newStart, prefs.grid, p.ppq).coerceAtLeast(0)
                }
                var delta = newStart - primary.startTick
                var lo = Int.MIN_VALUE
                var hi = Int.MAX_VALUE
                dragOriginals.forEach { n ->
                    lo = max(lo, -n.startTick)
                    hi = min(hi, n.durationTicks - 1)
                }
                if (lo > hi) lo = hi
                delta = delta.coerceIn(lo, hi)
                dragOriginals.forEach { n ->
                    map[n.id] = n.copy(
                        startTick = n.startTick + delta,
                        durationTicks = n.durationTicks - delta,
                    )
                }
            } else {
                var newEnd = primary.endTick + deltaRaw.roundToInt()
                if (prefs.snapEnabled) {
                    newEnd = snapTick(newEnd, prefs.grid, p.ppq)
                }
                var delta = newEnd - primary.endTick
                var minDelta = Int.MIN_VALUE
                dragOriginals.forEach { n ->
                    minDelta = max(minDelta, 1 - n.durationTicks)
                }
                delta = delta.coerceAtLeast(minDelta)
                dragOriginals.forEach { n ->
                    map[n.id] = n.copy(
                        durationTicks = (n.durationTicks + delta).coerceAtLeast(1)
                    )
                }
            }
            dragPreview = p.notes.map { map[it.id] ?: it }
        }
    }

    private fun maybePreview(pitch: Int, velocity: Int) {
        val now = SystemClock.uptimeMillis()
        if (pitch != lastPreviewPitch || now - lastPreviewAt > 130) {
            lastPreviewPitch = pitch
            lastPreviewAt = now
            host?.previewNote(pitch, velocity)
        }
    }

    private fun updateMarquee(x: Float, y: Float) {
        marqueeEndX = x
        marqueeEndY = y
        val p = project ?: return
        val left = min(marqueeStartX, x)
        val right = max(marqueeStartX, x)
        val top = min(marqueeStartY, y)
        val bottom = max(marqueeStartY, y)
        tmpRect.set(left, top, right, bottom)
        val ids = LinkedHashSet<Long>()
        p.notes.forEach { note ->
            val noteRect = noteRect(note, RectF())
            if (RectF.intersects(noteRect, tmpRect)) ids.add(note.id)
        }
        if (ids != marqueeSelection) {
            marqueeSelection = ids
            host?.setRollSelection(ids)
        }
    }

    private fun beginLoopDrag(x: Float) {
        val p = project ?: return
        val tick = snapIfEnabled(xToTick(x).toInt()).coerceAtLeast(0)
        val startX = tickToX(p.loopStartTick)
        val endX = tickToX(p.loopEndTick)
        val tolerance = dp(20f)
        loopDragHandle = when {
            abs(x - startX) <= tolerance -> 1
            abs(x - endX) <= tolerance -> 2
            else -> 3
        }
        loopDragStart = p.loopStartTick
        loopDragEnd = p.loopEndTick
        if (loopDragHandle == 3) {
            loopDragStart = tick
            loopDragEnd = tick + (p.ppq * 4)
        }
        loopDragging = true
        scrollHintUntil = SystemClock.uptimeMillis() + 600
    }

    private fun updateLoopDrag(x: Float) {
        if (!loopDragging) return
        val tick = snapIfEnabled(xToTick(x).toInt()).coerceAtLeast(0)
        when (loopDragHandle) {
            1 -> loopDragStart = min(tick, loopDragEnd - 1).coerceAtLeast(0)
            2 -> loopDragEnd = max(tick, loopDragStart + 1)
            else -> {
                if (tick >= loopDragStart) {
                    loopDragEnd = max(tick, loopDragStart + 1)
                } else {
                    loopDragEnd = loopDragStart
                    loopDragStart = tick
                }
            }
        }
    }

    private fun onLongPress() {
        if (!isAttachedToWindow) return
        when (mode) {
            Mode.PENDING_EMPTY -> {
                longPressFired = true
                mode = Mode.MARQUEE
                marqueeStartX = downX
                marqueeStartY = downY
                marqueeEndX = downX
                marqueeEndY = downY
                marqueeSelection = emptySet()
                host?.setRollSelection(emptySet())
                selectionIds = emptySet()
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
            Mode.PENDING_NOTE -> {
                longPressFired = true
                val id = downNoteId ?: return
                val current = host?.selection() ?: selectionIds
                val next = if (id in current) current - id else current + id
                host?.setRollSelection(next)
                selectionIds = next
                mode = Mode.NONE
                activePointerId = MotionEvent.INVALID_POINTER_ID
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
            else -> Unit
        }
    }

    private fun cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable)
    }

    // ------------------------------------------------------------------
    // Hover / stylus preview
    // ------------------------------------------------------------------

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                handleHover(event)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                scheduleHoverClear()
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    private fun handleHover(event: MotionEvent) {
        val tool = event.getToolType(0)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS ||
            tool == MotionEvent.TOOL_TYPE_ERASER ||
            tool == MotionEvent.TOOL_TYPE_MOUSE
        if (!stylus) {
            clearHover(sendToHost = true)
            return
        }
        if (mode != Mode.NONE) return

        handler.removeCallbacks(hoverClearRunnable)
        hoverClearScheduled = false
        val x = event.x
        val y = event.y
        lastX = x
        lastY = y
        val p = project ?: return

        if (x < contentLeft) {
            hoverPitch = pitchAtY(y).takeIf { y >= topHeight }
            hoverActive = false
            hoverNoteId = null
            hoverZone = HoverZone.EMPTY
            ghostDuration = 0
            host?.onHoverInfo(null)
            lastHoverSignature = emptyList()
            invalidate()
            return
        }
        if (y < topHeight) {
            hoverActive = false
            hoverPitch = null
            hoverNoteId = null
            ghostDuration = 0
            host?.onHoverInfo(null)
            lastHoverSignature = emptyList()
            invalidate()
            return
        }

        val pitch = pitchAtY(y)
        val rawTick = xToTick(x).coerceAtLeast(0.0)
        val snapped = snapIfEnabled(rawTick.toInt()).coerceAtLeast(0)
        val note = findNoteAt(x, y)
        hoverActive = true
        hoverPitch = pitch
        hoverRawTick = rawTick.toInt()
        val previousZone = if (note != null && hoverNoteId == note.id) hoverZone else null

        if (note != null) {
            hoverNoteId = note.id
            hoverZone = zoneFor(note, x, y, previousZone)
            ghostStart = note.startTick
            ghostPitch = note.pitch
            ghostDuration = note.durationTicks
        } else {
            hoverNoteId = null
            hoverZone = HoverZone.EMPTY
            ghostStart = snapped
            ghostPitch = pitch
            ghostDuration = inputDurationTicks()
        }

        val barLabel = meter?.positionAt(rawTick.toInt().coerceAtLeast(0))?.label() ?: "1.1"
        val signature = listOf(
            hoverRawTick,
            hoverPitch,
            hoverZone,
            hoverNoteId,
            ghostStart,
            ghostPitch,
            ghostDuration,
            barLabel,
        )
        if (signature != lastHoverSignature) {
            lastHoverSignature = signature
            val info = HoverInfo(
                xPx = x,
                yPx = y,
                pitch = pitch,
                tick = rawTick.toInt(),
                snappedTick = snapped,
                barBeatLabel = barLabel,
                zone = hoverZone,
                noteId = note?.id,
                notePitch = note?.pitch,
                noteStart = note?.startTick,
                noteDuration = note?.durationTicks,
                ghostStart = ghostStart,
                ghostPitch = ghostPitch,
                ghostDurationTicks = ghostDuration,
                stylus = true,
            )
            host?.onHoverInfo(info)
        }
        invalidate()
    }

    private fun scheduleHoverClear() {
        if (!hoverActive) return
        handler.removeCallbacks(hoverClearRunnable)
        hoverClearScheduled = true
        handler.postDelayed(hoverClearRunnable, 2400)
    }

    private fun clearHover(sendToHost: Boolean) {
        handler.removeCallbacks(hoverClearRunnable)
        hoverClearScheduled = false
        if (hoverActive || hoverPitch != null || ghostDuration != 0) {
            hoverActive = false
            hoverPitch = null
            hoverNoteId = null
            hoverZone = HoverZone.EMPTY
            ghostDuration = 0
            lastHoverSignature = emptyList()
            if (sendToHost) host?.onHoverInfo(null)
            invalidate()
        }
    }

    private fun zoneFor(note: Note, x: Float, y: Float, previous: HoverZone?): HoverZone {
        if (pitchAtY(y) != note.pitch) return HoverZone.EMPTY
        val x1 = tickToX(note.startTick)
        val x2 = tickToX(note.endTick)
        val width = x2 - x1
        val handle = min(resizeHandle, max(dp(10f), width * 0.36f))
        val hysteresis = dp(5f)
        val startEdge = x1 + handle
        val endEdge = x2 - handle
        if (previous == HoverZone.NOTE_START && x <= startEdge + hysteresis) {
            return HoverZone.NOTE_START
        }
        if (previous == HoverZone.NOTE_END && x >= endEdge - hysteresis) {
            return HoverZone.NOTE_END
        }
        return when {
            x <= startEdge -> HoverZone.NOTE_START
            x >= endEdge -> HoverZone.NOTE_END
            else -> HoverZone.NOTE_BODY
        }
    }

    private fun findNoteAt(x: Float, y: Float): Note? {
        val pitch = pitchAtY(y)
        val tolerance = dp(2f)
        val candidates = notesByPitch[pitch] ?: return null
        for (i in candidates.indices.reversed()) {
            val n = candidates[i]
            val x1 = tickToX(n.startTick)
            val x2 = tickToX(n.endTick)
            if (x >= x1 - tolerance && x <= x2 + tolerance) return n
        }
        return null
    }

    private fun isStylus(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun isEraser(event: MotionEvent): Boolean =
        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colBg)
        val p = project
        if (p == null) {
            textPaint.color = colDim
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("読み込み中…", width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        drawRollBackground(canvas)
        canvas.save()
        canvas.clipRect(contentLeft, contentTop, contentRight, contentBottom)
        drawGrid(canvas, p)
        drawLoopShade(canvas, p)
        drawNotes(canvas, p)
        drawGhost(canvas, p)
        drawPlayheadLine(canvas)
        drawMarquee(canvas)
        drawEmptyHint(canvas, p)
        canvas.restore()

        drawRuler(canvas, p)
        drawLoopLane(canvas, p)
        drawKeyboard(canvas, p)
        drawScrollbars(canvas, p)
        drawDragHud(canvas, p)
        drawCorner(canvas)
    }

    private fun drawRollBackground(canvas: Canvas) {
        fillPaint.color = colRowWhite
        canvas.drawRect(contentLeft, contentTop, contentRight, contentBottom, fillPaint)
        if (contentBottom <= contentTop) return
        val first = floor(lowestPitch).toInt().coerceAtLeast(0)
        val last = ceil(lowestPitch + visibleRows()).toInt().coerceAtMost(128)
        fillPaint.color = colRowBlack
        for (pitch in first until last) {
            if (isBlackKey(pitch)) {
                val top = pitchToTop(pitch)
                canvas.drawRect(contentLeft, top, contentRight, top + rowPx, fillPaint)
            }
        }
    }

    private fun drawGrid(canvas: Canvas, p: Project) {
        val m = meter ?: return
        val viewStart = startTick
        val viewEnd = startTick + visibleTicks()
        strokePaint.strokeWidth = dp(1f)

        val grid = prefs.grid
        if (grid != GridUnit.OFF) {
            val step = grid.ticks(p.ppq)
            val stepPx = step * tickScale
            if (stepPx >= dp(5f)) {
                var tick = (floor(viewStart / step).toInt() * step).coerceAtLeast(0)
                strokePaint.color = colGrid
                while (tick <= viewEnd && tick < Int.MAX_VALUE - step) {
                    val x = tickToX(tick)
                    if (x >= contentLeft && x <= contentRight) {
                        canvas.drawLine(x, contentTop, x, contentBottom, strokePaint)
                    }
                    tick += step
                }
            }
        }

        var bar = m.barAt(viewStart.toInt().coerceAtLeast(0))
        var guard = 0
        while (guard++ < 400) {
            val barStart = m.barStartTick(bar).coerceAtLeast(0)
            if (barStart > viewEnd) break
            val sig = m.signatureAt(barStart)
            val barTicks = sig.barTicks(p.ppq).coerceAtLeast(1)
            val beatTicks = barTicks / sig.numerator
            if (beatTicks * tickScale >= dp(7f)) {
                strokePaint.color = colBeat
                for (b in 1 until sig.numerator) {
                    val tick = barStart + b * beatTicks
                    val x = tickToX(tick)
                    if (x in contentLeft..contentRight) {
                        canvas.drawLine(x, contentTop, x, contentBottom, strokePaint)
                    }
                }
            }
            val x = tickToX(barStart)
            if (x in contentLeft..contentRight) {
                strokePaint.color = colBar
                strokePaint.strokeWidth = dp(1.4f)
                canvas.drawLine(x, contentTop, x, contentBottom, strokePaint)
                strokePaint.strokeWidth = dp(1f)
            }
            bar++
        }

        if (rowPx >= dp(7f)) {
            val first = floor(lowestPitch).toInt().coerceAtLeast(0)
            val last = ceil(lowestPitch + visibleRows()).toInt().coerceAtMost(128)
            strokePaint.color = colGrid
            for (pitch in first..last) {
                val y = pitchToTop(pitch)
                if (y in contentTop..contentBottom) {
                    canvas.drawLine(contentLeft, y, contentRight, y, strokePaint)
                }
            }
        }
    }

    private fun drawLoopShade(canvas: Canvas, p: Project) {
        val start: Int
        val end: Int
        if (loopDragging) {
            start = loopDragStart
            end = loopDragEnd
        } else {
            start = p.loopStartTick
            end = p.loopEndTick
        }
        if (end <= start) return
        val x1 = tickToX(start)
        val x2 = tickToX(end)
        if (x2 < contentLeft || x1 > contentRight) return
        fillPaint.color = if (p.loopEnabled || loopDragging) {
            withAlpha(colLoop, 0.075f)
        } else {
            withAlpha(colDim, 0.045f)
        }
        canvas.drawRect(x1, contentTop, x2, contentBottom, fillPaint)
    }

    private fun drawNotes(canvas: Canvas, p: Project) {
        val notes = dragPreview ?: p.notes
        val viewStart = startTick - 1
        val viewEnd = startTick + visibleTicks() + 1
        for (note in notes) {
            if (note.endTick < viewStart || note.startTick > viewEnd) continue
            drawNote(canvas, note)
        }
    }

    private fun drawNote(canvas: Canvas, note: Note) {
        val x1 = tickToX(note.startTick)
        var x2 = tickToX(note.endTick)
        if (x2 - x1 < dp(3f)) x2 = x1 + dp(3f)
        if (x2 < contentLeft || x1 > contentRight) return
        val top = pitchToTop(note.pitch)
        val height = rowPx
        val inset = (height * 0.09f).coerceIn(dp(0.5f), dp(2.5f))
        val left = max(x1, contentLeft - dp(2f))
        val right = min(x2, contentRight + dp(2f))
        if (right <= left) return
        tmpRect.set(left, top + inset, right, top + height - inset)
        val radius = min(dp(4f), tmpRect.height() / 2f)
        val selected = note.id in selectionIds
        val hovered = note.id == hoverNoteId

        notePaint.color = if (selected) colSelected else velocityColor(note.velocity)
        canvas.drawRoundRect(tmpRect, radius, radius, notePaint)
        if (selected) {
            noteStroke.color = colText
            noteStroke.strokeWidth = dp(1.2f)
            canvas.drawRoundRect(tmpRect, radius, radius, noteStroke)
        } else if (hovered) {
            noteStroke.color = withAlpha(colText, 0.85f)
            noteStroke.strokeWidth = dp(1.2f)
            canvas.drawRoundRect(tmpRect, radius, radius, noteStroke)
        }

        if (tmpRect.width() > dp(26f) && rowPx >= dp(12f)) {
            noteTextPaint.color = if (selected) 0xFF06201E.toInt() else 0xFF221607.toInt()
            noteTextPaint.textSize = min(sp(9f), rowPx * 0.55f).coerceAtLeast(sp(7f))
            val baseline = tmpRect.centerY() - (noteTextPaint.ascent() + noteTextPaint.descent()) / 2f
            canvas.save()
            canvas.clipRect(tmpRect.left + dp(3f), tmpRect.top, tmpRect.right - dp(2f), tmpRect.bottom)
            val label = if (tmpRect.width() > dp(72f)) {
                "${noteName(note.pitch)}  ${note.velocity}"
            } else {
                noteName(note.pitch)
            }
            canvas.drawText(label, tmpRect.left + dp(4f), baseline, noteTextPaint)
            canvas.restore()
        }

        if (selected || hovered) {
            noteStroke.color = if (selected) colText else colSelected
            noteStroke.strokeWidth = dp(2f)
            val grip = min(dp(3f), tmpRect.width() * 0.2f)
            canvas.drawLine(tmpRect.left + grip, tmpRect.top + dp(2f), tmpRect.left + grip, tmpRect.bottom - dp(2f), noteStroke)
            canvas.drawLine(tmpRect.right - grip, tmpRect.top + dp(2f), tmpRect.right - grip, tmpRect.bottom - dp(2f), noteStroke)
        }
    }

    private fun drawGhost(canvas: Canvas, p: Project) {
        val start: Int
        val pitch: Int
        val duration: Int
        if (mode == Mode.DRAW) {
            start = drawStartTick
            pitch = drawPitch
            duration = max(1, drawEndTick - drawStartTick)
        } else if (hoverActive && hoverNoteId == null && ghostDuration > 0) {
            start = ghostStart
            pitch = ghostPitch
            duration = ghostDuration
        } else {
            return
        }
        val x1 = tickToX(start)
        var x2 = tickToX(start + duration)
        if (x2 - x1 < dp(4f)) x2 = x1 + dp(4f)
        val top = pitchToTop(pitch)
        val inset = (rowPx * 0.09f).coerceIn(dp(0.5f), dp(2.5f))
        tmpRect.set(x1, top + inset, x2, top + rowPx - inset)
        val radius = min(dp(4f), tmpRect.height() / 2f)
        ghostFill.color = withAlpha(colSelected, 0.18f)
        ghostStroke.color = withAlpha(colSelected, 0.95f)
        canvas.drawRoundRect(tmpRect, radius, radius, ghostFill)
        canvas.drawRoundRect(tmpRect, radius, radius, ghostStroke)
        if (tmpRect.width() > dp(34f) && rowPx >= dp(12f)) {
            noteTextPaint.color = colSelected
            noteTextPaint.textSize = min(sp(9f), rowPx * 0.55f).coerceAtLeast(sp(7f))
            val baseline = tmpRect.centerY() - (noteTextPaint.ascent() + noteTextPaint.descent()) / 2f
            canvas.drawText(noteName(pitch), tmpRect.left + dp(4f), baseline, noteTextPaint)
        }
    }

    private fun drawPlayheadLine(canvas: Canvas) {
        val tick = host?.playheadTick() ?: 0.0
        val x = tickToX(tick)
        if (x < contentLeft - dp(1f) || x > contentRight + dp(1f)) return
        strokePaint.color = colPlayhead
        strokePaint.strokeWidth = dp(2f)
        canvas.drawLine(x, contentTop, x, contentBottom, strokePaint)
        strokePaint.strokeWidth = dp(1f)
    }

    private fun drawMarquee(canvas: Canvas) {
        if (mode != Mode.MARQUEE) return
        val left = min(marqueeStartX, marqueeEndX)
        val right = max(marqueeStartX, marqueeEndX)
        val top = min(marqueeStartY, marqueeEndY)
        val bottom = max(marqueeStartY, marqueeEndY)
        tmpRect.set(left, top, right, bottom)
        fillPaint.color = withAlpha(colSelected, 0.12f)
        canvas.drawRect(tmpRect, fillPaint)
        strokePaint.color = withAlpha(colSelected, 0.8f)
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRect(tmpRect, strokePaint)
        strokePaint.strokeWidth = dp(1f)
    }

    private fun drawRuler(canvas: Canvas, p: Project) {
        fillPaint.color = colPanel
        canvas.drawRect(contentLeft, 0f, contentRight, rulerHeight, fillPaint)
        val m = meter ?: return
        val viewStart = startTick
        val viewEnd = startTick + visibleTicks()
        val firstBar = m.barAt(viewStart.toInt().coerceAtLeast(0))
        var bar = firstBar
        var guard = 0
        val stepPx = max(dp(46f), dp(10f))
        var labelStep = 1
        while (guard++ < 400) {
            val barStart = m.barStartTick(bar).coerceAtLeast(0)
            if (barStart > viewEnd + 1) break
            val barTicks = m.barTickLength(bar).coerceAtLeast(1)
            val widthPx = barTicks * tickScale
            if (widthPx > 0) {
                labelStep = max(1, ceil(stepPx / widthPx).toInt())
            }
            val x = tickToX(barStart)
            if (x in contentLeft..contentRight) {
                if (bar % labelStep == 0) {
                    strokePaint.color = colBar
                    strokePaint.strokeWidth = dp(1.2f)
                    canvas.drawLine(x, rulerHeight * 0.35f, x, rulerHeight, strokePaint)
                    strokePaint.strokeWidth = dp(1f)
                    boldTextPaint.color = colText
                    boldTextPaint.textSize = sp(9.5f)
                    val baseline = rulerHeight * 0.52f - (boldTextPaint.ascent() + boldTextPaint.descent()) / 2f
                    canvas.drawText("${bar + 1}", x + dp(3f), baseline, boldTextPaint)
                } else {
                    strokePaint.color = colGrid
                    canvas.drawLine(x, rulerHeight * 0.7f, x, rulerHeight, strokePaint)
                }
            }
            bar++
        }

        strokePaint.color = colBar
        canvas.drawLine(contentLeft, rulerHeight, contentRight, rulerHeight, strokePaint)

        val playX = tickToX(host?.playheadTick() ?: 0.0)
        if (playX in contentLeft..contentRight) {
            fillPaint.color = colPlayhead
            path.reset()
            path.moveTo(playX - dp(5f), 0f)
            path.lineTo(playX + dp(5f), 0f)
            path.lineTo(playX, dp(7f))
            path.close()
            canvas.drawPath(path, fillPaint)
            strokePaint.color = colPlayhead
            strokePaint.strokeWidth = dp(1.5f)
            canvas.drawLine(playX, 0f, playX, rulerHeight, strokePaint)
            strokePaint.strokeWidth = dp(1f)
        }
    }

    private fun drawLoopLane(canvas: Canvas, p: Project) {
        fillPaint.color = colPanelRaised
        canvas.drawRect(contentLeft, rulerHeight, contentRight, topHeight, fillPaint)
        val start: Int
        val end: Int
        if (loopDragging) {
            start = loopDragStart
            end = loopDragEnd
        } else {
            start = p.loopStartTick
            end = p.loopEndTick
        }
        if (end > start) {
            val x1 = tickToX(start)
            val x2 = tickToX(end)
            val left = max(contentLeft, x1)
            val right = min(contentRight, x2)
            if (right > left) {
                loopRect.set(left, rulerHeight + dp(1.5f), right, topHeight - dp(1.5f))
                fillPaint.color = if (p.loopEnabled || loopDragging) {
                    withAlpha(colLoop, 0.85f)
                } else {
                    withAlpha(colFaint, 0.7f)
                }
                canvas.drawRect(loopRect, fillPaint)
                if (x1 in contentLeft..contentRight) {
                    fillPaint.color = if (p.loopEnabled || loopDragging) colSelected else colDim
                    canvas.drawRect(x1 - dp(1.5f), rulerHeight, x1 + dp(1.5f), topHeight, fillPaint)
                }
                if (x2 in contentLeft..contentRight) {
                    fillPaint.color = if (p.loopEnabled || loopDragging) colSelected else colDim
                    canvas.drawRect(x2 - dp(1.5f), rulerHeight, x2 + dp(1.5f), topHeight, fillPaint)
                }
                if (right - left > dp(42f)) {
                    noteTextPaint.color = if (p.loopEnabled || loopDragging) 0xFF04201D.toInt() else colText
                    noteTextPaint.textSize = sp(7.5f)
                    val baseline = rulerHeight + loopLaneHeight / 2f -
                        (noteTextPaint.ascent() + noteTextPaint.descent()) / 2f
                    canvas.drawText("LOOP", left + dp(4f), baseline, noteTextPaint)
                }
            }
        }
        strokePaint.color = colBar
        canvas.drawLine(contentLeft, topHeight, contentRight, topHeight, strokePaint)
    }

    private fun drawKeyboard(canvas: Canvas, p: Project) {
        fillPaint.color = colPanel
        canvas.drawRect(0f, contentTop, contentLeft, contentBottom, fillPaint)
        val first = floor(lowestPitch).toInt().coerceAtLeast(0)
        val last = ceil(lowestPitch + visibleRows()).toInt().coerceAtMost(128)
        val highlight = keyPreviewPitch ?: hoverPitch?.takeIf { hoverActive }
        for (pitch in first until last) {
            val top = pitchToTop(pitch)
            if (top + rowPx < contentTop || top > contentBottom) continue
            val black = isBlackKey(pitch)
            fillPaint.color = if (black) colBlackKey else colWhiteKey
            canvas.drawRect(0f, top, contentLeft, top + rowPx, fillPaint)
            if (pitch == highlight) {
                fillPaint.color = withAlpha(colSelected, 0.45f)
                canvas.drawRect(0f, top, contentLeft, top + rowPx, fillPaint)
            }
            if (!black) {
                strokePaint.color = if (pitch % 12 == 0) colFaint else withAlpha(colFaint, 0.45f)
                canvas.drawLine(0f, top + rowPx, contentLeft, top + rowPx, strokePaint)
            }
            if (pitch % 12 == 0 && rowPx >= dp(13f)) {
                noteTextPaint.color = 0xFF303A45.toInt()
                noteTextPaint.textSize = min(sp(8.5f), rowPx * 0.5f).coerceAtLeast(sp(7f))
                val baseline = top + rowPx / 2f - (noteTextPaint.ascent() + noteTextPaint.descent()) / 2f
                canvas.drawText(noteName(pitch), dp(7f), baseline, noteTextPaint)
            }
        }
        strokePaint.color = colBar
        strokePaint.strokeWidth = dp(1.5f)
        canvas.drawLine(contentLeft, contentTop, contentLeft, contentBottom, strokePaint)
        strokePaint.strokeWidth = dp(1f)
    }

    private fun drawCorner(canvas: Canvas) {
        fillPaint.color = colPanel
        canvas.drawRect(0f, 0f, contentLeft, topHeight, fillPaint)
        strokePaint.color = colBar
        canvas.drawLine(0f, topHeight, contentLeft, topHeight, strokePaint)
        canvas.drawLine(contentLeft, 0f, contentLeft, topHeight, strokePaint)
        boldTextPaint.color = colDim
        boldTextPaint.textSize = sp(8.5f)
        boldTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("KEY", contentLeft / 2f, topHeight / 2f -
            (boldTextPaint.ascent() + boldTextPaint.descent()) / 2f, boldTextPaint)
        boldTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawScrollbars(canvas: Canvas, p: Project) {
        val contentW = contentRight - contentLeft
        val contentH = contentBottom - contentTop
        if (contentW <= 0 || contentH <= 0) return
        val alpha = if (SystemClock.uptimeMillis() < scrollHintUntil) 0.55f else 0.22f

        val totalTicks = max(
            p.songEndTick().toDouble() + p.ppq * 16.0,
            startTick + visibleTicks(),
        ).coerceAtLeast(1.0)
        val frac = (visibleTicks() / totalTicks).toFloat().coerceIn(0.03f, 1f)
        val thumbW = (contentW * frac).coerceAtLeast(dp(18f))
        val maxScroll = (totalTicks - visibleTicks()).coerceAtLeast(1.0)
        val scrollFrac = (startTick / maxScroll).toFloat().coerceIn(0f, 1f)
        val thumbX = contentLeft + (contentW - thumbW) * scrollFrac
        fillPaint.color = withAlpha(colText, alpha)
        tmpRect.set(thumbX, contentBottom - dp(4f), thumbX + thumbW, contentBottom - dp(1f))
        canvas.drawRoundRect(tmpRect, dp(1.5f), dp(1.5f), fillPaint)

        val rows = visibleRows()
        val totalRows = 128f
        val vFrac = (rows / totalRows).coerceIn(0.05f, 1f)
        val thumbH = (contentH * vFrac).coerceAtLeast(dp(18f))
        val maxLow = (128f - rows).coerceAtLeast(1f)
        val vScroll = (lowestPitch / maxLow).coerceIn(0f, 1f)
        val thumbY = contentTop + (contentH - thumbH) * (1f - vScroll)
        fillPaint.color = withAlpha(colText, alpha)
        tmpRect.set(contentRight - dp(4f), thumbY, contentRight - dp(1f), thumbY + thumbH)
        canvas.drawRoundRect(tmpRect, dp(1.5f), dp(1.5f), fillPaint)
    }

    private fun drawDragHud(canvas: Canvas, p: Project) {
        if (mode != Mode.DRAW && mode != Mode.DRAG_MOVE && mode != Mode.DRAG_RESIZE) return
        val note = when (mode) {
            Mode.DRAW -> Note(
                id = -1,
                pitch = drawPitch,
                startTick = drawStartTick,
                durationTicks = max(1, drawEndTick - drawStartTick),
                velocity = prefs.inputVelocity,
                channel = p.track.channel,
            )
            else -> dragPreview?.firstOrNull { it.id == downNoteId } ?: dragPrimary
        } ?: return

        val bar = meter?.positionAt(note.startTick)?.label() ?: ""
        val count = if (mode == Mode.DRAG_MOVE || mode == Mode.DRAG_RESIZE) {
            max(1, dragOriginals.size)
        } else 0
        val line1 = if (count > 1) {
            "${noteName(note.pitch)}  v${note.velocity}  (${count})"
        } else {
            "${noteName(note.pitch)}  v${note.velocity}"
        }
        val line2 = "$bar   ${note.durationTicks}t"
        boldTextPaint.textSize = sp(11.5f)
        val textW = max(
            boldTextPaint.measureText(line1),
            smallTextPaint.measureText(line2),
        )
        val hudW = textW + dp(20f)
        val hudH = dp(40f)
        var left = lastX - hudW / 2f
        left = left.coerceIn(contentLeft + dp(4f), max(contentLeft + dp(4f), contentRight - hudW - dp(4f)))
        var top = lastY - hudH - dp(18f)
        if (top < contentTop + dp(4f)) top = lastY + dp(22f)
        top = top.coerceIn(contentTop + dp(4f), max(contentTop + dp(4f), contentBottom - hudH - dp(4f)))
        hudRect.set(left, top, left + hudW, top + hudH)
        fillPaint.color = withAlpha(0xFF0A0E12.toInt(), 0.93f)
        canvas.drawRoundRect(hudRect, dp(8f), dp(8f), fillPaint)
        strokePaint.color = withAlpha(colSelected, 0.7f)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(hudRect, dp(8f), dp(8f), strokePaint)
        boldTextPaint.color = colText
        boldTextPaint.textSize = sp(11.5f)
        canvas.drawText(
            line1,
            hudRect.left + dp(10f),
            hudRect.top + dp(15f) - (boldTextPaint.ascent() + boldTextPaint.descent()) / 2f,
            boldTextPaint,
        )
        smallTextPaint.color = colDim
        smallTextPaint.textSize = sp(9.5f)
        canvas.drawText(
            line2,
            hudRect.left + dp(10f),
            hudRect.top + dp(30f) - (smallTextPaint.ascent() + smallTextPaint.descent()) / 2f,
            smallTextPaint,
        )
    }

    private fun drawEmptyHint(canvas: Canvas, p: Project) {
        if (p.notes.isNotEmpty() || dragPreview != null || mode == Mode.DRAW ||
            (hoverActive && hoverNoteId == null && ghostDuration > 0)
        ) return
        val cx = (contentLeft + contentRight) / 2f
        val cy = (contentTop + contentBottom) / 2f
        if (contentRight - contentLeft < dp(180f)) return
        textPaint.color = withAlpha(colDim, 0.85f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = sp(13f)
        canvas.drawText("タップで音符を追加", cx, cy - dp(8f), textPaint)
        textPaint.textSize = sp(11f)
        textPaint.color = withAlpha(colFaint, 0.95f)
        canvas.drawText("ペンでドラッグして長さを指定 / 2本指でスクロール・ズーム", cx, cy + dp(14f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun noteRect(note: Note, out: RectF): RectF {
        val x1 = tickToX(note.startTick)
        val x2 = max(x1 + dp(3f), tickToX(note.endTick))
        val top = pitchToTop(note.pitch)
        out.set(x1, top, x2, top + rowPx)
        return out
    }

    private fun velocityColor(velocity: Int): Int {
        val v = (velocity.coerceIn(1, 127) / 127f)
        val dark = 0xFF8C5B1F.toInt()
        val light = 0xFFF6C77B.toInt()
        return blend(dark, light, 0.25f + v * 0.75f)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return Color.rgb(
            (ar + (br - ar) * f).roundToInt(),
            (ag + (bg - ag) * f).roundToInt(),
            (ab + (bb - ab) * f).roundToInt(),
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        const val MIN_DP_PER_TICK = 0.018f
        const val MAX_DP_PER_TICK = 2.2f
        const val MIN_ROW_HEIGHT = 8f
        const val MAX_ROW_HEIGHT = 64f
    }
}
