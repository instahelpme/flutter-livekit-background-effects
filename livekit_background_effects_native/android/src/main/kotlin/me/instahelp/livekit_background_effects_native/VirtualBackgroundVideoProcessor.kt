/*
 * Copyright 2024-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.instahelp.livekit_background_effects_native

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import org.webrtc.EglBase
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.VideoFrame.TextureBuffer
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.YuvConverter
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * A virtual background video processor for the local camera video stream.
 *
 * By default, blurs the background of the video stream.
 * Setting [backgroundImage] will use the provided image instead.
 *
 * All GL work happens on a dedicated thread with its own EGL context (shared with
 * [sharedContext]) and a dummy pbuffer surface. Processed frames are rendered into
 * offscreen framebuffers and handed downstream as RGB [TextureBufferImpl]s.
 *
 * We deliberately do NOT render into a window surface backed by an in-process
 * SurfaceTexture: with ANGLE-based GLES drivers (e.g. recent Samsung firmware), a
 * swapchain recreation on that surface synchronously calls back into the consumer,
 * which re-enters EGL while the driver's global lock is held and deadlocks the
 * render thread.
 *
 * None of the public methods block, so they are safe to call from the main thread.
 */
class VirtualBackgroundVideoProcessor(
    sharedContext: EglBase.Context,
) : LocalVideoTrack.ExternalVideoFrameProcessing {

    private val thread = HandlerThread(TAG).apply { start() }
    private val handler = Handler(thread.looper)

    // --- GL thread state (only touched on [handler]'s thread) ---
    private var eglBase: EglBase? = null
    private val frameDrawer = VideoFrameDrawer()
    private var yuvConverter: YuvConverter? = null
    private val backgroundTransformer = VirtualBackgroundTransformer()
    private val freeFrameBuffers = ArrayDeque<GlTextureFrameBuffer>()
    private var outstandingFrameBuffers = 0
    private var glReleased = false
    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var droppedFrames = 0

    // --- cross-thread state ---
    /** Newest frame waiting for the GL thread. Older frames are dropped if processing is slower than capture. */
    private val pendingFrame = AtomicReference<VideoFrame?>(null)

    @Volatile
    private var targetSink: VideoSink? = null

    @Volatile
    private var disposed = false

    @Volatile
    var lastMask: VirtualBackgroundTransformer.MaskHolder? = null

    /**
     * Enables or disables the virtual background.
     *
     * Defaults to true.
     */
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var backgroundImage: Bitmap? = null
        set(value) {
            field = value
            backgroundImageNeedsUpdating = true
        }

    @Volatile
    private var backgroundImageNeedsUpdating = false

    var blurRadius: Int? = null
        set(value) {
            field = value
            backgroundTransformer.blurRadius = value
        }

    init {
        handler.post {
            try {
                val egl = EglBase.create(sharedContext, EglBase.CONFIG_PIXEL_BUFFER)
                eglBase = egl
                egl.createDummyPbufferSurface()
                egl.makeCurrent()
                yuvConverter = YuvConverter()
            } catch (e: RuntimeException) {
                // yuvConverter stays null, so processFrame passes frames through unprocessed.
                Log.e(TAG, "failed to set up EGL, frames will be passed through unprocessed", e)
                eglBase?.release()
                eglBase = null
            }
        }
    }

    override fun setSink(sink: VideoSink?) {
        targetSink = sink
    }

    override fun onFrame(frame: VideoFrame) {
        if (disposed) {
            return
        }
        // If disabled, just pass through to the sink.
        if (!enabled) {
            targetSink?.onFrame(frame)
            return
        }

        try {
            frame.retain()
        } catch (e: Exception) {
            return
        }

        val previous = pendingFrame.getAndSet(frame)
        if (previous != null) {
            // A drain is already scheduled and will pick up the new frame.
            previous.release()
            return
        }
        if (!handler.post(::drainPendingFrame)) {
            // Thread already quit.
            pendingFrame.getAndSet(null)?.release()
        }
    }

    /**
     * Releases all resources. Does not block; GL resources are released on the
     * GL thread once all frames handed downstream have been returned.
     */
    fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        enabled = false
        targetSink = null
        val posted = handler.post {
            pendingFrame.getAndSet(null)?.release()
            releaseGlIfIdle()
        }
        if (!posted) {
            pendingFrame.getAndSet(null)?.release()
        }
    }

    private fun drainPendingFrame() {
        val frame = pendingFrame.getAndSet(null) ?: return
        try {
            if (!disposed) {
                processFrame(frame)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "failed to process frame", e)
        } finally {
            frame.release()
        }
    }

    private fun processFrame(frame: VideoFrame) {
        val buffer = frame.buffer
        val converter = yuvConverter
        if (glReleased || converter == null || buffer !is TextureBuffer || buffer.type != TextureBuffer.Type.OES) {
            // The transformer can only draw OES textures (camera frames).
            targetSink?.onFrame(frame)
            return
        }

        if (lastRotation != frame.rotation) {
            lastRotation = frame.rotation
            backgroundImageNeedsUpdating = true
            backgroundTransformer.rotation = frame.rotation
        }

        if (lastWidth != frame.rotatedWidth || lastHeight != frame.rotatedHeight) {
            lastWidth = frame.rotatedWidth
            lastHeight = frame.rotatedHeight
            backgroundImageNeedsUpdating = true
        }

        updateBackgroundImage(frame)

        lastMask?.let {
            backgroundTransformer.updateMask(it)
        }
        lastMask = null

        val width = buffer.width
        val height = buffer.height
        val target = obtainFrameBuffer(width, height)
        if (target == null) {
            // All output buffers are still held downstream; drop this frame.
            if (droppedFrames++ % 100 == 0) {
                Log.w(TAG, "no free output buffer, dropped $droppedFrames frames so far")
            }
            return
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.frameBufferId)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        backgroundTransformer.targetFrameBufferId = target.frameBufferId
        frameDrawer.drawFrame(frame, backgroundTransformer, null, 0, 0, width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // Consumers sample the texture from other (shared) contexts; make sure rendering is done.
        GLES20.glFinish()

        val outBuffer = TextureBufferImpl(
            width,
            height,
            TextureBuffer.Type.RGB,
            target.textureId,
            Matrix(),
            handler,
            converter,
        ) {
            if (!handler.post { returnFrameBuffer(target) }) {
                Log.w(TAG, "GL thread gone, leaking output framebuffer")
            }
        }
        val outFrame = VideoFrame(outBuffer, frame.rotation, frame.timestampNs)
        try {
            targetSink?.onFrame(outFrame)
        } finally {
            outFrame.release()
        }
    }

    private fun updateBackgroundImage(frame: VideoFrame) {
        if (!backgroundImageNeedsUpdating) {
            return
        }
        backgroundImageNeedsUpdating = false
        val backgroundImage = this.backgroundImage
        if (backgroundImage == null) {
            backgroundTransformer.backgroundImage = null
            return
        }

        val imageAspect = backgroundImage.width / backgroundImage.height.toFloat()
        val targetAspect = frame.rotatedWidth / frame.rotatedHeight.toFloat()
        var sx = 0
        var sy = 0
        var sWidth = backgroundImage.width
        var sHeight = backgroundImage.height

        if (imageAspect > targetAspect) {
            sWidth = (backgroundImage.height * targetAspect).roundToInt()
            sx = ((backgroundImage.width - sWidth) / 2f).roundToInt()
        } else {
            sHeight = (backgroundImage.width / targetAspect).roundToInt()
            sy = ((backgroundImage.height - sHeight) / 2f).roundToInt()
        }

        val matrix = Matrix()
        matrix.postRotate(-frame.rotation.toFloat())

        backgroundTransformer.backgroundImage = Bitmap.createBitmap(
            backgroundImage,
            sx,
            sy,
            sWidth,
            sHeight,
            matrix,
            true,
        )
    }

    private fun obtainFrameBuffer(width: Int, height: Int): GlTextureFrameBuffer? {
        val frameBuffer = freeFrameBuffers.removeFirstOrNull()
            ?: if (outstandingFrameBuffers < MAX_OUTPUT_BUFFERS) {
                GlTextureFrameBuffer(GLES20.GL_RGBA)
            } else {
                return null
            }
        frameBuffer.setSize(width, height)
        outstandingFrameBuffers++
        return frameBuffer
    }

    private fun returnFrameBuffer(frameBuffer: GlTextureFrameBuffer) {
        outstandingFrameBuffers--
        if (disposed || glReleased) {
            frameBuffer.release()
            releaseGlIfIdle()
        } else {
            freeFrameBuffers.addLast(frameBuffer)
        }
    }

    private fun releaseGlIfIdle() {
        freeFrameBuffers.forEach { it.release() }
        freeFrameBuffers.clear()
        if (outstandingFrameBuffers == 0) {
            releaseGl()
        }
    }

    private fun releaseGl() {
        if (glReleased) {
            return
        }
        glReleased = true
        try {
            if (eglBase?.hasSurface() == true) {
                backgroundTransformer.release()
                frameDrawer.release()
                yuvConverter?.release()
            }
            eglBase?.release()
        } catch (e: RuntimeException) {
            Log.e(TAG, "failed to release GL resources", e)
        }
        eglBase = null
        yuvConverter = null
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "VBVideoProcessor"
        private const val MAX_OUTPUT_BUFFERS = 3
    }
}
