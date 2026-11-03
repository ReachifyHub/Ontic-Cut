package com.boostlyte.vydra.render.gl

/**
 * Pure mathematical transform calculations for sub-pixel Ken Burns motion effects.
 *
 * All coordinates are normalized UV texture coordinates in [0.0, 1.0].
 * Eliminates CPU zoompan integer pixel stepping by computing floating-point
 * coordinates that map directly into OpenGL ES texture matrices and vertex arrays.
 */
object GlMotionTransform {

    /**
     * UV texture coordinates bounding box for sampling the source image.
     */
    data class CropRect(
        val uMin: Float,
        val vMin: Float,
        val uMax: Float,
        val vMax: Float
    ) {
        val width: Float get() = uMax - uMin
        val height: Float get() = vMax - vMin
        val centerX: Float get() = (uMin + uMax) / 2f
        val centerY: Float get() = (vMin + vMax) / 2f

        init {
            require(uMin in 0f..1f) { "uMin out of bounds: $uMin" }
            require(uMax in 0f..1f) { "uMax out of bounds: $uMax" }
            require(vMin in 0f..1f) { "vMin out of bounds: $vMin" }
            require(vMax in 0f..1f) { "vMax out of bounds: $vMax" }
            require(uMax > uMin) { "uMax must be greater than uMin" }
            require(vMax > vMin) { "vMax must be greater than vMin" }
        }
    }

    // Maximum zoom scale applied during Ken Burns (15% motion headroom, matches legacy FFmpeg 1.15)
    const val ZOOM_FACTOR = 1.15f

    /**
     * Computes the normalized UV crop rectangle at progress t in [0.0, 1.0].
     *
     * @param motionType One of "zoom_in", "zoom_out", "pan_left", "pan_right", "pan_up", "pan_down", "drift_up_right", or null
     * @param progress Normalized playback progress in [0.0, 1.0]
     * @param srcWidth Source image width in pixels
     * @param srcHeight Source image height in pixels
     * @param targetWidth Output video width in pixels
     * @param targetHeight Output video height in pixels
     */
    fun computeCropRect(
        motionType: String?,
        progress: Float,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): CropRect {
        require(srcWidth > 0 && srcHeight > 0) { "Source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }

        val t = progress.coerceIn(0.0f, 1.0f)
        val aspectSrc = srcWidth.toFloat() / srcHeight.toFloat()
        val aspectTarget = targetWidth.toFloat() / targetHeight.toFloat()

        // 1. Base cover box in UV space (center-cropped to match target aspect ratio with 0 black bars)
        val baseWidthUV: Float
        val baseHeightUV: Float

        if (aspectSrc > aspectTarget) {
            // Source is wider than target: fit height, crop width
            baseHeightUV = 1.0f
            baseWidthUV = aspectTarget / aspectSrc
        } else {
            // Source is taller than target: fit width, crop height
            baseWidthUV = 1.0f
            baseHeightUV = aspectSrc / aspectTarget
        }

        // Window size for zoomed-in state (1.0 / ZOOM_FACTOR of base size)
        val zoomedWidthUV = baseWidthUV / ZOOM_FACTOR
        val zoomedHeightUV = baseHeightUV / ZOOM_FACTOR

        // Motion margins available for panning
        val marginX = (baseWidthUV - zoomedWidthUV) / 2f
        val marginY = (baseHeightUV - zoomedHeightUV) / 2f

        val currentW: Float
        val currentH: Float
        val centerX: Float
        val centerY: Float

        when (motionType) {
            "zoom_in" -> {
                // Starts at full base cover, smoothly zooms in to 1.15x
                val scale = 1.0f - (1.0f - 1.0f / ZOOM_FACTOR) * t
                currentW = baseWidthUV * scale
                currentH = baseHeightUV * scale
                centerX = 0.5f
                centerY = 0.5f
            }
            "zoom_out" -> {
                // Starts at 1.15x zoomed, smoothly zooms out to full base cover
                val scale = (1.0f / ZOOM_FACTOR) + (1.0f - 1.0f / ZOOM_FACTOR) * t
                currentW = baseWidthUV * scale
                currentH = baseHeightUV * scale
                centerX = 0.5f
                centerY = 0.5f
            }
            "pan_left" -> {
                // Fixed zoom at 1.15x, camera moves right so subject appears to pan left
                currentW = zoomedWidthUV
                currentH = zoomedHeightUV
                centerX = (0.5f - marginX) + (2f * marginX) * t
                centerY = 0.5f
            }
            "pan_right" -> {
                // Fixed zoom at 1.15x, camera moves left so subject appears to pan right
                currentW = zoomedWidthUV
                currentH = zoomedHeightUV
                centerX = (0.5f + marginX) - (2f * marginX) * t
                centerY = 0.5f
            }
            "pan_up" -> {
                // Fixed zoom at 1.15x, camera moves down so subject appears to pan up
                currentW = zoomedWidthUV
                currentH = zoomedHeightUV
                centerX = 0.5f
                centerY = (0.5f - marginY) + (2f * marginY) * t
            }
            "pan_down" -> {
                // Fixed zoom at 1.15x, camera moves up so subject appears to pan down
                currentW = zoomedWidthUV
                currentH = zoomedHeightUV
                centerX = 0.5f
                centerY = (0.5f + marginY) - (2f * marginY) * t
            }
            "drift_up_right" -> {
                // Fixed zoom at 1.15x, camera moves down-left
                currentW = zoomedWidthUV
                currentH = zoomedHeightUV
                centerX = (0.5f + marginX) - (2f * marginX) * t
                centerY = (0.5f - marginY) + (2f * marginY) * t
            }
            else -> {
                // Static center cover
                currentW = baseWidthUV
                currentH = baseHeightUV
                centerX = 0.5f
                centerY = 0.5f
            }
        }

        val halfW = currentW / 2f
        val halfH = currentH / 2f

        val uMin = (centerX - halfW).coerceIn(0f, 1f - currentW)
        val uMax = (uMin + currentW).coerceIn(uMin + 0.001f, 1f)
        val vMin = (centerY - halfH).coerceIn(0f, 1f - currentH)
        val vMax = (vMin + currentH).coerceIn(vMin + 0.001f, 1f)

        return CropRect(uMin, vMin, uMax, vMax)
    }

    /**
     * Converts a CropRect into an 8-float OpenGL texture coordinate array (quad).
     * Standard orientation: (uMin, vMin) at top-left, (uMax, vMax) at bottom-right.
     */
    fun toTextureCoordinates(cropRect: CropRect): FloatArray {
        return floatArrayOf(
            cropRect.uMin, cropRect.vMax, // Bottom-left
            cropRect.uMax, cropRect.vMax, // Bottom-right
            cropRect.uMin, cropRect.vMin, // Top-left
            cropRect.uMax, cropRect.vMin  // Top-right
        )
    }

    /**
     * Computes the effective pixel aspect ratio of the crop window.
     * Should equal targetWidth / targetHeight.
     */
    fun computeEffectiveAspectRatio(cropRect: CropRect, srcWidth: Int, srcHeight: Int): Float {
        val pixelWidth = cropRect.width * srcWidth
        val pixelHeight = cropRect.height * srcHeight
        return pixelWidth / pixelHeight
    }
}
