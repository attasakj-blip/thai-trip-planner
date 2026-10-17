package th.trip.planner.logic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import th.trip.planner.model.FlightData
import th.trip.planner.model.Hub
import java.io.File

class FlightDataTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name", "/home/claude/thai-trip-planner/app/src/main/assets/$name")
        .map(::File).first { it.exists() }.readText()

    @Test fun flightFileParsesAndMatchesHubs() {
        val f = json.decodeFromString<FlightData>(asset("flights.json"))
        val hubs = json.decodeFromString<List<Hub>>(asset("hubs.json")).map { it.id }.toSet()
        val codes = f.airlines.map { it.code }.toSet()
        assertTrue(f.routes.size >= 20)
        f.routes.forEach { r ->
            assertTrue("${r.a}-${r.b}", r.a in hubs && r.b in hubs)
            assertTrue("${r.a}-${r.b}", r.airlines.all { it in codes })
        }
    }
}
