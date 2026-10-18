package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.model.Headway
import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Route
import th.trip.planner.model.RouteTrip
import th.trip.planner.model.TravelMode
import th.trip.planner.model.Txt
import java.time.LocalDate

class ItineraryTest {
    private fun t(s: String) = Txt(s, s)
    private fun hub(id: String, type: HubType, lat: Double, lon: Double, island: String? = null) =
        Hub(id, t(id), type, t("p"), lat, lon, islandGroup = island)

    private val pattayaBus = hub("bus_pattaya", HubType.BUS, 12.9446, 100.8936)
    private val moChit = hub("bus_mochit", HubType.BUS, 13.8122, 100.5481)
    private val southern = hub("bus_southern", HubType.BUS, 13.7838, 100.4296)
    private val surat = hub("bus_surat", HubType.BUS, 9.1335, 99.3256)
    private val donsak = hub("pier_donsak", HubType.PIER, 9.3290, 99.6950)
    private val nathon = hub("pier_nathon", HubType.PIER, 9.5348, 99.9356, "samui")
    private val bkk = hub("bkk", HubType.AIRPORT, 13.69, 100.75)
    private val usm = hub("usm", HubType.AIRPORT, 9.5478, 100.0623, "samui")
    private val sukhothaiBus = hub("bus_sukhothai", HubType.BUS, 17.0, 99.82)
    private val hubs = listOf(pattayaBus, moChit, southern, surat, donsak, nathon, bkk, usm, sukhothaiBus)

    private fun route(id: String, mode: TravelMode, stops: List<String>, vararg times: List<String?>, days: List<Int> = emptyList()) =
        Route(id, mode, t("op"), t(id), stops, times.map { RouteTrip(it) }, days = days)

    private val routes = listOf(
        Route("van_pattaya_mochit", TravelMode.BUS, t("op"), t("x"), listOf("bus_pattaya", "bus_mochit"),
            headway = Headway("05:00", "20:00", 40, listOf(0, 150))),
        route("bus_south_surat", TravelMode.BUS, listOf("bus_southern", "bus_surat"), listOf("19:00", "30:00"), listOf("20:30", "31:15")),
        route("ferry_donsak_nathon", TravelMode.BOAT, listOf("pier_donsak", "pier_nathon"),
            listOf("05:00", "06:30"), listOf("07:00", "08:30"), listOf("09:00", "10:30"), listOf("13:00", "14:30")),
        route("pg_bkk_usm", TravelMode.FLIGHT, listOf("bkk", "usm"), listOf("08:00", "09:05"), listOf("12:00", "13:05"), listOf("17:00", "18:05")),
    )

    private val pattaya = Itinerary.Point(12.9276, 100.8771)
    private val samui = Itinerary.Point(9.5120, 100.0136, island = true, islandGroup = "samui")
    private val date = LocalDate.of(2026, 11, 10)

    @Test fun pattayaToSamuiHasFlightAndOvernightBus() {
        val opts = Itinerary.options(pattaya, samui, date, hubs, routes)
        assertTrue(opts.isNotEmpty())
        // Never a car door to door to an island, and never a boat from Pattaya.
        assertTrue(opts.none { it.first.rides.isEmpty() })
        val fly = opts.first { it.mainMode == TravelMode.FLIGHT }
        assertEquals("usm", fly.first.rides.last().alight.id)
        assertTrue("several flight times", fly.journeys.size >= 2)
        val bus = opts.first { o -> o.first.rides.any { it.route.id == "bus_south_surat" } }
        val r = bus.first.rides.map { it.route.id }
        assertEquals(listOf("van_pattaya_mochit", "bus_south_surat", "ferry_donsak_nathon"), r)
        // Mo Chit -> Southern terminal by taxi, and Surat terminal -> Donsak pier by taxi.
        assertTrue(bus.first.steps.filterIsInstance<Itinerary.Step.Ground>().any { it.from?.id == "bus_mochit" && it.to?.id == "bus_southern" })
        // Connections respect buffers and run forward in time.
        bus.first.steps.zipWithNext().forEach { (a, b) -> assertTrue(b.start >= a.end) }
        val ferry = bus.first.rides.last()
        assertTrue(ferry.start >= 30 * 60 + Itinerary.groundMinutes(40.0))
    }

    @Test fun noModesWithoutService() {
        val sukhothai = Itinerary.Point(17.02, 99.70)
        val opts = Itinerary.options(samui, sukhothai, date, hubs, routes)
        // No route leaves Samui towards the north in this data, so nothing scheduled is offered
        assertFalse(opts.any { it.mainMode == TravelMode.TRAIN })
    }

    @Test fun carOnMainland() {
        val opts = Itinerary.options(pattaya, Itinerary.Point(13.75, 100.5), date, hubs, routes)
        assertTrue(opts.any { it.first.rides.isEmpty() })
    }

    @Test fun times() {
        assertEquals(30 * 60 + 15, Itinerary.parseTime("30:15"))
        assertEquals("06:15", Itinerary.formatTime(30 * 60 + 15))
        assertEquals(1, Itinerary.dayOffset(30 * 60))
    }
}
