package com.filmmaster.app.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * 离屏 EGL 上下文（pbuffer 1x1），用于拍照后的 GPU 处理。
 * 优先尝试 GLES3；失败时降级到 GLES2（由调用方决定是否改用 CPU 路径）。
 */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 3 = GLES3, 2 = GLES2, 0 = 初始化失败 */
    var glVersion: Int = 0
        private set

    fun init(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        var setup = createClient(3) ?: createClient(2)
        if (setup == null) {
            release()
            return false
        }
        val config = setup.first
        context = setup.second
        glVersion = if (isEs3Context(config)) 3 else 2

        val attribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        surface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            release()
            return false
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            release()
            return false
        }
        return true
    }

    private fun createClient(clientVersion: Int): Pair<EGLConfig, EGLContext>? {
        val renderableType = if (clientVersion >= 3) EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) return null
        if (numConfigs[0] <= 0) return null
        val config = configs[0] ?: return null

        val ctx = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE),
            0
        )
        if (ctx == EGL14.EGL_NO_CONTEXT) return null
        return config to ctx
    }

    private fun isEs3Context(config: EGLConfig): Boolean {
        // eglQueryContext 无直接版本查询；用配置的 EGL_RENDERABLE_TYPE 判断
        val value = IntArray(1)
        EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_RENDERABLE_TYPE, value, 0)
        return (value[0] and EGL_OPENGL_ES3_BIT_KHR) != 0
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }

    companion object {
        // EGL_OPENGL_ES3_BIT_KHR，EGL14 未提供常量
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    }
}
