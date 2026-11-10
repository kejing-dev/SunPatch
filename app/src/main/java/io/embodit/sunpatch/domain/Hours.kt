package io.embodit.sunpatch.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor

/** Everything needed to ask the sun about one day: place, compass, room, windows. */
data class SunSetup(val city: City, val northYawDeg: Float, val surfaces: List<LitSurface>, val windows: List<SunWindow>) {
    fun sunAt(date: LocalDate, minute: Int): SunPos = Solar.position(city, date, minute)

    fun vectorAt(date: LocalDate, minute: Int): Vec3 = Compass.sunVector(sunAt(date, minute), northYawDeg)

    fun frameAt(date: LocalDate, minute: Int): SunFrame = SunLight.frame(surfaces, windows, vectorAt(date, minute))

    /** 5-minute sample minutes over the day (design `sunModel.hours`: sunrise to sunset; outside them alt < 0 anyway). */
    fun dayMinutes(date: LocalDate): IntRange {
        val (rise, set) = Solar.riseSet(city, date)
        val a = rise?.let { (floor(it / Solar.STEP_MIN) * Solar.STEP_MIN).toInt() } ?: 0
        val b = set?.let { (Math.ceil(it / Solar.STEP_MIN) * Solar.STEP_MIN).toInt() } ?: (Solar.DAY_MIN - Solar.STEP_MIN)
        return a..b
    }
}

/** Minutes of direct sun per cell of every floor / upward surface (walls are not counted). */
class HoursMap(val date: LocalDate, val layers: Map<String, Layer>) {
    class Layer(val surface: LitSurface, val w: Int, val h: Int, val cell: Float, val minutes: FloatArray) {
        fun hoursAt(i: Int, j: Int) = minutes[j * w + i] / 60f
    }

    /** Floor area (m^2) with at least [hours] of sun. */
    fun areaAtLeast(hours: Float, roles: Set<SurfaceRole> = setOf(SurfaceRole.FLOOR)): Float =
        layers.values.filter { it.surface.role in roles }.sumOf { l -> l.minutes.count { it >= hours * 60f - 0.01f } * (l.cell * l.cell).toDouble() }.toFloat()

    fun cellsAtLeast(hours: Float): Int = layers.values.sumOf { l -> l.minutes.count { it >= hours * 60f - 0.01f } }

    companion object {
        fun compute(setup: SunSetup, date: LocalDate, cell: Float = SunLight.HOURS_CELL_M): HoursMap {
            val acc = Accumulator(setup.surfaces, cell)
            for (m in setup.dayMinutes(date) step Solar.STEP_MIN) acc.add(setup.frameAt(date, m), Solar.STEP_MIN.toFloat())
            return acc.result(date)
        }
    }

    /** Adds frames one by one (the day sweep uses the same accumulator, so its trail equals the map, R2-4). */
    class Accumulator(surfaces: List<LitSurface>, cell: Float) {
        private val layers = surfaces.filter { it.role != SurfaceRole.WALL }.associate { s ->
            val (w, h, c) = SunLight.grid(s, cell)
            s.id to Layer(s, w, h, c, FloatArray(w * h))
        }

        fun add(frame: SunFrame, minutes: Float) {
            if (!frame.anyLight) return
            for (l in layers.values) {
                val look = SunLight.Lookup(l.surface, frame)
                if (look.empty) continue
                for (j in 0 until l.h) for (i in 0 until l.w) {
                    if (look.coverage((i + 0.5f) * l.cell, (j + 0.5f) * l.cell) > 0) l.minutes[j * l.w + i] += minutes
                }
            }
        }

        fun result(date: LocalDate) = HoursMap(date, layers)
    }
}

/** Heat colours (design `palette.brandTokens` heat1-3): 0 h transparent, 1 h cyan, 2 h wheat, >= 4 h orange-gold. */
object HeatRamp {
    private val STOPS = floatArrayOf(0f, 1f, 2f, 4f)
    private val COLORS = intArrayOf(0x008FC9DE, 0xFF8FC9DE.toInt(), 0xFFF2C96B.toInt(), 0xFFF0963C.toInt())
    const val ALPHA = 0.70f

