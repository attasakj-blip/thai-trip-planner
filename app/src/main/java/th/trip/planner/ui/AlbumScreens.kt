@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package th.trip.planner.ui

import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import th.trip.planner.TripViewModel
import th.trip.planner.audio.LoopPlayer
import th.trip.planner.audio.MusicGen
import th.trip.planner.data.Photos
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.AlbumPhoto
import th.trip.planner.model.MusicChoice
import th.trip.planner.model.MusicStyle
import th.trip.planner.model.SlideSection
import th.trip.planner.model.Slideshow
import th.trip.planner.model.Tempo
import th.trip.planner.model.Transition
import th.trip.planner.model.Trip
import java.io.File
import java.time.LocalDate

fun styleLabel(s: MusicStyle, thai: Boolean) = when (s) {
    MusicStyle.THAI_LOCAL -> tr("🪘 ดนตรีท้องถิ่น", "🪘 Thai local")
    MusicStyle.MUSIC_BOX -> tr("🎁 กล่องดนตรี", "🎁 Music box")
    MusicStyle.HARP -> tr("🪉 ฮาร์ป", "🪉 Harp")
    MusicStyle.JAZZ -> tr("🎷 แจ๊ส", "🎷 Jazz")
    MusicStyle.SAMBA -> tr("🪇 แซมบ้า", "🪇 Samba")
    MusicStyle.FLUTE -> tr("🪈 ขลุ่ย", "🪈 Flute (khlui)")
}

fun tempoLabel(t: Tempo, thai: Boolean) = when (t) {
    Tempo.SLOW -> tr("ช้า", "Slow")
    Tempo.MEDIUM -> tr("ปานกลาง", "Medium")
    Tempo.FAST -> tr("เร็ว", "Fast")
}

/** The transition that suits each tempo: slow photos morph, medium ones zoom-fade, fast ones slide. */
fun transitionFor(t: Tempo) = when (t) { Tempo.SLOW -> Transition.MORPH; Tempo.MEDIUM -> Transition.CROSSFADE_ZOOM; Tempo.FAST -> Transition.SLIDE }

fun transitionLabel(t: Transition, thai: Boolean) = when (t) {
    Transition.MORPH -> tr("เปลี่ยนภาพแบบ morph นุ่มนวล", "Soft morph")
    Transition.CROSSFADE_ZOOM -> tr("ซูมและจางเข้า", "Zoom & fade")
    Transition.SLIDE -> tr("เลื่อนภาพ", "Slide")
}

// ---------------------------------------------------------------- Prompts on the trip screen

/** Daily album question, camera shortcut, album link and the last-day "finish the album?" question. */
@Composable
fun AlbumCards(vm: TripViewModel, nav: NavHostController) {
    val trip = vm.trip
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val first = TripLogic.firstDay(trip) ?: return
    val last = TripLogic.lastDay(trip) ?: return
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val answer = trip.albumDays[today.toString()]

    if (!trip.albumFinalized && today in first..last && answer == null) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F4FF))) {
            Column(Modifier.padding(14.dp)) {
                Text(tr("📷 วันนี้อยากเก็บภาพที่คุณถ่ายเข้าอัลบั้มทริปไหม?", "📷 Collect today's photos for your trip album?"), fontWeight = FontWeight.Bold)
                Text(tr("เปิด GPS ไว้ แล้วถ่ายด้วยแอปกล้องได้เลย เมื่อกลับมาที่แอปนี้จะให้เลือกภาพเข้าอัลบั้ม", "Keep GPS on and use your camera app. When you come back here, you'll pick which photos to add."), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.answerAlbumToday(true); permLauncher.launch(Photos.permissions()) }) { Text(tr("ต้องการ", "Yes")) }
                    OutlinedButton(onClick = { vm.answerAlbumToday(false) }) { Text(tr("วันนี้ไม่ต้อง", "Not today")) }
                }
            }
        }
    }
    if (answer == true && !trip.albumFinalized) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.weight(1f)) { Text(tr("📷 เปิดกล้อง", "📷 Open camera")) }
            if (!Photos.gpsOn(ctx)) Text(tr("⚠️ GPS ปิดอยู่", "⚠️ GPS is off"), color = Coral, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
    if (trip.photos.isNotEmpty() || trip.albumFinalized) {
        OutlinedButton(onClick = { nav.navigate(Routes.album(trip.id)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(tr("📔 อัลบั้มทริป (${trip.photos.size} ภาพ)", "📔 Trip album (${trip.photos.size} photos)"))
        }
    }
    if (!trip.albumFinalized && trip.photos.isNotEmpty() && today >= last) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3C4))) {
            Column(Modifier.padding(14.dp)) {
                Text(tr("วันนี้เป็นวันสุดท้ายของทริป ทำอัลบั้มให้สมบูรณ์เลยไหม?", "It's the last day. Finish the album now?"), fontWeight = FontWeight.Bold)
                Text(tr("จะหยุดนำเข้าภาพเพิ่ม แล้วสร้างสไลด์โชว์พร้อมเพลงที่คุณเลือก", "Photo import stops and a slideshow is made with the music you choose."), fontSize = 13.sp)
                Button(onClick = { nav.navigate(Routes.SLIDESHOW_SETUP) }) { Text(tr("สร้างสไลด์โชว์", "Make the slideshow")) }
            }
        }
    }
}

