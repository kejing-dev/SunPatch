package io.embodit.sunpatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9 for the system window (QA #4 q4-3): the window is asked for only while the head is level, so a cold start or a
 * recall with the head down 30° / up 30° waits (hint on) and opens once the head comes back within ±10° for 300 ms.
 */
class LevelGateTest {
    private fun run(gate: LevelGate, from: Long, to: Long, pitch: (Long) -> Float?): Pair<Long, String>? {
        var t = from
        while (t <= to) {
            gate.step(t, pitch(t))?.let { return t to it }
            t += 16
        }
        return null
    }

    @Test
    fun levelHeadOpensAfterDwell() {
        val g = LevelGate()
        g.ask("stage", 0)
        val (at, why) = run(g, 0, 2000) { 2f }!!
        assertEquals("stage", why)
        assertTrue("dwell $at", at in 300L..340L)
        assertFalse(g.waiting)
    }

    @Test
    fun pitchedHeadWaitsThenOpensWhenLevel() {
        for (p in listOf(-30f, 30f, -11f, 11f)) {
            val g = LevelGate()
            g.ask("stage", 0)
            assertNull("pitch $p must wait", run(g, 0, 20_000) { p })
            assertTrue("hint on at $p", g.hintWanted(p))
            // head comes back to 5° at t = 20 s
            val (at, _) = run(g, 20_016, 25_000) { 5f * Math.signum(p) }!!
            assertTrue("opens 300 ms after level, at $at", at in 20_300L..20_350L)
            assertFalse(g.hintWanted(5f))
        }
    }

    @Test
    fun dwellRestartsWhenHeadDipsAgain() {
        val g = LevelGate()
        g.ask("recall", 0)
        // level 0-200 ms, down at 200-400, level again from 400
        val r = run(g, 0, 3000) { t -> if (t in 200L until 400L) -25f else 0f }!!
        assertTrue("opens 300 ms after the second level, at ${r.first}", r.first >= 400 + 300 - 16)
        assertEquals("recall", r.second)
    }

    @Test
    fun noHeadPoseOpensAfterTimeoutWithoutHint() {
        val g = LevelGate()
        g.ask("stage", 0)
        assertFalse(g.hintWanted(null))
        val (at, why) = run(g, 0, 10_000) { null }!!
        assertTrue("no-head fallback at $at", at in 5000L..5020L)
        assertEquals("stage/nohead", why)
    }

    @Test
    fun noHeadTimeCountsFromTheFirstFrameNotTheAsk() {
        // headset: asked at launch, the Stage's first frame 6 s later with the head already pitched down 30°
        val g = LevelGate()
        g.ask("stage", 0)
        assertNull(run(g, 6000, 6100) { null })
        assertNull("pitched head after the late first frame still waits", run(g, 6116, 30_000) { -30f })
        assertTrue(g.waiting)
    }

    @Test
    fun nothingAskedNeverOpens() {
        val g = LevelGate()
        assertNull(run(g, 0, 5000) { 0f })
        assertFalse(g.hintWanted(40f))
    }

    @Test
    fun cancelStopsTheWait() {
        val g = LevelGate()
        g.ask("stage", 0)
        g.cancel()
        assertNull(run(g, 0, 5000) { 0f })
    }
}
