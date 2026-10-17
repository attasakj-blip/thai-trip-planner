package th.trip.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import th.trip.planner.TripViewModel
import th.trip.planner.logic.Level
import th.trip.planner.data.AirSource
import th.trip.planner.logic.RiskKind
import th.trip.planner.logic.SeasonalRisk
import th.trip.planner.logic.TripLogic
import th.trip.planner.logic.Warning
import th.trip.planner.model.Place
import th.trip.planner.model.Txt
import java.time.LocalDate

/** Live checks for one place, using real data where the trip days are close enough. */
suspend fun liveWarnings(vm: TripViewModel, place: Place, dates: List<LocalDate>): Pair<List<Warning>, List<Txt>> = coroutineScope {
    val today = LocalDate.now()
    val warnings = mutableListOf<Warning>()
    val notes = mutableListOf<Txt>()
    val soon = dates.filter { !it.isBefore(today) && it.isBefore(today.plusDays(7)) }
    val active = dates.any { !it.isBefore(today.minusDays(1)) && it.isBefore(today.plusDays(14)) }

    val air = async { vm.remote.airQuality(place.lat, place.lon) }
    val fire = async { if (active) vm.remote.hotspots(place.lat, place.lon) else null }
    val weather = async { if (soon.isNotEmpty()) vm.remote.forecast(place.lat, place.lon, 8) else null }
    val flood = async { if (soon.isNotEmpty()) vm.remote.floodRatio(place.lat, place.lon, 8) else null }

    air.await().onSuccess { a ->
        val pm = a.pm25 ?: return@onSuccess
        val level = when { pm > 75 -> Level.WARNING; pm > 37.5 -> Level.WATCH; else -> Level.INFO }
        val pmTxt = "%.0f".format(pm)
        val over = pm > 37.5
        warnings += if (a.source == AirSource.AIR4THAI) Warning(
            RiskKind.HAZE_FIRE, level,
            Txt("PM2.5 ตอนนี้ $pmTxt µg/m³", "PM2.5 now $pmTxt µg/m³"),
            Txt("สถานี ${a.stationTh} (ห่าง ${"%.0f".format(a.distanceKm)} กม.) ${a.time}" + if (over) " · เกินค่ามาตรฐาน 24 ชม. ของไทย (37.5)" else "",
                "Station ${a.stationEn} (${"%.0f".format(a.distanceKm)} km away) ${a.time}" + if (over) " · above Thailand's 24-h standard (37.5)" else ""),
            Txt("กรมควบคุมมลพิษ (Air4Thai)", "Pollution Control Department (Air4Thai)"),
        ) else Warning(
            RiskKind.HAZE_FIRE, level,
            Txt("PM2.5 ประมาณ $pmTxt µg/m³", "PM2.5 about $pmTxt µg/m³"),
            Txt("ค่าประมาณจากแบบจำลองบรรยากาศ ณ ตำแหน่งนี้ ${a.time}" + if (over) " · เกินค่ามาตรฐาน 24 ชม. ของไทย (37.5)" else "",
                "Atmospheric model estimate for this spot, ${a.time}" + if (over) " · above Thailand's 24-h standard (37.5)" else ""),
            Txt("Open-Meteo (แบบจำลอง CAMS ของยุโรป) ใช้แทนเมื่อเชื่อมต่อ Air4Thai ไม่ได้", "Open-Meteo (European CAMS model), used when Air4Thai can't be reached"),
        )
    }.onFailure { notes += Txt("ดึงค่าฝุ่นไม่ได้ในตอนนี้ ลองใหม่เมื่อมีอินเทอร์เน็ต", "Couldn't get PM2.5 right now; try again when online") }

    fire.await()?.onSuccess { n ->
        if (n > 0) warnings += Warning(
            RiskKind.HAZE_FIRE, if (n >= 10) Level.WARNING else Level.WATCH,
            Txt("พบจุดความร้อน $n จุดในรัศมี 30 กม.", "$n fire hotspots within 30 km"),
            Txt("จากดาวเทียมใน 24 ชั่วโมงที่ผ่านมา อาจเป็นไฟป่าหรือการเผา", "Seen by satellite in the last 24 hours; may be forest fires or burning."),
            Txt("ดาวเทียม VIIRS ผ่าน NASA FIRMS (ข้อมูลชุดเดียวกับที่ GISTDA ใช้)", "VIIRS satellite via NASA FIRMS (same data GISTDA uses)"),
        )
    }?.onFailure { notes += Txt("ดึงข้อมูลจุดความร้อนไม่ได้ในตอนนี้", "Couldn't get fire hotspots right now") }

    weather.await()?.onSuccess { list ->
        list.filter { it.date in soon }.forEach { f ->
            val rain = f.rainMm ?: 0.0
            val storm = (f.code ?: 0) in 95..99
            val heavy = rain >= 35.0 || (f.code ?: 0) in listOf(65, 82)
            val rainTxt = "%.0f".format(rain)
            if (heavy || storm) warnings += Warning(
                RiskKind.HEAVY_RAIN, if (rain >= 60) Level.WARNING else Level.WATCH,
                Txt((if (storm) "พายุฝนฟ้าคะนอง " else "ฝนตกหนัก ") + fmt(f.date, true), (if (storm) "Thunderstorms " else "Heavy rain ") + fmt(f.date, false)),
                Txt("ฝนรวม $rainTxt มม." + (f.rainChance?.let { " โอกาสฝน $it%" } ?: "") + " ระวังน้ำป่าและดินถล่มในพื้นที่ภูเขา",
                    "Rain $rainTxt mm" + (f.rainChance?.let { ", $it% chance" } ?: "") + ". Watch for flash floods and landslides in hilly areas."),
                Txt("พยากรณ์ Open-Meteo (ควรเช็กประกาศกรมอุตุนิยมวิทยาประกอบ tmd.go.th)", "Open-Meteo forecast (also check Thai Meteorological Department notices, tmd.go.th)"),
            )
            if ((f.tMax ?: 0.0) >= 39.0) warnings += Warning(
                RiskKind.HEAT, Level.WATCH,
                Txt("อากาศร้อนจัด ${fmt(f.date, true)}", "Very hot ${fmt(f.date, false)}"),
                Txt("สูงสุด ${"%.0f".format(f.tMax)}°C ดื่มน้ำบ่อยๆ หลีกเลี่ยงแดดช่วงเที่ยง", "High ${"%.0f".format(f.tMax)}°C. Drink often and avoid midday sun."),
                Txt("พยากรณ์ Open-Meteo", "Open-Meteo forecast"),
            )
            if ((f.gustKmh ?: 0.0) >= 60.0) warnings += Warning(
                RiskKind.HEAVY_RAIN, Level.WATCH,
                Txt("ลมกระโชกแรง ${fmt(f.date, true)}", "Strong gusts ${fmt(f.date, false)}"),
                Txt("ลมกระโชกถึง ${"%.0f".format(f.gustKmh)} กม./ชม. เรือเล็กควรงดออกจากฝั่ง", "Gusts up to ${"%.0f".format(f.gustKmh)} km/h. Small boats may not sail."),
                Txt("พยากรณ์ Open-Meteo", "Open-Meteo forecast"),
            )
        }
    }?.onFailure { notes += Txt("ดึงพยากรณ์อากาศไม่ได้ในตอนนี้", "Couldn't get the weather forecast right now") }

    flood.await()?.onSuccess { ratio ->
        if (ratio >= 3.0) warnings += Warning(
            RiskKind.FLOOD, if (ratio >= 6.0) Level.WARNING else Level.WATCH,
            Txt("ระดับน้ำในแม่น้ำใกล้เคียงอาจสูงกว่าปกติ", "Nearby river may run higher than usual"),
            Txt("คาดว่าน้ำจะมากกว่าปกติราว ${"%.0f".format(ratio)} เท่าในสัปดาห์นี้ ติดตามประกาศท้องถิ่น", "Forecast flow is about ${"%.0f".format(ratio)}× the usual level this week. Follow local notices."),
            Txt("แบบจำลองน้ำท่วม GloFAS ผ่าน Open-Meteo", "GloFAS flood model via Open-Meteo"),
        )
    }

    if (soon.isEmpty()) notes += Txt("วันเที่ยวยังเกิน 7 วัน จึงแสดงความเสี่ยงตามฤดูกาล พยากรณ์จริงจะขึ้นเมื่อใกล้วัน", "Trip days are more than 7 days away, so seasonal risks are shown; real forecasts appear closer to the date.")
    warnings to notes
}

