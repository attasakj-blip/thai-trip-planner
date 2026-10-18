package th.trip.planner.logic

import th.trip.planner.model.Hub
import th.trip.planner.model.HubType
import th.trip.planner.model.Route
import th.trip.planner.model.TravelMode
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

/**
 * Finds practical ways to travel between two points using real scheduled services (flights,
 * trains, buses, vans, boats) plus taxi / ride-hailing to and from the stations. Times are
 * minutes after midnight of the travel date (may pass 24 * 60 for the next day).
 */
object Itinerary {
    /** Where a journey starts or ends. [islandGroup] null = mainland (or an island with a road bridge). */
    data class Point(val lat: Double, val lon: Double, val island: Boolean = false, val islandGroup: String? = null)

    sealed class Step {
        abstract val start: Int
        abstract val end: Int

        /** Taxi / ride-hailing (or a walk when very short). Null hub = the place or stay itself. */
        data class Ground(val from: Hub?, val to: Hub?, val km: Double, override val start: Int, override val end: Int) : Step() {
            val walk get() = km <= 0.8
        }

        data class Ride(
            val route: Route, val code: String?, val boardIdx: Int, val alightIdx: Int,
            val board: Hub, val alight: Hub, override val start: Int, override val end: Int,
        ) : Step()
    }

    data class Journey(val steps: List<Step>) {
        val depart get() = steps.first().start
        val arrive get() = steps.last().end
        val minutes get() = arrive - depart
        val rides get() = steps.filterIsInstance<Step.Ride>()
        /** The mode that covers the most distance, or CAR for a door-to-door ride. */
        val mainMode: TravelMode get() = rides.maxByOrNull { TripLogic.distanceKm(it.board.lat, it.board.lon, it.alight.lat, it.alight.lon) }?.route?.mode ?: TravelMode.CAR
        val signature: String get() = steps.joinToString("|") { s ->
            when (s) {
                is Step.Ground -> "g:${s.from?.id ?: "*"}:${s.to?.id ?: "*"}"
                is Step.Ride -> "r:${s.route.id}:${s.boardIdx}:${s.alightIdx}"
            }
        }
    }

    /** One way to go, with the same connections at several times of day (earliest first). */
    data class Option(val journeys: List<Journey>) {
        val first get() = journeys.first()
        val signature get() = first.signature
        val mainMode get() = first.mainMode
    }

    // ------------------------------------------------------------------ tunables

    /** Minutes to be at the station before departure. */
    fun buffer(mode: TravelMode) = when (mode) {
        TravelMode.FLIGHT -> 90
        TravelMode.BOAT -> 30
        TravelMode.TRAIN, TravelMode.BUS -> 20
        else -> 15
    }

    /** How far (straight line, km) we'd send someone by taxi to reach a hub of this type. */
    fun accessLimitKm(type: HubType) = when (type) {
        HubType.AIRPORT -> 160.0
        HubType.TRAIN -> 50.0
        else -> 60.0
    }

    /** Rough taxi / car time for a straight-line distance. */
    fun groundMinutes(km: Double): Int {
        if (km <= 0.8) return max(3, (km / 4.5 * 60).toInt()) // walk
        val road = km * 1.35
        val speed = when { road < 10 -> 22.0; road < 40 -> 40.0; else -> 65.0 }
        return 10 + ceil(road / speed * 60).toInt()
    }

    private fun speedKmh(mode: TravelMode) = when (mode) {
        TravelMode.FLIGHT -> 550.0
        TravelMode.BOAT -> 25.0
        TravelMode.TRAIN -> 55.0
        TravelMode.BUS -> 65.0
        else -> 75.0
    }

