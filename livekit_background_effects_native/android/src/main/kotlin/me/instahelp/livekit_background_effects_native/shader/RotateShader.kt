/*
 * Copyright 2026 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.instahelp.livekit_background_effects_native.shader

import android.opengl.GLES11Ext
import android.opengl.GLES20
import org.webrtc.GlShader
import org.webrtc.GlUtil

private const val ROTATE_OES_FRAGMENT_SHADER_SOURCE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 texCoords;
uniform samplerExternalOES u_texture;
out vec4 fragColor;

void main() {
    fragColor = texture(u_texture, texCoords);
}
"""

private const val ROTATE_2D_FRAGMENT_SHADER_SOURCE = """#version 300 es
precision mediump float;
in vec2 texCoords;
uniform sampler2D u_texture;
out vec4 fragColor;

void main() {
    fragColor = texture(u_texture, texCoords);
}
"""

/**
 * Copies a texture into the currently bound framebuffer while rotating it by a multiple of
 * 90 degrees. Texture coordinates are used as-is (no flipping), i.e. texel row 0 of the
 * target ends up in texel row 0 of the source for rotation 0.
 */
internal class RotateShader private constructor(
    private val shader: GlShader,
    private val textureTarget: Int,
) {
    private val texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME)
    private val inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME)
    private val inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME)
    private val textureLocation = shader.getUniformLocation("u_texture")

    /**
     * Draws [textureId] into the framebuffer [frameBufferId] of size [width]x[height].
     *
     * Target texel (u, v) samples the source at the source coordinates obtained by rotating
     * the image by [rotation] degrees clockwise, where v = 0 is the first image row.
     */
    fun draw(textureId: Int, rotation: Int, frameBufferId: Int, width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId)
        GLES20.glViewport(0, 0, width, height)
        shader.useProgram()
        ShaderUtil.loadCoordMatrix(
            inPosLocation = inPosLocation,
            inPosFloats = FULL_RECTANGLE_BUFFER,
            inTcLocation = inTcLocation,
            inTcFloats = FULL_RECTANGLE_TEXTURE_BUFFER,
            texMatrixLocation = texMatrixLocation,
            texMatrix = rotationMatrix(rotation),
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glUniform1i(textureLocation, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(textureTarget, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GlUtil.checkNoGLES2Error("RotateShader.draw")
    }

    fun release() {
        shader.release()
    }

    companion object {
        fun forOes() = RotateShader(
            GlShader(DEFAULT_VERTEX_SHADER_SOURCE, ROTATE_OES_FRAGMENT_SHADER_SOURCE),
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        )

        fun for2d() = RotateShader(
            GlShader(DEFAULT_VERTEX_SHADER_SOURCE, ROTATE_2D_FRAGMENT_SHADER_SOURCE),
            GLES20.GL_TEXTURE_2D,
        )

        /**
         * Column-major 4x4 matrix mapping target coordinates (u, v) to source coordinates (s, t)
         * for an image rotated clockwise by [rotation] degrees:
         *   0:   (s, t) = (u, v)
         *   90:  (s, t) = (v, 1 - u)
         *   180: (s, t) = (1 - u, 1 - v)
         *   270: (s, t) = (1 - v, u)
         */
        fun rotationMatrix(rotation: Int): FloatArray {
            // s = a*u + b*v + c, t = d*u + e*v + f
            val (a, b, c) = when (rotation) {
                0 -> Triple(1f, 0f, 0f)
                90 -> Triple(0f, 1f, 0f)
                180 -> Triple(-1f, 0f, 1f)
                270 -> Triple(0f, -1f, 1f)
                else -> throw IllegalArgumentException("invalid rotation $rotation")
            }
            val (d, e, f) = when (rotation) {
                0 -> Triple(0f, 1f, 0f)
                90 -> Triple(-1f, 0f, 1f)
                180 -> Triple(0f, -1f, 1f)
                else -> Triple(1f, 0f, 0f)
            }
            return floatArrayOf(
                a, d, 0f, 0f,
                b, e, 0f, 0f,
                0f, 0f, 1f, 0f,
                c, f, 0f, 1f,
            )
        }
    }
}