@Composable
fun WarningsScreen(vm: TripViewModel, nav: NavHostController) {
    val trip = vm.trip
    Page(
        tr("1. คำเตือนสภาพอากาศและภัย", "1. Weather & hazard warnings"), nav, step = 1,
        bottom = {
            NextButton(tr("ยืนยันสถานที่ · ต่อไป: ที่พัก", "Confirm places · Next: stays")) {
                vm.update { it.copy(warningsConfirmed = true) }
                nav.navigate(Routes.LODGING)
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(trip.stops, key = { _, s -> s.placeId }) { i, s ->
                val p = vm.repo.placeById[s.placeId] ?: return@itemsIndexed
                StopWarnings(vm, i, p, TripLogic.dates(s), onChange = { nav.navigate(Routes.SCHEDULE) { popUpTo(Routes.SCHEDULE) { inclusive = true } } })
            }
        }
    }
}

@Composable
private fun StopWarnings(vm: TripViewModel, index: Int, p: Place, dates: List<LocalDate>, onChange: () -> Unit) {
    val thai = LocalThai.current
    val seasonal = SeasonalRisk.forPlace(p, dates)
    val live = produceState<Pair<List<Warning>, List<Txt>>?>(null, p.id, dates) { value = liveWarnings(vm, p, dates) }.value
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${index + 1}. ${p.name.get(thai)}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(fmtDates(dates, thai), fontSize = 13.sp, color = Color.Gray)
            VSpace()
            if (live == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(tr("กำลังตรวจข้อมูลล่าสุด…", "Checking latest data…"), fontSize = 13.sp)
            }
            val all = (live?.first.orEmpty() + seasonal).sortedByDescending { it.level }
            if (all.isEmpty() && live != null) Text(tr("✅ ไม่พบคำเตือนในช่วงนี้", "✅ No warnings for these days"))
            all.forEach { WarningRow(it) }
            live?.second?.forEach { Text("ℹ️ ${it.get(thai)}", fontSize = 12.sp, color = Color.Gray) }
            TextButton(onClick = onChange) { Text(tr("เปลี่ยนวันหรือสถานที่", "Change days or place")) }
        }
    }
}

@Composable
fun WarningRow(w: Warning) {
    val thai = LocalThai.current
    val (bg, icon) = when (w.level) {
        Level.WARNING -> MaterialTheme.colorScheme.errorContainer to "⚠️"
        Level.WATCH -> Color(0xFFFFF3C4) to "🔶"
        Level.INFO -> Color(0xFFE3F4EC) to "ℹ️"
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(10.dp)) {
            Text("$icon ${w.title.get(thai)}", fontWeight = FontWeight.SemiBold)
            Text(w.detail.get(thai), fontSize = 13.sp)
            Text(tr("ที่มา: ", "Source: ") + w.source.get(thai), fontSize = 11.sp, color = Color.Gray)
        }
    }
}
