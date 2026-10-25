package th.trip.planner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import th.trip.planner.logic.TripLogic
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val USER_AGENT = "ThaiTripPlanner/0.1 (Android; https://github.com/attasakj-blip/thai-trip-planner)"

/** A freely licensed photo with the credit that must be shown next to it. */
data class Photo(val url: String, val author: String, val license: String, val sourcePage: String)

enum class AirSource { AIR4THAI, OPEN_METEO }

/** PM2.5 reading. Station names are empty for the Open-Meteo model estimate. */
data class AirQuality(val stationTh: String, val stationEn: String, val distanceKm: Double, val pm25: Double?, val time: String, val source: AirSource)

data class DayForecast(
    val date: LocalDate,
    val tMax: Double?,
    val tMin: Double?,
    val rainMm: Double?,
    /** WMO weather code (95-99 = thunderstorm, 65/82 = heavy rain). */
    val code: Int?,
    val rainChance: Int? = null,
    val gustKmh: Double? = null,
)

/** A place from OpenStreetMap. */
data class Poi(
    val name: String,
    val address: String,
    val phone: String?,
    val website: String?,
    val hours: String?,
    val cuisine: String?,
    val emergency: Boolean,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
    val health: th.trip.planner.logic.Health.Kind? = null,
    val owner: th.trip.planner.logic.Health.Owner? = null,
    /** Logo or photo (Wikimedia Commons thumbnail) for hospitals that have one. */
    val logo: String? = null,
    val beds: Int? = null,
    val wikidata: String? = null,
    /** Wikimedia Commons page of the photo (credit). */
    val photoPage: String? = null,
    /** Short facts about a restaurant or café (keys such as "late", "view", "veg"); see ui highlightLabel. */
    val highlights: List<String> = emptyList(),
    /** Example photo of the kind of food or drink the place serves (not of the place itself), with its Commons page and dish name. */
    val dishPhoto: String? = null,
    val dishPage: String? = null,
    val dishName: String? = null,
    /** True when the dish is only a general example (nothing more specific is known about the menu). */
    val dishGuess: Boolean = false,
    val brand: String? = null,
)

/** Calls to outside services. Every call returns a Result so the UI can show "not available". */
class Remote {
    /** Where downloaded nearby-place lists are kept for offline use (set by the app). */
    var cacheDir: java.io.File? = null
    /** When this version of the app was installed (ms); saved lists from before it are refreshed. */
    var appUpdatedAt: Long = 0L
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

    /**
     * PM2.5 near a place. First the nearest Air4Thai (Pollution Control Department) station;
     * if that service can't be reached, the Open-Meteo air-quality model (CAMS) for that spot.
     */
    suspend fun airQuality(lat: Double, lon: Double): Result<AirQuality> =
        air4thai(lat, lon).recoverCatching { openMeteoAir(lat, lon).getOrThrow() }

    private suspend fun air4thai(lat: Double, lon: Double): Result<AirQuality> = runCatching {
        val root = json.parseToJsonElement(get("https://air4thai.pcd.go.th/services/getNewAQI_JSON.php")).jsonObject
        val stations = root["stations"].arr() ?: error("no stations")
        val best = stations.mapNotNull { s ->
            val o = s.obj() ?: return@mapNotNull null
            val sLat = o["lat"].num() ?: return@mapNotNull null
            val sLon = o["long"].num() ?: return@mapNotNull null
            val last = o["AQILast"].obj()
            val pm = last?.get("PM25").obj()?.get("value").num()?.takeIf { it >= 0 }
            val th = ((o["nameTH"].str() ?: "") + " " + (o["areaTH"].str() ?: "")).trim()
            val en = ((o["nameEN"].str() ?: "") + ", " + (o["areaEN"].str() ?: "")).trim(' ', ',')
            AirQuality(th, en, TripLogic.distanceKm(lat, lon, sLat, sLon), pm,
                "${last?.get("date").str() ?: ""} ${last?.get("time").str() ?: ""}".trim(), AirSource.AIR4THAI)
        }.filter { it.pm25 != null }.minByOrNull { it.distanceKm } ?: error("no readings")
        if (best.distanceKm > 80) error("no station within 80 km")
        best
    }

