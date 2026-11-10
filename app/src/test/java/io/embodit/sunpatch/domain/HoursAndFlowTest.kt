package io.embodit.sunpatch.domain

import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hours map, day sweep, marker verdicts, "move in front of me" sampling (design `sunModel.hours/sweep/verdict`, D29, D38). */
class HoursAndFlowTest {
    private val june = LocalDate.parse("2026-06-21")
    private val december = LocalDate.parse("2026-12-21")

    private fun setup(facing: Float = 180f): SunSetup {
        val v = VirtualRoom.around(HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY), floorY = 0f)
        val inside = Vec3(v.center.x, 1f, v.center.z)
        val surfaces = v.planes().mapNotNull { p ->
            when (p.semantic) {
                "FLOOR" -> LitSurface.horizontal(p.id, p.vertices, true)
                "TABLE" -> LitSurface.horizontal(p.id, p.vertices, false)
                "WALL" -> LitSurface.wall(p.id, p.vertices, inside)
                else -> null
            }
        }
        val w = SunWindow.standard("w1", "${VirtualRoom.ID_WALL}2", Vec3(0f, 1f, -3f), 0f, Vec3(0f, 0f, -1f))
        return SunSetup(Cities.BEIJING, Compass.northYawFor(w.outward, facing), surfaces, listOf(w))
    }

    @Test fun hoursMapCountsFloorAndTablesNotWalls() {
        val s = setup()
        val t0 = System.nanoTime()
        val map = HoursMap.compute(s, december)
        val ms = (System.nanoTime() - t0) / 1e6
        println("HOURS compute december ${"%.0f".format(ms)} ms, >=4h ${map.areaAtLeast(4f)} m2")
        assertTrue(map.layers.keys.none { it.startsWith(VirtualRoom.ID_WALL) })
        assertTrue(map.areaAtLeast(1f) > 0.5f)
        assertTrue(map.areaAtLeast(2f) > 0f)
        assertTrue(map.areaAtLeast(4f) <= map.areaAtLeast(1f))
        // winter sun never lands 0.5 m from the window wall (it needs alt > 61 deg there)
        val floor = map.layers.getValue(VirtualRoom.ID_FLOOR)
        val f = floor.surface.frame
        fun hoursAt(p: Vec3): Float {
            val i = ((f.a(p)) / floor.cell).toInt()
            val j = ((f.b(p)) / floor.cell).toInt()
            return floor.hoursAt(i, j)
        }
        assertEquals(0f, hoursAt(Vec3(0f, 0f, -2.5f)), 1e-6f)
        assertTrue(hoursAt(Vec3(0f, 0f, 0.5f)) > 0f)
    }

    @Test fun sweepTrailEqualsTheHoursMapEvenAtFiveFps() {
        val s = setup()
        val minutes = s.dayMinutes(december).step(Solar.STEP_MIN).toList()
        val clock = SweepClock(minutes.size)
        val acc = HoursMap.Accumulator(s.surfaces, SunLight.HOURS_CELL_M)
        var elapsed = 0L
        var k = 0
        while (!clock.finished) {
            elapsed += 200 // 5 fps
            repeat(clock.due(elapsed)) { acc.add(s.frameAt(december, minutes[k++]), Solar.STEP_MIN.toFloat()) }
            assertTrue("sweep overran", elapsed <= 8000)
        }
        assertEquals(minutes.size, k)
        val trail = acc.result(december)
        val map = HoursMap.compute(s, december)
        val a = trail.cellsAtLeast(1f)
        val b = map.cellsAtLeast(1f)
        assertTrue(b > 0)
        assertTrue("trail $a map $b", abs(a - b) <= 0.02 * b)
    }

    @Test fun sweepClockNeverSkipsAndEndsAtEightSeconds() {
        val c = SweepClock(180)
        var sum = 0
        for (t in longArrayOf(16, 500, 501, 3000, 7999)) sum += c.due(t)
        assertEquals(179, sum)
        sum += c.due(8000)
        assertEquals(180, sum)
        assertTrue(c.finished)
        assertEquals(0, c.due(9000))
    }

    @Test fun markerStatsAndThresholds() {
        val s = setup()
        val near = MarkerStats.day(s, VirtualRoom.ID_FLOOR, Vec3(0.3f, 0f, 0.2f), december)
        assertTrue(near.hours > 0f)
        assertNotNull(near.first)
        assertTrue(near.last!! > near.first!!)
        assertEquals(near.hours * 60f, (near.last!! - near.first!!).toFloat(), 1f + near.hours * 60f) // sanity
        val months = MarkerStats.withMonths(s, VirtualRoom.ID_FLOOR, Vec3(0f, 0f, -2.5f), december).months
        assertEquals(12, months.size)
        assertTrue(months[5] > 0f) // summer noon sun lands 0.27-0.71 m from the wall
        assertEquals(0f, months[11], 1e-6f) // winter sun starts 1.8 m in
    }

    @Test fun verdictBandsAndHysteresis() {
        assertEquals(Verdict.OK, Verdicts.of(Want.MORE, 4f))
        assertEquals(Verdict.MEH, Verdicts.of(Want.MORE, 3.9f))
        assertEquals(Verdict.NO, Verdicts.of(Want.MORE, 1.9f))
        assertEquals(Verdict.OK, Verdicts.of(Want.LESS, 1f))
        assertEquals(Verdict.MEH, Verdicts.of(Want.LESS, 1.5f))
        assertEquals(Verdict.NO, Verdicts.of(Want.LESS, 2.1f))
        assertEquals(Verdict.NEUTRAL, Verdicts.of(Want.LOOK, 9f))
        // 5 min under the line keeps OK, 15 min under drops to MEH
        assertEquals(Verdict.OK, Verdicts.stable(Want.MORE, 4f - 5f / 60f, Verdict.OK))
        assertEquals(Verdict.MEH, Verdicts.stable(Want.MORE, 4f - 15f / 60f, Verdict.OK))
        assertEquals(Want.MORE, MarkerKind.PLANT.want)
        assertEquals(Want.LESS, MarkerKind.SCREEN.want)
        assertEquals(Want.LOOK, MarkerKind.OTHER.want)
    }

    @Test fun heatRampStops() {
        assertEquals(0, HeatRamp.argb(0f))
        assertEquals(0xF0963C, HeatRamp.argb(6f) and 0xFFFFFF)
        assertEquals(0xF2C96B, HeatRamp.argb(2f) and 0xFFFFFF)
        assertEquals((0.7f * 255).toInt(), HeatRamp.argb(1f) ushr 24)
    }

    /** D29: pressing while looking at a panel 30 deg off, then turning to straight ahead and holding, samples < 2 deg off. */
    @Test fun headSampleIgnoresThePanelDirection() {
        val s = HeadSampler()
        val pos = Vec3(0f, 1.6f, 0f)
        s.start(0)
        var got: HeadSampler.Sample? = null
        var t = 0L
        while (got == null && t < 6000) {
            val yaw = when {
                t < 600 -> 30f // still looking at the panel, reading the hint
                t < 1100 -> 30f - (t - 600) * 0.06f // turning at 60 deg/s
                else -> 0f
            }
            got = s.step(t, pos, yaw, 0f)
            t += 16
        }
        assertNotNull(got)
        assertTrue("sampled yaw ${got!!.yawDeg}", abs(got.yawDeg) < 2f)
        assertTrue(!got.timedOut)
    }

    @Test fun headSampleWaitsWhileTurningAndTimesOut() {
        val s = HeadSampler()
        s.start(0)
        var got: HeadSampler.Sample? = null
        var t = 0L
        while (got == null && t < 6000) {
            got = s.step(t, Vec3.ZERO, 0f, 0f, yawRateOverride = 30f)
            t += 16
        }
        assertNotNull(got)
        assertTrue(got!!.timedOut)
        assertTrue(t >= 5000)
        // looking down 30 deg never counts as level
        val d = HeadSampler()
        d.start(0)
        for (k in 0..200) assertEquals(null, d.step(k * 16L, Vec3.ZERO, 0f, -30f).takeIf { k * 16L < 5000 })
    }

    @Test fun uprightPlacementIsYawOnly() {
        val head = Vec3(1f, 1.6f, 2f)
        for (yaw in listOf(0f, 90f, -45f, 180f)) {
            val p = Upright.ahead(head, yaw, 1.0f)
            assertEquals(1.6f, p.y, 1e-5f)
            assertEquals(1.0f, Vec3(p.x - head.x, 0f, p.z - head.z).length, 1e-4f)
            assertEquals(wrapDeg(yaw), wrapDeg(Compass.yawOf(p - head)), 1e-3f)
        }
    }
}
