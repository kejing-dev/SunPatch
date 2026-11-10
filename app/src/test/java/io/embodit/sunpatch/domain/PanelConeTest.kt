package io.embodit.sunpatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * QA #1 q1-3: the main window opens 1.75 m ahead on the line of sight; with a window marked on the wall ahead it must
 * wait until the head is turned so far that the panel's drawn width (+ margin) misses the window, and a recall obeys
 * the same rule. Geometry = the QA path: virtual room 4 x 4 x 2.7, standard 1.5 x 1.5 m window on the front wall 3 m
 * ahead (corners from the round-1 dump), head at the origin.
 */
class PanelConeTest {
    private val head = Vec3(0f, 0f, 0f)
    private val front = listOf(Vec3(-0.75f, 2.801f, -3f), Vec3(0.75f, 2.801f, -3f), Vec3(0.75f, 1.301f, -3f), Vec3(-0.75f, 1.301f, -3f))
    private val left = front.map { Vec3(-2f, it.y, -it.x - 1f) } // on the left wall, centre 1 m ahead

    @Test
    fun facingTheWindowIsBlockedAndTheTurnClearsIt() {
        val t = PanelCone.turnFor(head, 0f, listOf(front))!!
        assertTrue("must turn, got $t", abs(t) > 30f)
        assertFalse(PanelCone.clear(head, 0f, listOf(front)))
        // after the turn the panel's yaw interval misses the window by the margin
        assertTrue(PanelCone.clear(head, t, listOf(front)))
        // and the window seen from that yaw lies wholly outside the panel's drawn half width + margin
        val (lo, hi) = PanelCone.span(head, t, front)!!
        val half = PanelCone.PANEL_HALF_DEG + PanelCone.MARGIN_DEG
        assertTrue("span $lo..$hi vs $half", hi < -half || lo > half)
    }

    @Test
    fun aboutFortyDegreesEitherSide() {
        // window half-angle 14 deg + drawn panel half width 16.8 deg + margin 8 deg = 38.9 deg
        for (yaw in listOf(40f, -40f, 45f, -60f, 90f, 180f)) assertTrue("yaw $yaw", PanelCone.clear(head, yaw, listOf(front)))
        for (yaw in listOf(0f, 10f, -10f, 25f, -25f, 37f, -37f)) assertFalse("yaw $yaw", PanelCone.clear(head, yaw, listOf(front)))
    }

    @Test
    fun smallestTurnPicksTheNearSide() {
        // looking 10 deg left of the window: turning further left is shorter
        val t = PanelCone.turnFor(head, 10f, listOf(front))!!
        assertTrue("left turn expected, got $t", t > 0f)
        val r = PanelCone.turnFor(head, -10f, listOf(front))!!
        assertTrue("right turn expected, got $r", r < 0f)
        assertEquals(t, -r, 0.5f)
    }

    @Test
    fun windowBehindOrNoWindowNeedsNoTurn() {
        assertEquals(0f, PanelCone.turnFor(head, 180f, listOf(front)))
        assertEquals(0f, PanelCone.turnFor(head, 0f, emptyList()))
        assertNull(PanelCone.span(head, 180f, front))
    }

    @Test
    fun twoWindowsTheTurnClearsBoth() {
        val t = PanelCone.turnFor(head, 0f, listOf(front, left))!!
        assertTrue(PanelCone.clear(head, t, listOf(front, left)))
        assertTrue("turn right away from both, got $t", t < 0f)
    }

    @Test
    fun windowsAllAroundGiveNull() {
        val ring = (0 until 12).map { i ->
            val a = Math.toRadians(i * 30.0)
            val c = Vec3((-2.0 * Math.sin(a)).toFloat(), 0f, (-2.0 * Math.cos(a)).toFloat())
            val r = Vec3(Math.cos(a).toFloat(), 0f, (-Math.sin(a)).toFloat()) * 0.7f
            listOf(c - r + Vec3(0f, 2f, 0f), c + r + Vec3(0f, 2f, 0f), c + r + Vec3(0f, 1f, 0f), c - r + Vec3(0f, 1f, 0f))
        }
        assertNull(PanelCone.turnFor(head, 0f, ring))
    }

    @Test
    fun gateWaitsWhileNotAsideThenOpens() {
        val g = LevelGate()
        g.ask("marked", 0)
        var t = 0L
        while (t <= 10_000) {
            assertNull("level but in front of the window must wait", g.step(t, 0f, aside = false))
            t += 16
        }
        assertTrue(g.waiting)
        var opened: String? = null
        while (t <= 11_000 && opened == null) {
            opened = g.step(t, 0f, aside = true)
            t += 16
        }
        assertNotNull(opened)
        assertEquals("marked", opened)
    }
}
