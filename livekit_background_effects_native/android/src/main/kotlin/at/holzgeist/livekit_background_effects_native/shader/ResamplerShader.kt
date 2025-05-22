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

package at.holzgeist.livekit_background_effects_native.shader

import android.opengl.GLES11Ext
import android.opengl.GLES20
import at.holzgeist.livekit_background_effects_native.shader.VERTEX_SHADER_POS_COORD_NAME
import at.holzgeist.livekit_background_effects_native.shader.VERTEX_SHADER_TEX_COORD_NAME
import at.holzgeist.livekit_background_effects_native.shader.VERTEX_SHADER_TEX_MAT_NAME
import org.webrtc.GlShader
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.GlUtil

private const val DOWNSAMPLER_VERTEX_SHADER_SOURCE = """#version 300 es
in vec4 in_pos;
in vec4 in_tc;
uniform mat4 tex_mat;
out vec2 v_uv;
void main() {
    v_uv = (tex_mat * in_tc).xy;
    gl_Position = in_pos;
}
"""

private const val DOWNSAMPLER_FRAGMENT_SHADER_SOURCE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 v_uv;
uniform samplerExternalOES u_texture;
out vec4 fragColor;

void main() {
    fragColor = texture(u_texture, v_uv);
}
"""

// Vertex coordinates in Normalized Device Coordinates, i.e. (-1, -1) is bottom-left and (1, 1)
// is top-right.
internal val FULL_RECTANGLE_BUFFER = GlUtil.createFloatBuffer(
    floatArrayOf(
        -1.0f,
        -1.0f, // Bottom left.
        1.0f,
        -1.0f, // Bottom right.
        -1.0f,
        1.0f, // Top left.
        1.0f,
        1.0f, // Top right.
    ),
)

// Texture coordinates - (0, 0) is bottom-left and (1, 1) is top-right.
internal val FULL_RECTANGLE_TEXTURE_BUFFER = GlUtil.createFloatBuffer(
    floatArrayOf(
        0.0f,
        0.0f, // Bottom left.
        1.0f,
        0.0f, // Bottom right.
        0.0f,
        1.0f, // Top left.
        1.0f,
        1.0f, // Top right.
    ),
)

internal fun createResampler(): ResamplerShader {
    val textureFrameBuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
    val shader = GlShader(DOWNSAMPLER_VERTEX_SHADER_SOURCE, DOWNSAMPLER_FRAGMENT_SHADER_SOURCE)

    return ResamplerShader(
        shader = shader,
        textureFrameBuffer = textureFrameBuffer,
        texMatrixLocation = shader.getUniformLocation(VERTEX_SHADER_TEX_MAT_NAME),
        inPosLocation = shader.getAttribLocation(VERTEX_SHADER_POS_COORD_NAME),
        inTcLocation = shader.getAttribLocation(VERTEX_SHADER_TEX_COORD_NAME),
        texture = shader.getUniformLocation("u_texture"),
    )
}

/**
 * A shader that resamples a texture at a new size.
 */
internal data class ResamplerShader(
    val shader: GlShader,
    val textureFrameBuffer: GlTextureFrameBuffer,
    val texMatrixLocation: Int,
    val inPosLocation: Int,
    val inTcLocation: Int,
    val texture: Int,
) {

    fun release() {
        shader.release()
        textureFrameBuffer.release()
    }

    fun resample(
        inputTextureId: Int,
        newWidth: Int,
        newHeight: Int,
        texMatrix: FloatArray,
    ): Int {
        textureFrameBuffer.setSize(newWidth, newHeight)

        shader.useProgram()

        GLES20.glViewport(0, 0, newWidth, newHeight)
        ShaderUtil.loadCoordMatrix(
            inPosLocation = inPosLocation,
            inPosFloats = FULL_RECTANGLE_BUFFER,
            inTcLocation = inTcLocation,
            inTcFloats = FULL_RECTANGLE_TEXTURE_BUFFER,
            texMatrixLocation = texMatrixLocation,
            texMatrix = texMatrix,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(texture, 0)
        GlUtil.checkNoGLES2Error("ResamplerShader.glBindTexture")

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, textureFrameBuffer.frameBufferId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkNoGLES2Error("ResamplerShader.glDrawArrays")

        // cleanup
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        GlUtil.checkNoGLES2Error("ResamplerShader.applyDownsampling")
        return textureFrameBuffer.textureId
    }
}
