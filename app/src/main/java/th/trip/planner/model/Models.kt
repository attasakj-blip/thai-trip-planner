package th.trip.planner.model

import kotlinx.serialization.Serializable

/** Text in Thai and English. */
@Serializable
data class Txt(val th: String, val en: String) {
    fun get(thai: Boolean) = if (thai) th else en
}

enum class Region { NORTH, NORTHEAST, CENTRAL, BANGKOK, EAST, WEST, SOUTH_ANDAMAN, SOUTH_GULF }

/** A festival or a season that makes a place worth visiting at a certain time. */
@Serializable
data class Highlight(
    val name: Txt,
    /** Months 1..12 when it usually happens. */
    val months: List<Int>,
    val reason: Txt,
)

/** A known closure, e.g. a national park closed in the rainy season. Months 1..12. */
@Serializable
data class Closure(val months: List<Int>, val note: Txt)

@Serializable
data class Place(
    val id: String,
    val name: Txt,
    val province: Txt,
    val region: Region,
    val lat: Double,
    val lon: Double,
    val description: Txt,
    /** English Wikipedia article titles used to fetch freely licensed photos. */
    val wikiTitles: List<String> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val closures: List<Closure> = emptyList(),
    val island: Boolean = false,
    /** Places on the same island share this id (null = mainland, or an island joined by road such as Phuket). */
    val islandGroup: String? = null,
    /** Ids of transport hubs that serve this place. */
    val hubIds: List<String> = emptyList(),
)

/** A freely licensed photo of a place (Wikimedia Commons), with the credit the licence asks for. */
@Serializable
data class PlacePhoto(val url: String, val author: String = "", val license: String = "", val page: String = "")

enum class HubType { AIRPORT, BUS, TRAIN, PIER }

@Serializable
data class Hub(
    val id: String,
    val name: Txt,
    val type: HubType,
    val province: Txt,
    val lat: Double,
    val lon: Double,
    val phone: String? = null,
    val website: String? = null,
    val islandGroup: String? = null,
)

/** CAR = car, taxi or ride-hailing (Grab, Bolt, LINE MAN); VAN = shared minivan from a terminal. */
enum class TravelMode { FLIGHT, TRAIN, BUS, BOAT, CAR, VAN }

/** One scheduled run: a time per stop (null = doesn't stop), "HH:MM", hours may pass 24 for after midnight. */
@Serializable
data class RouteTrip(val times: List<String?>, val code: String? = null)

/** Frequent services: one every [everyMin] minutes between [first] and [last]; [minutes] = ride time to each stop. */
@Serializable
data class Headway(val first: String, val last: String, val everyMin: Int, val minutes: List<Int>? = null)

@Serializable
data class Seasonal(val months: List<Int>, val note: Txt? = null)

/** A scheduled public service (flight, train, bus, van or boat) between hubs. */
@Serializable
data class Route(
    val id: String,
    val mode: TravelMode,
    val operator: Txt,
    val name: Txt,
    val stops: List<String>,
    val trips: List<RouteTrip> = emptyList(),
    val headway: Headway? = null,
    /** ISO weekdays it runs (1 = Monday); empty = every day. */
    val days: List<Int> = emptyList(),
    val seasonal: Seasonal? = null,
    val fare: Txt? = null,
    val bookUrl: String? = null,
    val source: String? = null,
    val checked: String? = null,
)

@Serializable
data class RouteData(val updated: String = "", val routes: List<Route> = emptyList())

@Serializable
data class Airline(
    val code: String,
    val name: String,
    val website: String? = null,
    val carryOnKg: Int? = null,
    val carryOnNote: Txt? = null,
    val checkedNote: Txt? = null,
)

/** Airlines flying between airports [a] and [b] (hub ids, either direction). */
@Serializable
data class FlightRoute(val a: String, val b: String, val airlines: List<String> = emptyList(), val minutes: Int? = null, val perDay: String? = null)

@Serializable
data class FlightData(
    val updated: String = "",
    val source: String = "",
    val airlines: List<Airline> = emptyList(),
    val routes: List<FlightRoute> = emptyList(),
)

enum class GapPurpose { WORK, VISIT, TRAVEL, OTHER }

/** One place in the trip with the calendar dates (ISO yyyy-MM-dd) spent there. Dates may be non-contiguous. */
@Serializable
data class Stop(val placeId: String, val dates: List<String> = emptyList())

