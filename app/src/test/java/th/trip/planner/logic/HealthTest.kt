package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthTest {
    @Test fun hospitalsKept() {
        assertEquals(Health.Kind.HOSPITAL, Health.classify(mapOf("amenity" to "hospital", "name" to "โรงพยาบาลกรุงเทพพัทยา"), isArea = false))
        assertEquals(Health.Kind.HOSPITAL, Health.classify(mapOf("amenity" to "hospital", "name" to "Some Name"), isArea = true))
        assertEquals(Health.Kind.HEALTH_CENTRE, Health.classify(mapOf("amenity" to "clinic", "name" to "โรงพยาบาลส่งเสริมสุขภาพตำบลบ้านนา"), isArea = false))
        assertEquals(Health.Kind.CLINIC, Health.classify(mapOf("amenity" to "clinic", "name" to "Pattaya International Clinic"), isArea = true))
    }

    @Test fun shopsLeftOut() {
        // A bare dot with an unclear name (like a massage shop mapped as a hospital) is dropped.
        assertNull(Health.classify(mapOf("amenity" to "hospital", "name" to "นิรามัยแพทย์"), isArea = false))
        assertNull(Health.classify(mapOf("amenity" to "clinic", "name" to "หมอสมชายคลินิก"), isArea = false))
        assertNull(Health.classify(mapOf("amenity" to "clinic", "name" to "Smile Dental Clinic"), isArea = true))
        assertNull(Health.classify(mapOf("amenity" to "hospital", "name" to "นวดแผนไทย สุขภาพดี"), isArea = true))
        assertNull(Health.classify(mapOf("amenity" to "clinic", "name" to "คลินิกความงาม"), isArea = true))
    }

    @Test fun owner() {
        assertEquals(Health.Owner.PUBLIC, Health.owner(mapOf("name" to "โรงพยาบาลชุมชนบางละมุง")))
        assertEquals(Health.Owner.PRIVATE, Health.owner(mapOf("name" to "X", "operator:type" to "private")))
    }

    @Test fun commonsThumb() {
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Example.jpg/160px-Example.jpg", CommonsThumb.url("File:Example.jpg"))
        assert(CommonsThumb.url("Logo x.svg").endsWith("/160px-Logo_x.svg.png"))
    }
}