/** Shown when the user comes back from the camera with new photos. */
@Composable
fun ImportDialog(vm: TripViewModel) {
    val list = vm.candidates
    if (vm.gpsOffNotice) AlertDialog(
        onDismissRequest = { vm.gpsOffNotice = false },
        confirmButton = { TextButton(onClick = { vm.gpsOffNotice = false }) { Text(tr("ตกลง", "OK")) } },
        title = { Text(tr("GPS ปิดอยู่", "GPS is off")) },
        text = { Text(tr("ภาพที่ถ่ายตอน GPS ปิดจะเข้าอัลบั้มไม่ได้ เปิด GPS แล้วถ่ายใหม่นะครับ", "Photos taken with GPS off can't go into the album. Turn GPS on and take them again.")) },
    )
    if (list.isEmpty()) return
    val thai = LocalThai.current
    val allowed = list.filter { it.allowed }
    val chosen = remember(list) { mutableStateListOf(*allowed.toTypedArray()) }
    AlertDialog(
        onDismissRequest = { vm.dismissCandidates() },
        title = { Text(tr("เพิ่มภาพที่เพิ่งถ่ายเข้าอัลบั้มไหม?", "Add the photos you just took?")) },
        text = {
            Column {
                Text(tr("แตะเพื่อเลือกหรือยกเลิกแต่ละภาพ", "Tap to select or unselect each photo"), fontSize = 13.sp)
                if (allowed.size < list.size) Text(
                    tr("ภาพที่ถ่ายห่างจากสถานที่ในแพลนเกิน 40 กม. หรือไม่มีข้อมูลตำแหน่ง จะเพิ่มเข้าอัลบั้มทริปนี้ไม่ได้", "Photos taken more than 40 km from every planned place, or without a location, can't go into this trip's album."),
                    fontSize = 12.sp, color = Coral, modifier = Modifier.padding(top = 4.dp),
                )
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(list) { c ->
                        val on = c in chosen
                        Box(Modifier.padding(3.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .border(if (on) 3.dp else 0.dp, if (on) Teal else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(enabled = c.allowed) { if (on) chosen.remove(c) else chosen.add(c) }) {
                            AsyncImage(c.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (on) 1f else if (c.allowed) 0.45f else 0.25f })
                            if (on) Text("✓", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(Teal, RoundedCornerShape(50)).padding(horizontal = 6.dp))
                            if (!c.allowed) {
                                val why = when {
                                    c.nearestKm == null -> tr("ไม่มีตำแหน่ง", "No location")
                                    else -> tr("ห่าง ${c.nearestKm.toInt()} กม.", "${c.nearestKm.toInt()} km away")
                                }
                                Text("🚫 $why", color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC8B1E1E)).padding(2.dp))
                            } else c.placeId?.let { id ->
                                Text(vm.repo.placeById[id]?.name?.get(thai) ?: "", color = Color.White, fontSize = 10.sp, maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.importPhotos(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text(tr("เพิ่ม ${chosen.size} ภาพ", "Add ${chosen.size}")) } },
        dismissButton = { TextButton(onClick = { vm.dismissCandidates() }) { Text(tr("ไม่เพิ่ม", "Skip")) } },
    )
}

// ---------------------------------------------------------------- Album

private fun sectionsOf(vm: TripViewModel, trip: Trip): List<Pair<String?, List<AlbumPhoto>>> {
    val order = trip.stops.map { it.placeId }
    return trip.photos.groupBy { it.placeId }.entries
        .sortedBy { (id, _) -> order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
        .map { it.key to it.value.sortedBy { p -> p.takenAt } }
}

@Composable
fun AlbumScreen(vm: TripViewModel, nav: NavHostController, tripId: String) {
    val thai = LocalThai.current
    var version by remember { mutableIntStateOf(0) }
    val trip = remember(tripId, vm.trip, version) { vm.tripById(tripId) } ?: return
    var viewing by remember { mutableStateOf<AlbumPhoto?>(null) }
    val folder = vm.repo.tripDir(trip.id)
    Page(
        tr("อัลบั้มทริป", "Trip album"), nav,
        bottom = {
            when {
                trip.slideshow != null -> NextButton(tr("▶ เล่นสไลด์โชว์", "▶ Play slideshow")) { nav.navigate(Routes.player(trip.id)) }
                tripId == vm.trip.id && trip.photos.isNotEmpty() -> NextButton(tr("ทำอัลบั้มให้สมบูรณ์ + สร้างสไลด์โชว์", "Finish album & make slideshow")) { nav.navigate(Routes.SLIDESHOW_SETUP) }
                else -> {}
            }
        },
    ) { pad ->
        if (trip.photos.isEmpty()) {
            Text(tr("ยังไม่มีภาพในอัลบั้ม", "No photos yet"), Modifier.padding(pad).padding(24.dp), color = Color.Gray)
            return@Page
        }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp)) {
            sectionsOf(vm, trip).forEach { (placeId, photos) ->
                item(key = "h-$placeId") {
                    val name = placeId?.let { vm.repo.placeById[it]?.name?.get(thai) } ?: tr("ระหว่างเดินทาง", "On the way")
                    SectionTitle("📍 $name · ${photos.size}")
                }
                photos.groupBy { it.date }.forEach { (date, ofDay) ->
                    item(key = "d-$placeId-$date") { Text(fmt(LocalDate.parse(date), thai), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp)) }
                    item(key = "g-$placeId-$date") {
                        FlowRow(maxItemsInEachRow = 3, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ofDay.forEach { p ->
                                AsyncImage(File(folder, p.file), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { viewing = p })
                            }
                            repeat((3 - ofDay.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
    viewing?.let { p ->
        var confirm by remember(p) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewing = null },
            text = {
                Column {
                    AsyncImage(File(folder, p.file), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.takenAt)), fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text(tr("ปิด", "Close")) } },
            dismissButton = {
                TextButton(onClick = {
                    if (!confirm) confirm = true else { vm.deletePhoto(p, trip.id); version++; viewing = null }
                }) { Text(if (confirm) tr("ยืนยันลบ", "Confirm delete") else tr("🗑 ลบออกจากอัลบั้ม", "🗑 Remove"), color = Coral) }
            },
        )
    }
}

// ---------------------------------------------------------------- Slideshow setup

@Composable
fun SlideshowSetupScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trip = vm.trip
    val sections = sectionsOf(vm, trip)
    var withMusic by remember { mutableStateOf(true) }
    val choices = remember { mutableStateMapOf<String, MusicChoice>() }
    val player = remember { LoopPlayer() }
    var previewing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { player.stop() } }

    fun keyOf(id: String?) = id ?: "other"
    fun choiceOf(id: String?) = choices[keyOf(id)] ?: MusicChoice(MusicStyle.THAI_LOCAL, Tempo.SLOW, 0)

    Page(
        tr("สร้างสไลด์โชว์", "Make the slideshow"), nav,
        bottom = {
            NextButton(tr("ประมวลผลเป็นสไลด์โชว์", "Create slideshow")) {
                player.stop()
                val show = Slideshow(sections.map { (id, _) ->
                    val c = choiceOf(id)
                    SlideSection(id, if (withMusic) c else null, transitionFor(c.tempo))
                }, System.currentTimeMillis())
                vm.finalizeAlbum(show)
                vm.exportVideo(trip.id)
                nav.navigate(Routes.player(trip.id)) { popUpTo(Routes.SLIDESHOW_SETUP) { inclusive = true } }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(tr("เมื่อสร้างแล้วจะหยุดนำเข้าภาพเพิ่ม แต่ละสถานที่จะเริ่มด้วยป้ายชื่อที่ตกแต่งสวยงาม ตามด้วยภาพถ่ายพร้อมเอฟเฟกต์ที่เข้ากับจังหวะเพลง แล้วค่อยๆ จางหายเมื่อภาพของที่นั่นหมด", "Once created, no more photos are imported. Each place opens with a decorated title card, then its photos with transitions that suit the tempo, fading out at the end."), fontSize = 13.sp, color = Color.DarkGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("มีเสียงเพลงบรรเลงประกอบ", "Background music"), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(withMusic, { withMusic = it })
                }
            }
            items(sections, key = { keyOf(it.first) }) { (id, photos) ->
                val name = id?.let { vm.repo.placeById[it]?.name?.get(thai) } ?: tr("ระหว่างเดินทาง", "On the way")
                val c = choiceOf(id)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📍 $name (${photos.size})", fontWeight = FontWeight.Bold)
                        if (withMusic) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MusicStyle.entries.forEach { s -> FilterChip(c.style == s, { choices[keyOf(id)] = c.copy(style = s) }, label = { Text(styleLabel(s, thai)) }) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tempo.entries.forEach { t -> FilterChip(c.tempo == t, { choices[keyOf(id)] = c.copy(tempo = t) }, label = { Text(tempoLabel(t, thai)) }) }
                            }
                            Text(tr("เลือกเพลง (ยาวเพลงละ 1 นาที วนต่อเนื่องจนภาพของที่นี่หมด)", "Pick a piece (1 minute each, looped until this place's photos end)"), fontSize = 12.sp, color = Color.Gray)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0..2).forEach { v -> FilterChip(c.variant == v, { choices[keyOf(id)] = c.copy(variant = v) }, label = { Text("${v + 1}. " + MusicGen.pieceName(c.style, v, thai)) }) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    if (previewing == keyOf(id)) { player.stop(); previewing = null }
                                    else {
                                        previewing = keyOf(id)
                                        scope.launch {
                                            val pcm = withContext(Dispatchers.Default) { MusicGen.render(c) }
                                            if (previewing == keyOf(id)) player.start(pcm)
                                        }
                                    }
                                }) { Text(if (previewing == keyOf(id)) tr("⏹ หยุดฟัง", "⏹ Stop") else tr("▶ ฟังตัวอย่าง", "▶ Preview")) }
                                Text(transitionLabel(transitionFor(c.tempo), thai), fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Slideshow video

/** Makes (if needed) and plays the trip's slideshow video, stored at trips/<id>/slideshow.mp4. */
@Composable
fun SlideshowPlayerScreen(vm: TripViewModel, nav: NavHostController, tripId: String) {
    val trip = remember(tripId) { vm.tripById(tripId) } ?: return
    if (trip.slideshow == null) return
    val file = vm.videoFile(trip.id)
    val busy = vm.exportingTrip == trip.id
    var ready by remember(busy) { mutableStateOf(!busy && file.exists()) }
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy // the phone must stay awake while the video is being made
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) { if (!file.exists() && vm.exportingTrip == null && vm.exportError == null) vm.exportVideo(trip.id) }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            ready -> {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val controls = MediaController(ctx)
                            controls.setAnchorView(this)
                            setMediaController(controls)
                            setVideoPath(file.path)
                            setOnPreparedListener { it.isLooping = false; start() }
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { file.delete(); ready = false; vm.exportVideo(trip.id) }) { Text(tr("ทำวิดีโอใหม่", "Remake"), color = Color.White) }
                    Button(onClick = { nav.popBackStack() }) { Text(tr("ปิด", "Close")) }
                }
                Text(
                    tr("เก็บไว้ในโฟลเดอร์ทริปนี้ และใน Movies/ThaiTrip ของเครื่อง", "Saved in this trip's folder and in Movies/ThaiTrip"),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
            busy -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                val p = vm.exportProgress ?: 0f
                Text("🎬", fontSize = 48.sp)
                Text(tr("กำลังสร้างวิดีโอสไลด์โชว์", "Making your slideshow video"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${(p * 100).toInt()}%", color = Color.White, fontSize = 32.sp, modifier = Modifier.padding(vertical = 12.dp))
                androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text(
                    tr("เปิดแอปค้างไว้จนเสร็จนะครับ ป้ายชื่อสถานที่ ภาพ และเพลงของแต่ละที่จะถูกรวมเป็นไฟล์ MP4", "Keep the app open until it's done. Title cards, photos and each place's music are being combined into an MP4."),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { vm.cancelExport(); nav.popBackStack() }) { Text(tr("ยกเลิก", "Cancel"), color = Coral) }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                vm.exportError?.let { Text(tr("สร้างวิดีโอไม่สำเร็จ: $it", "Couldn't make the video: $it"), color = Coral, textAlign = TextAlign.Center) }
                Button(onClick = { vm.exportVideo(trip.id) }, modifier = Modifier.padding(top = 12.dp)) { Text(tr("🎬 สร้างไฟล์วิดีโอ", "🎬 Make the video")) }
                TextButton(onClick = { nav.popBackStack() }) { Text(tr("ปิด", "Close"), color = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- My trips

@Composable
fun MyTripsScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val trips = remember(vm.trip) { (listOf(vm.trip) + vm.repo.archivedTrips().filter { it.id != vm.trip.id }).filter { it.stops.isNotEmpty() || it.photos.isNotEmpty() } }
    Page(tr("ทริปของฉัน", "My trips"), nav) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trips.isEmpty()) item { Text(tr("ยังไม่มีทริป", "No trips yet"), color = Color.Gray) }
            items(trips, key = { it.id }) { t ->
                val places = t.stops.mapNotNull { vm.repo.placeById[it.placeId]?.name?.get(thai) }
                val first = TripLogic.firstDay(t); val last = TripLogic.lastDay(t)
                val cover = t.photos.firstOrNull()?.let { File(vm.repo.tripDir(t.id), it.file) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)))
                        Text(places.joinToString(" → "), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        if (first != null && last != null) Text("${fmt(first, thai)} – ${fmt(last, thai)}", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            (if (t.id == vm.trip.id && !t.finished) tr("ทริปปัจจุบัน · ", "Current trip · ") else "") + tr("${t.photos.size} ภาพ", "${t.photos.size} photos") +
                                (if (t.slideshow != null) tr(" · มีสไลด์โชว์", " · slideshow ready") else ""),
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate(Routes.album(t.id)) }) { Text(tr("📔 อัลบั้ม", "📔 Album")) }
                            if (t.slideshow != null) Button(onClick = { nav.navigate(Routes.player(t.id)) }) { Text(tr("▶ สไลด์โชว์", "▶ Slideshow")) }
                        }
                    }
                }
            }
        }
    }
}
