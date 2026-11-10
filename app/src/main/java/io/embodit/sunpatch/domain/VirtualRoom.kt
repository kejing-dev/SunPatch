package io.embodit.sunpatch.domain

/**
 * The stand-in room when the scan finds no ceiling (taste profile D7, 0.1.1): a box [width] x [depth] x
 * [height] metres on [floorY], its depth axis along [ahead], with one coffee table in front of the user so
 * the tags and grades have something to show. It is made of ordinary [PlaneSurface]s, so pinching, the light
 * field, the spacing and wall rules work on it unchanged; real furniture planes still count when they are
 * detected. Width, depth and height are changed in the settings slot, and "move in front of me" rebuilds it
 * around the current head position and yaw (profile D9).
 */
data class VirtualRoom(
    val center: P2,
    val ahead: P2,
    val floorY: Float,
    val width: Float = DEFAULT_WIDTH,
    val depth: Float = DEFAULT_DEPTH,
    val height: Float = DEFAULT_HEIGHT,
) {
    val ceilingY: Float
        get() = floorY + height

    private val right: P2
        get() = P2(-ahead.z, ahead.x)

    /** The box's planes; [floor] false when the scan already has a floor, [table] false when it has furniture. */
    fun planes(floor: Boolean = true, walls: Boolean = true, table: Boolean = true): List<PlaneSurface> {
        val a = ahead * (depth / 2f)
        val r = right * (width / 2f)
        // back-left, back-right, front-right, front-left (front = ahead of the user)
        val c = listOf(center - a - r, center - a + r, center + a + r, center + a - r)
        val out = ArrayList<PlaneSurface>()
        if (floor) out += PlaneSurface(ID_FLOOR, "FLOOR", c.map { it.at(floorY) })
        out += PlaneSurface(ID_CEILING, "CEILING", c.map { it.at(ceilingY) })
        if (walls) {
            for (i in 0 until 4) {
                val p = c[i]
                val q = c[(i + 1) % 4]
                out += PlaneSurface("$ID_WALL$i", "WALL", listOf(p.at(floorY), q.at(floorY), q.at(ceilingY), p.at(ceilingY)))
            }
        }
        if (table) {
            val tc = center + ahead * TABLE_AHEAD_M
            val ta = ahead * (TABLE_DEPTH_M / 2f)
            val tr = right * (TABLE_WIDTH_M / 2f)
            val y = floorY + TABLE_HEIGHT_M
            out += PlaneSurface(ID_TABLE, "TABLE", listOf(tc - ta - tr, tc - ta + tr, tc + ta + tr, tc + ta - tr).map { it.at(y) })
        }
        return out
    }

    fun resized(width: Float = this.width, depth: Float = this.depth, height: Float = this.height): VirtualRoom =
        copy(width = width.coerceIn(MIN_SIDE_M, MAX_SIDE_M), depth = depth.coerceIn(MIN_SIDE_M, MAX_SIDE_M), height = height.coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M))

    companion object {
        const val ID_PREFIX = "virtual-"
        const val ID_FLOOR = "virtual-floor"
        const val ID_CEILING = "virtual-ceiling"
        const val ID_WALL = "virtual-wall-"
        const val ID_TABLE = "virtual-table"
        const val DEFAULT_WIDTH = 4.0f
        const val DEFAULT_DEPTH = 4.0f
        const val DEFAULT_HEIGHT = 2.7f
        const val MIN_SIDE_M = 2.0f
        const val MAX_SIDE_M = 12.0f
        const val STEP_M = 0.5f
        const val HEIGHT_STEP_M = 0.1f
        const val MIN_HEIGHT_M = 2.2f
        const val MAX_HEIGHT_M = 4.0f

        /** Eye height above the floor when the scan has no floor (standing). */
        const val EYE_HEIGHT_M = 1.6f

        /** The user stands this far behind the room's centre, so most of the room is ahead. */
        const val USER_BEHIND_CENTER_M = 1.0f
        const val TABLE_AHEAD_M = 0.3f
        const val TABLE_WIDTH_M = 1.1f
        const val TABLE_DEPTH_M = 0.6f
        const val TABLE_HEIGHT_M = 0.42f

        fun isVirtual(id: String): Boolean = id.startsWith(ID_PREFIX)

        /**
         * A room around the head (profile D9: current position and yaw, never the world origin): the user
         * [USER_BEHIND_CENTER_M] behind its centre, looking along its depth; the floor is the scanned one when
         * known, else [EYE_HEIGHT_M] under the eyes. [size] keeps the dimensions of an earlier virtual room.
         */
        fun around(head: HeadPose, floorY: Float?, size: VirtualRoom? = null): VirtualRoom {
            val ahead = head.level()
            val eye = P2.of(head.position)
            return VirtualRoom(
                center = eye + ahead * USER_BEHIND_CENTER_M,
                ahead = ahead,
                floorY = floorY ?: (head.position.y - EYE_HEIGHT_M),
                width = size?.width ?: DEFAULT_WIDTH,
                depth = size?.depth ?: DEFAULT_DEPTH,
                height = size?.height ?: DEFAULT_HEIGHT,
            )
        }
    }
}
