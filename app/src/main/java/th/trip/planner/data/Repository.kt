package th.trip.planner.data

import android.content.Context
import kotlinx.serialization.json.Json
import th.trip.planner.model.FlightData
import th.trip.planner.model.Hub
import th.trip.planner.model.Place
import th.trip.planner.model.Trip
import java.io.File

/** Local data: the curated place and hub library (assets), the saved trip, and settings. */
class Repository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val tripFile = File(context.filesDir, "trip.json")

    val places: List<Place> by lazy { load("places.json") }
    val hubs: List<Hub> by lazy { load("hubs.json") }
    val placeById: Map<String, Place> by lazy { places.associateBy { it.id } }
    /** Domestic flight routes, airlines and baggage rules. */
    val flights: FlightData by lazy {
        val downloaded = File(dataDir, "flights.json")
        if (downloaded.exists()) runCatching { return@lazy json.decodeFromString<FlightData>(downloaded.readText()) }
        runCatching { json.decodeFromString<FlightData>(readAsset("flights.json")) }.getOrDefault(FlightData())
    }
    val hubById: Map<String, Hub> by lazy { hubs.associateBy { it.id } }
    /** Scheduled flights, trains, buses, vans and boats (timetables). */
    val routes: List<th.trip.planner.model.Route> by lazy {
        val downloaded = File(dataDir, "routes.json")
        if (downloaded.exists()) runCatching { return@lazy json.decodeFromString<th.trip.planner.model.RouteData>(downloaded.readText()).routes }
        runCatching { json.decodeFromString<th.trip.planner.model.RouteData>(readAsset("routes.json")).routes }.getOrDefault(emptyList())
    }

    /** How to reach each terminal, station, pier and airport by local transport (hub id -> ways). */
    val access: Map<String, List<th.trip.planner.model.HubAccess>> by lazy {
        val downloaded = File(dataDir, "access.json")
        if (downloaded.exists()) runCatching { return@lazy json.decodeFromString<th.trip.planner.model.AccessData>(downloaded.readText()).access }
        runCatching { json.decodeFromString<th.trip.planner.model.AccessData>(readAsset("access.json")).access }.getOrDefault(emptyMap())
    }
    /** Bangkok rail lines (BTS, MRT, Airport Rail Link, SRT Red lines). */
    val rail: List<th.trip.planner.model.RailLine> by lazy {
        val downloaded = File(dataDir, "rail.json")
        if (downloaded.exists()) runCatching { return@lazy json.decodeFromString<th.trip.planner.model.RailData>(downloaded.readText()).lines }
        runCatching { json.decodeFromString<th.trip.planner.model.RailData>(readAsset("rail.json")).lines }.getOrDefault(emptyList())
    }

    private fun readAsset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    /** Weekly data downloaded from GitHub Pages lives here; the copy built into the app is the fallback. */
    val dataDir: File get() = File(context.filesDir, "data").apply { mkdirs() }

    private inline fun <reified T> load(name: String): List<T> {
        val downloaded = File(dataDir, name)
        if (downloaded.exists()) runCatching { return json.decodeFromString<List<T>>(downloaded.readText()) }
        return json.decodeFromString(readAsset(name))
    }

    /** Checks that downloaded text really is a usable places / hubs file before it replaces the old one. */
    fun validPlaces(text: String) = runCatching { json.decodeFromString<List<Place>>(text).size >= 5 }.getOrDefault(false)
    fun validFlights(text: String) = runCatching { json.decodeFromString<FlightData>(text).routes.size >= 5 }.getOrDefault(false)
    fun validRoutes(text: String) = runCatching { json.decodeFromString<th.trip.planner.model.RouteData>(text).routes.size >= 5 }.getOrDefault(false)
    fun validAccess(text: String) = runCatching { json.decodeFromString<th.trip.planner.model.AccessData>(text).access.size >= 5 }.getOrDefault(false)
    fun validRail(text: String) = runCatching { json.decodeFromString<th.trip.planner.model.RailData>(text).lines.size >= 3 }.getOrDefault(false)
    fun validHubs(text: String) = runCatching { json.decodeFromString<List<Hub>>(text).size >= 5 }.getOrDefault(false)

    /** Version of the downloaded data (from meta.json), "" = only the built-in data. */
    var dataVersion: String
        get() = prefs.getString("data_version", "") ?: ""
        set(v) = prefs.edit().putString("data_version", v).apply()

    fun loadTrip(): Trip {
        val t = runCatching { json.decodeFromString<Trip>(tripFile.readText()) }.getOrDefault(Trip())
        return if (t.id.isBlank()) t.copy(id = newTripId(), createdAt = System.currentTimeMillis()) else t
    }

    fun newTripId(): String = "trip-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    /** Every trip gets its own folder: trips/<id>/ holding trip.json and photos/<place>/. */
    val tripsDir: File get() = File(context.filesDir, "trips").apply { mkdirs() }
    fun tripDir(id: String): File = File(tripsDir, id).apply { mkdirs() }
    fun photoFile(tripId: String, relative: String) = File(tripDir(tripId), relative)

    /** Saves a copy of the trip into its own folder so it shows in "My trips". */
    fun archive(trip: Trip) {
        if (trip.id.isBlank()) return
        File(tripDir(trip.id), "trip.json").writeText(json.encodeToString(Trip.serializer(), trip))
    }

    fun archivedTrips(): List<Trip> = tripsDir.listFiles().orEmpty()
        .mapNotNull { dir -> runCatching { json.decodeFromString<Trip>(File(dir, "trip.json").readText()) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun archivedTrip(id: String): Trip? = runCatching { json.decodeFromString<Trip>(File(tripDir(id), "trip.json").readText()) }.getOrNull()

    /** When the app last went to the background while collecting photos (ms), 0 = not set. */
    var leftAt: Long
        get() = prefs.getLong("left_at", 0L)
        set(v) = prefs.edit().putLong("left_at", v).apply()

    fun saveTrip(trip: Trip) {
        val tmp = File(tripFile.parentFile, "trip.json.tmp")
        tmp.writeText(json.encodeToString(Trip.serializer(), trip))
        tmp.renameTo(tripFile)
    }

    /** Menu language code ("" = not chosen yet: follow the phone). Older versions stored a Thai on/off switch. */
    var lang: String
        get() = prefs.getString("lang", null) ?: if (prefs.contains("thai")) (if (prefs.getBoolean("thai", true)) "th" else "en") else ""
        set(v) = prefs.edit().putString("lang", v).apply()

    /** Set when the user leaves for Booking.com / Agoda: "placeId|source|timeMillis", "" = none. */
    var pendingBooking: String
        get() = prefs.getString("pending_booking", "") ?: ""
        set(v) = prefs.edit().putString("pending_booking", v).apply()

    var checkInHour: Int
        get() = prefs.getInt("checkin_hour", 10)
        set(v) = prefs.edit().putInt("checkin_hour", v).apply()
}
