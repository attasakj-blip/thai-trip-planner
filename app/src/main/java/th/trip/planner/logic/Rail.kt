package th.trip.planner.logic

import th.trip.planner.model.RailLine
import th.trip.planner.model.RailStation
import java.util.PriorityQueue
import kotlin.math.roundToInt

/** Riding one rail line from [board] to [alight]. */
data class RailRide(val line: RailLine, val board: RailStation, val alight: RailStation, val stops: Int)

/** Walk to a station, ride one or more Bangkok rail lines (changing where they meet), walk to the end. */
data class RailPlan(
    val walkInKm: Double,
    val rides: List<RailRide>,
    val walkOutKm: Double,
    val minutes: Int,
)

/** Finds the quickest way by BTS / MRT / Airport Rail Link / SRT Red lines between two points. */
object RailPlanner {
    private const val WALK_MIN_PER_KM = 13.0
    private const val CHANGE_MIN = 6.0

    private data class Node(val line: Int, val idx: Int)

    /**
     * Returns the best plan, or null when either point is more than [maxWalkKm] from a station,
     * or the stations are the same (nothing to ride).
     */
    fun plan(lines: List<RailLine>, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, maxWalkKm: Double = 1.6): RailPlan? {
        if (lines.isEmpty()) return null
        fun km(a: Double, b: Double, c: Double, d: Double) = TransitPlanner.km(a, b, c, d)
        val dist = HashMap<Node, Double>()
        val prev = HashMap<Node, Node?>()
        val q = PriorityQueue<Pair<Double, Node>>(compareBy { it.first })
        for ((li, l) in lines.withIndex()) for ((si, s) in l.stations.withIndex()) {
            val w = km(fromLat, fromLon, s.lat, s.lon)
            if (w > maxWalkKm) continue
            val n = Node(li, si)
            val t = w * WALK_MIN_PER_KM + 4 // a few minutes to reach the platform
            if (t < (dist[n] ?: Double.MAX_VALUE)) { dist[n] = t; prev[n] = null; q += t to n }
        }
        if (q.isEmpty()) return null
        // Stations where lines meet: same name or a short walk apart.
        val transfers = HashMap<Node, MutableList<Pair<Node, Double>>>()
        val all = lines.flatMapIndexed { li, l -> l.stations.indices.map { Node(li, it) } }
        for (a in all) for (b in all) {
            if (a.line == b.line) continue
            val sa = lines[a.line].stations[a.idx]
            val sb = lines[b.line].stations[b.idx]
            val d = km(sa.lat, sa.lon, sb.lat, sb.lon)
            val same = sa.name.th == sb.name.th
            // Same-name stations are usually one building (e.g. BTS Siam); others need a walk.
            if (d <= 0.45 || (same && d < 1.0)) transfers.getOrPut(a) { ArrayList() } += b to ((if (same && d < 0.2) 3.0 else CHANGE_MIN) + d * WALK_MIN_PER_KM)
        }
        while (q.isNotEmpty()) {
            val (t, n) = q.poll()
            if (t > (dist[n] ?: Double.MAX_VALUE)) continue
            val l = lines[n.line]
            val nexts = ArrayList<Pair<Node, Double>>()
            if (n.idx > 0) nexts += Node(n.line, n.idx - 1) to l.minutesPerStop
            if (n.idx < l.stations.size - 1) nexts += Node(n.line, n.idx + 1) to l.minutesPerStop
            if (l.loop && l.stations.size > 2) {
                if (n.idx == 0) nexts += Node(n.line, l.stations.size - 1) to l.minutesPerStop
                if (n.idx == l.stations.size - 1) nexts += Node(n.line, 0) to l.minutesPerStop
            }
            transfers[n]?.let { nexts += it }
            for ((m, c) in nexts) {
                val nt = t + c
                if (nt < (dist[m] ?: Double.MAX_VALUE)) { dist[m] = nt; prev[m] = n; q += nt to m }
            }
        }
        // Best place to get off.
        var best: Node? = null
        var bestT = Double.MAX_VALUE
        for ((n, t) in dist) {
            val s = lines[n.line].stations[n.idx]
            val w = km(toLat, toLon, s.lat, s.lon)
            if (w > maxWalkKm) continue
            val total = t + w * WALK_MIN_PER_KM
            if (total < bestT) { bestT = total; best = n }
        }
        val end = best ?: return null
        val path = generateSequence(end) { prev[it] }.toList().reversed()
        // Group consecutive nodes on the same line into rides.
        val rides = ArrayList<RailRide>()
        var start = path.first()
        var last = start
        fun close() {
            if (start.line == last.line && start.idx != last.idx) {
                val l = lines[start.line]
                val n = l.stations.size
                val direct = kotlin.math.abs(last.idx - start.idx)
                val stops = if (l.loop) minOf(direct, n - direct) else direct
                rides += RailRide(l, l.stations[start.idx], l.stations[last.idx], stops)
            }
        }
        for (n in path.drop(1)) {
            if (n.line != last.line) { close(); start = n }
            last = n
        }
        close()
        if (rides.isEmpty()) return null
        val first = rides.first().board
        val lastSt = rides.last().alight
        return RailPlan(
            km(fromLat, fromLon, first.lat, first.lon),
            rides,
            km(toLat, toLon, lastSt.lat, lastSt.lon),
            bestT.roundToInt(),
        )
    }
}
