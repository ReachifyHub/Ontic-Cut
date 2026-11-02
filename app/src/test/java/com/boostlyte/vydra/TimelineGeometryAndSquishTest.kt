package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.utils.ProjectSerializer
import com.boostlyte.vydra.viewmodels.ClipLayout
import com.boostlyte.vydra.viewmodels.TimelineLayoutCalculator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimelineGeometryAndSquishTest {

    @Before
    fun setUp() {
        ProjectSerializer.uriDeserializerFallback = { str -> DummyUri.fromPath(str) }
    }

    @Test
    fun testFiveClipsWithTwoUnequalTransitions_serializeDeserializeParity() {
        // Construct a 5-clip project:
        // Clip 0: 5000ms
        // Clip 1: 4000ms
        // Clip 2: 6000ms
        // Clip 3: 5000ms
        // Clip 4: 3000ms
        // Gap 0 (between Clip 0 & 1): Transition duration 1000ms
        // Gap 1 (between Clip 1 & 2): None (0ms overlap)
        // Gap 2 (between Clip 2 & 3): Transition duration 1500ms
        // Gap 3 (between Clip 3 & 4): None (0ms overlap)

        val clipDurations = listOf(5000L, 4000L, 6000L, 5000L, 3000L)
        val clipUris = (0 until 5).map { DummyUri.fromPath("file:///dummy_clip_$it.mp4") }

        val primaryClip = EditOperation.MergeItem(
            uri = clipUris[0],
            durationMs = clipDurations[0],
            trimStartMs = 0L,
            trimEndMs = clipDurations[0]
        )

        val mergeItems = (1 until 5).map { i ->
            EditOperation.MergeItem(
                uri = clipUris[i],
                durationMs = clipDurations[i],
                trimStartMs = 0L,
                trimEndMs = clipDurations[i]
            )
        }

        val transitions = listOf(
            EditOperation.Transition(index = 0, type = "fade", durationMs = 1000L),
            EditOperation.Transition(index = 2, type = "wipeleft", durationMs = 1500L)
        )

        val sequenceBefore = listOf(primaryClip) + mergeItems

        // 1. Compute timeline layout before serialization
        val layoutBefore = TimelineLayoutCalculator.computeTimelineLayout(sequenceBefore, transitions)

        // Mathematical verification:
        // sum(durations) = 5000 + 4000 + 6000 + 5000 + 3000 = 23000ms
        // sum(transitions) = 1000 + 1500 = 2500ms
        // expected totalDuration = 23000 - 2500 = 20500ms
        assertEquals(20500L, layoutBefore.totalDurationMs)

        // Per-clip layout verification:
        // Clip 0: [0, 5000]
        // Transition 0 at: 5000 - 1000 = 4000
        // Clip 1: [4000, 4000 + 4000 = 8000]
        // Clip 2: [8000, 8000 + 6000 = 14000]
        // Transition 2 at: 14000 - 1500 = 12500
        // Clip 3: [12500, 12500 + 5000 = 17500]
        // Clip 4: [17500, 17500 + 3000 = 20500]
        val expectedClips = listOf(
            ClipLayout(index = 0, startMs = 0L, endMs = 5000L, durationMs = 5000L),
            ClipLayout(index = 1, startMs = 4000L, endMs = 8000L, durationMs = 4000L),
            ClipLayout(index = 2, startMs = 8000L, endMs = 14000L, durationMs = 6000L),
            ClipLayout(index = 3, startMs = 12500L, endMs = 17500L, durationMs = 5000L),
            ClipLayout(index = 4, startMs = 17500L, endMs = 20500L, durationMs = 3000L)
        )
        assertEquals(expectedClips, layoutBefore.clips)

        val viewportWidth = 1080f
        val density = 2.0f
        val pixelsPerMsBefore = TimelineLayoutCalculator.calculatePixelsPerMs(
            layoutBefore.totalDurationMs,
            viewportWidth,
            density
        )

        // 2. Build VideoProject & EditRecipe, then serialize to JSON
        val project = VideoProject(
            sourceUri = primaryClip.uri,
            sourceName = "dummy_clip_0.mp4",
            operations = listOf(
                EditOperation.Trim(0L, clipDurations[0]),
                EditOperation.Merge(mergeItems)
            ) + transitions
        )

        val recipe = EditRecipe.fromVideoProject("TestProject", project)
        assertEquals(20500L, recipe.durationMs)
        assertEquals(5, recipe.clipCount)

        val json = ProjectSerializer.serialize(recipe)
        assertNotNull(json)
        assertTrue(json.contains("20500"))

        // 3. Deserialize JSON back to EditRecipe & VideoProject
        val deserializedRecipe = ProjectSerializer.deserialize(json)
        val deserializedProject = deserializedRecipe.toVideoProject()

        // Extract sequence from deserialized project:
        val desMergeOp = deserializedProject.operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val desTrimOp = deserializedProject.operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val desTransitions = deserializedProject.operations.filterIsInstance<EditOperation.Transition>()

        assertNotNull(desMergeOp)
        assertEquals(4, desMergeOp!!.items.size)
        assertEquals(2, desTransitions.size)

        val desPrimaryClip = EditOperation.MergeItem(
            uri = deserializedProject.sourceUri,
            durationMs = desTrimOp?.let { it.endMs - it.startMs } ?: 5000L,
            trimStartMs = desTrimOp?.startMs ?: 0L,
            trimEndMs = desTrimOp?.endMs ?: 5000L
        )

        val sequenceAfter = listOf(desPrimaryClip) + desMergeOp.items

        // 4. Compute timeline layout after deserialization
        val layoutAfter = TimelineLayoutCalculator.computeTimelineLayout(sequenceAfter, desTransitions)
        val pixelsPerMsAfter = TimelineLayoutCalculator.calculatePixelsPerMs(
            layoutAfter.totalDurationMs,
            viewportWidth,
            density
        )

        // 5. Assert IDENTICAL geometry:
        // - Per-clip starts and ends
        assertEquals("Clips layout must be identical after reopen", layoutBefore.clips, layoutAfter.clips)
        // - Total duration ms
        assertEquals("Total duration ms must be identical after reopen", layoutBefore.totalDurationMs, layoutAfter.totalDurationMs)
        assertEquals(20500L, layoutAfter.totalDurationMs)
        // - Pixels-per-ms (prevents squish)
        assertEquals("Pixels-per-ms must be identical after reopen", pixelsPerMsBefore, pixelsPerMsAfter, 0.00001f)

        // - Invariant check:
        val totalClipDurations = sequenceAfter.sumOf { it.trimmedDurationMs }
        val totalTransitionDurations = layoutAfter.transitions.sumOf { it.durationMs }
        assertEquals(layoutAfter.totalDurationMs, totalClipDurations - totalTransitionDurations)
    }

    @Test
    fun testMathematicalInvariant_transitionsEverywhere() {
        // Worst-case scenario: transitions on EVERY gap
        val durations = listOf(3000L, 4000L, 5000L, 3500L, 4500L)
        val items = durations.mapIndexed { idx, d ->
            EditOperation.MergeItem(uri = DummyUri.fromPath("file:///item_$idx.jpg"), durationMs = d)
        }
        val transitions = listOf(
            EditOperation.Transition(index = 0, type = "fade", durationMs = 1000L),
            EditOperation.Transition(index = 1, type = "wipeleft", durationMs = 1200L),
            EditOperation.Transition(index = 2, type = "wiperight", durationMs = 800L),
            EditOperation.Transition(index = 3, type = "circlecrop", durationMs = 1500L)
        )

        val layout = TimelineLayoutCalculator.computeTimelineLayout(items, transitions)
        val expectedTotal = durations.sum() - (1000L + 1200L + 800L + 1500L)
        assertEquals(expectedTotal, layout.totalDurationMs)

        // Verify strictly monotonic offsets:
        assertTrue(layout.transitions[0].offsetMs < layout.transitions[1].offsetMs)
        assertTrue(layout.transitions[1].offsetMs < layout.transitions[2].offsetMs)
        assertTrue(layout.transitions[2].offsetMs < layout.transitions[3].offsetMs)
    }
}