    private suspend fun openMeteoAir(lat: Double, lon: Double): Result<AirQuality> = runCatching {
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "pm2_5,pm10").addQueryParameter("timezone", "Asia/Bangkok")
            .build().toString()
        val cur = json.parseToJsonElement(get(url)).jsonObject["current"].obj() ?: error("no data")
        AirQuality("", "", 0.0, cur["pm2_5"].num() ?: error("no pm2.5"), cur["time"].str()?.replace('T', ' ') ?: "", AirSource.OPEN_METEO)
    }

    /** Daily forecast (rain, rain chance, temperature, gusts, weather code) from Open-Meteo. No key needed. */
    suspend fun forecast(lat: Double, lon: Double, days: Int = 7): Result<List<DayForecast>> = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("daily", "precipitation_sum,precipitation_probability_max,temperature_2m_max,temperature_2m_min,weather_code,wind_gusts_10m_max")
            .addQueryParameter("timezone", "Asia/Bangkok").addQueryParameter("forecast_days", days.coerceIn(1, 16).toString())
            .build().toString()
        val d = json.parseToJsonElement(get(url)).jsonObject["daily"].obj() ?: error("no daily data")
        val times = d["time"].arr() ?: error("no days")
        fun col(name: String, i: Int) = d[name].arr()?.getOrNull(i).num()
        times.indices.map { i ->
            DayForecast(
                LocalDate.parse(times[i].str()), col("temperature_2m_max", i), col("temperature_2m_min", i),
                col("precipitation_sum", i), col("weather_code", i)?.toInt(), col("precipitation_probability_max", i)?.toInt(), col("wind_gusts_10m_max", i),
            )
        }
    }

    /** River discharge forecast vs. its usual level (GloFAS via Open-Meteo), for flood warnings. Returns ratio max/median. */
    suspend fun floodRatio(lat: Double, lon: Double, days: Int = 7): Result<Double> = runCatching {
        val url = "https://flood-api.open-meteo.com/v1/flood".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("daily", "river_discharge,river_discharge_median")
            .addQueryParameter("forecast_days", days.coerceIn(1, 30).toString())
            .build().toString()
        val d = json.parseToJsonElement(get(url)).jsonObject["daily"].obj() ?: error("no data")
        val q = d["river_discharge"].arr()?.mapNotNull { it.num() }.orEmpty()
        val med = d["river_discharge_median"].arr()?.mapNotNull { it.num() }.orEmpty()
        if (q.isEmpty() || med.isEmpty()) error("no river here")
        val m = med.average()
        if (m < 1.0) error("river too small") // tiny streams give noisy ratios
        q.max() / m
    }

    private var fireCache: Pair<Long, List<Pair<Double, Double>>>? = null

    /**
     * Satellite fire hotspots within [radiusKm] in the last 24 hours. Uses NASA FIRMS's public
     * South-East Asia files from two VIIRS satellites (the same data GISTDA uses). No key needed.
     */
    suspend fun hotspots(lat: Double, lon: Double, radiusKm: Double = 30.0): Result<Int> = runCatching {
        val now = System.currentTimeMillis()
        val points = fireCache?.takeIf { now - it.first < 3 * 3600_000L }?.second ?: run {
            val files = listOf(
                "https://firms.modaps.eosdis.nasa.gov/data/active_fire/suomi-npp-viirs-c2/csv/SUOMI_VIIRS_C2_SouthEast_Asia_24h.csv",
                "https://firms.modaps.eosdis.nasa.gov/data/active_fire/noaa-20-viirs-c2/csv/J1_VIIRS_C2_SouthEast_Asia_24h.csv",
            )
            val all = files.flatMap { f ->
                val csv = runCatching { get(f) }.getOrNull() ?: return@flatMap emptyList()
                csv.lineSequence().drop(1).mapNotNull { line ->
                    val c = line.split(',')
                    val la = c.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                    val lo = c.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                    // Keep Thailand and its neighbours only.
                    if (la in 4.0..22.0 && lo in 96.0..107.0) la to lo else null
                }.toList()
            }
            if (all.isEmpty() && files.isNotEmpty()) runCatching { get(files[0]) }.getOrThrow()
            all.also { fireCache = now to it }
        }
        points.count { (la, lo) -> TripLogic.distanceKm(lat, lon, la, lo) <= radiusKm }
    }

    /**
     * Nearby places from OpenStreetMap (Overpass API), nearest first. [type] is one of
     * restaurant, cafe, hospital, police, lodging. Free, no key; data © OpenStreetMap contributors.
     * Medical places are filtered strictly (see [th.trip.planner.logic.Health]) and get logos from Wikidata.
     */
    suspend fun nearby(lat: Double, lon: Double, type: String, thai: Boolean, limit: Int = 60): Result<List<Poi>> = runCatching {
        cached("nb:$type:${"%.3f".format(lat)}:${"%.3f".format(lon)}:$thai") { loadNearby(lat, lon, type, thai, limit) }
    }

    private suspend fun loadNearby(lat: Double, lon: Double, type: String, thai: Boolean, limit: Int): List<Poi> {
        val medical = type == "hospital"
        val filters = when (type) {
            "restaurant" -> listOf("[\"amenity\"~\"^(restaurant|food_court)$\"]")
            "cafe" -> listOf("[\"amenity\"=\"cafe\"]")
            "hospital" -> listOf("[\"amenity\"~\"^(hospital|clinic|doctors)$\"]", "[\"healthcare\"~\"^(hospital|clinic)$\"]")
            "police" -> listOf("[\"amenity\"=\"police\"]")
            "fire" -> listOf("[\"amenity\"=\"fire_station\"]")
            else -> listOf("[\"tourism\"~\"^(hotel|guest_house|hostel|resort|motel|apartment)$\"]")
        }
        val radius = when (type) { "hospital" -> 30000; "police", "fire" -> 25000; "restaurant", "cafe" -> 4000; else -> 6000 }
        val parts = filters.joinToString("") { "nwr$it(around:$radius,$lat,$lon);" }
        val query = "[out:json][timeout:50];($parts);out center tags 400;"
        val elements = json.parseToJsonElement(overpass(query)).jsonObject["elements"].arr() ?: return emptyList()
        val list = elements.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val t = o["tags"].obj() ?: return@mapNotNull null
            val tags = t.mapValues { it.value.str() ?: "" }
            val isArea = o["type"].str() != "node"
            val kind = if (medical) th.trip.planner.logic.Health.classify(tags, isArea) ?: return@mapNotNull null else null
            val pLat = o["lat"].num() ?: o["center"].obj()?.get("lat").num() ?: return@mapNotNull null
            val pLon = o["lon"].num() ?: o["center"].obj()?.get("lon").num() ?: return@mapNotNull null
            val name = (if (thai) tags["name:th"] else tags["name:en"])?.ifBlank { null } ?: tags["name"]?.ifBlank { null } ?: return@mapNotNull null
            val address = listOfNotNull(
                tags["addr:housenumber"], (if (thai) null else tags["addr:street:en"]) ?: tags["addr:street"],
                tags["addr:subdistrict"], tags["addr:district"], tags["addr:city"], tags["addr:province"],
            ).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val image = tags["image"]?.takeIf { it.startsWith("File:") }?.let { th.trip.planner.logic.CommonsThumb.url(it) }
                ?: tags["wikimedia_commons"]?.takeIf { it.startsWith("File:") }?.let { th.trip.planner.logic.CommonsThumb.url(it) }
            Poi(
                name = name,
                address = address,
                phone = (tags["phone"] ?: tags["contact:phone"] ?: tags["emergency:phone"])?.split(';')?.first()?.trim()?.ifBlank { null },
                website = tags["website"] ?: tags["contact:website"],
                hours = tags["opening_hours"],
                cuisine = tags["cuisine"]?.replace(';', ' ')?.replace('_', ' '),
                emergency = tags["emergency"] == "yes",
                lat = pLat, lon = pLon,
                distanceKm = TripLogic.distanceKm(lat, lon, pLat, pLon),
                health = kind,
                owner = if (medical) th.trip.planner.logic.Health.owner(tags) else null,
                logo = image,
                beds = tags["beds"]?.toIntOrNull(),
                wikidata = tags["wikidata"]?.takeIf { it.matches(Regex("Q\\d+")) },
            )
        }.distinctBy { it.name to (it.lat * 200).toInt() }
        val sorted = when (type) {
            // Hospitals first (nearest first), then health centres and clinics.
            "hospital" -> list.sortedWith(compareBy<Poi>({ it.health?.ordinal ?: 9 }, { it.distanceKm }))
            // Police stations before small police boxes.
            "police" -> list.sortedWith(compareBy<Poi>({ if (isPoliceStation(it.name)) 0 else 1 }, { it.distanceKm }))
            else -> list.sortedBy { it.distanceKm }
        }
        val top = sorted.take(limit)
        return when (type) {
            "hospital" -> withLogos(top)
            "police" -> withCommonsPhotos(withLogos(top), lat, lon, Regex("(?i)police|ตำรวจ|สภ\\.|สน\\."))
            else -> top
        }
    }

    /**
     * Restaurants, cafés, hospitals, police and fire stations around a tourist place, prepared
     * every week from OpenStreetMap (with phones and photos added) and published on GitHub Pages.
     * Much more reliable than asking the busy public map servers from the phone. Kept on the phone
     * so it also works offline.
     */
    private suspend fun placePois(placeId: String): Map<String, kotlinx.serialization.json.JsonArray>? {
        val file = cacheDir?.let { java.io.File(it, "$placeId.json") }
        // The lists are rebuilt every week: keep a copy for a day, and drop copies older than this app version.
        val fresh = file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < 86400_000L && it.lastModified() > appUpdatedAt }
        val text = fresh?.readText() ?: runCatching { get(DataSync.BASE + "pois/$placeId.json") }.getOrNull()?.also { t -> runCatching { file?.writeText(t) } }
            ?: file?.takeIf { it.exists() }?.readText() ?: return null
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return o.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonArray)?.let { k to it } }.toMap()
    }

    /**
     * Places of [type] (restaurant, cafe, hospital, police, fire) near [placeId] from the weekly
     * lists, with distances measured from [lat]/[lon]; falls back to the live map search.
     */
    suspend fun poisFor(placeId: String?, lat: Double, lon: Double, type: String, thai: Boolean): Result<List<Poi>> {
        if (placeId != null) {
            val arr = runCatching { placePois(placeId)?.get(type) }.getOrNull()
            if (arr != null) return runCatching {
                arr.mapNotNull { e ->
                    val o = e.obj() ?: return@mapNotNull null
                    val n = o["name"].obj()
                    val name = (if (thai) n?.get("th").str() else n?.get("en").str()) ?: n?.get("th").str() ?: return@mapNotNull null
                    val pLat = o["lat"].num() ?: return@mapNotNull null
                    val pLon = o["lon"].num() ?: return@mapNotNull null
                    Poi(
                        name = name, address = o["address"].str() ?: "", phone = o["phone"].str(), website = o["website"].str(),
                        hours = o["hours"].str(), cuisine = o["cuisine"].str(), emergency = o["emergency"]?.let { it.toString() == "true" } == true,
                        lat = pLat, lon = pLon, distanceKm = TripLogic.distanceKm(lat, lon, pLat, pLon),
                        health = o["health"].str()?.let { h -> th.trip.planner.logic.Health.Kind.entries.firstOrNull { it.name == h } },
                        owner = o["owner"].str()?.let { h -> th.trip.planner.logic.Health.Owner.entries.firstOrNull { it.name == h } },
                        logo = o["photo"].str(), beds = o["beds"].num()?.toInt(), photoPage = o["photoPage"].str(),
                        highlights = o["highlights"].arr().orEmpty().mapNotNull { it.str() },
                        dishPhoto = o["dishPhoto"].str(), dishPage = o["dishPage"].str(),
                        dishName = o["dishName"].obj()?.let { d -> (if (thai) d["th"].str() else d["en"].str()) ?: d["th"].str() },
                        dishGuess = o["dishGuess"].str() == "true",
                        brand = o["brand"].str(),
                    )
                }
            }
        }
        // Not one of the app's places (e.g. GPS on the road): ask the map servers, twice if needed.
        val first = nearby(lat, lon, type, thai)
        return if (first.isSuccess) first else { kotlinx.coroutines.delay(1500); nearby(lat, lon, type, thai) }
    }

    fun isPoliceStation(name: String) = Regex("(?i)สถานีตำรวจ|สภ\\.|สน\\.|police station|tourist police|ตำรวจท่องเที่ยว").containsMatchIn(name)

    /**
     * Photos from Wikimedia Commons taken right at each place (within 150 m) whose file name
     * matches [titleMatch], e.g. a police station's sign. Best effort, one request.
     */
    private suspend fun withCommonsPhotos(list: List<Poi>, lat: Double, lon: Double, titleMatch: Regex): List<Poi> {
        if (list.all { it.logo != null }) return list
        val files = runCatching {
            val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("formatversion", "2")
                .addQueryParameter("list", "geosearch").addQueryParameter("gsnamespace", "6")
                .addQueryParameter("gscoord", "$lat|$lon").addQueryParameter("gsradius", "10000").addQueryParameter("gslimit", "500")
                .build().toString()
            json.parseToJsonElement(get(url)).jsonObject["query"].obj()?.get("geosearch").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val title = o["title"].str() ?: return@mapNotNull null
                if (!titleMatch.containsMatchIn(title) || !Regex("(?i)\\.(jpe?g|png)$").containsMatchIn(title)) return@mapNotNull null
                Triple(title, o["lat"].num() ?: return@mapNotNull null, o["lon"].num() ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return list
        return list.map { p ->
            if (p.logo != null) return@map p
            val near = files.minByOrNull { TripLogic.distanceKm(p.lat, p.lon, it.second, it.third) }
            if (near != null && TripLogic.distanceKm(p.lat, p.lon, near.second, near.third) <= 0.15) p.copy(logo = th.trip.planner.logic.CommonsThumb.url(near.first, 320)) else p
        }
    }

    /** Fills in hospital logos (or a photo) from Wikidata for places linked to it. Best effort. */
    private suspend fun withLogos(list: List<Poi>): List<Poi> {
        val ids = list.filter { it.logo == null }.mapNotNull { it.wikidata }.distinct().take(50)
        if (ids.isEmpty()) return list
        val files = runCatching {
            val url = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "wbgetentities").addQueryParameter("format", "json")
                .addQueryParameter("props", "claims").addQueryParameter("ids", ids.joinToString("|")).build().toString()
            val entities = json.parseToJsonElement(get(url)).jsonObject["entities"].obj() ?: return@runCatching emptyMap()
            entities.mapNotNull { (id, ent) ->
                val claims = ent.obj()?.get("claims").obj() ?: return@mapNotNull null
                val file = listOf("P154", "P18").firstNotNullOfOrNull { p ->
                    claims[p].arr()?.firstOrNull().obj()?.get("mainsnak").obj()?.get("datavalue").obj()?.get("value").str()
                } ?: return@mapNotNull null
                id to file
            }.toMap()
        }.getOrDefault(emptyMap())
        return list.map { p -> files[p.wikidata]?.let { p.copy(logo = th.trip.planner.logic.CommonsThumb.url(it)) } ?: p }
    }

    // Public Overpass servers. They are free but often slow or busy, so we ask them all at once
    // and use whichever answers first.
    private val overpassHosts = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    private val slowHttp = http.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    private suspend fun postOverpass(host: String, query: String): String {
        val body = okhttp3.FormBody.Builder().add("data", query).build()
        val call = slowHttp.newCall(Request.Builder().url(host).post(body).build())
        val text = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val r = runCatching {
                        response.use { if (!it.isSuccessful) error("HTTP ${it.code}"); it.body?.string() ?: error("empty body") }
                    }
                    if (cont.isActive) cont.resumeWith(r)
                }
            })
        }
        // A busy server can answer 200 with no data and a "runtime error" remark.
        val compact = text.replace(" ", "").replace("\n", "")
        if (!text.trimStart().startsWith("{") || ("runtime error" in text && "\"elements\":[]" in compact))
            error("server busy")
        return text
    }

    private suspend fun overpass(query: String): String = kotlinx.coroutines.coroutineScope {
        val results = kotlinx.coroutines.channels.Channel<Result<String>>(overpassHosts.size)
        val jobs = overpassHosts.mapIndexed { i, host ->
            launch {
                // The last server is a backup: only ask it if the others are slow.
                if (i == overpassHosts.lastIndex) kotlinx.coroutines.delay(8000)
                results.send(runCatching { postOverpass(host, query) })
            }
        }
        var last: Throwable? = null
        repeat(overpassHosts.size) {
            val r = results.receive()
            if (r.isSuccess) { jobs.forEach { it.cancel() }; return@coroutineScope r.getOrThrow() }
            last = r.exceptionOrNull()
        }
        throw last ?: IllegalStateException("no server")
    }

    private val cache = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T =
        (cache[key] as T?) ?: load().also { cache[key] = it }

    /**
     * Bus terminals ("bus") or long-distance railway stations ("train") near a point, nearest
     * first, from OpenStreetMap.
     */
    suspend fun stations(lat: Double, lon: Double, kind: String, thai: Boolean, radiusKm: Int = 35): Result<List<Poi>> = runCatching {
        cached("st:$kind:${"%.3f".format(lat)}:${"%.3f".format(lon)}:$thai") {
            val filter = if (kind == "train") "[\"railway\"=\"station\"][\"station\"!~\"^(subway|light_rail|monorail)$\"]" else "[\"amenity\"=\"bus_station\"]"
            val query = "[out:json][timeout:25];(nwr$filter(around:${radiusKm * 1000},$lat,$lon););out center tags 60;"
            val elements = json.parseToJsonElement(overpass(query)).jsonObject["elements"].arr() ?: JsonArray(emptyList())
            elements.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val t = o["tags"].obj() ?: return@mapNotNull null
                val pLat = o["lat"].num() ?: o["center"].obj()?.get("lat").num() ?: return@mapNotNull null
                val pLon = o["lon"].num() ?: o["center"].obj()?.get("lon").num() ?: return@mapNotNull null
                val name = (if (thai) t["name:th"].str() else t["name:en"].str()) ?: t["name"].str() ?: return@mapNotNull null
                Poi(name, "", t["phone"].str(), t["website"].str(), t["opening_hours"].str(), null, false, pLat, pLon, TripLogic.distanceKm(lat, lon, pLat, pLon))
            }.distinctBy { it.name }.sortedBy { it.distanceKm }
        }
    }

    /** Road distance (km) and driving time (minutes) from the public OSRM server. */
    suspend fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Result<Pair<Double, Int>> = runCatching {
        cached("osrm:%.4f,%.4f;%.4f,%.4f".format(fromLon, fromLat, toLon, toLat)) {
            val text = get("https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;$toLon,$toLat?overview=false")
            val r = json.parseToJsonElement(text).jsonObject["routes"].arr()?.firstOrNull().obj() ?: error("no route")
            (r["distance"].num() ?: error("no distance")) / 1000.0 to ((r["duration"].num() ?: 0.0) / 60.0).toInt()
        }
    }

    /** Finds an address in Thailand (OpenStreetMap Nominatim). */
    suspend fun geocode(address: String): Result<Pair<Double, Double>> = runCatching {
        cached("geo:$address") {
            val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json").addQueryParameter("limit", "1")
                .addQueryParameter("countrycodes", "th").addQueryParameter("q", address).build().toString()
            val first = json.parseToJsonElement(get(url)).arr()?.firstOrNull().obj() ?: error("not found")
            (first["lat"].num() ?: error("no lat")) to (first["lon"].num() ?: error("no lon"))
        }
    }

    /**
     * Public-transport lines (bus, songthaew, BTS/MRT/Airport Rail Link, trains, boats) that stop
     * near both points, with all their stops, from OpenStreetMap.
     */
    suspend fun transitLines(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, thai: Boolean, walkM: Int = 800): Result<List<th.trip.planner.logic.TransitLine>> = runCatching {
        cached("tr:%.4f,%.4f;%.4f,%.4f:$thai".format(fromLat, fromLon, toLat, toLon)) {
            fun near(lat: Double, lon: Double) =
                "node(around:$walkM,$lat,$lon)[public_transport~\"^(platform|stop_position)$\"];" +
                    "node(around:$walkM,$lat,$lon)[highway=bus_stop];" +
                    "node(around:$walkM,$lat,$lon)[railway~\"^(station|halt|stop)$\"];" +
                    "node(around:$walkM,$lat,$lon)[amenity=ferry_terminal];"
            val routes = "^(bus|trolleybus|share_taxi|minibus|subway|light_rail|monorail|train|tram|ferry)$"
            val query = "[out:json][timeout:40];(${near(fromLat, fromLon)})->.a;(${near(toLat, toLon)})->.b;" +
                "(rel(bn.a)[type=route][route~\"$routes\"];rel(bn.b)[type=route][route~\"$routes\"];)->.r;" +
                ".r out body;node(r.r);out body;"
            val elements = json.parseToJsonElement(overpass(query)).jsonObject["elements"].arr() ?: JsonArray(emptyList())
            val nodes = HashMap<Long, th.trip.planner.logic.TransitStop>()
            for (e in elements) {
                val o = e.obj() ?: continue
                if (o["type"].str() != "node") continue
                val id = o["id"].num()?.toLong() ?: continue
                val t = o["tags"].obj()
                val name = t?.let { (if (thai) it["name:th"].str() else it["name:en"].str()) ?: it["name"].str() } ?: ""
                nodes[id] = th.trip.planner.logic.TransitStop(id, name, o["lat"].num() ?: continue, o["lon"].num() ?: continue)
            }
            elements.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                if (o["type"].str() != "relation") return@mapNotNull null
                val t = o["tags"].obj() ?: return@mapNotNull null
                val members = o["members"].arr()?.mapNotNull { it.obj() }?.filter { it["type"].str() == "node" } ?: return@mapNotNull null
                val stopsByRole = members.filter { it["role"].str()?.startsWith("stop") == true }
                val use = stopsByRole.ifEmpty { members.filter { it["role"].str()?.startsWith("platform") == true } }
                val stops = use.mapNotNull { m -> nodes[m["ref"].num()?.toLong()] }
                if (stops.size < 2) return@mapNotNull null
                val kind = when (t["route"].str()) {
                    "share_taxi", "minibus" -> th.trip.planner.logic.TransitKind.SONGTHAEW
                    "subway", "light_rail", "monorail", "tram" -> th.trip.planner.logic.TransitKind.METRO
                    "train" -> th.trip.planner.logic.TransitKind.TRAIN
                    "ferry" -> th.trip.planner.logic.TransitKind.BOAT
                    else -> th.trip.planner.logic.TransitKind.BUS
                }
                val name = (if (thai) t["name:th"].str() else t["name:en"].str()) ?: t["name"].str() ?: ""
                th.trip.planner.logic.TransitLine(
                    o["id"].num()?.toLong() ?: return@mapNotNull null,
                    t["ref"].str() ?: "", name, kind,
                    (if (thai) t["operator:th"].str() else t["operator:en"].str()) ?: t["operator"].str() ?: "",
                    stops,
                )
            }
        }
    }

    /**
     * A named building, shop, temple, school or similar right next to a point (within [radiusM]),
     * so a bus stop can be described as "near 7-Eleven". Null when nothing is found.
     */
    suspend fun landmark(lat: Double, lon: Double, thai: Boolean, radiusM: Int = 150): Result<Pair<String, Double>?> = runCatching {
        cached("lm:%.5f,%.5f:$thai".format(lat, lon)) {
            val query = "[out:json][timeout:20];nwr(around:$radiusM,$lat,$lon)[name]" +
                "[~\"^(amenity|shop|tourism|leisure|office|historic|building|railway|public_transport)$\"~\".\"];out center tags 60;"
            val elements = json.parseToJsonElement(overpass(query)).jsonObject["elements"].arr() ?: JsonArray(emptyList())
            val prominent = Regex("^(place_of_worship|school|university|college|hospital|clinic|marketplace|bank|fuel|post_office|police|townhall|cinema|mall|supermarket|convenience|department_store|attraction|museum|hotel|park|station)$")
            val best = elements.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val t = o["tags"].obj() ?: return@mapNotNull null
                // Skip the stops themselves.
                if (t["highway"].str() == "bus_stop" || t["public_transport"].str() in setOf("platform", "stop_position")) return@mapNotNull null
                val name = (if (thai) t["name:th"].str() else t["name:en"].str()) ?: t["name"].str() ?: return@mapNotNull null
                val la = o["lat"].num() ?: o["center"].obj()?.get("lat").num() ?: return@mapNotNull null
                val lo = o["lon"].num() ?: o["center"].obj()?.get("lon").num() ?: return@mapNotNull null
                val km = th.trip.planner.logic.TripLogic.distanceKm(lat, lon, la, lo)
                val kind = listOf("amenity", "shop", "tourism", "leisure", "railway").firstNotNullOfOrNull { t[it].str() } ?: ""
                Triple(name, km, km * (if (prominent.matches(kind)) 0.5 else 1.0))
            }.minByOrNull { it.third }
            Opt(best?.let { it.first to it.second })
        }.v
    }

    /** Lets "nothing found" be cached too. */
    private class Opt<T>(val v: T?)
}
