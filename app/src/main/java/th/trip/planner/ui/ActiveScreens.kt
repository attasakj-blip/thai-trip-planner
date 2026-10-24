@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package th.trip.planner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import th.trip.planner.TripViewModel
import th.trip.planner.audio.LoopPlayer
import th.trip.planner.audio.ThaiMusic
import th.trip.planner.data.Photo
import th.trip.planner.logic.TripLogic
import th.trip.planner.logic.TripStatus
import th.trip.planner.model.TravelMode
import th.trip.planner.ui.scenes.SceneKind
import th.trip.planner.ui.scenes.TravelScene
import java.time.LocalDate
import java.time.LocalTime

internal fun hasLocation(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Follows the phone's position while the screen is open. Returns null until a fix arrives. */
@SuppressLint("MissingPermission")
@Composable
private fun rememberLocation(granted: Boolean): Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(granted) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = LocationListener { loc = it }
        if (granted && hasLocation(ctx)) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { loc = it }
            providers.forEach { runCatching { lm.requestLocationUpdates(it, 60_000L, 200f, listener, Looper.getMainLooper()) } }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return loc
}

// ---------------------------------------------------------------- Active trip

@Composable
fun ActiveScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val ctx = LocalContext.current
    val trip = vm.trip
    var granted by remember { mutableStateOf(hasLocation(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasLocation(ctx) }
    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) launcher.launch(perms.toTypedArray())
    }
    val loc = rememberLocation(granted)
    val today = LocalDate.now()
    val status = TripLogic.status(trip, vm.repo.placeById, today, loc?.latitude, loc?.longitude)
    val lastDay = TripLogic.lastDay(trip)
    LaunchedEffect(status) {
        if (trip.activated && status == TripStatus.Finished) nav.navigate(Routes.FINALE)
    }

    val scene = when (status) {
        is TripStatus.Travelling -> SceneKind.Moving(trip.legs.getOrNull(status.toStop - 1)?.mode ?: TravelMode.CAR)
        is TripStatus.AtPlace -> SceneKind.AtPlace
        is TripStatus.InGap -> SceneKind.Break
        else -> SceneKind.Waiting
    }
    val caption = when (status) {
        TripStatus.NotStarted -> tr("ทริปยังไม่เริ่ม เริ่ม ${TripLogic.firstDay(trip)?.let { fmt(it, true) } ?: ""}", "Trip starts ${TripLogic.firstDay(trip)?.let { fmt(it, false) } ?: ""}")
        is TripStatus.AtPlace -> tr("ตอนนี้อยู่ที่ ", "You're at ") + (vm.repo.placeById[trip.stops[status.stopIndex].placeId]?.name?.get(thai) ?: "")
        is TripStatus.Travelling -> {
            val to = trip.stops.getOrNull(status.toStop)?.let { vm.repo.placeById[it.placeId]?.name?.get(thai) } ?: ""
            tr("กำลังเดินทางไป ", "On the way to ") + to
        }
        is TripStatus.InGap -> {
            val purpose = trip.gapPurposes[today.toString()]
            tr("พักจากการเที่ยว", "A break from sightseeing") + (purpose?.let { " · " + purposeLabel(it, thai) } ?: "")
        }
        TripStatus.Finished -> tr("ถึงจุดหมายแล้ว", "You've arrived")
    }

    Page(tr("ทริปของฉัน", "My trip"), null, showSettings = false) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                TravelScene(scene, Modifier.fillMaxSize())
                Text(
                    caption, color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (!granted) Text(
                tr("เปิด GPS และอนุญาตตำแหน่งเพื่อให้แอปรู้ว่าคุณอยู่ที่ไหน ตอนนี้ใช้วันที่ในปฏิทินแทน", "Turn on GPS and allow location so the app knows where you are. Using calendar dates for now."),
                fontSize = 12.sp, color = Coral, modifier = Modifier.padding(16.dp, 8.dp),
            )
            ProgressPath(vm, status) { }
            // Each place with its food, hospitals, police and fire stations (moved here from the trip summary).
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.stops.forEachIndexed { i, s ->
                    val p = vm.repo.placeById[s.placeId] ?: return@forEachIndexed
                    val here = status is TripStatus.AtPlace && status.stopIndex == i
                    Card(Modifier.fillMaxWidth(), colors = if (here) CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)) else CardDefaults.cardColors()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("📍 ${i + 1}. ${p.name.get(thai)}" + if (here) tr(" (อยู่ที่นี่)", " (you're here)") else "", fontWeight = FontWeight.Bold)
                            Text(fmtDates(TripLogic.dates(s), thai), fontSize = 12.sp, color = Color.Gray)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "food")) }) { Text(tr("🍜 ร้านอาหาร/กาแฟ", "🍜 Food & cafés")) }
                                OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "hospital")) }) { Text(tr("🏥 โรงพยาบาล", "🏥 Hospitals")) }
                                OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "police")) }) { Text(tr("👮 สถานีตำรวจ", "👮 Police")) }
                                OutlinedButton(onClick = { nav.navigate(Routes.nearby(i, "fire")) }) { Text(tr("🚒 สถานีดับเพลิง", "🚒 Fire stations")) }
                            }
                        }
                    }
                }
            }
            VSpace(12)

            val first = TripLogic.firstDay(trip)
            val showCheckIn = first != null && lastDay != null && today in first..lastDay &&
                LocalTime.now().hour >= vm.repo.checkInHour && today.toString() !in trip.checkIns
            if (showCheckIn) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3C4))) {
                Column(Modifier.padding(14.dp)) {
                    Text(tr("วันนี้เป็นไปตามแผนไหม?", "Is today going to plan?"), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.checkIn(true) }) { Text(tr("ตามแผน", "Yes")) }
                        OutlinedButton(onClick = { vm.checkIn(false); nav.navigate(Routes.REPLAN) }) { Text(tr("ไม่ตามแผน", "No")) }
                    }
                }
            }
            Column(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.SUMMARY) }, modifier = Modifier.fillMaxWidth()) { Text(tr("ดูสรุปทริป", "Trip summary")) }
                OutlinedButton(onClick = { nav.navigate(Routes.REPLAN) }, modifier = Modifier.fillMaxWidth()) { Text(tr("ปรับแผนการเดินทาง", "Adjust the plan")) }
                TextButton(onClick = { nav.navigate(Routes.FINALE) }) { Text(tr("ฉันถึงจุดหมายสุดท้ายแล้ว", "I've reached my final destination")) }
                VSpace()
                // Local services near where the traveller is now (GPS), else near today's place.
                val here = (status as? TripStatus.AtPlace)?.let { vm.repo.placeById[trip.stops[it.stopIndex].placeId] }
                when {
                    loc != null -> {
                        val gLat = Math.round(loc.latitude * 50) / 50.0 // ~2 km steps so walking around does not refetch
                        val gLon = Math.round(loc.longitude * 50) / 50.0
                        // The ready-made list of the nearest trip place, when the traveller is close to one.
                        val near = trip.stops.mapNotNull { vm.repo.placeById[it.placeId] }
                            .minByOrNull { th.trip.planner.logic.TripLogic.distanceKm(gLat, gLon, it.lat, it.lon) }
                            ?.takeIf { th.trip.planner.logic.TripLogic.distanceKm(gLat, gLon, it.lat, it.lon) <= 20 }
                        EmergencyCard(vm, gLat, gLon, placeId = near?.id)
                    }
                    here != null -> EmergencyCard(vm, here.lat, here.lon, here.name.get(LocalThai.current), here.id)
                    else -> EmergencyCard()
                }
            }
        }
    }

}