    fun parseTime(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val p = s.trim().split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    fun formatTime(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Days after the travel date (0 = same day). */
    fun dayOffset(min: Int) = Math.floorDiv(min, 1440)

    // ------------------------------------------------------------------ timetable expansion

    /** Times per run: an array with one entry per stop (-1 = doesn't stop). Sorted by first departure. */
    private class Timetable(val route: Route, val runs: List<IntArray>, val codes: List<String?>)

    private fun runsOnDate(r: Route, date: LocalDate): Boolean {
        if (r.days.isNotEmpty() && date.dayOfWeek.value !in r.days) return false
        if (r.seasonal != null && r.seasonal.months.isNotEmpty() && date.monthValue !in r.seasonal.months) return false
        return true
    }

    private fun timetable(r: Route, hubs: Map<String, Hub>): Timetable? {
        val n = r.stops.size
        if (n < 2 || r.stops.any { it !in hubs }) return null
        val runs = ArrayList<IntArray>()
        val codes = ArrayList<String?>()
        for (t in r.trips) {
            if (t.times.size != n) continue
            val arr = IntArray(n) { parseTime(t.times[it]) ?: -1 }
            // Times must go forward along the route.
            var last = -1
            var ok = arr.count { it >= 0 } >= 2
            for (v in arr) if (v >= 0) { if (v < last) ok = false; last = v }
            if (ok) { runs += arr; codes += t.code }
        }
        r.headway?.let { h ->
            val first = parseTime(h.first) ?: return@let
            val last = parseTime(h.last) ?: return@let
            if (h.everyMin <= 0) return@let
            val offsets = h.minutes?.takeIf { it.size == n } ?: run {
                var acc = 0.0
                List(n) { i ->
                    if (i > 0) {
                        val a = hubs.getValue(r.stops[i - 1]); val b = hubs.getValue(r.stops[i])
                        acc += TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon) * 1.3 / speedKmh(r.mode) * 60
                    }
                    acc.toInt()
                }
            }
            var t = first
            while (t <= last) {
                runs += IntArray(n) { t + offsets[it] }
                codes += null
                t += h.everyMin
            }
        }
        if (runs.isEmpty()) return null
        val order = runs.indices.sortedBy { i -> runs[i].first { it >= 0 } }
        return Timetable(r, order.map { runs[it] }, order.map { codes[it] })
    }

    // ------------------------------------------------------------------ search

    private sealed class Label(val time: Int) {
        class Access(time: Int, val step: Step.Ground) : Label(time)
        class Ride(time: Int, val step: Step.Ride, val prev: Label) : Label(time)
        class Transfer(time: Int, val step: Step.Ground, val prev: Label) : Label(time)
    }

    private fun Label.steps(): List<Step> = when (this) {
        is Label.Access -> listOf(step)
        is Label.Ride -> prev.steps() + step
        is Label.Transfer -> prev.steps() + step
    }

    private fun sameLand(p: Point, h: Hub, km: Double): Boolean = when {
        p.islandGroup != null -> h.islandGroup == p.islandGroup
        p.island -> km <= 10.0 // an island we know nothing more about: only hubs right there
        else -> h.islandGroup == null
    }

    private fun sameLand(a: Hub, b: Hub) = a.islandGroup == b.islandGroup

    /**
     * Travel options from [from] to [to] on [date], best first. Several different ways
     * (e.g. fly direct; bus + ferry; fly + ferry), each with up to [timesPerOption] departure times.
     */
    fun options(
        from: Point, to: Point, date: LocalDate, hubs: List<Hub>, routes: List<Route>,
        earliest: Int = 5 * 60, maxOptions: Int = 6, timesPerOption: Int = 6,
    ): List<Option> {
        val hubById = hubs.associateBy { it.id }
        // Runs of the travel date and the next day (for overnight trips that connect in the morning).
        val next = date.plusDays(1)
        val tables = routes.mapNotNull { r ->
            val base = timetable(r, hubById) ?: return@mapNotNull null
            val today = runsOnDate(r, date)
            val tomorrow = runsOnDate(r, next)
            if (!today && !tomorrow) return@mapNotNull null
            val runs = (if (today) base.runs else emptyList()) +
                (if (tomorrow) base.runs.map { run -> IntArray(run.size) { if (run[it] >= 0) run[it] + 1440 else -1 } } else emptyList())
            val codes = (if (today) base.codes else emptyList()) + (if (tomorrow) base.codes else emptyList())
            val order = runs.indices.sortedBy { i -> runs[i].first { it >= 0 } }
            Timetable(r, order.map { runs[it] }, order.map { codes[it] })
        }
        val found = LinkedHashMap<String, Journey>()

        fun keep(j: Journey) {
            if (!sensibleRoute(j, directKmFor(from, to))) return
            val old = found[j.signature]
            if (old == null || j.arrive < old.arrive) found[j.signature] = j
        }

        // Door to door by car / ride-hailing when there's a road.
        val directKm = TripLogic.distanceKm(from.lat, from.lon, to.lat, to.lon)
        val sameGround = from.islandGroup == to.islandGroup && (from.islandGroup != null || (!from.island && !to.island) || directKm <= 10)
        if (sameGround && directKm <= 1000) keep(Journey(listOf(Step.Ground(null, null, directKm, earliest + 180, earliest + 180 + groundMinutes(directKm)))))

        // Different searches give different kinds of answers: by start time and with / without flying.
        val modeSets = listOf(TravelMode.entries.toSet(), TravelMode.entries.toSet() - TravelMode.FLIGHT, TravelMode.entries.toSet() - TravelMode.BOAT)
        // Also search with only short taxi rides to the first station, so bus/van/train ways
        // aren't hidden by a long taxi to the airport.
        for (maxAccess in listOf(Double.MAX_VALUE, 25.0)) for (modes in modeSets) for (start in listOf(earliest, earliest + 240, earliest + 480, earliest + 720)) {
            search(from, to, start, hubs, tables.filter { it.route.mode in modes }, maxAccess).forEach { j -> keep(tighten(j, tables)) }
        }
        if (found.isEmpty()) return emptyList()

        // Same connections at other times of the day.
        val opts = found.values.mapNotNull { j ->
            val alts = alternatives(j, from, to, tables, earliest, timesPerOption).filter { sensibleRoute(it, directKm) }
            // For each arrival time keep the latest start (no needless waiting), and skip runs
            // that would mean a long wait (e.g. missing the last ferry and waiting overnight).
            // Only departures on the travel day itself.
            val all = (alts + j).filter { it.depart in 0 until 1440 }.groupBy { it.arrive }.values.map { g -> g.maxBy { it.depart } }
            if (all.isEmpty()) return@mapNotNull null
            val quickest = all.minOf { it.minutes }
            Option(all.filter { it.minutes <= quickest * 1.6 + 60 }.distinctBy { it.depart }.sortedBy { it.depart }.take(timesPerOption))
        }
        if (opts.isEmpty()) return emptyList()

        fun best(o: Option) = o.journeys.minOf { score(it) }
        fun modes(o: Option) = o.first.rides.map { it.route.mode }
        val bestScore = opts.minOf { best(it) }
        // Drop an option when a simpler one (its modes are a subset, e.g. "flight" vs "bus + flight")
        // is at least as good, unless the extra part is a ferry (bus/flight + ferry combos are common and cheaper).
        val sensible = opts.filter { x ->
            best(x) <= bestScore * 4 + 720 && opts.none { y ->
                y !== x && modes(y).size < modes(x).size && modes(y).isNotEmpty() && isSubsequence(modes(y), modes(x)) &&
                    best(y) <= best(x) && (modes(x) - modes(y).toSet()).none { it == TravelMode.BOAT }
            }
        }
        // Best first, but show different main modes before more of the same.
        val sorted = sensible.sortedBy { best(it) }
        val picked = ArrayList<Option>()
        sorted.distinctBy { modes(it) }.forEach { if (picked.size < maxOptions) picked += it }
        // Then more of the same kind, at most two of each.
        sorted.forEach { o -> if (o !in picked && picked.size < maxOptions && picked.count { modes(it) == modes(o) } < 2) picked += o }
        return picked.sortedBy { best(it) }
    }

    private fun <T> isSubsequence(small: List<T>, big: List<T>): Boolean {
        var i = 0
        for (x in big) if (i < small.size && small[i] == x) i++
        return i == small.size
    }

    private fun directKmFor(a: Point, b: Point) = TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon)

