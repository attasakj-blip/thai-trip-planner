@file:OptIn(ExperimentalMaterial3Api::class)

package th.trip.planner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import th.trip.planner.TripViewModel
import th.trip.planner.model.Txt

/** The app's name, the same in every language (also in res/values/strings.xml). */
const val APP_NAME = "Thai Trip Planner"

val LocalThai = compositionLocalOf { true }

/** Thai text, English text, or (for the other 28 languages) the translation of the English text. */
fun tr(th: String, en: String) = I18n.t(th, en)

fun Txt.text() = I18n.t(th, en)

val Teal = Color(0xFF0E7C66)
val Sand = Color(0xFFFDF8F0)
val Coral = Color(0xFFE76F51)
val Amber = Color(0xFFF4A261)

private val colors = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    secondary = Amber, tertiary = Coral,
    background = Sand, surface = Color.White,
    primaryContainer = Color(0xFFCFEFE6), secondaryContainer = Color(0xFFFFE8D1),
    errorContainer = Color(0xFFFFE1DA),
)

object Routes {
    const val HOME = "home"
    const val CATALOG = "catalog"
    const val PLACE = "place/{id}"
    const val SCHEDULE = "schedule"
    const val WARNINGS = "warnings"
    const val LODGING = "lodging"
    const val TRANSPORT = "transport"
    const val SUMMARY = "summary"
    const val NEARBY = "nearby/{stop}/{kind}"
    const val ACTIVE = "active"
    const val REPLAN = "replan"
    const val FINALE = "finale"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{trip}"
    const val SLIDESHOW_SETUP = "slideshow_setup"
    const val PLAYER = "player/{trip}"
    const val MY_TRIPS = "my_trips"
    fun album(trip: String) = "album/$trip"
    fun player(trip: String) = "player/$trip"
    fun place(id: String) = "place/$id"
    fun nearby(stop: Int, kind: String) = "nearby/$stop/$kind"
}

@Composable
fun AppRoot(vm: TripViewModel, onExit: () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalThai provides vm.thai,
            androidx.compose.ui.platform.LocalLayoutDirection provides
                if (I18n.current.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
            // Android 15 draws the app behind the status bar and the back/home/recents buttons.
            // Keep everything inside the safe area so bottom buttons never sit under them.
            Surface(Modifier.fillMaxSize(), color = Sand) {
              androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val nav = rememberNavController()
                val start = when {
                    vm.trip.finished -> Routes.HOME
                    vm.trip.activated -> Routes.ACTIVE
                    else -> Routes.HOME
                }
                NavHost(nav, startDestination = start) {
                    composable(Routes.HOME) { HomeScreen(vm, nav) }
                    composable(Routes.CATALOG) { CatalogScreen(vm, nav) }
                    composable(Routes.PLACE, listOf(navArgument("id") { type = NavType.StringType })) {
                        PlaceDetailScreen(vm, nav, it.arguments?.getString("id") ?: "")
                    }
                    composable(Routes.SCHEDULE) { ScheduleScreen(vm, nav) }
                    composable(Routes.WARNINGS) { WarningsScreen(vm, nav) }
                    composable(Routes.LODGING) { LodgingScreen(vm, nav) }
                    composable(Routes.TRANSPORT) { TransportScreen(vm, nav) }
                    composable(Routes.SUMMARY) { SummaryScreen(vm, nav) }
                    composable(
                        Routes.NEARBY,
                        listOf(navArgument("stop") { type = NavType.IntType }, navArgument("kind") { type = NavType.StringType }),
                    ) { NearbyScreen(vm, nav, it.arguments?.getInt("stop") ?: 0, it.arguments?.getString("kind") ?: "food") }
                    composable(Routes.ACTIVE) { ActiveScreen(vm, nav) }
                    composable(Routes.REPLAN) { ReplanScreen(vm, nav) }
                    composable(Routes.FINALE) { FinaleScreen(vm, nav, onExit) }
                    composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
                    composable(Routes.ALBUM, listOf(navArgument("trip") { type = NavType.StringType })) {
                        AlbumScreen(vm, nav, it.arguments?.getString("trip") ?: "")
                    }
                    composable(Routes.SLIDESHOW_SETUP) { SlideshowSetupScreen(vm, nav) }
                    composable(Routes.PLAYER, listOf(navArgument("trip") { type = NavType.StringType })) {
                        SlideshowPlayerScreen(vm, nav, it.arguments?.getString("trip") ?: "")
                    }
                    composable(Routes.MY_TRIPS) { MyTripsScreen(vm, nav) }
                }
                ImportDialog(vm)
                StayFormDialog(vm)
              }
            }
        }
    }
}

/** Common page frame: title, optional step progress (1..5), back button and a bottom action. */
@Composable
fun Page(
    title: String,
    nav: NavHostController?,
    step: Int? = null,
    showSettings: Boolean = false,
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        if (nav != null && nav.previousBackStackEntry != null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("กลับ", "Back"))
                            }
                        }
                    },
                    actions = {
                        if (showSettings && nav != null) IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Filled.Settings, contentDescription = tr("ตั้งค่า", "Settings"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Sand),
                )
                if (step != null) {
                    LinearProgressIndicator(progress = { step / 5f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        },
        bottomBar = {
            if (bottom != null) Surface(shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { bottom() }
            }
        },
        containerColor = Sand,
        content = content,
    )
}

@Composable
fun NextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(text) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Teal, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
fun LanguageToggle(vm: TripViewModel) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("🌐 " + I18n.current.native) }
    if (open) LanguageDialog(vm) { open = false }
}

/** Picker for the 30 menu languages, each shown in its own script. */
@Composable
fun LanguageDialog(vm: TripViewModel, onDone: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text(tr("ปิด", "Close")) } },
        title = { Text(tr("เลือกภาษา", "Language")) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(420.dp)) {
                items(I18n.LANGS.size) { i ->
                    val l = I18n.LANGS[i]
                    val on = l.code == I18n.lang
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.setLanguage(l.code); onDone() }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "✓" else "", color = Teal, modifier = Modifier.padding(end = 10.dp))
                        Column {
                            Text(l.native, fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null)
                            if (l.native != l.english) Text(l.english, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun VSpace(h: Int = 8) = Spacer(Modifier.height(h.dp))
