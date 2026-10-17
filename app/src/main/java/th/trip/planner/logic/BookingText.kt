package th.trip.planner.logic

/**
 * Pulls stay details out of a booking confirmation the user pasted or shared into the app
 * (an email, an app's "share" text, or a Booking.com / Agoda link). Anything it can't find
 * is left blank for the user to type.
 */
object BookingText {
    data class Found(
        val name: String = "",
        val address: String = "",
        val phone: String = "",
        val ref: String = "",
        val url: String = "",
    )

    private val urlRe = Regex("""https?://\S+""")
    private val bookingSlug = Regex("""booking\.com/hotel/[a-z]{2}/([a-z0-9-]+?)(?:\.[a-z]{2}(?:-[a-z]{2})?)?\.html""", RegexOption.IGNORE_CASE)
    private val agodaSlug = Regex("""agoda\.com/(?:[a-z]{2}-[a-z]{2}/)?([a-z0-9-]+)/hotel/""", RegexOption.IGNORE_CASE)

    private const val REF_CHARS = """[A-Za-z0-9][A-Za-z0-9.\-]{4,}"""
    private val refRes = listOf(
        Regex("""(?i)(?:confirmation|booking|reservation|itinerary)\s*(?:number|no\.?|id|code|reference|ref\.?|#)\s*[:：#]?\s*($REF_CHARS)"""),
        Regex("""(?:หมายเลข|รหัส|เลขที่)\s*(?:การ)?(?:จอง|ยืนยัน(?:การจอง)?|อ้างอิง)\s*[:：#]?\s*($REF_CHARS)"""),
    )
    private val phoneRe = Regex("""(?:\+66|\b0)[\s\-]?\d(?:[\s\-]?\d){7,9}\b""")
    private val nameLabel = Regex("""(?im)^\s*(?:hotel|property|accommodation|ชื่อที่พัก|ที่พัก|โรงแรม)\s*(?:name)?\s*[:：]\s*(.+?)\s*$""")
    private val addressLabel = Regex("""(?im)^\s*(?:address|location|ที่อยู่|ที่ตั้ง)\s*[:：]\s*(.+?)\s*$""")
    private val postcode = Regex("""\b[1-9]\d{4}\b""")

    fun parse(text: String): Found {
        val url = urlRe.findAll(text).map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull { "booking.com" in it.lowercase() || "agoda" in it.lowercase() } ?: ""

        val ref = refRes.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.trimEnd('.', '-') ?: ""
        val withoutRef = if (ref.isBlank()) text else text.replace(ref, " ")
        val withoutUrls = urlRe.replace(withoutRef, " ")

        val phoneLine = withoutUrls.lines().firstOrNull { l ->
            val low = l.lowercase()
            ("phone" in low || "tel" in low || "โทร" in l) && phoneRe.containsMatchIn(l)
        }
        val phone = (phoneLine?.let { phoneRe.find(it) } ?: phoneRe.find(withoutUrls))?.value?.trim() ?: ""

        val name = nameLabel.find(text)?.groupValues?.get(1)
            ?: (bookingSlug.find(url) ?: agodaSlug.find(url))?.groupValues?.get(1)?.let(::slugToName)
            ?: ""

        val address = addressLabel.find(text)?.groupValues?.get(1)
            ?: withoutUrls.lines().map { it.trim() }.firstOrNull { l ->
                l.length >= 15 && l != name && phoneRe.find(l)?.value != l &&
                    ("thailand" in l.lowercase() || "ประเทศไทย" in l || postcode.containsMatchIn(l))
            }
            ?: ""

        return Found(name.trim(), address.trim(), phone, ref, url)
    }

    /** "baan-suan-pai-resort" -> "Baan Suan Pai Resort". */
    fun slugToName(slug: String) = slug.split('-').filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