    /** No going back through a station already passed, and no huge detours. */
    private fun sensibleRoute(j: Journey, directKm: Double): Boolean {
        val seq = ArrayList<String>()
        fun add(id: String?) { if (id != null && seq.lastOrNull() != id) seq += id }
        var pathKm = 0.0
        for (s in j.steps) when (s) {
            is Step.Ground -> { add(s.from?.id); add(s.to?.id); pathKm += s.km }
            is Step.Ride -> { add(s.board.id); add(s.alight.id); pathKm += TripLogic.distanceKm(s.board.lat, s.board.lon, s.alight.lat, s.alight.lon) }
        }
        if (seq.size != seq.toSet().size) return false
        return pathKm <= directKm * 2.2 + 40
    }

    /** Lower is better: travel time plus a penalty for each change. */
    fun score(j: Journey): Int = j.minutes + 25 * max(0, j.steps.size - 1) +
        (if (j.rides.isEmpty()) (j.minutes / 3) else 0) + // long taxi rides are tiring and costly
        j.steps.filterIsInstance<Step.Ground>().sumOf { max(0.0, it.km - 15) * 0.6 }.toInt()

    private fun search(from: Point, to: Point, start: Int, hubs: List<Hub>, tables: List<Timetable>, maxAccessKm: Double): List<Journey> {
        val access = HashMap<String, Label>()
        for (h in hubs) {
            val km = TripLogic.distanceKm(from.lat, from.lon, h.lat, h.lon)
            if (km > minOf(accessLimitKm(h.type), maxAccessKm) || !sameLand(from, h, km)) continue
            val t = start + groundMinutes(km)
            access[h.id] = Label.Access(t, Step.Ground(null, h, km, start, t))
        }
        val egress = HashMap<String, Double>()
        for (h in hubs) {
            val km = TripLogic.distanceKm(to.lat, to.lon, h.lat, h.lon)
            if (km <= accessLimitKm(h.type) && sameLand(to, h, km)) egress[h.id] = km
        }
        if (access.isEmpty() || egress.isEmpty()) return emptyList()
        val hubById = hubs.associateBy { it.id }

        val best = HashMap<String, Int>() // earliest arrival at each hub in any round
        access.forEach { (id, l) -> best[id] = l.time }
        var prevRound: Map<String, Label> = access
        val out = ArrayList<Journey>()

        repeat(3) {
            val round = HashMap<String, Label>()
            for (tt in tables) {
                val r = tt.route
                val buf = buffer(r.mode)
                var run = -1
                var boardIdx = -1
                var boardLabel: Label? = null
                for (i in r.stops.indices) {
                    val hubId = r.stops[i]
                    if (run >= 0) {
                        val t = tt.runs[run][i]
                        if (t >= 0 && i > boardIdx) {
                            if (t < (best[hubId] ?: Int.MAX_VALUE) || (round[hubId]?.time ?: Int.MAX_VALUE) > t && egress.containsKey(hubId)) {
                                val step = Step.Ride(r, tt.codes[run], boardIdx, i, hubById.getValue(r.stops[boardIdx]), hubById.getValue(hubId), tt.runs[run][boardIdx], t)
                                if ((round[hubId]?.time ?: Int.MAX_VALUE) > t) round[hubId] = Label.Ride(t, step, boardLabel!!)
                                if (t < (best[hubId] ?: Int.MAX_VALUE)) best[hubId] = t
                            }
                        }
                    }
                    val here = prevRound[hubId] ?: continue
                    val ready = here.time + buf
                    val current = if (run >= 0) tt.runs[run][i] else Int.MAX_VALUE
                    val next = tt.runs.indexOfFirst { it[i] >= ready && it.drop(i + 1).any { v -> v >= 0 } }
                    if (next >= 0 && tt.runs[next][i] < current) { run = next; boardIdx = i; boardLabel = here }
                }
            }
            // Arrivals that finish the trip.
            for ((id, l) in round) {
                val km = egress[id] ?: continue
                val h = hubById.getValue(id)
                out += Journey(l.steps() + Step.Ground(h, null, km, l.time, l.time + groundMinutes(km)))
            }
            // Taxi between stations in the same city (e.g. Mo Chit to the Southern Bus Terminal).
            val withTransfers = HashMap<String, Label>(round)
            for ((id, l) in round) {
                val a = hubById.getValue(id)
                for (b in hubs) {
                    if (b.id == id || !sameLand(a, b)) continue
                    val km = TripLogic.distanceKm(a.lat, a.lon, b.lat, b.lon)
                    // Piers and airports are often well outside town (Surat Thani to Donsak pier is ~60 km).
                    val limit = if (a.type == HubType.PIER || b.type == HubType.PIER || a.type == HubType.AIRPORT || b.type == HubType.AIRPORT) 75.0 else 40.0
                    if (km > limit) continue
                    val t = l.time + groundMinutes(km)
                    if (t < (withTransfers[b.id]?.time ?: Int.MAX_VALUE)) withTransfers[b.id] = Label.Transfer(t, Step.Ground(a, b, km, l.time, t), l)
                }
            }
            prevRound = withTransfers
        }
        return out
    }

