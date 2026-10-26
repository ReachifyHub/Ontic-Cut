package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.utils.performAppHapticFeedback

/**
 * Lightweight, high-performance timeline track view for auto-captions.
 * Renders all caption cues on a single track lane on the timeline without
 * creating hundreds of individual Android views.
 */
class CaptionTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var totalDurationMs: Long = 0L
    var customMsPerPixel: Float? = null
    var captionOperations: List<EditOperation.AddText> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedOperationId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onCaptionClicked: ((String) -> Unit)? = null
    var onCaptionTrimChanged: ((EditOperation.AddText, Long, Long) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1929") // Dark purple-tinted track background
        style = Paint.Style.FILL
    }

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#673AB7") // Deep Purple
        style = Paint.Style.FILL
    }

    private val cueBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9575CD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val selectedCuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E57C2")
        style = Paint.Style.FILL
    }

    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F") // Amber highlight border
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

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#512DA8")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
    }

    private var isDragging = false
    private var dragTarget: DragTarget = DragTarget.NONE
    private var draggingOp: EditOperation.AddText? = null
    private var dragStartX = 0f
    private var initialTrimStart = 0L
    private var initialTrimEnd = 0L

    private enum class DragTarget { NONE, LEFT_HANDLE, RIGHT_HANDLE, BODY }

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

        // 1. Draw track lane background
        val trackRect = RectF(0f, 2f, w, h - 2f)
        canvas.drawRoundRect(trackRect, 8f, 8f, bgPaint)

        val pxPerMs = getPxPerMs()
        val cornerRadius = 6f
        val pillMarginY = 6f

        // 2. Draw all caption cue blocks
        for (op in captionOperations) {
            val startMs = op.startTimeMs ?: 0L
            val endMs = op.endTimeMs ?: (startMs + 1000L)
            val isSelected = (op.id == selectedOperationId)

            val left = (startMs * pxPerMs).coerceAtLeast(0f)
            val right = (endMs * pxPerMs).coerceAtLeast(left + 12f)
            val cueRect = RectF(left, pillMarginY, right, h - pillMarginY)

            // Fill & Border
            val fillPaint = if (isSelected) selectedCuePaint else cuePaint
            canvas.drawRoundRect(cueRect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(cueRect, cornerRadius, cornerRadius, if (isSelected) selectionBorderPaint else cueBorderPaint)

            // Text content (single line clipped)
            val cleanText = op.text.replace("\n", " ").trim()
            if (cleanText.isNotEmpty() && (right - left) > 30f) {
                canvas.save()
                canvas.clipRect(cueRect.left + 8f, cueRect.top, cueRect.right - 8f, cueRect.bottom)
                val textY = cueRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(cleanText, cueRect.left + 10f, textY, textPaint)
                canvas.restore()
            }

            // Draw handles if selected
            if (isSelected) {
                val handleRadius = 5f
                // Left handle
                canvas.drawRoundRect(RectF(left - 2f, pillMarginY + 8f, left + 4f, h - pillMarginY - 8f), handleRadius, handleRadius, handlePaint)
                // Right handle
                canvas.drawRoundRect(RectF(right - 4f, pillMarginY + 8f, right + 2f, h - pillMarginY - 8f), handleRadius, handleRadius, handlePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val pxPerMs = getPxPerMs()
        val touchX = event.x

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = touchX
                val selectedOp = captionOperations.find { it.id == selectedOperationId }

                // Check if tapped handles of selected cue first
                if (selectedOp != null) {
                    val startX = (selectedOp.startTimeMs ?: 0L) * pxPerMs
                    val endX = (selectedOp.endTimeMs ?: 0L) * pxPerMs

                    if (kotlin.math.abs(touchX - startX) <= handleTouchSlopPx) {
                        isDragging = true
                        dragTarget = DragTarget.LEFT_HANDLE
                        draggingOp = selectedOp
                        initialTrimStart = selectedOp.startTimeMs ?: 0L
                        initialTrimEnd = selectedOp.endTimeMs ?: 0L
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStateChanged?.invoke(true)
                        performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    } else if (kotlin.math.abs(touchX - endX) <= handleTouchSlopPx) {
                        isDragging = true
                        dragTarget = DragTarget.RIGHT_HANDLE
                        draggingOp = selectedOp
                        initialTrimStart = selectedOp.startTimeMs ?: 0L
                        initialTrimEnd = selectedOp.endTimeMs ?: 0L
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStateChanged?.invoke(true)
                        performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    }
                }

                // Check which cue was tapped
                val touchedCue = findCueAtX(touchX)
                if (touchedCue != null) {
                    performAppHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onCaptionClicked?.invoke(touchedCue.id)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging && draggingOp != null) {
                    val deltaX = touchX - dragStartX
                    val msPerPx = getMsPerPx()
                    val deltaMs = (deltaX * msPerPx).toLong()

                    val op = draggingOp!!
                    var newStart = initialTrimStart
                    var newEnd = initialTrimEnd

                    when (dragTarget) {
                        DragTarget.LEFT_HANDLE -> {
                            newStart = (initialTrimStart + deltaMs).coerceIn(0L, initialTrimEnd - 200L)
                        }
                        DragTarget.RIGHT_HANDLE -> {
                            val maxLimit = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                            newEnd = (initialTrimEnd + deltaMs).coerceIn(initialTrimStart + 200L, maxLimit)
                        }
                        else -> {}
                    }

                    onCaptionTrimChanged?.invoke(op, newStart, newEnd)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    dragTarget = DragTarget.NONE
                    draggingOp = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragStateChanged?.invoke(false)
                    invalidate()
                    return true
                }
            }
        }

        return super.onTouchEvent(event)
    }

    private fun findCueAtX(x: Float): EditOperation.AddText? {
        val pxPerMs = getPxPerMs()
        for (op in captionOperations) {
            val left = (op.startTimeMs ?: 0L) * pxPerMs - 6f
            val right = (op.endTimeMs ?: 0L) * pxPerMs + 6f
            if (x in left..right) {
                return op
            }
        }
        return null
    }
}
