package th.trip.planner.logic

import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Place
import th.trip.planner.model.Stop
import th.trip.planner.model.TravelMode
import th.trip.planner.model.Trip
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A problem with the schedule that must be fixed before moving on. */
sealed class ScheduleIssue {
    data class NoDates(val stopIndex: Int) : ScheduleIssue()
    /** Stop [first] has dates on or after the first date of the later stop [second]. */
    data class Overlap(val first: Int, val second: Int) : ScheduleIssue()
}

/** Dates that are not used for sightseeing between the first and last day of the trip. */
data class Gap(
    /** Visiting-order index of the stop before the gap (gap inside a stop: same as [beforeStop]). */
    val afterStop: Int,
    /** Visiting-order index of the stop after the gap. */
    val beforeStop: Int,
    val dates: List<LocalDate>,
)

/** Where the traveller is in the plan. */
sealed class TripStatus {
    data object NotStarted : TripStatus()
    data class AtPlace(val stopIndex: Int) : TripStatus()
    /** On the way to stop [toStop] (by leg toStop - 1; toStop 0 = heading to the first place). */
    data class Travelling(val toStop: Int) : TripStatus()
    data class InGap(val afterStop: Int) : TripStatus()
    data object Finished : TripStatus()
}

object TripLogic {

    fun dates(stop: Stop): List<LocalDate> = stop.dates.map(LocalDate::parse).sorted()

    fun validate(stops: List<Stop>): List<ScheduleIssue> {
        val issues = mutableListOf<ScheduleIssue>()
        stops.forEachIndexed { i, s -> if (s.dates.isEmpty()) issues += ScheduleIssue.NoDates(i) }
        // The visiting order must follow the calendar. The same day may be shared by two
        // stops (a travel day), but a stop may not have days after the next stop starts.
        for (i in 0 until stops.size - 1) {
            val a = dates(stops[i])
            val b = dates(stops[i + 1])
            if (a.isEmpty() || b.isEmpty()) continue
            if (a.last() > b.first()) issues += ScheduleIssue.Overlap(i, i + 1)
        }
        return issues
    }

    /** Dates between the first and last trip day that belong to no stop. */
    fun gaps(stops: List<Stop>): List<Gap> {
        val gaps = mutableListOf<Gap>()
        val all = stops.flatMap { dates(it) }.toSet()
        stops.forEachIndexed { i, stop ->
            val d = dates(stop)
            // Gaps inside one stop (e.g. 1st, 2nd and 5th at the same place).
            for (k in 0 until d.size - 1) {
                val between = daysBetween(d[k], d[k + 1]).filter { it !in all }
                if (between.isNotEmpty()) gaps += Gap(i, i, between)
            }
            // Gap between this stop and the next one.
            if (i < stops.size - 1) {
                val next = dates(stops[i + 1])
                if (d.isNotEmpty() && next.isNotEmpty() && d.last() < next.first()) {
                    val between = daysBetween(d.last(), next.first()).filter { it !in all }
                    if (between.isNotEmpty()) gaps += Gap(i, i + 1, between)
                }
            }
        }
        return gaps
    }

    /** Dates strictly between [a] and [b]. */
    fun daysBetween(a: LocalDate, b: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d = a.plusDays(1)
        while (d < b) { out += d; d = d.plusDays(1) }
        return out
    }

    fun firstDay(trip: Trip): LocalDate? = trip.stops.flatMap { dates(it) }.minOrNull()
    fun lastDay(trip: Trip): LocalDate? = trip.stops.flatMap { dates(it) }.maxOrNull()

    /** Number of legs: origin -> stop 1, between stops, last stop -> end point. */
    /** One leg between each pair of consecutive places: leg i goes from stop i to stop i + 1. */
    fun legCount(trip: Trip) = (trip.stops.size - 1).coerceAtLeast(0)

    /** Default travel date of each leg, used when the user has not picked one. */
    fun defaultLegDate(trip: Trip, legIndex: Int): LocalDate? {
        val stops = trip.stops
        return stops.getOrNull(legIndex + 1)?.let { dates(it).firstOrNull() }
    }