    /**
     * Leave as late as possible: keep the last ride, and for each earlier ride take the latest run
     * that still makes the next connection (the search itself finds the earliest start).
     */
    private fun tighten(j: Journey, tables: List<Timetable>): Journey {
        val rideIdx = j.steps.indices.filter { j.steps[it] is Step.Ride }
        if (rideIdx.size < 2) return j.rides.firstOrNull()?.let { reanchor(j.steps) } ?: j
        val byId = tables.associateBy { it.route.id }
        val steps = j.steps.toMutableList()
        for (n in rideIdx.size - 2 downTo 0) {
            val k = rideIdx[n]
            val nextK = rideIdx[n + 1]
            val next = steps[nextK] as Step.Ride
            val between = (k + 1 until nextK).sumOf { steps[it].end - steps[it].start }
            val mustArrive = next.start - buffer(next.route.mode) - between
            val r = steps[k] as Step.Ride
            val tt = byId[r.route.id] ?: return j
            val runIdx = tt.runs.indices.lastOrNull { i -> val run = tt.runs[i]; run[r.boardIdx] >= 0 && run[r.alightIdx] in run[r.boardIdx]..mustArrive } ?: return j
            val run = tt.runs[runIdx]
            steps[k] = r.copy(start = run[r.boardIdx], end = run[r.alightIdx], code = tt.codes[runIdx])
            var t = run[r.alightIdx]
            for (g in k + 1 until nextK) { val s = steps[g]; val d = s.end - s.start; steps[g] = (s as Step.Ground).copy(start = t, end = t + d); t += d }
        }
        return reanchor(steps)
    }

