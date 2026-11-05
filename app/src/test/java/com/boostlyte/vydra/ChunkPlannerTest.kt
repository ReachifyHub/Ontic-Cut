package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.viewmodels.TimelineLayoutCalculator
import org.junit.Assert.*
import org.junit.Test

class ChunkPlannerTest {

    @Test
    fun testChunkTriggerThreshold() {
        val items16 = (1..16).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan16 = ChunkPlanner.plan(items16, emptyList())
        assertFalse("16 clips must NOT trigger chunked export", plan16.isChunked)
        assertEquals(1, plan16.chunks.size)
        assertEquals(16, plan16.chunks[0].clips.size)

        val items17 = (1..17).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan17 = ChunkPlanner.plan(items17, emptyList())
        assertTrue("17 clips MUST trigger chunked export", plan17.isChunked)
        assertEquals(2, plan17.chunks.size)
        assertEquals(10, plan17.chunks[0].clips.size)
        assertEquals(7, plan17.chunks[1].clips.size)
    }

    @Test
    fun testChunkPlanning_sixtyClipsChunkSizes() {
        // 60 clips: with MAX_CHUNK_SIZE = 10, should partition into [10, 10, 10, 10, 10, 10]
        val items60 = (1..60).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan60 = ChunkPlanner.plan(items60, emptyList())
        assertTrue(plan60.isChunked)
        assertEquals(6, plan60.chunks.size)
        assertEquals(listOf(10, 10, 10, 10, 10, 10), plan60.chunks.map { it.clips.size })
        assertEquals(5, plan60.boundaryTransitions.size)
    }

    @Test
    fun testMathematicalInvariant_transitionsEverywhere() {
        // 35 clips with transitions on EVERY single gap (gaps 0..33)
        val items35 = (0 until 35).map { idx ->
            val dur = 4000L + (idx % 5) * 500L
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$idx.jpg"), durationMs = dur)
        }
        val allTransitions = (0 until 34).map { gapIdx ->
            val transDur = 1000L + (gapIdx % 3) * 200L
            EditOperation.Transition(index = gapIdx, type = "fade", durationMs = transDur)
        }

        val fullLayout = TimelineLayoutCalculator.computeTimelineLayout(items35, allTransitions)
        val plan = ChunkPlanner.plan(items35, allTransitions)

        assertTrue(plan.isChunked)
        assertEquals(fullLayout.totalDurationMs, plan.totalDurationMs)

        // Mathematical invariant:
        // sum(chunk durations) - sum(boundary transitions) == totalDurationMs
        val sumChunkDurations = plan.chunks.sumOf { it.durationMs }
        val sumBoundaryTransitions = plan.boundaryTransitions.sumOf { it.transition?.durationMs ?: 0L }
        val compositeDuration = sumChunkDurations - sumBoundaryTransitions

        assertEquals(
            "Composite duration of chained chunks must equal total timeline layout",
            plan.totalDurationMs,
            compositeDuration
        )
    }

    @Test
    fun testClampingCaptionAndOverlayWindow_straddlingBoundary() {
        // Chunk 0: global 0ms..50000ms
        // Cue 1: fully inside chunk (10000ms..20000ms)
        val w1 = ChunkPlanner.clampToChunkWindow(10000L, 20000L, 0L, 50000L)
        assertTrue(w1.isVisibleInChunk)
        assertEquals(10000L, w1.localStartMs)
        assertEquals(20000L, w1.localEndMs)

        // Cue 2: before chunk (starts 0ms, ends 5000ms, chunk starts at 10000ms)
        val w2 = ChunkPlanner.clampToChunkWindow(0L, 5000L, 10000L, 50000L)
        assertFalse(w2.isVisibleInChunk)

        // Cue 3: after chunk (starts 70000ms, chunk ends at 60000ms)
        val w3 = ChunkPlanner.clampToChunkWindow(70000L, 80000L, 0L, 60000L)
        assertFalse(w3.isVisibleInChunk)

        // Cue 4: Straddles boundary between Chunk 0 (0..50000ms) and Chunk 1 (50000..100000ms)
        // Global cue: 45000ms..55000ms (10 seconds total)
        val inChunk0 = ChunkPlanner.clampToChunkWindow(45000L, 55000L, 0L, 50000L)
        assertTrue(inChunk0.isVisibleInChunk)
        assertEquals(45000L, inChunk0.localStartMs)
        assertEquals(50000L, inChunk0.localEndMs) // Clamped to chunk 0 end

        val inChunk1 = ChunkPlanner.clampToChunkWindow(45000L, 55000L, 50000L, 50000L)
        assertTrue(inChunk1.isVisibleInChunk)
        assertEquals(0L, inChunk1.localStartMs) // Starts at local 0 of chunk 1
        assertEquals(5000L, inChunk1.localEndMs) // 55000 - 50000 = 5000ms

        // Combined visible duration equals original cue duration (5s in chunk 0 + 5s in chunk 1 = 10s)
        val chunk0Duration = inChunk0.localEndMs - inChunk0.localStartMs
        val chunk1Duration = inChunk1.localEndMs - inChunk1.localStartMs
        assertEquals(10000L, chunk0Duration + chunk1Duration)
    }

