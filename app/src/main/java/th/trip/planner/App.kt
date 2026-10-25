package th.trip.planner


import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient
import th.trip.planner.data.Remote
import th.trip.planner.data.Repository
import th.trip.planner.data.USER_AGENT
import th.trip.planner.logic.TripLogic
import th.trip.planner.model.GapPurpose
import th.trip.planner.model.Leg
import th.trip.planner.model.Lodging
import th.trip.planner.model.Stop
import th.trip.planner.model.Trip
import th.trip.planner.ui.AppRoot
import th.trip.planner.work.CheckInScheduler
import java.time.LocalDate

class TripApp : Application(), ImageLoaderFactory {
    lateinit var repo: Repository
    val remote = Remote()

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        remote.cacheDir = java.io.File(filesDir, "pois").apply { mkdirs() }
        remote.appUpdatedAt = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        CheckInScheduler.createChannel(this)
        // Older versions kept album photo copies and slideshow videos here; the album is gone.
        Thread { runCatching { repo.tripsDir.listFiles().orEmpty().forEach { d -> java.io.File(d, "photos").deleteRecursively(); java.io.File(d, "slideshow.mp4").delete() } } }.start()
        // Check GitHub Pages for this week's data; offline is fine, the app keeps what it has.
        Thread { runCatching { th.trip.planner.data.DataSync.run(repo) } }.start()
    }

    // Wikimedia asks every client to send a descriptive User-Agent.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { c -> c.proceed(c.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                .build()
        }
        .crossfade(true)
        .build()
}

class MainActivity : ComponentActivity() {
    private val vm: TripViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot(vm, onExit = { finishAndRemoveTask() }) }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = listOfNotNull(intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT), intent.getStringExtra(android.content.Intent.EXTRA_TEXT)).joinToString("\n")
        if (text.isNotBlank()) vm.onSharedText(text)
    }

    // Coming back from Booking.com / Agoda: ask about the stay.
    override fun onStart() { super.onStart(); vm.onAppReturned() }
}

