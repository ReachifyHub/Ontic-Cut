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
        _project.value = project
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _hasUnsavedEdits.value = false
        updateUiState { it.copy(canUndo = false, pendingOperationCount = project.getOperationCount()) }
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
    private fun buildDrawtextExpr(op: EditOperation.AddText, fontFilePath: String?, timeOffsetMs: Long = 0L): String {
        val fontToUse = op.fontPath ?: fontFilePath

        val isAutoCaption = op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID

        // Use WYSIWYG fractional coordinates if available, else fall back to explicit coordinates
        val (posX, posY) = if (op.positionKeyframes.isNotEmpty()) {
            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L, timeOffsetMs = timeOffsetMs)
            Pair("'(w*($xExpr))-(tw/2)'", "'(h*($yExpr))-(th/2)'")
        } else if (op.hasCustomPosition()) {
            Pair("'(w*${op.relativeX})-(tw/2)'", "'(h*${op.relativeY})-(th/2)'")
        } else if (isAutoCaption) {
            Pair("(w-tw)/2", "h-th-40")
        } else {
            when (op.position) {
                TextPosition.TOP_LEFT -> Pair("24", "24")
                TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> Pair("(w-tw)/2", "24")
                TextPosition.TOP_RIGHT -> Pair("w-tw-24", "24")
                TextPosition.CENTER_LEFT -> Pair("24", "(h-th)/2")
                TextPosition.CENTER -> Pair("(w-tw)/2", "(h-th)/2")
                TextPosition.CENTER_RIGHT -> Pair("w-tw-24", "(h-th)/2")
                TextPosition.BOTTOM_LEFT -> Pair("24", "h-th-40")
                TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> Pair("(w-tw)/2", "h-th-40")
                TextPosition.BOTTOM_RIGHT -> Pair("w-tw-24", "h-th-40")
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
        val boxBorderW = if (isAutoCaption) 4 else 0

        val (shadowColor, shadowOffset) = if (op.borderThickness > 0) {
            val resScale = (_exportResolution.value.toFloat() / 720f).coerceIn(0.5f, 3.0f)
            val offset = maxOf(1, Math.round(op.borderThickness * 0.8f * resScale))
            Pair("black@0.75", offset)
        } else Pair(null, 0)

        val fontColor = formatColorForFFmpeg(op.color)

        return com.boostlyte.vydra.utils.FFmpegFilterUtils.buildValidatedDrawtextFilter(
            text = op.text,
            fontFilePath = fontToUse,
            fontSize = op.fontSize,
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
            lineSpacing = op.lineSpacing.toInt()
        )
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
        timeVar: String = "T"
    ): String {
        val dx = "(X - ($cx))"
        val dy = "(Y - ($cy))"
        val rx = "($dx * $cosA + $dy * $sinA)"
        val ry = "(-$dx * $sinA + $dy * $cosA)"

        val featherExpr = if (mc.featherKeyframes.isNotEmpty()) {
            buildFFmpegInterpolationExpr(mc.featherKeyframes, false, mc.feather, startTimeMs, timeVar)
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
        val subtitleCueTexts = subtitleOps.flatMap { it.cues.map { cue -> cue.text.trim().lowercase() } }.toSet()
        val seenAutoCaptionTimes = mutableSetOf<Pair<Long?, Long?>>()
        val overlayOps = operations.filterNot { op ->
            if (op is EditOperation.Crop) return@filterNot true
            if (op is EditOperation.AddText) {
                if (op.text.isBlank()) return@filterNot true
                val cleanText = op.text.trim().lowercase()

                // If explicit AddSubtitles are active, suppress ALL auto-caption AddText cards and matching text
                if (hasExplicitSubtitles) {
                    if (op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                        return@filterNot true
                    }
                    if (cleanText in subtitleCueTexts) {
                        return@filterNot true
                    }
                }

                if (op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                    val key = Pair(op.startTimeMs, op.endTimeMs)
                    if (seenAutoCaptionTimes.contains(key)) {
                        return@filterNot true
                    }
                    seenAutoCaptionTimes.add(key)
                }

                // Suppress stray unpositioned script / transcript text at default top position
                if (!op.hasCustomPosition() && op.positionKeyframes.isEmpty()) {
                    if (op.position == TextPosition.TOP_CENTER || op.position == TextPosition.CENTER_TOP || op.position == TextPosition.TOP_LEFT) {
                        if (op.startTimeMs == null && op.endTimeMs == null) {
                            return@filterNot true
                        }
                        if (op.groupId == com.boostlyte.vydra.captions.CaptionCueBuilder.CAPTION_GROUP_ID) {
                            return@filterNot true
                        }
                    }
                }
            }
            false
        }
        val cropOps = operations.filterIsInstance<EditOperation.Crop>()

        for (op in overlayOps) {
            when (op) {
                is EditOperation.AddText -> {
                    val filterExpr = buildDrawtextExpr(op, fontFilePath, timeOffsetMs)
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
                    val scaledFontSize = (op.fontSize * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(16)
                    val boxBorderW = (8f * effectiveDensity * (refHeight / 1080f)).toInt().coerceAtLeast(4)
                    val marginY = (24f * effectiveDensity * (refHeight / 1080f)).toInt()
                    val lineSpacing = (scaledFontSize * 1.25f).toInt()
                    
                    val fontToUse = if (!op.fontPath.isNullOrBlank() && java.io.File(op.fontPath).exists()) op.fontPath else fontFilePath
                    val fontColor = formatColorForFFmpeg(op.color)
                    val boxColor = if (op.backgroundColor != "none" && op.backgroundColor.isNotBlank()) {
                        formatColorForFFmpeg(op.backgroundColor)
                    } else {
                        "black@0.5"
                    }

                    for (cue in op.cues) {
                        val cleanText = cue.text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
                        val (posX, posY) = if (op.hasCustomPosition()) {
                            Pair("'(w*${op.relativeX})-(tw/2)'", "'(h*${op.relativeY})-(th/2)'")
                        } else {
                            when (op.position) {
                                TextPosition.TOP_LEFT -> Pair("24", "24")
                                TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> Pair("(w-tw)/2", "24")
                                TextPosition.TOP_RIGHT -> Pair("w-tw-24", "24")
                                TextPosition.CENTER_LEFT -> Pair("24", "(h-th)/2")
                                TextPosition.CENTER -> Pair("(w-tw)/2", "(h-th)/2")
                                TextPosition.CENTER_RIGHT -> Pair("w-tw-24", "(h-th)/2")
                                TextPosition.BOTTOM_LEFT -> Pair("24", "h-$marginY-th")
                                TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> Pair("(w-tw)/2", "h-$marginY-th")
                                TextPosition.BOTTOM_RIGHT -> Pair("w-tw-24", "h-$marginY-th")
                            }
                        }

                        val cueStartLocal = (cue.startTimeMs - timeOffsetMs).coerceAtLeast(0L)
                        val cueEndLocal = (cue.endTimeMs - timeOffsetMs).coerceAtLeast(0L)

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

        if (currentProject.operations.isEmpty() && currentProject.canvasAspectRatio == null) {
            return "-y -i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }

        val operations = operationsOverride ?: currentProject.operations

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

        val effectiveFontPath = if (!fontFilePath.isNullOrBlank() && java.io.File(fontFilePath).exists()) {
            fontFilePath
        } else if (context != null) {
            try {
                val f = java.io.File(context.cacheDir, "Roboto-Regular.ttf")
                if (!f.exists() || f.length() == 0L) {
                    context.assets.open("fonts/Roboto-Regular.ttf").use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                f.absolutePath
            } catch (e: Exception) {
                fontFilePath
            }
        } else {
            fontFilePath
        }

        /** Resolve a URI to a real filesystem path FFmpeg can read.
         *  content:// URIs are copied to cacheDir; file:// or plain paths are used directly. */
        fun resolveUriToPath(uri: android.net.Uri): String? = resolveUriToPath(context, uri)

        // 1. Source Video Input (Index 0)
        var outputDuration: Double? = null
        val reverseOp = operations.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val speedOp = operations.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val finalProxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        
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
            val isMainImage = sourceFilePath.endsWith(".png", ignoreCase = true) ||
                              sourceFilePath.endsWith(".jpg", ignoreCase = true) ||
                              sourceFilePath.endsWith(".jpeg", ignoreCase = true) ||
                              sourceFilePath.endsWith(".webp", ignoreCase = true)
            if (isMainImage) {
                val duration = if (trimOp != null) (trimOp.endMs - trimOp.startMs) / 1000.0 else 5.0
                if (mergeOp == null) {
                    outputDuration = duration
                }
                cmd.append(" -framerate $exportFpsVal -loop 1 -t $duration -i \"$sourceFilePath\"")
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
        if (mergeOp != null) {
            for (item in mergeOp.items) {
                if (item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(item.proxyUri) ?: item.proxyUri.path ?: item.proxyUri.toString()
                    val duration = item.trimmedDurationMs / 1000.0
                    cmd.append(" -t $duration -i \"$proxyPath\"")
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
                    
                    val isImage = item.isImage || 
                                  videoPath.endsWith(".png", ignoreCase = true) || 
                                  videoPath.endsWith(".jpg", ignoreCase = true) || 
                                  videoPath.endsWith(".jpeg", ignoreCase = true) ||
                                  videoPath.endsWith(".webp", ignoreCase = true)
                    
                    if (isImage) {
                        cmd.append(" -framerate $exportFpsVal -loop 1 -t $itemDurSec -i \"$videoPath\"")
                    } else {
                        cmd.append(" -ss $startSecs -t $itemDurSec -i \"$videoPath\"")
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
                    val isImage = path.endsWith(".png", ignoreCase = true) || 
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

                val motionW = (((mainWidth * 1.15).toInt()) / 2) * 2
                val motionH = (((mainHeight * 1.15).toInt()) / 2) * 2
                val coverScale = "scale=w=$motionW:h=$motionH:force_original_aspect_ratio=increase"
                val coverCrop = "crop=$motionW:$motionH:(in_w-$motionW)/2:(in_h-$motionH)/2"

                val motionFilter = when (clipMotionType) {
                    "zoom_in" -> "$coverScale,$coverCrop,zoompan=z='min(1.0+0.12*(on/($exportFpsVal*$d))\\,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${mainWidth}x${mainHeight}:fps=$exportFpsVal"
                    "zoom_out" -> "$coverScale,$coverCrop,zoompan=z='max(1.12-0.12*(on/($exportFpsVal*$d))\\,1.0)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${mainWidth}x${mainHeight}:fps=$exportFpsVal"
                    "pan_left" -> "$coverScale,$coverCrop,crop=$mainWidth:$mainHeight:x='(in_w-out_w)*(1-(t/$d))':y='(in_h-out_h)/2'"
                    "pan_right" -> "$coverScale,$coverCrop,crop=$mainWidth:$mainHeight:x='(in_w-out_w)*(t/$d)':y='(in_h-out_h)/2'"
                    "pan_up" -> "$coverScale,$coverCrop,crop=$mainWidth:$mainHeight:x='(in_w-out_w)/2':y='(in_h-out_h)*(1-(t/$d))'"
                    "pan_down" -> "$coverScale,$coverCrop,crop=$mainWidth:$mainHeight:x='(in_w-out_w)/2':y='(in_h-out_h)*(t/$d)'"
                    "drift_up_right" -> "$coverScale,$coverCrop,crop=$mainWidth:$mainHeight:x='(in_w-out_w)*(t/$d)':y='(in_h-out_h)*(1-(t/$d))'"
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
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2[raw_stage$i]")
                } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                    val bgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight:(in_w-$mainWidth)/2:(in_h-$mainHeight)/2"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[$bgImageIndex:v]$bgScale[bg$i]")
                    filterParts.add("[pre$i]$fgScale[fg$i]")
                    // Use shortest=1 in case the loop goes on forever, but we specified -loop 1 which creates an infinite stream for image
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1[raw_stage$i]")
                } else {
                    // Default COLOR
                    val color = bgOp?.colorHex ?: "black"
                    val padColor = if (color.startsWith("#")) formatColorForFFmpeg(color) else "black"
                    // Create a solid color background of mainWidth x mainHeight
                    filterParts.add("color=c=$padColor:s=${mainWidth}x${mainHeight}:r=${_exportFps.value}[bg$i]")
                    filterParts.add("[pre$i]scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1[raw_stage$i]")
                }

                // Explicit normalization stage for clip i: guarantees exact resolution, padding, SAR, fps, and pixel format before xfade/transitions
                filterParts.add("[raw_stage$i]settb=1/${_exportFps.value},setpts=N,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${_exportFps.value},format=yuv420p[norm$i]")

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
            data class StreamInfo(val vLabel: String, val aLabel: String, val durationSec: Double)

            val pendingClipIndices = mutableListOf(0) // start with clip 0

            var currentStream: StreamInfo? = null
            var transitionLabelCounter = 0
            var concatLabelCounter = 0

            fun flushPendingGroup(clipIndices: List<Int>): StreamInfo {
                return if (clipIndices.size == 1) {
                    val i = clipIndices[0]
                    StreamInfo("[norm$i]", "[anorm$i]", durationsArray[i])
                } else {
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    val inputs = clipIndices.joinToString("") { "[norm$it][anorm$it]" }
                    // concat then ensure consistent format/fps
                    filterParts.add("${inputs}concat=n=${clipIndices.size}:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}format=yuv420p,fps=30[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    val totalDur = clipIndices.sumOf { durationsArray[it] }
                    concatLabelCounter++
                    // use the normalized labels for subsequent processing
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", totalDur)
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
                            filterParts.add("${vOut}format=yuv420p,fps=30[norm${concatLabelCounter}v]")
                            filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                            concatLabelCounter++
                            StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + leftStream.durationSec)
                        }
                    }

                    val rawTransDuration = transOp.durationMs / 1000.0
                    val maxSafeDuration = minOf(baseStream.durationSec - 0.05, durationsArray[gapIdx + 1] - 0.05).coerceAtLeast(0.1)
                    val transDuration = rawTransDuration.coerceIn(0.1, maxSafeDuration)
                    val offset = (baseStream.durationSec - transDuration).coerceAtLeast(0.0)

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

                    val formattedDuration = String.format(java.util.Locale.US, "%.3f", transDuration)
                    val formattedOffset = String.format(java.util.Locale.US, "%.3f", offset)

                    filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedTransType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                    filterParts.add("${baseStream.aLabel}${nextALabel}acrossfade=d=${formattedDuration}:c1=tri:c2=tri${rawXfaOut}")
                    filterParts.add("${rawXfvOut}settb=1/30,setpts=N,fps=30,format=yuv420p${normXfvOut}")
                    filterParts.add("${rawXfaOut}asetpts=PTS-STARTPTS,aresample=async=1000${normXfaOut}")

                    val newDuration = offset + durationsArray[gapIdx + 1]
                    currentStream = StreamInfo(normXfvOut, normXfaOut, newDuration)
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
                    filterParts.add("${vOut}settb=1/30,setpts=N,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    concatLabelCounter++
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + remainingStream.durationSec)
                }
                else -> StreamInfo("[norm0]", "[anorm0]", durationsArray[0]) // fallback single clip
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
                com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr)
            }
            if (context != null && (filterComplexStr.length > 2000 || (mergeOp != null && mergeOp.items.size >= 10))) {
                val scriptFile = File(context.cacheDir, "filter_script_${System.currentTimeMillis()}.txt")
                scriptFile.writeText(filterComplexStr)
                com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr, scriptFile)
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
                
                val bitrate = when (_exportResolution.value) {
                    2160 -> "30M"
                    1440 -> "16M"
                    1080 -> "8M"
                    720 -> "5M"
                    480 -> "2M"
                    else -> "1M"
                }
                val videoCodec = encoderOverride ?: run {
                    val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(context, targetWidth, targetHeight, _exportFps.value, bitrate)
                    enc.ffmpegVideoCodecArgs
                }
                cmd.append(" $videoCodec")
                
                if (!audioMuted) {
                    cmd.append(" -c:a aac")
                }
            }

            if (outputDuration != null) {
                val formattedDur = String.format(java.util.Locale.US, "%.3f", outputDuration).trimEnd('0').trimEnd('.')
                cmd.append(" -t $formattedDur")
            }
            cmd.append(" \"$outputFilePath\"")

            val finalCommand = cmd.toString()
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
                com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr)
            }
            if (context != null && filterComplexStr.length > 2000) {
                val scriptFile = File(context.cacheDir, "filter_script_${System.currentTimeMillis()}.txt")
                scriptFile.writeText(filterComplexStr)
                com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr, scriptFile)
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
            val bitrate = when (_exportResolution.value) {
                2160 -> "30M"
                1440 -> "16M"
                1080 -> "8M"
                720 -> "5M"
                480 -> "2M"
                else -> "1M"
            }
            val videoCodec = encoderOverride ?: run {
                val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(context, targetWidth, targetHeight, _exportFps.value, bitrate)
                enc.ffmpegVideoCodecArgs
            }
            // If there are no video filters, we still need to apply the scale and fps output params
            if (!hasVideoFilters) {
                cmd.append(" $videoCodec -vf scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2:color=black -r ${_exportFps.value}")
            } else {
                cmd.append(" $videoCodec")
            }
            
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a aac")
            }
        }

        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
        cmd.append(" \"$outputFilePath\"")

        val finalCommand = cmd.toString()
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
        encoderOverride: String? = null
    ): ChunkCommand {
        val cmd = StringBuilder("-y")
        var inputIndex = 0

        // 1. Inputs for this chunk's clips
        for ((localIdx, clip) in chunkSpec.clips.withIndex()) {
            if (clip.globalIndex == 0) {
                val origUri = clip.item.uri
                val origUriStr = origUri.toString()
                val scheme = origUri.scheme ?: "file"
                val resolvedPath = sourceFilePath
                val f = File(resolvedPath)
                val exists = f.exists()
                val size = if (exists) f.length() else -1L
                Log.i(TAG, "ChunkClip: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex} (global 0), origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', exists=$exists, size=$size bytes")

                val isMainImage = sourceFilePath.endsWith(".png", ignoreCase = true) ||
                                  sourceFilePath.endsWith(".jpg", ignoreCase = true) ||
                                  sourceFilePath.endsWith(".jpeg", ignoreCase = true) ||
                                  sourceFilePath.endsWith(".webp", ignoreCase = true)
                val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
                val duration = if (clip.item.trimmedDurationMs > 0L) {
                    clip.item.trimmedDurationMs / 1000.0
                } else if (trimOp != null) {
                    (trimOp.endMs - trimOp.startMs) / 1000.0
                } else {
                    5.0
                }
                if (clip.item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(context, clip.item.proxyUri) ?: clip.item.proxyUri.path ?: clip.item.proxyUri.toString()
                    cmd.append(" -t $duration -i \"$proxyPath\"")
                } else if (isMainImage) {
                    cmd.append(" -framerate ${_exportFps.value} -loop 1 -t $duration -i \"$sourceFilePath\"")
                } else if (trimOp != null) {
                    val startSecs = trimOp.startMs / 1000.0
                    cmd.append(" -ss $startSecs -t $duration -i \"$sourceFilePath\"")
                } else {
                    cmd.append(" -i \"$sourceFilePath\"")
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
                Log.i(TAG, "ChunkClip: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex} (global ${clip.globalIndex}), origUri='$origUriStr', scheme='$scheme', resolvedPath='$resolvedPath', exists=$exists, size=$size bytes")
                if (!exists || size <= 0L) {
                    Log.e(TAG, "ChunkClip missing or empty: chunk=${chunkSpec.chunkIndex}, clip=${clip.localIndex}, path='$resolvedPath', uri='$origUriStr'")
                }

                if (item.proxyUri != null) {
                    val proxyPath = resolveUriToPath(context, item.proxyUri) ?: item.proxyUri.path ?: item.proxyUri.toString()
                    val duration = item.trimmedDurationMs / 1000.0
                    cmd.append(" -t $duration -i \"$proxyPath\"")
                } else {
                    val videoPath = resolvedPath
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
                    val isImage = item.isImage ||
                                  videoPath.endsWith(".png", ignoreCase = true) ||
                                  videoPath.endsWith(".jpg", ignoreCase = true) ||
                                  videoPath.endsWith(".jpeg", ignoreCase = true) ||
                                  videoPath.endsWith(".webp", ignoreCase = true)
                    if (isImage) {
                        cmd.append(" -framerate ${_exportFps.value} -loop 1 -t $itemDurSec -i \"$videoPath\"")
                    } else {
                        cmd.append(" -ss $startSecs -t $itemDurSec -i \"$videoPath\"")
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

        for ((localIdx, clip) in chunkSpec.clips.withIndex()) {
            val d = if (clip.item.trimmedDurationMs > 0L) {
                clip.item.trimmedDurationMs / 1000.0
            } else if (clip.item.trimEndMs > clip.item.trimStartMs) {
                (clip.item.trimEndMs - clip.item.trimStartMs) / 1000.0
            } else if (clip.item.durationMs > 0L) {
                clip.item.durationMs / 1000.0
            } else {
                5.0
            }
            durationsArray[localIdx] = d

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
            filterParts.add("[$localIdx:v]$preFilters[pre$localIdx]")

            val motionOp = operations.filterIsInstance<EditOperation.MotionEffect>().find { it.index == clip.globalIndex }
            val clipMotionType = motionOp?.motionType ?: clip.item.motionEffect
            val safeD = d.coerceAtLeast(0.1)

            val motionW = (((targetWidth * 1.15).toInt()) / 2) * 2
            val motionH = (((targetHeight * 1.15).toInt()) / 2) * 2
            val coverScale = "scale=w=$motionW:h=$motionH:force_original_aspect_ratio=increase"
            val coverCrop = "crop=$motionW:$motionH:(in_w-$motionW)/2:(in_h-$motionH)/2"

            val motionFilter = when (clipMotionType) {
                "zoom_in" -> "$coverScale,$coverCrop,zoompan=z='min(1.0+0.12*(on/(${_exportFps.value}*$safeD))\\,1.15)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${targetWidth}x${targetHeight}:fps=${_exportFps.value}"
                "zoom_out" -> "$coverScale,$coverCrop,zoompan=z='max(1.12-0.12*(on/(${_exportFps.value}*$safeD))\\,1.0)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=${targetWidth}x${targetHeight}:fps=${_exportFps.value}"
                "pan_left" -> "$coverScale,$coverCrop,crop=$targetWidth:$targetHeight:x='(in_w-out_w)*(1-(t/$safeD))':y='(in_h-out_h)/2'"
                "pan_right" -> "$coverScale,$coverCrop,crop=$targetWidth:$targetHeight:x='(in_w-out_w)*(t/$safeD)':y='(in_h-out_h)/2'"
                "pan_up" -> "$coverScale,$coverCrop,crop=$targetWidth:$targetHeight:x='(in_w-out_w)/2':y='(in_h-out_h)*(1-(t/$safeD))'"
                "pan_down" -> "$coverScale,$coverCrop,crop=$targetWidth:$targetHeight:x='(in_w-out_w)/2':y='(in_h-out_h)*(t/$safeD)'"
                "drift_up_right" -> "$coverScale,$coverCrop,crop=$targetWidth:$targetHeight:x='(in_w-out_w)*(t/$safeD)':y='(in_h-out_h)*(1-(t/$safeD))'"
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
                filterParts.add("[bg$localIdx][fg$localIdx]overlay=(W-w)/2:(H-h)/2[raw_stage$localIdx]")
            } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                val bgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=increase,crop=$targetWidth:$targetHeight:(in_w-$targetWidth)/2:(in_h-$targetHeight)/2"
                val fgScale = "scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease"
                filterParts.add("[$bgImageIndex:v]$bgScale[bg$localIdx]")
                filterParts.add("[pre$localIdx]$fgScale[fg$localIdx]")
                filterParts.add("[bg$localIdx][fg$localIdx]overlay=(W-w)/2:(H-h)/2:shortest=1[raw_stage$localIdx]")
            } else {
                val color = bgOp?.colorHex ?: "black"
                val padColor = if (color.startsWith("#")) formatColorForFFmpeg(color) else "black"
                filterParts.add("color=c=$padColor:s=${targetWidth}x${targetHeight}:r=${_exportFps.value}[bg$localIdx]")
                filterParts.add("[pre$localIdx]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease[fg$localIdx]")
                filterParts.add("[bg$localIdx][fg$localIdx]overlay=(W-w)/2:(H-h)/2:shortest=1[raw_stage$localIdx]")
            }

            filterParts.add("[raw_stage$localIdx]settb=1/${_exportFps.value},setpts=N,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${_exportFps.value},format=yuv420p[norm$localIdx]")
        }

        // Chain internal transitions for this chunk
        data class LocalStreamInfo(val vLabel: String, val durationSec: Double)
        val pendingClips = mutableListOf(0)
        var currentStream: LocalStreamInfo? = null
        var transitionCounter = 0
        var concatCounter = 0

        fun flushLocalPending(clips: List<Int>): LocalStreamInfo {
            return if (clips.size == 1) {
                val i = clips[0]
                LocalStreamInfo("[norm$i]", durationsArray[i])
            } else {
                val vOut = "[ccat${concatCounter}v]"
                val inputs = clips.joinToString("") { "[norm$it]" }
                filterParts.add("${inputs}concat=n=${clips.size}:v=1:a=0${vOut}")
                filterParts.add("${vOut}settb=1/${_exportFps.value},setpts=N,fps=${_exportFps.value},format=yuv420p[norm${concatCounter}v]")
                val totalDur = clips.sumOf { durationsArray[it] }
                concatCounter++
                LocalStreamInfo("[norm${concatCounter - 1}v]", totalDur)
            }
        }

        for (gapIdx in 0 until inputCount - 1) {
            val transOp = chunkSpec.internalTransitions.find { it.index == gapIdx }
            if (transOp == null || transOp.type == "none") {
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
                        filterParts.add("${vOut}settb=1/${_exportFps.value},setpts=N,fps=${_exportFps.value},format=yuv420p[norm${concatCounter}v]")
                        concatCounter++
                        LocalStreamInfo("[norm${concatCounter - 1}v]", currentStream.durationSec + leftStream.durationSec)
                    }
                }

                val rawTransDuration = transOp.durationMs / 1000.0
                val maxSafeDuration = minOf(baseStream.durationSec - 0.05, durationsArray[gapIdx + 1] - 0.05).coerceAtLeast(0.1)
                val transDuration = rawTransDuration.coerceIn(0.1, maxSafeDuration)
                val offset = (baseStream.durationSec - transDuration).coerceAtLeast(0.0)

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

                val formattedDuration = String.format(java.util.Locale.US, "%.3f", transDuration)
                val formattedOffset = String.format(java.util.Locale.US, "%.3f", offset)

                filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${sanitizedType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                filterParts.add("${rawXfvOut}settb=1/${_exportFps.value},setpts=N,fps=${_exportFps.value},format=yuv420p${normXfvOut}")

                val newDuration = offset + durationsArray[gapIdx + 1]
                currentStream = LocalStreamInfo(normXfvOut, newDuration)
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
                filterParts.add("${vOut}settb=1/${_exportFps.value},setpts=N,scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${_exportFps.value},format=yuv420p[norm${concatCounter}v]")
                concatCounter++
                LocalStreamInfo("[norm${concatCounter - 1}v]", currentStream.durationSec + remainingStream.durationSec)
            }
            else -> LocalStreamInfo("[norm0]", durationsArray[0])
        }

        var currentVideoLabel = finalStream.vLabel

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

        val overlayOpsToApply = clampedTextOps + clampedImageOps + clampedSubtitleOps
        if (overlayOpsToApply.isNotEmpty()) {
            val (overlayStages, tempLabel) = buildVideoFilterStages(
                operations = overlayOpsToApply,
                fontFilePath = fontFilePath,
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
        filterParts.add("${currentVideoLabel}scale=$targetWidth:$targetHeight,fps=${_exportFps.value},format=yuv420p$finalVideoLabel")

        val filterComplexStr = filterParts.joinToString(";\n")
        var scriptFile: File? = null
        if (context != null && filterComplexStr.length > 2000) {
            scriptFile = File(context.cacheDir, "filter_script_chunk_${chunkSpec.chunkIndex}_${System.currentTimeMillis()}.txt")
            scriptFile.writeText(filterComplexStr)
            com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr, scriptFile)
            cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
        } else {
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
        }

        val bitrate = when (_exportResolution.value) {
            2160 -> "30M"
            1440 -> "16M"
            1080 -> "8M"
            720 -> "5M"
            480 -> "2M"
            else -> "1M"
        }
        val videoCodec = encoderOverride ?: run {
            val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(context, targetWidth, targetHeight, _exportFps.value, bitrate)
            enc.ffmpegVideoCodecArgs
        }

        cmd.append(" -map \"$finalVideoLabel\" -an $videoCodec")
        val formattedDur = String.format(java.util.Locale.US, "%.3f", chunkSpec.durationMs / 1000.0).trimEnd('0').trimEnd('.')
        cmd.append(" -t $formattedDur \"$chunkOutputPath\"")

        val commandStr = cmd.toString()
        Log.d(TAG, "Built chunk ${chunkSpec.chunkIndex} command: $commandStr")
        return ChunkCommand(
            chunkIndex = chunkSpec.chunkIndex,
            command = commandStr,
            outputPath = chunkOutputPath,
            durationSecs = chunkSpec.durationMs / 1000.0,
            scriptFilePath = scriptFile?.absolutePath
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
        var currentV = "[0:v]"
        var currentStreamDurationSec = chunkPlan.chunks[0].durationMs / 1000.0

        for (bIdx in 0 until numChunks - 1) {
            val nextChunkInput = "[${bIdx + 1}:v]"
            val nextChunkDurationSec = chunkPlan.chunks[bIdx + 1].durationMs / 1000.0
            val boundaryTrans = chunkPlan.boundaryTransitions.find { it.boundaryIndex == bIdx }?.transition

            if (boundaryTrans != null && boundaryTrans.type != "none") {
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

                val formattedDuration = String.format(java.util.Locale.US, "%.3f", transDuration)
                val formattedOffset = String.format(java.util.Locale.US, "%.3f", offset)

                filterParts.add("${currentV}${nextChunkInput}xfade=transition=${sanitizedType}:duration=${formattedDuration}:offset=${formattedOffset}${rawXfvOut}")
                filterParts.add("${rawXfvOut}settb=1/${_exportFps.value},setpts=N,fps=${_exportFps.value},format=yuv420p${normXfvOut}")

                currentV = normXfvOut
                currentStreamDurationSec = offset + nextChunkDurationSec
            } else {
                val rawCcatOut = "[raw_ccat$bIdx]"
                val normCcatOut = "[norm_ccat$bIdx]"

                filterParts.add("${currentV}${nextChunkInput}concat=n=2:v=1:a=0${rawCcatOut}")
                filterParts.add("${rawCcatOut}settb=1/${_exportFps.value},setpts=N,fps=${_exportFps.value},format=yuv420p${normCcatOut}")

                currentV = normCcatOut
                currentStreamDurationSec += nextChunkDurationSec
            }
        }

        var currentAudioLabel: String? = null
        var maxAudioEndSec = 0.0
        if (audioInputIndices.isNotEmpty()) {
            val mixInputLabels = mutableListOf<String>()
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
                currentAudioLabel = "[outa]"
            } else {
                val allBg = mixInputLabels.joinToString("")
                filterParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest:normalize=0,alimiter=limit=0.95[outa]")
                currentAudioLabel = "[outa]"
            }
        }

        // If audio extends past total video duration, pad video with frozen last frame (tpad)
        val finalEffectiveDurationSec = if (maxAudioEndSec > currentStreamDurationSec && maxAudioEndSec > 0.0) {
            val padSec = maxAudioEndSec - currentStreamDurationSec
            filterParts.add("${currentV}tpad=stop_mode=clone:stop_duration=$padSec[pad_v]")
            currentV = "[pad_v]"
            maxAudioEndSec
        } else {
            currentStreamDurationSec
        }

        val filterComplexStr = filterParts.joinToString(";\n")
        var scriptFile: File? = null
        if (context != null && filterComplexStr.length > 2000) {
            scriptFile = File(context.cacheDir, "filter_script_final_${System.currentTimeMillis()}.txt")
            scriptFile.writeText(filterComplexStr)
            com.boostlyte.vydra.utils.AppLogger.saveFilterComplexScriptLog(context, filterComplexStr, scriptFile)
            cmd.append(" -filter_complex_script \"${scriptFile.absolutePath}\"")
        } else {
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
        }

        val bitrate = when (_exportResolution.value) {
            2160 -> "30M"
            1440 -> "16M"
            1080 -> "8M"
            720 -> "5M"
            480 -> "2M"
            else -> "1M"
        }
        val videoCodec = encoderOverride ?: run {
            val enc = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(context, targetWidth, targetHeight, _exportFps.value, bitrate)
            enc.ffmpegVideoCodecArgs
        }

        cmd.append(" -map \"$currentV\"")
        if (currentAudioLabel != null) {
            cmd.append(" -map \"$currentAudioLabel\" -c:a aac")
        } else {
            cmd.append(" -an")
        }
        cmd.append(" $videoCodec")

        val formattedTotalDur = String.format(java.util.Locale.US, "%.3f", finalEffectiveDurationSec).trimEnd('0').trimEnd('.')
        cmd.append(" -t $formattedTotalDur \"$outputFilePath\"")

        val commandStr = cmd.toString()
        Log.d(TAG, "Built final pass command: $commandStr")
        return ChunkCommand(
            chunkIndex = -1,
            command = commandStr,
            outputPath = outputFilePath,
            durationSecs = finalEffectiveDurationSec,
            scriptFilePath = scriptFile?.absolutePath
        )
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
        val activeOperations = operationsOverride ?: currentProject.operations


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

        val targetCanvasRatio = currentProject.canvasAspectRatio ?: com.boostlyte.vydra.models.CanvasAspectRatio.fromDimensions(sourceVideoWidth, sourceVideoHeight)
        val (targetWidth, targetHeight) = targetCanvasRatio.getTargetDimensions(_exportResolution.value)

        val chunkTempDir = context?.cacheDir ?: File("/tmp")
        val timestamp = System.currentTimeMillis()

        val bitrate = when (_exportResolution.value) {
            2160 -> "30M"
            1440 -> "16M"
            1080 -> "8M"
            720 -> "5M"
            480 -> "2M"
            else -> "1M"
        }

        // Select capability-aware encoder path consistently for the entire export
        val selectedEncoder = com.boostlyte.vydra.export.EncoderSelector.getEffectiveEncoder(
            context = context,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fps = _exportFps.value,
            bitrate = bitrate
        )
        val overallRenderPath = "legacy"

        // TRIGGER CHECK: If <= 16, execute single-pass export using legacy FFmpeg directly (no GL intermediates)
        if (allSequenceItems.size <= ChunkPlanner.CHUNK_TRIGGER_THRESHOLD) {
            val singlePassCmd = buildConsolidatedFFmpegCommand(
                sourceFilePath = sourceFilePath,
                outputFilePath = outputFilePath,
                fontFilePath = fontFilePath,
                context = context,
                operationsOverride = activeOperations,
                encoderOverride = selectedEncoder.ffmpegVideoCodecArgs
            ) ?: return null

            return ChunkExportPlan(
                isChunked = false,
                singlePassCommand = singlePassCmd,
                renderPath = overallRenderPath,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                fps = _exportFps.value,
                resolution = _exportResolution.value,
                density = context?.resources?.displayMetrics?.density ?: 1.0f,
                fontFilePath = fontFilePath,
                operations = activeOperations
            )
        }

        // Large sequence (> 16 inputs) -> Plan Chunks purely with legacy FFmpeg without GL pre-processing upfront.
        val plan = ChunkPlanner.plan(allSequenceItems, transitions)
        val density = context?.resources?.displayMetrics?.density ?: 1.0f

        val chunkCommands = mutableListOf<ChunkCommand>()
        val chunkTempPaths = mutableListOf<String>()

        for (chunk in plan.chunks) {
            val chunkTempFile = File(chunkTempDir, "temp_chunk_${chunk.chunkIndex}_${timestamp}.mp4")
            chunkTempPaths.add(chunkTempFile.absolutePath)

            val chunkCmd = buildChunkPassCommand(
                chunkSpec = chunk,
                sourceFilePath = sourceFilePath,
                chunkOutputPath = chunkTempFile.absolutePath,
                fontFilePath = fontFilePath,
                context = context,
                totalDurationMs = plan.totalDurationMs,
                density = density,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                operations = activeOperations,
                encoderOverride = selectedEncoder.ffmpegVideoCodecArgs
            )
            chunkCommands.add(chunkCmd.copy(renderPath = overallRenderPath))
        }

        val finalPassCmd = buildFinalPassCommand(
            chunkPlan = plan,
            chunkTempPaths = chunkTempPaths,
            outputFilePath = outputFilePath,
            context = context,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            operations = activeOperations,
            encoderOverride = selectedEncoder.ffmpegVideoCodecArgs
        )

        // Determine if final pass can safely use fast stream copy (-c copy concat demuxer)
        val hasBoundaryTransitions = plan.boundaryTransitions.any { it.transition != null && it.transition.type != "none" }
        val hasAudioOperations = activeOperations.any { it is EditOperation.AddBackgroundAudio }
        val hasWholeProjectFilters = activeOperations.any { it is EditOperation.AddSubtitles || it is EditOperation.AddText }
        val canStreamCopy = !hasBoundaryTransitions && !hasAudioOperations && !hasWholeProjectFilters

        return ChunkExportPlan(
            isChunked = true,
            chunkCommands = chunkCommands,
            finalPassCommand = finalPassCmd.command,
            chunkTempPaths = chunkTempPaths,
            totalDurationSecs = finalPassCmd.durationSecs,
            chunkDurationsSecs = plan.chunks.map { it.durationMs / 1000.0 },
            renderPath = overallRenderPath,
            chunkSpecs = plan.chunks,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            fps = _exportFps.value,
            resolution = _exportResolution.value,
            density = density,
            fontFilePath = fontFilePath,
            operations = activeOperations,
            canStreamCopyFinalPass = canStreamCopy
        )
    }

    /**
     * Bounded per-chunk GL motion pre-rendering.
     * Renders motion MP4 clips only for image items in the active chunk with motion effects.
     * Intermediate files are deleted immediately after that chunk completes.
     */
    /**
     * Legacy no-op stub: GL motion rendering during export is disabled.
     */
    @Deprecated("Legacy FFmpeg is the sole rendering engine. GL motion rendering is disabled.")
    fun preRenderGlMotionForChunk(
        chunkSpec: ChunkSpec,
        operations: List<EditOperation>,
        cacheDir: File,
        targetWidth: Int,
        targetHeight: Int,
        fps: Int,
        preferredEncoder: String = "hardware"
    ): Pair<ChunkSpec, List<File>> {
        return Pair(chunkSpec, emptyList())
    }

    /**
     * Legacy no-op stub: GL motion rendering during export is disabled.
     */
    @Deprecated("Legacy FFmpeg is the sole rendering engine. GL motion rendering is disabled.")
    fun preRenderGlMotionIntermediates(
        items: List<EditOperation.MergeItem>,
        operations: List<EditOperation>,
        cacheDir: File,
        targetWidth: Int,
        targetHeight: Int,
        fps: Int,
        preferredEncoder: String = "hardware"
    ): Pair<List<EditOperation.MergeItem>, String> {
        return Pair(items, "legacy")
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
        density: Float = 1.0f
    ): String? {
        val currentProject = _project.value ?: return null
        val operations = currentProject.operations
            .filterNot { it is EditOperation.Merge } // Skip merge for preview

        if (operations.none { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddSubtitles }) {
            return null // No visual operations to preview
        }

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
            fontFilePath = fontFilePath,
            density = density,
            sourceVideoHeight = 1080
        )
        if (videoStages.isNotEmpty()) {
            cmd.append(" -filter_complex \"${videoStages.joinToString(";")}\"")
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
        updateUiState { it.copy(isExporting = true, exportProgress = 0, errorMessage = null) }
    }

    fun updateExportProgress(progress: Int) {
        updateUiState { it.copy(exportProgress = progress.coerceIn(0, 100)) }
    }

    fun finishExport() {
        updateUiState { it.copy(isExporting = false, exportProgress = 100) }
    }

    fun exportError(error: String) {
        updateUiState { it.copy(isExporting = false, errorMessage = error) }
    }

    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
    }

    fun saveRecipe(projectName: String): EditRecipe? =
        _project.value?.let { EditRecipe.fromVideoProject(projectName, it) }

    fun loadRecipe(recipe: EditRecipe) {
        _project.value = recipe.toVideoProject()
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(pendingOperationCount = recipe.operations.size, canUndo = false) }
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