package io.embodit.sunpatch.domain

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Design `sunModel.testVectors`: +-0.5 deg; sunrise / sunset at -0.833 deg +-3 min. */
class SolarTest {
    private fun city(k: String) = Cities.byKey(k)!!

    private fun check(c: String, date: String, hh: Int, mm: Int, alt: Double, az: Double?) {
        val p = Solar.position(city(c), LocalDate.parse(date), hh * 60 + mm)
        assertEquals("$c $date $hh:$mm alt", alt, p.altDeg, 0.5)
        if (az != null) assertEquals("$c $date $hh:$mm az", az, p.azDeg, 0.5)
    }

    @Test fun beijingSummerNoon() = check("beijing", "2026-06-21", 12, 0, 73.2, 167.4)

    @Test fun beijingWinterNoon() = check("beijing", "2026-12-21", 12, 0, 26.6, 176.9)

    @Test fun beijingEquinoxMorning() = check("beijing", "2026-03-20", 9, 0, 28.7, 118.1)

    @Test fun shanghaiAutumnAfternoon() = check("shanghai", "2026-09-23", 15, 0, 34.7, 245.5)

    @Test fun guangzhouNearZenith() = check("guangzhou", "2026-06-21", 13, 0, 82.7, 274.1)

    @Test fun londonSummerTimeIsLocal() = check("london", "2026-06-21", 13, 0, 61.9, 179.1)

    @Test fun newYorkWinterIsLocal() = check("newyork", "2026-12-21", 12, 0, 25.9, 181.6)

    @Test fun beijingEdgesOfTheDay() {
        check("beijing", "2026-06-21", 5, 0, 1.6, null)
        check("beijing", "2026-06-21", 19, 30, 1.8, null)
    }

    @Test fun sunriseSunsetBeijingSolstice() {
        val (rise, set) = Solar.riseSet(city("beijing"), LocalDate.parse("2026-06-21"))
        assertNotNull(rise)
        assertNotNull(set)
        assertEquals(4 * 60 + 46.0, rise!!, 3.0)
        assertEquals(19 * 60 + 46.0, set!!, 3.0)
    }

    @Test fun instantComesFromTheCityZoneNotTheDevice() {
        val i = Solar.instantOf(city("london"), LocalDate.parse("2026-06-21"), 13 * 60)
        assertEquals(ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant(), i)
    }

    @Test fun sixtyFourCitiesWithValidZones() {
        assertEquals(64, Cities.all.size)
        assertEquals(64, Cities.all.map { it.key }.toSet().size)
        Cities.all.forEach { ZoneId.of(it.zoneId) }
    }
}
