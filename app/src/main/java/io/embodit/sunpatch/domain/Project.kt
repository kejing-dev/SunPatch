package io.embodit.sunpatch.domain

/** A world anchor's pose when an item was saved (one anchor per window / marker, design `dataModel`). */
data class AnchorPose(val position: Vec3, val rotation: Quat4) {
    fun toLocal(world: Vec3): Vec3 = rotation.conjugate().rotate(world - position)

    fun toWorld(local: Vec3): Vec3 = rotation.rotate(local) + position

    /** Yaw of the anchor's -Z axis (degrees, the Compass convention). */
    val yawDeg: Float
        get() = Compass.yawOf(rotation.rotate(Vec3.AHEAD).let { Vec3(it.x, 0f, it.z) })
}

/** A marked window: [no] is its number (1..3), [corners] TL, TR, BR, BL seen from inside, [outward] away from the room. */
data class WindowRec(
    val id: String,
    val no: Int,
    val wallId: String?,
    val corners: List<Vec3>,
    val outward: Vec3,
    val anchorId: String? = null,
    val anchorPose: AnchorPose? = null,
) {
    fun sun(): SunWindow = SunWindow(id, wallId, corners, outward)

    val center: Vec3
        get() = corners.fold(Vec3.ZERO) { a, c -> a + c } / 4f
    val width: Float
        get() = corners[0].distanceTo(corners[1])
    val height: Float
        get() = corners[0].distanceTo(corners[3])

    fun moved(f: (Vec3) -> Vec3, turn: (Vec3) -> Vec3): WindowRec = copy(corners = corners.map(f), outward = turn(outward).let { Vec3(it.x, 0f, it.z).normalized() })
}

/** A marker on a floor / upward face (design `content.markerKinds`, `sunModel.verdict`). */
data class MarkerRec(
    val id: String,
    val no: Int,
    val kind: MarkerKind,
    val want: Want,
    val pos: Vec3,
    val anchorId: String? = null,
    val anchorPose: AnchorPose? = null,
)

/**
 * The one saved project (design `dataModel.project`): the city, the room's north (independent of any window, R1-14),
 * the windows and the markers. Positions are Stage metres of the session that saved them; anchors bring them back.
 */
data class SunProject(
    val city: City = Cities.BEIJING,
    val northYawDeg: Float = 0f,
    val northSet: Boolean = false,
    val windows: List<WindowRec> = emptyList(),
    val markers: List<MarkerRec> = emptyList(),
) {
    /** Compass bearing window 1 faces (the facing row is bound to it), or null without a window. */
    fun facingDeg(): Float? = windows.firstOrNull()?.let { Compass.bearingOf(it.outward, northYawDeg) }

    fun bearingOf(w: WindowRec): Float = Compass.bearingOf(w.outward, northYawDeg)

    companion object {
        const val MAX_WINDOWS = 3
        const val MAX_MARKERS = 8
    }
}

/** 8-way names of a compass bearing (design: 北 东北 东 东南 南 西南 西 西北). */
object Dirs {
    val ZH = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val EN = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val WINDOW_ZH = listOf("北窗", "东北窗", "东窗", "东南窗", "南窗", "西南窗", "西窗", "西北窗")
    val WINDOW_EN = listOf("North window", "NE window", "East window", "SE window", "South window", "SW window", "West window", "NW window")

    fun index8(bearing: Float): Int = ((Math.round(Compass.wrap360(bearing) / 45f)) % 8 + 8) % 8

    fun name(bearing: Float, zh: Boolean): String = (if (zh) ZH else EN)[index8(bearing)]

    fun windowName(bearing: Float, zh: Boolean): String = (if (zh) WINDOW_ZH else WINDOW_EN)[index8(bearing)]

    /** 16-way for the sun readout (东南偏南 / SSE). */
    val ZH16 = listOf("北", "东北偏北", "东北", "东北偏东", "东", "东南偏东", "东南", "东南偏南", "南", "西南偏南", "西南", "西南偏西", "西", "西北偏西", "西北", "西北偏北")
    val EN16 = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun name16(az: Double, zh: Boolean): String = (if (zh) ZH16 else EN16)[Solar.compassIndex(az)]
}