/** A travel leg. index 0 = origin -> first stop, i = stop i-1 -> stop i, last = last stop -> end hub. */
@Serializable
data class Leg(
    val mode: TravelMode? = null,
    val date: String? = null,
    val note: String = "",
    /** The chosen travel option (its connections, see Itinerary.Journey.signature) and departure (minutes after midnight). */
    val plan: String? = null,
    val depart: Int? = null,
    /** The kind of travel the user prefers for this leg (a TravelMode name), "ALL" to see every way, null = not asked yet. */
    val prefer: String? = null,
    /** When the traveller wants to leave the place (minutes after midnight of [date]); null = not asked yet. */
    val leaveAt: Int? = null,
    /** The chosen local way for a short leg in or near town (walk, taxi, rail, a bus line…), see the rejoin screen's Way keys. */
    val local: String? = null,
)

/** One way to get to or from a terminal, station, pier or airport by local transport. */
@Serializable
data class HubAccess(
    /** BTS, MRT, ARL, SRT_RED, BRT, GOLD, BUS, AC_BUS, AIRPORT_BUS, VAN, SONGTHAEW, SHUTTLE, BOAT, TAXI, WALK */
    val kind: String,
    val ref: String? = null,
    val line: Txt? = null,
    val color: String? = null,
    val from: Txt? = null,
    val board: Txt? = null,
    val hours: String? = null,
    val everyMin: Int? = null,
    val minutes: Int? = null,
    val fare: Txt? = null,
    val note: Txt? = null,
    val source: String? = null,
    val checked: String? = null,
)

@Serializable
data class AccessData(val updated: String? = null, val access: Map<String, List<HubAccess>> = emptyMap())

/** A Bangkok rail line (BTS, MRT, Airport Rail Link, SRT Red lines) with its stations in order. */
@Serializable
data class RailStation(val id: String, val name: Txt, val lat: Double, val lon: Double)

@Serializable
data class RailLine(
    val id: String,
    /** BTS, MRT, ARL, SRT_RED, GOLD, MONORAIL */
    val kind: String,
    val name: Txt,
    val color: String,
    val stations: List<RailStation>,
    val loop: Boolean = false,
    /** Typical minutes between stations. */
    val minutesPerStop: Double = 2.5,
)

@Serializable
data class RailData(val updated: String? = null, val lines: List<RailLine> = emptyList())

@Serializable
data class Lodging(
    val name: String = "",
    val url: String = "",
    val note: String = "",
    val address: String = "",
    val phone: String = "",
    /** Booking or reference number. */
    val bookingRef: String = "",
    /** Where it was booked: "booking.com", "agoda", "osm", or "" when typed in by the user. */
    val source: String = "",
    /** Where the stay is, when known (picked from the map list, or found from the address). */
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val isEmpty: Boolean get() = name.isBlank() && address.isBlank() && phone.isBlank() && bookingRef.isBlank() && note.isBlank()
}

@Serializable
data class Trip(
    /** Folder name for this trip under "My trips". */
    val id: String = "",
    val createdAt: Long = 0,
    /** Stops in the visiting order chosen by the user. */
    val stops: List<Stop> = emptyList(),
    /** Purpose of each date that falls in a gap between visiting days. key = ISO date. */
    val gapPurposes: Map<String, GapPurpose> = emptyMap(),
    val warningsConfirmed: Boolean = false,
    val lodgings: Map<String, Lodging> = emptyMap(),
    val originCity: String = "",
    val originCountry: String = "Thailand",
    val originHubId: String? = null,
    val endHubId: String? = null,
    val legs: List<Leg> = emptyList(),
    val activated: Boolean = false,
    val finished: Boolean = false,
    /** Daily check-in answers. key = ISO date, value = on plan? */
    val checkIns: Map<String, Boolean> = emptyMap(),
    /** The last time the traveller used GPS to get back onto the plan (null = never). */
    val rejoin: RejoinInfo? = null,
)

/**
 * A way back onto the plan picked from the trip summary: from where (GPS), when, to which
 * part of the plan ([target]: "s<stop>" or "l<leg>"), by which way ([way], see the rejoin
 * screen), and what had to change in the rest of the trip.
 */
@Serializable
data class RejoinInfo(
    val atMillis: Long,
    val fromLat: Double,
    val fromLon: Double,
    val target: String,
    val way: String,
    val wayTitle: String,
    /** Expected arrival at the join point, ISO local date-time. */
    val arrive: String,
    /** What changed in the rest of the trip, one line each (in the language used at the time). */
    val notes: List<String> = emptyList(),
)