    fun legDate(trip: Trip, legIndex: Int): LocalDate? =
        trip.legs.getOrNull(legIndex)?.date?.let(LocalDate::parse) ?: defaultLegDate(trip, legIndex)

    /**
     * Works out where the traveller should be. Uses the calendar, and the GPS position when
     * given: on a travel day the traveller counts as "at the place" once within [nearKm] of it.
     */
    fun status(
        trip: Trip,
        places: Map<String, Place>,
        today: LocalDate,
        lat: Double? = null,
        lon: Double? = null,
        nearKm: Double = 20.0,
    ): TripStatus {
        if (trip.finished) return TripStatus.Finished
        val first = firstDay(trip) ?: return TripStatus.NotStarted
        val last = lastDay(trip)!!
        if (today < first) return TripStatus.NotStarted
        if (today > last) return TripStatus.Finished

        fun near(stopIndex: Int): Boolean {
            if (lat == null || lon == null) return false
            val p = places[trip.stops[stopIndex].placeId] ?: return false
            return distanceKm(lat, lon, p.lat, p.lon) <= nearKm
        }

        val todays = trip.stops.indices.filter { today in dates(trip.stops[it]) }
        if (todays.isNotEmpty()) {
            // If GPS says we're at one of today's places, use it.
            todays.firstOrNull { near(it) }?.let { return TripStatus.AtPlace(it) }
            if (lat != null && lon != null) {
                // Arrival day of a stop and not near it yet: on the way there (leg i leads to stop i).
                val arriving = todays.lastOrNull { dates(trip.stops[it]).first() == today }
                if (arriving != null) return TripStatus.Travelling(arriving)
            }
            return TripStatus.AtPlace(todays.first())
        }
        // A day with no stop: a gap, or a travel leg on a custom date.
        val leg = (0 until legCount(trip)).firstOrNull { trip.legs.getOrNull(it)?.date?.let(LocalDate::parse) == today }
        if (leg != null) return TripStatus.Travelling(leg + 1)
        val before = trip.stops.indices.lastOrNull { i -> dates(trip.stops[i]).any { it < today } } ?: 0
        return TripStatus.InGap(before)
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Hubs near a place: listed hubs first, then any hub within [radiusKm]. */
    fun hubsFor(place: Place, hubs: List<Hub>, radiusKm: Double = 60.0): List<Hub> {
        val listed = place.hubIds.mapNotNull { id -> hubs.firstOrNull { it.id == id } }
        val nearby = hubs.filter { it !in listed && distanceKm(place.lat, place.lon, it.lat, it.lon) <= radiusKm }
            .sortedBy { distanceKm(place.lat, place.lon, it.lat, it.lon) }
        return listed + nearby
    }

    /**
     * Travel modes that make sense between two points. [fromHubs]/[toHubs] are the hubs serving
     * each end. Car is always possible on the mainland.
     */
    fun modeOptions(
        fromLat: Double, fromLon: Double, fromHubs: List<Hub>, fromIsland: Boolean,
        toLat: Double, toLon: Double, toHubs: List<Hub>, toIsland: Boolean,
    ): List<TravelMode> {
        val km = distanceKm(fromLat, fromLon, toLat, toLon)
        fun has(h: List<Hub>, t: HubType) = h.any { it.type == t }
        val out = mutableListOf<TravelMode>()
        if (km > 250 && has(fromHubs, HubType.AIRPORT) && has(toHubs, HubType.AIRPORT)) out += TravelMode.FLIGHT
        if (has(fromHubs, HubType.TRAIN) && has(toHubs, HubType.TRAIN)) out += TravelMode.TRAIN
        val mainland = !fromIsland && !toIsland
        // Buses and minivans leave from bus terminals, which almost every town has.
        if (mainland || has(fromHubs, HubType.BUS) || has(toHubs, HubType.BUS)) out += TravelMode.BUS
        if (mainland && km <= 450) out += TravelMode.VAN
        if (fromIsland || toIsland || has(fromHubs, HubType.PIER) && has(toHubs, HubType.PIER)) out += TravelMode.BOAT
        out += TravelMode.CAR
        return out.distinct()
    }
}
