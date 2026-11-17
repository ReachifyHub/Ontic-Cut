package com.boostlyte.vydra.export

import android.net.DummyUri
import com.boostlyte.vydra.models.CanvasAspectRatio
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.viewmodels.VideoEditingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCompletionUiStateTest {

    @Test
    fun testExportCompletionStateLifecycle_videoAndAudio() {
        val vm = VideoEditingViewModel()

        // 1. Default state
        assertFalse(vm.uiState.value.isExporting)
        assertFalse(vm.uiState.value.isExportComplete)
        assertNull(vm.uiState.value.exportedUri)

        // 2. Start video export
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = false)
        vm.startExport()
        assertTrue(vm.uiState.value.isExporting)
        assertFalse(vm.uiState.value.isExportComplete)
        assertFalse(vm.uiState.value.exportedIsAudioOnly)

        vm.updateExportProgress(65)
        assertEquals(65, vm.uiState.value.exportProgress)

        // 3. Complete export with URI and display location
        val uri = "content://media/external/video/media/42"
        vm.completeExport(uri, "Movies/Vydra")
        val completeState = vm.uiState.value
        assertFalse(completeState.isExporting)
        assertTrue(completeState.isExportComplete)
        assertEquals(100, completeState.exportProgress)
        assertEquals(uri, completeState.exportedUri)
        assertEquals("Movies/Vydra", completeState.exportedDisplayLocation)
        assertFalse(completeState.exportedIsAudioOnly)

        // 4. Dismiss completion card via Done
        vm.dismissExportCompletion()
        val dismissed = vm.uiState.value
        assertFalse(dismissed.isExporting)
        assertFalse(dismissed.isExportComplete)
        assertNull(dismissed.exportedUri)
        assertNull(dismissed.exportedDisplayLocation)

        // 5. Audio-only export flow
        vm.setExportSettings(resolution = 1080, fps = 30, audioOnly = true)
        vm.startExport()
        vm.completeExport("file:///storage/emulated/0/Music/Vydra/out.mp3", "Music/Vydra")
        val audioComplete = vm.uiState.value
        assertTrue(audioComplete.isExportComplete)
        assertTrue(audioComplete.exportedIsAudioOnly)
        assertEquals("Music/Vydra", audioComplete.exportedDisplayLocation)

        // 6. Error clears completion flags
        vm.exportError("Disk full")
        assertFalse(vm.uiState.value.isExportComplete)
        assertNull(vm.uiState.value.exportedUri)
        assertEquals("Disk full", vm.uiState.value.errorMessage)
    }

    @Test
    fun testSinglePassFilterGraph_staticImagesUseExactTrimFrameCount() {
        val mainUri = DummyUri.fromPath("/tmp/img0.jpg")
        val secondUri = DummyUri.fromPath("/tmp/img1.jpg")
        val project = VideoProject(
            sourceUri = mainUri,
            sourceName = "SyncProject",
            canvasAspectRatio = CanvasAspectRatio.LANDSCAPE_16_9,
            operations = listOf(
                EditOperation.Trim(startMs = 0L, endMs = 4100L),
                EditOperation.Merge(
                    items = listOf(
                        EditOperation.MergeItem(
                            uri = secondUri,
                            durationMs = 3500L,
                            trimStartMs = 0L,
                            trimEndMs = 3500L,
                            isImage = true
                        )
                    )
                ),
                EditOperation.Transition(index = 0, type = "fade", durationMs = 100L)
            )
        )

        val vm = VideoEditingViewModel()
        vm.loadProject(project)
        vm.setExportSettings(resolution = 720, fps = 30, audioOnly = false)

        val cmd = vm.buildConsolidatedFFmpegCommand(
            sourceFilePath = "/tmp/img0.jpg",
            outputFilePath = "/tmp/out.mp4"
        )
        assertNotNull("Command must not be null", cmd)
        val cmdStr = cmd!!
        // 4100ms at 30fps = round(4.1 * 30) = 123 frames; 3500ms at 30fps = 105 frames
        assertTrue("Single-pass graph must trim clip 0 to exact frame count: $cmdStr", cmdStr.contains("trim=end_frame=123"))
        assertTrue("Single-pass graph must trim clip 1 to exact frame count: $cmdStr", cmdStr.contains("trim=end_frame=105"))
        assertTrue("Single-pass graph must include xfade with exact offset=4.000: $cmdStr", cmdStr.contains("offset=4.000"))
    }
}
