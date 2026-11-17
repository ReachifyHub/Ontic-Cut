package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.viewmodels.TimelineLayoutCalculator
import com.boostlyte.vydra.viewmodels.TimelinePlaybackMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePlaybackMapperTest {

    @Test
    fun test65Clips64ShortTransitions_slicesMatchAuthoritativeDurationAndTransitionsNeverDrift() {
        val items = (0 until 65).map { idx ->
            val dur = 4100L + (idx % 7) * 550L
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/storage/emulated/0/DCIM/img_$idx.jpg"),
                durationMs = dur,
                trimStartMs = 0L,
                trimEndMs = dur,
                isImage = true
            )
        }
        val transitions = (0 until 64).map { idx ->
            EditOperation.Transition(
                index = idx,
                type = if (idx % 2 == 0) "fade" else "dissolve",
                durationMs = 100L
            )
        }

        val model = TimelinePlaybackMapper.buildModel(items, transitions)
        val rawSum = items.sumOf { it.trimmedDurationMs }
        val expectedAuthoritativeDuration = rawSum - (64L * 100L)

        assertEquals(expectedAuthoritativeDuration, model.layout.totalDurationMs)
        assertEquals(
            "Concatenating playback slices must match authoritative total duration",
            model.layout.totalDurationMs,
            model.slices.sumOf { it.timelineDurationMs }
        )
        assertEquals(65, model.slices.size)

        // Verify slices are contiguous from 0 to totalDurationMs
        assertEquals(0L, model.slices.first().timelineStartMs)
        assertEquals(model.layout.totalDurationMs, model.slices.last().timelineEndMs)
        for (i in 0 until model.slices.size - 1) {
            assertEquals(model.slices[i].timelineEndMs, model.slices[i + 1].timelineStartMs)
        }

        // Verify every transition midpoint activates the exact corresponding transition gap
        for (t in model.layout.transitions) {
            val midMs = t.offsetMs + (t.durationMs / 2L)
            val resolved = TimelinePlaybackMapper.resolveAt(model, midMs)
            assertNotNull(resolved)
            assertEquals("Transition gap ${t.gapIndex} must be active at $midMs ms", t.gapIndex, resolved!!.activeTransition?.gapIndex)
            assertEquals(0.5f, resolved.transitionProgress, 0.02f)
        }

        // Verify non-transition midpoints of all 65 clips resolve to the exact clipIndex & null transition
        for (clip in model.layout.clips) {
            val midMs = clip.startMs + (clip.durationMs / 2L)
            val resolved = TimelinePlaybackMapper.resolveAt(model, midMs)
            assertNotNull(resolved)
            assertEquals("Clip ${clip.index} midpoint must map to clip ${clip.index}", clip.index, resolved!!.clipIndex)
            assertEquals(midMs - clip.startMs, resolved.relTimeMs)
            assertNull("Clip ${clip.index} midpoint should not trigger a transition", resolved.activeTransition)
        }
    }

    @Test
    fun testResolveSlicePositionAndComputeGlobalPosition_noResetAtSliceEnd() {
        val sliceDurations = listOf(4950L, 4900L, 4950L)

        // 1. Resolve slice position across transitions
        assertEquals(Pair(0, 0L), TimelinePlaybackMapper.resolveSlicePosition(sliceDurations, 0L))
        assertEquals(Pair(0, 4949L), TimelinePlaybackMapper.resolveSlicePosition(sliceDurations, 4949L))
        assertEquals(Pair(1, 0L), TimelinePlaybackMapper.resolveSlicePosition(sliceDurations, 4950L))
        assertEquals(Pair(2, 4950L), TimelinePlaybackMapper.resolveSlicePosition(sliceDurations, 14800L))

        // 2. When still in slice 1 (lastItemIndex == 1) and rawSlicePosMs == chunkDur (4900L),
        // global position must be 4950 + 4900 = 9850, NOT reset to 4950!
        val (atEndSameSlice, idxEnd) = TimelinePlaybackMapper.computeGlobalPosition(
            sliceDurationsMs = sliceDurations,
            currentSliceIndex = 1,
            rawSlicePosMs = 4900L,
            lastItemIndex = 1,
            lastGlobalPosMs = 9834L,
            isPlaying = true
        )
        assertEquals(9850L, atEndSameSlice)
        assertEquals(1, idxEnd)

        // 3. When ExoPlayer switches to slice 2 (lastItemIndex = 1 -> currentSliceIndex = 2)
        // while briefly reporting stale slice 1 position (4940ms > chunkDur 4950 only if greater, e.g., 4980ms)
        val (staleSwitchPos, idxNew) = TimelinePlaybackMapper.computeGlobalPosition(
            sliceDurationsMs = listOf(5000L, 2000L),
            currentSliceIndex = 1,
            rawSlicePosMs = 4980L,
            lastItemIndex = 0,
            lastGlobalPosMs = 4990L,
            isPlaying = true
        )
        assertEquals(5000L, staleSwitchPos)
        assertEquals(1, idxNew)
    }

    @Test
    fun testCalculatePlayheadInsertionIndex_usesTransitionWindows() {
        val items = List(3) { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/$idx.jpg"),
                durationMs = 5000L,
                trimStartMs = 0L,
                trimEndMs = 5000L,
                isImage = true
            )
        }
        val transitions = listOf(
            EditOperation.Transition(index = 0, type = "fade", durationMs = 1000L),
            EditOperation.Transition(index = 1, type = "dissolve", durationMs = 1000L)
        )
        val layout = TimelineLayoutCalculator.computeTimelineLayout(items, transitions)
        // Total = 15000 - 2000 = 13000ms.
        // Clip 0: [0, 5000], Trans 0: [4000, 5000], Clip 1: [4000, 9000], Trans 1: [8000, 9000], Clip 2: [8000, 13000].
        assertEquals(13000L, layout.totalDurationMs)

        assertEquals(0, TimelinePlaybackMapper.calculatePlayheadInsertionIndex(layout, 50L))
        assertEquals(1, TimelinePlaybackMapper.calculatePlayheadInsertionIndex(layout, 4500L))
        assertNull(TimelinePlaybackMapper.calculatePlayheadInsertionIndex(layout, 6500L))
        assertEquals(2, TimelinePlaybackMapper.calculatePlayheadInsertionIndex(layout, 8500L))
        assertEquals(3, TimelinePlaybackMapper.calculatePlayheadInsertionIndex(layout, 12950L))
    }

    @Test
    fun testFindClipForSplitAndSnapTargets_matchAuthoritativeGeometry() {
        val items = List(3) { idx ->
            EditOperation.MergeItem(
                uri = DummyUri.fromPath("/tmp/$idx.mp4"),
                durationMs = 4000L,
                trimStartMs = 0L,
                trimEndMs = 4000L
            )
        }
        val transitions = listOf(EditOperation.Transition(index = 0, type = "fade", durationMs = 500L))
        val model = TimelinePlaybackMapper.buildModel(items, transitions)
        // Clip 0: [0, 4000], Clip 1: [3500, 7500], Clip 2: [7500, 11500]

        val splitClip = TimelinePlaybackMapper.findClipForSplit(model, playheadMs = 5500L, selectedIndex = 1)
        assertNotNull(splitClip)
        assertEquals(1, splitClip!!.index)

        val audioOp = EditOperation.AddBackgroundAudio(
            audioUri = DummyUri.fromPath("/tmp/vo.mp3"),
            startTimeMs = 200L,
            endTimeMs = 11000L,
            beats = listOf(1200L)
        )
        val targets = TimelinePlaybackMapper.getSnapTargetsMs(model.layout, listOf(audioOp))
        assertTrue(targets.contains(0L))
        assertTrue(targets.contains(3500L))
        assertTrue(targets.contains(4000L))
        assertTrue(targets.contains(7500L))
        assertTrue(targets.contains(11500L))
        assertTrue(targets.contains(1400L)) // 200 + 1200 beat
    }

    @Test
    fun testShouldResyncBgAudio_continuousAcrossCutResyncsOnlyOnDriftOrSeek() {
        // Minor drift (25ms) after transition boundary -> NO seek
        assertFalse(
            TimelinePlaybackMapper.shouldResyncBgAudio(
                isSeek = false,
                playbackJustStarted = false,
                playheadJumped = false,
                isBgPlaying = true,
                currentBgPosMs = 10025L,
                targetAudioMs = 10000L,
                elapsedSinceLastResyncMs = 2000L
            )
        )

        // Significant drift (>120ms) after cooldown (>=500ms) -> seeks to re-align
        assertTrue(
            TimelinePlaybackMapper.shouldResyncBgAudio(
                isSeek = false,
                playbackJustStarted = false,
                playheadJumped = false,
                isBgPlaying = true,
                currentBgPosMs = 10180L,
                targetAudioMs = 10000L,
                elapsedSinceLastResyncMs = 600L
            )
        )

        // Significant drift inside cooldown (<500ms) -> throttled
        assertFalse(
            TimelinePlaybackMapper.shouldResyncBgAudio(
                isSeek = false,
                playbackJustStarted = false,
                playheadJumped = false,
                isBgPlaying = true,
                currentBgPosMs = 10180L,
                targetAudioMs = 10000L,
                elapsedSinceLastResyncMs = 250L
            )
        )

        // Explicit seek always resyncs
        assertTrue(
            TimelinePlaybackMapper.shouldResyncBgAudio(
                isSeek = true,
                playbackJustStarted = false,
                playheadJumped = false,
                isBgPlaying = true,
                currentBgPosMs = 10000L,
                targetAudioMs = 10000L,
                elapsedSinceLastResyncMs = 10L
            )
        )
    }
}
