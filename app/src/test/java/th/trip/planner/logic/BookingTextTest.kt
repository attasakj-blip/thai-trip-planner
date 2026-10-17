package th.trip.planner.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class BookingTextTest {
    @Test
    fun bookingEmail() {
        val f = BookingText.parse(
            """
            Your booking is confirmed
            Confirmation number: 4123.456.789
            PIN code: 1234
            Hotel name: Baan Suan Pai Resort
            Address: 99 Moo 3, Wiang Tai, Pai, Mae Hong Son 58130, Thailand
            Phone: +66 53 699 123
            https://www.booking.com/hotel/th/baan-suan-pai.html?aid=1
            """.trimIndent(),
        )
        assertEquals("4123.456.789", f.ref)
        assertEquals("Baan Suan Pai Resort", f.name)
        assertEquals("99 Moo 3, Wiang Tai, Pai, Mae Hong Son 58130, Thailand", f.address)
        assertEquals("+66 53 699 123", f.phone)
        assertEquals("https://www.booking.com/hotel/th/baan-suan-pai.html?aid=1", f.url)
    }

    @Test
    fun agodaThaiShare() {
        val f = BookingText.parse(
            "หมายเลขการจอง: 987654321\nโทร 081-234-5678\nhttps://www.agoda.com/th-th/riverside-chiang-mai-hotel/hotel/chiang-mai-th.html",
        )
        assertEquals("987654321", f.ref)
        assertEquals("081-234-5678", f.phone)
        assertEquals("Riverside Chiang Mai Hotel", f.name)
    }

    @Test
    fun linkOnly() {
        val f = BookingText.parse("Look at this place https://www.booking.com/hotel/th/the-sea-krabi.en-gb.html")
        assertEquals("The Sea Krabi", f.name)
        assertEquals("", f.ref)
        assertEquals("", f.phone)
    }

    @Test
    fun nothingFound() {
        val f = BookingText.parse("see you soon")
        assertEquals(BookingText.Found(), f)
    }
}
