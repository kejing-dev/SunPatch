package io.embodit.sunpatch.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Sun altitude and azimuth in degrees (azimuth 0 = north, clockwise; design `sunModel.algorithm`). */
data class SunPos(val altDeg: Double, val azDeg: Double)

/**
 * NOAA ESRL general solar position equations (public domain), error well under 0.5 degrees. Local date and
 * clock time go through the city's IANA zone first (design R1-4: daylight saving comes from java.time, never
 * from the device clock). Sunrise / sunset use alt = -0.833 degrees (refraction + solar radius, R1-10); the
 * patch itself appears only while alt > 0 (R2-3).
 */
object Solar {
    const val RISE_SET_ALT_DEG = -0.833
    const val STEP_MIN = 5
    const val DAY_MIN = 24 * 60

    fun position(latDeg: Double, lonDeg: Double, instant: Instant): SunPos {
        val utc = instant.atZone(ZoneId.of("UTC"))
        val doy = utc.dayOfYear
        val daysInYear = if (utc.toLocalDate().isLeapYear) 366.0 else 365.0
        val hourUtc = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
        val g = 2.0 * PI / daysInYear * (doy - 1 + (hourUtc - 12.0) / 24.0)
        val eqtime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val trueSolarMin = hourUtc * 60.0 + eqtime + 4.0 * lonDeg
        val haRad = Math.toRadians(trueSolarMin / 4.0 - 180.0)
        val lat = Math.toRadians(latDeg)
        val cosZen = (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(haRad)).coerceIn(-1.0, 1.0)
        val zen = acos(cosZen)
        // azimuth from north, clockwise
        val az = atan2(sin(haRad), cos(haRad) * sin(lat) - Math.tan(decl) * cos(lat))
        val azDeg = (Math.toDegrees(az) + 180.0).mod(360.0)
        return SunPos(90.0 - Math.toDegrees(zen), azDeg)
    }

    fun instantOf(city: City, date: LocalDate, minuteOfDay: Int): Instant =
        ZonedDateTime.of(date.atStartOfDay(), ZoneId.of(city.zoneId)).plusMinutes(minuteOfDay.toLong()).toInstant()

    fun position(city: City, date: LocalDate, minuteOfDay: Int): SunPos =
        position(city.lat, city.lon, instantOf(city, date, minuteOfDay))

    /** Local minute of the day when the sun crosses [altDeg] going up (rise) / down (set); null if it never does. */
    fun riseSet(city: City, date: LocalDate, altDeg: Double = RISE_SET_ALT_DEG): Pair<Double?, Double?> {
        fun alt(m: Double) = position(city.lat, city.lon, instantOf(city, date, 0).plusSeconds((m * 60).toLong())).altDeg - altDeg
        var rise: Double? = null
        var set: Double? = null
        var prev = alt(0.0)
        var m = 10.0
        while (m <= DAY_MIN) {
            val cur = alt(m)
            if (prev < 0 && cur >= 0 && rise == null) rise = bisect(m - 10, m, ::alt)
            if (prev >= 0 && cur < 0) set = bisect(m - 10, m, ::alt)
            prev = cur
            m += 10.0
        }
        return rise to set
    }

    private fun bisect(a0: Double, b0: Double, f: (Double) -> Double): Double {
        var a = a0
        var b = b0
        val fa = f(a) < 0
        repeat(30) {
            val mid = (a + b) / 2
            if ((f(mid) < 0) == fa) a = mid else b = mid
        }
        return (a + b) / 2
    }

    /** "SSE" style 16-point name of an azimuth. */
    fun compassIndex(azDeg: Double): Int = (((azDeg % 360 + 360) % 360 + 11.25) / 22.5).toInt() % 16

    fun fmt(minuteOfDay: Double?): String {
        if (minuteOfDay == null) return "--:--"
        val m = Math.round(minuteOfDay).toInt().coerceIn(0, DAY_MIN)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}

/**
 * Room compass (design `sunModel.roomNorth`): [northYawDeg] is the yaw of north in Stage space, where yaw y means
 * the horizontal direction (-sin y, 0, -cos y), so yaw 0 is -Z (ahead at Stage start). East = north x up.
 */
object Compass {
    fun dirOfYaw(yawDeg: Float): Vec3 {
        val r = Math.toRadians(yawDeg.toDouble())
        return Vec3(-sin(r).toFloat(), 0f, -cos(r).toFloat())
    }

    fun yawOf(d: Vec3): Float = Math.toDegrees(atan2(-d.x.toDouble(), -d.z.toDouble())).toFloat()

    fun north(northYawDeg: Float): Vec3 = dirOfYaw(northYawDeg)

    fun east(northYawDeg: Float): Vec3 = north(northYawDeg).cross(Vec3.UP)

    /** North yaw that makes the horizontal direction [outward] point at compass bearing [bearingDeg]. */
    fun northYawFor(outward: Vec3, bearingDeg: Float): Float = wrap360(yawOf(outward) + bearingDeg)

    /** Compass bearing (0 N, 90 E) of a horizontal direction. */
    fun bearingOf(d: Vec3, northYawDeg: Float): Float {
        val n = north(northYawDeg)
        val e = east(northYawDeg)
        return wrap360(Math.toDegrees(atan2(d.dot(e).toDouble(), d.dot(n).toDouble())).toFloat())
    }

    /** Unit vector from the room towards the sun (design `sunModel.sunVector`). */
    fun sunVector(sun: SunPos, northYawDeg: Float): Vec3 {
        val alt = Math.toRadians(sun.altDeg)
        val az = Math.toRadians(sun.azDeg)
        val h = cos(alt)
        val v = north(northYawDeg) * (h * cos(az)).toFloat() + east(northYawDeg) * (h * sin(az)).toFloat() + Vec3.UP * sin(alt).toFloat()
        return v.normalized()
    }

    fun wrap360(a: Float): Float = ((a % 360f) + 360f) % 360f

    fun len2(x: Double, y: Double) = sqrt(x * x + y * y)
}
