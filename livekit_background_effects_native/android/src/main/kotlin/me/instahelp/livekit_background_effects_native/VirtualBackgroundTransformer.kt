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
import me.instahelp.livekit_background_effects_native.shader.RotateShader
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
 *
 * Coordinate spaces: the composite samples background and mask in the camera frame's
 * unrotated texture space (raw OES coordinates). The segmenter, however, gets an upright
 * image ([readbackUpright]); [uploadMask] rotates its result back into the frame's space.
 */
class VirtualBackgroundTransformer(
    val downSampleFactor: Int = 2,
) : RendererCommon.GlDrawer {

    private val TAG = VirtualBackgroundTransformer::class.java.simpleName

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

    // Mask pipeline: the upright mask is uploaded, softened with a box blur and then rotated
    // back into the frame's (unrotated) texture space, where the composite samples it.
    private lateinit var maskUploadFrameBuffer: LKGlTextureFrameBuffer
    private lateinit var maskBlurFrameBuffers: Pair<GlTextureFrameBuffer, GlTextureFrameBuffer>
    private lateinit var finalMaskFrameBuffer: GlTextureFrameBuffer

    // Upright, downscaled copy of the camera frame that is fed to the segmenter.
    private lateinit var readbackFrameBuffer: GlTextureFrameBuffer
    private lateinit var rotateOes: RotateShader
    private lateinit var rotate2d: RotateShader

    var initialized = false

    /**
     * Framebuffer the final composite is rendered into. The intermediate passes
     * (blur, resampling) bind their own framebuffers and reset to 0 afterwards,
     * so this has to be re-bound right before compositing.
     */
    var targetFrameBufferId = 0

    private fun ensureInitialized() {
        if (!initialized) {
            initialize()
        }
    }

    private fun initialize() {
        Log.i(TAG, "initialize shaders")
        compositeShader = createCompsiteShader()
        blurShader = createBlurShader()
        boxBlurShader = createBoxBlurShader()

        bgTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)
        frameTexture = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D)

        bgTextureFrameBuffers =
            GlTextureFrameBuffer(GLES30.GL_RGBA) to GlTextureFrameBuffer(GLES30.GL_RGBA)

        downSampler = createResampler()

        maskUploadFrameBuffer =
            LKGlTextureFrameBuffer(GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE)
        maskBlurFrameBuffers =
            GlTextureFrameBuffer(GLES20.GL_RGBA) to GlTextureFrameBuffer(GLES20.GL_RGBA)
        finalMaskFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)

        readbackFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
        rotateOes = RotateShader.forOes()
        rotate2d = RotateShader.for2d()

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
        ensureInitialized()
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

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFrameBufferId)
        compositeShader.renderComposite(
            backgroundTextureId = backgroundTexture,
            frameTextureId = oesTextureId,
            maskTextureId = finalMaskFrameBuffer.textureId,
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            texMatrix = texMatrix,
            rotation = r,
        )

    }

    /**
     * Rotates the camera frame upright, downscales it to [width]x[height] and reads it back
     * as RGBA into [out] (row 0 = top of the upright image).
     *
     * [width] and [height] are the upright (rotated) dimensions.
     */
    fun readbackUpright(oesTextureId: Int, rotation: Int, width: Int, height: Int, out: ByteBuffer) {
        ensureInitialized()
        readbackFrameBuffer.setSize(width, height)
        rotateOes.draw(oesTextureId, rotation, readbackFrameBuffer.frameBufferId, width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readbackFrameBuffer.frameBufferId)
        out.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkNoError("readbackUpright")
    }

    /**
     * Uploads a mask produced from [readbackUpright] for a frame with the given [rotation].
     */
    internal fun uploadMask(mask: PersonSegmenter.Mask, rotation: Int) {
        ensureInitialized()
        val width = mask.width
        val height = mask.height

        maskUploadFrameBuffer.setSize(width, height)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskUploadFrameBuffer.textureId)
        // Rows of a one-byte-per-pixel mask are not necessarily 4-byte aligned.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        mask.buffer.rewind()
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            width,
            height,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            mask.buffer,
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        checkNoError("uploadMask")

        // Soften the mask in upright space; the blur is rotation independent.
        val blurred = boxBlurShader.applyBlur(
            maskUploadFrameBuffer.textureId, 2f, width, height, maskBlurFrameBuffers, 0
        )

        // Rotate back into the unrotated frame space used by the composite.
        val unrotatedWidth = if (rotation % 180 == 0) width else height
        val unrotatedHeight = if (rotation % 180 == 0) height else width
        finalMaskFrameBuffer.setSize(unrotatedWidth, unrotatedHeight)
        rotate2d.draw(
            blurred,
            (360 - rotation) % 360,
            finalMaskFrameBuffer.frameBufferId,
            unrotatedWidth,
            unrotatedHeight,
        )
    }

    /**
     * Sets a mask without any person, so the whole frame shows the background.
     */
    fun clearMask() {
        ensureInitialized()
        finalMaskFrameBuffer.setSize(1, 1)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, finalMaskFrameBuffer.frameBufferId)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkNoError("clearMask")
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

        maskUploadFrameBuffer.release()
        maskBlurFrameBuffers.first.release()
        maskBlurFrameBuffers.second.release()
        finalMaskFrameBuffer.release()
        readbackFrameBuffer.release()
        rotateOes.release()
        rotate2d.release()
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
