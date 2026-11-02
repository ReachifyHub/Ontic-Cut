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
        val items24 = (1..24).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan24 = ChunkPlanner.plan(items24, emptyList())
        assertFalse("24 clips must NOT trigger chunked export", plan24.isChunked)
        assertEquals(1, plan24.chunks.size)
        assertEquals(24, plan24.chunks[0].clips.size)

        val items25 = (1..25).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan25 = ChunkPlanner.plan(items25, emptyList())
        assertTrue("25 clips MUST trigger chunked export", plan25.isChunked)
        assertEquals(2, plan25.chunks.size)
        assertEquals(16, plan25.chunks[0].clips.size)
        assertEquals(9, plan25.chunks[1].clips.size)
    }

    @Test
    fun testChunkPlanning_sixtyClipsChunkSizes() {
        // 60 clips: should partition into [16, 16, 16, 12]
        val items60 = (1..60).map {
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$it.jpg"), durationMs = 5000L)
        }
        val plan60 = ChunkPlanner.plan(items60, emptyList())
        assertTrue(plan60.isChunked)
        assertEquals(4, plan60.chunks.size)
        assertEquals(listOf(16, 16, 16, 12), plan60.chunks.map { it.clips.size })
        assertEquals(3, plan60.boundaryTransitions.size)
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
}
