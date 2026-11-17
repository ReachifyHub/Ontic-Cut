package com.boostlyte.vydra.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoEditingUiState
import com.boostlyte.vydra.models.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import com.boostlyte.vydra.services.ChunkPlanner
import com.boostlyte.vydra.services.ChunkSpec
import com.boostlyte.vydra.services.ChunkPlan
import com.boostlyte.vydra.services.ChunkCommand
import com.boostlyte.vydra.services.ChunkExportPlan
import com.boostlyte.vydra.services.ChunkClip

// ExportQuality enum removed in favor of individual parameters

class VideoEditingViewModel : ViewModel() {

    companion object {
        const val FORCE_LEGACY_EXPORT = true
        private const val TAG = "VideoEditingViewModel"
        const val MAX_UNDO_STACK_SIZE = 30
    }


    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<HistoryState>>(emptyList())
    private val _redoStack = MutableStateFlow<List<HistoryState>>(emptyList())
    private val _exportResolution = MutableStateFlow(1080)
    private val _exportFps = MutableStateFlow(30)
    private val _exportAudioOnly = MutableStateFlow(false)
    private val _selectedOperationId = MutableStateFlow<String?>(null)

    private val _hasUnsavedEdits = MutableStateFlow(false)

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val selectedOperationId: StateFlow<String?> = _selectedOperationId.asStateFlow()
    val undoStack: StateFlow<List<HistoryState>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<HistoryState>> = _redoStack.asStateFlow()
    val exportResolution: StateFlow<Int> = _exportResolution.asStateFlow()
    val exportFps: StateFlow<Int> = _exportFps.asStateFlow()
    val exportAudioOnly: StateFlow<Boolean> = _exportAudioOnly.asStateFlow()
    val hasUnsavedEdits: StateFlow<Boolean> = _hasUnsavedEdits.asStateFlow()

    fun markProjectSaved() {
        _hasUnsavedEdits.value = false
    }

    fun markHasUnsavedEdits() {
        _hasUnsavedEdits.value = true
    }

    fun setExportSettings(resolution: Int, fps: Int, audioOnly: Boolean) {
        _exportResolution.value = resolution
        _exportFps.value = fps
        _exportAudioOnly.value = audioOnly
    }

    fun updateCanvasAspectRatio(aspectRatio: com.boostlyte.vydra.models.CanvasAspectRatio) {
        executeCommand(com.boostlyte.vydra.commands.MutateProjectCommand("Change Canvas Ratio") { project ->
            project.copy(canvasAspectRatio = aspectRatio)
        })
        markHasUnsavedEdits()
    }

    val operations: StateFlow<List<EditOperation>>
        get() = MutableStateFlow(project.value?.operations ?: emptyList()).asStateFlow()

    fun initializeProject(sourceUri: Uri, sourceName: String) {
        _project.value = VideoProject(sourceUri = sourceUri, sourceName = sourceName)
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _hasUnsavedEdits.value = false
        updateUiState { it.copy(canUndo = false) }
    }

    fun loadProject(project: VideoProject) {
        val normalizedProject = project.copy(
            operations = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(project.operations)
        )
        _project.value = normalizedProject
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _hasUnsavedEdits.value = false
        updateUiState { it.copy(canUndo = false, pendingOperationCount = normalizedProject.getOperationCount()) }
    }


    data class HistoryState(
        val project: VideoProject,
        val commandDescription: String
    )

