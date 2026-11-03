package com.boostlyte.vydra

import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.utils.AudioVolumeRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPreviewStabilityTest {

    @Test
    fun testAudioVolumeRamp_smoothRampDown() {
        // Initial 1.0f -> target 0.2f (e.g. ducking or volume slider reduction)
        // 100ms ramp with 50ms ticks
        val target = 0.2f
        val dt = 50L
        val rampMs = 100L

        val step1 = AudioVolumeRamp.computeNextVolume(1.0f, target, dt, rampMs)
        // maxChange = 50 / 100 = 0.5f -> 1.0 - 0.5 = 0.5f
        assertEquals(0.5f, step1, 0.001f)

        val step2 = AudioVolumeRamp.computeNextVolume(step1, target, dt, rampMs)
        // Remaining diff is 0.5 - 0.2 = 0.3 <= 0.5 -> snaps to target 0.2f
        assertEquals(0.2f, step2, 0.001f)
    }

    @Test
    fun testAudioVolumeRamp_smoothRampUp() {
        // Initial 0.0f -> target 1.0f (e.g. unmute or ducking release)
        val target = 1.0f
        val dt = 50L
        val rampMs = 100L

        val step1 = AudioVolumeRamp.computeNextVolume(0.0f, target, dt, rampMs)
        assertEquals(0.5f, step1, 0.001f)

        val step2 = AudioVolumeRamp.computeNextVolume(step1, target, dt, rampMs)
        assertEquals(1.0f, step2, 0.001f)
    }

    @Test
    fun testAudioVolumeRamp_zeroOrNegativeDtPreservesVolume() {
        val current = 0.75f
        val target = 0.2f
        assertEquals(current, AudioVolumeRamp.computeNextVolume(current, target, 0L), 0.001f)
        assertEquals(current, AudioVolumeRamp.computeNextVolume(current, target, -10L), 0.001f)
    }

    @Test
    fun testAudioVolumeRamp_smallChangeSnapsToTarget() {
        val current = 0.81f
        val target = 0.83f
        val dt = 50L // maxChange = 0.5
        val result = AudioVolumeRamp.computeNextVolume(current, target, dt)
        assertEquals(target, result, 0.001f)
    }

    @Test
    fun testAudioVolumeRamp_targetVolumeCalculationWithDuckingAndFade() {
        // Raw volume 1.0f, fade in 50%, no fade out, ducking active (0.2f)
        val target = AudioVolumeRamp.computeTargetVolume(
            rawVolume = 1.0f,
            fadeInMult = 0.5f,
            fadeOutMult = 1.0f,
            duckingTarget = AudioVolumeRamp.DUCKING_TARGET_MULTIPLIER,
            isMasterMuted = false
        )
        // 1.0 * 0.5 * 1.0 * 0.2 = 0.1f
        assertEquals(0.1f, target, 0.001f)
    }

    @Test
    fun testAudioVolumeRamp_masterMuteOverridesAll() {
        val target = AudioVolumeRamp.computeTargetVolume(
            rawVolume = 1.5f,
            fadeInMult = 1.0f,
            fadeOutMult = 1.0f,
            duckingTarget = 1.0f,
            isMasterMuted = true
        )
        assertEquals(0.0f, target, 0.0f)
    }

    @Test
    fun testAudioVolumeRamp_fadeMultiplierMath() {
        // Fade in 1000ms, offset 500ms -> inMult = 0.5f
        // Fade out 1000ms, remaining 2000ms -> outMult = 1.0f
        val mult = AudioVolumeRamp.computeFadeMultiplier(
            offsetInOpMs = 500L,
            remainingInOpMs = 2000L,
            fadeInDurationMs = 1000L,
            fadeOutDurationMs = 1000L
        )
        assertEquals(0.5f, mult, 0.001f)
    }

    @Test
    fun testAudioPlayerLifecycle_singleInstancePerOpInvariant() {
        // Simulate bgAudioPlayerMap lifecycle invariant
        val simulatedPlayerMap = mutableMapOf<String, String>()
        val op1 = "audio_track_1"

        // First creation
        simulatedPlayerMap.getOrPut(op1) { "player_instance_1" }
        assertEquals(1, simulatedPlayerMap.size)
        assertEquals("player_instance_1", simulatedPlayerMap[op1])

        // Second lookup does NOT create a second instance
        simulatedPlayerMap.getOrPut(op1) { "player_instance_2_unwanted" }
        assertEquals(1, simulatedPlayerMap.size)
        assertEquals("player_instance_1", simulatedPlayerMap[op1])

        // Deletion releases and removes
        val released = simulatedPlayerMap.remove(op1)
        assertEquals("player_instance_1", released)
        assertTrue(simulatedPlayerMap.isEmpty())
    }

    @Test
    fun testAudioPlayerLifecycle_orphanReconciliation() {
        val simulatedPlayerMap = mutableMapOf(
            "op_A" to "player_A",
            "op_B" to "player_B",
            "op_OLD" to "player_OLD"
        )
        val activeOpIds = setOf("op_A", "op_B")

        // Reconcile
        val iter = simulatedPlayerMap.iterator()
        val releasedIds = mutableListOf<String>()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in activeOpIds) {
                releasedIds.add(entry.key)
                iter.remove()
            }
        }

        assertEquals(listOf("op_OLD"), releasedIds)
        assertEquals(2, simulatedPlayerMap.size)
        assertTrue("op_A" in simulatedPlayerMap)
        assertTrue("op_B" in simulatedPlayerMap)
        assertFalse("op_OLD" in simulatedPlayerMap)
    }

    @Test
    fun testClipBoundarySeekingInvariant() {
        // Test boundary logic: continuous ticks within a clip should NOT require seek
        val clip0End = 3000L
        val clip1End = 7000L

        fun checkRequiresSeek(
            globalPos: Long,
            lastClipIdx: Int,
            lastPos: Long,
            isSeek: Boolean,
            playbackJustStarted: Boolean
        ): Pair<Boolean, Int> {
            val currentClipIdx = if (globalPos < clip0End) 0 else 1
            val crossedClipBoundary = (currentClipIdx != lastClipIdx && lastClipIdx != -1)
            val playheadJumped = (lastPos != -1L && kotlin.math.abs(globalPos - lastPos) > 400L)
            val requiresSeek = isSeek || crossedClipBoundary || playbackJustStarted || playheadJumped
            return Pair(requiresSeek, currentClipIdx)
        }

        // 1. Playback start at pos 1000
        val (seekOnStart, clipIdx1) = checkRequiresSeek(1000L, -1, -1L, isSeek = false, playbackJustStarted = true)
        assertTrue("Start of playback must seek", seekOnStart)
        assertEquals(0, clipIdx1)

        // 2. Continuous playback at pos 1050 (within clip 0)
        val (seekSteady1, clipIdx2) = checkRequiresSeek(1050L, 0, 1000L, isSeek = false, playbackJustStarted = false)
        assertFalse("Continuous steady play must NOT seek", seekSteady1)
        assertEquals(0, clipIdx2)

        // 3. Continuous playback at pos 1100 (within clip 0)
        val (seekSteady2, clipIdx3) = checkRequiresSeek(1100L, 0, 1050L, isSeek = false, playbackJustStarted = false)
        assertFalse("Continuous steady play must NOT seek", seekSteady2)
        assertEquals(0, clipIdx3)

        // 4. Playhead crosses boundary into clip 1 (pos 3050)
        val (seekBoundary, clipIdx4) = checkRequiresSeek(3050L, 0, 2990L, isSeek = false, playbackJustStarted = false)
        assertTrue("Crossing boundary into next clip must trigger seek", seekBoundary)
        assertEquals(1, clipIdx4)

        // 5. Explicit user seek
        val (seekUser, _) = checkRequiresSeek(4000L, 1, 3100L, isSeek = true, playbackJustStarted = false)
        assertTrue("Explicit user seek must trigger seek", seekUser)
    }
}
