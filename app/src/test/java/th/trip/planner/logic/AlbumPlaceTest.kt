package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import th.trip.planner.model.Place
import th.trip.planner.model.Region
import th.trip.planner.model.Txt

class AlbumPlaceTest {
    private fun place(id: String, lat: Double, lon: Double) =
        Place(id = id, region = Region.NORTH, lat = lat, lon = lon, name = Txt(id, id), province = Txt(id, id), description = Txt("", ""))

    private val chiangMai = place("chiang-mai", 18.7883, 98.9853)
    private val pai = place("pai", 19.3583, 98.4401)

    @Test fun photoNearAPlannedPlaceGoesToThatPlace() {
        assertEquals("pai", TripLogic.albumPlace(listOf(chiangMai, pai), 19.36, 98.45)?.id)
        assertEquals("chiang-mai", TripLogic.albumPlace(listOf(chiangMai, pai), 18.80, 99.00)?.id)
    }

    @Test fun photoMoreThan40kmFromEveryPlaceIsRejected() {
        // Bangkok is ~580 km from Chiang Mai.
        assertNull(TripLogic.albumPlace(listOf(chiangMai, pai), 13.7563, 100.5018))
        // Lamphun city is ~22 km from Chiang Mai: allowed. Lampang (~75 km): not.
        assertEquals("chiang-mai", TripLogic.albumPlace(listOf(chiangMai), 18.5745, 99.0087)?.id)
        assertNull(TripLogic.albumPlace(listOf(chiangMai), 18.2888, 99.4909))
    }

    @Test fun noPlacesMeansNothingAllowed() {
        assertNull(TripLogic.albumPlace(emptyList(), 18.7, 98.9))
    }
}