    @Test
    fun testDurationInvariant_64And120Clips() {
        // Test 64 clips (with MAX_CHUNK_SIZE = 10 -> 6 chunks of 10 + 1 chunk of 4)
        val items64 = (0 until 64).map { idx ->
            val dur = 3000L + (idx % 7) * 400L
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$idx.jpg"), durationMs = dur)
        }
        val transitions64 = (0 until 63).map { gapIdx ->
            val transDur = 800L + (gapIdx % 4) * 200L
            EditOperation.Transition(index = gapIdx, type = "fade", durationMs = transDur)
        }
        val layout64 = TimelineLayoutCalculator.computeTimelineLayout(items64, transitions64)
        val plan64 = ChunkPlanner.plan(items64, transitions64)

        assertTrue(plan64.isChunked)
        assertEquals(7, plan64.chunks.size)
        assertEquals(listOf(10, 10, 10, 10, 10, 10, 4), plan64.chunks.map { it.clips.size })
        val sumChunk64 = plan64.chunks.sumOf { it.durationMs }
        val sumBoundary64 = plan64.boundaryTransitions.sumOf { it.transition?.durationMs ?: 0L }
        assertEquals(layout64.totalDurationMs, sumChunk64 - sumBoundary64)

        // Test 120 clips (stress test: 12 chunks of 10)
        val items120 = (0 until 120).map { idx ->
            val dur = 2500L + (idx % 9) * 300L
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$idx.jpg"), durationMs = dur)
        }
        val transitions120 = (0 until 119).map { gapIdx ->
            val transDur = 600L + (gapIdx % 5) * 150L
            EditOperation.Transition(index = gapIdx, type = "fade", durationMs = transDur)
        }
        val layout120 = TimelineLayoutCalculator.computeTimelineLayout(items120, transitions120)
        val plan120 = ChunkPlanner.plan(items120, transitions120)

        assertTrue(plan120.isChunked)
        assertEquals(12, plan120.chunks.size)
        assertEquals(List(12) { 10 }, plan120.chunks.map { it.clips.size })
        val sumChunk120 = plan120.chunks.sumOf { it.durationMs }
        val sumBoundary120 = plan120.boundaryTransitions.sumOf { it.transition?.durationMs ?: 0L }
        assertEquals(layout120.totalDurationMs, sumChunk120 - sumBoundary120)
    }

    @Test
    fun testChunkPlanTransitionsEverywhere_boundaryIsolation() {
        val items40 = (0 until 40).map { idx ->
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$idx.jpg"), durationMs = 4000L)
        }
        val transitions40 = (0 until 39).map { gapIdx ->
            EditOperation.Transition(index = gapIdx, type = "wipeleft", durationMs = 1000L)
        }
        val plan40 = ChunkPlanner.plan(items40, transitions40)

        assertTrue(plan40.isChunked)
        // 40 clips -> [10, 10, 10, 10]
        assertEquals(4, plan40.chunks.size)
        assertEquals(3, plan40.boundaryTransitions.size)

        // Boundary transitions must be gap 9, 19, and 29
        assertEquals(9, plan40.boundaryTransitions[0].globalGapIndex)
        assertEquals(19, plan40.boundaryTransitions[1].globalGapIndex)
        assertEquals(29, plan40.boundaryTransitions[2].globalGapIndex)

        // Internal transitions inside each chunk must be remapped to 0 until (size - 1)
        for (chunk in plan40.chunks) {
            val maxExpectedGap = chunk.clips.size - 2
            for (t in chunk.internalTransitions) {
                assertTrue("Internal transition index must be <= max gap $maxExpectedGap", t.index in 0..maxExpectedGap)
            }
        }
    }

    @Test
    fun testKeyframeTimeRemapCorrectness() {
        val vm = com.boostlyte.vydra.viewmodels.VideoEditingViewModel()
        val keyframes = listOf(
            EditOperation.KeyframePoint(timeMs = 10000L, valueX = 0.0f, valueY = 0.0f),
            EditOperation.KeyframePoint(timeMs = 20000L, valueX = 1.0f, valueY = 1.0f)
        )

        // Without time offset (chunk 0 starting at 0ms):
        // effectiveStartSec = (10000 - 0) / 1000.0 = 10.0
        val expr0 = vm.buildFFmpegInterpolationExpr(
            keyframes = keyframes,
            useValueY = false,
            defaultValue = 0.0f,
            startTimeMs = 10000L,
            timeOffsetMs = 0L
        )
        assertTrue("Expression must use 10.0 for effectiveStartSec without offset", expr0.contains("(t-10.0)"))

        // With time offset 10000ms (chunk 1 starting at global 10000ms):
        // effectiveStartSec = (10000 - 10000) / 1000.0 = 0.0
        val exprChunk1 = vm.buildFFmpegInterpolationExpr(
            keyframes = keyframes,
            useValueY = false,
            defaultValue = 0.0f,
            startTimeMs = 10000L,
            timeOffsetMs = 10000L
        )
        assertTrue("Expression in chunk 1 must shift effectiveStartSec to 0.0", exprChunk1.contains("(t-0.0)"))
    }
}