/** The places in order, with the traveller's position marked. */
@Composable
private fun ProgressPath(vm: TripViewModel, status: TripStatus, onTapStop: (Int) -> Unit) {
    val thai = LocalThai.current
    val trip = vm.trip
    // Position on the path: node i = stop i. Half steps = travelling or a break between places.
    val pos: Float = when (status) {
        TripStatus.NotStarted -> -0.5f
        is TripStatus.AtPlace -> status.stopIndex.toFloat()
        is TripStatus.Travelling -> status.toStop - 0.5f
        is TripStatus.InGap -> status.afterStop + 0.5f
        TripStatus.Finished -> trip.stops.size.toFloat()
    }
    val labels = trip.stops.map { vm.repo.placeById[it.placeId]?.name?.get(thai) ?: "" }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { i, label ->
            val done = i < pos
            val here = i.toFloat() == pos
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                Box(
                    Modifier.size(if (here) 46.dp else 36.dp).clip(CircleShape)
                        .background(if (here) Coral else if (done) Teal else Color(0xFFE0E0E0))
                        .border(2.dp, Color.White, CircleShape)
                        .clickable { onTapStop(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (here) "🧳" else if (done) "✓" else "${i + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
            if (i < labels.size - 1) {
                val travelling = pos > i && pos < i + 1
                Box(Modifier.padding(top = 18.dp).width(28.dp).height(4.dp).background(if (pos >= i + 1) Teal else Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                    if (travelling) {
                        val mode = trip.legs.getOrNull(i)?.mode
                        Text(mode?.let { modeLabel(it, thai).take(2) } ?: "🚐", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Replan

@Composable
fun ReplanScreen(vm: TripViewModel, nav: NavHostController) {
    Page(tr("ปรับแผนทริป", "Adjust the trip"), nav) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(tr("จะปรับเปลี่ยนตั้งแต่ส่วนไหน? ข้อมูลส่วนอื่นของทริปเดิมจะถูกเก็บไว้ เมื่อแก้เสร็จให้กดยืนยันการอัปเดตทริปในหน้าสรุป", "What would you like to change? Everything else is kept. When you're done, confirm the update on the summary page."))
            VSpace(12)
            listOf(
                Triple(tr("📅 สถานที่เดิม แต่เปลี่ยนวัน/ลำดับ", "📅 Same places, change days or order"), Routes.SCHEDULE, false),
                Triple(tr("🚆 สถานที่และวันเดิม แต่เปลี่ยนการเดินทาง", "🚆 Same places and days, change transport"), Routes.TRANSPORT, false),
                Triple(tr("🏨 เปลี่ยนที่พัก", "🏨 Change where to stay"), Routes.LODGING, false),
                Triple(tr("➕ เพิ่มหรือลบสถานที่", "➕ Add or remove places"), Routes.CATALOG, false),
                Triple(tr("🔄 เริ่มแพลนใหม่ทั้งหมด", "🔄 Start a completely new plan"), Routes.CATALOG, true),
            ).forEach { (label, route, reset) ->
                OutlinedButton(onClick = {
                    if (reset) vm.newTrip() else vm.startReplan()
                    nav.navigate(route) { popUpTo(Routes.REPLAN) { inclusive = true } }
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label) }
            }
        }
    }
}

// ---------------------------------------------------------------- Finale

@Composable
fun FinaleScreen(vm: TripViewModel, nav: NavHostController, onExit: () -> Unit) {
    val thai = LocalThai.current
    val places = vm.trip.stops.mapNotNull { vm.repo.placeById[it.placeId] }
    // Library photos of the places (Wikimedia Commons).
    val photos = produceState(emptyList<Photo>(), places) {
        value = places.flatMap { p -> vm.repo.placePhotos[p.id].orEmpty().take(2).map { Photo(it.url, it.author, it.license, it.page) }.ifEmpty { vm.remote.photos(p.wikiTitles, max = 2).getOrDefault(emptyList()) } }
    }.value

    // Music: render once, loop forever without a gap, fade in.
    val player = remember { LoopPlayer() }
    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.Default) { ThaiMusic.render() }
        player.start(data)
        for (k in 0..30) { player.setVolume(k / 30f); delay(100) }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    BackHandler { /* stay here until the user ends the trip */ }

    // Slideshow with a soft morph: the next photo fades and zooms in while the current one zooms out.
    var index by remember { mutableIntStateOf(0) }
    val morph = remember { Animatable(0f) }
    LaunchedEffect(photos.size) {
        if (photos.size < 2) return@LaunchedEffect
        while (true) {
            delay(3500)
            morph.snapTo(0f)
            morph.animateTo(1f, tween(1800, easing = LinearEasing))
            index = (index + 1) % photos.size
            morph.snapTo(0f)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (photos.isNotEmpty()) {
            val cur = photos[index % photos.size]
            val next = photos[(index + 1) % photos.size]
            val p = morph.value
            AsyncImage(cur.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - p; scaleX = 1f + 0.12f * p; scaleY = 1f + 0.12f * p
            })
            AsyncImage(next.url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = p; scaleX = 1.12f - 0.12f * p; scaleY = 1.12f - 0.12f * p
            })
            Text("📷 ${cur.author}" + (if (cur.license.isNotBlank()) " · ${cur.license}" else ""), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        } else {
            TravelScene(SceneKind.AtPlace, Modifier.fillMaxSize())
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tr("คุณได้เดินทางท่องเที่ยวครบตามที่วางแผนไว้แล้ว 🎉", "You've completed every stop you planned 🎉"),
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr("หวังว่าคุณจะมีความทรงจำที่ดีกับทริปนี้ และหวังว่าจะได้กลับมาเที่ยวประเทศไทยอีกในอนาคต",
                    "We hope this trip leaves you with wonderful memories, and that you'll come back to Thailand again."),
                color = Color.White, textAlign = TextAlign.Center,
            )
            Text(places.joinToString(" · ") { it.name.get(thai) }, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { player.stop(); vm.finishTrip(); onExit() },
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(tr("สิ้นสุดทริป", "End trip")) }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(vm: TripViewModel, nav: NavHostController) {
    var hour by remember { mutableIntStateOf(vm.repo.checkInHour) }
    Page(tr("ตั้งค่า", "Settings"), nav) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("ภาษาของเมนู", "Menu language"), Modifier.weight(1f))
                LanguageToggle(vm)
            }
            SectionTitle(tr("เวลาเช็กอินประจำวัน", "Daily check-in time"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (hour > 6) hour-- ; vm.repo.checkInHour = hour }) { Text("−") }
                Text(String.format(java.util.Locale.US, "%02d:00", hour), fontSize = 18.sp)
                TextButton(onClick = { if (hour < 22) hour++ ; vm.repo.checkInHour = hour }) { Text("+") }
            }
            SectionTitle(tr("แหล่งข้อมูล", "Data sources"))
            Text(
                tr("• รูปสถานที่: Wikimedia Commons (สัญญาอนุญาตเสรี แสดงชื่อผู้ถ่ายทุกภาพ)\n• ค่าฝุ่น PM2.5: กรมควบคุมมลพิษ (Air4Thai) หรือแบบจำลอง CAMS ผ่าน Open-Meteo\n• พยากรณ์อากาศและน้ำท่วม: Open-Meteo\n• จุดความร้อน: ดาวเทียม VIIRS ผ่าน NASA FIRMS\n• ร้าน ที่พัก โรงพยาบาล ตำรวจ: OpenStreetMap\n• คลังสถานที่และจุดขนส่ง: รวบรวมโดยทีมแอป อัปเดตทุกวันพุธ\nทุกแหล่งใช้ได้ฟรี ไม่ต้องใส่ key",
                    "• Place photos: Wikimedia Commons (free licences, author credited on every photo)\n• PM2.5: Pollution Control Department (Air4Thai), or the CAMS model via Open-Meteo\n• Weather and flood forecasts: Open-Meteo\n• Fire hotspots: VIIRS satellite via NASA FIRMS\n• Food, stays, hospitals, police: OpenStreetMap\n• Place and transport-hub library: curated by the app team, updated every Wednesday\nAll sources are free; no keys needed."),
                fontSize = 13.sp,
            )
        }
    }
}
