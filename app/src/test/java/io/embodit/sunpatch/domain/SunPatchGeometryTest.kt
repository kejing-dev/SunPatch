package io.embodit.sunpatch.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design `sunModel.geometryVectors` and `acceptance.mustPass` (patch geometry): default virtual room 4 x 4 x 2.7 m
 * around a user at the origin looking along -Z, a standard 1.5 x 1.5 m window (sill 0.9, top 2.4) in the front wall
 * (z = -3) facing south, Beijing. "Off the wall" = perpendicular distance from the window wall.
 */
class SunPatchGeometryTest {
    private val beijing = Cities.BEIJING
    private val june = LocalDate.parse("2026-06-21")
    private val december = LocalDate.parse("2026-12-21")

    private fun room(depth: Float = 4f): VirtualRoom =
        VirtualRoom.around(HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY), floorY = 0f).resized(depth = depth)

    private fun surfaces(v: VirtualRoom): List<LitSurface> {
        val inside = Vec3(v.center.x, 1f, v.center.z)
        return v.planes().mapNotNull { p ->
            when (p.semantic) {
                "FLOOR" -> LitSurface.horizontal(p.id, p.vertices, floor = true)
                "TABLE" -> LitSurface.horizontal(p.id, p.vertices, floor = false)
                "WALL" -> LitSurface.wall(p.id, p.vertices, inside)
                else -> null
            }
        }
    }

    private fun frontWallId(v: VirtualRoom) = "${VirtualRoom.ID_WALL}2"

    private val frontZ = -3f

    private fun southWindow(v: VirtualRoom) =
        SunWindow.standard("w1", frontWallId(v), Vec3(0f, 1.5f, v.center.z - v.depth / 2f), 0f, Vec3(0f, 0f, -1f))

    private fun setup(facing: Float = 180f, depth: Float = 4f): SunSetup {
        val v = room(depth)
        val w = southWindow(v)
        return SunSetup(beijing, Compass.northYawFor(w.outward, facing), surfaces(v), listOf(w))
    }

    private fun piecesOn(f: SunFrame, id: String) = f.pieces.filter { it.surfaceId == id }

    @Test fun roomIsWhereTheDesignSaysItIs() {
        val v = room()
        val walls = v.planes().filter { it.semantic == "WALL" }
        val front = walls.first { it.id == frontWallId(v) }
        front.vertices.forEach { assertEquals(frontZ, it.z, 1e-4f) }
        assertEquals(1f, walls.first { it.id == "${VirtualRoom.ID_WALL}0" }.vertices.first().z, 1e-4f)
    }

    @Test fun compassFacingSouthPutsNorthBehindTheWindow() {
        val s = setup()
        val w = s.windows.first()
        assertEquals(180f, Compass.bearingOf(w.outward, s.northYawDeg), 1e-3f)
        assertEquals(1f, Compass.north(s.northYawDeg).z, 1e-4f)
        assertEquals(270f, Compass.bearingOf(Vec3(1f, 0f, 0f), s.northYawDeg), 1e-3f)
    }

    @Test fun summerNoonFloorPatchHugsTheWall() {
        val s = setup()
        val f = s.frameAt(june, 12 * 60)
        val floor = piecesOn(f, VirtualRoom.ID_FLOOR).single()
        val off = floor.quad.map { it.z - frontZ }
        assertEquals(0.265f, off.min(), 0.02f)
        assertEquals(0.707f, off.max(), 0.02f)
        // about 1.5 m wide, sheared east
        assertEquals(1.5f, abs(floor.quad[1].x - floor.quad[0].x), 0.02f)
        assertTrue(floor.quad[3].x < floor.quad[0].x) // the sill edge lands nearer the wall and further east (east = -X here)
        assertTrue(SunLight.litArea(s.surfaces.first { it.id == VirtualRoom.ID_FLOOR }, f) > 0.5f)
    }

    @Test fun at45DegreesOffWallEqualsHeight() {
        val s = setup()
        val w = s.windows.first()
        val sun = Compass.sunVector(SunPos(45.0, 180.0), s.northYawDeg)
        val floor = SunLight.frame(s.surfaces, listOf(w), sun).pieces.single { it.surfaceId == VirtualRoom.ID_FLOOR }
        val off = floor.quad.map { it.z - frontZ }
        assertEquals(0.9f, off.min(), 0.01f)
        assertEquals(2.4f, off.max(), 0.01f)
    }

    @Test fun winterNoonReachesTheFarWall() {
        val s = setup()
        val f = s.frameAt(december, 12 * 60)
        val floor = piecesOn(f, VirtualRoom.ID_FLOOR).single()
        val off = floor.quad.map { it.z - frontZ }
        assertEquals(1.80f, off.min(), 0.02f)
        assertEquals(4.79f, off.max(), 0.03f)
        val back = piecesOn(f, "${VirtualRoom.ID_WALL}0").single()
        assertEquals(0.40f, back.quad.maxOf { it.y }, 0.03f)
        val backWall = s.surfaces.first { it.id == "${VirtualRoom.ID_WALL}0" }
        assertTrue(SunLight.litArea(backWall, f) > 0.2f)
        // the floor patch stops at the far wall
        val floorRaster = SunLight.raster(s.surfaces.first { it.id == VirtualRoom.ID_FLOOR }, f)
        assertTrue(floorRaster.cells.any { it > 0 })
    }

    @Test fun fiveMetreRoomMissesTheFarWall() {
        val s = setup(depth = 5f)
        val f = s.frameAt(december, 12 * 60)
        val back = piecesOn(f, "${VirtualRoom.ID_WALL}0").single()
        assertEquals(-0.10f, back.quad.maxOf { it.y }, 0.03f)
        assertEquals(0f, SunLight.litArea(s.surfaces.first { it.id == "${VirtualRoom.ID_WALL}0" }, f), 1e-6f)
    }

    @Test fun tableShadowShiftsAlongMinusSun() {
        for ((date, shift) in listOf(december to 0.84f, june to 0.13f)) {
            val s = setup()
            val f = s.frameAt(date, 12 * 60)
            val table = s.surfaces.first { it.id == VirtualRoom.ID_TABLE }
            val shadow = f.shadows.single { it.surfaceId == VirtualRoom.ID_FLOOR && it.occluderId == VirtualRoom.ID_TABLE }
            val c0 = table.polygon.fold(Vec3.ZERO) { a, p -> a + p } / 4f
            val c1 = shadow.polygon.fold(Vec3.ZERO) { a, p -> a + p } / 4f
            assertEquals("$date", shift, hypot(c1.x - c0.x, c1.z - c0.z), 0.03f)
            assertEquals(0f, c1.y, 1e-4f)
        }
    }

    @Test fun shadowIsDarkButUnderTheTableCanBeLit() {
        val s = setup()
        val f = s.frameAt(december, 12 * 60)
        val floor = s.surfaces.first { it.id == VirtualRoom.ID_FLOOR }
        val look = SunLight.Lookup(floor, f)
        val shadow = f.shadows.single { it.surfaceId == VirtualRoom.ID_FLOOR && it.occluderId == VirtualRoom.ID_TABLE }
        val c = shadow.polygon.fold(Vec3.ZERO) { a, p -> a + p } / 4f
        assertEquals(0, look.coverage(c))
        // under the table top (z -1.6 .. -1.0) but outside its shadow: lit from the side
        assertEquals(1, look.coverage(Vec3(0f, 0f, -1.1f)))
        // the table top itself is lit
        assertEquals(1, SunLight.Lookup(s.surfaces.first { it.id == VirtualRoom.ID_TABLE }, f).coverage(Vec3(0f, 0.42f, -1.1f)))
    }

    @Test fun windowWallAndWallsFacingAwayStayDark() {
        val s = setup()
        for (m in listOf(9 * 60, 12 * 60, 15 * 60)) {
            val f = s.frameAt(december, m)
            assertTrue(f.pieces.none { it.surfaceId == frontWallId(room()) })
            f.pieces.filter { it.surfaceId.startsWith(VirtualRoom.ID_WALL) }.forEach { p ->
                val wall = s.surfaces.first { it.id == p.surfaceId }
                assertTrue(f.sun.dot(wall.normal) > 0f)
            }
        }
    }

    @Test fun noPatchAtNightOrWithTheSunBehindTheWall() {
        val s = setup()
        assertFalse(s.frameAt(june, 3 * 60).anyLight)
        val north = setup(facing = 0f)
        assertFalse(north.frameAt(june, 12 * 60).anyLight)
        assertTrue(north.frameAt(june, 7 * 60).anyLight)
        assertTrue(north.frameAt(june, 18 * 60).anyLight)
    }

    @Test fun nextEntrySameRuleAsThePatch() {
        val north = setup(facing = 0f)
        val r = NextEntry.at(north, june, 12 * 60)
        assertTrue("$r", r is NextEntry.Result.After)
        assertEquals(16 * 60 + 15.0, (r as NextEntry.Result.After).minute.toDouble(), 5.0)
        assertEquals(NextEntry.Result.AfterSet, NextEntry.at(setup(), june, 20 * 60))
        assertEquals(NextEntry.Result.NotToday, NextEntry.at(setup(facing = 90f), june, 15 * 60))
        assertTrue(NextEntry.at(setup(), june, 12 * 60) is NextEntry.Result.Lit)
    }

    @Test fun northWindowMorningEndsAround0825() {
        val north = setup(facing = 0f)
        val lit = (4 * 60..12 * 60 step 5).filter { north.frameAt(june, it).anyLight }
        assertEquals(8 * 60 + 25.0, lit.max().toDouble(), 5.0)
    }

    @Test fun twoPointMarkingRules() {
        val out = Vec3(0f, 0f, -1f)
        val w = SunWindow.fromCorners("w", "wall", Vec3(-0.6f, 2.2f, -3f), Vec3(0.7f, 0.95f, -3.02f), out)
        assertNotNull(w)
        w!!
        assertEquals(Vec3(-0.6f, 2.2f, -3f), w.corners[0])
        assertEquals(0.7f, w.corners[2].x, 1e-4f)
        assertEquals(0.95f, w.corners[2].y, 1e-4f)
        w.corners.forEach { assertEquals(-3f, it.z, 1e-4f) }
        assertEquals(1.3f, w.width, 1e-4f)
        assertNull(SunWindow.fromCorners("w", "wall", Vec3(0f, 2f, -3f), Vec3(0.2f, 1f, -3f), out))
        assertNull(SunWindow.fromCorners("w", "wall", Vec3(0f, 2f, -3f), Vec3(1f, 1.8f, -3f), out))
        // corners in the other order still give a top-left first rectangle
        val back = SunWindow.fromCorners("w", "wall", Vec3(0.7f, 0.95f, -3f), Vec3(-0.6f, 2.2f, -3f), out)!!
        assertEquals(w.corners, back.corners)
    }

    @Test fun northStaysWhenWindowOneIsDeleted() {
        val s = setup()
        val w2 = SunWindow.standard("w2", "${VirtualRoom.ID_WALL}1", Vec3(2f, 1f, -1f), 0f, Vec3(1f, 0f, 0f))
        val before = Compass.bearingOf(w2.outward, s.northYawDeg)
        val after = s.copy(windows = listOf(w2))
        assertEquals(before, Compass.bearingOf(w2.outward, after.northYawDeg), 1e-4f)
        assertEquals(270f, before, 1e-3f) // the right-hand wall of a south-facing room faces west
    }
}
