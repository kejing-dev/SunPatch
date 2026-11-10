package io.embodit.sunpatch.domain

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Label plates read the right way round from every side (0.1.1: the 0.1.0 tags were upside down on the headset). */
class BillboardTest {
    private val eps = 1e-3f

    /** The viewer's right when looking from [eye] at [target] (Stage: +Y up, right = forward x up). */
    private fun viewerRight(eye: Vec3, target: Vec3): Vec3 {
        val f = Vec3(target.x - eye.x, 0f, target.z - eye.z).normalized()
        return f.cross(Vec3.UP).normalized()
    }

    private fun viewers(center: Vec3): List<Vec3> =
        (0 until 16).flatMap { i ->
            val a = i * Math.PI.toFloat() / 8f
            // eye level, looking up at a ceiling label, looking down at a coffee table
            listOf(-1.2f, 0f, 1.5f).map { dy -> center + Vec3(sin(a) * 2f, dy, cos(a) * 2f) }
        }

    @Test
    fun facesTheViewerAboutTheVerticalOnly() {
        val center = Vec3(0.4f, 0.75f, -1.3f)
        for (eye in viewers(center)) {
            val q = Billboard.yawTowards(center, eye)
            val x = q.rotate(Vec3.RIGHT)
            val y = q.rotate(Vec3.UP)
            val z = q.rotate(Vec3(0f, 0f, 1f))
            assertEquals("no pitch / roll: up stays up", 1f, y.dot(Vec3.UP), eps)
            val toEye = Vec3(eye.x - center.x, 0f, eye.z - center.z).normalized()
            assertEquals("front (+Z) towards the viewer", 1f, z.dot(toEye), eps)
            assertEquals("plate +X is the viewer's right", 1f, x.dot(viewerRight(eye, center)), eps)
            assertTrue("right-handed, not mirrored", x.cross(y).dot(z) > 1f - eps)
        }
    }

    @Test
    fun quadFrontIsCounterClockwiseFromTheViewer() {
        val c = Billboard.CORNERS
        val t = Billboard.TRIANGLES
        for (k in t.indices step 3) {
            val n = (c[t[k + 1]] - c[t[k]]).cross(c[t[k + 2]] - c[t[k]])
            assertTrue("triangle $k faces +Z", n.z > 0f)
        }
    }

    @Test
    fun bitmapTopRowIsOnTopAndLeftColumnOnTheLeft() {
        val c = Billboard.CORNERS
        val uv = Billboard.UV
        for (i in c.indices) {
            val (u, v) = uv[i]
            val row = Billboard.bitmapRow(v)
            if (c[i].y > 0f) assertEquals("top corners sample the bitmap's first row", 0f, row, eps) else assertEquals("bottom corners sample the last row", 1f, row, eps)
            if (c[i].x < 0f) assertEquals("left corners sample the first column", 0f, u, eps) else assertEquals("right corners sample the last column", 1f, u, eps)
        }
    }

    @Test
    fun straightAboveOrBelowKeepsAReadableDefault() {
        val center = Vec3(0f, 2.7f, 0f)
        val q = Billboard.yawTowards(center, Vec3(0f, 1.6f, 0f))
        val y = q.rotate(Vec3.UP)
        val x = q.rotate(Vec3.RIGHT)
        val z = q.rotate(Vec3(0f, 0f, 1f))
        assertEquals(1f, y.dot(Vec3.UP), eps)
        assertTrue(x.cross(y).dot(z) > 1f - eps)
    }
}
