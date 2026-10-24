package th.trip.planner.logic

import th.trip.planner.model.Trip
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Getting back onto the plan after leaving it: once the traveller picks where to rejoin and
 * how to get there, checks whether the rest of the trip still works from the expected arrival,
 * and moves only what no longer fits (later departures, and stay days that can't be used).
 */
object Rejoin {
    /** Where to rejoin: arrive at stop [index], or catch the first ride of leg [index] at its station. */
    sealed class Target {
        abstract val index: Int
        abstract val key: String
        data class AtStop(override val index: Int) : Target() { override val key get() = "s$index" }
        data class Board(override val index: Int) : Target() { override val key get() = "l$index" }

        companion object {
            fun parse(key: String): Target? {
                val n = key.drop(1).toIntOrNull() ?: return null
                return when (key.firstOrNull()) { 's' -> AtStop(n); 'l' -> Board(n); else -> null }
            }
        }
    }

    /** Finds travel options for leg [leg] on [date], not leaving before minute [earliest]. */
    fun interface LegPlanner {
        fun options(leg: Int, date: LocalDate, earliest: Int): List<Itinerary.Option>
    }

    sealed class Change {
        data class StopDates(val stop: Int, val before: List<LocalDate>, val after: List<LocalDate>) : Change()
        /** Leg moved to another departure (and maybe another way of travelling). Null times = no timetable. */
        data class LegTime(
            val leg: Int,
            val beforeDate: LocalDate?, val beforeDepart: Int?,
            val afterDate: LocalDate, val afterDepart: Int?,
            val otherWay: Boolean,
        ) : Change()
        /** No connection found in the next days: the traveller must pick this leg's transport again. */
        data class NoConnection(val leg: Int) : Change()
    }

    sealed class Entry {
        data class Stop(val index: Int, val dates: List<LocalDate>, val arrive: LocalDateTime?, val changed: Boolean) : Entry()
        data class Leg(
            val index: Int, val date: LocalDate?, val depart: LocalDateTime?, val arrive: LocalDateTime?,
            val journey: Itinerary.Journey?, val changed: Boolean, val noConnection: Boolean,
        ) : Entry()
    }

    data class Result(val trip: Trip, val changes: List<Change>, val entries: List<Entry>)

    private fun at(date: LocalDate, minutes: Int): LocalDateTime = date.atStartOfDay().plusMinutes(minutes.toLong())

    /** When a journey really starts for someone already waiting at its first station. */
    private fun startAtHub(j: Itinerary.Journey): Int {
        val first = j.rides.firstOrNull() ?: return j.depart
        return if (j.steps.first() is Itinerary.Step.Ground) first.start - Itinerary.buffer(first.route.mode) else j.depart
    }

    /** The journey the plan has for leg [i], or null when the leg has no timetable plan. */
    fun plannedJourney(trip: Trip, i: Int, planner: LegPlanner): Itinerary.Journey? {
        val leg = trip.legs.getOrNull(i) ?: return null
        val sig = leg.plan ?: return null
        val date = TripLogic.legDate(trip, i) ?: return null
        val opt = planner.options(i, date, 5 * 60).firstOrNull { it.signature == sig } ?: return null
        return opt.journeys.firstOrNull { it.depart == leg.depart } ?: opt.first
    }

    /**
     * Checks the rest of [trip] when the traveller reaches [target] at [arrival] ([now] = today,
     * days before it are history and are never touched). [groundMinutes] estimates a leg with no
     * timetable (car, or a mode picked without a schedule).
     */
    fun retime(
        trip: Trip, target: Target, arrival: LocalDateTime, now: LocalDate,
        planner: LegPlanner, groundMinutes: (Int) -> Int,
    ): Result {
        val stops = trip.stops.toMutableList()
        val legCount = TripLogic.legCount(trip)
        val legs = MutableList(legCount) { trip.legs.getOrNull(it) ?: th.trip.planner.model.Leg() }
        val changes = ArrayList<Change>()
        val entries = ArrayList<Entry>()
        val chosenDates = HashMap<Int, LocalDate>()

        fun arriveAtStop(s: Int, t: LocalDateTime) {
            val before = TripLogic.dates(stops[s])
            // Days between today and the arrival day can no longer be spent there.
            var after = before.filter { it < now || it >= t.toLocalDate() }
            // Passing through on the day the next leg leaves needs no extra day; the last stop, or a
            // stop left with no days at all, keeps the arrival day.
            if (after.none { it >= t.toLocalDate() } && (s == stops.lastIndex || after.isEmpty())) after = (after + t.toLocalDate()).sorted()
            val changed = after != before
            if (changed) {
                stops[s] = stops[s].copy(dates = after.map { it.toString() })
                changes += Change.StopDates(s, before, after)
            }
            entries += Entry.Stop(s, after, t, changed)
        }

        /** Runs leg [i] starting no earlier than [ready]; returns the arrival at the next stop, or null if stuck. */
        fun runLeg(i: Int, ready: LocalDateTime, atHub: Boolean): LocalDateTime? {
            val leg = legs[i]
            val date = TripLogic.legDate(trip.copy(stops = stops, legs = legs), i) ?: ready.toLocalDate()
            val planned = plannedJourney(trip, i, planner)
            if (planned != null) {
                val start = if (atHub) startAtHub(planned) else planned.depart
                if (!at(date, start).isBefore(ready)) {
                    chosenDates[i] = date
                    entries += Entry.Leg(i, date, at(date, planned.depart), at(date, planned.arrive), planned, false, false)
                    return at(date, planned.arrive)
                }
                // Missed it: the next departure of the same way, else the quickest other way, in the next 3 days.
                for (d in 0..2) {
                    val day = ready.toLocalDate().plusDays(d.toLong())
                    val readyMin = if (d == 0) ready.hour * 60 + ready.minute else 0
                    val opts = planner.options(i, day, maxOf(0, readyMin - if (atHub) 120 else 0))
                    fun ok(j: Itinerary.Journey) = (if (atHub) startAtHub(j) else j.depart) >= readyMin && j.depart < 1440
                    val same = opts.firstOrNull { it.signature == leg.plan }?.journeys?.filter(::ok)?.minByOrNull { it.arrive }
                    val pick = same ?: opts.flatMap { o -> o.journeys.filter(::ok).map { o to it } }.minByOrNull { it.second.arrive }?.second
                    if (pick != null) {
                        val o = opts.first { o -> o.journeys.any { it === pick } }
                        legs[i] = leg.copy(mode = o.mainMode, plan = o.signature, depart = pick.depart)
                        chosenDates[i] = day
                        changes += Change.LegTime(i, date, leg.depart, day, pick.depart, o.signature != leg.plan)
                        entries += Entry.Leg(i, day, at(day, pick.depart), at(day, pick.arrive), pick, true, false)
                        return at(day, pick.arrive)
                    }
                }
                changes += Change.NoConnection(i)
                entries += Entry.Leg(i, date, null, null, null, true, true)
                return null
            }
            // No timetable: leave once there and on the planned day at the earliest.
            val day = if (date.isBefore(ready.toLocalDate())) ready.toLocalDate() else date
            val depart = maxOf(ready, at(day, 9 * 60))
            chosenDates[i] = day
            val moved = day != date
            if (moved) changes += Change.LegTime(i, date, null, day, null, false)
            entries += Entry.Leg(i, day, depart, depart.plusMinutes(groundMinutes(i).toLong()), null, moved, false)
            return depart.plusMinutes(groundMinutes(i).toLong())
        }

        var t: LocalDateTime? = arrival
        var leg = when (target) {
            is Target.AtStop -> { arriveAtStop(target.index, arrival); target.index }
            is Target.Board -> {
                t = runLeg(target.index, arrival, atHub = true)
                t?.let { arriveAtStop(target.index + 1, it) }
                target.index + 1
            }
        }
        while (t != null && leg < legCount) {
            t = runLeg(leg, t, atHub = false)
            t?.let { arriveAtStop(leg + 1, it) }
            leg++
        }
        // Keep each handled leg on its day: an explicit date only where it differs from the default.
        val out = trip.copy(stops = stops, legs = legs)
        val fixed = out.legs.mapIndexed { i, l ->
            val d = chosenDates[i] ?: return@mapIndexed l
            l.copy(date = if (TripLogic.defaultLegDate(out, i) == d) null else d.toString())
        }
        return Result(out.copy(legs = fixed), changes, entries)
    }
}
