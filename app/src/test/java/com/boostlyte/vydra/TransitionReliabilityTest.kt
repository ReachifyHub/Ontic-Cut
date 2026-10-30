package com.boostlyte.vydra

import android.net.DummyUri
import android.net.Uri
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.viewmodels.TransitionTimelineCalculator
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import com.boostlyte.vydra.viewmodels.deleteSequenceSegment
import com.boostlyte.vydra.viewmodels.splitVideoSegment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Reliability test suite for:
 * 1. Export PTS timestamp normalization and mathematical invariant in TransitionTimelineCalculator.
 * 2. Transition index remapping on splitVideoSegment and deleteSequenceSegment.
 * 3. FFmpeg xfade chaining PTS normalization verification.
 */
class TransitionReliabilityTest {

    private lateinit var dummyUri: Uri

    @Before
    fun setUp() {
        dummyUri = DummyUri.INSTANCE
    }

    // ── 1. Mathematical Invariant & Monotonicity ─────────────────────────────

    @Test
    fun testTransitionTimelineCalculator_invariantHoldsForTwoClips() {
        val clipDurations = listOf(5.0, 5.0)
        val transitions = listOf(
            EditOperation.Transition(0, "fade", 1000L)
        )
        val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)

        val expectedTotal = (5.0 + 5.0) - 1.0
        assertEquals(expectedTotal, math.totalDurationSec, 1e-6)
        assertEquals(1, math.transitions.size)
        assertEquals(4.0, math.transitions[0].offsetSec, 1e-6)
        assertEquals(1.0, math.transitions[0].durationSec, 1e-6)
    }

    @Test
    fun testTransitionTimelineCalculator_invariantHoldsForConsecutiveTransitions() {
        // 3 clips with 2 consecutive transitions
        val clipDurations = listOf(5.0, 5.0, 5.0)
        val transitions = listOf(
            EditOperation.Transition(0, "fade", 1000L),
            EditOperation.Transition(1, "wipeleft", 1500L)
        )
        val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)

        val sumClips = clipDurations.sum()
        val sumTransitions = 1.0 + 1.5
        val expectedTotal = sumClips - sumTransitions
        assertEquals(expectedTotal, math.totalDurationSec, 1e-6)

        assertEquals(2, math.transitions.size)
        // Transition 0: starts at 5.0 - 1.0 = 4.0s
        assertEquals(4.0, math.transitions[0].offsetSec, 1e-6)
        // After transition 0, accumulated duration is 4.0 + 5.0 = 9.0s
        // Transition 1: starts at 9.0 - 1.5 = 7.5s
        assertEquals(7.5, math.transitions[1].offsetSec, 1e-6)

        // Monotonic check
        assertTrue(math.transitions[1].offsetSec > math.transitions[0].offsetSec)
    }

    @Test
    fun testTransitionTimelineCalculator_invariantHoldsForFiveClipsChained() {
        // 5 clips with 4 consecutive transitions
        val clipDurations = listOf(3.0, 4.0, 5.0, 3.5, 4.5) // sum = 20.0s
        val transitions = listOf(
            EditOperation.Transition(0, "fade", 500L),   // 0.5s
            EditOperation.Transition(1, "dissolve", 1000L), // 1.0s
            EditOperation.Transition(2, "coverleft", 800L), // 0.8s
            EditOperation.Transition(3, "zoomin", 1200L)    // 1.2s
        ) // sum transitions = 3.5s

        val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)

        val expectedTotal = 20.0 - 3.5 // 16.5s
        assertEquals(expectedTotal, math.totalDurationSec, 1e-6)
        assertEquals(4, math.transitions.size)

        // Verify strictly monotonic offsets
        for (i in 0 until math.transitions.size - 1) {
            assertTrue(
                "Offset $i (${math.transitions[i].offsetSec}) should be < offset ${i + 1} (${math.transitions[i + 1].offsetSec})",
                math.transitions[i].offsetSec < math.transitions[i + 1].offsetSec
            )
        }
    }

    @Test
    fun testTransitionTimelineCalculator_mixedTransitionsAndPlainCuts() {
        // 4 clips of 4s each (sum = 16s)
        // Gap 0: 1.0s transition
        // Gap 1: plain cut (no transition)
        // Gap 2: 1.0s transition
        val clipDurations = listOf(4.0, 4.0, 4.0, 4.0)
        val transitions = listOf(
            EditOperation.Transition(0, "fade", 1000L),
            EditOperation.Transition(2, "slideleft", 1000L)
        )

        val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)
        val expectedTotal = 16.0 - 2.0 // 14.0s
        assertEquals(expectedTotal, math.totalDurationSec, 1e-6)

        assertEquals(2, math.transitions.size)
        assertEquals(0, math.transitions[0].gapIndex)
        assertEquals(3.0, math.transitions[0].offsetSec, 1e-6)

        assertEquals(2, math.transitions[1].gapIndex)
        // Clip 0 (4) - 1 + Clip 1 (4) = 7s. Clip 2 plain cut (+4) = 11s.
        // Trans 2 offset = 11 - 1 = 10s.
        assertEquals(10.0, math.transitions[1].offsetSec, 1e-6)
        assertTrue(math.transitions[1].offsetSec > math.transitions[0].offsetSec)
    }

    @Test
    fun testTransitionTimelineCalculator_clampingExcessiveDuration() {
        val clipDurations = listOf(1.0, 2.0)
        // Transition is 3.0s, but left clip is only 1.0s
        val transitions = listOf(
            EditOperation.Transition(0, "fade", 3000L)
        )
        val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)

        // Clamped to left clip duration (1.0s)
        assertEquals(1.0, math.transitions[0].durationSec, 1e-6)
        assertEquals(0.0, math.transitions[0].offsetSec, 1e-6)
        // Total duration: 0.0 + 2.0 = 2.0s
        assertEquals(2.0, math.totalDurationSec, 1e-6)
    }

    // ── 2. Transition Index Remapping on Split ────────────────────────────────

    @Test
    fun testSplitVideoSegment_remapsTransitionsWhenSplittingClipZero() {
        val viewModel = VideoEditingViewModel()
        val initialOps = listOf(
            EditOperation.Trim(0L, 6000L),
            EditOperation.Merge(
                listOf(
                    EditOperation.MergeItem(dummyUri, 4000L, 0L, 4000L),
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L)
                )
            ),
            EditOperation.Transition(0, "fade", 1000L),      // Between clip 0 and clip 1
            EditOperation.Transition(1, "wipeleft", 1000L)   // Between clip 1 and clip 2
        )
        val project = VideoProject(sourceUri = dummyUri, sourceName = "test_prj", operations = initialOps)
        viewModel.loadProject(project)

        // Split clip 0 at 3000ms
        viewModel.splitVideoSegment(0, 3000L, dummyUri, 6000L)

        val updatedOps = viewModel.project.value!!.operations
        val updatedTransitions = updatedOps.filterIsInstance<EditOperation.Transition>()

        assertEquals(2, updatedTransitions.size)
        // Transition that was at index 0 (between original clip 0 and clip 1) should now be at index 1
        // (between the second half of clip 0 and original clip 1)
        val t0 = updatedTransitions.find { it.type == "fade" }
        assertNotNull(t0)
        assertEquals(1, t0!!.index)

        // Transition that was at index 1 should now be at index 2
        val t1 = updatedTransitions.find { it.type == "wipeleft" }
        assertNotNull(t1)
        assertEquals(2, t1!!.index)

        // No transition at index 0 (seam between split halves)
        assertNull(updatedTransitions.find { it.index == 0 })
    }

    @Test
    fun testSplitVideoSegment_leavesEarlierTransitionsIntact() {
        val viewModel = VideoEditingViewModel()
        val initialOps = listOf(
            EditOperation.Trim(0L, 6000L),
            EditOperation.Merge(
                listOf(
                    EditOperation.MergeItem(dummyUri, 4000L, 0L, 4000L),
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L)
                )
            ),
            EditOperation.Transition(0, "fade", 1000L),      // Between clip 0 and clip 1
            EditOperation.Transition(1, "wipeleft", 1000L)   // Between clip 1 and clip 2
        )
        val project = VideoProject(sourceUri = dummyUri, sourceName = "test_prj", operations = initialOps)
        viewModel.loadProject(project)

        // Split clip 1 (which is the first MergeItem) at 2000ms
        viewModel.splitVideoSegment(1, 2000L, dummyUri, 4000L)

        val updatedOps = viewModel.project.value!!.operations
        val updatedTransitions = updatedOps.filterIsInstance<EditOperation.Transition>()

        assertEquals(2, updatedTransitions.size)
        // Transition at index 0 is BEFORE split index 1 -> stays at index 0
        val t0 = updatedTransitions.find { it.type == "fade" }
        assertNotNull(t0)
        assertEquals(0, t0!!.index)

        // Transition at index 1 is at split index 1 -> shifts to index 2
        val t1 = updatedTransitions.find { it.type == "wipeleft" }
        assertNotNull(t1)
        assertEquals(2, t1!!.index)
    }

    // ── 3. Transition Index Remapping on Delete ───────────────────────────────

    @Test
    fun testDeleteSequenceSegment_removesTouchingTransitionsAndShiftsSubsequent() {
        val viewModel = VideoEditingViewModel()
        // 4 clips: primary (clip 0) and 3 merge items (clips 1, 2, 3)
        val initialOps = listOf(
            EditOperation.Trim(0L, 5000L),
            EditOperation.Merge(
                listOf(
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L),
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L),
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L)
                )
            ),
            EditOperation.Transition(0, "fade", 1000L),      // Gap 0 (clip 0-1)
            EditOperation.Transition(1, "wipeleft", 1000L),  // Gap 1 (clip 1-2)
            EditOperation.Transition(2, "zoomin", 1000L)     // Gap 2 (clip 2-3)
        )
        val project = VideoProject(sourceUri = dummyUri, sourceName = "test_prj", operations = initialOps)
        viewModel.loadProject(project)

        // Delete clip 2 (index 2)
        // Touching boundaries are gap 1 (between 1 and 2) and gap 2 (between 2 and 3).
        // Both transition 1 and transition 2 must be removed!
        // Transition 0 (before clip 2) must remain at index 0.
        viewModel.deleteSequenceSegment(2)

        val updatedOps = viewModel.project.value!!.operations
        val updatedTransitions = updatedOps.filterIsInstance<EditOperation.Transition>()

        assertEquals(1, updatedTransitions.size)
        assertEquals(0, updatedTransitions[0].index)
        assertEquals("fade", updatedTransitions[0].type)
    }

    @Test
    fun testDeleteSequenceSegment_deletePrimaryClipPromotesAndShiftsTransitions() {
        val viewModel = VideoEditingViewModel()
        // 3 clips: primary (clip 0) and 2 merge items (clips 1, 2)
        val initialOps = listOf(
            EditOperation.Trim(0L, 5000L),
            EditOperation.Merge(
                listOf(
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L),
                    EditOperation.MergeItem(dummyUri, 5000L, 0L, 5000L)
                )
            ),
            EditOperation.Transition(0, "fade", 1000L),      // Gap 0 (clip 0-1)
            EditOperation.Transition(1, "wipeleft", 1000L)   // Gap 1 (clip 1-2)
        )
        val project = VideoProject(sourceUri = dummyUri, sourceName = "test_prj", operations = initialOps)
        viewModel.loadProject(project)

        // Delete clip 0 (primary clip)
        // Transition 0 (touching clip 0) is removed.
        // Transition 1 (between original clip 1 and clip 2) shifts from index 1 to index 0.
        viewModel.deleteSequenceSegment(0)

        val updatedOps = viewModel.project.value!!.operations
        val updatedTransitions = updatedOps.filterIsInstance<EditOperation.Transition>()

        assertEquals(1, updatedTransitions.size)
        assertEquals(0, updatedTransitions[0].index)
        assertEquals("wipeleft", updatedTransitions[0].type)
    }

    // ── 4. Chained Transition PTS Normalization in FFmpeg Command ─────────────

    @Test
    fun testBuildConsolidatedFFmpegCommand_normalizesPTSBetweenChainedTransitions() {
        val viewModel = VideoEditingViewModel()
        val initialOps = listOf(
            EditOperation.Trim(0L, 4000L),
            EditOperation.Merge(
                listOf(
                    EditOperation.MergeItem(dummyUri, 4000L, 0L, 4000L),
                    EditOperation.MergeItem(dummyUri, 4000L, 0L, 4000L)
                )
            ),
            EditOperation.Transition(0, "fade", 1000L),
            EditOperation.Transition(1, "wipeleft", 1000L)
        )
        val project = VideoProject(sourceUri = dummyUri, sourceName = "test_prj", operations = initialOps)
        viewModel.loadProject(project)

        val cmd = viewModel.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/fake/video.mp4",
            outputFilePath = "/fake/output.mp4"
        )
        assertNotNull(cmd)

        // Verify video normalization after xfade: settb=1/30,setpts=N
        assertTrue("FFmpeg command must normalize video PTS with settb=1/30,setpts=N", cmd!!.contains("settb=1/30,setpts=N"))
        // Verify audio normalization after acrossfade: asetpts=PTS-STARTPTS,aresample=async=1000
        assertTrue("FFmpeg command must normalize audio PTS with asetpts=PTS-STARTPTS,aresample=async=1000",
            cmd.contains("asetpts=PTS-STARTPTS,aresample=async=1000"))
        // Verify xfade chaining uses raw and norm labels
        assertTrue("FFmpeg command must chain normalized transition labels", cmd.contains("norm_xfv0"))
    }
}
