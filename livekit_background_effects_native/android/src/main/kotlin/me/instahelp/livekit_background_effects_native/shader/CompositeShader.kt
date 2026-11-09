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

package me.instahelp.livekit_background_effects_native.shader

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import org.webrtc.GlShader
import org.webrtc.GlUtil

private const val COMPOSITE_FRAGMENT_SHADER_SOURCE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 texCoords;
uniform sampler2D background;
uniform sampler2D mask;
uniform samplerExternalOES frame;
uniform float rotations[6];
out vec4 fragColor;

vec2 rotateCoordinates(vec2 coords) {
    return vec2(
        coords.x * rotations[0] + coords.y * rotations[1] + rotations[2],
        coords.x * rotations[3] + coords.y * rotations[4] + rotations[5]
    );
}

void main() {
    vec2 rotatedCoords = rotateCoordinates(texCoords);
    vec4 frameTex = texture(frame, rotatedCoords);
    vec4 bgTex = texture(background, rotatedCoords);

    // The mask is generated from this very frame, so it covers exactly the same area.
    float maskVal = texture(mask, rotatedCoords).r;

    // Screen-space gradient of the mask: large at the person's outline, ~0 elsewhere.
    float grad = length(vec2(dFdx(maskVal), dFdy(maskVal)));

    // Turn the confidence into a mostly binary alpha with an anti-aliased edge whose width
    // follows the gradient. Higher = softer edge. The lower bound keeps smoothstep defined
    // (edge0 < edge1) in flat areas.
    float edgeSoftness = 2.0;
    float edgeWidth = max(grad * edgeSoftness, 0.02);
    float alpha = smoothstep(0.5 - edgeWidth, 0.5 + edgeWidth, maskVal);

    fragColor = mix(bgTex, vec4(frameTex.rgb, 1.0), alpha);
}
"""

internal fun createCompsiteShader(): CompositeShader {
    val shader = GlShader(DEFAULT_VERTEX_SHADER_SOURCE, COMPOSITE_FRAGMENT_SHADER_SOURCE)

    return CompositeShader(
        shader = shader,
        texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME),
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME),
        mask = shader.getUniformLocation("mask"),
        frame = shader.getUniformLocation("frame"),
        background = shader.getUniformLocation("background"),
        rotations = shader.getUniformLocation("rotations"),
    )
}

internal data class CompositeShader(
    val shader: GlShader,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texMatrixLocation: Int,
    val mask: Int,
    val frame: Int,
    val background: Int,
    val rotations: Int,
) {
    private val TAG = "CompositeShader"

    fun renderComposite(
        backgroundTextureId: Int,
        frameTextureId: Int,
        maskTextureId: Int,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        texMatrix: FloatArray,
        rotation: Int,
    ) {
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Set up uniforms for the composite shader
        shader.useProgram()

        ShaderUtil.loadCoordMatrix(
            inPosLocation = inPosLocation,
            inPosFloats = FULL_RECTANGLE_BUFFER,
            inTcLocation = inTcLocation,
            inTcFloats = FULL_RECTANGLE_TEXTURE_BUFFER,
            texMatrixLocation = texMatrixLocation,
            texMatrix = texMatrix,
        )
        GlUtil.checkNoGLES2Error("loadCoordMatrix")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTextureId)
        GLES20.glUniform1i(background, 0)
        GlUtil.checkNoGLES2Error("GL_TEXTURE0")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTextureId)
        GLES20.glUniform1i(mask, 1)
        GlUtil.checkNoGLES2Error("GL_TEXTURE1")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTextureId)
        GLES20.glUniform1i(frame, 2)
        GlUtil.checkNoGLES2Error("GL_TEXTURE2")

        val xx: Float
        val xy: Float
        val x: Float
        val yx: Float
        val yy: Float
        val y: Float
        when (rotation) {
            0 -> {
                xx = 1f
                xy = 0f
                x = 0f
                yx = 0f
                yy = 1f
                y = 0f
            }
            90 -> {
                // TODO verify values
                xx = 0f
                xy = -1f
                x = 1f
                yx = -1f
                yy = 0f
                y = 1f
            }
            180 -> {
                xx = -1f
                xy = 0f
                x = 1f
                yx = 0f
                yy = -1f
                y = 1f
            }
            270 -> {
                xx = 0f
                xy = 1f
                x = 0f
                yx = -1f
                yy = 0f
                y = 1f
            }
            else -> {
                throw RuntimeException("invalid rotation $rotation")
            }
        }

        GLES20.glUniform1fv(rotations, 6, floatArrayOf(
            xx,xy,x,yx,yy,y
        ), 0)

        // Draw composite
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkNoGLES2Error("GL_TRIANGLE_STRIP")

        // Cleanup
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GlUtil.checkNoGLES2Error("renderComposite")
    }

    fun release() {
        shader.release()
    }
}
