package io.embodit.sunpatch.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** What a surface is to the sun model (design `sunModel.patch`). */
enum class SurfaceRole { FLOOR, UP, WALL }

/**
 * A 2D frame on a surface: horizontal surfaces use Stage +X / +Z, walls use the horizontal along the wall
 * (to the right when you face the wall from inside the room) and +Y. Texel (i, j) of a light layer bitmap
 * sits at `origin + u * (i + 0.5) * cell + v * (j + 0.5) * cell`.
 */
data class SurfaceFrame(val origin: Vec3, val u: Vec3, val v: Vec3, val sizeU: Float, val sizeV: Float) {
    fun a(p: Vec3) = (p - origin).dot(u)

    fun b(p: Vec3) = (p - origin).dot(v)

    fun at(a: Float, b: Float): Vec3 = origin + u * a + v * b
}

/**
 * A surface that can receive light: the floor, any upward face (desk, bed, virtual table) or a wall. [normal]
 * is up for horizontal faces and points into the room for walls.
 */
data class LitSurface(val id: String, val role: SurfaceRole, val polygon: List<Vec3>, val normal: Vec3) {
    val frame: SurfaceFrame by lazy {
        val u = if (role == SurfaceRole.WALL) (-normal).cross(Vec3.UP).normalized() else Vec3.RIGHT
        val v = if (role == SurfaceRole.WALL) Vec3.UP else Vec3(0f, 0f, 1f)
        val p0 = polygon.first()
        val proj = polygon.map { it - normal * (it - p0).dot(normal) }
        val aa = proj.map { it.dot(u) }
        val bb = proj.map { it.dot(v) }
        val minA = aa.min()
        val minB = bb.min()
        // origin on the plane: p0 shifted to (minA, minB) in the frame
        val origin = p0 + u * (minA - p0.dot(u)) + v * (minB - p0.dot(v))
        SurfaceFrame(origin, u, v, aa.max() - minA, bb.max() - minB)
    }

    val poly2: List<FloatArray> by lazy { polygon.map { floatArrayOf(frame.a(it), frame.b(it)) } }

    val area: Float by lazy { abs(Poly2.area(poly2)) }

    val height: Float
        get() = polygon.map { it.y }.average().toFloat()

    companion object {
        /** Wall normal turned into the room (towards [inside]); planes from the scan come with either winding. */
        fun wall(id: String, polygon: List<Vec3>, inside: Vec3): LitSurface {
            var n = PlaneSurface(id, "WALL", polygon).normal
            n = Vec3(n.x, 0f, n.z).normalized()
            if ((inside - polygon.first()).dot(n) < 0f) n = -n
            return LitSurface(id, SurfaceRole.WALL, polygon, n)
        }

        fun horizontal(id: String, polygon: List<Vec3>, floor: Boolean) =
            LitSurface(id, if (floor) SurfaceRole.FLOOR else SurfaceRole.UP, polygon, Vec3.UP)
    }
}

/** A marked window (design `dataModel.windows`): corners top-left, top-right, bottom-right, bottom-left as seen from inside. */
data class SunWindow(val id: String, val wallId: String?, val corners: List<Vec3>, val outward: Vec3) {
    val center: Vec3
        get() = corners.fold(Vec3.ZERO) { a, c -> a + c } / 4f
    val width: Float
        get() = corners[0].distanceTo(corners[1])
    val height: Float
        get() = corners[0].distanceTo(corners[3])

