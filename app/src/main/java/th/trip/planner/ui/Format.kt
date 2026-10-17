package th.trip.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import th.trip.planner.TripViewModel
import th.trip.planner.data.Photo
import th.trip.planner.model.GapPurpose
import th.trip.planner.model.HubType
import th.trip.planner.model.Place
import th.trip.planner.model.TravelMode
import java.time.LocalDate

private val thMonths = listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")
private val enMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val thMonthsFull = listOf("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน", "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")
private val enMonthsFull = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

fun monthShort(m: Int, thai: Boolean) =
    if (thai) thMonths[m - 1]
    else if (I18n.lang == "en" || I18n.thai) enMonths[m - 1]
    else java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, I18n.current.locale)
fun monthYear(d: LocalDate, thai: Boolean) =
    if (thai) "${thMonthsFull[d.monthValue - 1]} ${d.year + 543}"
    else if (I18n.lang == "en" || I18n.thai) "${enMonthsFull[d.monthValue - 1]} ${d.year}"
    else java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", I18n.current.locale).format(d)

/** "1 พ.ย. 2569" / "1 Nov 2026". */
fun fmt(d: LocalDate, thai: Boolean) =
    if (thai) "${d.dayOfMonth} ${thMonths[d.monthValue - 1]} ${d.year + 543}"
    else if (I18n.lang == "en" || I18n.thai) "${d.dayOfMonth} ${enMonths[d.monthValue - 1]} ${d.year}"
    else java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(I18n.current.locale).format(d)

/** Compact list of dates, e.g. "1, 2, 5 พ.ย. 2569". */
fun fmtDates(dates: List<LocalDate>, thai: Boolean): String {
    if (dates.isEmpty()) return ""
    val byMonth = dates.sorted().groupBy { it.year to it.monthValue }
    return byMonth.entries.joinToString(" · ") { (ym, ds) ->
        val (y, m) = ym
        ds.joinToString(", ") { it.dayOfMonth.toString() } + " " + monthShort(m, thai) + " " + (if (thai) y + 543 else y)
    }
}

fun modeLabel(m: TravelMode, thai: Boolean) = when (m) {
    TravelMode.FLIGHT -> tr("✈️ เครื่องบิน", "✈️ Flight")
    TravelMode.TRAIN -> tr("🚆 รถไฟ", "🚆 Train")
    TravelMode.BUS -> tr("🚌 รถโดยสาร", "🚌 Bus")
    TravelMode.BOAT -> tr("⛴️ เรือ", "⛴️ Boat")
    TravelMode.CAR -> tr("🚗 รถยนต์ / เรียกรถ", "🚗 Car / ride-hailing")
    TravelMode.VAN -> tr("🚐 รถตู้โดยสาร", "🚐 Minivan")
}

fun hubTypeLabel(t: HubType, thai: Boolean) = when (t) {
    HubType.AIRPORT -> tr("✈️ สนามบิน", "✈️ Airport")
    HubType.BUS -> tr("🚌 สถานีขนส่ง", "🚌 Bus terminal")
    HubType.TRAIN -> tr("🚆 สถานีรถไฟ", "🚆 Railway station")
    HubType.PIER -> tr("⛴️ ท่าเรือ", "⛴️ Pier")
}

fun purposeLabel(p: GapPurpose, thai: Boolean) = when (p) {
    GapPurpose.WORK -> tr("💼 ทำงาน/ภารกิจ", "💼 Work / errands")
    GapPurpose.VISIT -> tr("🏠 เยี่ยมเพื่อนหรือญาติ", "🏠 Visit friends or family")
    GapPurpose.TRAVEL -> tr("🧳 ใช้เดินทาง", "🧳 Travel time")
    GapPurpose.OTHER -> tr("✨ กิจกรรมอื่น", "✨ Something else")
}

/** Loads free-licensed photos for a place (Wikimedia Commons via Wikipedia). */
@Composable
fun rememberPhotos(vm: TripViewModel, place: Place): List<Photo>? =
    produceState<List<Photo>?>(initialValue = null, place.id) {
        value = vm.remote.photos(place.wikiTitles).getOrDefault(emptyList())
    }.value

/** A photo with its author and licence written on it, as the licence requires. */
@Composable
fun CreditedPhoto(photo: Photo?, modifier: Modifier = Modifier, ratio: Float = 16f / 9f) {
    Box(modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE6EFEA))) {
        if (photo != null) {
            AsyncImage(model = photo.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("📷 ${photo.author} · ${photo.license}", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("🏞️", fontSize = 40.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun Badge(text: String, bg: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}