/** Holds the one trip being planned or travelled, and saves every change. */
class TripViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as TripApp).repo
    val remote = (app as TripApp).remote

    var trip by mutableStateOf(repo.loadTrip().also { repo.saveTrip(it) })
        private set

    init {
        val saved = repo.lang
        th.trip.planner.ui.I18n.set(app, saved.ifBlank { th.trip.planner.ui.I18n.deviceDefault() })
        ensureLegs()
    }

    /** True when the menus are in Thai (place names and descriptions then use Thai too). */
    val thai: Boolean get() = th.trip.planner.ui.I18n.thai

    fun setLanguage(code: String) {
        th.trip.planner.ui.I18n.set(getApplication(), code)
        repo.lang = th.trip.planner.ui.I18n.lang
    }

    fun update(f: (Trip) -> Trip) {
        trip = f(trip)
        repo.saveTrip(trip)
    }

    fun isSelected(placeId: String) = trip.stops.any { it.placeId == placeId }

    fun toggle(placeId: String) = update { t ->
        if (t.stops.any { it.placeId == placeId }) t.copy(stops = t.stops.filterNot { it.placeId == placeId })
        else t.copy(stops = t.stops + Stop(placeId))
    }

    fun move(index: Int, delta: Int) = update { t ->
        val j = index + delta
        if (j !in t.stops.indices) t else t.copy(stops = t.stops.toMutableList().apply { add(j, removeAt(index)) })
    }

    fun setDates(index: Int, dates: Set<LocalDate>) = update { t ->
        t.copy(stops = t.stops.toMutableList().apply { this[index] = this[index].copy(dates = dates.sorted().map { it.toString() }) })
    }

    fun setGapPurpose(dates: List<LocalDate>, purpose: GapPurpose) = update { t ->
        t.copy(gapPurposes = t.gapPurposes + dates.associate { it.toString() to purpose })
    }

    /** Keeps the legs list the same length as the number of travel legs. */
    fun ensureLegs() = update { t ->
        val n = TripLogic.legCount(t)
        // Older versions also had a leg from the start point and one to the end point.
        val old = if (t.legs.size == n + 2) t.legs.drop(1) else t.legs
        if (t.legs.size == n) t else t.copy(legs = List(n) { old.getOrNull(it) ?: Leg() })
    }

    fun setLeg(i: Int, leg: Leg) = update { t ->
        val legs = List(TripLogic.legCount(t)) { t.legs.getOrNull(it) ?: Leg() }.toMutableList()
        if (i in legs.indices) legs[i] = leg
        t.copy(legs = legs)
    }

    fun setLodging(placeId: String, lodging: Lodging) = update {
        it.copy(lodgings = if (lodging.isEmpty) it.lodgings - placeId else it.lodgings + (placeId to lodging))
    }

    /**
     * The stay form to show: after coming back from Booking.com / Agoda, after a booking was
     * shared into the app, or when the user taps "enter my own stay".
     */
    data class StayForm(val placeId: String, val prefill: Lodging, val afterBooking: Boolean)

    var stayForm by mutableStateOf<StayForm?>(null)

    /** The user is leaving for a booking site to book a stay for this place. */
    fun startBooking(placeId: String, source: String) {
        repo.pendingBooking = "$placeId|$source|${System.currentTimeMillis()}"
    }

    private fun pending(): Triple<String, String, Long>? {
        val parts = repo.pendingBooking.split('|')
        if (parts.size != 3) return null
        val at = parts[2].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > 2 * 24 * 3600_000L || repo.placeById[parts[0]] == null) { repo.pendingBooking = ""; return null }
        return Triple(parts[0], parts[1], at)
    }

    /** Back from the booking site: ask whether the booking went through and offer the form. */
    private fun checkBookingReturn() {
        val (placeId, source, at) = pending() ?: return
        if (System.currentTimeMillis() - at < 10_000L || stayForm != null) return
        val old = trip.lodgings[placeId] ?: Lodging()
        stayForm = StayForm(placeId, old.copy(source = source), afterBooking = true)
    }

    /** A confirmation shared from another app: fill the form for the place the user was booking. */
    fun onSharedText(text: String) {
        val p = pending()
        val placeId = p?.first ?: stayForm?.placeId
            ?: trip.stops.firstOrNull { trip.lodgings[it.placeId] == null }?.placeId
            ?: trip.stops.firstOrNull()?.placeId ?: return
        val base = stayForm?.prefill ?: trip.lodgings[placeId] ?: Lodging()
        stayForm = StayForm(placeId, fillFrom(base.copy(source = p?.second ?: sourceOf(text, base.source)), text), afterBooking = p != null)
    }

    fun fillFrom(l: Lodging, text: String): Lodging {
        val f = th.trip.planner.logic.BookingText.parse(text)
        return l.copy(
            name = f.name.ifBlank { l.name },
            address = f.address.ifBlank { l.address },
            phone = f.phone.ifBlank { l.phone },
            bookingRef = f.ref.ifBlank { l.bookingRef },
            url = f.url.ifBlank { l.url },
            source = l.source.ifBlank { sourceOf(text, "") },
        )
    }

    private fun sourceOf(text: String, fallback: String) = when {
        "agoda" in text.lowercase() -> "agoda"
        "booking.com" in text.lowercase() -> "booking.com"
        else -> fallback
    }

    /** Closes the form; [lodging] null = not booked / cancelled. */
    fun closeStayForm(placeId: String?, lodging: Lodging?) {
        if (stayForm?.afterBooking == true || lodging != null) repo.pendingBooking = ""
        if (placeId != null && lodging != null) setLodging(placeId, lodging)
        stayForm = null
    }

    fun activate() {
        update { it.copy(activated = true, finished = false) }
        CheckInScheduler.schedule(getApplication(), repo.checkInHour)
    }

    fun checkIn(onPlan: Boolean) = update { it.copy(checkIns = it.checkIns + (LocalDate.now().toString() to onPlan)) }

    /** Starts editing an activated trip; it must be confirmed and activated again. */
    fun startReplan() = update { it.copy(activated = false, warningsConfirmed = false) }

    fun finishTrip() {
        update { it.copy(finished = true, activated = false) }
        repo.archive(trip)
        CheckInScheduler.cancel(getApplication())
    }

    /** Removes a trip for good. The current trip can only go once it has finished. */
    fun deleteTrip(id: String): Boolean {
        if (id == trip.id) {
            if (!trip.finished) return false
            repo.tripDir(id).deleteRecursively()
            update { Trip(id = repo.newTripId(), createdAt = System.currentTimeMillis()) }
        } else repo.tripDir(id).deleteRecursively()
        tripsVersion++
        return true
    }

    /** Bumped when trips are deleted so lists reload. */
    var tripsVersion by mutableStateOf(0)

    fun newTrip() {
        // Keep the old trip in its own folder under "My trips".
        if (trip.stops.isNotEmpty()) repo.archive(trip)
        update { Trip(id = repo.newTripId(), createdAt = System.currentTimeMillis()) }
        CheckInScheduler.cancel(getApplication())
    }

    /** Back in the app: offer the stay form if the user was booking a stay. */
    fun onAppReturned() = checkBookingReturn()

    /** The current trip if [id] matches it, otherwise an archived trip. */
    fun tripById(id: String): Trip? = if (id == trip.id) trip else repo.archivedTrip(id)
}
