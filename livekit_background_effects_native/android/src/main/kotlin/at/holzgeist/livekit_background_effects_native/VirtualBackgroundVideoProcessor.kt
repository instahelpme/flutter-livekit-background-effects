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

package at.holzgeist.livekit_background_effects_native

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cloudwebrtc.webrtc.video.LocalVideoTrack
//import com.google.mlkit.vision.common.InputImage
//import com.google.mlkit.vision.segmentation.Segmentation
//import com.google.mlkit.vision.segmentation.Segmenter
//import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlUtil
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoFrame.TextureBuffer
import org.webrtc.VideoSink
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt

/**
 * A virtual background video processor for the local camera video stream.
 *
 * By default, blurs the background of the video stream.
 * Setting [backgroundImage] will use the provided image instead.
 */
class VirtualBackgroundVideoProcessor(private val eglBase: EglBase, dispatcher: CoroutineDispatcher = Dispatchers.Default) : LocalVideoTrack.ExternalVideoFrameProcessing {
    private val TAG = "VBVideoProcessor"

    private var targetSink: VideoSink? = null

    private var lastRotation = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private val surfaceTextureHelper = SurfaceTextureHelper.create("BitmapToYUV", eglBase.eglBaseContext, true)
    private val surface = Surface(surfaceTextureHelper.surfaceTexture)
    private val backgroundTransformer = VirtualBackgroundTransformer()
    private var eglRenderer: EglRenderer? = null

    private val scope = CoroutineScope(dispatcher)
    private val taskFlow = MutableSharedFlow<VideoFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    var lastMask: VirtualBackgroundTransformer.MaskHolder? = null

    /**
     * Enables or disables the virtual background.
     *
     * Defaults to true.
     */
    var enabled: Boolean = true

    var backgroundImage: Bitmap? = null
        set(value) {
            field = value
            backgroundImageNeedsUpdating = true
        }
    private var backgroundImageNeedsUpdating = false
    var blurRadius: Int? = null
        set(value) {
            backgroundTransformer.blurRadius = value
        }

    init {
        // Funnel processing into a single flow that won't buffer,
        // since processing may be slower than video capture.
        scope.launch {
            taskFlow.collect { frame ->
                // Log.i(TAG, "process frame from ${frame.timestampNs}")
                processFrame(frame)
//                targetSink?.onFrame(frame)

                frame.release()
            }
        }
    }

    fun onCapturerStarted(started: Boolean) {
        if (started) {
            eglRenderer = EglRenderer(VirtualBackgroundVideoProcessor::class.java.simpleName)
                .apply {
                    init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, backgroundTransformer)
                    createEglSurface(surface)
                }
            surfaceTextureHelper.startListening { frame ->
//                Log.i(TAG, "new frame from surface helper ${(frame.buffer as TextureBuffer).getTextureId()} ${frame.rotation}")
                 // Log.i(TAG, "passing frame to sink ${frame.timestampNs}")
                targetSink?.onFrame(frame)
            }
        }
    }

    fun onCapturerStopped() {
        surfaceTextureHelper.stopListening()
        eglRenderer?.release()
        eglRenderer = null

    }

    override fun onFrame(frame: VideoFrame) {
//        Log.i(TAG, "onFrame: ${(frame.buffer as TextureBuffer).textureId} ${frame.rotation}")
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
        // Log.i(TAG, "emitting frame to flow ${frame.timestampNs}")

        // If the frame is successfully emitted, the process flow will own the frame.
        if (!taskFlow.tryEmit(frame)) {
//            Log.i(TAG, "could not emit new frame")
            frame.release()
        } else {
//                Log.i(TAG, "emitted new frame, awaiting processing")
            }
    }

    fun processFrame(frame: VideoFrame) {
//        Log.i(TAG, "process frame ${(frame.buffer as TextureBuffer).textureId} ${frame.rotation}")
//        Log.i(TAG, "DEVTO got frame with timestamp ${frame.timestampNs}")

        if (lastRotation != frame.rotation) {
            lastRotation = frame.rotation
            backgroundImageNeedsUpdating = true
            surfaceTextureHelper.setFrameRotation(frame.rotation)
            backgroundTransformer.rotation = frame.rotation
        }

        if (lastWidth != frame.rotatedWidth || lastHeight != frame.rotatedHeight) {
            surfaceTextureHelper.setTextureSize(frame.buffer.width, frame.buffer.height)
            lastWidth = frame.rotatedWidth
            lastHeight = frame.rotatedHeight
            backgroundImageNeedsUpdating = true
        }

        frame.retain()
        surfaceTextureHelper.handler.post {
            val backgroundImage = this.backgroundImage
            if (backgroundImageNeedsUpdating && backgroundImage != null) {
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

                val resizedImage = Bitmap.createBitmap(
                    backgroundImage,
                    sx,
                    sy,
                    sWidth,
                    sHeight,
                    matrix,
                    true,
                )
                backgroundTransformer.backgroundImage = resizedImage
                backgroundImageNeedsUpdating = false
            } else if (backgroundImageNeedsUpdating) {
                backgroundTransformer.backgroundImage = null
                backgroundImageNeedsUpdating = false
            }

            lastMask?.let {
                backgroundTransformer.updateMask(it)
            }
            lastMask = null
             // Log.i(TAG, "send frame to renderer ${frame.timestampNs}")
            eglRenderer?.onFrame(frame)
            frame.release()
        }
    }

    override fun setSink(sink: VideoSink?) {
        targetSink = sink
    }

    fun dispose() {
        scope.cancel()
        surfaceTextureHelper.stopListening()
        surfaceTextureHelper.dispose()
        surface.release()
        eglRenderer?.release()
        backgroundTransformer.release()
        GlUtil.checkNoGLES2Error("VirtualBackgroundVideoProcessor.dispose")
    }
}
