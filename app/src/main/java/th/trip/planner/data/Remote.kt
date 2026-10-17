package th.trip.planner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import th.trip.planner.logic.TripLogic
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.cos

const val USER_AGENT = "ThaiTripPlanner/0.1 (Android; https://github.com/attasakj-blip/thai-trip-planner)"

/** A freely licensed photo with the credit that must be shown next to it. */
data class Photo(val url: String, val author: String, val license: String, val sourcePage: String)

data class AirQuality(val station: String, val distanceKm: Double, val pm25: Double?, val time: String)

data class DayForecast(val date: LocalDate, val tMax: Double?, val tMin: Double?, val rainMm: Double?, val cond: Int?)

data class Poi(
    val name: String,
    val address: String,
    val rating: Double?,
    val ratingCount: Int?,
    val phone: String?,
    val mapsUri: String?,
    val openNow: Boolean?,
    val photoUrl: String?,
    val photoCredit: String?,
    val distanceKm: Double?,
)

/** Calls to outside services. Every call returns a Result so the UI can show "not available". */
class Remote {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val photoCache = ConcurrentHashMap<String, List<Photo>>()

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.string() ?: error("empty body")
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    /**
     * Freely licensed photos from the images used in the given English Wikipedia articles
     * (hosted on Wikimedia Commons). Only Creative Commons / public-domain images are kept.
     */
    suspend fun photos(titles: List<String>, max: Int = 4): Result<List<Photo>> = runCatching {
        val key = titles.joinToString("|")
        photoCache[key]?.let { return@runCatching it }
        val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").addQueryParameter("generator", "images")
            .addQueryParameter("titles", key).addQueryParameter("gimlimit", "50")
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|extmetadata|mime")
            .addQueryParameter("iiurlwidth", "1000")
            .addQueryParameter("iiextmetadatafilter", "Artist|LicenseShortName")
            .build().toString()
        val root = json.parseToJsonElement(get(url)).jsonObject
        val pages = root["query"].obj()?.get("pages").arr() ?: JsonArray(emptyList())
        val skip = Regex("(?i)flag|seal|logo|map|locator|icon|symbol|emblem|coat_of_arms|signature")
        val out = pages.mapNotNull { p ->
            val title = p.obj()?.get("title").str() ?: return@mapNotNull null
            if (skip.containsMatchIn(title)) return@mapNotNull null
            val info = p.obj()?.get("imageinfo").arr()?.firstOrNull().obj() ?: return@mapNotNull null
            if (info["mime"].str() != "image/jpeg") return@mapNotNull null
            val meta = info["extmetadata"].obj()
            val license = meta?.get("LicenseShortName").obj()?.get("value").str() ?: return@mapNotNull null
            val free = license.startsWith("CC", true) || license.contains("public domain", true) ||
                license.startsWith("PD", true) || license.startsWith("GFDL", true)
            if (!free) return@mapNotNull null
            val artist = meta?.get("Artist").obj()?.get("value").str()?.let(::stripHtml)?.ifBlank { null } ?: "Wikimedia Commons"
            Photo(
                url = info["thumburl"].str() ?: info["url"].str() ?: return@mapNotNull null,
                author = artist, license = license,
                sourcePage = info["descriptionurl"].str() ?: "https://commons.wikimedia.org",
            )
        }.take(max)
        photoCache[key] = out
        out
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

    /** Nearest Air4Thai (Pollution Control Department) station and its latest PM2.5 reading. */
    suspend fun airQuality(lat: Double, lon: Double): Result<AirQuality> = runCatching {
        val root = json.parseToJsonElement(get("http://air4thai.pcd.go.th/services/getNewAQI_JSON.php")).jsonObject
        val stations = root["stations"].arr() ?: error("no stations")
        val best = stations.mapNotNull { s ->
            val o = s.obj() ?: return@mapNotNull null
            val sLat = o["lat"].num() ?: return@mapNotNull null
            val sLon = o["long"].num() ?: return@mapNotNull null
            val last = o["AQILast"].obj()
            val pm = last?.get("PM25").obj()?.get("value").num()?.takeIf { it >= 0 }
            val name = (o["nameTH"].str() ?: "") + " " + (o["areaTH"].str() ?: "")
            AirQuality(name.trim(), TripLogic.distanceKm(lat, lon, sLat, sLon), pm,
                "${last?.get("date").str() ?: ""} ${last?.get("time").str() ?: ""}".trim())
        }.filter { it.pm25 != null }.minByOrNull { it.distanceKm } ?: error("no readings")
        if (best.distanceKm > 80) error("no station within 80 km")
        best
    }

    /** Daily forecast from the Thai Meteorological Department NWP API. Needs a token. */
    suspend fun tmdDaily(lat: Double, lon: Double, start: LocalDate, days: Int, token: String): Result<List<DayForecast>> = runCatching {
        require(token.isNotBlank()) { "no TMD token" }
        val url = "https://data.tmd.go.th/nwpapi/v1/forecast/location/daily/at".toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat.toString()).addQueryParameter("lon", lon.toString())
            .addQueryParameter("fields", "tc_max,tc_min,rain,cond")
            .addQueryParameter("date", start.toString()).addQueryParameter("duration", days.coerceIn(1, 7).toString())
            .build().toString()
        val root = json.parseToJsonElement(get(url, mapOf("accept" to "application/json", "authorization" to "Bearer $token"))).jsonObject
        val forecasts = root["WeatherForecasts"].arr()?.firstOrNull().obj()?.get("forecasts").arr() ?: error("no forecasts")
        forecasts.mapNotNull { f ->
            val o = f.obj() ?: return@mapNotNull null
            val time = o["time"].str() ?: return@mapNotNull null
            val d = o["data"].obj()
            DayForecast(LocalDate.parse(time.take(10)), d?.get("tc_max").num(), d?.get("tc_min").num(), d?.get("rain").num(), d?.get("cond").num()?.toInt())
        }
    }

