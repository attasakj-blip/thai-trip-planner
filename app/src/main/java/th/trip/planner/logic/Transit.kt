package th.trip.planner.logic

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TransitStop(val id: Long, val name: String, val lat: Double, val lon: Double)

enum class TransitKind { BUS, SONGTHAEW, METRO, TRAIN, BOAT }

/** One public-transport line (from OpenStreetMap), with its stops in travel order. */
data class TransitLine(
    val id: Long,
    val ref: String,
    val name: String,
    val kind: TransitKind,
    val operator: String,
    val stops: List<TransitStop>,
)

/** Riding [line] from [board] to [alight]. */
data class Ride(val line: TransitLine, val board: TransitStop, val alight: TransitStop, val stopCount: Int)

/** A way to get somewhere by public transport: walk, one or two rides, walk. */
data class TransitPlan(
    val rides: List<Ride>,
    val walkToBoardKm: Double,
    val transferWalkKm: Double,
    val walkFromAlightKm: Double,
) {
    /** Rough door-to-door minutes: walking 12 min/km, 2 min per stop, 10 min per change. */
    val minutes: Int get() =
        ((walkToBoardKm + transferWalkKm + walkFromAlightKm) * 12 + rides.sumOf { it.stopCount } * 2.0 + (rides.size - 1) * 10 + 5).roundToInt()
}

object TransitPlanner {

    /** Fast distance in km, good enough for a few kilometres. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)) * 111.32
        val y = (lat2 - lat1) * 110.57
        return sqrt(x * x + y * y)
    }

    /**
     * Finds direct rides and rides with one change between two points. A change is allowed
     * where the two lines' stops are at most [maxTransferKm] apart. Best plans first.
     */
    fun plan(
        lines: List<TransitLine>,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        maxWalkKm: Double = 0.8,
        maxTransferKm: Double = 0.25,
        limit: Int = 4,
    ): List<TransitPlan> {
        data class Near(val line: TransitLine, val index: Int, val walk: Double)

        // For each line: the best stop to get on near the start, and to get off near the end.
        val boardings = mutableListOf<Near>()
        val alightings = mutableListOf<Near>()
        for (l in lines) {
            if (l.stops.size < 2) continue
            l.stops.withIndex().filter { (i, s) -> i < l.stops.size - 1 && km(fromLat, fromLon, s.lat, s.lon) <= maxWalkKm }
                .minByOrNull { (_, s) -> km(fromLat, fromLon, s.lat, s.lon) }
                ?.let { (i, s) -> boardings += Near(l, i, km(fromLat, fromLon, s.lat, s.lon)) }
            l.stops.withIndex().filter { (i, s) -> i > 0 && km(toLat, toLon, s.lat, s.lon) <= maxWalkKm }
                .minByOrNull { (_, s) -> km(toLat, toLon, s.lat, s.lon) }
                ?.let { (i, s) -> alightings += Near(l, i, km(toLat, toLon, s.lat, s.lon)) }
        }

        val plans = mutableListOf<TransitPlan>()
        // Direct.
        for (b in boardings) {
            val a = alightings.firstOrNull { it.line.id == b.line.id && it.index > b.index } ?: continue
            plans += TransitPlan(
                listOf(Ride(b.line, b.line.stops[b.index], a.line.stops[a.index], a.index - b.index)),
                b.walk, 0.0, a.walk,
            )
        }
        // One change.
        val directLines = plans.map { it.rides[0].line.id }.toSet()
        for (b in boardings) {
            if (b.line.id in directLines) continue
            for (a in alightings) {
                if (a.line.id == b.line.id || a.line.id in directLines) continue
                var best: TransitPlan? = null
                for (i in b.index + 1 until b.line.stops.size) {
                    val s1 = b.line.stops[i]
                    for (j in 0 until a.index) {
                        val s2 = a.line.stops[j]
                        val walk = if (s1.id == s2.id) 0.0 else km(s1.lat, s1.lon, s2.lat, s2.lon)
                        if (walk > maxTransferKm) continue
                        val p = TransitPlan(
                            listOf(
                                Ride(b.line, b.line.stops[b.index], s1, i - b.index),
                                Ride(a.line, s2, a.line.stops[a.index], a.index - j),
                            ),
                            b.walk, walk, a.walk,
                        )
                        if (best == null || p.minutes < best.minutes) best = p
                    }
                }
                best?.let { plans += it }
            }
        }
        // Same line numbers can appear as several relations (variants); keep the best of each.
        return plans.sortedBy { it.minutes }
            .distinctBy { p -> p.rides.joinToString("|") { it.line.kind.name + it.line.ref.ifBlank { it.line.name } } }
            .take(limit)
    }
}

object Fares {
    /**
     * Bangkok-style taxi meter fare in baht (35 for the first km, then by distance band).
     * Ride-hailing apps usually land in a similar range, more at busy times.
     */
    fun taxiMeter(km: Double): Int {
        var fare = 35.0
        var left = (km - 1).coerceAtLeast(0.0)
        val bands = listOf(9.0 to 6.5, 10.0 to 7.0, 20.0 to 8.0, 20.0 to 8.5, 20.0 to 9.0, Double.MAX_VALUE to 10.5)
        for ((len, rate) in bands) {
            val d = minOf(left, len)
            fare += d * rate
            left -= d
            if (left <= 0) break
        }
        return fare.roundToInt()
    }

    /** A rounded "about X–Y baht" range for a ride of [km]. */
    fun rideRange(km: Double): Pair<Int, Int> {
        val base = taxiMeter(km)
        fun round(v: Double) = ((v / 10).roundToInt() * 10).coerceAtLeast(40)
        return round(base * 0.9) to round(base * 1.5)
    }
}
