/*
 * Copyright 2025 LiveKit, Inc.
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
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLU
import android.util.Log
import me.instahelp.livekit_background_effects_native.opengl.LKGlTextureFrameBuffer
import me.instahelp.livekit_background_effects_native.shader.BlurShader
import me.instahelp.livekit_background_effects_native.shader.CompositeShader
import me.instahelp.livekit_background_effects_native.shader.ResamplerShader
import me.instahelp.livekit_background_effects_native.shader.createBlurShader
import me.instahelp.livekit_background_effects_native.shader.createBoxBlurShader
import me.instahelp.livekit_background_effects_native.shader.createCompsiteShader
import me.instahelp.livekit_background_effects_native.shader.createResampler
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.GlUtil
import org.webrtc.RendererCommon
import java.nio.ByteBuffer

/**
 * Blurs the background of the camera video stream.
 */
class VirtualBackgroundTransformer(
    val downSampleFactor: Int = 2,
) : RendererCommon.GlDrawer {

    private val TAG = VirtualBackgroundTransformer::class.java.simpleName

    data class MaskHolder(val width: Int, val height: Int, val buffer: ByteBuffer)

    private lateinit var compositeShader: CompositeShader
    private lateinit var blurShader: BlurShader
    private lateinit var boxBlurShader: BlurShader

    private var bgTexture = 0
    private var frameTexture = 0

    private lateinit var bgTextureFrameBuffers: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>

    private lateinit var downSampler: ResamplerShader

    var backgroundImageStateLock = Any()
    var backgroundImage: Bitmap? = null
        set(value) {
            if (value == field) {
                return
            }

            synchronized(backgroundImageStateLock) {
                field = value
                backgroundImageNeedsUpdating = true
            }
        }
    var backgroundImageNeedsUpdating = false

    var blurRadius: Int? = null

    var rotationStateLock = Any()
    var rotation: Int = 0
        set(value) {
            if (value == field) {
                return
            }

            synchronized(rotationStateLock) {
                field = value
            }
        }

    // For double buffering the final mask
    private var readMaskIndex = 0 // Index for renderFrame to read from
    private var writeMaskIndex = 1 // Index for updateMask to write to

    private fun swapMaskIndexes() {
        if (readMaskIndex == 0) {
            readMaskIndex = 1
            writeMaskIndex = 0
        } else {
            readMaskIndex = 0
            writeMaskIndex = 1
        }
    }

    var newMask: MaskHolder? = null
    var maskWidth: Int? = null
    var maskHeight: Int? = null
    var prevMask: MaskHolder? = null
    lateinit var anotherTempMaskFrameBuffer: LKGlTextureFrameBuffer
    lateinit var tempMaskTextureFrameBuffer: GlTextureFrameBuffer

    lateinit var finalMaskFrameBuffers: List<GlTextureFrameBuffer>

    var initialized = false

    fun initialize() {
        Log.e(TAG, "initialize shaders")
        compositeShader = createCompsiteShader()
        blurShader = createBlurShader()
        boxBlurShader = createBoxBlurShader()

        bgTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        frameTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)

        bgTextureFrameBuffers =
            GlTextureFrameBuffer(GLES30.GL_RGBA) to GlTextureFrameBuffer(GLES30.GL_RGBA)

        downSampler = createResampler()

        // For double buffering the final mask
        anotherTempMaskFrameBuffer =
            LKGlTextureFrameBuffer(GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE)
        tempMaskTextureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)

        finalMaskFrameBuffers = listOf(
            GlTextureFrameBuffer(GLES20.GL_RGBA),
            GlTextureFrameBuffer(GLES20.GL_RGBA),
        )

        GlUtil.checkNoGLES2Error("VirtualBackgroundTransformer.initialize")
        initialized = true
    }

    override fun drawOes(
        oesTextureId: Int,
        texMatrix: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        if (!initialized) {
            initialize()
        }
        // Log.i(TAG, "start drawing to $oesTextureId")

        newMask?.let {
            updateMaskFrameBuffer(it)
            prevMask = it
            newMask = null
        }
        val r: Int
        synchronized(rotationStateLock) {
            r = rotation
        }

        val backgroundTexture: Int

        synchronized(backgroundImageStateLock) {
            val backgroundImage = this.backgroundImage
            if (backgroundImage != null) {
                val bgTextureFrameBuffer = bgTextureFrameBuffers.first

                if (backgroundImageNeedsUpdating) {
                    val byteBuffer = ByteBuffer.allocateDirect(backgroundImage.byteCount)
                    backgroundImage.copyPixelsToBuffer(byteBuffer)
                    byteBuffer.rewind()

                    // Upload the background into a texture
                    bgTextureFrameBuffer.setSize(backgroundImage.width, backgroundImage.height)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureFrameBuffer.textureId)
                    checkNoError("bindBackgroundTexture")

                    GLES20.glTexSubImage2D(
                        /*target*/
                        GLES20.GL_TEXTURE_2D,
                        0,
                        0,
                        0,
                        backgroundImage.width,
                        backgroundImage.height,
                        /*format*/
                        GLES20.GL_RGBA,
                        /*type*/
                        GLES20.GL_UNSIGNED_BYTE,
                        byteBuffer,
                    )
                    checkNoError("updateBackgroundFrameBuffer")
                    backgroundImageNeedsUpdating = false
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    checkNoError("unbindBackgroundTexture")
                }
                backgroundTexture = bgTextureFrameBuffer.textureId
            } else {
                val downSampleWidth = frameWidth / downSampleFactor
                val downSampleHeight = frameHeight / downSampleFactor

                val downSampledFrameTexture =
                    downSampler.resample(oesTextureId, downSampleWidth, downSampleHeight, IDENTITY)
                var radius = blurRadius?.toFloat()
                if (radius == null) {
                    radius = 16f
                }
                backgroundTexture = blurShader.applyBlur(
                    downSampledFrameTexture,
                    radius,
                    downSampleWidth,
                    downSampleHeight,
                    bgTextureFrameBuffers,
                    r
                )
            }
        }

        val maskAspectRatio: Float
        if (maskWidth != null && maskHeight != null) {
            maskAspectRatio = maskWidth!!.toFloat() / maskHeight!!.toFloat()
        } else {
            maskAspectRatio = 1f
        }

        compositeShader.renderComposite(
            backgroundTextureId = backgroundTexture,
            frameTextureId = oesTextureId,
            maskTextureId = finalMaskFrameBuffers[readMaskIndex].textureId,
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            texMatrix = texMatrix,
            rotation = r,
            maskAspectRatio = maskAspectRatio
        )
        // Log.i(TAG, "finished drawing to $oesTextureId $backgroundTexture, ${finalMaskFrameBuffers[readMaskIndex].textureId}, ${bgTextureFrameBuffers.first.textureId} ${bgTextureFrameBuffers.second.textureId}")

    }

    /**
     * Thread-safe method to set the foreground mask.
     */
    fun updateMask(segmentationMask: MaskHolder) {
        newMask = segmentationMask
    }

    private fun updateMaskFrameBuffer(segmentationMask: MaskHolder) {

        val width = segmentationMask.width
        val height = segmentationMask.height
        maskWidth = width
        maskHeight = height

        anotherTempMaskFrameBuffer.setSize(width, height)
        tempMaskTextureFrameBuffer.setSize(width, height)
        finalMaskFrameBuffers[0].setSize(width, height)
        finalMaskFrameBuffers[1].setSize(width, height)

        // Upload the mask into a texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, anotherTempMaskFrameBuffer.textureId)
        checkNoError("bindMaskTexture")


        GLES20.glTexSubImage2D(
            /*target*/
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            /*format*/
            GLES30.GL_RED,
            /*type*/
            GLES20.GL_UNSIGNED_BYTE,
            segmentationMask.buffer,
        )

        checkNoError("updateMaskFrameBuffer")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val finalMaskBuffer = finalMaskFrameBuffers[writeMaskIndex]
        val frameBuffers = tempMaskTextureFrameBuffer to finalMaskBuffer
        val r: Int
        synchronized(rotationStateLock) {
            r = rotation
        }


        boxBlurShader.applyBlur(
            anotherTempMaskFrameBuffer.textureId, 2f, width, height, frameBuffers, r
        )

        // Swap indicies for next frame.
        swapMaskIndexes()
    }

    override fun drawRgb(
        p0: Int, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun drawYuv(
        p0: IntArray?, p1: FloatArray?, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun release() {
        if (initialized) {
        initialized = false
        compositeShader.release()
        blurShader.release()
        boxBlurShader.release()

        bgTextureFrameBuffers.first.release()
        bgTextureFrameBuffers.second.release()
        downSampler.release()

        anotherTempMaskFrameBuffer.release()
        tempMaskTextureFrameBuffer.release()
        finalMaskFrameBuffers.forEach {
            it.release()
        }
            
        }
    }

    companion object {

        val TAG = VirtualBackgroundTransformer::class.java.simpleName
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun checkNoError(message: String) {
        val error = GLES20.glGetError()
        var errorMessage = ""
        if (error != 0) {
            errorMessage = GLU.gluErrorString(error)
        }

        GlUtil.checkNoGLES2Error("$TAG.$message $errorMessage")
    }
}