    /** Number of satellite fire hotspots within [radiusKm] in the last [days] days (NASA FIRMS, VIIRS). */
    suspend fun hotspots(lat: Double, lon: Double, key: String, radiusKm: Double = 30.0, days: Int = 2): Result<Int> = runCatching {
        require(key.isNotBlank()) { "no FIRMS key" }
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        val box = String.format(java.util.Locale.US, "%.3f,%.3f,%.3f,%.3f", lon - dLon, lat - dLat, lon + dLon, lat + dLat)
        val csv = get("https://firms.modaps.eosdis.nasa.gov/api/area/csv/$key/VIIRS_SNPP_NRT/$box/$days")
        if (!csv.startsWith("latitude")) error(csv.take(80))
        csv.lines().drop(1).count { it.isNotBlank() }
    }

    /**
     * Nearby places from Google Places API (New). [type] is a Places type such as
     * "restaurant", "cafe", "hospital" or "police". Results are shown live and never stored,
     * as the Google Maps Platform terms require.
     */
    suspend fun nearby(lat: Double, lon: Double, type: String, key: String, thai: Boolean, byDistance: Boolean): Result<List<Poi>> = runCatching {
        require(key.isNotBlank()) { "no Places key" }
        val body = buildJsonObject {
            putJsonArray("includedTypes") { add(JsonPrimitive(type)) }
            put("maxResultCount", 20)
            put("languageCode", if (thai) "th" else "en")
            put("rankPreference", if (byDistance) "DISTANCE" else "POPULARITY")
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", lat); put("longitude", lon) }
                    put("radius", if (byDistance) 20000.0 else 8000.0)
                }
            }
        }.toString()
        val fields = "places.displayName,places.formattedAddress,places.rating,places.userRatingCount," +
            "places.nationalPhoneNumber,places.googleMapsUri,places.location,places.photos,places.currentOpeningHours.openNow"
        val text = withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://places.googleapis.com/v1/places:searchNearby")
                .header("X-Goog-Api-Key", key).header("X-Goog-FieldMask", fields)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) error("HTTP ${r.code} ${t.take(120)}")
                t
            }
        }
        val places = json.parseToJsonElement(text).jsonObject["places"].arr() ?: return@runCatching emptyList()
        places.mapNotNull { p ->
            val o = p.obj() ?: return@mapNotNull null
            val loc = o["location"].obj()
            val pLat = loc?.get("latitude").num()
            val pLon = loc?.get("longitude").num()
            val photo = o["photos"].arr()?.firstOrNull().obj()
            val photoName = photo?.get("name").str()
            Poi(
                name = o["displayName"].obj()?.get("text").str() ?: return@mapNotNull null,
                address = o["formattedAddress"].str() ?: "",
                rating = o["rating"].num(),
                ratingCount = o["userRatingCount"].num()?.toInt(),
                phone = o["nationalPhoneNumber"].str(),
                mapsUri = o["googleMapsUri"].str(),
                openNow = (o["currentOpeningHours"].obj()?.get("openNow") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull(),
                photoUrl = photoName?.let { "https://places.googleapis.com/v1/$it/media?maxWidthPx=600&key=$key" },
                photoCredit = photo?.get("authorAttributions").arr()?.firstOrNull().obj()?.get("displayName").str(),
                distanceKm = if (pLat != null && pLon != null) TripLogic.distanceKm(lat, lon, pLat, pLon) else null,
            )
        }
    }
}
