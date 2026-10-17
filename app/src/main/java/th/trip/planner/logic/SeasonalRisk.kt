package th.trip.planner.logic

import th.trip.planner.model.Place
import th.trip.planner.model.Region
import th.trip.planner.model.Txt
import java.time.LocalDate

enum class RiskKind { HAZE_FIRE, HEAVY_RAIN, FLASH_FLOOD_LANDSLIDE, ROUGH_SEA, HEAT, FLOOD, CLOSURE }

enum class Level { INFO, WATCH, WARNING }

data class Warning(
    val kind: RiskKind,
    val level: Level,
    val title: Txt,
    val detail: Txt,
    /** Where the information comes from. */
    val source: Txt,
)

/**
 * Typical seasonal risks by region and month. Used for trip dates too far ahead for a real
 * forecast (more than about 7 days). Based on Thailand's well-known climate pattern:
 * hot season Mar–May, southwest monsoon mid-May–Oct, northeast monsoon rain on the
 * southern Gulf coast Oct/Nov–Jan, and burning-season haze in the north Feb–Apr.
 */
object SeasonalRisk {

    private val seasonal = Txt("ข้อมูลภูมิอากาศตามฤดูกาลของประเทศไทย (ค่าเฉลี่ย ไม่ใช่พยากรณ์)", "Thailand seasonal climate pattern (typical, not a forecast)")
    private val parkInfo = Txt("ข้อมูลการปิดอุทยานในคลังสถานที่ของแอป", "Park closure info in the app's place library")

