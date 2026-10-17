package th.trip.planner.logic

import java.security.MessageDigest

/** Which medical places are trustworthy enough to list, and how to describe them. */
object Health {
    enum class Kind { HOSPITAL, HEALTH_CENTRE, CLINIC }
    enum class Owner { PUBLIC, PRIVATE }

    // Names that are not medical care for a traveller in trouble (massage, spa, beauty, dentists, vets...).
    private val notCare = Regex(
        "(?i)นวด|massage|spa\\b|สปา|ความงาม|beauty|aesthetic|cosmetic|skin|ผิวพรรณ|เสริมสวย|dental|dentist|ทันต|ฟัน|" +
            "vet\\b|veterinar|สัตว์|animal|\\bpet\\b|แผนไทย|thai traditional|herb|สมุนไพร|hair|lasik|แว่น|optic|nail|tattoo",
    )
    private val hospitalName = Regex("(?i)โรงพยาบาล|hospital|รพ\\.|medical cent|ศูนย์การแพทย์|ศูนย์แพทย์")
    private val healthCentreName = Regex("(?i)ส่งเสริมสุขภาพ|รพ\\.?สต|health promoting|health cent|สถานีอนามัย|ศูนย์บริการสาธารณสุข")
    private val clinicName = Regex("(?i)คลินิก|คลีนิก|clinic|เวชกรรม|polyclinic|medical")
    private val publicName = Regex("(?i)โรงพยาบาล(ศูนย์|ทั่วไป|ชุมชน|มหาราช|สมเด็จ|จังหวัด|ส่งเสริม)|ค่ายทหาร|ตำรวจ|มหาวิทยาลัย|university|provincial|government|crown prince|somdet|ส่งเสริมสุขภาพ|สาธารณสุข")
    private val badSpeciality = Regex("(?i)dentist|cosmetic|dermatology|massage|alternative|traditional|veterinary|psychotherapy")

    /**
     * Returns the kind of place to show, or null to leave it out. [isArea] means the place is
     * drawn as a building or grounds on the map (not just a dot), which small shops rarely are.
     */
    fun classify(tags: Map<String, String>, isArea: Boolean): Kind? {
        val name = tags["name"].orEmpty() + " " + tags["name:th"].orEmpty() + " " + tags["name:en"].orEmpty()
        if (name.isBlank()) return null
        if (notCare.containsMatchIn(name)) return null
        if (tags["healthcare:speciality"]?.let { badSpeciality.containsMatchIn(it) } == true) return null
        val amenity = tags["amenity"]
        val healthcare = tags["healthcare"]
        val known = tags.containsKey("wikidata") || tags["beds"]?.toIntOrNull()?.let { it > 0 } == true
        return when {
            amenity == "hospital" || healthcare == "hospital" -> when {
                // A bare dot called "hospital" with nothing else to back it up is often a shop.
                isArea || known || hospitalName.containsMatchIn(name) -> Kind.HOSPITAL
                else -> null
            }
            healthCentreName.containsMatchIn(name) -> Kind.HEALTH_CENTRE
            amenity == "clinic" || healthcare == "clinic" || amenity == "doctors" -> when {
                hospitalName.containsMatchIn(name) && (isArea || known) -> Kind.HOSPITAL
                (isArea || known) && clinicName.containsMatchIn(name) -> Kind.CLINIC
                else -> null
            }
            else -> null
        }
    }

    fun owner(tags: Map<String, String>): Owner? {
        when (tags["operator:type"]) {
            "government", "public", "military", "university" -> return Owner.PUBLIC
            "private", "business", "commercial" -> return Owner.PRIVATE
        }
        val name = tags["name"].orEmpty() + " " + tags["name:th"].orEmpty() + " " + tags["operator"].orEmpty()
        return if (publicName.containsMatchIn(name)) Owner.PUBLIC else null
    }
}

/** Wikimedia Commons thumbnail address for a file name like "Bangkok Hospital logo.svg". */
object CommonsThumb {
    fun url(file: String, width: Int = 160): String {
        val name = file.removePrefix("File:").trim().replace(' ', '_')
        val md5 = MessageDigest.getInstance("MD5").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%28", "(").replace("%29", ")")
        val suffix = if (name.endsWith(".svg", true)) ".png" else ""
        return "https://upload.wikimedia.org/wikipedia/commons/thumb/${md5[0]}/${md5.substring(0, 2)}/$enc/${width}px-$enc$suffix"
    }
}