    /** Move the ground steps before the first ride so they end one buffer before it departs. */
    private fun reanchor(steps: List<Step>): Journey {
        val first = steps.indexOfFirst { it is Step.Ride }
        if (first <= 0) return Journey(steps)
        val out = steps.toMutableList()
        var back = out[first].start - buffer((out[first] as Step.Ride).route.mode)
        for (k in first - 1 downTo 0) {
            val g = out[k] as Step.Ground
            val d = g.end - g.start
            out[k] = g.copy(start = back - d, end = back)
            back -= d
        }
        return Journey(out)
    }

    /** The same connections at other times: each run of the longest ride, with the best connections around it. */
    private fun alternatives(j: Journey, from: Point, to: Point, tables: List<Timetable>, earliest: Int, max: Int): List<Journey> {
        val rideIdx = j.steps.indices.filter { j.steps[it] is Step.Ride }
        if (rideIdx.isEmpty()) return emptyList()
        val anchor = rideIdx.maxBy { k -> val r = j.steps[k] as Step.Ride; TripLogic.distanceKm(r.board.lat, r.board.lon, r.alight.lat, r.alight.lon) }
        val byId = tables.associateBy { it.route.id }
        val a = j.steps[anchor] as Step.Ride
        val tt = byId[a.route.id] ?: return emptyList()
        val out = tt.runs.indices.mapNotNull { run -> follow(j, byId, anchor, run) }
            .filter { it.depart >= earliest - 60 && it.depart < earliest + 1440 }
        return out.groupBy { it.arrive }.values.map { g -> g.maxBy { it.depart } }.sortedBy { it.depart }.let { list ->
            // Spread the choices over the day when there are many.
            if (list.size <= max) list else {
                val step = list.size.toDouble() / max
                List(max) { list[(it * step).toInt()] }
            }
        }
    }

    /** Rebuild [j] with run [run] of the ride at step [anchor]: earliest connections after it, latest before it. */
    private fun follow(j: Journey, tables: Map<String, Timetable>, anchor: Int, run: Int): Journey? {
        val steps = j.steps.toMutableList()
        val a = steps[anchor] as Step.Ride
        val at = tables[a.route.id]?.runs?.getOrNull(run) ?: return null
        if (at[a.boardIdx] < 0 || at[a.alightIdx] < at[a.boardIdx]) return null
        steps[anchor] = a.copy(start = at[a.boardIdx], end = at[a.alightIdx], code = tables[a.route.id]?.codes?.get(run))
        var clock = at[a.alightIdx]
        for (k in anchor + 1 until steps.size) {
            when (val s = steps[k]) {
                is Step.Ground -> { val d = s.end - s.start; steps[k] = s.copy(start = clock, end = clock + d); clock += d }
                is Step.Ride -> {
                    val tt = tables[s.route.id] ?: return null
                    val ready = clock + buffer(s.route.mode)
                    val i = tt.runs.indexOfFirst { it[s.boardIdx] >= ready && it[s.alightIdx] >= it[s.boardIdx] }
                    if (i < 0) return null
                    steps[k] = s.copy(start = tt.runs[i][s.boardIdx], end = tt.runs[i][s.alightIdx], code = tt.codes[i])
                    clock = tt.runs[i][s.alightIdx]
                }
            }
        }
        // Backwards: each earlier step must end before the next one starts (minus the boarding buffer).
        for (k in anchor - 1 downTo 0) {
            val next = steps[k + 1]
            val deadline = next.start - (if (next is Step.Ride) buffer(next.route.mode) else 0)
            when (val s = steps[k]) {
                is Step.Ground -> { val d = s.end - s.start; steps[k] = s.copy(start = deadline - d, end = deadline) }
                is Step.Ride -> {
                    val tt = tables[s.route.id] ?: return null
                    val i = tt.runs.indices.lastOrNull { val r = tt.runs[it]; r[s.boardIdx] >= 0 && r[s.alightIdx] in r[s.boardIdx]..deadline } ?: return null
                    steps[k] = s.copy(start = tt.runs[i][s.boardIdx], end = tt.runs[i][s.alightIdx], code = tt.codes[i])
                }
            }
        }
        return Journey(steps)
    }
}
