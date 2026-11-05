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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A virtual background video processor for the local camera video stream.
 *
 * By default, blurs the background of the video stream.
 * Setting [backgroundImage] will use the provided image instead.
 *
 * Every output frame is composited with the segmentation mask computed from that same
 * frame, so the mask never lags behind the video. Per frame:
 *  1. GL thread: rotate the camera frame upright, downscale it and read it back.
 *  2. Segmentation thread ([PersonSegmenter]): run MediaPipe on the small image.
 *  3. GL thread: upload the mask and composite the frame.
 * Segmentation of the next frame runs while the current frame is composited. Frames that
 * arrive while a frame is being segmented are dropped (only the newest one is kept), so
 * the output frame rate drops to the segmentation throughput rather than showing a stale
 * mask.
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
 *
 * Per-second statistics are logged when debug logging is enabled for the tag:
 * `adb shell setprop log.tag.VBVideoProcessor DEBUG`.
 */
class VirtualBackgroundVideoProcessor(
    sharedContext: EglBase.Context,
    context: Context,
    modelBuffer: ByteBuffer,
) : LocalVideoTrack.ExternalVideoFrameProcessing {

    private val thread = HandlerThread(TAG).apply { start() }
    private val handler = Handler(thread.looper)
    private val segmenter = PersonSegmenter(context, modelBuffer)
    private val stats = Stats()

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

    /** Frame whose mask is currently being computed by [segmenter]. */
    private var segmentingFrame: VideoFrame? = null

    /** RGBA readback of the upright, downscaled frame; read by [segmenter] while segmenting. */
    private var readbackBuffer: ByteBuffer? = null

    // --- cross-thread state ---
    /** Newest frame waiting for the GL thread. Older frames are dropped if processing is slower than capture. */
    private val pendingFrame = AtomicReference<VideoFrame?>(null)

    @Volatile
    private var targetSink: VideoSink? = null

    @Volatile
    private var disposed = false

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
                // yuvConverter stays null, so frames are passed through unprocessed.
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
        stats.inputFrames.incrementAndGet()

        val previous = pendingFrame.getAndSet(frame)
        if (previous != null) {
            // Not picked up yet; the GL thread will take the new frame instead.
            stats.droppedFrames.incrementAndGet()
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
        segmenter.close()
        val posted = handler.post {
            pendingFrame.getAndSet(null)?.release()
            // A segmentation result may still arrive; it finds no frame and is ignored.
            segmentingFrame?.release()
            segmentingFrame = null
            releaseGlIfIdle()
        }
        if (!posted) {
            pendingFrame.getAndSet(null)?.release()
        }
    }

    /** Takes the pending frame unless a frame is being segmented; then it is picked up in [onMask]. */
    private fun drainPendingFrame() {
        if (segmentingFrame != null) {
            return
        }
        val frame = pendingFrame.getAndSet(null) ?: return
        if (!tryStartSegmentation(frame)) {
            processWithoutSegmentation(frame)
        }
    }

    private fun canProcess(frame: VideoFrame): Boolean {
        val buffer = frame.buffer
        // The transformer can only draw OES textures (camera frames).
        return !glReleased && yuvConverter != null &&
            buffer is TextureBuffer && buffer.type == TextureBuffer.Type.OES
    }

    /**
     * Reads back [frame] and hands it to the segmenter. Returns true if the frame was
     * consumed (it is then released by this processor), false if it has to be handled
     * without segmentation.
     */
    private fun tryStartSegmentation(frame: VideoFrame): Boolean {
        if (disposed || segmenter.failed || !canProcess(frame)) {
            return false
        }
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            val rotatedWidth = frame.rotatedWidth
            val rotatedHeight = frame.rotatedHeight
            val scale = SEGMENTATION_SIZE / max(rotatedWidth, rotatedHeight).toFloat()
            val width = max(1, (rotatedWidth * scale).roundToInt())
            val height = max(1, (rotatedHeight * scale).roundToInt())

            val previousBuffer = readbackBuffer
            val buffer = if (previousBuffer != null && previousBuffer.capacity() == width * height * 4) {
                previousBuffer
            } else {
                ByteBuffer.allocateDirect(width * height * 4).also { readbackBuffer = it }
            }
            backgroundTransformer.readbackUpright(
                (frame.buffer as TextureBuffer).textureId,
                frame.rotation,
                width,
                height,
                buffer,
            )
            stats.readbackNs.add(SystemClock.elapsedRealtimeNanos() - start)

            segmentingFrame = frame
            val posted = segmenter.segment(buffer, width, height, frame.timestampNs) { mask, inferenceNs ->
                handler.post { onMask(frame, mask, inferenceNs) }
            }
            if (!posted) {
                segmentingFrame = null
                frame.release()
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "failed to start segmentation", e)
            if (segmentingFrame === frame) {
                segmentingFrame = null
            }
            frame.release()
        }
        return true
    }

    private fun onMask(frame: VideoFrame, mask: PersonSegmenter.Mask?, inferenceNs: Long) {
        if (segmentingFrame !== frame) {
            // Released by dispose().
            return
        }
        segmentingFrame = null
        stats.inferenceNs.add(inferenceNs)

        var next: VideoFrame? = null
        var nextStarted = false
        try {
            if (disposed || glReleased || !enabled) {
                return
            }
            if (mask != null) {
                // Upload before starting the next segmentation, which reuses the mask buffer.
                backgroundTransformer.uploadMask(mask, frame.rotation)
            } else if (segmenter.failed) {
                backgroundTransformer.clearMask()
            } else {
                // Better to skip a frame than to show it with a wrong mask.
                stats.droppedFrames.incrementAndGet()
                return
            }

            // Segment the next frame while this one is being composited.
            next = pendingFrame.getAndSet(null)
            nextStarted = next != null && tryStartSegmentation(next)

            composite(frame)
        } catch (e: RuntimeException) {
            Log.e(TAG, "failed to process frame", e)
        } finally {
            frame.release()
            if (next != null && !nextStarted) {
                processWithoutSegmentation(next)
            }
            stats.maybeLog()
        }
    }

    /** Handles (and releases) a frame that can't or doesn't need to be segmented. */
    private fun processWithoutSegmentation(frame: VideoFrame) {
        try {
            if (disposed) {
                return
            }
            if (!canProcess(frame)) {
                targetSink?.onFrame(frame)
                return
            }
            // Segmentation is permanently unavailable: show only the background rather
            // than leaking it unprocessed.
            backgroundTransformer.clearMask()
            composite(frame)
        } catch (e: RuntimeException) {
            Log.e(TAG, "failed to process frame", e)
        } finally {
            frame.release()
        }
    }

    /** Composites [frame] with the current mask and emits it. Does not release [frame]. */
    private fun composite(frame: VideoFrame) {
        val start = SystemClock.elapsedRealtimeNanos()
        val buffer = frame.buffer
        val converter = yuvConverter ?: return

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

        val width = buffer.width
        val height = buffer.height
        val target = obtainFrameBuffer(width, height)
        if (target == null) {
            // All output buffers are still held downstream; drop this frame.
            stats.droppedFrames.incrementAndGet()
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
        stats.compositeNs.add(SystemClock.elapsedRealtimeNanos() - start)
        stats.outputFrames++
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

    /** Timing and frame-rate statistics, logged once per second if debug logging is enabled. */
    private class Stats {
        val inputFrames = AtomicInteger()
        val droppedFrames = AtomicInteger()

        // GL thread only
        var outputFrames = 0
        val readbackNs = Samples()
        val inferenceNs = Samples()
        val compositeNs = Samples()
        private var windowStartNs = SystemClock.elapsedRealtimeNanos()

        fun maybeLog() {
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsedNs = now - windowStartNs
            if (elapsedNs < 1_000_000_000L) {
                return
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val seconds = elapsedNs / 1e9
                Log.d(
                    TAG,
                    "in %.1f fps, out %.1f fps, dropped %d | readback %s | inference %s | composite %s".format(
                        inputFrames.get() / seconds,
                        outputFrames / seconds,
                        droppedFrames.get(),
                        readbackNs.summary(),
                        inferenceNs.summary(),
                        compositeNs.summary(),
                    ),
                )
            }
            windowStartNs = now
            inputFrames.set(0)
            droppedFrames.set(0)
            outputFrames = 0
            readbackNs.reset()
            inferenceNs.reset()
            compositeNs.reset()
        }
    }

    private class Samples {
        private val values = LongArray(128)
        private var count = 0

        fun add(ns: Long) {
            if (count < values.size) {
                values[count++] = ns
            }
        }

        fun reset() {
            count = 0
        }

        /** "avg/p95 ms" of the collected samples. */
        fun summary(): String {
            if (count == 0) {
                return "-"
            }
            val sorted = values.copyOf(count).also { it.sort() }
            val avg = sorted.average() / 1e6
            val p95 = sorted[((count - 1) * 0.95).toInt()] / 1e6
            return "%.1f/%.1f ms".format(avg, p95)
        }
    }

    companion object {
        private const val TAG = "VBVideoProcessor"
        private const val MAX_OUTPUT_BUFFERS = 3

        /** Long side of the image fed to the segmenter (the model's input is 256x256). */
        private const val SEGMENTATION_SIZE = 256
    }
}
