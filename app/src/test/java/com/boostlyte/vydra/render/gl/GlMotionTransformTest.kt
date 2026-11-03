package com.boostlyte.vydra.render.gl

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GlMotionTransform math.
 * Verifies sub-pixel precision, continuous motion interpolation, aspect ratio preservation,
 * and boundary safety across all motion types.
 */
class GlMotionTransformTest {

    private val EPSILON = 0.001f

    @Test
    fun testZoomIn_smoothContinuousProgression() {
        val srcW = 1920
        val srcH = 1080
        val targetW = 1080
        val targetH = 1920 // 9:16 portrait target

        val rect0 = GlMotionTransform.computeCropRect("zoom_in", 0.0f, srcW, srcH, targetW, targetH)
        val rectMid = GlMotionTransform.computeCropRect("zoom_in", 0.5f, srcW, srcH, targetW, targetH)
        val rect1 = GlMotionTransform.computeCropRect("zoom_in", 1.0f, srcW, srcH, targetW, targetH)

        // As t increases, window size shrinks (camera zooms into image)
        assertTrue("Width must shrink continuously", rect0.width > rectMid.width)
        assertTrue("Width must shrink continuously", rectMid.width > rect1.width)
        assertTrue("Height must shrink continuously", rect0.height > rectMid.height)
        assertTrue("Height must shrink continuously", rectMid.height > rect1.height)

        // Center must remain stable at (0.5, 0.5)
        assertEquals(0.5f, rect0.centerX, EPSILON)
        assertEquals(0.5f, rectMid.centerX, EPSILON)
        assertEquals(0.5f, rect1.centerX, EPSILON)

        // Aspect ratio must match target aspect ratio exactly throughout
        val expectedRatio = targetW.toFloat() / targetH.toFloat()
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rect0, srcW, srcH), EPSILON)
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rectMid, srcW, srcH), EPSILON)
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rect1, srcW, srcH), EPSILON)
    }

    @Test
    fun testZoomOut_smoothContinuousProgression() {
        val srcW = 1376
        val srcH = 768
        val targetW = 1920
        val targetH = 1080 // 16:9 landscape target

        val rect0 = GlMotionTransform.computeCropRect("zoom_out", 0.0f, srcW, srcH, targetW, targetH)
        val rectMid = GlMotionTransform.computeCropRect("zoom_out", 0.5f, srcW, srcH, targetW, targetH)
        val rect1 = GlMotionTransform.computeCropRect("zoom_out", 1.0f, srcW, srcH, targetW, targetH)

        // As t increases, window size expands (camera zooms out)
        assertTrue("Width must expand continuously", rect0.width < rectMid.width)
        assertTrue("Width must expand continuously", rectMid.width < rect1.width)
        assertTrue("Height must expand continuously", rect0.height < rectMid.height)
        assertTrue("Height must expand continuously", rectMid.height < rect1.height)

        val expectedRatio = targetW.toFloat() / targetH.toFloat()
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rect0, srcW, srcH), EPSILON)
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rectMid, srcW, srcH), EPSILON)
        assertEquals(expectedRatio, GlMotionTransform.computeEffectiveAspectRatio(rect1, srcW, srcH), EPSILON)
    }

    @Test
    fun testPanHorizontally_maintainsSizeAndPreservesAspect() {
        val srcW = 1920
        val srcH = 1080
        val targetW = 1920
        val targetH = 1080

        val left0 = GlMotionTransform.computeCropRect("pan_left", 0.0f, srcW, srcH, targetW, targetH)
        val left1 = GlMotionTransform.computeCropRect("pan_left", 1.0f, srcW, srcH, targetW, targetH)

        // Size is invariant during pure pan
        assertEquals(left0.width, left1.width, EPSILON)
        assertEquals(left0.height, left1.height, EPSILON)
        // CenterX moves across time
        assertNotEquals(left0.centerX, left1.centerX, EPSILON)

        val right0 = GlMotionTransform.computeCropRect("pan_right", 0.0f, srcW, srcH, targetW, targetH)
        val right1 = GlMotionTransform.computeCropRect("pan_right", 1.0f, srcW, srcH, targetW, targetH)

        assertEquals(right0.width, right1.width, EPSILON)
        assertEquals(right0.height, right1.height, EPSILON)
        assertNotEquals(right0.centerX, right1.centerX, EPSILON)
    }

    @Test
    fun testPanVertically_maintainsSizeAndPreservesAspect() {
        val srcW = 1080
        val srcH = 1920
        val targetW = 1080
        val targetH = 1920

        val up0 = GlMotionTransform.computeCropRect("pan_up", 0.0f, srcW, srcH, targetW, targetH)
        val up1 = GlMotionTransform.computeCropRect("pan_up", 1.0f, srcW, srcH, targetW, targetH)

        assertEquals(up0.width, up1.width, EPSILON)
        assertNotEquals(up0.centerY, up1.centerY, EPSILON)

        val down0 = GlMotionTransform.computeCropRect("pan_down", 0.0f, srcW, srcH, targetW, targetH)
        val down1 = GlMotionTransform.computeCropRect("pan_down", 1.0f, srcW, srcH, targetW, targetH)

        assertEquals(down0.width, down1.width, EPSILON)
        assertNotEquals(down0.centerY, down1.centerY, EPSILON)
    }

    @Test
    fun testDriftUpRight_movesBothAxes() {
        val srcW = 1920
        val srcH = 1080
        val targetW = 1920
        val targetH = 1080

        val drift0 = GlMotionTransform.computeCropRect("drift_up_right", 0.0f, srcW, srcH, targetW, targetH)
        val drift1 = GlMotionTransform.computeCropRect("drift_up_right", 1.0f, srcW, srcH, targetW, targetH)

        assertNotEquals(drift0.centerX, drift1.centerX, EPSILON)
        assertNotEquals(drift0.centerY, drift1.centerY, EPSILON)
    }

    @Test
    fun testAllCoordinatesStrictlyBoundedInUnitInterval() {
        val motionTypes = listOf(
            "zoom_in", "zoom_out", "pan_left", "pan_right",
            "pan_up", "pan_down", "drift_up_right", null
        )
        val dimensions = listOf(
            Pair(1920, 1080),
            Pair(1080, 1920),
            Pair(1000, 1000),
            Pair(4000, 2000),
            Pair(800, 1600)
        )

        for (type in motionTypes) {
            for ((srcW, srcH) in dimensions) {
                for ((targetW, targetH) in dimensions) {
                    for (step in 0..10) {
                        val t = step / 10.0f
                        val rect = GlMotionTransform.computeCropRect(type, t, srcW, srcH, targetW, targetH)

                        assertTrue("uMin >= 0: ${rect.uMin}", rect.uMin >= 0f)
                        assertTrue("uMax <= 1: ${rect.uMax}", rect.uMax <= 1f)
                        assertTrue("vMin >= 0: ${rect.vMin}", rect.vMin >= 0f)
                        assertTrue("vMax <= 1: ${rect.vMax}", rect.vMax <= 1f)
                        assertTrue("uMax > uMin", rect.uMax > rect.uMin)
                        assertTrue("vMax > vMin", rect.vMax > rect.vMin)

                        val texCoords = GlMotionTransform.toTextureCoordinates(rect)
                        assertEquals(8, texCoords.size)
                        texCoords.forEach { coord ->
                            assertTrue("Tex coord in [0, 1]: $coord", coord in 0f..1f)
                        }
                    }
                }
            }
        }
    }
}
