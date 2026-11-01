package com.boostlyte.vydra.customviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class TransitionPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var transitionType: String = "none"
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null
    private var snapshotBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()

    fun updateTransition(type: String, prog: Float, fromBitmap: Bitmap?, toBitmap: Bitmap?) {
        this.transitionType = type.lowercase()
        this.progress = prog.coerceIn(0f, 1f)
        this.bitmapA = fromBitmap
        this.bitmapB = toBitmap
        this.snapshotBitmap = null
        invalidate()
    }

    fun updateTransition(type: String, prog: Float, bitmap: Bitmap?) {
        this.transitionType = type.lowercase()
        this.progress = prog.coerceIn(0f, 1f)
        this.bitmapA = bitmap
        this.bitmapB = null
        this.snapshotBitmap = bitmap
        invalidate()
    }

    fun clearSnapshot() {
        this.bitmapA = null
        this.bitmapB = null
        this.snapshotBitmap = null
        this.transitionType = "none"
        this.progress = 0f
        invalidate()
    }

    private fun drawFitBitmap(canvas: Canvas, bmp: Bitmap?, dst: RectF, alpha: Int = 255) {
        if (bmp == null || bmp.isRecycled) return
        try {
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            paint.alpha = alpha.coerceIn(0, 255)
            srcRect.set(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, dst, paint)
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmpA = if (bitmapA != null && !bitmapA!!.isRecycled) bitmapA else (if (snapshotBitmap != null && !snapshotBitmap!!.isRecycled) snapshotBitmap else null)
        val bmpB = if (bitmapB != null && !bitmapB!!.isRecycled) bitmapB else null

        if ((bmpA == null && bmpB == null) || transitionType == "none") {
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        dstRect.set(0f, 0f, w, h)

        if (progress <= 0f) {
            drawFitBitmap(canvas, bmpA, dstRect, 255)
            return
        }
        if (progress >= 1f) {
            drawFitBitmap(canvas, bmpB ?: bmpA, dstRect, 255)
            return
        }

        when (transitionType) {
            "fade", "dissolve" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                drawFitBitmap(canvas, bmpB, dstRect, (progress * 255).toInt())
            }
            "fadeblack" -> {
                if (progress < 0.5f) {
                    drawFitBitmap(canvas, bmpA, dstRect, 255)
                    val alpha = (progress * 2f * 255).toInt().coerceIn(0, 255)
                    paint.reset()
                    paint.color = Color.BLACK
                    paint.alpha = alpha
                    canvas.drawRect(dstRect, paint)
                } else {
                    drawFitBitmap(canvas, bmpB ?: bmpA, dstRect, 255)
                    val alpha = ((1f - progress) * 2f * 255).toInt().coerceIn(0, 255)
                    paint.reset()
                    paint.color = Color.BLACK
                    paint.alpha = alpha
                    canvas.drawRect(dstRect, paint)
                }
            }
            "fadewhite" -> {
                if (progress < 0.5f) {
                    drawFitBitmap(canvas, bmpA, dstRect, 255)
                    val alpha = (progress * 2f * 255).toInt().coerceIn(0, 255)
                    paint.reset()
                    paint.color = Color.WHITE
                    paint.alpha = alpha
                    canvas.drawRect(dstRect, paint)
                } else {
                    drawFitBitmap(canvas, bmpB ?: bmpA, dstRect, 255)
                    val alpha = ((1f - progress) * 2f * 255).toInt().coerceIn(0, 255)
                    paint.reset()
                    paint.color = Color.WHITE
                    paint.alpha = alpha
                    canvas.drawRect(dstRect, paint)
                }
            }
            "wipeleft" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.clipRect(w * (1f - progress), 0f, w, h)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "wiperight" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.clipRect(0f, 0f, w * progress, h)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "wipeup" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.clipRect(0f, h * (1f - progress), w, h)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "wipedown" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.clipRect(0f, 0f, w, h * progress)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "slideleft" -> {
                canvas.save()
                canvas.translate(-w * progress, 0f)
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.restore()

                canvas.save()
                canvas.translate(w * (1f - progress), 0f)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "slideright" -> {
                canvas.save()
                canvas.translate(w * progress, 0f)
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.restore()

                canvas.save()
                canvas.translate(-w * (1f - progress), 0f)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "slideup" -> {
                canvas.save()
                canvas.translate(0f, -h * progress)
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.restore()

                canvas.save()
                canvas.translate(0f, h * (1f - progress))
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "slidedown" -> {
                canvas.save()
                canvas.translate(0f, h * progress)
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.restore()

                canvas.save()
                canvas.translate(0f, -h * (1f - progress))
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "coverleft" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.translate(w * (1f - progress), 0f)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "coverright" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                canvas.translate(-w * (1f - progress), 0f)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "circlecrop" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                val maxRadius = Math.hypot((w / 2).toDouble(), (h / 2).toDouble()).toFloat()
                val radius = maxRadius * progress
                path.reset()
                path.addCircle(w / 2f, h / 2f, radius, Path.Direction.CW)
                canvas.clipPath(path)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "rectcrop" -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                canvas.save()
                val halfW = (w / 2f) * progress
                val halfH = (h / 2f) * progress
                canvas.clipRect(w / 2f - halfW, h / 2f - halfH, w / 2f + halfW, h / 2f + halfH)
                drawFitBitmap(canvas, bmpB, dstRect, 255)
                canvas.restore()
            }
            "zoomin" -> {
                canvas.save()
                val scaleA = 1f + progress * 0.2f
                canvas.scale(scaleA, scaleA, w / 2f, h / 2f)
                drawFitBitmap(canvas, bmpA, dstRect, ((1f - progress) * 255).toInt())
                canvas.restore()

                canvas.save()
                val scaleB = 0.8f + progress * 0.2f
                canvas.scale(scaleB, scaleB, w / 2f, h / 2f)
                drawFitBitmap(canvas, bmpB, dstRect, (progress * 255).toInt())
                canvas.restore()
            }
            else -> {
                drawFitBitmap(canvas, bmpA, dstRect, 255)
                drawFitBitmap(canvas, bmpB, dstRect, (progress * 255).toInt())
            }
        }
    }
}
