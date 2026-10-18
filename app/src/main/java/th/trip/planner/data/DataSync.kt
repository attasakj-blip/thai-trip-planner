package th.trip.planner.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches the place, hub and flight-route library that GitHub Actions publishes to GitHub Pages every
 * Wednesday 0:00 Thai time. Runs when the app opens; with no internet the app keeps using
 * what it already has (last download, or the copy built into the app).
 */
object DataSync {
    const val BASE = "https://attasakj-blip.github.io/thai-trip-planner/data/v1/"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun get(name: String): String? = runCatching {
        http.newCall(Request.Builder().url(BASE + name).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    /** Returns true when newer data was downloaded (it is used from the next time the app opens). */
    fun run(repo: Repository): Boolean {
        val meta = get("meta.json") ?: return false
        val version = runCatching { Json.parseToJsonElement(meta).jsonObject["version"]?.jsonPrimitive?.content }.getOrNull() ?: return false
        if (version == repo.dataVersion) return false
        val places = get("places.json")?.takeIf { repo.validPlaces(it) } ?: return false
        val hubs = get("hubs.json")?.takeIf { repo.validHubs(it) } ?: return false
        write(File(repo.dataDir, "places.json"), places)
        write(File(repo.dataDir, "hubs.json"), hubs)
        get("flights.json")?.takeIf { repo.validFlights(it) }?.let { write(File(repo.dataDir, "flights.json"), it) }
        get("routes.json")?.takeIf { repo.validRoutes(it) }?.let { write(File(repo.dataDir, "routes.json"), it) }
        get("access.json")?.takeIf { repo.validAccess(it) }?.let { write(File(repo.dataDir, "access.json"), it) }
        get("rail.json")?.takeIf { repo.validRail(it) }?.let { write(File(repo.dataDir, "rail.json"), it) }
        repo.dataVersion = version
        return true
    }

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
