package com.boostlyte.vydra.render.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance off-screen OpenGL ES 2.0+ motion renderer.
 *
 * Replaces CPU-bound FFmpeg zoompan by rendering static images with smooth floating-point
 * sub-pixel transformations into MediaCodec + MediaMuxer MP4 intermediates.
 *
 * Implements a robust fallback chain:
 * 1. Hardware MediaCodec encoder (gl_hw)
 * 2. Software MediaCodec encoder (gl_sw)
 * 3. Returns failure for caller to use legacy FFmpeg zoompan (legacy)
 */
object GlMotionRenderer {

    private const val TAG = "GlMotionRenderer"
    private const val MIME_TYPE = "video/avc"
    private const val I_FRAME_INTERVAL = 1 // 1 second between keyframes for clean bridge concatenation
    private const val TIMEOUT_USEC = 10_000L

    sealed class GlRenderResult {
        data class Success(val outputPath: String, val renderPath: String) : GlRenderResult()
        data class Failure(val reason: String, val throwable: Throwable? = null) : GlRenderResult()
    }

    /**
     * Renders a static image into an MP4 video clip with Ken Burns motion.
     *
     * @param imagePath Absolute path to source image file
     * @param outputPath Absolute path to output intermediate MP4
     * @param targetWidth Output video width (must be even)
     * @param targetHeight Output video height (must be even)
     * @param durationSecs Duration of the clip in seconds
     * @param fps Framerate (e.g. 30)
     * @param motionType One of "zoom_in", "zoom_out", "pan_left", "pan_right", "pan_up", "pan_down", "drift_up_right"
     * @param preferredEncoder "hardware" or "software"
     */
    fun renderMotionClip(
        imagePath: String,
        outputPath: String,
        targetWidth: Int,
        targetHeight: Int,
        durationSecs: Double,
        fps: Int = 30,
        motionType: String?,
        preferredEncoder: String = "hardware"
    ): GlRenderResult {
        val safeW = (targetWidth / 2) * 2
        val safeH = (targetHeight / 2) * 2
        val safeDuration = durationSecs.coerceAtLeast(0.1)
        val totalFrames = Math.round(safeDuration * fps).toInt().coerceAtLeast(1)

        val imageFile = File(imagePath)
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return GlRenderResult.Failure("Source image file does not exist or is empty: $imagePath")
        }

        val decodedBitmap = try {
            decodeImageWithOrientation(imagePath, safeW, safeH)
        } catch (e: Exception) {
            return GlRenderResult.Failure("Failed to decode image $imagePath: ${e.message}", e)
        } ?: return GlRenderResult.Failure("Decoded bitmap is null for $imagePath")

        try {
            // Attempt 1: Try preferred encoder (usually hardware)
            if (preferredEncoder != "software") {
                try {
                    Log.d(TAG, "Attempting hardware GL motion render for $imagePath -> $outputPath")
                    renderWithEncoder(
                        bitmap = decodedBitmap,
                        outputPath = outputPath,
                        width = safeW,
                        height = safeH,
                        totalFrames = totalFrames,
                        fps = fps,
                        motionType = motionType,
                        codecName = null // Default hardware encoder
                    )
                    val outFile = File(outputPath)
                    if (outFile.exists() && outFile.length() > 0L) {
                        return GlRenderResult.Success(outputPath, renderPath = "gl_hw")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware encoder failed: ${e.message}, falling back to software GL encoder", e)
                }
            }

            // Attempt 2: Software MediaCodec encoder fallback
            val swCodecName = findSoftwareAvcEncoder()
            if (swCodecName != null) {
                try {
                    Log.d(TAG, "Attempting software GL motion render with $swCodecName")
                    renderWithEncoder(
                        bitmap = decodedBitmap,
                        outputPath = outputPath,
                        width = safeW,
                        height = safeH,
                        totalFrames = totalFrames,
                        fps = fps,
                        motionType = motionType,
                        codecName = swCodecName
                    )
                    val outFile = File(outputPath)
                    if (outFile.exists() && outFile.length() > 0L) {
                        return GlRenderResult.Success(outputPath, renderPath = "gl_sw")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Software encoder failed: ${e.message}", e)
                }
            }

            return GlRenderResult.Failure("Both hardware and software MediaCodec encoders failed for GL motion rendering")
        } finally {
            decodedBitmap.recycle()
        }
    }

    private fun renderWithEncoder(
        bitmap: Bitmap,
        outputPath: String,
        width: Int,
        height: Int,
        totalFrames: Int,
        fps: Int,
        motionType: String?,
        codecName: String?
    ) {
        val outputFile = File(outputPath)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val targetBitrate = calculateBitrate(width, height, fps)
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val encoder = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MIME_TYPE)
        }