    companion object {
        const val MIN_SIDE_M = 0.3f

        /**
         * Two-point marking (design `input.cornerRule`): [first] = top-left, [second] = bottom-right, both on the wall
         * whose outward normal (away from the user) is [outward]. The rectangle is gravity-aligned; either point
         * may be the higher one, the sides are sorted. Null when a side is under 0.3 m.
         */
        fun fromCorners(id: String, wallId: String?, first: Vec3, second: Vec3, outward: Vec3): SunWindow? {
            val n = Vec3(outward.x, 0f, outward.z).normalized()
            val right = n.cross(Vec3.UP).normalized()
            val b = second - n * (second - first).dot(n) // onto the plane through first
            val a1 = first.dot(right)
            val a2 = b.dot(right)
            val top = max(first.y, b.y)
            val bottom = min(first.y, b.y)
            if (abs(a2 - a1) < MIN_SIDE_M || top - bottom < MIN_SIDE_M) return null
            val base = first - right * a1
            fun p(a: Float, y: Float) = Vec3(base.x + right.x * a, y, base.z + right.z * a)
            val l = min(a1, a2)
            val r = max(a1, a2)
            return SunWindow(id, wallId, listOf(p(l, top), p(r, top), p(r, bottom), p(l, bottom)), n)
        }

        /** "Place a standard window" (design T3): 1.5 x 1.5 m, sill 0.9 m, centred horizontally on [at]. */
        fun standard(id: String, wallId: String?, at: Vec3, floorY: Float, outward: Vec3): SunWindow {
            val n = Vec3(outward.x, 0f, outward.z).normalized()
            val right = n.cross(Vec3.UP).normalized()
            val c = Vec3(at.x, floorY, at.z)
            val l = c - right * 0.75f
            val r = c + right * 0.75f
            return SunWindow(id, wallId, listOf(l.copy(y = floorY + 2.4f), r.copy(y = floorY + 2.4f), r.copy(y = floorY + 0.9f), l.copy(y = floorY + 0.9f)), n)
        }
    }
}

/** One window's patch on one surface (unclipped, on the surface plane) and the occluders' shadows there. */
data class PatchPiece(val surfaceId: String, val windowId: String, val quad: List<Vec3>)

data class ShadowPiece(val surfaceId: String, val occluderId: String, val polygon: List<Vec3>)

/** Everything the sun does at one instant (design `sunModel.patch`). */
data class SunFrame(val sun: Vec3, val pieces: List<PatchPiece>, val shadows: List<ShadowPiece>, val litWindows: Set<String>) {
    val anyLight: Boolean
        get() = pieces.isNotEmpty()

    companion object {
        val DARK = SunFrame(Vec3.UP, emptyList(), emptyList(), emptySet())
    }
}

object SunLight {
    /** Patch alpha per window and the cap where windows overlap (design `spatialLayout.patch`). */
    const val PATCH_ALPHA = 0.45f
    const val PATCH_ALPHA_CAP = 0.60f
    const val LAYER_CELL_M = 0.04f
    const val HOURS_CELL_M = 0.05f
    const val MAX_CELLS = 160

    fun windowLit(w: SunWindow, s: Vec3): Boolean = s.y > 0f && s.dot(w.outward) > 0f

    /**
     * Parallel projection along -[s] (design R1-1): the window rectangle onto every surface that faces the light,
     * minus the shadows of every upward face projected the same way. The window's own wall and walls facing away
     * are skipped; a projection with a corner behind the window (t <= 0) is dropped.
     */
    fun frame(surfaces: List<LitSurface>, windows: List<SunWindow>, s: Vec3): SunFrame {
        if (s.y <= 0f) return SunFrame.DARK
        val lit = windows.filter { windowLit(it, s) }
        if (lit.isEmpty()) return SunFrame(s, emptyList(), emptyList(), emptySet())
        val pieces = ArrayList<PatchPiece>()
        val shadows = ArrayList<ShadowPiece>()
        val occluders = surfaces.filter { it.role == SurfaceRole.UP }
        for (surf in surfaces) {
            val facing = s.dot(surf.normal)
            if (facing <= 1e-4f) continue
            var any = false
            for (w in lit) {
                if (surf.role == SurfaceRole.WALL && (surf.id == w.wallId || abs(surf.normal.dot(w.outward)) > 0.98f && planeDist(surf, w.center) < 0.05f)) continue
                val q = project(w.corners, surf, s) ?: continue
                pieces += PatchPiece(surf.id, w.id, q)
                any = true
            }
            if (!any) continue
            for (o in occluders) {
                if (o.id == surf.id) continue
                val q = project(o.polygon, surf, s) ?: continue
                shadows += ShadowPiece(surf.id, o.id, q)
            }
        }
        return SunFrame(s, pieces, shadows, lit.map { it.id }.toSet())
    }

    private fun planeDist(surf: LitSurface, p: Vec3) = abs((p - surf.polygon.first()).dot(surf.normal))

