package com.filmmaster.app.gl

import android.opengl.GLES30

internal object GlUtil {

    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            val typeName = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw IllegalStateException("$typeName shader compile failed: $log")
        }
        return shader
    }
}
