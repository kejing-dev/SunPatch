package io.embodit.sunpatch.domain

import kotlin.math.abs

enum class RoomKind { REAL, VIRTUAL }

/** What a ray landed on (design `input.cornerRule` / `markerRule`): walls take corners, floor / upward faces take markers. */
enum class HitRole { FLOOR, UP, WALL, CEILING }

/** Analytic ray x room result (D24: no colliders on planes, the hit is computed here). */
data class RoomHit(val point: Vec3, val role: HitRole, val surface: LitSurface?, val distance: Float)

/**
 * The room the sun falls into (design `virtualRoom.rule`, R1-6): the scanned floor / walls / upward faces, the missing
 * walls filled from a [VirtualRoom] built around the user, or the virtual box alone. At most [MAX_SURFACES] receivers
 * (design `sunModel.limits`), the floor first, then by area.
 */
class Room(
    val kind: RoomKind,
    val surfaces: List<LitSurface>,
    private val ceilings: List<PlaneSurface>,
    val virtual: VirtualRoom?,
    val floorY: Float,
    val realWalls: Int,
    val realFloor: Boolean,
) {
    private val hitPlanes: List<Pair<LitSurface, PlaneSurface>> = surfaces.map { it to PlaneSurface(it.id, it.role.name, it.polygon) }

    val walls: List<LitSurface>
        get() = surfaces.filter { it.role == SurfaceRole.WALL }

    fun surface(id: String?): LitSurface? = if (id == null) null else surfaces.firstOrNull { it.id == id }

    /** Nearest surface the ray meets (two-sided, bounded polygons); ceilings count only to say "not here". */
    fun hit(origin: Vec3, dir: Vec3, maxDistance: Float = 15f): RoomHit? {
        var best: RoomHit? = null
        for ((s, p) in hitPlanes) {
            val h = p.hit(origin, dir, maxDistance) ?: continue
            if (best == null || h.distance < best.distance) {
                val role = when (s.role) {
                    SurfaceRole.FLOOR -> HitRole.FLOOR
                    SurfaceRole.UP -> HitRole.UP
                    SurfaceRole.WALL -> HitRole.WALL
                }
                best = RoomHit(h.point, role, s, h.distance)
            }
        }
        for (c in ceilings) {
            val h = c.hit(origin, dir, maxDistance) ?: continue
            if (best == null || h.distance < best.distance) best = RoomHit(h.point, HitRole.CEILING, null, h.distance)
        }
        return best
    }

    /** The highest floor / upward face whose outline holds [p]'s xz, at or below [maxY]; the floor otherwise. */
    fun surfaceUnder(p: Vec3, maxY: Float = p.y + 0.05f): LitSurface? {
        val horiz = surfaces.filter { it.role != SurfaceRole.WALL && it.height <= maxY }
        val inside = horiz.filter { s -> Poly2.Prepared(s.poly2).contains(s.frame.a(p), s.frame.b(p)) }
        return inside.maxByOrNull { it.height } ?: surfaces.firstOrNull { it.role == SurfaceRole.FLOOR }
    }

    /** Short description for logs and the dump. */
    fun describe(): String =
        "kind=$kind floorY=%.2f realWalls=$realWalls realFloor=$realFloor surfaces=${surfaces.size} (${surfaces.joinToString(",") { "${it.role}:${it.id.take(14)}" }})".format(java.util.Locale.US, floorY)

    companion object {
        const val MAX_SURFACES = 12
        private const val UP_MIN_M = 0.15f
        private const val UP_MAX_M = 1.6f
        private const val WALL_MATCH_COS = 0.9f
        private const val WALL_MATCH_M = 0.8f

        /**
         * [planes] from the scan, [virtual] the box around the user (null before the first head pose), [useVirtual] the
         * box alone (S03), [inside] a point inside the room (turns wall normals into the room).
         */
        fun build(planes: Collection<PlaneSurface>, virtual: VirtualRoom?, useVirtual: Boolean, inside: Vec3): Room? {
            val floors = planes.filter { it.semantic == "FLOOR" && it.isHorizontal && it.vertices.size >= 3 }
            val realFloorY = floors.maxByOrNull { it.area }?.centroid?.y
            val vr = virtual
            val ceilings = ArrayList<PlaneSurface>()
            if (useVirtual || planes.none { it.semantic == "WALL" && it.isVertical }) {
                vr ?: return null
                val out = ArrayList<LitSurface>()
                for (p in vr.planes(floor = true, walls = true, table = true)) {
                    when (p.semantic) {
                        "FLOOR" -> out += LitSurface.horizontal(p.id, p.vertices, floor = true)
                        "TABLE" -> out += LitSurface.horizontal(p.id, p.vertices, floor = false)
                        "WALL" -> out += LitSurface.wall(p.id, p.vertices, inside.copy(y = vr.floorY + 1.2f))
                        "CEILING" -> ceilings += p
                    }
                }
                return Room(RoomKind.VIRTUAL, out, ceilings, vr, vr.floorY, 0, realFloorY != null)
            }
            val floorY = realFloorY ?: vr?.floorY ?: return null
            val out = ArrayList<LitSurface>()
            floors.forEach { out += LitSurface.horizontal(it.id, it.vertices, floor = true) }
            val realWalls = planes.filter { it.semantic == "WALL" && it.isVertical && it.vertices.size >= 3 }
            realWalls.forEach { out += LitSurface.wall(it.id, it.vertices, inside) }
            planes.filter { it.isHorizontal && it.semantic != "FLOOR" && it.semantic != "CEILING" && it.centroid.y - floorY in UP_MIN_M..UP_MAX_M }
                .forEach { out += LitSurface.horizontal(it.id, it.vertices, floor = false) }
            planes.filter { it.semantic == "CEILING" || (it.isHorizontal && it.centroid.y - floorY > 2.0f) }.forEach { ceilings += it }
            if (vr != null) {
                val box = vr.planes(floor = floors.isEmpty(), walls = true, table = false)
                for (p in box) {
                    when (p.semantic) {
                        "FLOOR" -> out += LitSurface.horizontal(p.id, p.vertices, floor = true)
                        "CEILING" -> if (ceilings.isEmpty()) ceilings += p
                        "WALL" -> {
                            val covered = realWalls.any { r ->
                                val n1 = Vec3(r.normal.x, 0f, r.normal.z).normalized()
                                val n2 = Vec3(p.normal.x, 0f, p.normal.z).normalized()
                                abs(n1.dot(n2)) > WALL_MATCH_COS && abs((r.centroid - p.centroid).dot(n2)) < WALL_MATCH_M
                            }
                            if (!covered) out += LitSurface.wall(p.id, p.vertices, inside)
                        }
                    }
                }
            }
            return Room(RoomKind.REAL, limit(out), ceilings, vr, floorY, realWalls.size, floors.isNotEmpty())
        }

        /** Floors first, then the largest of the rest, [MAX_SURFACES] in all (design `sunModel.limits`). */
        fun limit(all: List<LitSurface>): List<LitSurface> {
            if (all.size <= MAX_SURFACES) return all
            val floors = all.filter { it.role == SurfaceRole.FLOOR }.sortedByDescending { it.area }
            val rest = all.filter { it.role != SurfaceRole.FLOOR }.sortedByDescending { it.area }
            return (floors + rest).take(MAX_SURFACES)
        }
    }
}

/** Keeps a window on "the same wall" when a virtual wall moves (resize / turn / move in front of me). */
object WallSlide {
    fun follow(win: SunWindow, old: LitSurface, new: LitSurface): SunWindow {
        val oc = centroid(old.polygon)
        val nc = centroid(new.polygon)
        val ou = old.frame.u
        val nu = new.frame.u
        val a = (win.center - oc).dot(ou)
        val corners = win.corners.map { c ->
            val da = (c - win.center).dot(ou)
            val off = a + da
            Vec3(nc.x + nu.x * off, c.y, nc.z + nu.z * off)
        }
        return SunWindow(win.id, new.id, corners, -new.normal)
    }

    fun centroid(p: List<Vec3>): Vec3 = p.fold(Vec3.ZERO) { s, v -> s + v } / p.size.toFloat()
}