        var muxer: MediaMuxer? = null
        var eglHelper: EglSurfaceHelper? = null

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            eglHelper = EglSurfaceHelper(inputSurface)
            eglHelper.makeCurrent()

            val glProgram = GlProgram()
            val textureId = glProgram.loadTexture(bitmap)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val srcW = bitmap.width
            val srcH = bitmap.height

            for (frameIdx in 0 until totalFrames) {
                val progress = if (totalFrames > 1) frameIdx.toFloat() / (totalFrames - 1) else 0.0f
                val cropRect = GlMotionTransform.computeCropRect(motionType, progress, srcW, srcH, width, height)
                val texCoords = GlMotionTransform.toTextureCoordinates(cropRect)

                GLES20.glViewport(0, 0, width, height)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                glProgram.draw(textureId, texCoords)

                val presentationTimeNs = (frameIdx.toLong() * 1_000_000_000L) / fps
                eglHelper.setPresentationTime(presentationTimeNs)
                eglHelper.swapBuffers()

                // Drain encoder outputs
                videoTrackIndex = drainEncoder(encoder, muxer, bufferInfo, false, videoTrackIndex) { muxerStarted = true }
            }

            // Signal end of stream
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, bufferInfo, true, videoTrackIndex) { muxerStarted = true }

            glProgram.release(textureId)
        } finally {
            try { eglHelper?.release() } catch (_: Exception) {}
            try {
                encoder.stop()
                encoder.release()
            } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer?,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        trackIndex: Int,
        onMuxerStarted: () -> Unit
    ): Int {
        var currentTrackIndex = trackIndex

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (currentTrackIndex >= 0) {
                    throw RuntimeException("Format changed twice")
                }
                val newFormat = encoder.outputFormat
                currentTrackIndex = muxer?.addTrack(newFormat) ?: -1
                muxer?.start()
                onMuxerStarted()
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                    ?: throw RuntimeException("getOutputBuffer was null")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0 && currentTrackIndex >= 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer?.writeSampleData(currentTrackIndex, encodedData, bufferInfo)
                }

                encoder.releaseOutputBuffer(status, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }

        return currentTrackIndex
    }

    private fun calculateBitrate(width: Int, height: Int, fps: Int): Int {
        val pixels = width * height
        return when {
            pixels >= 1920 * 1080 -> 8_000_000 // 8 Mbps for 1080p
            pixels >= 1280 * 720  -> 4_000_000 // 4 Mbps for 720p
            else                  -> 2_000_000 // 2 Mbps for smaller
        }
    }

    private fun findSoftwareAvcEncoder(): String? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            val types = info.supportedTypes
            val isAvc = types.any { it.equals(MIME_TYPE, ignoreCase = true) }
            if (!isAvc) continue
            val name = info.name.lowercase()
            if (name.contains("google") || name.contains("sw") || name.contains("c2.android")) {
                return info.name
            }
        }
        return null
    }

    private fun decodeImageWithOrientation(path: String, maxW: Int, maxH: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        var sampleSize = 1
        val reqW = maxW * 2
        val reqH = maxH * 2
        while ((options.outWidth / sampleSize) > reqW || (options.outHeight / sampleSize) > reqH) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

        val rotation = try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }

        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            rawBitmap.recycle()
            rotated
        } else {
            rawBitmap
        }
    }

    // ── EGL Surface Helper ───────────────────────────────────────────────────

    private class EglSurfaceHelper(surface: Any) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("Unable to get EGL14 display")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw RuntimeException("Unable to initialize EGL14")
            }

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
            val config = configs[0] ?: throw RuntimeException("Unable to find suitable EGLConfig")

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("Failed to create EGL context")
            }

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("Failed to create EGL window surface")
            }
        }

        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // ── OpenGL ES 2.0 Shader Program ─────────────────────────────────────────

    private class GlProgram {
        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        private val programId: Int
        private val aPositionHandle: Int
        private val aTexCoordHandle: Int
        private val sTextureHandle: Int

        private val vertexBuffer: FloatBuffer
        private var texCoordBuffer: FloatBuffer

        init {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            programId = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }

            aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
            sTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")

            // Full screen quad in normalized device coordinates
            val vertices = floatArrayOf(
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
            )
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
            vertexBuffer.position(0)

            texCoordBuffer = ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }

        fun loadTexture(bitmap: Bitmap): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return texId
        }

        fun draw(textureId: Int, texCoords: FloatArray) {
            GLES20.glUseProgram(programId)

            texCoordBuffer.clear()
            texCoordBuffer.put(texCoords)
            texCoordBuffer.position(0)

            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(sTextureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        }

        fun release(textureId: Int) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            GLES20.glDeleteProgram(programId)
        }

        private fun compileShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }
    }
}