    fun executeCommand(command: com.boostlyte.vydra.commands.EditCommand) {
        val current = _project.value ?: return
        
        val historyState = HistoryState(current, command.description)
        val stack = _undoStack.value + historyState
        _undoStack.value = if (stack.size > MAX_UNDO_STACK_SIZE) stack.takeLast(MAX_UNDO_STACK_SIZE) else stack
        
        _redoStack.value = emptyList()
        
        val newProject = command.execute(current)
        _project.value = newProject
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = newProject.getOperationCount(),
                canUndo = true,
                canRedo = false
            )
        }
        markHasUnsavedEdits()
    }

    fun undo() {
        val current = _project.value ?: return
        val stack = _undoStack.value
        if (stack.isEmpty()) return
        
        val previousState = stack.last()
        _undoStack.value = stack.dropLast(1)
        
        // Push current to redo stack.
        val redoState = HistoryState(current, previousState.commandDescription)
        val rStack = _redoStack.value + redoState
        _redoStack.value = if (rStack.size > MAX_UNDO_STACK_SIZE) rStack.takeLast(MAX_UNDO_STACK_SIZE) else rStack
        
        _project.value = previousState.project
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = previousState.project.getOperationCount(),
                canUndo = _undoStack.value.isNotEmpty(),
                canRedo = true
            )
        }
        markHasUnsavedEdits()
    }

    fun selectOperation(id: String?) {
        _selectedOperationId.value = id
    }

    fun redo() {
        val current = _project.value ?: return
        val stack = _redoStack.value
        if (stack.isEmpty()) return
        
        val nextState = stack.last()
        _redoStack.value = stack.dropLast(1)
        
        // Push current to undo stack.
        val undoState = HistoryState(current, nextState.commandDescription)
        val uStack = _undoStack.value + undoState
        _undoStack.value = if (uStack.size > MAX_UNDO_STACK_SIZE) uStack.takeLast(MAX_UNDO_STACK_SIZE) else uStack
        
        _project.value = nextState.project
        
        updateUiState { state ->
            state.copy(
                pendingOperationCount = nextState.project.getOperationCount(),
                canUndo = true,
                canRedo = _redoStack.value.isNotEmpty()
            )
        }
        markHasUnsavedEdits()
    }
    fun setPreviewOperationIndex(operationIndex: Int) {
        updateUiState { it.copy(currentPreviewOperationIndex = operationIndex) }
    }

    // ── Private filter-building helpers ─────────────────────────────────────

    /** Build a crop filter expression for an aspect ratio. */
    private fun buildCropFilterExpr(op: EditOperation.Crop): String? {
        val hasCustomBounds = op.aspectRatio == "Custom" || op.xFraction > 0f || op.yFraction > 0f || op.wFraction < 1f || op.hFraction < 1f
        if (hasCustomBounds) {
            val w = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.wFraction)
            val h = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.hFraction)
            val x = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.xFraction)
            val y = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.yFraction)
            return "crop=w=$w:h=$h:x='min($x\\,iw-($w))':y='min($y\\,ih-($h))',setsar=1"
        }

        return when (op.aspectRatio) {
            "16:9" -> "crop='trunc(min(iw\\,ih*16/9)/2)*2':'trunc(min(ih\\,iw*9/16)/2)*2',setsar=1"
            "9:16" -> "crop='trunc(min(iw\\,ih*9/16)/2)*2':'trunc(min(ih\\,iw*16/9)/2)*2',setsar=1"
            "1:1"  -> "crop='trunc(min(iw\\,ih)/2)*2':'trunc(min(iw\\,ih)/2)*2',setsar=1"
            else   -> null
        }
    }

    /** Build a drawtext filter expression for a text operation. */
    private fun buildDrawtextExpr(
        op: EditOperation.AddText,
        fontFilePath: String?,
        timeOffsetMs: Long = 0L,
        sourceVideoHeight: Int = 1080,
        density: Float = 1.0f
    ): String {
        val fontToUse = op.fontPath ?: fontFilePath

        val isAutoCaption = op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID
        val heightScale = (sourceVideoHeight.toFloat() / 1080f).coerceIn(0.25f, 3.0f)
        val effectiveDensity = if (density > 0.5f) density else 2.75f
        val marginX = (24f * heightScale).toInt().coerceAtLeast(8)
        val marginY = (24f * heightScale).toInt().coerceAtLeast(8)
        val bottomMargin = (40f * heightScale).toInt().coerceAtLeast(14)

        // Use WYSIWYG fractional coordinates if available, else fall back to explicit coordinates
        val (posX, posY) = if (op.positionKeyframes.isNotEmpty()) {
            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
            Pair("'(w*($xExpr))-(tw/2)'", "'(h*($yExpr))-(th/2)'")
        } else if (op.hasCustomPosition()) {
            Pair("'(w*${op.relativeX})-(tw/2)'", "'(h*${op.relativeY})-(th/2)'")
        } else if (isAutoCaption) {
            Pair("(w-tw)/2", "h-th-$bottomMargin")
        } else {
            when (op.position) {
                TextPosition.TOP_LEFT -> Pair("$marginX", "$marginY")
                TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> Pair("(w-tw)/2", "$marginY")
                TextPosition.TOP_RIGHT -> Pair("w-tw-$marginX", "$marginY")
                TextPosition.CENTER_LEFT -> Pair("$marginX", "(h-th)/2")
                TextPosition.CENTER -> Pair("(w-tw)/2", "(h-th)/2")
                TextPosition.CENTER_RIGHT -> Pair("w-tw-$marginX", "(h-th)/2")
                TextPosition.BOTTOM_LEFT -> Pair("$marginX", "h-th-$bottomMargin")
                TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> Pair("(w-tw)/2", "h-th-$bottomMargin")
                TextPosition.BOTTOM_RIGHT -> Pair("w-tw-$marginX", "h-th-$bottomMargin")
            }
        }

        val alphaExpr = if (op.opacityKeyframes.isNotEmpty()) {
            buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
        } else if (op.opacity < 1.0f) {
            "${op.opacity}"
        } else null

        val safeStartMs = op.startTimeMs?.let { maxOf(0L, it - timeOffsetMs) }
        val rawEndMs = op.endTimeMs?.let { maxOf(0L, it - timeOffsetMs) }
        val safeEndMs = if (safeStartMs != null && rawEndMs != null && rawEndMs <= safeStartMs) {
            safeStartMs + 1000L
        } else {
            rawEndMs
        }

        val boxColor = if (isAutoCaption) "black@0.5" else null
        val boxBorderW = if (isAutoCaption) (4f * heightScale).toInt().coerceAtLeast(2) else 0

        val (shadowColor, shadowOffset) = if (op.borderThickness > 0) {
            val resScale = (sourceVideoHeight.toFloat() / 720f).coerceIn(0.5f, 3.0f)
            val offset = maxOf(1, Math.round(op.borderThickness * 0.8f * resScale))
            Pair("black@0.75", offset)
        } else Pair(null, 0)

        val fontColor = formatColorForFFmpeg(op.color)

        // Scale font size relative to output resolution height so text maintains identical visual proportion across 1080p, 720p, 480p
        val effectiveFontSize = if (isAutoCaption) {
            (op.fontSize * effectiveDensity * heightScale).toInt().coerceAtLeast(12)
        } else {
            (op.fontSize * heightScale * (effectiveDensity / 2.75f)).toInt().coerceAtLeast(10)
        }

        return com.boostlyte.vydra.utils.FFmpegFilterUtils.buildValidatedDrawtextFilter(
            text = op.text,
            fontFilePath = fontToUse,
            fontSize = effectiveFontSize,
            fontColor = fontColor,
            x = posX,
            y = posY,
            startTimeMs = safeStartMs,
            endTimeMs = safeEndMs,
            boxColor = boxColor,
            boxBorderW = boxBorderW,
            shadowColor = shadowColor,
            shadowOffset = shadowOffset,
            alphaExpr = alphaExpr,
            textAlign = if (op.textAlign.isNotEmpty()) op.textAlign else null,
            lineSpacing = (op.lineSpacing * heightScale).toInt()
        )
    }

    private fun hasAnimatedMask(mc: EditOperation.MaskConfig): Boolean {
        return mc.shape != EditOperation.MaskShape.NONE && (
            mc.positionKeyframes.isNotEmpty() ||
            mc.sizeKeyframes.isNotEmpty() ||
            mc.rotationKeyframes.isNotEmpty() ||
            mc.featherKeyframes.isNotEmpty()
        )
    }

    private fun isImageFilePath(path: String): Boolean {
        return path.endsWith(".png", ignoreCase = true) ||
               path.endsWith(".jpg", ignoreCase = true) ||
               path.endsWith(".jpeg", ignoreCase = true) ||
               path.endsWith(".webp", ignoreCase = true)
    }

    private fun buildFFmpegMaskAlphaExpr(
        mc: EditOperation.MaskConfig,
        cx: String,
        cy: String,
        mw: String,
        mh: String,
        cosA: String,
        sinA: String,
        startTimeMs: Long = 0L,
        timeVar: String = "T",
        timeOffsetMs: Long = 0L
    ): String {
        val dx = "(X - ($cx))"
        val dy = "(Y - ($cy))"
        val rx = "($dx * $cosA + $dy * $sinA)"
        val ry = "(-$dx * $sinA + $dy * $cosA)"

        val featherExpr = if (mc.featherKeyframes.isNotEmpty()) {
            buildFFmpegInterpolationExpr(mc.featherKeyframes, false, mc.feather, startTimeMs, timeVar, timeOffsetMs = timeOffsetMs)
        } else {
            mc.feather.toString()
        }
        val fPx = "(max(0.1\\, ($featherExpr)*0.5))"

        val shapeExpr = when (mc.shape) {
            EditOperation.MaskShape.RECTANGLE ->
                "clip(255 * ($mw/2 + $fPx - abs($rx)) / (2 * $fPx)\\, 0\\, 255) * clip(255 * ($mh/2 + $fPx - abs($ry)) / (2 * $fPx)\\, 0\\, 255) / 255"
            EditOperation.MaskShape.ELLIPSE ->
                "clip(255 * (1 + ($fPx/($mw/2)) - sqrt(pow($rx/($mw/2)\\, 2) + pow($ry/($mh/2)\\, 2))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            EditOperation.MaskShape.SPLIT ->
                "clip(255 * ($ry + $fPx) / (2 * $fPx)\\, 0\\, 255)"
            EditOperation.MaskShape.SHUTTER ->
                "clip(255 * ($mh/2 + $fPx - abs($ry)) / (2 * $fPx)\\, 0\\, 255)"
            EditOperation.MaskShape.HEART ->
                "clip(255 * (1 + ($fPx/($mw/2)) - sqrt(pow($rx/($mw/2)\\, 2) + pow(($ry + abs($rx)*0.5)/($mh/2)\\, 2))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            EditOperation.MaskShape.STAR ->
                "clip(255 * (1 + ($fPx/($mw/2)) - (pow(abs($rx)/($mw/2)\\, 0.7) + pow(abs($ry)/($mh/2)\\, 0.7))) / (2 * ($fPx/($mw/2)))\\, 0\\, 255)"
            else -> "255"
        }

        return if (mc.isInverted) "(255 - ($shapeExpr))" else "($shapeExpr)"
    }

    fun buildFFmpegInterpolationExpr(
        keyframes: List<EditOperation.KeyframePoint>,
        useValueY: Boolean,
        defaultValue: Float,
        startTimeMs: Long,
        timeVar: String = "t",
        timeOffsetMs: Long = 0L
    ): String {
        if (keyframes.isEmpty()) return defaultValue.toString()
        val sorted = keyframes.sortedBy { it.timeMs }
        val effectiveStartSec = (startTimeMs - timeOffsetMs) / 1000.0
        val tRel = "($timeVar-$effectiveStartSec)"
        
        if (sorted.size == 1) {
            val v = if (useValueY) sorted[0].valueY else sorted[0].valueX
            return v.toString()
        }
        
        var expr = ""
        val lastVal = if (useValueY) sorted.last().valueY else sorted.last().valueX
        expr = lastVal.toString()
        
        for (i in sorted.size - 2 downTo 0) {
            val k1 = sorted[i]
            val k2 = sorted[i + 1]
            val t1 = k1.timeMs / 1000.0
            val t2 = k2.timeMs / 1000.0
            val v1 = if (useValueY) k1.valueY else k1.valueX
            val v2 = if (useValueY) k2.valueY else k2.valueX
            
            val diffVal = v2 - v1
            val diffTime = t2 - t1
            val segmentExpr = if (diffTime > 0) {
                "$v1 + ($diffVal) * ($tRel - $t1) / ($diffTime)"
            } else {
                v1.toString()
            }
            
            expr = "if(lt($tRel\\, $t2)\\, $segmentExpr\\, $expr)"
        }
        
        val firstVal = if (useValueY) sorted.first().valueY else sorted.first().valueX
        val firstTime = sorted.first().timeMs / 1000.0
        expr = "if(lt($tRel\\, $firstTime)\\, $firstVal\\, $expr)"
        
        return expr
    }

    private fun formatColorForFFmpeg(colorHex: String): String {
        if (!colorHex.startsWith("#")) return colorHex
        return when (colorHex.length) {
            9 -> { // #AARRGGBB -> 0xRRGGBBAA
                val aa = colorHex.substring(1, 3)
                val rrggbb = colorHex.substring(3)
                "0x$rrggbb$aa"
            }
            7 -> { // #RRGGBB -> 0xRRGGBB
                "0x${colorHex.substring(1)}"
            }
            else -> colorHex
        }
    }

    fun buildEnableExpr(startTimeMs: Long?, endTimeMs: Long?): String {
        return com.boostlyte.vydra.utils.FFmpegFilterUtils.buildEnableFilter(startTimeMs, endTimeMs)
    }

    private fun getFFmpegFilterForAdjust(op: EditOperation.Adjust): String? {
        val filters = mutableListOf<String>()

        // 1. eq filter (brightness, contrast, saturation)
        if (op.brightness != 0 || op.contrast != 0 || op.saturation != 0) {
            val b = op.brightness / 100.0
            val c = 1.0 + (op.contrast / 100.0)
            val s = 1.0 + (op.saturation / 100.0)
            filters.add("eq=brightness=$b:contrast=$c:saturation=$s")
        }

        // 2. exposure
        if (op.exposure != 0) {
            val ev = (op.exposure / 100.0) * 3.0
            filters.add("exposure=exposure=$ev")
        }

        // 3. warmth (colorbalance)
        if (op.warmth != 0) {
            val w = (op.warmth / 100.0) * 0.3
            filters.add("colorbalance=rs=$w:rm=$w:rh=$w:bs=${-w}:bm=${-w}:bh=${-w}")
        }

        // 4. shadows & highlights (emulated via standard FFmpeg 'curves' filter)
        if (op.shadow != 0 || op.highlights != 0) {
            val yShadow = (0.25 + (op.shadow / 100.0) * 0.15).coerceIn(0.01, 0.99)
            val yHighlight = (0.75 + (op.highlights / 100.0) * 0.15).coerceIn(0.01, 0.99)
            val sStr = String.format(java.util.Locale.US, "%.3f", yShadow)
            val hStr = String.format(java.util.Locale.US, "%.3f", yHighlight)
            filters.add("curves=m='0/0 0.25/$sStr 0.75/$hStr 1/1'")
        }

        // 5. sharpen (unsharp)
        if (op.sharpen != 0) {
            val amt = (op.sharpen / 100.0) * 1.5
            filters.add("unsharp=luma_amount=$amt")
        }

        // 6. vignette
        if (op.vignette != 0) {
            val angle = 1.5 - (op.vignette / 100.0) * 0.9
            filters.add("vignette=angle=$angle")
        }

        return if (filters.isNotEmpty()) filters.joinToString(",") else null
    }

    private fun getFFmpegFilterForName(name: String): String? {
        return when (name.lowercase()) {
            "vintage" -> "curves=preset=vintage"
            "warm" -> "colorchannelmixer=1.1:0:0:0:0:1.0:0:0:0:0:0.9"
            "cool" -> "colorchannelmixer=0.9:0:0:0:0:1.0:0:0:0:0:1.1"
            "contrast" -> "curves=preset=strong_contrast"
            "monochrome" -> "hue=s=0"
            "vignette" -> "vignette"
            "negative" -> "curves=preset=negative"
            "crossprocess" -> "curves=preset=cross_process"
            else -> null
        }
    }

    /**
     * Build the video portion of a filter_complex graph from non-merge, non-audio operations.
     *
     * @return Pair of (list of filter stages, final stream label) — e.g. (["[0:v]crop=...[v0]", "[v0]drawtext=...[v1]"], "[v1]")
     *         If no video filters needed, returns (emptyList, "[0:v]")
     */
    fun buildVideoFilterStages(
        operations: List<EditOperation>,
        fontFilePath: String?,
        inputLabel: String = "[0:v]",
        imageInputIndices: List<Pair<Int, EditOperation.AddImageOverlay>> = emptyList(),
        density: Float = 1.0f,
        sourceVideoHeight: Int = 1080,
        timeOffsetMs: Long = 0L
    ): Pair<List<String>, String> {
        val stages = mutableListOf<String>()
        var currentLabel = inputLabel
        var stageIndex = 0

        // Process overlays first so their relative coordinates align with the uncropped original video, matching the UI.
        // Deduplicate: If explicit AddSubtitles cues are present, suppress duplicate auto-caption AddText overlays and matching transcript overlays.
        // Also deduplicate auto-caption cues that share the same start/end time windows.
        val subtitleOps = operations.filterIsInstance<EditOperation.AddSubtitles>()
        val hasExplicitSubtitles = subtitleOps.any { it.cues.isNotEmpty() }
        val subtitleCueTexts = subtitleOps.flatMap { it.cues.map { cue -> cue.text.trim().lowercase() } }.filter { it.isNotBlank() }.toSet()
        val autoCaptionTexts = operations.filterIsInstance<EditOperation.AddText>()
            .filter { it.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID }
            .map { it.text.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val allCaptionCueTexts = subtitleCueTexts + autoCaptionTexts
        val hasAnyCaptionsOrSubtitles = allCaptionCueTexts.isNotEmpty()

        Log.i(TAG, "buildVideoFilterStages: total ops=${operations.size}, explicitSubtitles=$hasExplicitSubtitles, anyCaptions=$hasAnyCaptionsOrSubtitles")
        for ((idx, op) in operations.withIndex()) {
            when (op) {
                is EditOperation.AddText -> Log.i(TAG, "  Incoming Op[$idx] AddText: text='${op.text.take(40)}', pos=${op.position}, relX=${op.relativeX}, relY=${op.relativeY}, start=${op.startTimeMs}, end=${op.endTimeMs}, groupId=${op.groupId}")
                is EditOperation.AddSubtitles -> Log.i(TAG, "  Incoming Op[$idx] AddSubtitles: fileName='${op.fileName}', cues=${op.cues.size}, pos=${op.position}, relX=${op.relativeX}, relY=${op.relativeY}")
                else -> Log.d(TAG, "  Incoming Op[$idx] ${op::class.simpleName}")
            }
        }

        val seenAutoCaptionTimes = mutableSetOf<Pair<Long?, Long?>>()
        val overlayOps = operations.filterNot { op ->
            if (op is EditOperation.Crop) return@filterNot true
            if (op is EditOperation.AddText) {
                if (op.text.isBlank()) return@filterNot true
                val cleanText = op.text.trim().lowercase()

                // If explicit AddSubtitles are active, suppress duplicate AddText auto-caption cards
                if (hasExplicitSubtitles && op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                    Log.i(TAG, "Filtering out duplicate AddText auto-caption card: '${op.text.take(30)}'")
                    return@filterNot true
                }

                // If ANY captions/subtitles are active, suppress matching or overlapping full transcript / spoken text cards
                if (hasAnyCaptionsOrSubtitles && op.groupId != com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                    if (cleanText in allCaptionCueTexts) {
                        Log.i(TAG, "Filtering out stray transcript text matching caption cue: '${op.text.take(30)}'")
                        return@filterNot true
                    }
                    if (allCaptionCueTexts.any { it.length >= 3 && (cleanText.contains(it) || it.contains(cleanText)) }) {
                        Log.i(TAG, "Filtering out stray transcript text containing caption cue text: '${op.text.take(30)}'")
                        return@filterNot true
                    }
                    // Suppress unbounded or long spoken transcript text cards spanning the video
                    if ((op.startTimeMs == null || op.endTimeMs == null) && cleanText.length > 40) {
                        Log.i(TAG, "Filtering out unbounded long narration script text card: '${op.text.take(30)}'")
                        return@filterNot true
                    }
                    // Suppress any non-custom unpositioned text card when explicit subtitles are active
                    if (hasExplicitSubtitles && !op.hasCustomPosition() && op.positionKeyframes.isEmpty()) {
                        Log.i(TAG, "Filtering out unpositioned text card because explicit subtitles are active: '${op.text.take(30)}'")
                        return@filterNot true
                    }
                }

                if (op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                    val key = Pair(op.startTimeMs, op.endTimeMs)
                    if (seenAutoCaptionTimes.contains(key)) {
                        Log.i(TAG, "Filtering out duplicate auto-caption at same time window: ${op.startTimeMs}..${op.endTimeMs}")
                        return@filterNot true
                    }
                    seenAutoCaptionTimes.add(key)
                }

                // Suppress stray unpositioned script / transcript text at default top position
                if (!op.hasCustomPosition() && op.positionKeyframes.isEmpty()) {
                    if (op.position == TextPosition.TOP_CENTER || op.position == TextPosition.CENTER_TOP || op.position == TextPosition.TOP_LEFT || op.position == TextPosition.TOP_RIGHT) {
                        Log.i(TAG, "Filtering out unpositioned top text card: '${op.text.take(30)}' at ${op.position}")
                        return@filterNot true
                    }
                }
            }
            false
        }
        val cropOps = operations.filterIsInstance<EditOperation.Crop>()

        for (op in overlayOps) {
            when (op) {
                is EditOperation.AddText -> {
                    val filterExpr = buildDrawtextExpr(op, fontFilePath, timeOffsetMs, sourceVideoHeight, density)
                    Log.i(TAG, "Export filter stage [$stageIndex] AddText: text='${op.text.take(40)}', expr=$filterExpr")
                    val nextLabel = "[v$stageIndex]"
                    stages.add("$currentLabel$filterExpr$nextLabel")
                    currentLabel = nextLabel
                    stageIndex++
                }
                is EditOperation.AddImageOverlay -> {
                    val imageInputIndex = imageInputIndices.find { it.second.id == op.id }?.first
                    if (imageInputIndex != null) {
                        val radians = op.rotationAngle * Math.PI / 180.0
                        val scaledImgLabel = "[scaled_img_$stageIndex]"
                        val refVidLabel = "[ref_vid_$stageIndex]"
                        val rotatedImgLabel = "[rotated_img_$stageIndex]"
                        val nextLabel = "[v$stageIndex]"

                        val path = op.imageUri.path
                        val isGif = path != null && path.endsWith(".gif", ignoreCase = true)
                        val isVideo = path != null && (path.endsWith(".mp4", ignoreCase = true) ||
                                                       path.endsWith(".mkv", ignoreCase = true) ||
                                                       path.endsWith(".mov", ignoreCase = true) ||
                                                       path.endsWith(".3gp", ignoreCase = true))

                        if (isVideo) {
                            val startSec = ((op.startTimeMs ?: 0L) - timeOffsetMs) / 1000.0
                            val ptsLabel = "[pts_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            val speedExpr = if (op.speedKeyframes.isNotEmpty()) {
                                buildFFmpegInterpolationExpr(op.speedKeyframes, useValueY = false, defaultValue = 1.0f, startTimeMs = 0L, timeVar = "T", timeOffsetMs = timeOffsetMs)
                            } else {
                                "1.0"
                            }
                            val safeSpeedExpr = "if(gt($speedExpr,0.1),$speedExpr,0.1)"
                            stages.add("[$imageInputIndex:v]setpts='(PTS-STARTPTS)/($safeSpeedExpr)+${startSec}/TB',format=rgba$mirrorStr$ptsLabel")
                            stages.add("${ptsLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        } else {
                            val rgbaImgLabel = "[rgba_img_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            val hasMaskKeyframes = op.maskConfig.positionKeyframes.isNotEmpty() || op.maskConfig.sizeKeyframes.isNotEmpty() || op.maskConfig.rotationKeyframes.isNotEmpty() || op.maskConfig.featherKeyframes.isNotEmpty()
                            if (op.opacityKeyframes.isNotEmpty() || hasMaskKeyframes) {
                                // Static image: convert to video stream so geq's T variable advances
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr,loop=loop=-1:size=1:start=0,fps=25$rgbaImgLabel")
                            } else {
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr$rgbaImgLabel")
                            }
                            stages.add("${rgbaImgLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        }

                        var currentOverlayLabel = scaledImgLabel
                        if (op.chromaKeyColor != null) {
                            val colorkeyLabel = "[colorkey_$stageIndex]"
                            val color = formatColorForFFmpeg(op.chromaKeyColor)
                            // colorkey=color=0xRRGGBB:similarity=0.1:blend=0.1
                            stages.add("${currentOverlayLabel}colorkey=${color}:${op.chromaKeySimilarity}:0.1${colorkeyLabel}")
                            currentOverlayLabel = colorkeyLabel
                        }

                        val overlayStartMs = op.startTimeMs ?: 0L
                        if (op.opacityKeyframes.isNotEmpty()) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = overlayStartMs, timeVar = "T", timeOffsetMs = timeOffsetMs)
                            stages.add("${currentOverlayLabel}format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*($alphaExpr)'${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        } else if (op.opacity < 1.0f) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            stages.add("${currentOverlayLabel}format=rgba,colorchannelmixer=aa=${op.opacity}${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        }

                        if (op.maskConfig.shape != EditOperation.MaskShape.NONE) {
                            val maskLabel = "[mask_$stageIndex]"
                            val mc = op.maskConfig
                            val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, overlayStartMs, "T", timeOffsetMs = timeOffsetMs) + "))" else "W*${mc.relativeX}"
                            val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, overlayStartMs, "T", timeOffsetMs = timeOffsetMs) + "))" else "H*${mc.relativeY}"
                            val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), overlayStartMs, "T", timeOffsetMs = timeOffsetMs) else null
                            val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
                            val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
                            val cosA: String
                            val sinA: String
                            if (mc.rotationKeyframes.isNotEmpty()) {
                                val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, overlayStartMs, "T", timeOffsetMs = timeOffsetMs)
                                val rad = "(($rotExpr)*PI/180)"
                                cosA = "cos($rad)"
                                sinA = "sin($rad)"
                            } else {
                                val rad = Math.toRadians(mc.rotationAngle.toDouble())
                                cosA = Math.cos(rad).toString()
                                sinA = Math.sin(rad).toString()
                            }
                            
                            val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, overlayStartMs, "T")
                            
                            stages.add("${currentOverlayLabel}format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='alpha(X,Y)*($finalAlphaExpr/255)'${maskLabel}")
                            currentOverlayLabel = maskLabel
                        }

                        // Rotate image/video overlay (c=none preserves transparent background, ow/oh prevent cropping)
                        stages.add("${currentOverlayLabel}rotate=$radians:c=none:ow='rotw($radians)':oh='roth($radians)'${rotatedImgLabel}")
                        // Overlay image/video on the reference video
                        val enablePart = buildEnableExpr(op.startTimeMs?.let { (it - timeOffsetMs).coerceAtLeast(0L) }, op.endTimeMs?.let { (it - timeOffsetMs).coerceAtLeast(0L) })
                        val hasMaskKfs = op.maskConfig.positionKeyframes.isNotEmpty() || op.maskConfig.sizeKeyframes.isNotEmpty() || op.maskConfig.rotationKeyframes.isNotEmpty() || op.maskConfig.featherKeyframes.isNotEmpty()
                        val shortestPart = if (((isGif || isVideo) && op.isLooping) || (!isGif && !isVideo && (op.opacityKeyframes.isNotEmpty() || hasMaskKfs))) ":shortest=1" else ""
                        
                        val overlayX = if (op.positionKeyframes.isNotEmpty()) {
                            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
                            "x='(W*($xExpr))-(w/2)'"
                        } else {
                            "x='(W*${op.relativeX})-(w/2)'"
                        }
                        val overlayY = if (op.positionKeyframes.isNotEmpty()) {
                            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
                            "y='(H*($yExpr))-(h/2)'"
                        } else {
                            "y='(H*${op.relativeY})-(h/2)'"
                        }
                        
                        stages.add("${refVidLabel}${rotatedImgLabel}overlay=$overlayX:$overlayY${shortestPart}${enablePart}${nextLabel}")

                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                is EditOperation.AddSubtitles -> {
                    val effectiveDensity = if (density > 0.5f) density else 2.75f
                    val refHeight = if (sourceVideoHeight > 0) sourceVideoHeight.toFloat() else 1080f
                    val heightScale = (refHeight / 1080f).coerceIn(0.25f, 3.0f)
                    val scaledFontSize = (op.fontSize * effectiveDensity * heightScale).toInt().coerceAtLeast(12)
                    val boxBorderW = (6f * effectiveDensity * heightScale).toInt().coerceAtLeast(2)
                    val marginY = (24f * effectiveDensity * heightScale).toInt().coerceAtLeast(8)
                    val marginX = (24f * heightScale).toInt().coerceAtLeast(8)
                    val lineSpacing = (scaledFontSize * 1.25f).toInt()
                    
                    val fontToUse = if (!op.fontPath.isNullOrBlank() && java.io.File(op.fontPath).exists()) op.fontPath else fontFilePath
                    val fontColor = formatColorForFFmpeg(op.color)
                    val boxColor = if (op.backgroundColor != "none" && op.backgroundColor.isNotBlank()) {
                        formatColorForFFmpeg(op.backgroundColor)
                    } else {
                        "black@0.5"
                    }

                    for (cue in op.cues) {
                        val cleanText = cue.text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim()
                        if (cleanText.isBlank()) continue
                        val (posX, posY) = if (op.hasCustomPosition()) {
                            Pair("'(w*${op.relativeX})-(tw/2)'", "'(h*${op.relativeY})-(th/2)'")
                        } else {
                            when (op.position) {
                                TextPosition.TOP_LEFT -> Pair("$marginX", "$marginY")
                                TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> Pair("(w-tw)/2", "$marginY")
                                TextPosition.TOP_RIGHT -> Pair("w-tw-$marginX", "$marginY")
                                TextPosition.CENTER_LEFT -> Pair("$marginX", "(h-th)/2")
                                TextPosition.CENTER -> Pair("(w-tw)/2", "(h-th)/2")
                                TextPosition.CENTER_RIGHT -> Pair("w-tw-$marginX", "(h-th)/2")
                                TextPosition.BOTTOM_LEFT -> Pair("$marginX", "h-$marginY-th")
                                TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> Pair("(w-tw)/2", "h-$marginY-th")
                                TextPosition.BOTTOM_RIGHT -> Pair("w-tw-$marginX", "h-$marginY-th")
                            }
                        }

                        val cueStartLocal = (cue.startTimeMs - timeOffsetMs).coerceAtLeast(0L)
                        val cueEndLocal = (cue.endTimeMs - timeOffsetMs).coerceAtLeast(0L)
                        if (cueEndLocal <= cueStartLocal) continue

                        val filterExpr = com.boostlyte.vydra.utils.FFmpegFilterUtils.buildValidatedDrawtextFilter(
                            text = cleanText,
                            fontFilePath = fontToUse,
                            fontSize = scaledFontSize,
                            fontColor = fontColor,
                            x = posX,
                            y = posY,
                            startTimeMs = cueStartLocal,
                            endTimeMs = cueEndLocal,
                            boxColor = boxColor,
                            boxBorderW = boxBorderW
                        )
                        
                        val nextLabel = "[v$stageIndex]"
                        stages.add("$currentLabel$filterExpr$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                else -> {}
            }
        }

        // Process crop at the end so it crops the video and all overlays together, matching the preview.
        for (op in cropOps) {
            val filterExpr = buildCropFilterExpr(op)
            if (filterExpr != null) {
                val nextLabel = "[v$stageIndex]"
                stages.add("$currentLabel$filterExpr$nextLabel")
                currentLabel = nextLabel
                stageIndex++
            }
        }

        return Pair(stages, currentLabel)
    }

    /**
     * Ensures FFmpeg receives a real, existing font file path on disk (never a bare alias such as
     * "Roboto-Regular" or null when a Context is available).
     */
    private fun resolveEffectiveFontPath(fontFilePath: String?, context: Context?): String? {
        if (!fontFilePath.isNullOrBlank() && java.io.File(fontFilePath).exists()) {
            return fontFilePath
        }
        if (context != null) {
            return try {
                val f = java.io.File(context.cacheDir, "Roboto-Regular.ttf")
                if (!f.exists() || f.length() == 0L) {
                    context.assets.open("fonts/Roboto-Regular.ttf").use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                f.absolutePath
            } catch (_: Exception) {
                fontFilePath
            }
        }
        return fontFilePath
    }

    /**
     * Build the consolidated FFmpeg command for final export.
     *
     * Uses -filter_complex with explicit stream labels so each filter stage
     * receives the correct input dimensions (e.g., drawtext after crop uses
     * post-crop resolution).
     *
     * @param sourceFilePath  Absolute path to the source video.
     * @param outputFilePath  Absolute path for the exported video.
     * @param fontFilePath    Absolute path to a TTF font file (from assets via copyFontToCache).
     *                        Required for text overlays to render on Android.
     */
    fun buildConsolidatedFFmpegCommand(
        sourceFilePath: String,
        outputFilePath: String,
        fontFilePath: String? = null,
        context: Context? = null,
        operationsOverride: List<EditOperation>? = null,
        encoderOverride: String? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val density = context?.resources?.displayMetrics?.density ?: 1.0f

        val isMainImage = sourceFilePath.endsWith(".png", ignoreCase = true) ||
                          sourceFilePath.endsWith(".jpg", ignoreCase = true) ||
                          sourceFilePath.endsWith(".jpeg", ignoreCase = true) ||
                          sourceFilePath.endsWith(".webp", ignoreCase = true)

        var sourceVideoWidth = 1280
        var sourceVideoHeight = 720
        if (isMainImage) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(sourceFilePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    var w = opts.outWidth
                    var h = opts.outHeight
                    try {
                        val exif = android.media.ExifInterface(sourceFilePath)
                        val orientation = exif.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                            orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                            orientation == android.media.ExifInterface.ORIENTATION_TRANSVERSE) {
                            val temp = w
                            w = h
                            h = temp
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exif orientation read failed: ${e.message}")
                    }
                    sourceVideoWidth = w
                    sourceVideoHeight = h
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting image dimensions: ${e.message}")
            }
        } else {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val w = wStr?.toIntOrNull() ?: 1280
                    val h = hStr?.toIntOrNull() ?: 720
                    val r = rStr?.toIntOrNull() ?: 0
                    if (r == 90 || r == 270) {
                        sourceVideoWidth = h
                        sourceVideoHeight = w
                    } else {
                        sourceVideoWidth = w
                        sourceVideoHeight = h
                    }
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting source video dimensions: ${e.message}")
            }
        }

        val targetCanvasRatio = currentProject.canvasAspectRatio ?: com.boostlyte.vydra.models.CanvasAspectRatio.fromDimensions(sourceVideoWidth, sourceVideoHeight)
        val (targetWidth, targetHeight) = targetCanvasRatio.getTargetDimensions(_exportResolution.value)
        val memProfile = com.boostlyte.vydra.export.ExportMemoryProfile.fromContext(context, targetWidth, targetHeight)

        if (currentProject.operations.isEmpty() && currentProject.canvasAspectRatio == null) {
            return "-y -i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }

        val operations = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(
            operationsOverride ?: currentProject.operations
        )

        // Detect if source video has an audio stream
        val hasAudio = if (isMainImage) {
            false
        } else {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val hasAudioStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                    hasAudioStr == "yes"
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking audio in source file: ${e.message}")
                true // fallback to assuming it has audio
            }
        }

        // ── Collect operation types ───────────────────────────────────────────
        val mergeOp = operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val nonMergeOps = operations.filterNot { it is EditOperation.Merge }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val audioOps = operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        val audioMuted = operations.any { it is EditOperation.MuteAudio }
        val videoOps = nonMergeOps.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddImageOverlay || it is EditOperation.AddSubtitles }
        val imageOps = operations.filterIsInstance<EditOperation.AddImageOverlay>()

        // ── Unified Input Indexing ────────────────────────────────────────────
        val cmd = StringBuilder("-y") // Always overwrite output — prevents silent failure on retry
        var inputIndex = 0

        val effectiveFontPath = resolveEffectiveFontPath(fontFilePath, context)

        /** Resolve a URI to a real filesystem path FFmpeg can read.
         *  content:// URIs are copied to cacheDir; file:// or plain paths are used directly. */
        fun resolveUriToPath(uri: android.net.Uri): String? = resolveUriToPath(context, uri)

        // 1. Source Video Input (Index 0)
        var outputDuration: Double? = null
        val reverseOp = operations.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val speedOp = operations.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val finalProxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        
        val mainMaskConfigForInput = operations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()?.maskConfig
            ?: mergeOp?.items?.getOrNull(0)?.maskConfig
            ?: EditOperation.MaskConfig()
        val mainIsSingleFrameImage = if (finalProxyUri == null && isMainImage) {
            !hasAnimatedMask(mainMaskConfigForInput)
        } else {
            false
        }
        var mainSingleFrameDurSec = 5.0

        val exportFpsVal = _exportFps.value
        if (finalProxyUri != null) {
            val proxyPath = resolveUriToPath(finalProxyUri) ?: finalProxyUri.toString()
            val baseDuration = if (trimOp != null) {
                (trimOp.endMs - trimOp.startMs)
            } else {
                try {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(proxyPath)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        r.release()
                    }
                } catch (e: Exception) { 0L }
            }
            if (baseDuration > 0) {
                val speed = speedOp?.speed ?: 1.0f
                outputDuration = if (trimOp != null) (baseDuration / speed / 1000.0) else (baseDuration / 1000.0)
                cmd.append(" -t $outputDuration -i \"$proxyPath\"")
            } else {
                cmd.append(" -i \"$proxyPath\"")
            }
        } else {
            if (isMainImage) {
                val duration = if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
                mainSingleFrameDurSec = duration
                if (mergeOp == null) {
                    outputDuration = duration
                }
                if (mainIsSingleFrameImage) {
                    // Decode single image frame once; filtergraph expands frames via zoompan/loop
                    cmd.append(" -framerate $exportFpsVal -i \"$sourceFilePath\"")
                } else {
                    cmd.append(" -framerate $exportFpsVal -loop 1 -t $duration -i \"$sourceFilePath\"")
                }
            } else if (trimOp != null) {
                val startSecs = trimOp.startMs / 1000.0
                val duration = (trimOp.endMs - trimOp.startMs) / 1000.0
                if (mergeOp == null) {
                    outputDuration = duration
                }
                cmd.append(" -ss $startSecs -t $duration -i \"$sourceFilePath\"")
            } else {
                cmd.append(" -i \"$sourceFilePath\"")
            }
        }
        inputIndex++

        // 2. Merge Video Inputs
        val mergeVideoIndices = mutableListOf<Int>()
        val isClipSingleFrameImage = mutableListOf<Boolean>()
        isClipSingleFrameImage.add(mainIsSingleFrameImage)
        val isClipImage = mutableListOf<Boolean>()
        isClipImage.add(finalProxyUri == null && isMainImage)
        if (mergeOp != null) {
            for (item in mergeOp.items) {
                if (item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(item.proxyUri) ?: item.proxyUri.path ?: item.proxyUri.toString()
                    val duration = item.trimmedDurationMs / 1000.0
                    cmd.append(" -t $duration -i \"$proxyPath\"")
                    isClipSingleFrameImage.add(false)
                    isClipImage.add(false)
                } else {
                    val videoPath = resolveUriToPath(item.uri) ?: item.uri.toString()
                    val startSecs = item.trimStartMs / 1000.0
                    val itemDurSec = if (item.trimmedDurationMs > 0L) {
                        item.trimmedDurationMs / 1000.0
                    } else if (item.trimEndMs > item.trimStartMs) {
                        (item.trimEndMs - item.trimStartMs) / 1000.0
                    } else if (item.durationMs > 0L) {
                        item.durationMs / 1000.0
                    } else {
                        5.0
                    }
                    
                    val isImage = item.isImage || isImageFilePath(videoPath)
                    isClipImage.add(isImage)
                    if (isImage && !hasAnimatedMask(item.maskConfig)) {
                        // Single-frame image input avoids repeated JPEG decode on every frame
                        cmd.append(" -framerate $exportFpsVal -i \"$videoPath\"")
                        isClipSingleFrameImage.add(true)
                    } else if (isImage) {
                        cmd.append(" -framerate $exportFpsVal -loop 1 -t $itemDurSec -i \"$videoPath\"")
                        isClipSingleFrameImage.add(false)
                    } else {
                        cmd.append(" -ss $startSecs -t $itemDurSec -i \"$videoPath\"")
                        isClipSingleFrameImage.add(false)
                    }
                }
                mergeVideoIndices.add(inputIndex)
                inputIndex++
            }
        }

        // 3. Audio Inputs  (content:// URIs must be cached to a real path)
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = resolveUriToPath(audioOp.audioUri)
            if (audioPath == null) {
                Log.e(TAG, "Failed to resolve audio URI to path: ${audioOp.audioUri} — skipping track")
                continue
            }
            cmd.append(" -i \"$audioPath\"")
            audioInputIndices.add(Pair(inputIndex, audioOp))
            inputIndex++
        }

        // 4. Image Overlay Inputs  (content:// URIs must be cached to a real path)
        val imageInputIndices = mutableListOf<Pair<Int, EditOperation.AddImageOverlay>>()
        for (imageOp in imageOps) {
            val imagePath = resolveUriToPath(imageOp.imageUri)
            if (imagePath == null) {
                Log.e(TAG, "Failed to resolve image URI to path: ${imageOp.imageUri} — skipping overlay")
                continue
            }
            val isGif = imagePath.endsWith(".gif", ignoreCase = true)
            val isVideo = imagePath.endsWith(".mp4", ignoreCase = true) ||
                          imagePath.endsWith(".mkv", ignoreCase = true) ||
                          imagePath.endsWith(".mov", ignoreCase = true) ||
                          imagePath.endsWith(".3gp", ignoreCase = true)
            if ((isGif || isVideo) && imageOp.isLooping) {
                cmd.append(" -stream_loop -1 -i \"$imagePath\"")
            } else {
                cmd.append(" -i \"$imagePath\"")
            }
            imageInputIndices.add(Pair(inputIndex, imageOp))
            inputIndex++
        }

        // 5. Canvas Background Image Input
        val bgOp = operations.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        var bgImageIndex = -1
        if (bgOp != null && bgOp.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgOp.imageUri != null) {
            val bgPath = resolveUriToPath(bgOp.imageUri)
            if (bgPath != null) {
                cmd.append(" -framerate $exportFpsVal -loop 1 -i \"$bgPath\"")
                bgImageIndex = inputIndex
                inputIndex++
            }
        }

        // ── MERGE PATH ────────────────────────────────────────────────────────
        if (mergeOp != null) {
            val inputCount = 1 + mergeOp.videoUris.size
            val filterParts = mutableListOf<String>()

            var mainWidth = targetWidth
            var mainHeight = targetHeight
            var sourceDurationMs = 0L

            if (isMainImage) {
                sourceDurationMs = 5000L
            } else {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(sourceFilePath)
                        val dStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        sourceDurationMs = dStr?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting source duration: ${e.message}")
                }
            }

            val hasAudioArray = BooleanArray(inputCount)
            val durationsArray = DoubleArray(inputCount)
            
            hasAudioArray[0] = if (isMainImage) false else hasAudio
            durationsArray[0] = if (isMainImage) {
                if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
            } else if (speedOp?.proxyUri != null) {
                val base = if (trimOp != null) (trimOp.endMs - trimOp.startMs) else sourceDurationMs
                (base / speedOp.speed) / 1000.0
            } else if (trimOp != null) {
                (trimOp.endMs - trimOp.startMs) / 1000.0
            } else {
                sourceDurationMs / 1000.0
            }

            for ((idx, item) in mergeOp.items.withIndex()) {
                val path = item.uri.path ?: item.uri.toString()
                hasAudioArray[idx + 1] = try {
                    val isImage = item.isImage ||
                                  path.endsWith(".png", ignoreCase = true) ||
                                  path.endsWith(".jpg", ignoreCase = true) ||
                                  path.endsWith(".jpeg", ignoreCase = true) ||
                                  path.endsWith(".webp", ignoreCase = true)
                    if (isImage) {
                        false
                    } else {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(path)
                            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            h == "yes"
                        } finally {
                            r.release()
                        }
                    }
                } catch (e: Exception) { false }
                durationsArray[idx + 1] = if (item.trimmedDurationMs > 0L) {
                    item.trimmedDurationMs / 1000.0
                } else if (item.trimEndMs > item.trimStartMs) {
                    (item.trimEndMs - item.trimStartMs) / 1000.0
                } else if (item.durationMs > 0L) {
                    item.durationMs / 1000.0
                } else {
                    5.0
                }
            }

            val mergeClipDurationsMs = durationsArray.map { kotlin.math.round(it * 1000.0).toLong().coerceAtLeast(100L) }
            val mergeOpsTransitions = operations.filterIsInstance<EditOperation.Transition>()
            val mergeLayout = TimelineLayoutCalculator.computeTimelineLayoutFromDurations(mergeClipDurationsMs, mergeOpsTransitions)
            val (bresenhamMergeFrames, _) = ChunkPlanner.computeBresenhamClipFrames(
                sliceDurationsMs = mergeClipDurationsMs,
                internalTransitions = mergeOpsTransitions,
                chunkGlobalStartMs = 0L,
                chunkGlobalEndMs = mergeLayout.totalDurationMs,
                fps = exportFpsVal
            )
            val clipFrameCounts = IntArray(inputCount) { idx ->
                bresenhamMergeFrames.getOrElse(idx) { kotlin.math.round(durationsArray[idx] * exportFpsVal).toInt().coerceAtLeast(1) }
            }
            for (idx in 0 until inputCount) {
                durationsArray[idx] = clipFrameCounts[idx].toDouble() / exportFpsVal
            }


            for (i in 0 until inputCount) {
                val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == i }
                val lutFilterExpr = colorFilterOp?.let { getFFmpegFilterForName(it.filterName) }
                val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == i }
                val adjustFilterExpr = adjustOp?.let { getFFmpegFilterForAdjust(it) }

                val isMirrored = if (i == 0) {
                    operations.any { it is EditOperation.MirrorMain && it.isMirrored }
                } else {
                    mergeOp?.items?.getOrNull(i - 1)?.isMirrored == true
                }
                
                val maskConfig = if (i == 0) {
                    val mainMask = operations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()?.maskConfig
                    if (mainMask != null && mainMask.shape != EditOperation.MaskShape.NONE) {
                        mainMask
                    } else {
                        mergeOp?.items?.getOrNull(0)?.maskConfig ?: EditOperation.MaskConfig()
                    }
                } else {
                    mergeOp?.items?.getOrNull(i - 1)?.maskConfig ?: EditOperation.MaskConfig()
                }
                
                // 1. Prepare raw video with basic visual filters
                var preFilters = "setpts=PTS-STARTPTS"
                if (lutFilterExpr != null) preFilters += ",$lutFilterExpr"
                if (adjustFilterExpr != null) preFilters += ",$adjustFilterExpr"
                if (isMirrored) preFilters += ",hflip"
                
                if (maskConfig.shape != EditOperation.MaskShape.NONE) {
                    val mc = maskConfig
                    val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, 0L, "T") + "))" else "W*${mc.relativeX}"
                    val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, 0L, "T") + "))" else "H*${mc.relativeY}"
                    val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), 0L, "T") else null
                    val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
                    val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
                    val cosA: String
                    val sinA: String
                    if (mc.rotationKeyframes.isNotEmpty()) {
                        val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, 0L, "T")
                        val rad = "(($rotExpr)*PI/180)"
                        cosA = "cos($rad)"
                        sinA = "sin($rad)"
                    } else {
                        val rad = Math.toRadians(mc.rotationAngle.toDouble())
                        cosA = Math.cos(rad).toString()
                        sinA = Math.sin(rad).toString()
                    }
                    val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, 0L, "T")
                    preFilters += ",format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='255*($finalAlphaExpr/255)'"
                }
                
                filterParts.add("[$i:v]$preFilters[pre$i]")

                // 2. Apply background/padding or Ken Burns motion
                val motionOp = operations.filterIsInstance<EditOperation.MotionEffect>().find { it.index == i }
                val clipMotionType = motionOp?.motionType ?: if (i > 0) mergeOp?.items?.getOrNull(i - 1)?.motionEffect else null
                val d = durationsArray[i].coerceAtLeast(0.1)
                val isSingleFrame = isClipSingleFrameImage.getOrElse(i) { false }
                val isImgClip = isClipImage.getOrElse(i) { false }
                val clipFrames = clipFrameCounts[i].coerceAtLeast(1)
                val singleFrameLoop = if (isSingleFrame) {
                    ",format=yuv420p,loop=loop=-1:size=1:start=0,setpts=N/($exportFpsVal*TB),trim=end_frame=$clipFrames,setpts=PTS-STARTPTS"
                } else {
                    ""
                }

                // Motion coordinate space scaled cleanly by device memory tier
                val scaleFactor = maxOf(1.0, memProfile.motionSpaceShortEdge / minOf(mainWidth, mainHeight))
                val baseW = (((mainWidth * scaleFactor).toInt()) / 2) * 2
                val baseH = (((mainHeight * scaleFactor).toInt()) / 2) * 2
                val motionW = (((baseW * 1.15).toInt()) / 2) * 2
                val motionH = (((baseH * 1.15).toInt()) / 2) * 2
                val coverScale = "scale=w=$motionW:h=$motionH:force_original_aspect_ratio=increase"
                val coverCrop = "crop=$motionW:$motionH:(in_w-$motionW)/2:(in_h-$motionH)/2"
                val panScaleDown = if (scaleFactor > 1.0) ",scale=${mainWidth}x${mainHeight}:flags=bilinear" else ""
                val zoompanD = if (isSingleFrame) clipFrames else 1

                val motionFilter = when (clipMotionType) {
                    "zoom_in" -> "$coverScale,$coverCrop,zoompan=z='min(1.0+0.12*(on/($exportFpsVal*$d))\\,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$zoompanD:s=${mainWidth}x${mainHeight}:fps=$exportFpsVal"
                    "zoom_out" -> "$coverScale,$coverCrop,zoompan=z='max(1.12-0.12*(on/($exportFpsVal*$d))\\,1.0)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$zoompanD:s=${mainWidth}x${mainHeight}:fps=$exportFpsVal"
                    "pan_left" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*(1-(t/$d))':y='(in_h-out_h)/2'$panScaleDown"
                    "pan_right" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*(t/$d)':y='(in_h-out_h)/2'$panScaleDown"
                    "pan_up" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)/2':y='(in_h-out_h)*(1-(t/$d))'$panScaleDown"
                    "pan_down" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)/2':y='(in_h-out_h)*(t/$d)'$panScaleDown"
                    "drift_up_right" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*(t/$d)':y='(in_h-out_h)*(1-(t/$d))'$panScaleDown"
                    else -> null
                }

                if (motionFilter != null) {
                    filterParts.add("[pre$i]$motionFilter[raw_stage$i]")
                } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR) {
                    val blur = bgOp.blurRadius
                    val bgScale = "format=yuv420p,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight:(in_w-$mainWidth)/2:(in_h-$mainHeight)/2,boxblur=$blur"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[pre$i]split=2[bg_orig$i][fg_orig$i]")
                    filterParts.add("[bg_orig$i]$bgScale[bg$i]")
                    filterParts.add("[fg_orig$i]$fgScale[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2${singleFrameLoop}[raw_stage$i]")
                } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                    val bgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight:(in_w-$mainWidth)/2:(in_h-$mainHeight)/2"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[$bgImageIndex:v]$bgScale[bg$i]")
                    filterParts.add("[pre$i]$fgScale[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1${singleFrameLoop}[raw_stage$i]")
                } else {
                    // Default COLOR: direct scale+pad avoids an extra color generator + overlay pass
                    val color = bgOp?.colorHex ?: "black"
                    val padColor = if (color.startsWith("#")) formatColorForFFmpeg(color) else "black"
                    filterParts.add("[pre$i]scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2:color=$padColor,setsar=1${singleFrameLoop}[raw_stage$i]")
                }

                // Explicit normalization stage for clip i: apply target fps BEFORE setpts=N so non-matching video framerates stay in real-time sync
                if (isImgClip) {
                    val exactTrimPrefix = if (!isSingleFrame && motionFilter == null) "trim=end_frame=$clipFrames," else ""
                    filterParts.add("[raw_stage$i]${exactTrimPrefix}settb=1/${_exportFps.value},setpts=N,setsar=1,fps=${_exportFps.value},format=yuv420p[norm$i]")
                } else {
                    filterParts.add("[raw_stage$i]fps=${_exportFps.value},settb=1/${_exportFps.value},setpts=N,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[norm$i]")
                }

                val isClipMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == i }?.isMuted ?: false
                if (hasAudioArray[i] && !isClipMuted) {
                    filterParts.add("[$i:a]asetpts=PTS-STARTPTS,aformat=sample_rates=44100:channel_layouts=stereo[anorm$i]")
                } else {
                    val d = durationsArray[i]
                    filterParts.add("anullsrc=r=44100:cl=stereo,atrim=duration=$d,asetpts=PTS-STARTPTS[anorm$i]")
                }
            }

            // Build a list of per-gap transition info (gap i = between clip i and clip i+1)
            // transitionOps[i] is the Transition op for gap i, or null if none.
            val transitionOps = List(inputCount - 1) { gapIdx ->
                operations.filterIsInstance<EditOperation.Transition>().find { it.index == gapIdx }
            }
            val transitionMath = TransitionTimelineCalculator.calculate(
                clipDurationsSec = durationsArray.toList(),
                transitions = operations.filterIsInstance<EditOperation.Transition>()
            )

            // Chain xfade/acrossfade for transitions and concat for plain gaps.
            // Represents a finalized stream ready to be used as xfade input
            data class StreamInfo(val vLabel: String, val aLabel: String, val frames: Int, val durationSec: Double)

            val pendingClipIndices = mutableListOf(0) // start with clip 0

            var currentStream: StreamInfo? = null
            var transitionLabelCounter = 0
            var concatLabelCounter = 0

            fun flushPendingGroup(clipIndices: List<Int>): StreamInfo {
                return if (clipIndices.size == 1) {
                    val i = clipIndices[0]
                    StreamInfo("[norm$i]", "[anorm$i]", clipFrameCounts[i], durationsArray[i])
                } else {
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    val inputs = clipIndices.joinToString("") { "[norm$it][anorm$it]" }
                    // concat then ensure consistent format/fps
                    filterParts.add("${inputs}concat=n=${clipIndices.size}:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}format=yuv420p,fps=${exportFpsVal}[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    val totalFrames = clipIndices.sumOf { clipFrameCounts[it] }
                    val totalDur = totalFrames.toDouble() / exportFpsVal
                    concatLabelCounter++
                    // use the normalized labels for subsequent processing
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", totalFrames, totalDur)
                }
            }

            for (gapIdx in 0 until inputCount - 1) {
                val transOp = transitionOps[gapIdx]
                if (transOp == null || transOp.type == "none") {
                    // No transition at this gap — just accumulate the next clip
                    pendingClipIndices.add(gapIdx + 1)
                } else {
                    // Flush the pending group into a stream, if any pending clips exist.
                    // If none exist, it means the currentStream (previous transition output) is the base.
                    val baseStream = if (pendingClipIndices.isEmpty()) {
                        currentStream ?: throw IllegalStateException("Both pending clips and currentStream are empty")
                    } else {
                        val leftStream = flushPendingGroup(pendingClipIndices)
                        pendingClipIndices.clear()
                        if (currentStream == null) {
                            leftStream
                        } else {
                            // Concat previous xfade output with the newly flushed left group
                            val vOut = "[ccat${concatLabelCounter}v]"
                            val aOut = "[ccat${concatLabelCounter}a]"
                            filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${leftStream.vLabel}${leftStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                            filterParts.add("${vOut}format=yuv420p,fps=${exportFpsVal}[norm${concatLabelCounter}v]")
                            filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                            concatLabelCounter++
                            val mergedFrames = currentStream.frames + leftStream.frames
                            StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", mergedFrames, mergedFrames.toDouble() / exportFpsVal)
                        }
                    }

                    val nextFrames = clipFrameCounts[gapIdx + 1]
                    val maxSafeFrames = minOf(baseStream.frames - 1, nextFrames - 1).coerceAtLeast(1)
                    val rawTransFrames = kotlin.math.round((transOp.durationMs / 1000.0) * exportFpsVal).toInt().coerceAtLeast(1)
                    val transFrames = rawTransFrames.coerceIn(1, maxSafeFrames)
                    val offsetFrames = (baseStream.frames - transFrames).coerceAtLeast(0)
                    val transDuration = transFrames.toDouble() / exportFpsVal
                    val offset = offsetFrames.toDouble() / exportFpsVal

                    val rawXfvOut = "[raw_xfv${transitionLabelCounter}]"
                    val rawXfaOut = "[raw_xfa${transitionLabelCounter}]"
                    val normXfvOut = "[norm_xfv${transitionLabelCounter}]"
                    val normXfaOut = "[norm_xfa${transitionLabelCounter}]"
                    val nextVLabel = "[norm${gapIdx + 1}]"
                    val nextALabel = "[anorm${gapIdx + 1}]"

                    val validXfadeTransitions = setOf(
                        "fade", "fadeblack", "fadewhite", "rectcrop", "circlecrop", "circleclose", "circleopen",
                        "horzclose", "horzopen", "vertclose", "vertopen", "diagbl", "diagbr", "diagtl", "diagtr",
                        "hlslice", "hrslice", "vuslice", "vdslice", "dissolve", "pixelize", "slidedown", "slideleft",
                        "slideright", "slideup", "wipedown", "wipeleft", "wiperight", "wipeup", "zoomin", "squeezeh",
                        "squeezev", "coverleft", "coverright", "coverup", "coverdown", "revealleft", "revealright",
                        "revealup", "revealdown"
                    )
                    val rawType = transOp.type.lowercase()
                    val sanitizedTransType = when (rawType) {
                        "smoothleft" -> "coverleft"
                        "smoothright" -> "coverright"
                        "distance" -> "zoomin"
                        else -> if (validXfadeTransitions.contains(rawType)) rawType else "fade"
                    }

                    val formattedDuration = String.format(java.util.Locale.US, "%.6f", transDuration)
                    val formattedOffset = String.format(java.util.Locale.US, "%.6f", offset)

                    filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedTransType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                    filterParts.add("${baseStream.aLabel}${nextALabel}acrossfade=d=${formattedDuration}:c1=tri:c2=tri${rawXfaOut}")
                    filterParts.add("${rawXfvOut}settb=1/${exportFpsVal},setpts=N,fps=${exportFpsVal},format=yuv420p${normXfvOut}")
                    filterParts.add("${rawXfaOut}asetpts=PTS-STARTPTS,aresample=async=1000${normXfaOut}")

                    val newFrames = offsetFrames + nextFrames
                    currentStream = StreamInfo(normXfvOut, normXfaOut, newFrames, newFrames.toDouble() / exportFpsVal)
                    transitionLabelCounter++
                    // Next clip (gapIdx+1) has already been consumed by xfade — don't add it to pending
                }
            }

            // Flush any remaining pending clips
            val remainingStream = if (pendingClipIndices.isNotEmpty()) flushPendingGroup(pendingClipIndices) else null

            val finalStream: StreamInfo = when {
                currentStream == null && remainingStream != null -> remainingStream
                currentStream != null && remainingStream == null -> currentStream!!
                currentStream != null && remainingStream != null -> {
                    // Concat the last xfade output with remaining plain clips
                    // FFmpeg concat requires interleaved [v0][a0][v1][a1] order
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    // concat then normalize timestamps
                    filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${remainingStream.vLabel}${remainingStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}settb=1/${exportFpsVal},setpts=N,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${exportFpsVal},format=yuv420p[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    concatLabelCounter++
                    val sumFrames = currentStream.frames + remainingStream.frames
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", sumFrames, sumFrames.toDouble() / exportFpsVal)
                }
                else -> StreamInfo("[norm0]", "[anorm0]", clipFrameCounts[0], durationsArray[0]) // fallback single clip
            }

            var currentVLabel = finalStream.vLabel
            var currentALabel = finalStream.aLabel
            val accumulatedDuration = finalStream.durationSec

            var currentVideoLabel = currentVLabel
            var currentAudioLabel = currentALabel

            var maxAudioEndSec = 0.0
            for (audioOp in audioOps) {
                val startSec = (audioOp.startTimeMs ?: 0L) / 1000.0
                val durSec = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                    (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                } else if (audioOp.internalEndMs > 0L) {
                    (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                } else if (audioOp.originalDurationMs > 0L) {
                    audioOp.originalDurationMs / 1000.0
                } else {
                    0.0
                }
                if (startSec + durSec > maxAudioEndSec) {
                    maxAudioEndSec = startSec + durSec
                }
            }

            val finalOutputDuration = if (maxAudioEndSec > accumulatedDuration && maxAudioEndSec > 0.0) {
                val padSec = maxAudioEndSec - accumulatedDuration
                filterParts.add("${currentVideoLabel}tpad=stop_mode=clone:stop_duration=$padSec[pad_v]")
                currentVideoLabel = "[pad_v]"
                maxAudioEndSec
            } else {
                accumulatedDuration
            }

            outputDuration = finalOutputDuration


            val (sourceVideoStages, tempVideoLabel) = buildVideoFilterStages(
                operations = videoOps,
                fontFilePath = effectiveFontPath,
                inputLabel = currentVideoLabel,
                imageInputIndices = imageInputIndices,
                density = density,
                sourceVideoHeight = targetHeight
            )
            filterParts.addAll(sourceVideoStages)
            val finalVideoLabel = "[fmtv]"
            filterParts.add("${tempVideoLabel}scale=$targetWidth:$targetHeight,fps=${_exportFps.value},format=yuv420p$finalVideoLabel")

            if (audioMuted) {
                filterParts.add("${currentAudioLabel}anullsink")
            } else if (audioInputIndices.isNotEmpty()) {
                val mixInputLabels = mutableListOf<String>()
                mixInputLabels.add(currentAudioLabel)

                for ((idx, pair) in audioInputIndices.withIndex()) {
                    val (inputIdx, audioOp) = pair
                    val trackLabel = "[a_bg_$idx]"
                    val filters = mutableListOf<String>()

                    val internalStartSec = audioOp.internalStartMs / 1000.0
                    var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                    if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                        val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        val maxEndSec = internalStartSec + timelineDurationSec
                        if (maxEndSec < internalEndSec) internalEndSec = maxEndSec
                    }

                    if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                        if (internalEndSec != Double.MAX_VALUE) {
                            filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                        } else {
                            filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                        }
                    }
                    if (audioOp.fadeInDurationMs > 0L) {
                        val fadeSec = audioOp.fadeInDurationMs / 1000.0
                        filters.add("afade=t=in:st=0:d=$fadeSec")
                    }
                    if (audioOp.fadeOutDurationMs > 0L) {
                        val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                        val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                            (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        } else if (audioOp.internalEndMs > 0L) {
                            (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                        } else {
                            (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                        }
                        val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                        filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
                    }
                    var delayMs = audioOp.startTimeMs ?: 0L
                    if (delayMs < 0) delayMs = 0L
                    if (delayMs > 0) {
                        filters.add("aformat=channel_layouts=stereo,adelay=$delayMs|$delayMs")
                    }
                    val safeVol = audioOp.volume.coerceIn(0f, 2f)
                    filters.add("volume=${String.format(java.util.Locale.US, "%.2f", safeVol)}")

                    if (filters.isNotEmpty()) {
                        filterParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                        mixInputLabels.add(trackLabel)
                    } else {
                        mixInputLabels.add("[$inputIdx:a]")
                    }
                }

                if (!audioInputIndices.any { it.second.removeOriginalAudio }) {
                    var mainAudioRef = currentAudioLabel
                    val duckingCount = audioInputIndices.count { it.second.ducking }
                    
                    if (duckingCount > 0) {
                        val splits = (0..duckingCount).map { "[main_split_$it]" }
                        filterParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                        mainAudioRef = splits[0]
                        
                        var splitIdx = 1
                        for (i in mixInputLabels.indices.drop(1)) { // skip index 0 which is currentAudioLabel
                            val opIndex = i - 1
                            val op = audioInputIndices[opIndex].second
                            if (op.ducking) {
                                val bgLabel = mixInputLabels[i]
                                val sidechainLabel = splits[splitIdx++]
                                val duckedLabel = "[ducked_$opIndex]"
                                filterParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$opIndex]")
                                filterParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$opIndex]")
                                filterParts.add("[bg_fmt_$opIndex][sc_fmt_$opIndex]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                                mixInputLabels[i] = duckedLabel
                            }
                        }
                    }
                    
                    mixInputLabels[0] = mainAudioRef
                    val allInputs = mixInputLabels.joinToString("")
                    filterParts.add("${allInputs}amix=inputs=${mixInputLabels.size}:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
                    currentAudioLabel = "[outa]"
                } else {
                    filterParts.add("${mixInputLabels[0]}anullsink")
                    mixInputLabels.removeAt(0)
                    if (mixInputLabels.size == 1) {
                        val singleLabel = mixInputLabels[0]
                        filterParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                        currentAudioLabel = "[outa]"
                    } else {
                        val allBg = mixInputLabels.joinToString("")
                        filterParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
                        currentAudioLabel = "[outa]"
                    }
                }
            }

            if (_exportAudioOnly.value) {
                filterParts.add("${finalVideoLabel}nullsink")
            }
            val filterComplexStr = filterParts.joinToString(";\n")
            if (context != null) {
                val scriptFile = File(context.cacheDir, "filter_script_${System.nanoTime()}.txt")
                scriptFile.writeText(filterComplexStr)
                cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
            } else {
                cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
            }
            
            if (_exportAudioOnly.value) {
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                    // Vydra ffmpeg might have libmp3lame, fallback to libmp3lame or default mp3 encoder
                    cmd.append(" -c:a libmp3lame -b:a 192k")
                } else {
                    cmd.append(" -an")
                }
            } else {
                cmd.append(" -map \"$finalVideoLabel\"")
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                } else {
                    cmd.append(" -an")
                }
                
                val bitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(_exportResolution.value, _exportFps.value)
                val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(_exportResolution.value)
                val audioBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetAudioBitrate(_exportResolution.value)
                val videoCodec = encoderOverride ?: run {
                    val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
                        context, targetWidth, targetHeight, _exportFps.value, bitrate, swCrf, memProfile.softwareEncoderThreads
                    )
                    enc.ffmpegVideoCodecArgs
                }
                cmd.append(" $videoCodec")
                
                if (!audioMuted) {
                    cmd.append(" -c:a aac -b:a $audioBitrate")
                }
            }

            if (outputDuration != null) {
                val formattedDur = String.format(java.util.Locale.US, "%.3f", outputDuration).trimEnd('0').trimEnd('.')
                cmd.append(" -t $formattedDur")
            }
            cmd.append(" \"$outputFilePath\"")

            val finalCommand = cmd.toString()
            if (context != null) {
                com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                    context = context,
                    passName = "single_pass_merge",
                    command = finalCommand,
                    filterScriptContent = filterComplexStr
                )
            }
            Log.d(TAG, "Built merge command: $finalCommand")
            return finalCommand
        }

        // ── NON-MERGE PATH ────────────────────────────────────────────────────
        val filterComplexParts = mutableListOf<String>()
        var currentInputVideoLabel = "[0:v]"

        val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == 0 }
        val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == 0 }

        val prepFilters = mutableListOf<String>()
        if (colorFilterOp != null) {
            val lutFilterExpr = getFFmpegFilterForName(colorFilterOp.filterName)
            if (lutFilterExpr != null) {
                prepFilters.add(lutFilterExpr)
            }
        }
        if (adjustOp != null) {
            val adjustFilterExpr = getFFmpegFilterForAdjust(adjustOp)
            if (adjustFilterExpr != null) {
                prepFilters.add(adjustFilterExpr)
            }
        }
        val mirrorMainOp = operations.filterIsInstance<EditOperation.MirrorMain>().find { it.isMirrored }
        if (mirrorMainOp != null) {
            prepFilters.add("hflip")
        }
        
        val maskMainOp = operations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
        if (maskMainOp != null && maskMainOp.maskConfig.shape != EditOperation.MaskShape.NONE) {
            val mc = maskMainOp.maskConfig
            val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, 0L, "T") + "))" else "W*${mc.relativeX}"
            val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, 0L, "T") + "))" else "H*${mc.relativeY}"
            val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), 0L, "T") else null
            val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
            val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
            val cosA: String
            val sinA: String
            if (mc.rotationKeyframes.isNotEmpty()) {
                val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, 0L, "T")
                val rad = "(($rotExpr)*PI/180)"
                cosA = "cos($rad)"
                sinA = "sin($rad)"
            } else {
                val rad = Math.toRadians(mc.rotationAngle.toDouble())
                cosA = Math.cos(rad).toString()
                sinA = Math.sin(rad).toString()
            }
            val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, 0L, "T")
            prepFilters.add("format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='255*($finalAlphaExpr/255)'")
        }

        if (prepFilters.isNotEmpty()) {
            val combinedFilters = prepFilters.joinToString(",")
            filterComplexParts.add("[0:v]${combinedFilters}[cfv]")
            currentInputVideoLabel = "[cfv]"
        }

        if (mainIsSingleFrameImage) {
            val expandLabel = "[img_exp]"
            val safeDur = mainSingleFrameDurSec.coerceAtLeast(0.1)
            filterComplexParts.add("${currentInputVideoLabel}scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,format=yuv420p,loop=loop=-1:size=1:start=0,setpts=N/($exportFpsVal*TB),trim=duration=$safeDur,setpts=PTS-STARTPTS$expandLabel")
            currentInputVideoLabel = expandLabel
        }

        // ── Video filter stages (crop, text, image overlays) ──
        val (videoStages, finalVideoLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = effectiveFontPath,
            inputLabel = currentInputVideoLabel,
            imageInputIndices = imageInputIndices,
            density = density,
            sourceVideoHeight = targetHeight
        )
        filterComplexParts.addAll(videoStages)

        // ── Audio filter stages (multi-track adelay + volume mixing) ──
        var finalAudioLabel: String? = null
        val mainVideoMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == 0 }?.isMuted ?: false
        val effectiveAudioMuted = audioMuted || (mainVideoMuted && audioInputIndices.isEmpty())

        if (effectiveAudioMuted) {
            // No audio output — will use -an flag
        } else if (audioInputIndices.isNotEmpty()) {
            val mixInputLabels = mutableListOf<String>()

            for ((idx, pair) in audioInputIndices.withIndex()) {
                val (inputIdx, audioOp) = pair
                val trackLabel = "[a$idx]"
                val filters = mutableListOf<String>()

                val internalStartSec = audioOp.internalStartMs / 1000.0
                var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                    val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    val maxEndSec = internalStartSec + timelineDurationSec
                    if (maxEndSec < internalEndSec) {
                        internalEndSec = maxEndSec
                    }
                }

                if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                    if (internalEndSec != Double.MAX_VALUE) {
                        filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                    } else {
                        filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                    }
                }
                if (audioOp.fadeInDurationMs > 0L) {
                    val fadeSec = audioOp.fadeInDurationMs / 1000.0
                    filters.add("afade=t=in:st=0:d=$fadeSec")
                }
                if (audioOp.fadeOutDurationMs > 0L) {
                    val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                    val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                        (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    } else if (audioOp.internalEndMs > 0L) {
                        (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                    } else {
                        (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                    }
                    val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                    filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
                }
                var delayMs = audioOp.startTimeMs ?: 0L
                if (delayMs < 0) delayMs = 0L
                if (delayMs > 0) {
                    filters.add("aformat=channel_layouts=stereo,adelay=$delayMs|$delayMs")
                }
                
                val safeVol = audioOp.volume.coerceIn(0f, 2f)
                filters.add("volume=${String.format(java.util.Locale.US, "%.2f", safeVol)}")

                if (filters.isNotEmpty()) {
                    filterComplexParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                    mixInputLabels.add(trackLabel)
                } else {
                    mixInputLabels.add("[$inputIdx:a]")
                }
            }

            // Mix original audio with all background tracks
            if (hasAudio && !mainVideoMuted && !audioInputIndices.any { it.second.removeOriginalAudio }) {
                var mainAudioRef = "[0:a]"
                val duckingCount = audioInputIndices.count { it.second.ducking }
                
                if (duckingCount > 0) {
                    val splits = (0..duckingCount).map { "[main_split_$it]" }
                    filterComplexParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                    mainAudioRef = splits[0]
                    
                    var splitIdx = 1
                    for (i in mixInputLabels.indices) {
                        val op = audioInputIndices[i].second
                        if (op.ducking) {
                            val bgLabel = mixInputLabels[i]
                            val sidechainLabel = splits[splitIdx++]
                            val duckedLabel = "[ducked_$i]"
                            filterComplexParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$i]")
                            filterComplexParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$i]")
                            filterComplexParts.add("[bg_fmt_$i][sc_fmt_$i]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                            mixInputLabels[i] = duckedLabel
                        }
                    }
                }

                val allInputs = mainAudioRef + mixInputLabels.joinToString("")
                val totalInputs = 1 + mixInputLabels.size
                filterComplexParts.add("${allInputs}amix=inputs=$totalInputs:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
                finalAudioLabel = "[outa]"
            } else {
                if (mixInputLabels.size == 1) {
                    val singleLabel = mixInputLabels[0]
                    filterComplexParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                    finalAudioLabel = "[outa]"
                } else {
                    val allBg = mixInputLabels.joinToString("")
                    filterComplexParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
                    finalAudioLabel = "[outa]"
                }
            }
        }

        // ── Assemble the command ──
        val fmtLabel = "[fmtv]"
        when {
            bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR -> {
                val blur = bgOp.blurRadius
                val bgScale = "format=yuv420p,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight:(in_w-$targetWidth)/2:(in_h-$targetHeight)/2,boxblur=$blur"
                val fgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease"
                filterComplexParts.add("${finalVideoLabel}split=2[bg_nm_orig][fg_nm_orig]")
                filterComplexParts.add("[bg_nm_orig]$bgScale[bg_nm]")
                filterComplexParts.add("[fg_nm_orig]$fgScale[fg_nm]")
                filterComplexParts.add("[bg_nm][fg_nm]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=${_exportFps.value},format=yuv420p$fmtLabel")
            }
            bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1 -> {
                val bgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight:(in_w-$targetWidth)/2:(in_h-$targetHeight)/2"
                val fgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease"
                filterComplexParts.add("[$bgImageIndex:v]$bgScale[bg_nm]")
                filterComplexParts.add("${finalVideoLabel}$fgScale[fg_nm]")
                filterComplexParts.add("[bg_nm][fg_nm]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,fps=${_exportFps.value},format=yuv420p$fmtLabel")
            }
            else -> {
                val color = bgOp?.colorHex ?: "black"
                val padColor = if (color.startsWith("#")) formatColorForFFmpeg(color) else "black"
                filterComplexParts.add("${finalVideoLabel}scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2:color=$padColor,setsar=1,fps=${_exportFps.value},format=yuv420p$fmtLabel")
            }
        }
        val mappedVideoLabel = fmtLabel
        val hasVideoFilters = true
        val hasAudioFilters = finalAudioLabel != null

        if (hasVideoFilters || hasAudioFilters) {
            
            if (_exportAudioOnly.value && hasVideoFilters) {
                filterComplexParts.add("${mappedVideoLabel}nullsink")
            }
            
            val filterComplexStr = filterComplexParts.joinToString(";\n")
            if (context != null) {
                val scriptFile = File(context.cacheDir, "filter_script_${System.nanoTime()}.txt")
                scriptFile.writeText(filterComplexStr)
                cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
            } else {
                cmd.append(" -filter_complex \"${filterComplexParts.joinToString(";")}\"")
            }

            if (!_exportAudioOnly.value) {
                if (hasVideoFilters) {
                    cmd.append(" -map \"$mappedVideoLabel\"")
                } else {
                    cmd.append(" -map 0:v")
                }
            }

            if (effectiveAudioMuted) {
                cmd.append(" -an")
            } else if (hasAudioFilters) {
                cmd.append(" -map \"$finalAudioLabel\"")
            } else {
                cmd.append(" -map 0:a?")
            }
        } else {
            if (_exportAudioOnly.value) {
                cmd.append(" -vn")
                if (!effectiveAudioMuted) {
                    cmd.append(" -map 0:a?")
                }
            }
            if (effectiveAudioMuted) {
                cmd.append(" -an")
            }
        }

        // Codecs
        if (_exportAudioOnly.value) {
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a libmp3lame -b:a 192k")
            }
        } else {
            val bitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(_exportResolution.value, _exportFps.value)
            val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(_exportResolution.value)
            val audioBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetAudioBitrate(_exportResolution.value)
            val videoCodec = encoderOverride ?: run {
                val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
                    context, targetWidth, targetHeight, _exportFps.value, bitrate, swCrf, memProfile.softwareEncoderThreads
                )
                enc.ffmpegVideoCodecArgs
            }
            // If there are no video filters, we still need to apply the scale and fps output params
            if (!hasVideoFilters) {
                cmd.append(" $videoCodec -vf scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2:color=black -r ${_exportFps.value}")
            } else {
                cmd.append(" $videoCodec")
            }
            
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a aac -b:a $audioBitrate")
            }
        }

        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
        cmd.append(" \"$outputFilePath\"")

        val finalCommand = cmd.toString()
        if (context != null) {
            val nonMergeFilterStr = if (hasVideoFilters || hasAudioFilters) filterComplexParts.joinToString(";\n") else null
            com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                context = context,
                passName = "single_pass",
                command = finalCommand,
                filterScriptContent = nonMergeFilterStr
            )
        }
        Log.d(TAG, "Built command: $finalCommand")
        return finalCommand
    }

    /**
     * Resolve a URI to a real filesystem path FFmpeg can read.
     * content:// URIs are copied to cacheDir; file:// or plain paths are used directly.
     */
    fun resolveUriToPath(context: Context?, uri: android.net.Uri): String? {
        if (uri.scheme == "content" && context != null) {
            return copyContentUriToTempFile(context, uri)
        }
        return uri.path ?: uri.toString()
    }

    /**
     * Build a chunk pass command for an individual sub-sequence chunk (video-only).
     */
    fun buildChunkPassCommand(
        chunkSpec: ChunkSpec,
        sourceFilePath: String,
        chunkOutputPath: String,
        fontFilePath: String?,
        context: Context?,
        totalDurationMs: Long,
        density: Float,
        targetWidth: Int,
        targetHeight: Int,
        operations: List<EditOperation>,
        encoderOverride: String? = null,
        tailPadFrames: Int = 0
    ): ChunkCommand {
        val cmd = StringBuilder("-y")
        var inputIndex = 0

        val exportFps = _exportFps.value.coerceAtLeast(1)
        val memProfile = com.boostlyte.vydra.export.ExportMemoryProfile.fromContext(context, targetWidth, targetHeight)
        val clipIsImageFlags = mutableListOf<Boolean>()
        val clipIsSingleFrameFlags = mutableListOf<Boolean>()
        val clipFullDurationsSec = mutableListOf<Double>()
        val clipSliceStartsSec = mutableListOf<Double>()
        val clipSliceDurationsSec = mutableListOf<Double>()

        // 1. Inputs for this chunk's clips
        for ((localIdx, clip) in chunkSpec.clips.withIndex()) {
            val fullDurMs = if (clip.fullClipDurationMs > 0L) clip.fullClipDurationMs else ChunkPlanner.getClipFullDurationMs(clip.item)
            val sliceStartMs = clip.sliceStartInClipMs.coerceAtLeast(0L)
            val sliceDurMs = if (clip.sliceDurationMs > 0L) clip.sliceDurationMs else (fullDurMs - sliceStartMs).coerceAtLeast(100L)

            val fullDurSec = (fullDurMs / 1000.0).coerceAtLeast(0.1)
            val sliceStartSec = sliceStartMs / 1000.0
            val sliceDurSec = (sliceDurMs / 1000.0).coerceAtLeast(0.1)

            clipFullDurationsSec.add(fullDurSec)
            clipSliceStartsSec.add(sliceStartSec)
            clipSliceDurationsSec.add(sliceDurSec)

            val maskAnimated = hasAnimatedMask(clip.item.maskConfig)

            if (clip.globalIndex == 0) {
                val origUri = clip.item.uri
                val origUriStr = origUri.toString()
                val scheme = origUri.scheme ?: "file"
                val resolvedPath = sourceFilePath
                val f = File(resolvedPath)
                val exists = f.exists()
                val size = if (exists) f.length() else -1L
                Log.i(TAG, "ChunkClip: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex} (global 0), origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', exists=$exists, size=$size bytes, slice=${sliceStartMs}ms..${sliceStartMs + sliceDurMs}ms")

                val isMainImage = clip.item.isImage || isImageFilePath(sourceFilePath)
                val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()

                if (clip.item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(context, clip.item.proxyUri) ?: clip.item.proxyUri.path ?: clip.item.proxyUri.toString()
                    if (sliceStartMs > 0L) {
                        cmd.append(" -ss $sliceStartSec -t $sliceDurSec -i \"$proxyPath\"")
                    } else {
                        cmd.append(" -t $sliceDurSec -i \"$proxyPath\"")
                    }
                    clipIsImageFlags.add(false)
                    clipIsSingleFrameFlags.add(false)
                } else if (isMainImage) {
                    if (!maskAnimated) {
                        cmd.append(" -framerate $exportFps -i \"$sourceFilePath\"")
                        clipIsSingleFrameFlags.add(true)
                    } else {
                        cmd.append(" -framerate $exportFps -loop 1 -t $sliceDurSec -i \"$sourceFilePath\"")
                        clipIsSingleFrameFlags.add(false)
                    }
                    clipIsImageFlags.add(true)
                } else if (trimOp != null || sliceStartMs > 0L || sliceDurMs < fullDurMs) {
                    val baseStartSecs = (trimOp?.startMs ?: clip.item.trimStartMs) / 1000.0
                    val effectiveSs = baseStartSecs + (sliceStartSec * clip.item.speed.toDouble())
                    val effectiveReadDur = sliceDurSec * clip.item.speed.toDouble()
                    cmd.append(" -ss $effectiveSs -t $effectiveReadDur -i \"$sourceFilePath\"")
                    clipIsImageFlags.add(false)
                    clipIsSingleFrameFlags.add(false)
                } else {
                    cmd.append(" -i \"$sourceFilePath\"")
                    clipIsImageFlags.add(false)
                    clipIsSingleFrameFlags.add(false)
                }
            } else {
                val item = clip.item
                val origUri = item.uri
                val origUriStr = origUri.toString()
                val scheme = origUri.scheme ?: (if (origUriStr.startsWith("/")) "file" else "none")
                val resolvedPath = clip.resolvedLocalPath ?: resolveUriToPath(context, item.uri) ?: item.uri.path ?: origUriStr
                val f = File(resolvedPath)
                val exists = f.exists()
                val size = if (exists) f.length() else -1L
                Log.i(TAG, "ChunkClip: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex} (global ${clip.globalIndex}), origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', exists=$exists, size=$size bytes, slice=${sliceStartMs}ms..${sliceStartMs + sliceDurMs}ms")
                if (!exists || size <= 0L) {
                    Log.e(TAG, "ChunkClip missing or empty: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex}, path='$resolvedPath', uri='$origUriStr'")
                }

                if (item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(context, item.proxyUri) ?: item.proxyUri.path ?: item.proxyUri.toString()
                    if (sliceStartMs > 0L) {
                        cmd.append(" -ss $sliceStartSec -t $sliceDurSec -i \"$proxyPath\"")
                    } else {
                        cmd.append(" -t $sliceDurSec -i \"$proxyPath\"")
                    }
                    clipIsImageFlags.add(false)
                    clipIsSingleFrameFlags.add(false)
                } else {
                    val videoPath = resolvedPath
                    val isImage = item.isImage || isImageFilePath(videoPath)
                    clipIsImageFlags.add(isImage)
                    if (isImage && !maskAnimated) {
                        cmd.append(" -framerate $exportFps -i \"$videoPath\"")
                        clipIsSingleFrameFlags.add(true)
                    } else if (isImage) {
                        cmd.append(" -framerate $exportFps -loop 1 -t $sliceDurSec -i \"$videoPath\"")
                        clipIsSingleFrameFlags.add(false)
                    } else {
                        val baseStartSecs = item.trimStartMs / 1000.0
                        val effectiveSs = baseStartSecs + (sliceStartSec * item.speed.toDouble())
                        val effectiveReadDur = sliceDurSec * item.speed.toDouble()
                        cmd.append(" -ss $effectiveSs -t $effectiveReadDur -i \"$videoPath\"")
                        clipIsSingleFrameFlags.add(false)
                    }
                }
            }
            inputIndex++
        }

        // 2. Image Overlay Inputs intersecting chunk window
        val chunkStart = chunkSpec.globalStartMs
        val chunkDur = chunkSpec.durationMs
        val imageOps = operations.filterIsInstance<EditOperation.AddImageOverlay>()
        val visibleImageIndices = mutableListOf<Pair<Int, EditOperation.AddImageOverlay>>()
        val clampedImageOps = mutableListOf<EditOperation.AddImageOverlay>()

        for (imageOp in imageOps) {
            val start = imageOp.startTimeMs ?: 0L
            val end = imageOp.endTimeMs ?: totalDurationMs
            val clamped = ChunkPlanner.clampToChunkWindow(start, end, chunkStart, chunkDur)
            if (clamped.isVisibleInChunk) {
                val imagePath = resolveUriToPath(context, imageOp.imageUri) ?: continue
                val isGif = imagePath.endsWith(".gif", ignoreCase = true)
                val isVideo = imagePath.endsWith(".mp4", ignoreCase = true) ||
                              imagePath.endsWith(".mkv", ignoreCase = true) ||
                              imagePath.endsWith(".mov", ignoreCase = true) ||
                              imagePath.endsWith(".3gp", ignoreCase = true)
                if ((isGif || isVideo) && imageOp.isLooping) {
                    cmd.append(" -stream_loop -1 -i \"$imagePath\"")
                } else {
                    cmd.append(" -i \"$imagePath\"")
                }
                val clampedOp = imageOp.copy(startTimeMs = clamped.localStartMs, endTimeMs = clamped.localEndMs)
                visibleImageIndices.add(Pair(inputIndex, clampedOp))
                clampedImageOps.add(clampedOp)
                inputIndex++
            }
        }

        // 3. Canvas background image input if needed
        val bgOp = operations.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        var bgImageIndex = -1
        if (bgOp != null && bgOp.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgOp.imageUri != null) {
            val bgPath = resolveUriToPath(context, bgOp.imageUri)
            if (bgPath != null) {
                cmd.append(" -framerate ${_exportFps.value} -loop 1 -i \"$bgPath\"")
                bgImageIndex = inputIndex
                inputIndex++
            }
        }

        // 4. Filter complex for chunk clips
        val filterParts = mutableListOf<String>()
        val inputCount = chunkSpec.clips.size
        val durationsArray = DoubleArray(inputCount)
        val clipFrameCounts = IntArray(inputCount)

        val fallbackBresenham = if (chunkSpec.totalFrames <= 0 || chunkSpec.clips.any { it.exactFrames <= 0 }) {
            ChunkPlanner.computeBresenhamClipFrames(
                sliceDurationsMs = chunkSpec.clips.indices.map { idx ->
                    kotlin.math.round(clipSliceDurationsSec[idx] * 1000.0).toLong().coerceAtLeast(100L)
                },
                internalTransitions = chunkSpec.internalTransitions,
                chunkGlobalStartMs = chunkSpec.globalStartMs,
                chunkGlobalEndMs = chunkSpec.globalEndMs,
                fps = exportFps
            )
        } else {
            null
        }

        for ((localIdx, clip) in chunkSpec.clips.withIndex()) {
            val safeFullD = clipFullDurationsSec[localIdx]
            val sliceStartSec = clipSliceStartsSec[localIdx]
            val isSingleFrame = clipIsSingleFrameFlags[localIdx]
            val isImgClip = clipIsImageFlags[localIdx]

            val sliceFrames = if (clip.exactFrames > 0) {
                clip.exactFrames
            } else {
                fallbackBresenham?.first?.getOrNull(localIdx)
                    ?: kotlin.math.round(clipSliceDurationsSec[localIdx] * exportFps).toInt().coerceAtLeast(1)
            }
            clipFrameCounts[localIdx] = sliceFrames
            val safeSliceD = sliceFrames.toDouble() / exportFps
            durationsArray[localIdx] = safeSliceD

            val startFrame = kotlin.math.round((clip.sliceStartInClipMs / 1000.0) * exportFps).toInt().coerceAtLeast(0)
            val maskTimeOffsetMs = clip.sliceStartInClipMs

            val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == clip.globalIndex }
            val lutFilterExpr = colorFilterOp?.let { getFFmpegFilterForName(it.filterName) }
            val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == clip.globalIndex }
            val adjustFilterExpr = adjustOp?.let { getFFmpegFilterForAdjust(it) }

            val isMirrored = clip.item.isMirrored
            val maskConfig = clip.item.maskConfig

            var preFilters = "setpts=PTS-STARTPTS"
            if (lutFilterExpr != null) preFilters += ",$lutFilterExpr"
            if (adjustFilterExpr != null) preFilters += ",$adjustFilterExpr"
            if (isMirrored) preFilters += ",hflip"

            if (maskConfig.shape != EditOperation.MaskShape.NONE) {
                val mc = maskConfig
                val cx = if (mc.positionKeyframes.isNotEmpty()) "(W*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, false, mc.relativeX, 0L, "T", timeOffsetMs = maskTimeOffsetMs) + "))" else "W*${mc.relativeX}"
                val cy = if (mc.positionKeyframes.isNotEmpty()) "(H*(" + buildFFmpegInterpolationExpr(mc.positionKeyframes, true, mc.relativeY, 0L, "T", timeOffsetMs = maskTimeOffsetMs) + "))" else "H*${mc.relativeY}"
                val sizeExpr = if (mc.sizeKeyframes.isNotEmpty()) buildFFmpegInterpolationExpr(mc.sizeKeyframes, false, ((mc.relativeWidth + mc.relativeHeight) / 2f * 200f), 0L, "T", timeOffsetMs = maskTimeOffsetMs) else null
                val mw = if (sizeExpr != null) "(W*(($sizeExpr)/200))" else "W*${mc.relativeWidth}"
                val mh = if (sizeExpr != null) "(H*(($sizeExpr)/200))" else "H*${mc.relativeHeight}"
                val cosA: String
                val sinA: String
                if (mc.rotationKeyframes.isNotEmpty()) {
                    val rotExpr = buildFFmpegInterpolationExpr(mc.rotationKeyframes, false, mc.rotationAngle, 0L, "T", timeOffsetMs = maskTimeOffsetMs)
                    val rad = "(($rotExpr)*PI/180)"
                    cosA = "cos($rad)"
                    sinA = "sin($rad)"
                } else {
                    val rad = Math.toRadians(mc.rotationAngle.toDouble())
                    cosA = Math.cos(rad).toString()
                    sinA = Math.sin(rad).toString()
                }
                val finalAlphaExpr = buildFFmpegMaskAlphaExpr(mc, cx, cy, mw, mh, cosA, sinA, 0L, "T", timeOffsetMs = maskTimeOffsetMs)
                preFilters += ",format=rgba,geq=r='r(X,Y)*($finalAlphaExpr/255)':g='g(X,Y)*($finalAlphaExpr/255)':b='b(X,Y)*($finalAlphaExpr/255)':a='255*($finalAlphaExpr/255)'"
            }
            filterParts.add("[$localIdx:v]$preFilters[pre$localIdx]")

            val motionOp = operations.filterIsInstance<EditOperation.MotionEffect>().find { it.index == clip.globalIndex }
            val clipMotionType = motionOp?.motionType ?: clip.item.motionEffect

            // Motion coordinate space scaled cleanly by device memory tier
            val scaleFactor = maxOf(1.0, memProfile.motionSpaceShortEdge / minOf(targetWidth, targetHeight))
            val baseW = (((targetWidth * scaleFactor).toInt()) / 2) * 2
            val baseH = (((targetHeight * scaleFactor).toInt()) / 2) * 2
            val motionW = (((baseW * 1.15).toInt()) / 2) * 2
            val motionH = (((baseH * 1.15).toInt()) / 2) * 2
            val coverScale = "scale=w=$motionW:h=$motionH:force_original_aspect_ratio=increase"
            val coverCrop = "crop=$motionW:$motionH:(in_w-$motionW)/2:(in_h-$motionH)/2"
            val panScaleDown = if (scaleFactor > 1.0) ",scale=${targetWidth}x${targetHeight}:flags=bilinear" else ""

            val singleFrameLoop = if (isSingleFrame) {
                ",format=yuv420p,loop=loop=-1:size=1:start=0,setpts=N/($exportFps*TB),trim=end_frame=$sliceFrames,setpts=PTS-STARTPTS"
            } else {
                ""
            }
            val zoompanD = if (isSingleFrame) sliceFrames else 1
            val zoomOnExpr = if (startFrame > 0) "(on+$startFrame)" else "on"
            val panTimeRatio = if (sliceStartSec > 0.0) "((t+$sliceStartSec)/$safeFullD)" else "(t/$safeFullD)"

            val motionFilter = when (clipMotionType) {
                "zoom_in" -> "$coverScale,$coverCrop,zoompan=z='min(1.0+0.12*($zoomOnExpr/($exportFps*$safeFullD))\\,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$zoompanD:s=${targetWidth}x${targetHeight}:fps=$exportFps"
                "zoom_out" -> "$coverScale,$coverCrop,zoompan=z='max(1.12-0.12*($zoomOnExpr/($exportFps*$safeFullD))\\,1.0)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$zoompanD:s=${targetWidth}x${targetHeight}:fps=$exportFps"
                "pan_left" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*(1-$panTimeRatio)':y='(in_h-out_h)/2'$panScaleDown"
                "pan_right" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*$panTimeRatio':y='(in_h-out_h)/2'$panScaleDown"
                "pan_up" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)/2':y='(in_h-out_h)*(1-$panTimeRatio)'$panScaleDown"
                "pan_down" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)/2':y='(in_h-out_h)*$panTimeRatio'$panScaleDown"
                "drift_up_right" -> "$coverScale,$coverCrop$singleFrameLoop,crop=$baseW:$baseH:x='(in_w-out_w)*$panTimeRatio':y='(in_h-out_h)*(1-$panTimeRatio)'$panScaleDown"
                else -> null
            }

            if (motionFilter != null) {
                filterParts.add("[pre$localIdx]$motionFilter[raw_stage$localIdx]")
            } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR) {
                val blur = bgOp.blurRadius
                val bgScale = "format=yuv420p,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight:(in_w-$targetWidth)/2:(in_h-$targetHeight)/2,boxblur=$blur"
                val fgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease"
                filterParts.add("[pre$localIdx]split=2[bg_orig$localIdx][fg_orig$localIdx]")
                filterParts.add("[bg_orig$localIdx]$bgScale[bg$localIdx]")
                filterParts.add("[fg_orig$localIdx]$fgScale[fg$localIdx]")
                filterParts.add("[bg$localIdx][fg$localIdx]overlay=(W-w)/2:(H-h)/2${singleFrameLoop}[raw_stage$localIdx]")
            } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                val bgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight:(in_w-$targetWidth)/2:(in_h-$targetHeight)/2"
                val fgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease"
                filterParts.add("[$bgImageIndex:v]$bgScale[bg$localIdx]")
                filterParts.add("[pre$localIdx]$fgScale[fg$localIdx]")
                filterParts.add("[bg$localIdx][fg$localIdx]overlay=(W-w)/2:(H-h)/2:shortest=1${singleFrameLoop}[raw_stage$localIdx]")
            } else {
                val color = bgOp?.colorHex ?: "black"
                val padColor = if (color.startsWith("#")) formatColorForFFmpeg(color) else "black"
                filterParts.add("[pre$localIdx]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2:color=$padColor,setsar=1${singleFrameLoop}[raw_stage$localIdx]")
            }

            if (isImgClip) {
                val exactTrimPrefix = if (!isSingleFrame && motionFilter == null) "trim=end_frame=$sliceFrames," else ""
                filterParts.add("[raw_stage$localIdx]${exactTrimPrefix}settb=1/$exportFps,setpts=N,setsar=1,fps=$exportFps,format=yuv420p[norm$localIdx]")
            } else {
                val speedPrefix = if (!clip.item.isReversed && clip.item.proxyUri == null && clip.item.speed != 1.0f && clip.item.speed > 0.05f) {
                    "setpts=(PTS-STARTPTS)/${clip.item.speed},"
                } else {
                    ""
                }
                filterParts.add("[raw_stage$localIdx]${speedPrefix}fps=$exportFps,settb=1/$exportFps,setpts=N,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[norm$localIdx]")
            }
        }

        // Chain internal transitions for this chunk using integer frame counts
        data class LocalStreamInfo(val vLabel: String, val frames: Int, val durationSec: Double)
        val pendingClips = mutableListOf(0)
        var currentStream: LocalStreamInfo? = null
        var transitionCounter = 0
        var concatCounter = 0

        fun flushLocalPending(clips: List<Int>): LocalStreamInfo {
            return if (clips.size == 1) {
                val i = clips[0]
                LocalStreamInfo("[norm$i]", clipFrameCounts[i], durationsArray[i])
            } else {
                val vOut = "[ccat${concatCounter}v]"
                val inputs = clips.joinToString("") { "[norm$it]" }
                filterParts.add("${inputs}concat=n=${clips.size}:v=1:a=0${vOut}")
                filterParts.add("${vOut}settb=1/${exportFps},setpts=N,fps=${exportFps},format=yuv420p[norm${concatCounter}v]")
                val sumFrames = clips.sumOf { clipFrameCounts[it] }
                concatCounter++
                LocalStreamInfo("[norm${concatCounter - 1}v]", sumFrames, sumFrames.toDouble() / exportFps)
            }
        }

        for (gapIdx in 0 until inputCount - 1) {
            val transOp = chunkSpec.internalTransitions.find { it.index == gapIdx }
            if (transOp == null || transOp.type.equals("none", ignoreCase = true) || transOp.durationMs <= 0L) {
                pendingClips.add(gapIdx + 1)
            } else {
                val baseStream = if (pendingClips.isEmpty()) {
                    currentStream ?: throw IllegalStateException("Empty stream state in chunk pass")
                } else {
                    val leftStream = flushLocalPending(pendingClips)
                    pendingClips.clear()
                    if (currentStream == null) {
                        leftStream
                    } else {
                        val vOut = "[ccat${concatCounter}v]"
                        filterParts.add("${currentStream.vLabel}${leftStream.vLabel}concat=n=2:v=1:a=0${vOut}")
                        filterParts.add("${vOut}settb=1/${exportFps},setpts=N,fps=${exportFps},format=yuv420p[norm${concatCounter}v]")
                        concatCounter++
                        val mergedFrames = currentStream.frames + leftStream.frames
                        LocalStreamInfo("[norm${concatCounter - 1}v]", mergedFrames, mergedFrames.toDouble() / exportFps)
                    }
                }

                val nextFrames = clipFrameCounts[gapIdx + 1]
                val maxSafeFrames = minOf(baseStream.frames - 1, nextFrames - 1).coerceAtLeast(1)
                val rawTransFrames = kotlin.math.round((transOp.durationMs / 1000.0) * exportFps).toInt().coerceAtLeast(1)
                val transFrames = rawTransFrames.coerceIn(1, maxSafeFrames)
                val offsetFrames = (baseStream.frames - transFrames).coerceAtLeast(0)
                val transDuration = transFrames.toDouble() / exportFps
                val offset = offsetFrames.toDouble() / exportFps

                val rawXfvOut = "[raw_xfv${transitionCounter}]"
                val normXfvOut = "[norm_xfv${transitionCounter}]"
                val nextVLabel = "[norm${gapIdx + 1}]"

                val validXfadeTransitions = setOf(
                    "fade", "fadeblack", "fadewhite", "rectcrop", "circlecrop", "circleclose", "circleopen",
                    "horzclose", "horzopen", "vertclose", "vertopen", "diagbl", "diagbr", "diagtl", "diagtr",
                    "hlslice", "hrslice", "vuslice", "vdslice", "dissolve", "pixelize", "slidedown", "slideleft",
                    "slideright", "slideup", "wipedown", "wipeleft", "wiperight", "wipeup", "zoomin", "squeezeh",
                    "squeezev", "coverleft", "coverright", "coverup", "coverdown", "revealleft", "revealright",
                    "revealup", "revealdown"
                )
                val rawType = transOp.type.lowercase()
                val sanitizedType = when (rawType) {
                    "smoothleft" -> "coverleft"
                    "smoothright" -> "coverright"
                    "distance" -> "zoomin"
                    else -> if (validXfadeTransitions.contains(rawType)) rawType else "fade"
                }

                val formattedDuration = String.format(java.util.Locale.US, "%.6f", transDuration)
                val formattedOffset = String.format(java.util.Locale.US, "%.6f", offset)

                filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                filterParts.add("${rawXfvOut}settb=1/${exportFps},setpts=N,fps=${exportFps},format=yuv420p${normXfvOut}")

                val newFrames = offsetFrames + nextFrames
                currentStream = LocalStreamInfo(normXfvOut, newFrames, newFrames.toDouble() / exportFps)
                transitionCounter++
            }
        }

        val remainingStream = if (pendingClips.isNotEmpty()) flushLocalPending(pendingClips) else null
        val finalStream: LocalStreamInfo = when {
            currentStream == null && remainingStream != null -> remainingStream
            currentStream != null && remainingStream == null -> currentStream!!
            currentStream != null && remainingStream != null -> {
                val vOut = "[ccat${concatCounter}v]"
                filterParts.add("${currentStream.vLabel}${remainingStream.vLabel}concat=n=2:v=1:a=0${vOut}")
                filterParts.add("${vOut}settb=1/${exportFps},setpts=N,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${exportFps},format=yuv420p[norm${concatCounter}v]")
                concatCounter++
                val sumFrames = currentStream.frames + remainingStream.frames
                LocalStreamInfo("[norm${concatCounter - 1}v]", sumFrames, sumFrames.toDouble() / exportFps)
            }
            else -> LocalStreamInfo("[norm0]", clipFrameCounts[0], durationsArray[0])
        }

        var currentVideoLabel = finalStream.vLabel
        val safeTailPadFrames = tailPadFrames.coerceAtLeast(0)
        if (safeTailPadFrames > 0) {
            val padOutLabel = "[chunk_tpad]"
            filterParts.add("${currentVideoLabel}tpad=stop_mode=clone:stop=$safeTailPadFrames$padOutLabel")
            currentVideoLabel = padOutLabel
        }

        // 5. Clamped overlays and subtitles for this chunk
        val clampedTextOps = mutableListOf<EditOperation.AddText>()
        for (textOp in operations.filterIsInstance<EditOperation.AddText>()) {
            val start = textOp.startTimeMs ?: 0L
            val end = textOp.endTimeMs ?: totalDurationMs
            val clamped = ChunkPlanner.clampToChunkWindow(start, end, chunkStart, chunkDur)
            if (clamped.isVisibleInChunk) {
                clampedTextOps.add(textOp.copy(startTimeMs = clamped.localStartMs, endTimeMs = clamped.localEndMs))
            }
        }

        val clampedSubtitleOps = mutableListOf<EditOperation.AddSubtitles>()
        for (subOp in operations.filterIsInstance<EditOperation.AddSubtitles>()) {
            val clampedCues = mutableListOf<com.boostlyte.vydra.models.SubtitleCue>()
            for (cue in subOp.cues) {
                val clamped = ChunkPlanner.clampToChunkWindow(cue.startTimeMs, cue.endTimeMs, chunkStart, chunkDur)
                if (clamped.isVisibleInChunk) {
                    clampedCues.add(cue.copy(startTimeMs = clamped.localStartMs, endTimeMs = clamped.localEndMs))
                }
            }
            if (clampedCues.isNotEmpty()) {
                clampedSubtitleOps.add(subOp.copy(cues = clampedCues))
            }
        }

        val effectiveFontPath = resolveEffectiveFontPath(fontFilePath, context)
        val overlayOpsToApply = clampedTextOps + clampedImageOps + clampedSubtitleOps
        if (overlayOpsToApply.isNotEmpty()) {
            val (overlayStages, tempLabel) = buildVideoFilterStages(
                operations = overlayOpsToApply,
                fontFilePath = effectiveFontPath,
                inputLabel = currentVideoLabel,
                imageInputIndices = visibleImageIndices,
                density = density,
                sourceVideoHeight = targetHeight,
                timeOffsetMs = 0L
            )
            filterParts.addAll(overlayStages)
            currentVideoLabel = tempLabel
        }

        val finalVideoLabel = "[chunk_out]"
        val hasCropOverlay = overlayOpsToApply.any { it is EditOperation.Crop }
        if (hasCropOverlay) {
            filterParts.add("${currentVideoLabel}scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$exportFps,format=yuv420p$finalVideoLabel")
        } else {
            filterParts.add("${currentVideoLabel}setsar=1,fps=$exportFps,format=yuv420p$finalVideoLabel")
        }

        val filterComplexStr = filterParts.joinToString(";\n")
        var scriptFile: File? = null
        if (context != null) {
            scriptFile = File(context.cacheDir, "filter_script_chunk_${chunkSpec.chunkIndex}_${System.nanoTime()}.txt")
            scriptFile.writeText(filterComplexStr)
            cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
        } else {
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
        }

        val bitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(_exportResolution.value, _exportFps.value)
        val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(_exportResolution.value)
        val videoCodec = encoderOverride ?: run {
            val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
                context, targetWidth, targetHeight, exportFps, bitrate, swCrf, memProfile.softwareEncoderThreads
            )
            enc.ffmpegVideoCodecArgs
        }

        val chunkTotalFrames = when {
            safeTailPadFrames > 0 -> (finalStream.frames + safeTailPadFrames).coerceAtLeast(1)
            chunkSpec.totalFrames > 0 -> chunkSpec.totalFrames
            else -> finalStream.frames.coerceAtLeast(1)
        }
        val exactChunkDurSecs = chunkTotalFrames.toDouble() / exportFps
        val timescale = exportFps * 1000

        cmd.append(" -map \"$finalVideoLabel\" -an $videoCodec")
        cmd.append(" -frames:v $chunkTotalFrames -video_track_timescale $timescale \"$chunkOutputPath\"")

        val commandStr = cmd.toString()
        if (context != null) {
            com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                context = context,
                passName = "chunk_${chunkSpec.chunkIndex + 1}_built",
                command = commandStr,
                filterScriptContent = filterComplexStr
            )
        }
        Log.d(TAG, "Built chunk ${chunkSpec.chunkIndex} command: $commandStr")
        return ChunkCommand(
            chunkIndex = chunkSpec.chunkIndex,
            command = commandStr,
            outputPath = chunkOutputPath,
            durationSecs = exactChunkDurSecs,
            scriptFilePath = scriptFile?.absolutePath,
            tailPadFrames = safeTailPadFrames
        )
    }

    /**
     * Build the final pass command that chains chunk videos and mixes full audio track.
     */
    fun buildFinalPassCommand(
        chunkPlan: ChunkPlan,
        chunkTempPaths: List<String>,
        outputFilePath: String,
        context: Context?,
        targetWidth: Int,
        targetHeight: Int,
        operations: List<EditOperation>,
        encoderOverride: String? = null
    ): ChunkCommand {
        val cmd = StringBuilder("-y")
        var inputIndex = 0

        // 1. Chunk temp video inputs
        for (tempPath in chunkTempPaths) {
            cmd.append(" -i \"$tempPath\"")
            inputIndex++
        }

        // 2. Audio inputs
        val audioOps = operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = resolveUriToPath(context, audioOp.audioUri) ?: continue
            cmd.append(" -i \"$audioPath\"")
            audioInputIndices.add(Pair(inputIndex, audioOp))
            inputIndex++
        }

        val filterParts = mutableListOf<String>()
        val numChunks = chunkTempPaths.size

        // Chain chunk videos using boundary transitions
        // Up-front normalization of all raw chunk video inputs:
        // Guarantees that EVERY input link to EVERY xfade or concat has the exact same timebase (1/fps),
        // monotonic timestamps (PTS-STARTPTS), frame rate, and pixel format.
        // This permanently eliminates the timebase mismatch crash (e.g. 1/30 vs 1/15360) on Android/Vivo Y85.
        for (i in 0 until numChunks) {
            val rawChunk = "[$i:v]"
            val normChunk = "[norm_in$i]"
            filterParts.add("${rawChunk}settb=1/${_exportFps.value},setpts=PTS-STARTPTS,fps=${_exportFps.value},format=yuv420p${normChunk}")
        }

        val exportFpsSafe = _exportFps.value.coerceAtLeast(1)
        fun chunkDurSec(index: Int): Double {
            val c = chunkPlan.chunks.getOrNull(index) ?: return 0.0
            return if (c.totalFrames > 0) c.totalFrames.toDouble() / exportFpsSafe else c.durationMs / 1000.0
        }

        val hasAnyBoundaryTransitions = chunkPlan.boundaryTransitions.any { it.transition != null && !it.transition.type.equals("none", true) && it.transition.durationMs > 0L }
        var currentV: String
        var currentStreamDurationSec: Double

        if (!hasAnyBoundaryTransitions) {
            // Flat N-way concatenation of pre-normalized chunks:
            val allInputs = (0 until numChunks).joinToString("") { "[norm_in$it]" }
            val rawCcatOut = "[raw_ccat]"
            val normCcatOut = "[norm_ccat]"
            filterParts.add("${allInputs}concat=n=$numChunks:v=1:a=0${rawCcatOut}")
            filterParts.add("${rawCcatOut}settb=1/${exportFpsSafe},setpts=N,fps=${exportFpsSafe},format=yuv420p${normCcatOut}")
            currentV = normCcatOut
            currentStreamDurationSec = (0 until numChunks).sumOf { chunkDurSec(it) }
        } else {
            currentV = "[norm_in0]"
            currentStreamDurationSec = chunkDurSec(0)

            for (bIdx in 0 until numChunks - 1) {
                val nextChunkInput = "[norm_in${bIdx + 1}]"
                val nextChunkDurationSec = chunkDurSec(bIdx + 1)
                val boundaryTrans = chunkPlan.boundaryTransitions.find { it.boundaryIndex == bIdx }?.transition

                if (boundaryTrans != null && !boundaryTrans.type.equals("none", true) && boundaryTrans.durationMs > 0L) {
                    val rawTransDuration = boundaryTrans.durationMs / 1000.0
                    val maxSafeDuration = minOf(currentStreamDurationSec - 0.05, nextChunkDurationSec - 0.05).coerceAtLeast(0.1)
                    val transDuration = rawTransDuration.coerceIn(0.1, maxSafeDuration)
                    val offset = (currentStreamDurationSec - transDuration).coerceAtLeast(0.0)

                    val rawXfvOut = "[raw_xfv$bIdx]"
                    val normXfvOut = "[norm_xfv$bIdx]"

                    val validXfadeTransitions = setOf(
                        "fade", "fadeblack", "fadewhite", "rectcrop", "circlecrop", "circleclose", "circleopen",
                        "horzclose", "horzopen", "vertclose", "vertopen", "diagbl", "diagbr", "diagtl", "diagtr",
                        "hlslice", "hrslice", "vuslice", "vdslice", "dissolve", "pixelize", "slidedown", "slideleft",
                        "slideright", "slideup", "wipedown", "wipeleft", "wiperight", "wipeup", "zoomin", "squeezeh",
                        "squeezev", "coverleft", "coverright", "coverup", "coverdown", "revealleft", "revealright",
                        "revealup", "revealdown"
                    )
                    val rawType = boundaryTrans.type.lowercase()
                    val sanitizedType = when (rawType) {
                        "smoothleft" -> "coverleft"
                        "smoothright" -> "coverright"
                        "distance" -> "zoomin"
                        else -> if (validXfadeTransitions.contains(rawType)) rawType else "fade"
                    }

                    val formattedDuration = String.format(java.util.Locale.US, "%.6f", transDuration)
                    val formattedOffset = String.format(java.util.Locale.US, "%.6f", offset)

                    filterParts.add("${currentV}${nextChunkInput}xfade=transition=${sanitizedType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                    filterParts.add("${rawXfvOut}settb=1/${exportFpsSafe},setpts=N,fps=${exportFpsSafe},format=yuv420p${normXfvOut}")

                    currentV = normXfvOut
                    currentStreamDurationSec = offset + nextChunkDurationSec
                } else {
                    val rawCcatOut = "[raw_ccat$bIdx]"
                    val normCcatOut = "[norm_ccat$bIdx]"

                    filterParts.add("${currentV}${nextChunkInput}concat=n=2:v=1:a=0${rawCcatOut}")
                    filterParts.add("${rawCcatOut}settb=1/${exportFpsSafe},setpts=N,fps=${exportFpsSafe},format=yuv420p${normCcatOut}")

                    currentV = normCcatOut
                    currentStreamDurationSec += nextChunkDurationSec
                }
            }
        }

        val audioStageResult = buildBackgroundAudioFilterStages(audioInputIndices)
        filterParts.addAll(audioStageResult.filterParts)
        val currentAudioLabel = audioStageResult.outputLabel
        val maxAudioEndSec = audioStageResult.maxAudioEndSec

        // If audio extends past total video duration, pad video with frozen last frame (tpad)
        val finalEffectiveDurationSec = if (maxAudioEndSec > currentStreamDurationSec && maxAudioEndSec > 0.0) {
            val padFrames = kotlin.math.round((maxAudioEndSec - currentStreamDurationSec) * exportFpsSafe).toInt().coerceAtLeast(1)
            filterParts.add("${currentV}tpad=stop_mode=clone:stop=$padFrames[pad_v]")
            currentV = "[pad_v]"
            currentStreamDurationSec + (padFrames.toDouble() / exportFpsSafe)
        } else {
            currentStreamDurationSec
        }

        val filterComplexStr = filterParts.joinToString(";\n")
        var scriptFile: File? = null
        if (context != null) {
            scriptFile = File(context.cacheDir, "filter_script_final_${System.nanoTime()}.txt")
            scriptFile.writeText(filterComplexStr)
            cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
        } else {
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
        }

        val bitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(_exportResolution.value, exportFpsSafe)
        val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(_exportResolution.value)
        val audioBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetAudioBitrate(_exportResolution.value)
        val memProfile = com.boostlyte.vydra.export.ExportMemoryProfile.fromContext(context, targetWidth, targetHeight)
        val videoCodec = encoderOverride ?: run {
            val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
                context, targetWidth, targetHeight, exportFpsSafe, bitrate, swCrf, memProfile.softwareEncoderThreads
            )
            enc.ffmpegVideoCodecArgs
        }

        cmd.append(" -map \"$currentV\"")
        if (currentAudioLabel != null) {
            cmd.append(" -map \"$currentAudioLabel\" -c:a aac -b:a $audioBitrate")
        } else {
            cmd.append(" -an")
        }
        cmd.append(" $videoCodec -video_track_timescale ${exportFpsSafe * 1000}")

        val formattedTotalDur = String.format(java.util.Locale.US, "%.6f", finalEffectiveDurationSec).trimEnd('0').trimEnd('.')
        cmd.append(" -t $formattedTotalDur \"$outputFilePath\"")

        val commandStr = cmd.toString()
        if (context != null) {
            com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                context = context,
                passName = "final_pass_fallback_built",
                command = commandStr,
                filterScriptContent = filterComplexStr
            )
        }
        Log.d(TAG, "Built final pass command: $commandStr")
        return ChunkCommand(
            chunkIndex = -1,
            command = commandStr,
            outputPath = outputFilePath,
            durationSecs = finalEffectiveDurationSec,
            scriptFilePath = scriptFile?.absolutePath
        )
    }

    private data class AudioFilterStageResult(
        val filterParts: List<String>,
        val outputLabel: String?,
        val maxAudioEndSec: Double
    )

    private fun buildBackgroundAudioFilterStages(
        audioInputIndices: List<Pair<Int, EditOperation.AddBackgroundAudio>>
    ): AudioFilterStageResult {
        if (audioInputIndices.isEmpty()) {
            return AudioFilterStageResult(emptyList(), null, 0.0)
        }
        val filterParts = mutableListOf<String>()
        val mixInputLabels = mutableListOf<String>()
        var maxAudioEndSec = 0.0

        for ((idx, pair) in audioInputIndices.withIndex()) {
            val (inputIdx, audioOp) = pair
            val trackLabel = "[a_bg_$idx]"
            val filters = mutableListOf<String>()

            val internalStartSec = audioOp.internalStartMs / 1000.0
            var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

            if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                val maxEndSec = internalStartSec + timelineDurationSec
                if (maxEndSec < internalEndSec) internalEndSec = maxEndSec
            }

            if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                if (internalEndSec != Double.MAX_VALUE) {
                    filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                } else {
                    filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                }
            }
            if (audioOp.fadeInDurationMs > 0L) {
                val fadeSec = audioOp.fadeInDurationMs / 1000.0
                filters.add("afade=t=in:st=0:d=$fadeSec")
            }
            if (audioOp.fadeOutDurationMs > 0L) {
                val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                    (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                } else if (audioOp.internalEndMs > 0L) {
                    (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                } else {
                    (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                }
                val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
            }
            var delayMs = audioOp.startTimeMs ?: 0L
            if (delayMs < 0) delayMs = 0L
            if (delayMs > 0) {
                filters.add("aformat=channel_layouts=stereo,adelay=$delayMs|$delayMs")
            }
            val safeVol = audioOp.volume.coerceIn(0f, 2f)
            filters.add("volume=${String.format(java.util.Locale.US, "%.2f", safeVol)}")

            val effectiveAudioDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
            } else if (audioOp.internalEndMs > 0L) {
                (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
            } else {
                audioOp.originalDurationMs / 1000.0
            }
            val totalAudioTrackEnd = (delayMs / 1000.0) + effectiveAudioDur
            if (totalAudioTrackEnd > maxAudioEndSec) {
                maxAudioEndSec = totalAudioTrackEnd
            }

            if (filters.isNotEmpty()) {
                filterParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                mixInputLabels.add(trackLabel)
            } else {
                mixInputLabels.add("[$inputIdx:a]")
            }
        }

        if (mixInputLabels.size == 1) {
            val singleLabel = mixInputLabels[0]
            filterParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
        } else {
            val allBg = mixInputLabels.joinToString("")
            filterParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
        }
        return AudioFilterStageResult(filterParts, "[outa]", maxAudioEndSec)
    }

    private fun isConfirmedAacInM4a(audioPath: String): Boolean {
        val file = File(audioPath)
        if (!file.exists() || file.length() < 32L) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val headLen = minOf(len, 8192L).toInt()
                val head = ByteArray(headLen)
                raf.readFully(head)
                val headStr = String(head, Charsets.ISO_8859_1)
                if (!headStr.contains("ftyp")) return false
                if (headStr.contains("alac")) return false
                if (headStr.contains("mp4a")) return true

                // Many .m4a recordings write `moov` (containing `mp4a`) after `mdat` at the end of the file.
                // Walk top-level ISO-BMFF boxes in O(1) seeks to locate `moov`.
                var offset = 0L
                var hops = 0
                while (offset + 8L <= len && hops < 32) {
                    raf.seek(offset)
                    val boxSizeU32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val boxType = String(typeBytes, Charsets.ISO_8859_1)
                    val actualSize = if (boxSizeU32 == 1L && offset + 16L <= len) {
                        raf.readLong()
                    } else if (boxSizeU32 == 0L) {
                        len - offset
                    } else {
                        boxSizeU32
                    }
                    if (actualSize < 8L) break
                    if (boxType == "moov") {
                        val moovRead = minOf(actualSize, 262_144L).toInt()
                        val moovBytes = ByteArray(moovRead)
                        raf.seek(offset)
                        raf.readFully(moovBytes)
                        val moovStr = String(moovBytes, Charsets.ISO_8859_1)
                        return moovStr.contains("mp4a") && !moovStr.contains("alac")
                    }
                    offset += actualSize
                    hops++
                }

                // Fallback: inspect tail bytes if box sizes were non-standard
                val tailSize = minOf(len, 16384L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(len - tailSize)
                raf.readFully(tail)
                val tailStr = String(tail, Charsets.ISO_8859_1)
                tailStr.contains("mp4a") && !tailStr.contains("alac")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isAdtsAac(audioPath: String): Boolean {
        if (!audioPath.endsWith(".aac", ignoreCase = true)) return false
        val file = File(audioPath)
        if (!file.exists() || file.length() < 4L) return false
        return try {
            val bytes = ByteArray(4)
            file.inputStream().use { it.read(bytes) }
            (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xF6) == 0xF0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds an audio-only FFmpeg command to mix voiceover/background audio tracks into a single
     * AAC .m4a file so final assembly can stream-copy all video chunks (`-c copy`) with the mixed audio.
     * Uses direct stream-copy when a single unmodified AAC track is provided to avoid unnecessary re-encoding.
     */
    fun buildAudioMixCommand(
        operations: List<EditOperation>,
        outputAudioPath: String,
        targetDurationSecs: Double,
        context: Context?,
        audioBitrate: String = com.boostlyte.vydra.export.EncoderSelector.getTargetAudioBitrate(_exportResolution.value)
    ): String? {
        if (operations.any { it is EditOperation.MuteAudio }) return null
        val audioOps = operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        if (audioOps.isEmpty()) return null

        val audioInputs = mutableListOf<Pair<String, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = resolveUriToPath(context, audioOp.audioUri) ?: continue
            audioInputs.add(Pair(audioPath, audioOp))
        }
        if (audioInputs.isEmpty()) return null

        // Single-track fast path: bypass redundant filter_complex graph when track has no trim/fade/delay/gain edits
        if (audioInputs.size == 1) {
            val (audioPath, op) = audioInputs[0]
            val noFadeOrDelay = op.fadeInDurationMs <= 0L && op.fadeOutDurationMs <= 0L && (op.startTimeMs ?: 0L) <= 0L
            val unityVol = op.volume in 0.99f..1.01f
            val noStartTrim = op.internalStartMs <= 0L
            val effectiveOpDurSec = when {
                op.endTimeMs != null -> (op.endTimeMs - (op.startTimeMs ?: 0L)) / 1000.0
                op.internalEndMs > 0L -> op.internalEndMs / 1000.0
                else -> op.originalDurationMs / 1000.0
            }
            val effectiveTargetDur = maxOf(targetDurationSecs, effectiveOpDurSec).coerceAtLeast(0.1)
            val fullLengthUsed = (op.internalEndMs <= 0L || (op.originalDurationMs > 0L && op.internalEndMs >= op.originalDurationMs - 50L)) &&
                (op.endTimeMs == null || (op.endTimeMs >= effectiveTargetDur * 1000.0 - 50.0))

            if (noFadeOrDelay && unityVol && noStartTrim && fullLengthUsed) {
                val formattedDur = String.format(java.util.Locale.US, "%.6f", effectiveTargetDur).trimEnd('0').trimEnd('.')
                val directCmd = when {
                    isConfirmedAacInM4a(audioPath) ->
                        "-y -i \"$audioPath\" -vn -c:a copy -t $formattedDur \"$outputAudioPath\""
                    isAdtsAac(audioPath) ->
                        "-y -i \"$audioPath\" -vn -c:a copy -bsf:a aac_adtstoasc -t $formattedDur \"$outputAudioPath\""
                    else ->
                        "-y -i \"$audioPath\" -vn -ar 44100 -ac 2 -c:a aac -b:a $audioBitrate -t $formattedDur \"$outputAudioPath\""
                }
                if (context != null) {
                    com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                        context = context,
                        passName = "audio_mix_built",
                        command = directCmd,
                        filterScriptContent = null
                    )
                }
                return directCmd
            }
        }

        val cmd = StringBuilder("-y")
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for ((idx, pair) in audioInputs.withIndex()) {
            cmd.append(" -i \"${pair.first}\"")
            audioInputIndices.add(Pair(idx, pair.second))
        }

        val audioStage = buildBackgroundAudioFilterStages(audioInputIndices)
        val outLabel = audioStage.outputLabel ?: return null
        val filterStr = audioStage.filterParts.joinToString(";")

        if (context != null) {
            val scriptFile = File(context.cacheDir, "filter_script_audio_${System.nanoTime()}.txt")
            scriptFile.writeText(filterStr)
            cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
        } else {
            cmd.append(" -filter_complex \"$filterStr\"")
        }

        val effectiveDur = maxOf(targetDurationSecs, audioStage.maxAudioEndSec).coerceAtLeast(0.1)
        val formattedDur = String.format(java.util.Locale.US, "%.6f", effectiveDur).trimEnd('0').trimEnd('.')
        cmd.append(" -vn -map \"$outLabel\" -c:a aac -b:a $audioBitrate -t $formattedDur \"$outputAudioPath\"")
        val audioCmdStr = cmd.toString()
        if (context != null) {
            com.boostlyte.vydra.utils.AppLogger.appendFilterGraphEntry(
                context = context,
                passName = "audio_mix_built",
                command = audioCmdStr,
                filterScriptContent = filterStr
            )
        }
        return audioCmdStr
    }

    internal fun computeMaxAudioEndSec(operations: List<EditOperation>): Double {
        if (operations.any { it is EditOperation.MuteAudio }) return 0.0
        var maxEndSec = 0.0
        for (audioOp in operations.filterIsInstance<EditOperation.AddBackgroundAudio>()) {
            val delayMs = (audioOp.startTimeMs ?: 0L).coerceAtLeast(0L)
            val effectiveDurMs = when {
                audioOp.endTimeMs != null && audioOp.startTimeMs != null ->
                    (audioOp.endTimeMs - audioOp.startTimeMs).coerceAtLeast(0L)
                audioOp.internalEndMs > 0L ->
                    (audioOp.internalEndMs - audioOp.internalStartMs).coerceAtLeast(0L)
                audioOp.originalDurationMs > 0L ->
                    audioOp.originalDurationMs
                else -> 0L
            }
            val trackEndSec = (delayMs + effectiveDurMs) / 1000.0
            if (trackEndSec > maxEndSec) {
                maxEndSec = trackEndSec
            }
        }
        return maxEndSec
    }

    /**
     * Build a ChunkExportPlan for the project.
     * Preserves single-pass export byte-identically when total sequence input count <= 24.
     * Activates chunked rendering when input count > 24.
     */
    fun buildChunkExportPlan(
        sourceFilePath: String,
        outputFilePath: String,
        fontFilePath: String? = null,
        context: Context? = null,
        operationsOverride: List<EditOperation>? = null
    ): ChunkExportPlan? {
        val currentProject = _project.value ?: return null
        val activeOperations = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(
            operationsOverride ?: currentProject.operations
        )
        val effectiveFontPath = resolveEffectiveFontPath(fontFilePath, context)


        val mergeOp = activeOperations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val transitions = activeOperations.filterIsInstance<EditOperation.Transition>()

        val trimOp = activeOperations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val speedOp = activeOperations.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val reverseOp = activeOperations.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val mirrorOp = activeOperations.filterIsInstance<EditOperation.MirrorMain>().lastOrNull()
        val maskMainOp = activeOperations.filterIsInstance<EditOperation.MaskMain>().lastOrNull()
        val maskConfig = maskMainOp?.maskConfig ?: EditOperation.MaskConfig()
        val isMirrored = mirrorOp?.isMirrored ?: false
        val isReversed = reverseOp?.isReversed ?: false
        val speed = speedOp?.speed ?: 1.0f
        val proxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri

        val probeStartMs = System.currentTimeMillis()
        val isMainImage = sourceFilePath.endsWith(".png", ignoreCase = true) ||
                          sourceFilePath.endsWith(".jpg", ignoreCase = true) ||
                          sourceFilePath.endsWith(".jpeg", ignoreCase = true) ||
                          sourceFilePath.endsWith(".webp", ignoreCase = true)

        var sourceDurationMs = 5000L
        if (!isMainImage) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val dStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    sourceDurationMs = dStr?.toLongOrNull() ?: 5000L
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting source duration: ${e.message}")
            }
        } else {
            sourceDurationMs = trimOp?.let { it.endMs - it.startMs } ?: 5000L
        }

        val primaryUri: android.net.Uri = try {
            val parsed: android.net.Uri? = android.net.Uri.parse(sourceFilePath)
            parsed ?: currentProject.sourceUri
        } catch (_: Exception) {
            currentProject.sourceUri
        }

        val primaryItem = EditOperation.MergeItem(
            uri = primaryUri,
            durationMs = sourceDurationMs,
            trimStartMs = trimOp?.startMs ?: 0L,
            trimEndMs = trimOp?.endMs ?: sourceDurationMs,
            speed = speed,
            isReversed = isReversed,
            isMirrored = isMirrored,
            proxyUri = proxyUri,
            maskConfig = maskConfig,
            isImage = isMainImage
        )

        val allSequenceItems = mutableListOf(primaryItem)
        if (mergeOp != null) {
            allSequenceItems.addAll(mergeOp.items)
        }

        var sourceVideoWidth = 1280
        var sourceVideoHeight = 720
        if (isMainImage) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(sourceFilePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    sourceVideoWidth = opts.outWidth
                    sourceVideoHeight = opts.outHeight
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading image dimensions: ${e.message}")
            }
        } else {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val w = wStr?.toIntOrNull() ?: 1280
                    val h = hStr?.toIntOrNull() ?: 720
                    val r = rStr?.toIntOrNull() ?: 0
                    if (r == 90 || r == 270) {
                        sourceVideoWidth = h
                        sourceVideoHeight = w
                    } else {
                        sourceVideoWidth = w
                        sourceVideoHeight = h
                    }
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading video dimensions: ${e.message}")
            }
        }
        val probeSecs = (System.currentTimeMillis() - probeStartMs) / 1000.0
        if (context != null) {
            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                context,
                "prep_media_probe",
                probeSecs,
                "clips=${allSequenceItems.size} mainImage=$isMainImage src=${sourceVideoWidth}x${sourceVideoHeight}"
            )
        }

        val targetCanvasRatio = currentProject.canvasAspectRatio ?: com.boostlyte.vydra.models.CanvasAspectRatio.fromDimensions(sourceVideoWidth, sourceVideoHeight)
        val (targetWidth, targetHeight) = targetCanvasRatio.getTargetDimensions(_exportResolution.value)
        val memProfile = com.boostlyte.vydra.export.ExportMemoryProfile.fromContext(context, targetWidth, targetHeight)

        val chunkTempDir = context?.cacheDir ?: File("/tmp")
        val timestamp = System.currentTimeMillis()

        val bitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetVideoBitrate(_exportResolution.value, _exportFps.value)
        val swCrf = com.boostlyte.vydra.export.EncoderSelector.getTargetSoftwareCrf(_exportResolution.value)
        val audioBitrate = com.boostlyte.vydra.export.EncoderSelector.getTargetAudioBitrate(_exportResolution.value)

        // Select capability-aware encoder path consistently for the entire export
        val selectedEncoder = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
            context = context,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fps = _exportFps.value,
            bitrate = bitrate,
            crf = swCrf,
            softwareThreads = memProfile.softwareEncoderThreads
        )
        val overallRenderPath = "legacy"

        val dynamicTriggerThreshold = if (memProfile.isLowMemoryTier) {
            maxOf(8, memProfile.maxChunkSize * 2)
        } else {
            ChunkPlanner.CHUNK_TRIGGER_THRESHOLD
        }

        // TRIGGER CHECK: Single-pass if below trigger threshold, otherwise chunk sequentially
        if (allSequenceItems.size <= dynamicTriggerThreshold) {
            val cmdBuildStartMs = System.currentTimeMillis()
            val singlePassCmd = buildConsolidatedFFmpegCommand(
                sourceFilePath = sourceFilePath,
                outputFilePath = outputFilePath,
                fontFilePath = effectiveFontPath,
                context = context,
                operationsOverride = activeOperations,
                encoderOverride = selectedEncoder.ffmpegVideoCodecArgs
            ) ?: return null
            if (context != null) {
                com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                    context,
                    "prep_command_build",
                    (System.currentTimeMillis() - cmdBuildStartMs) / 1000.0,
                    "mode=single_pass encoderBackend=${selectedEncoder.backend} lowMem=${memProfile.isLowMemoryTier} swThreads=${memProfile.softwareEncoderThreads}"
                )
            }

            return ChunkExportPlan(
                isChunked = false,
                singlePassCommand = singlePassCmd,
                renderPath = overallRenderPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                fps = _exportFps.value,
                resolution = _exportResolution.value,
                density = context?.resources?.displayMetrics?.density ?: 1.0f,
                fontFilePath = effectiveFontPath,
                operations = activeOperations,
                audioBitrate = audioBitrate
            )
        }

        // Large sequence -> Plan chunks while containing boundary transitions inside chunks
        val chunkPlanStartMs = System.currentTimeMillis()
        val plan = ChunkPlanner.plan(
            sequenceItems = allSequenceItems,
            transitions = transitions,
            containBoundaryTransitions = true,
            fps = _exportFps.value,
            maxChunkSize = memProfile.maxChunkSize,
            chunkTriggerThreshold = dynamicTriggerThreshold
        )
        val chunkPlanSecs = (System.currentTimeMillis() - chunkPlanStartMs) / 1000.0
        val density = context?.resources?.displayMetrics?.density ?: 1.0f

        val cmdBuildStartMs = System.currentTimeMillis()
        val hasBoundaryTransitions = plan.boundaryTransitions.any { it.transition != null && !it.transition.type.equals("none", true) && it.transition.durationMs > 0L }
        val isMuted = activeOperations.any { it is EditOperation.MuteAudio }
        val hasAudioOperations = !isMuted && activeOperations.any { it is EditOperation.AddBackgroundAudio }
        val exportFpsSafe = _exportFps.value.coerceAtLeast(1)

        val baseVideoOnlyDurationSecs = if (!hasBoundaryTransitions && plan.chunks.all { it.totalFrames > 0 }) {
            plan.chunks.sumOf { it.totalFrames }.toDouble() / exportFpsSafe
        } else {
            plan.totalDurationMs / 1000.0
        }
        val maxAudioEndSec = if (hasAudioOperations) computeMaxAudioEndSec(activeOperations) else 0.0

        // Fold audio-tail padding directly into the LAST chunk's initial render pass
        // so the last chunk is encoded only ONCE and final assembly remains fast `-c copy`.
        val tailPadFrames = if (!hasBoundaryTransitions && hasAudioOperations && plan.chunks.isNotEmpty() && maxAudioEndSec > baseVideoOnlyDurationSecs + 0.05) {
            kotlin.math.round((maxAudioEndSec - baseVideoOnlyDurationSecs) * exportFpsSafe).toInt().coerceAtLeast(1)
        } else {
            0
        }
        val tailPadMs = if (tailPadFrames > 0) {
            kotlin.math.round((tailPadFrames.toDouble() / exportFpsSafe) * 1000.0).toLong()
        } else {
            0L
        }
        val effectiveChunks = if (tailPadFrames > 0 && plan.chunks.isNotEmpty()) {
            plan.chunks.mapIndexed { idx, c ->
                if (idx == plan.chunks.lastIndex) {
                    c.copy(
                        globalEndMs = c.globalEndMs + tailPadMs,
                        durationMs = c.durationMs + tailPadMs,
                        totalFrames = c.totalFrames + tailPadFrames
                    )
                } else {
                    c
                }
            }
        } else {
            plan.chunks
        }
        val effectivePlan = if (tailPadFrames > 0) {
            plan.copy(
                chunks = effectiveChunks,
                totalDurationMs = plan.totalDurationMs + tailPadMs
            )
        } else {
            plan
        }

        val chunkCommands = mutableListOf<ChunkCommand>()
        val chunkTempPaths = mutableListOf<String>()

        for (chunk in effectiveChunks) {
            val chunkTempFile = File(chunkTempDir, "temp_chunk_${chunk.chunkIndex}_${timestamp}.mp4")
            chunkTempPaths.add(chunkTempFile.absolutePath)
            val chunkTailPadFrames = if (chunk.chunkIndex == effectiveChunks.lastIndex) tailPadFrames else 0

            val chunkCmd = buildChunkPassCommand(
                chunkSpec = chunk,
                sourceFilePath = sourceFilePath,
                chunkOutputPath = chunkTempFile.absolutePath,
                fontFilePath = effectiveFontPath,
                context = context,
                totalDurationMs = effectivePlan.totalDurationMs,
                density = density,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                operations = activeOperations,
                encoderOverride = selectedEncoder.ffmpegVideoCodecArgs,
                tailPadFrames = chunkTailPadFrames
            )
            chunkCommands.add(chunkCmd.copy(renderPath = overallRenderPath))
        }

        val finalPassCmd = buildFinalPassCommand(
            chunkPlan = effectivePlan,
            chunkTempPaths = chunkTempPaths,
            outputFilePath = outputFilePath,
            context = context,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            operations = activeOperations,
            encoderOverride = selectedEncoder.ffmpegVideoCodecArgs
        )

        val canStreamCopy = !hasBoundaryTransitions && !hasAudioOperations
        val exactChunkDurationsSecs = effectiveChunks.map { c ->
            if (c.totalFrames > 0) c.totalFrames.toDouble() / exportFpsSafe else c.durationMs / 1000.0
        }
        val videoOnlyDurationSecs = if (!hasBoundaryTransitions && effectiveChunks.all { it.totalFrames > 0 }) {
            effectiveChunks.sumOf { it.totalFrames }.toDouble() / exportFpsSafe
        } else {
            effectivePlan.totalDurationMs / 1000.0
        }
        val effectiveTotalDurationSecs = if (!hasBoundaryTransitions) {
            videoOnlyDurationSecs
        } else {
            finalPassCmd.durationSecs
        }
        val paddingDurationSecs = 0.0
        val needsVideoPadding = false

        val mixedAudioTempFile = File(chunkTempDir, "mixed_audio_${timestamp}.m4a")
        val audioMixCmd = if (!hasBoundaryTransitions && hasAudioOperations) {
            buildAudioMixCommand(
                operations = activeOperations,
                outputAudioPath = mixedAudioTempFile.absolutePath,
                targetDurationSecs = effectiveTotalDurationSecs,
                context = context,
                audioBitrate = audioBitrate
            )
        } else {
            null
        }
        val canStreamCopyWithAudioMux = !hasBoundaryTransitions && (audioMixCmd != null)
        val cmdBuildSecs = (System.currentTimeMillis() - cmdBuildStartMs) / 1000.0
        if (context != null) {
            val activeBoundaryTransCount = plan.boundaryTransitions.count { it.transition != null && !it.transition.type.equals("none", true) && it.transition.durationMs > 0L }
            val splitBoundaries = plan.boundaryTransitions.count { it.globalGapIndex == -1 }
            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                context,
                "prep_chunk_planning",
                chunkPlanSecs,
                "numChunks=${effectiveChunks.size} maxChunkSize=${memProfile.maxChunkSize} swThreads=${memProfile.softwareEncoderThreads} " +
                    "motionShortEdge=${memProfile.motionSpaceShortEdge.toInt()} lowMem=${memProfile.isLowMemoryTier} memTier=${memProfile.memoryTierLabel} " +
                    "splitBoundaries=$splitBoundaries unresolvedBoundaryTrans=$activeBoundaryTransCount tailPadFrames=$tailPadFrames"
            )
            com.boostlyte.vydra.utils.AppLogger.logStageTiming(
                context,
                "prep_command_build",
                cmdBuildSecs,
                "mode=chunked encoderBackend=${selectedEncoder.backend} canStreamCopy=$canStreamCopy canStreamCopyAudioMux=$canStreamCopyWithAudioMux tailPadFrames=$tailPadFrames needsPadding=$needsVideoPadding"
            )
        }

        return ChunkExportPlan(
            isChunked = true,
            chunkCommands = chunkCommands,
            finalPassCommand = finalPassCmd.command,
            chunkTempPaths = chunkTempPaths,
            totalDurationSecs = effectiveTotalDurationSecs,
            chunkDurationsSecs = exactChunkDurationsSecs,
            renderPath = overallRenderPath,
            chunkSpecs = effectiveChunks,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fps = exportFpsSafe,
            resolution = _exportResolution.value,
            density = density,
            fontFilePath = effectiveFontPath,
            operations = activeOperations,
            canStreamCopyFinalPass = canStreamCopy,
            canStreamCopyVideoWithAudioMux = canStreamCopyWithAudioMux,
            audioMixCommand = audioMixCmd,
            mixedAudioTempPath = if (audioMixCmd != null) mixedAudioTempFile.absolutePath else null,
            audioBitrate = audioBitrate,
            videoDurationSecs = videoOnlyDurationSecs,
            needsVideoPadding = needsVideoPadding,
            paddingDurationSecs = paddingDurationSecs,
            expectedAudioDurationSecs = maxAudioEndSec,
            hasAudio = hasAudioOperations
        )
    }

    /**
     * Build an FFmpeg command for a fast 3-second preview around the playhead.
     * Uses ultrafast preset and high CRF for speed over quality.
     * Skips merge operations (preview is source-only).
     *
     * @param seekPositionMs  Current playhead position in ms.
     */
    fun buildPreviewCommand(
        sourceFilePath: String,
        previewOutputPath: String,
        seekPositionMs: Long,
        fontFilePath: String? = null,
        density: Float = 1.0f,
        context: Context? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val operations = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(currentProject.operations)
            .filterNot { it is EditOperation.Merge } // Skip merge for preview

        if (operations.none { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddSubtitles }) {
            return null // No visual operations to preview
        }

        val effectiveFontPath = resolveEffectiveFontPath(fontFilePath, context)
        val videoOps = operations.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddSubtitles }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()

        val cmd = StringBuilder()
        var outputDuration: Double? = null
        if (trimOp != null) {
            val durationSecs = (trimOp.endMs - trimOp.startMs) / 1000.0
            outputDuration = durationSecs
            cmd.append("-ss ${trimOp.startMs / 1000.0} -t $durationSecs -i \"$sourceFilePath\"")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Build filter_complex for video operations
        val (videoStages, finalLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = effectiveFontPath,
            density = density,
            sourceVideoHeight = 1080
        )
        if (videoStages.isNotEmpty()) {
            val filterStr = videoStages.joinToString(";\n")
            if (context != null) {
                val scriptFile = File(context.cacheDir, "filter_script_preview_${System.nanoTime()}.txt")
                scriptFile.writeText(filterStr)
                cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
            } else {
                cmd.append(" -filter_complex \"${videoStages.joinToString(";")}\"")
            }
            cmd.append(" -map \"$finalLabel\" -map 0:a?")
        }

        // Fast preview settings
        cmd.append(" -c:v libx264 -preset ultrafast -threads 0 -pix_fmt yuv420p -b:v 1500k -c:a aac")
        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
        cmd.append(" -y \"$previewOutputPath\"")

        val finalCommand = cmd.toString()
        Log.d(TAG, "Built preview command: $finalCommand")
        return finalCommand
    }

    private fun copyContentUriToTempFile(context: Context, uri: Uri): String? {
        return try {
            val mimeType = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            val pathLower = (uri.path ?: "").lowercase()
            val ext = when {
                mimeType?.startsWith("image/png") == true || pathLower.endsWith(".png") -> ".png"
                mimeType?.startsWith("image/webp") == true || pathLower.endsWith(".webp") -> ".webp"
                mimeType?.startsWith("image/") == true || pathLower.endsWith(".jpg") || pathLower.endsWith(".jpeg") -> ".jpg"
                mimeType?.startsWith("audio/mp4") == true || mimeType?.startsWith("audio/x-m4a") == true || pathLower.endsWith(".m4a") -> ".m4a"
                mimeType?.startsWith("audio/mpeg") == true || pathLower.endsWith(".mp3") -> ".mp3"
                mimeType?.startsWith("audio/wav") == true || pathLower.endsWith(".wav") -> ".wav"
                mimeType?.startsWith("audio/") == true || pathLower.endsWith(".aac") -> ".aac"
                else -> ".mp4"
            }
            val prefix = if (ext in listOf(".jpg", ".png", ".webp")) "temp_img" else if (ext in listOf(".mp3", ".m4a", ".wav", ".aac")) "temp_audio" else "temp_video"
            val tempFile = File.createTempFile(prefix, ext, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            if (tempFile.length() > 0L) {
                tempFile.absolutePath
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e("VideoEditingViewModel", "Failed to copy content URI: ${e.message}")
            null
        }
    }

    /**
     * Accurately calculates the total expected export duration in seconds, taking into account
     * clip durations, transition overlaps (xfade), and audio track bounds.
     */
    fun getCalculatedExportDurationSec(context: Context? = null): Double {
        val currentProject = _project.value ?: return 0.0
        val mergeOp = currentProject.operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val trimOp = currentProject.operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val audioOps = currentProject.operations.filterIsInstance<EditOperation.AddBackgroundAudio>()

        val baseVideoDur = if (mergeOp != null) {
            val clipDurations = mutableListOf<Double>()
            val c0Dur = if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
            clipDurations.add(c0Dur)
            for (item in mergeOp.items) {
                clipDurations.add(item.trimmedDurationMs / 1000.0)
            }
            val transitions = currentProject.operations.filterIsInstance<EditOperation.Transition>()
            val math = TransitionTimelineCalculator.calculate(clipDurations, transitions)
            math.totalDurationSec
        } else if (trimOp != null) {
            (trimOp.endMs - trimOp.startMs) / 1000.0
        } else {
            0.0
        }

        var maxAudioEndSec = 0.0
        for (audioOp in audioOps) {
            val startSec = (audioOp.startTimeMs ?: 0L) / 1000.0
            val durSec = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
            } else if (audioOp.internalEndMs > 0L) {
                (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
            } else if (audioOp.originalDurationMs > 0L) {
                audioOp.originalDurationMs / 1000.0
            } else {
                0.0
            }
            if (startSec + durSec > maxAudioEndSec) {
                maxAudioEndSec = startSec + durSec
            }
        }

        return maxOf(baseVideoDur, maxAudioEndSec)
    }

    /**
     * Build the FFmpeg concat command for merging videos.
     * FIX: All paths are now quoted to prevent truncation on long package-name paths.
     */
    fun buildMergeCommand(
        context: Context,
        currentVideoPath: String,
        videoUrisToMerge: List<Uri>,
        listFilePath: String,
        outputFilePath: String
    ): String {
        val listContent = StringBuilder()
        listContent.append("file '$currentVideoPath'\n")

        for (uri in videoUrisToMerge) {
            val tempFilePath = copyContentUriToTempFile(context, uri)
            if (tempFilePath != null) {
                listContent.append("file '$tempFilePath'\n")
            } else {
                Log.e("VideoEditingViewModel", "Skipping URI due to copy failure: $uri")
            }
        }

        File(listFilePath).writeText(listContent.toString())

        // Both listFilePath and outputFilePath are quoted
        return "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
    }

    fun startExport() {
        updateUiState {
            it.copy(
                isExporting = true,
                exportProgress = 0,
                isExportComplete = false,
                exportedUri = null,
                exportedDisplayLocation = null,
                exportedIsAudioOnly = _exportAudioOnly.value,
                errorMessage = null
            )
        }
    }

    fun updateExportProgress(progress: Int) {
        updateUiState { it.copy(exportProgress = progress.coerceIn(0, 100)) }
    }

    fun completeExport(savedUri: String?, displayLocation: String? = null) {
        updateUiState {
            it.copy(
                isExporting = false,
                exportProgress = 100,
                isExportComplete = true,
                exportedUri = savedUri,
                exportedDisplayLocation = displayLocation,
                exportedIsAudioOnly = _exportAudioOnly.value
            )
        }
    }

    fun dismissExportCompletion() {
        updateUiState {
            it.copy(
                isExportComplete = false,
                exportedUri = null,
                exportedDisplayLocation = null
            )
        }
    }

    fun finishExport() {
        updateUiState {
            it.copy(
                isExporting = false,
                exportProgress = 100,
                isExportComplete = false,
                exportedUri = null,
                exportedDisplayLocation = null
            )
        }
    }

    fun exportError(error: String) {
        updateUiState {
            it.copy(
                isExporting = false,
                isExportComplete = false,
                exportedUri = null,
                errorMessage = error
            )
        }
    }

    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
    }

    fun saveRecipe(projectName: String): EditRecipe? =
        _project.value?.let { EditRecipe.fromVideoProject(projectName, it) }

    fun loadRecipe(recipe: EditRecipe) {
        val restored = recipe.toVideoProject()
        val normalized = restored.copy(
            operations = com.boostlyte.vydra.captions.CaptionCueBuilder.normalizeCaptionOperations(restored.operations)
        )
        _project.value = normalized
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(pendingOperationCount = normalized.operations.size, canUndo = false) }
    }

    private fun updateUiState(updater: (VideoEditingUiState) -> VideoEditingUiState) {
        _uiState.update(updater)
        if (_undoStack.value.isNotEmpty()) {
            _hasUnsavedEdits.value = true
        }
    }

    private fun getWrappedSubtitleLines(text: String, fontSize: Int = 22, density: Float = 2.75f): List<String> {
        val effectiveDensity = if (density > 0.5f) density else 2.75f
        val avgCharWidth = 0.52f * fontSize * effectiveDensity
        val maxCharsPerLine = (918f / avgCharWidth).toInt().coerceIn(10, 60)

        val rawLines = text.split("\n")
        val resultLines = mutableListOf<String>()
        for (rawLine in rawLines) {
            val trimmedRaw = rawLine.trim()
            if (trimmedRaw.length <= maxCharsPerLine) {
                if (trimmedRaw.isNotEmpty()) resultLines.add(trimmedRaw)
            } else {
                val words = trimmedRaw.split(Regex("\\s+"))
                var currentLine = StringBuilder()
                for (word in words) {
                    if (currentLine.isEmpty()) {
                        currentLine.append(word)
                    } else {
                        if (currentLine.length + 1 + word.length <= maxCharsPerLine) {
                            currentLine.append(" ").append(word)
                        } else {
                            resultLines.add(currentLine.toString().trim())
                            currentLine = StringBuilder(word)
                        }
                    }
                }
                if (currentLine.isNotEmpty()) {
                    resultLines.add(currentLine.toString().trim())
                }
            }
        }
        return if (resultLines.isEmpty()) listOf(text) else resultLines
    }
}