    fun forPlace(place: Place, dates: List<LocalDate>): List<Warning> {
        val months = dates.map { it.monthValue }.toSet()
        val out = mutableListOf<Warning>()
        val r = place.region

        if ((r == Region.NORTH && months.any { it in 2..4 }) || (r == Region.NORTH && 1 in months)) {
            out += Warning(
                RiskKind.HAZE_FIRE, if (months.any { it in 2..4 }) Level.WARNING else Level.WATCH,
                Txt("ช่วงหมอกควันและไฟป่า", "Haze and forest-fire season"),
                Txt("ภาคเหนือมักมีจุดความร้อนและค่า PM2.5 สูงช่วง ก.พ.–เม.ย. (สูงสุดราว มี.ค.) ควรเตรียมหน้ากาก N95 และตรวจค่าฝุ่นก่อนเดินทาง",
                    "Northern Thailand often has many fire hotspots and high PM2.5 in Feb–Apr (worst around March). Bring N95 masks and check air quality before going."),
                seasonal,
            )
        }
        if (r == Region.NORTHEAST && months.any { it in 1..4 }) {
            out += Warning(
                RiskKind.HAZE_FIRE, Level.WATCH,
                Txt("อาจมีหมอกควัน", "Possible haze"),
                Txt("ภาคอีสานมีการเผาในที่โล่งช่วง ม.ค.–เม.ย. ค่าฝุ่นอาจสูงบางวัน", "Open burning in the northeast in Jan–Apr can raise PM2.5 on some days."),
                seasonal,
            )
        }
        if ((r == Region.BANGKOK || r == Region.CENTRAL) && months.any { it == 12 || it in 1..2 }) {
            out += Warning(
                RiskKind.HAZE_FIRE, Level.WATCH,
                Txt("ฝุ่น PM2.5 ในเมือง", "City PM2.5"),
                Txt("กรุงเทพฯ และภาคกลางมักมีค่าฝุ่นสูงช่วง ธ.ค.–ก.พ. เมื่ออากาศนิ่ง", "Bangkok and the central plains often see high PM2.5 in Dec–Feb when the air is still."),
                seasonal,
            )
        }
        if (months.any { it in 3..5 }) {
            out += Warning(
                RiskKind.HEAT, Level.WATCH,
                Txt("ฤดูร้อน", "Hot season"),
                Txt("มี.ค.–พ.ค. อุณหภูมิอาจเกิน 38–40°C ดื่มน้ำบ่อยๆ และหลีกเลี่ยงแดดจัดช่วงเที่ยง", "Mar–May can exceed 38–40°C. Drink often and avoid the midday sun."),
                seasonal,
            )
        }
        val southwestMonsoon = r != Region.SOUTH_GULF
        if (southwestMonsoon && months.any { it in 5..10 }) {
            val peak = months.any { it in 8..10 }
            out += Warning(
                RiskKind.HEAVY_RAIN, if (peak) Level.WARNING else Level.WATCH,
                Txt("ฤดูฝน (มรสุมตะวันตกเฉียงใต้)", "Rainy season (southwest monsoon)"),
                Txt("ฝนตกบ่อยช่วง พ.ค.–ต.ค. หนักที่สุดราว ส.ค.–ต.ค. ควรติดตามประกาศเตือนภัยของกรมอุตุนิยมวิทยา",
                    "Frequent rain May–Oct, heaviest around Aug–Oct. Follow Thai Meteorological Department warnings."),
                seasonal,
            )
            if (r == Region.NORTH || r == Region.WEST || r == Region.SOUTH_ANDAMAN) {
                if (months.any { it in 7..10 }) out += Warning(
                    RiskKind.FLASH_FLOOD_LANDSLIDE, Level.WARNING,
                    Txt("เสี่ยงน้ำป่าไหลหลากและดินถล่ม", "Flash flood and landslide risk"),
                    Txt("พื้นที่ภูเขาและเชิงเขามีความเสี่ยงน้ำป่าและดินถล่มช่วง ก.ค.–ต.ค. หลีกเลี่ยงลำห้วยและน้ำตกเมื่อฝนตกหนัก",
                        "Mountain areas face flash floods and landslides in Jul–Oct. Keep away from streams and waterfalls after heavy rain."),
                    seasonal,
                )
            }
            if (r == Region.SOUTH_ANDAMAN) out += Warning(
                RiskKind.ROUGH_SEA, Level.WARNING,
                Txt("ทะเลอันดามันมีคลื่นลมแรง", "Rough Andaman Sea"),
                Txt("พ.ค.–ต.ค. คลื่นสูง เรือบางเส้นทางงดให้บริการ และบางเกาะปิดฤดูมรสุม", "May–Oct brings high waves; some boat routes stop and some islands close for the monsoon."),
                seasonal,
            )
            if ((r == Region.BANGKOK || r == Region.CENTRAL) && months.any { it in 9..10 }) out += Warning(
                RiskKind.FLOOD, Level.WATCH,
                Txt("เสี่ยงน้ำท่วม", "Flood risk"),
                Txt("ภาคกลางมีโอกาสน้ำท่วมช่วง ก.ย.–ต.ค. เมื่อน้ำเหนือไหลลงมา", "The central plains can flood in Sep–Oct when water flows down from the north."),
                seasonal,
            )
        }
        if (r == Region.SOUTH_GULF && months.any { it in 10..12 || it == 1 }) {
            out += Warning(
                RiskKind.HEAVY_RAIN, if (months.any { it in 11..12 }) Level.WARNING else Level.WATCH,
                Txt("ฝนตกหนักฝั่งอ่าวไทย (มรสุมตะวันออกเฉียงเหนือ)", "Heavy rain on the Gulf coast (northeast monsoon)"),
                Txt("ภาคใต้ฝั่งตะวันออก เช่น เกาะสมุย นครศรีธรรมราช สงขลา ฝนตกหนักช่วง ต.ค.–ม.ค. (หนักสุด พ.ย.–ธ.ค.) อาจมีน้ำท่วมและคลื่นสูง",
                    "The southern Gulf coast (Samui, Nakhon Si Thammarat, Songkhla) gets heavy rain Oct–Jan (worst Nov–Dec), with floods and high waves possible."),
                seasonal,
            )
        }
        for (c in place.closures) {
            if (months.any { it in c.months }) out += Warning(
                RiskKind.CLOSURE, Level.WARNING,
                Txt("สถานที่อาจปิดในช่วงนี้", "May be closed at this time"), c.note, parkInfo,
            )
        }
        return out
    }
}
