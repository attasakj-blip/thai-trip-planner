package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.model.GapPurpose
import th.trip.planner.model.Leg
import th.trip.planner.model.Place
import th.trip.planner.model.Region
import th.trip.planner.model.Stop
import th.trip.planner.model.TravelMode
import th.trip.planner.model.Trip
import th.trip.planner.model.Txt
import java.time.LocalDate

class TripLogicTest {
    private fun place(id: String, lat: Double, lon: Double, region: Region = Region.NORTH) =
        Place(id, Txt(id, id), Txt("", ""), region, lat, lon, Txt("", ""))

    private val cm = place("cm", 18.79, 98.98)
    private val pai = place("pai", 19.36, 98.44)
    private val bkk = place("bkk", 13.75, 100.49, Region.BANGKOK)
    private val places = listOf(cm, pai, bkk).associateBy { it.id }

    private val trip = Trip(
        stops = listOf(
            Stop("bkk", listOf("2026-11-01", "2026-11-02")),
            Stop("cm", listOf("2026-11-03", "2026-11-04", "2026-11-07")), // 5th–6th: gap inside stop
            Stop("pai", listOf("2026-11-10", "2026-11-11")), // 8th–9th: gap between stops
        ),
    )

    @Test fun findsGapsInsideAndBetweenStops() {
        val gaps = TripLogic.gaps(trip.stops)
        assertEquals(2, gaps.size)
        assertEquals(listOf(LocalDate.parse("2026-11-05"), LocalDate.parse("2026-11-06")), gaps[0].dates)
        assertEquals(1, gaps[0].afterStop)
        assertEquals(listOf(LocalDate.parse("2026-11-08"), LocalDate.parse("2026-11-09")), gaps[1].dates)
        assertEquals(1, gaps[1].afterStop); assertEquals(2, gaps[1].beforeStop)
    }

    @Test fun contiguousTripHasNoGaps() {
        val t = listOf(Stop("a", listOf("2026-01-01", "2026-01-02")), Stop("b", listOf("2026-01-02", "2026-01-03")))
        assertTrue(TripLogic.gaps(t).isEmpty())
        assertTrue(TripLogic.validate(t).isEmpty()) // shared travel day is allowed
    }

    @Test fun detectsOverlapAndMissingDates() {
        val t = listOf(Stop("a", listOf("2026-01-05")), Stop("b", listOf("2026-01-03")), Stop("c"))
        val issues = TripLogic.validate(t)
        assertTrue(issues.contains(ScheduleIssue.Overlap(0, 1)))
        assertTrue(issues.contains(ScheduleIssue.NoDates(2)))
    }

    @Test fun statusByCalendar() {
        assertEquals(TripStatus.NotStarted, TripLogic.status(trip, places, LocalDate.parse("2026-10-31")))
        assertEquals(TripStatus.AtPlace(0), TripLogic.status(trip, places, LocalDate.parse("2026-11-02")))
        assertEquals(TripStatus.InGap(1), TripLogic.status(trip, places, LocalDate.parse("2026-11-05")))
        assertEquals(TripStatus.AtPlace(2), TripLogic.status(trip, places, LocalDate.parse("2026-11-11")))
        assertEquals(TripStatus.Finished, TripLogic.status(trip, places, LocalDate.parse("2026-11-12")))
    }

    @Test fun statusUsesGpsOnArrivalDay() {
        val day = LocalDate.parse("2026-11-03")
        // Still in Bangkok on the day we should reach Chiang Mai: travelling on leg 1.
        assertEquals(TripStatus.Travelling(1), TripLogic.status(trip, places, day, 13.75, 100.49))
        // Arrived in Chiang Mai.
        assertEquals(TripStatus.AtPlace(1), TripLogic.status(trip, places, day, 18.80, 98.99))
    }

    @Test fun customLegDateOnGapDayIsTravelling() {
        // Leg 1 goes from Chiang Mai (stop 1) to Pai (stop 2).
        val t = trip.copy(legs = List(2) { Leg() }.toMutableList().also { it[1] = Leg(TravelMode.BUS, "2026-11-09") },
            gapPurposes = mapOf("2026-11-08" to GapPurpose.VISIT))
        assertEquals(TripStatus.Travelling(2), TripLogic.status(t, places, LocalDate.parse("2026-11-09")))
    }

    @Test fun legsOnlyBetweenPlaces() {
        assertEquals(2, TripLogic.legCount(trip))
        assertEquals(0, TripLogic.legCount(Trip(stops = listOf(Stop("bkk")))))
        // Leg 0 (Bangkok -> Chiang Mai) defaults to the first Chiang Mai day.
        assertEquals(LocalDate.parse("2026-11-03"), TripLogic.legDate(trip, 0))
        assertEquals(LocalDate.parse("2026-11-10"), TripLogic.legDate(trip, 1))
    }

    @Test fun distance() {
        val km = TripLogic.distanceKm(13.75, 100.49, 18.79, 98.98)
        assertTrue(km in 560.0..600.0)
    }

    @Test fun seasonalRisksForNorthInMarch() {
        val w = SeasonalRisk.forPlace(cm, listOf(LocalDate.parse("2026-03-10")))
        assertTrue(w.any { it.kind == RiskKind.HAZE_FIRE && it.level == Level.WARNING })
        assertTrue(w.any { it.kind == RiskKind.HEAT })
        assertTrue(w.none { it.kind == RiskKind.HEAVY_RAIN })
    }
}
