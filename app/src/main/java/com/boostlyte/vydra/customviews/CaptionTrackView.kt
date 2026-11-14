package com.boostlyte.vydra.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.SubtitleCue
import com.boostlyte.vydra.utils.performAppHapticFeedback

/**
 * Lightweight, high-performance timeline track view for unified subtitles (both Auto Captions
 * and imported .srt files). Renders all cues on a single timeline lane without creating
 * hundreds of individual Android views.
 */
class CaptionTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class CachedCueLayout(
        val index: Int,
        val cue: SubtitleCue,
        val startMs: Long,
        val endMs: Long,
        val cleanText: String,
        val textYOffset: Float
    )

    private var cachedCueLayouts: MutableList<CachedCueLayout> = mutableListOf()

    var totalDurationMs: Long = 0L
    var customMsPerPixel: Float? = null

    var subtitleOperation: EditOperation.AddSubtitles? = null
        set(value) {
            field = value
            rebuildCueCache()
            invalidate()
        }

    var selectedCueIndex: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var currentPlayheadMs: Long = -1L
        private set
    var activeCueIndex: Int = -1
        private set

    /**
     * Updates playhead position. Invalidates ONLY when the playhead crosses a cue boundary.
     */
    fun updatePlayhead(playheadMs: Long) {
        currentPlayheadMs = playheadMs
        val activeCue = cachedCueLayouts.firstOrNull { playheadMs in it.startMs..it.endMs }
        val newActiveIdx = activeCue?.index ?: -1
        if (newActiveIdx != activeCueIndex) {
            activeCueIndex = newActiveIdx
            invalidate()
        }
    }

    private fun rebuildCueCache() {
        val op = subtitleOperation
        if (op?.fontPath != null) {
            try {
                textPaint.typeface = android.graphics.Typeface.createFromFile(op.fontPath)
            } catch (_: Exception) {
                textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        } else {
            textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        if (op != null && op.fontSize > 0) {
            textPaint.textSize = op.fontSize.toFloat().coerceIn(20f, 32f)
        } else {
            textPaint.textSize = 28f
        }
        val textYOffset = -((textPaint.descent() + textPaint.ascent()) / 2f)
        cachedCueLayouts = op?.cues?.mapIndexed { idx, cue ->
            val startMs = cue.startTimeMs.coerceAtLeast(0L)
            val endMs = cue.endTimeMs.coerceAtLeast(startMs + 200L)
            val clean = cue.text.replace("\r\n", " ").replace("\n", " ").trim()
            CachedCueLayout(idx, cue, startMs, endMs, clean, textYOffset)
        }?.toMutableList() ?: mutableListOf()

        if (selectedCueIndex >= cachedCueLayouts.size) {
            selectedCueIndex = -1
        }
    }

    var onCueClicked: ((Int, SubtitleCue) -> Unit)? = null
    var onCueTrimCommitted: ((Int, SubtitleCue, Long, Long) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    // ── Pre-allocated Paints (Cached) ──────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1929")
        style = Paint.Style.FILL
    }

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#673AB7")
        style = Paint.Style.FILL
    }

    private val cueBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9575CD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val activeCueBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val selectedCuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E57C2")
        style = Paint.Style.FILL
    }

    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }

    // ── Reusable Scratch Rects (Zero-Allocation onDraw) ────────────────────────
    private val scratchTrackRect = RectF()
    private val scratchCueRect = RectF()
    private val scratchHandleRect = RectF()

    private var isDragging = false
    private var dragTarget: DragTarget = DragTarget.NONE
    private var draggingCueIndex: Int = -1
    private var dragStartX = 0f
    private var initialTrimStart = 0L
    private var initialTrimEnd = 0L
    private var pendingTrimStart = 0L
    private var pendingTrimEnd = 0L

    private enum class DragTarget { NONE, LEFT_HANDLE, RIGHT_HANDLE }

    private val handleTouchSlopPx = 36f

    private fun getMsPerPx(): Float {
        return customMsPerPixel ?: if (width > 0 && totalDurationMs > 0) totalDurationMs.toFloat() / width.toFloat() else 10f
    }

    private fun getPxPerMs(): Float {
        val msPerPx = getMsPerPx()
        return if (msPerPx > 0f) 1f / msPerPx else 0.1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        scratchTrackRect.set(0f, 2f, w, h - 2f)
        canvas.drawRoundRect(scratchTrackRect, 8f, 8f, bgPaint)

        val pxPerMs = getPxPerMs()
        val cornerRadius = 6f
        val pillMarginY = 6f

        val clipLeft = canvas.clipBounds.left.toFloat()
        val clipRight = canvas.clipBounds.right.toFloat()

        for (cached in cachedCueLayouts) {
            val left = (cached.startMs * pxPerMs).coerceAtLeast(0f)
            val right = (cached.endMs * pxPerMs).coerceAtLeast(left + 12f)
            if (right < clipLeft - 50f || left > clipRight + 50f) continue

            val isSelected = (cached.index == selectedCueIndex)
            val isActive = (cached.index == activeCueIndex)
            scratchCueRect.set(left, pillMarginY, right, h - pillMarginY)

            val fillPaint = if (isSelected) selectedCuePaint else cuePaint
            val borderPaint = when {
                isSelected -> selectionBorderPaint
                isActive -> activeCueBorderPaint
                else -> cueBorderPaint
            }
            canvas.drawRoundRect(scratchCueRect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(scratchCueRect, cornerRadius, cornerRadius, borderPaint)

            if (cached.cleanText.isNotEmpty() && (right - left) > 30f) {
                canvas.save()
                canvas.clipRect(scratchCueRect.left + 8f, scratchCueRect.top, scratchCueRect.right - 8f, scratchCueRect.bottom)
                val textY = scratchCueRect.centerY() + cached.textYOffset
                canvas.drawText(cached.cleanText, scratchCueRect.left + 10f, textY, textPaint)
                canvas.restore()
            }

            if (isSelected) {
                val handleRadius = 5f
                scratchHandleRect.set(left - 2f, pillMarginY + 8f, left + 4f, h - pillMarginY - 8f)
                canvas.drawRoundRect(scratchHandleRect, handleRadius, handleRadius, handlePaint)
                scratchHandleRect.set(right - 4f, pillMarginY + 8f, right + 2f, h - pillMarginY - 8f)
                canvas.drawRoundRect(scratchHandleRect, handleRadius, handleRadius, handlePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pxPerMs = getPxPerMs()
        val touchX = event.x

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = touchX
                val selectedCached = cachedCueLayouts.getOrNull(selectedCueIndex)

                if (selectedCached != null) {
                    val startX = selectedCached.startMs * pxPerMs
                    val endX = selectedCached.endMs * pxPerMs

                    if (kotlin.math.abs(touchX - startX) <= handleTouchSlopPx) {
                        isDragging = true
                        dragTarget = DragTarget.LEFT_HANDLE
                        draggingCueIndex = selectedCached.index
                        initialTrimStart = selectedCached.startMs
                        initialTrimEnd = selectedCached.endMs
                        pendingTrimStart = initialTrimStart
                        pendingTrimEnd = initialTrimEnd
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStateChanged?.invoke(true)
                        performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    } else if (kotlin.math.abs(touchX - endX) <= handleTouchSlopPx) {
                        isDragging = true
                        dragTarget = DragTarget.RIGHT_HANDLE
                        draggingCueIndex = selectedCached.index
                        initialTrimStart = selectedCached.startMs
                        initialTrimEnd = selectedCached.endMs
                        pendingTrimStart = initialTrimStart
                        pendingTrimEnd = initialTrimEnd
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStateChanged?.invoke(true)
                        performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    }
                }

                val touchedCue = findCueAtX(touchX)
                if (touchedCue != null) {
                    performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onCueClicked?.invoke(touchedCue.index, touchedCue.cue)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && draggingCueIndex in cachedCueLayouts.indices) {
                    val deltaX = touchX - dragStartX
                    val msPerPx = getMsPerPx()
                    val deltaMs = (deltaX * msPerPx).toLong()

                    val minBound = if (draggingCueIndex > 0) cachedCueLayouts[draggingCueIndex - 1].endMs else 0L
                    val maxBound = if (draggingCueIndex + 1 < cachedCueLayouts.size) {
                        cachedCueLayouts[draggingCueIndex + 1].startMs
                    } else if (totalDurationMs > 0L) {
                        totalDurationMs
                    } else {
                        Long.MAX_VALUE
                    }

                    when (dragTarget) {
                        DragTarget.LEFT_HANDLE -> {
                            val upper = (initialTrimEnd - 200L).coerceAtLeast(minBound)
                            pendingTrimStart = (initialTrimStart + deltaMs).coerceIn(minBound, upper)
                            pendingTrimEnd = initialTrimEnd
                        }
                        DragTarget.RIGHT_HANDLE -> {
                            val lower = (initialTrimStart + 200L).coerceAtMost(maxBound)
                            pendingTrimStart = initialTrimStart
                            pendingTrimEnd = (initialTrimEnd + deltaMs).coerceIn(lower, maxBound)
                        }
                        else -> {}
                    }

                    val current = cachedCueLayouts[draggingCueIndex]
                    cachedCueLayouts[draggingCueIndex] = current.copy(
                        startMs = pendingTrimStart,
                        endMs = pendingTrimEnd
                    )
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val idx = draggingCueIndex
                    val committedStart = pendingTrimStart
                    val committedEnd = pendingTrimEnd
                    isDragging = false
                    dragTarget = DragTarget.NONE
                    draggingCueIndex = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragStateChanged?.invoke(false)
                    if (idx in cachedCueLayouts.indices && (committedStart != initialTrimStart || committedEnd != initialTrimEnd)) {
                        onCueTrimCommitted?.invoke(idx, cachedCueLayouts[idx].cue, committedStart, committedEnd)
                    }
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findCueAtX(x: Float): CachedCueLayout? {
        val pxPerMs = getPxPerMs()
        for (cached in cachedCueLayouts) {
            val left = cached.startMs * pxPerMs - 6f
            val right = cached.endMs * pxPerMs + 6f
            if (x in left..right) {
                return cached
            }
        }
        return null
    }
}
