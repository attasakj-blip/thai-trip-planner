@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package th.trip.planner.ui

import android.content.Intent
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
import androidx.compose.foundation.combinedClickable
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
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.Trip
import java.io.File

// ---------------------------------------------------------------- My trips

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MyTripsScreen(vm: TripViewModel, nav: NavHostController) {
    val thai = LocalThai.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var toDelete by remember { mutableStateOf<th.trip.planner.model.Trip?>(null) }
    val trips = remember(vm.trip, vm.tripsVersion) { (listOf(vm.trip) + vm.repo.archivedTrips().filter { it.id != vm.trip.id }).filter { it.stops.isNotEmpty() } }
    toDelete?.let { t ->
        val name = t.stops.mapNotNull { vm.repo.placeById[it.placeId]?.name?.get(thai) }.joinToString(" → ").ifBlank { tr("ทริปนี้", "this trip") }
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("ลบทริปนี้ไหม?", "Delete this trip?")) },
            text = { Text(tr("$name\nลบแล้วกู้คืนไม่ได้", "$name\nThis can't be undone.")) },
            confirmButton = { TextButton(onClick = { vm.deleteTrip(t.id); toDelete = null }) { Text(tr("ลบ", "Delete"), color = Color(0xFFD32F2F)) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("ยกเลิก", "Cancel")) } },
        )
    }
    Page(tr("ทริปของฉัน", "My trips"), nav) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trips.isEmpty()) item { Text(tr("ยังไม่มีทริป", "No trips yet"), color = Color.Gray) }
            if (trips.isNotEmpty()) item { Text(tr("กดค้างที่ทริปเพื่อลบทริปที่จบแล้ว", "Press and hold a trip to delete it once it has ended"), fontSize = 12.sp, color = Color.Gray) }
            items(trips, key = { it.id }) { t ->
                val places = t.stops.mapNotNull { vm.repo.placeById[it.placeId]?.name?.get(thai) }
                val first = TripLogic.firstDay(t); val last = TripLogic.lastDay(t)
                val current = t.id == vm.trip.id && !t.finished
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { if (current) nav.navigate(Routes.SUMMARY) }, onLongClick = {
                    if (current) android.widget.Toast.makeText(ctx, tr("ลบได้เมื่อจบทริปแล้ว", "You can delete a trip once it has ended"), android.widget.Toast.LENGTH_SHORT).show()
                    else toDelete = t
                })) {
                    Column(Modifier.padding(12.dp)) {
                        Text(places.joinToString(" → "), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        if (first != null && last != null) Text("${fmt(first, thai)} – ${fmt(last, thai)}", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            if (current) tr("ทริปปัจจุบัน · แตะเพื่อดูสรุปทริป", "Current trip · tap for the summary")
                            else if (t.finished) tr("จบทริปแล้ว", "Ended") else tr("ทริปที่เก็บไว้", "Saved trip"),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
