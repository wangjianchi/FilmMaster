package com.filmmaster.app.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.filmmaster.app.lut.Lut3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 成片后的 3D LUT GPU 处理（离屏 EGL + FBO）。
 *
 * 管线位置：JPEG 解码 → 旋转 →（此处）LUT + 强度 → JPEG 编码。
 * M1 只在成片路径工作；预览仍为相机直通。M2 起会把该 shader 扩展为线性空间管线。
 *
 * 设备无 GLES3 / EGL 失败时自动降级到 [Lut3D.applyArgb] 的 CPU 路径。
 */
class LutGlProcessor {

    fun process(
        src: Bitmap,
        lut: Lut3D,
        strength: Float,
        grain: Float = 0f,
        vignette: Float = 0f,
        seed: Int = 0
    ): Bitmap {
        val gpu = runCatching { processGpu(src, lut, strength, grain, vignette, seed) }
        gpu.getOrNull()?.let { return it }
        Log.w(TAG, "GPU LUT path failed, falling back to CPU: ${gpu.exceptionOrNull()?.message}")
        return processCpu(src, lut, strength, grain, vignette, seed)
    }

    private fun processGpu(
        src: Bitmap,
        lut: Lut3D,
        strength: Float,
        grain: Float,
        vignette: Float,
        seed: Int
    ): Bitmap {
        val egl = EglCore()
        check(egl.init()) { "EGL init failed" }
        if (egl.glVersion < 3) {
            egl.release()
            throw IllegalStateException("GLES3 is required for LUT shader")
        }

        val srcTexture = IntArray(1)
        val lutTexture = IntArray(1)
        val fboTexture = IntArray(1)
        val fboIds = IntArray(1)
        val vboIds = IntArray(1)
        var program = 0

        try {
            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            val limit = if (maxTexture[0] > 0) maxTexture[0] else 4096
            val input = if (src.width > limit || src.height > limit) {
                val scale = limit.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                src
            }
            val width = input.width
            val height = input.height

            program = GlUtil.createProgram(VERTEX_SHADER, fragmentShader(lut.size))

            // 输入图像纹理（unit 0）
            GLES30.glGenTextures(1, srcTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexture[0])
            set2DParams()
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, input, 0)

            // 3D LUT 纹理（unit 1）
            GLES30.glGenTextures(1, lutTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            val lutData = lut.copyData()
            val lutBuffer = FloatBuffer.allocate(lutData.size)
            lutBuffer.put(lutData)
            lutBuffer.position(0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GL_RGB16F,
                lut.size, lut.size, lut.size, 0,
                GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuffer
            )

            // 输出纹理 + FBO
            GLES30.glGenTextures(1, fboTexture, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture[0])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            set2DParams()

            GLES30.glGenFramebuffers(1, fboIds, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, fboTexture[0], 0
            )
            val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            check(fboStatus == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Framebuffer incomplete: 0x${Integer.toHexString(fboStatus)}"
            }

            // 全屏四边形（triangle strip）
            val vertices = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
            GLES30.glGenBuffers(1, vboIds, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[0])
            val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            vertexBuffer.put(vertices)
            vertexBuffer.position(0)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, vertices.size * 4,
                vertexBuffer, GLES30.GL_STATIC_DRAW
            )

            GLES30.glUseProgram(program)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uImage"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(program, "uStrength"),
                strength.coerceIn(0f, 1f)
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(program, "uGrain"),
                grain.coerceIn(0f, 1f)
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(program, "uVignette"),
                vignette.coerceIn(0f, 1f)
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(program, "uSeed"),
                seed.toFloat()
            )

            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            // GL 原点在左下，FBO 行 0 对应 bitmap 顶部（与 texImage2D 的上传方向相抵），无需翻转
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(
                0, 0, width, height,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels
            )
            pixels.position(0)

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(pixels)
            return out
        } finally {
            if (program != 0) GLES30.glDeleteProgram(program)
            deleteTexture(srcTexture)
            deleteTexture(lutTexture)
            deleteTexture(fboTexture)
            if (fboIds[0] != 0) GLES30.glDeleteFramebuffers(1, fboIds, 0)
            if (vboIds[0] != 0) GLES30.glDeleteBuffers(1, vboIds, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            egl.release()
        }
    }

    private fun processCpu(
        src: Bitmap,
        lut: Lut3D,
        strength: Float,
        grain: Float,
        vignette: Float,
        seed: Int
    ): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val graded = lut.applyArgb(pixels, strength)
        if (grain <= 0f && vignette <= 0f) {
            return Bitmap.createBitmap(graded, width, height, Bitmap.Config.ARGB_8888)
        }

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = kotlin.math.sqrt(cx * cx + cy * cy)
        for (i in graded.indices) {
            val p = graded[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF

            if (vignette > 0f) {
                val x = (i % width) - cx
                val y = (i / width) - cy
                val d = kotlin.math.sqrt(x * x + y * y) / maxRadius
                val fall = 1f - vignette * smoothstep(0.25f, 1.15f, d)
                r = (r * fall).toInt().coerceIn(0, 255)
                g = (g * fall).toInt().coerceIn(0, 255)
                b = (b * fall).toInt().coerceIn(0, 255)
            }
            if (grain > 0f) {
                val n = (noiseAt(i, seed) * grain * 14f).toInt()
                r = (r + n).coerceIn(0, 255)
                g = (g + n).coerceIn(0, 255)
                b = (b + n).coerceIn(0, 255)
            }
            graded[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(graded, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun noiseAt(index: Int, seed: Int): Float {
        var x = index + seed * 0x9E3779B9.toInt()
        x = x xor (x ushr 16)
        x *= 0x7FEB352D
        x = x xor (x ushr 15)
        x *= 0x846CA68B.toInt()
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f * 2f - 1f
    }

    private fun set2DParams() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun deleteTexture(id: IntArray) {
        if (id[0] != 0) {
            GLES30.glDeleteTextures(1, id, 0)
            id[0] = 0
        }
    }

    private fun fragmentShader(lutSize: Int): String =
        FRAGMENT_SHADER.replace("%LUT_SIZE%", lutSize.toString())

    companion object {
        private const val TAG = "LutGlProcessor"

        // GLES30 未导出 GL_RGB16F 常量时的手动定义（值为 0x881B）
        private const val GL_RGB16F = 0x881B

        private val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            precision highp sampler3D;
            in vec2 vTexCoord;
            uniform sampler2D uImage;
            uniform sampler3D uLut;
            uniform float uStrength;
            uniform float uGrain;
            uniform float uVignette;
            uniform float uSeed;
            out vec4 outColor;
            const float LUT_SIZE = %LUT_SIZE%.0;

            float hash(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            void main() {
                vec4 src = texture(uImage, vTexCoord);
                vec3 c = clamp(src.rgb, 0.0, 1.0);
                // 采样点对齐到 LUT texel 中心，避免半格偏移
                vec3 coord = (c * (LUT_SIZE - 1.0) + 0.5) / LUT_SIZE;
                vec3 graded = texture(uLut, coord).rgb;
                vec3 rgb = mix(src.rgb, graded, uStrength);

                if (uGrain > 0.0) {
                    float n = hash(gl_FragCoord.xy + uSeed) - 0.5;
                    rgb += n * uGrain * 0.22;
                }
                if (uVignette > 0.0) {
                    vec2 d = (vTexCoord - vec2(0.5)) / vec2(0.5);
                    float r = length(d) / 1.41421356;
                    float fall = 1.0 - uVignette * smoothstep(0.25, 1.15, r);
                    rgb *= fall;
                }
                outColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
            }
        """.trimIndent()
    }
}