    /** Each point moved along -s onto the surface's plane; null if any point would have to move towards the sun. */
    fun project(points: List<Vec3>, surf: LitSurface, s: Vec3): List<Vec3>? {
        val p0 = surf.polygon.first()
        val sn = s.dot(surf.normal)
        if (sn <= 1e-5f) return null
        return points.map { p ->
            val t = (p - p0).dot(surf.normal) / sn
            if (t <= 1e-3f) return null
            p - s * t
        }
    }

    /** Prepared 2D view of one surface in one frame, for fast per-point tests. */
    class Lookup(val surface: LitSurface, frame: SunFrame) {
        private val patches = frame.pieces.filter { it.surfaceId == surface.id }.map { Poly2.Prepared(it.quad.map(::to2)) }
        private val shadows = frame.shadows.filter { it.surfaceId == surface.id }.map { Poly2.Prepared(it.polygon.map(::to2)) }
        private val outline = Poly2.Prepared(surface.poly2)
        val empty: Boolean = patches.isEmpty()

        private fun to2(p: Vec3) = floatArrayOf(surface.frame.a(p), surface.frame.b(p))

        /** Number of windows lighting the point (a, b) in the surface frame; 0 outside, in a shadow or in the dark. */
        fun coverage(a: Float, b: Float): Int {
            if (patches.isEmpty() || !outline.contains(a, b)) return 0
            if (shadows.any { it.contains(a, b) }) return 0
            return patches.count { it.contains(a, b) }
        }

        fun coverage(p: Vec3): Int = coverage(surface.frame.a(p), surface.frame.b(p))
    }

    /** Grid size for a surface at [cell] metres, enlarging the cell so neither side exceeds [maxCells]. */
    fun grid(surface: LitSurface, cell: Float, maxCells: Int = MAX_CELLS): Triple<Int, Int, Float> {
        val f = surface.frame
        val c = max(cell, max(f.sizeU, f.sizeV) / maxCells)
        return Triple(max(1, ceil(f.sizeU / c).toInt()), max(1, ceil(f.sizeV / c).toInt()), c)
    }

    /** Coverage per cell, row-major from the frame origin (j * w + i). */
    fun raster(surface: LitSurface, frame: SunFrame, cell: Float = LAYER_CELL_M, maxCells: Int = 192): Raster {
        val (w, h, c) = grid(surface, cell, maxCells)
        val out = ByteArray(w * h)
        val look = Lookup(surface, frame)
        if (!look.empty) {
            for (j in 0 until h) for (i in 0 until w) out[j * w + i] = look.coverage((i + 0.5f) * c, (j + 0.5f) * c).toByte()
        }
        return Raster(surface.id, w, h, c, out)
    }

    /** Lit area in m^2 of one surface (cells of [cell] metres). */
    fun litArea(surface: LitSurface, frame: SunFrame, cell: Float = LAYER_CELL_M): Float {
        val r = raster(surface, frame, cell)
        return r.cells.count { it > 0 } * r.cell * r.cell
    }
}

class Raster(val surfaceId: String, val w: Int, val h: Int, val cell: Float, val cells: ByteArray)

/** Small 2D polygon helpers (points as [a, b] float pairs). */
object Poly2 {
    fun area(p: List<FloatArray>): Float {
        var s = 0f
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            s += p[i][0] * q[1] - q[0] * p[i][1]
        }
        return s / 2f
    }

    fun centroid(p: List<FloatArray>): FloatArray = floatArrayOf(p.map { it[0] }.average().toFloat(), p.map { it[1] }.average().toFloat())

    /** Even-odd point-in-polygon with a bounding-box early out. */
    class Prepared(private val pts: List<FloatArray>) {
        private val minA = pts.minOf { it[0] }
        private val maxA = pts.maxOf { it[0] }
        private val minB = pts.minOf { it[1] }
        private val maxB = pts.maxOf { it[1] }

        fun contains(a: Float, b: Float): Boolean {
            if (a < minA || a > maxA || b < minB || b > maxB) return false
            var inside = false
            var j = pts.size - 1
            for (i in pts.indices) {
                val pi = pts[i]
                val pj = pts[j]
                if ((pi[1] > b) != (pj[1] > b) && a < (pj[0] - pi[0]) * (b - pi[1]) / (pj[1] - pi[1]) + pi[0]) inside = !inside
                j = i
            }
            return inside
        }
    }
}
