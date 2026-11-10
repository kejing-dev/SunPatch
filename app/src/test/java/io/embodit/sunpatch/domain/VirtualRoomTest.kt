package io.embodit.sunpatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The virtual room (taste profile D7 / D9, design `virtualRoom.defaults`): 4 x 4 x 2.7 m, user 1 m behind its centre. */
class VirtualRoomTest {
    private fun headAt(x: Float, y: Float, z: Float, yawDeg: Float): HeadPose =
        HeadPose(Vec3(x, y, z), Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()))

    @Test
    fun defaultsMatchTheDesign() {
        val v = VirtualRoom.around(headAt(0f, 1.6f, 0f, 0f), floorY = null)
        assertEquals(4f, v.width, 1e-6f)
        assertEquals(4f, v.depth, 1e-6f)
        assertEquals(2.7f, v.height, 1e-6f)
        assertEquals(0f, v.floorY, 1e-4f)
        val planes = v.planes()
        assertEquals(4, planes.count { it.semantic == "WALL" })
        val table = planes.single { it.semantic == "TABLE" }
        assertEquals(0.42f, table.vertices.first().y, 1e-4f)
        // front wall 3 m ahead, back wall 1 m behind
        assertEquals(-3f, planes.single { it.id == "${VirtualRoom.ID_WALL}2" }.vertices.first().z, 1e-4f)
        assertEquals(1f, planes.single { it.id == "${VirtualRoom.ID_WALL}0" }.vertices.first().z, 1e-4f)
    }

    @Test
    fun followsTheHeadPositionAndYawOnly() {
        for (yaw in listOf(0f, 90f, 180f, -135f)) {
            for (pitch in listOf(-30f, 0f, 30f)) {
                val q = Quat4.axisAngle(Vec3.UP, Math.toRadians(yaw.toDouble()).toFloat()) *
                    Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitch.toDouble()).toFloat())
                val h = HeadPose(Vec3(3f, 1.5f, -2f), q)
                val v = VirtualRoom.around(h, floorY = 0.1f)
                val ahead = Compass.dirOfYaw(yaw)
                assertEquals(ahead.x, v.ahead.x, 1e-3f)
                assertEquals(ahead.z, v.ahead.z, 1e-3f)
                assertEquals(3f + ahead.x, v.center.x, 1e-3f)
                assertEquals(0.1f, v.floorY, 1e-6f)
            }
        }
    }

    @Test
    fun resizeIsClamped() {
        val v = VirtualRoom.around(headAt(0f, 1.6f, 0f, 30f), floorY = 0f).resized(width = 0.5f, depth = 40f, height = 9f)
        assertEquals(VirtualRoom.MIN_SIDE_M, v.width, 1e-6f)
        assertEquals(VirtualRoom.MAX_SIDE_M, v.depth, 1e-6f)
        assertEquals(VirtualRoom.MAX_HEIGHT_M, v.height, 1e-6f)
        assertTrue(v.planes().isNotEmpty())
    }
}
