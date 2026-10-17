package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitTest {
    private var nextId = 1L
    private fun stop(name: String, lat: Double, lon: Double) = TransitStop(nextId++, name, lat, lon)

    // A west-east line along lat 13.75 and a north-south line along lon 100.53.
    private val west = stop("West", 13.75, 100.50)
    private val mid = stop("Middle", 13.75, 100.53)
    private val east = stop("East", 13.75, 100.56)
    private val north = stop("North", 13.78, 100.53)
    private val south = stop("South", 13.72, 100.53)
    private val midB = TransitStop(99, "Middle (north-south)", 13.7505, 100.5302)

    private val line1 = TransitLine(1, "25", "West - East", TransitKind.BUS, "BMTA", listOf(west, mid, east))
    private val line1Back = TransitLine(2, "25", "East - West", TransitKind.BUS, "BMTA", listOf(east, mid, west))
    private val line2 = TransitLine(3, "BTS", "North - South", TransitKind.METRO, "BTS", listOf(north, midB, south))

    @Test fun directRideInTheRightDirection() {
        val plans = TransitPlanner.plan(listOf(line1, line1Back, line2), 13.7502, 100.501, 13.7501, 100.559)
        val p = plans.first()
        assertEquals(1, p.rides.size)
        assertEquals(1L, p.rides[0].line.id) // not the line running the other way
        assertEquals("West", p.rides[0].board.name)
        assertEquals("East", p.rides[0].alight.name)
        assertEquals(2, p.rides[0].stopCount)
    }

    @Test fun oneChange() {
        val plans = TransitPlanner.plan(listOf(line1, line2), 13.7502, 100.501, 13.721, 100.531)
        val p = plans.first()
        assertEquals(2, p.rides.size)
        assertEquals("Middle", p.rides[0].alight.name)
        assertEquals("Middle (north-south)", p.rides[1].board.name)
        assertEquals("South", p.rides[1].alight.name)
        assertTrue(p.transferWalkKm < 0.1)
    }

    @Test fun nothingNearby() {
        assertTrue(TransitPlanner.plan(listOf(line1, line2), 14.5, 101.0, 13.72, 100.53).isEmpty())
    }

    @Test fun taxiFare() {
        assertEquals(35, Fares.taxiMeter(1.0))
        assertEquals(35 + 59, Fares.taxiMeter(10.0)) // 9 km x 6.5 = 58.5
        val (lo, hi) = Fares.rideRange(20.0)
        assertTrue(lo in 150..200 && hi > lo)
    }
}
