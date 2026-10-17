package th.trip.planner.data

import android.content.Context
import kotlinx.serialization.json.Json
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
    val hubById: Map<String, Hub> by lazy { hubs.associateBy { it.id } }

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

    var thai: Boolean
        get() = prefs.getBoolean("thai", true)
        set(v) = prefs.edit().putBoolean("thai", v).apply()

    /** Thai Meteorological Department NWP API token (https://data.tmd.go.th). */
    var tmdToken: String
        get() = prefs.getString("tmd", "") ?: ""
        set(v) = prefs.edit().putString("tmd", v.trim()).apply()

    /** NASA FIRMS map key for satellite fire hotspots (same VIIRS/MODIS satellites GISTDA uses). */
    var firmsKey: String
        get() = prefs.getString("firms", "") ?: ""
        set(v) = prefs.edit().putString("firms", v.trim()).apply()

    /** Google Maps Platform key with Places API (New) enabled. */
    var placesKey: String
        get() = prefs.getString("places", "") ?: ""
        set(v) = prefs.edit().putString("places", v.trim()).apply()

    var checkInHour: Int
        get() = prefs.getInt("checkin_hour", 10)
        set(v) = prefs.edit().putInt("checkin_hour", v).apply()
}
