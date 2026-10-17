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
    val fire = async { if (vm.repo.firmsKey.isNotBlank() && active) vm.remote.hotspots(place.lat, place.lon, vm.repo.firmsKey) else null }
    val tmd = async {
        if (vm.repo.tmdToken.isNotBlank() && soon.isNotEmpty()) vm.remote.tmdDaily(place.lat, place.lon, soon.first(), 7, vm.repo.tmdToken) else null
    }

    air.await().onSuccess { a ->
        val pm = a.pm25 ?: return@onSuccess
        val level = when { pm > 75 -> Level.WARNING; pm > 37.5 -> Level.WATCH; else -> Level.INFO }
        warnings += Warning(
            RiskKind.HAZE_FIRE, level,
            Txt("PM2.5 ตอนนี้ ${"%.0f".format(pm)} µg/m³", "PM2.5 now ${"%.0f".format(pm)} µg/m³"),
            Txt("สถานี ${a.station} (ห่าง ${"%.0f".format(a.distanceKm)} กม.) ${a.time}" + if (pm > 37.5) " · เกินค่ามาตรฐาน 24 ชม. ของไทย (37.5)" else "",
                "Station ${a.station} (${"%.0f".format(a.distanceKm)} km away) ${a.time}" + if (pm > 37.5) " · above Thailand's 24-h standard (37.5)" else ""),
            Txt("กรมควบคุมมลพิษ (Air4Thai)", "Pollution Control Department (Air4Thai)"),
        )
    }.onFailure { notes += Txt("ดึงค่าฝุ่นจาก Air4Thai ไม่ได้ในตอนนี้", "Couldn't get PM2.5 from Air4Thai right now") }

    fire.await()?.onSuccess { n ->
        if (n > 0) warnings += Warning(
            RiskKind.HAZE_FIRE, if (n >= 10) Level.WARNING else Level.WATCH,
            Txt("พบจุดความร้อน $n จุดในรัศมี 30 กม.", "$n fire hotspots within 30 km"),
            Txt("จากดาวเทียมใน 2 วันที่ผ่านมา อาจเป็นไฟป่าหรือการเผา", "Seen by satellite in the last 2 days; may be forest fires or burning."),
            Txt("ดาวเทียม VIIRS ผ่าน NASA FIRMS (ข้อมูลชุดเดียวกับที่ GISTDA ใช้)", "VIIRS satellite via NASA FIRMS (same data GISTDA uses)"),
        )
    }?.onFailure { notes += Txt("ดึงข้อมูลจุดความร้อนไม่ได้", "Couldn't get fire hotspots") }

    tmd.await()?.onSuccess { list ->
        list.filter { it.date in soon }.forEach { f ->
            val heavy = (f.cond ?: 0) in 7..8 || (f.rainMm ?: 0.0) >= 35.0
            val hot = (f.tMax ?: 0.0) >= 39.0
            if (heavy) warnings += Warning(
                RiskKind.HEAVY_RAIN, Level.WARNING,
                Txt("พยากรณ์ฝนตกหนัก ${fmt(f.date, true)}", "Heavy rain forecast ${fmt(f.date, false)}"),
                Txt("ปริมาณฝน ${f.rainMm ?: "-"} มม. ระวังน้ำป่าในพื้นที่ภูเขา", "Rain ${f.rainMm ?: "-"} mm. Watch for flash floods in hilly areas."),
                Txt("กรมอุตุนิยมวิทยา", "Thai Meteorological Department"),
            )
            if (hot) warnings += Warning(
                RiskKind.HEAT, Level.WATCH,
                Txt("อากาศร้อนจัด ${fmt(f.date, true)}", "Very hot ${fmt(f.date, false)}"),
                Txt("สูงสุด ${f.tMax}°C", "High ${f.tMax}°C"),
                Txt("กรมอุตุนิยมวิทยา", "Thai Meteorological Department"),
            )
        }
    }?.onFailure { notes += Txt("ดึงพยากรณ์จากกรมอุตุนิยมวิทยาไม่ได้", "Couldn't get the TMD forecast") }

    if (soon.isEmpty()) notes += Txt("วันเที่ยวยังเกิน 7 วัน จึงแสดงความเสี่ยงตามฤดูกาล พยากรณ์จริงจะขึ้นเมื่อใกล้วัน", "Trip days are more than 7 days away, so seasonal risks are shown; real forecasts appear closer to the date.")
    else if (vm.repo.tmdToken.isBlank()) notes += Txt("ใส่ token กรมอุตุนิยมวิทยาในหน้าตั้งค่าเพื่อดูพยากรณ์รายวัน", "Add a TMD token in Settings to see daily forecasts.")
    if (vm.repo.firmsKey.isBlank()) notes += Txt("ใส่ FIRMS key ในหน้าตั้งค่าเพื่อตรวจจุดความร้อนไฟป่า", "Add a FIRMS key in Settings to check fire hotspots.")
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
