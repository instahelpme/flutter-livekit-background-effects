/*
 * Copyright 2026 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.instahelp.livekit_background_effects_native

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteBuffer

/**
 * Runs MediaPipe person segmentation on its own thread.
 *
 * Only one [segment] call may be in flight at a time: the caller must wait for the callback
 * before calling [segment] again, and must not touch the input buffer in the meantime.
 */
internal class PersonSegmenter(context: Context, modelBuffer: ByteBuffer) {

    /**
     * A confidence mask with one byte per pixel (0 = background, 255 = person), in the same
     * orientation as the input image. [buffer] is reused and only valid until the next
     * [segment] call.
     */
    class Mask(val width: Int, val height: Int, val buffer: ByteBuffer)

    private val thread = HandlerThread("VBSegmenter").apply { start() }
    private val handler = Handler(thread.looper)

    // --- segmentation thread state ---
    private var segmenter: ImageSegmenter? = null
    private var inputBitmap: Bitmap? = null
    private var maskBuffer: ByteBuffer? = null
    private var maskFloats = FloatArray(0)
    private var lastTimestampMs = Long.MIN_VALUE

    /** True if the segmenter could not be created; no masks will ever be produced. */
    @Volatile
    var failed = false
        private set

    init {
        val appContext = context.applicationContext
        handler.post {
            try {
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetBuffer(modelBuffer)
                            .setDelegate(Delegate.CPU)
                            .build(),
                    )
                    .setRunningMode(RunningMode.VIDEO)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build()
                segmenter = ImageSegmenter.createFromOptions(appContext, options)
            } catch (e: RuntimeException) {
                Log.e(TAG, "failed to create segmenter", e)
                failed = true
            }
        }
    }

    /**
     * Segments an RGBA image of [width]x[height] pixels (row 0 first, upright).
     *
     * [callback] is invoked exactly once on the segmentation thread, with null if
     * segmentation failed, together with the inference time in nanoseconds. Returns false
     * (and never invokes [callback]) if the segmenter has been closed.
     */
    fun segment(
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        timestampNs: Long,
        callback: (Mask?, Long) -> Unit,
    ): Boolean = handler.post {
        val start = SystemClock.elapsedRealtimeNanos()
        var mask: Mask? = null
        try {
            mask = runSegmentation(rgba, width, height, timestampNs)
        } catch (e: RuntimeException) {
            Log.e(TAG, "segmentation failed", e)
        } finally {
            callback(mask, SystemClock.elapsedRealtimeNanos() - start)
        }
    }

    private fun runSegmentation(rgba: ByteBuffer, width: Int, height: Int, timestampNs: Long): Mask? {
        val segmenter = this.segmenter ?: return null

        val previousBitmap = inputBitmap
        val bitmap = if (previousBitmap != null && previousBitmap.width == width && previousBitmap.height == height) {
            previousBitmap
        } else {
            previousBitmap?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { inputBitmap = it }
        }
        rgba.rewind()
        bitmap.copyPixelsFromBuffer(rgba)

        // VIDEO mode requires strictly increasing timestamps.
        var timestampMs = timestampNs / 1_000_000
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1
        }
        lastTimestampMs = timestampMs

        // Note: the input MPImage is intentionally not closed, closing it would recycle the
        // reused bitmap.
        val result = segmenter.segmentForVideo(BitmapImageBuilder(bitmap).build(), timestampMs)
        val masks = result.confidenceMasks().orElse(null) ?: return null
        try {
            val confidence = masks.firstOrNull() ?: return null
            val pixels = confidence.width * confidence.height
            if (maskFloats.size != pixels) {
                maskFloats = FloatArray(pixels)
            }
            ByteBufferExtractor.extract(confidence).asFloatBuffer().get(maskFloats)

            val previousOut = maskBuffer
            val out = if (previousOut != null && previousOut.capacity() == pixels) {
                previousOut
            } else {
                ByteBuffer.allocateDirect(pixels).also { maskBuffer = it }
            }
            out.clear()
            for (i in 0 until pixels) {
                out.put((maskFloats[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            }
            out.rewind()
            return Mask(confidence.width, confidence.height, out)
        } finally {
            masks.forEach { it.close() }
        }
    }

    /** Releases the segmenter once any in-flight segmentation has finished. Does not block. */
    fun close() {
        handler.post {
            try {
                segmenter?.close()
            } catch (e: RuntimeException) {
                Log.e(TAG, "failed to close segmenter", e)
            }
            segmenter = null
            inputBitmap?.recycle()
            inputBitmap = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "VBSegmenter"
    }
}
