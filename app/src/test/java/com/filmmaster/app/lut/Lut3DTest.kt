package com.filmmaster.app.lut

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Lut3DTest {

    private val identityCube = """
        # 2x2x2 identity LUT (R fastest, then G, then B)
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun parsesIdentityCube() {
        val lut = Lut3D.parseCube(identityCube)
        assertEquals(2, lut.size)
        assertEquals(24, lut.copyData().size)
    }

    @Test
    fun trilinearSampleIsExactForIdentityLut() {
        val lut = Lut3D.parseCube(identityCube)
        val out = lut.sample(0.25f, 0.5f, 0.75f)
        assertEquals(0.25f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(0.75f, out[2], 1e-4f)
    }

    @Test
    fun sampleClampsOutsideGamut() {
        val lut = Lut3D.parseCube(identityCube)
        val out = lut.sample(-1f, 2f, 0.5f)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
    }

    @Test
    fun applyArgbRespectsAlphaAndStrength() {
        // 全黑 LUT
        val blackLut = Lut3D(2, FloatArray(24))
        val src = intArrayOf(0x80112233.toInt())

        val full = blackLut.applyArgb(src, 1f)
        assertEquals(0, full[0] and 0x00FFFFFF)
        assertEquals(0x80L, ((full[0] ushr 24) and 0xFF).toLong())

        val zero = blackLut.applyArgb(src, 0f)
        assertEquals(src[0], zero[0])

        val half = blackLut.applyArgb(src, 0.5f)
        assertEquals(9, (half[0] shr 16) and 0xFF)
        assertEquals(17, (half[0] shr 8) and 0xFF)
        assertEquals(26, half[0] and 0xFF)
    }

    @Test
    fun parseRejectsIncompleteData() {
        try {
            Lut3D.parseCube("LUT_3D_SIZE 2\n0 0 0\n")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("data mismatch") ||
                expected.message!!.contains("Missing"))
        }
    }

    @Test
    fun proceduralLooksAreValidAndDistinct() {
        val looks = ProceduralLuts.looks
        assertEquals(3, looks.size)
        looks.forEach { look ->
            assertEquals(ProceduralLuts.SIZE, look.lut.size)
            val mid = look.lut.sample(0.5f, 0.5f, 0.5f)
            mid.forEach { assertTrue("LUT value must be in [0,1]", it in 0f..1f) }
        }
        val warm = ProceduralLuts.looks[0].lut.sample(0.9f, 0.5f, 0.3f)
        val faded = ProceduralLuts.looks[2].lut.sample(0.9f, 0.5f, 0.3f)
        assertTrue("presets should produce different output",
            abs(warm[0] - faded[0]) > 1e-3f)
    }
}
