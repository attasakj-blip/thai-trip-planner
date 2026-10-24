package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Leg
import th.trip.planner.model.Route
import th.trip.planner.model.RouteTrip
import th.trip.planner.model.Stop
import th.trip.planner.model.TravelMode
import th.trip.planner.model.Trip
import th.trip.planner.model.Txt
import java.time.LocalDate
import java.time.LocalDateTime

class RejoinTest {
    private fun t(s: String) = Txt(s, s)
    private val busA = Hub("bus_a", t("A"), HubType.BUS, t("p"), 13.01, 100.0)
    private val busB = Hub("bus_b", t("B"), HubType.BUS, t("p"), 14.49, 100.0)
    private val hubs = listOf(busA, busB)
    private val routes = listOf(
        Route("bus_ab", TravelMode.BUS, t("op"), t("A-B"), listOf("bus_a", "bus_b"), listOf(RouteTrip(listOf("08:00", "11:00")), RouteTrip(listOf("14:00", "17:00")))),
    )
    private val points = listOf(Itinerary.Point(13.0, 100.0), Itinerary.Point(14.5, 100.0), Itinerary.Point(14.6, 100.1))
    private val planner = Rejoin.LegPlanner { i, date, earliest -> Itinerary.options(points[i], points[i + 1], date, hubs, routes, earliest = earliest) }

    private val d1 = LocalDate.of(2026, 11, 10)
    private val d2 = d1.plusDays(1)
    private val d3 = d1.plusDays(2)
    private val d4 = d1.plusDays(3)

    /** X (d1) -bus d2 08:00-> Y (d2, d3) -car d4-> Z (d4). */
    private fun trip(): Trip {
        val bus = planner.options(0, d2, 300).first { it.first.rides.isNotEmpty() }
        val morning = bus.journeys.first { it.rides.first().start == 8 * 60 }
        return Trip(
            id = "t",
            stops = listOf(Stop("x", listOf(d1.toString())), Stop("y", listOf(d2.toString(), d3.toString())), Stop("z", listOf(d4.toString()))),
            legs = listOf(Leg(mode = TravelMode.BUS, plan = bus.signature, depart = morning.depart), Leg(mode = TravelMode.CAR)),
        )
    }

    private fun run(target: Rejoin.Target, at: LocalDateTime) = Rejoin.retime(trip(), target, at, d2, planner) { 60 }

    @Test fun inTimeChangesNothing() {
        val r = run(Rejoin.Target.AtStop(0), d2.atTime(6, 0))
        assertTrue(r.changes.toString(), r.changes.isEmpty())
        assertEquals(trip().stops, r.trip.stops)
        assertEquals(trip().legs, r.trip.legs)
    }

    @Test fun missedBusTakesTheAfternoonOne() {
        val r = run(Rejoin.Target.AtStop(0), d2.atTime(10, 0))
        val c = r.changes.single() as Rejoin.Change.LegTime
        assertEquals(d2, c.afterDate)
        val leg = r.trip.legs[0]
        val e = r.entries.filterIsInstance<Rejoin.Entry.Leg>().first()
        assertEquals(14 * 60, e.journey!!.rides.first().start)
        assertEquals(trip().legs[0].plan, leg.plan)
        // Stays and the car leg are untouched.
        assertEquals(trip().stops, r.trip.stops)
        assertEquals(trip().legs[1], r.trip.legs[1])
    }

    @Test fun lateNightMovesToTomorrowAndShortensTheStay() {
        val r = run(Rejoin.Target.AtStop(0), d2.atTime(23, 0))
        val leg = r.changes.filterIsInstance<Rejoin.Change.LegTime>().single()
        assertEquals(d3, leg.afterDate)
        assertEquals(d3.toString(), r.trip.legs[0].date ?: TripLogic.defaultLegDate(r.trip, 0).toString())
        val stay = r.changes.filterIsInstance<Rejoin.Change.StopDates>().single()
        assertEquals(listOf(d3), stay.after)
        assertEquals(listOf(d4.toString()), r.trip.stops[2].dates)
    }

    @Test fun boardingAtTheStationOnlyNeedsTheRide() {
        // At the station at 07:35 (boarding closes 07:40): the 08:00 bus is still fine even though the taxi part would have left earlier.
        val r = run(Rejoin.Target.Board(0), d2.atTime(7, 35))
        assertTrue(r.changes.toString(), r.changes.isEmpty())
        assertEquals(Rejoin.Entry.Stop::class, r.entries[1]::class)
    }

    @Test fun lateCarLegMovesToTheArrivalDay() {
        // Arriving at Y only on d4 evening after a long detour: the car leg moves nowhere (already d4), Y keeps d4 only.
        val r = Rejoin.retime(trip(), Rejoin.Target.AtStop(1), d4.atTime(20, 0), d2, planner) { 60 }
        assertEquals(listOf(d4.toString()), r.trip.stops[1].dates)
        val last = r.entries.filterIsInstance<Rejoin.Entry.Leg>().single()
        assertEquals(d4, last.date)
        assertTrue(last.depart!! >= d4.atTime(20, 0))
    }
}
