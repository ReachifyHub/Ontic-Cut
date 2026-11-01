package com.boostlyte.vydra

import android.net.DummyUri
import android.net.Uri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import com.boostlyte.vydra.viewmodels.duplicateSequenceSegment
import com.boostlyte.vydra.viewmodels.insertSequenceItems
import com.boostlyte.vydra.viewmodels.splitVideoSegment
import com.boostlyte.vydra.viewmodels.swapSequenceSegments
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimelinePlayheadAndReorderTest {

    private lateinit var dummyUri: Uri

    @Before
    fun setUp() {
        dummyUri = DummyUri.INSTANCE
    }

    private fun createTestProject(
        primaryDuration: Long = 10000L,
        mergeDurations: List<Long> = emptyList(),
        transitions: List<EditOperation.Transition> = emptyList()
    ): VideoEditingViewModel {
        val vm = VideoEditingViewModel()
        val ops = mutableListOf<EditOperation>()
        ops.add(EditOperation.Trim(0L, primaryDuration))
        if (mergeDurations.isNotEmpty()) {
            val mergeItems = mergeDurations.map { dur ->
                EditOperation.MergeItem(
                    uri = dummyUri,
                    durationMs = dur,
                    trimStartMs = 0L,
                    trimEndMs = dur
                )
            }
            ops.add(EditOperation.Merge(mergeItems))
        }
        ops.addAll(transitions)

        val project = VideoProject(
            sourceUri = dummyUri,
            sourceName = "test.mp4",
            operations = ops
        )
        vm.loadProject(project)
        return vm
    }

    // ── TASK 1: Playhead precision splitting ──────────────────────────────────

    @Test
    fun testSplitAt5sIn10sClip_splitsAtExact5s() {
        val vm = createTestProject(primaryDuration = 10000L)
        val playheadMs = 5000L
        val clipStartGlobal = 0L
        val localOffset = playheadMs - clipStartGlobal
        val speed = 1.0f
        val sourceOffsetMs = (localOffset * speed).toLong()
        val localSplitMs = 0L + sourceOffsetMs

        vm.splitVideoSegment(0, localSplitMs, dummyUri, 10000L)

        val project = vm.project.value!!
        val trim = project.operations.filterIsInstance<EditOperation.Trim>().first()
        assertEquals(0L, trim.startMs)
        assertEquals(5000L, trim.endMs)

        val merge = project.operations.filterIsInstance<EditOperation.Merge>().first()
        assertEquals(1, merge.items.size)
        assertEquals(5000L, merge.items[0].trimStartMs)
        assertEquals(10000L, merge.items[0].trimEndMs)
    }

    @Test
    fun testSplitWithTrimmedClipAndSpeed_preservesSourceOffsetMath() {
        // Pre-trimmed clip: source is 0..20000, trimmed to 4000..14000, 2.0x speed
        // Trimmed duration on timeline = (14000 - 4000) / 2.0 = 5000ms
        val vm = VideoEditingViewModel()
        val ops = mutableListOf<EditOperation>()
        ops.add(EditOperation.Trim(4000L, 14000L))
        ops.add(EditOperation.SpeedMain(2.0f))
        val project = VideoProject(
            sourceUri = dummyUri,
            sourceName = "test.mp4",
            operations = ops
        )
        vm.loadProject(project)

        // Playhead at 2000ms into the clip on timeline
        val localOffsetTimeline = 2000L
        val sourceOffsetMs = (localOffsetTimeline * 2.0f).toLong() // 4000ms in source
        val localSplitMs = 4000L + sourceOffsetMs // 8000ms in source

        vm.splitVideoSegment(0, localSplitMs, dummyUri, 20000L)

        val updated = vm.project.value!!
        val trim = updated.operations.filterIsInstance<EditOperation.Trim>().first()
        assertEquals(4000L, trim.startMs)
        assertEquals(8000L, trim.endMs) // Duration: 4000 / 2.0 = 2000ms on timeline!

        val merge = updated.operations.filterIsInstance<EditOperation.Merge>().first()
        assertEquals(1, merge.items.size)
        assertEquals(8000L, merge.items[0].trimStartMs)
        assertEquals(14000L, merge.items[0].trimEndMs) // Duration: 6000 / 2.0 = 3000ms on timeline!
    }

    // ── TASK 2: Playhead-aware insertion detection logic ──────────────────────

    @Test
    fun testBoundaryDetection_gapVsInsideClip() {
        // Timeline with 2 clips: Clip 0 (5000ms) and Clip 1 (5000ms), total 10000ms
        val clipDurations = listOf(5000L, 5000L)
        val toleranceMs = 120L

        fun calculateInsertIndex(playheadMs: Long): Int? {
            val totalDuration = clipDurations.sum()
            if (playheadMs <= toleranceMs) return 0
            if (playheadMs >= totalDuration - toleranceMs) return clipDurations.size
            var accum = 0L
            for (i in 0 until clipDurations.size - 1) {
                accum += clipDurations[i]
                if (Math.abs(playheadMs - accum) <= toleranceMs) return i + 1
            }
            return null
        }

        // 1. Playhead at start
        assertEquals(0, calculateInsertIndex(0L))
        assertEquals(0, calculateInsertIndex(50L))

        // 2. Playhead between Clip 0 and Clip 1
        assertEquals(1, calculateInsertIndex(5000L))
        assertEquals(1, calculateInsertIndex(5050L))

        // 3. Playhead at end
        assertEquals(2, calculateInsertIndex(10000L))
        assertEquals(2, calculateInsertIndex(9950L))

        // 4. Playhead inside Clip 0 (e.g. 2500ms)
        assertNull(calculateInsertIndex(2500L))
        // Playhead inside Clip 1 (e.g. 7500ms)
        assertNull(calculateInsertIndex(7500L))
    }

    // ── TASK 3: Duplicate & Reorder ───────────────────────────────────────────

    @Test
    fun testDuplicateClip_insertsCloneImmediatelyAfter() {
        val vm = createTestProject(primaryDuration = 5000L, mergeDurations = listOf(6000L))

        // Duplicate primary clip (index 0)
        vm.duplicateSequenceSegment(0)

        val project = vm.project.value!!
        val merge = project.operations.filterIsInstance<EditOperation.Merge>().first()
        // Sequence should now be: Clip 0 (5s), Clone of Clip 0 (5s), Original Clip 1 (6s)
        assertEquals(2, merge.items.size)
        assertEquals(5000L, merge.items[0].trimEndMs)
        assertEquals(6000L, merge.items[1].trimEndMs)

        // Duplicate clip at index 1 (the clone)
        vm.duplicateSequenceSegment(1)
        val project2 = vm.project.value!!
        val merge2 = project2.operations.filterIsInstance<EditOperation.Merge>().first()
        assertEquals(3, merge2.items.size)
    }

    @Test
    fun testSwapSequenceSegments_swapsAdjacentClips() {
        val vm = createTestProject(primaryDuration = 4000L, mergeDurations = listOf(8000L))

        // Swap Clip 0 (4s) and Clip 1 (8s)
        vm.swapSequenceSegments(0, 1)

        val project = vm.project.value!!
        val trim = project.operations.filterIsInstance<EditOperation.Trim>().first()
        // Clip 1 is now the primary clip (8000ms)
        assertEquals(8000L, trim.endMs)

        val merge = project.operations.filterIsInstance<EditOperation.Merge>().first()
        assertEquals(1, merge.items.size)
        // Clip 0 is now merge item 0 (4000ms)
        assertEquals(4000L, merge.items[0].trimEndMs)
    }

    // ── TASK 4: Transition index integrity ────────────────────────────────────

    @Test
    fun testTransitionIntegrity_whenSplitting() {
        // 2 clips (Clip 0: 5s, Clip 1: 5s) with transition at gap 0
        val trans = listOf(EditOperation.Transition(0, "fade", 1000L))
        val vm = createTestProject(primaryDuration = 5000L, mergeDurations = listOf(5000L), transitions = trans)

        // Split Clip 0 at 2500ms (index 0)
        vm.splitVideoSegment(0, 2500L, dummyUri, 5000L)

        val project = vm.project.value!!
        val transitions = project.operations.filterIsInstance<EditOperation.Transition>()
        assertEquals(1, transitions.size)
        // Transition that was between Clip 0 and Clip 1 (gap 0) shifted to gap 1 (between 0b and 1)
        assertEquals(1, transitions[0].index)
    }

    @Test
    fun testTransitionIntegrity_whenDuplicating() {
        // Clips: 0, 1 with transition at gap 0
        val trans = listOf(EditOperation.Transition(0, "fade", 1000L))
        val vm = createTestProject(primaryDuration = 5000L, mergeDurations = listOf(5000L), transitions = trans)

        // Duplicate Clip 0 (index 0)
        vm.duplicateSequenceSegment(0)

        val project = vm.project.value!!
        val transitions = project.operations.filterIsInstance<EditOperation.Transition>()
        assertEquals(1, transitions.size)
        // Gap 0 (between Clip 0 and its clone) has no transition
        // The original transition shifted to gap 1 (between clone and Clip 1)
        assertEquals(1, transitions[0].index)
    }

    @Test
    fun testTransitionIntegrity_whenSwapping() {
        // 3 clips with transition at gap 0
        val trans = listOf(EditOperation.Transition(0, "fade", 1000L))
        val vm = createTestProject(primaryDuration = 5000L, mergeDurations = listOf(5000L, 5000L), transitions = trans)

        // Swap Clip 0 and Clip 1 (touching gap 0)
        vm.swapSequenceSegments(0, 1)

        val project = vm.project.value!!
        val transitions = project.operations.filterIsInstance<EditOperation.Transition>()
        // Transition at swapped boundary 0 is cleared to prevent invalid boundary math
        assertTrue(transitions.isEmpty())
    }
}