    /** ARGB; alpha 0 below a few minutes, 70 % from 1 h. */
    fun argb(hours: Float): Int {
        if (hours <= 0.05f) return 0
        val h = hours.coerceAtMost(4f)
        var k = 0
        while (k < 2 && h > STOPS[k + 1]) k++
        val t = ((h - STOPS[k]) / (STOPS[k + 1] - STOPS[k])).coerceIn(0f, 1f)
        val c0 = COLORS[k]
        val c1 = COLORS[k + 1]
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * t).toInt()
        val a = (if (k == 0) t else 1f) * ALPHA * 255f
        return (a.toInt() shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

enum class Want { MORE, LESS, LOOK }

enum class Verdict { OK, MEH, NO, NEUTRAL }

/** Marker kinds and their default "want" (design `content.markerKinds`). */
enum class MarkerKind(val zh: String, val en: String, val want: Want) {
    PLANT("花盆", "Plant", Want.MORE),
    DESK("书桌", "Desk", Want.LESS),
    SCREEN("屏幕", "Screen", Want.LESS),
    PET("猫窝", "Pet bed", Want.MORE),
    BED("床", "Bed", Want.MORE),
    OTHER("其它", "Other", Want.LOOK),
}

/** One marker's sun for a day: hours, first / last lit minute, and 12 monthly values (15th of each month). */
data class MarkerStats(val hours: Float, val first: Int?, val last: Int?, val months: FloatArray = FloatArray(0)) {
    companion object {
        val NONE = MarkerStats(0f, null, null)

        /** Samples the point every 5 min; [surfaceId] is the surface it rests on. */
        fun day(setup: SunSetup, surfaceId: String, point: Vec3, date: LocalDate): MarkerStats {
            val surf = setup.surfaces.firstOrNull { it.id == surfaceId } ?: return NONE
            var minutes = 0
            var first: Int? = null
            var last: Int? = null
            for (m in setup.dayMinutes(date) step Solar.STEP_MIN) {
                val f = setup.frameAt(date, m)
                if (!f.anyLight) continue
                if (SunLight.Lookup(surf, f).coverage(point) > 0) {
                    minutes += Solar.STEP_MIN
                    if (first == null) first = m
                    last = m + Solar.STEP_MIN
                }
            }
            return MarkerStats(minutes / 60f, first, last)
        }

        fun withMonths(setup: SunSetup, surfaceId: String, point: Vec3, date: LocalDate): MarkerStats {
            val d = day(setup, surfaceId, point, date)
            val months = FloatArray(12) { day(setup, surfaceId, point, LocalDate.of(date.year, it + 1, 15)).hours }
            return d.copy(months = months)
        }
    }

    override fun equals(other: Any?) = other is MarkerStats && other.hours == hours && other.first == first && other.last == last && other.months.contentEquals(months)

    override fun hashCode() = hours.hashCode() * 31 + (first ?: -1)
}

/** Fixed thresholds (design `sunModel.verdict`), with +-10 min hysteresis around each threshold. */
object Verdicts {
    const val HYST_H = 10f / 60f

    fun of(want: Want, hours: Float): Verdict = when (want) {
        Want.LOOK -> Verdict.NEUTRAL
        Want.MORE -> if (hours >= 4f) Verdict.OK else if (hours >= 2f) Verdict.MEH else Verdict.NO
        Want.LESS -> if (hours <= 1f) Verdict.OK else if (hours <= 2f) Verdict.MEH else Verdict.NO
    }

    /** Keeps [previous] unless the hours moved more than 10 min past the threshold that separates them. */
    fun stable(want: Want, hours: Float, previous: Verdict?): Verdict {
        val now = of(want, hours)
        if (previous == null || previous == now || previous == Verdict.NEUTRAL || want == Want.LOOK) return now
        val up = of(want, hours + HYST_H)
        val down = of(want, hours - HYST_H)
        return if (up == previous || down == previous) previous else now
    }
}

/** "Comes in after 15:10" (design `sunModel.nextEntry`): same condition as the patch, alt > 0 and sun outside a window. */
object NextEntry {
    sealed interface Result {
        data class Lit(val windowId: String) : Result
        data class After(val minute: Int, val windowId: String) : Result
        data object NotToday : Result
        data object BeforeRise : Result
        data object AfterSet : Result
    }

    fun at(setup: SunSetup, date: LocalDate, minute: Int): Result {
        val pos = setup.sunAt(date, minute)
        val s = Compass.sunVector(pos, setup.northYawDeg)
        setup.windows.firstOrNull { SunLight.windowLit(it, s) }?.let { return Result.Lit(it.id) }
        val (rise, set) = Solar.riseSet(setup.city, date)
        if (pos.altDeg <= 0.0) {
            if (rise != null && minute < rise) {
                // before sunrise: the earliest lit minute today, if any
                return find(setup, date, minute)?.let { Result.After(it.first, it.second) } ?: Result.NotToday
            }
            return Result.AfterSet
        }
        return find(setup, date, minute)?.let { Result.After(it.first, it.second) } ?: Result.NotToday
    }

    private fun find(setup: SunSetup, date: LocalDate, from: Int): Pair<Int, String>? {
        var m = (from / Solar.STEP_MIN + 1) * Solar.STEP_MIN
        while (m < Solar.DAY_MIN) {
            val s = setup.vectorAt(date, m)
            setup.windows.firstOrNull { SunLight.windowLit(it, s) }?.let { return m to it.id }
            m += Solar.STEP_MIN
        }
        return null
    }
}

/** "Sweep a day" pacing (design `sunModel.sweep`, R2-4): steps due = floor(elapsed / 8 s * total), never skipped. */
class SweepClock(val totalSteps: Int, val durationMs: Long = 8000L) {
    var done = 0
        private set

    /** How many more steps to run now; the caller runs each one (accumulating) in order. */
    fun due(elapsedMs: Long): Int {
        val target = if (elapsedMs >= durationMs) totalSteps else floor(elapsedMs.toDouble() / durationMs * totalSteps).toInt().coerceIn(0, totalSteps)
        val n = (target - done).coerceAtLeast(0)
        done += n
        return n
    }

    val finished: Boolean
        get() = done >= totalSteps
}

/**
 * "Move in front of me" sampling (design `virtualRoom.headSample`, D29): wait until |pitch| <= 10 deg and the yaw
 * rate < 5 deg/s for 300 ms, then take position + yaw of that frame; after 5 s take the last frame anyway.
 */
class HeadSampler(
    val maxPitchDeg: Float = 10f,
    val maxYawRate: Float = 5f,
    val dwellMs: Long = 300L,
    val timeoutMs: Long = 5000L,
    /** The user is still looking at the panel when pressing; give them time to read the hint and turn. */
    val minDelayMs: Long = 800L,
) {
    data class Sample(val position: Vec3, val yawDeg: Float, val timedOut: Boolean)

    private var startMs = -1L
    private var stableSince = -1L
    private var lastYaw = Float.NaN
    private var lastMs = -1L
    var active = false
        private set

    fun start(now: Long) {
        active = true
        startMs = now
        stableSince = -1L
        lastYaw = Float.NaN
        lastMs = -1L
    }

    fun cancel() {
        active = false
    }

    /** [yawRateOverride] lets the debug command inject a turn rate (deg/s) instead of the measured one. */
    fun step(now: Long, position: Vec3?, yawDeg: Float?, pitchDeg: Float?, yawRateOverride: Float? = null): Sample? {
        if (!active) return null
        if (position == null || yawDeg == null || pitchDeg == null) {
            if (now - startMs >= timeoutMs) active = false
            return null
        }
        val rate = yawRateOverride ?: if (lastMs < 0 || lastYaw.isNaN() || now <= lastMs) 0f else abs(wrapDeg(yawDeg - lastYaw)) * 1000f / (now - lastMs)
        lastYaw = yawDeg
        lastMs = now
        val stable = abs(pitchDeg) <= maxPitchDeg && rate < maxYawRate
        if (!stable) stableSince = -1L else if (stableSince < 0) stableSince = now
        if (stable && now - startMs < minDelayMs) stableSince = now
        if (stable && now - stableSince >= dwellMs) {
            active = false
            return Sample(position, yawDeg, false)
        }
        if (now - startMs >= timeoutMs) {
            active = false
            return Sample(position, yawDeg, true)
        }
        return null
    }
}

/** Yaw-only placement (taste profile D9): a point [distance] ahead along [yawDeg] at [dy] from the head height. */
object Upright {
    fun ahead(head: Vec3, yawDeg: Float, distance: Float, dy: Float = 0f): Vec3 {
        val d = Compass.dirOfYaw(yawDeg)
        return Vec3(head.x + d.x * distance, head.y + dy, head.z + d.z * distance)
    }

    /** A 3D-plate rotation that only turns about the vertical so its +Z faces [eye]. */
    fun facingYaw(at: Vec3, eye: Vec3): Float = Compass.yawOf(at - eye)
}
