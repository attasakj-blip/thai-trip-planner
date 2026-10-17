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
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import th.trip.planner.data.Candidate
import th.trip.planner.data.Photos
import th.trip.planner.logic.TripStatus
import th.trip.planner.model.AlbumPhoto
import th.trip.planner.model.Slideshow
import th.trip.planner.model.MusicChoice
import th.trip.planner.audio.MusicGen
import th.trip.planner.logic.SlideTimeline
import th.trip.planner.video.FrameRenderer
import th.trip.planner.video.SectionContent
import th.trip.planner.video.VideoExporter
import kotlinx.coroutines.Job
import java.io.File
import th.trip.planner.model.Tempo
import th.trip.planner.ui.fmtDates
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
        CheckInScheduler.createChannel(this)
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
    }

    // The album collects photos taken while the user switched away to the camera app.
    override fun onStop() { super.onStop(); vm.onAppLeft() }
    override fun onStart() { super.onStart(); vm.onAppReturned() }
}

/** Holds the one trip being planned or travelled, and saves every change. */
class TripViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as TripApp).repo
    val remote = (app as TripApp).remote

    var trip by mutableStateOf(repo.loadTrip().also { repo.saveTrip(it) })
        private set

    /** New gallery photos waiting for the user to pick which go into the album. */
    var candidates by mutableStateOf<List<Candidate>>(emptyList())
        private set
    /** Set when photos were found but GPS was off, so they can't be accepted. */
    var gpsOffNotice by mutableStateOf(false)
    var thai by mutableStateOf(repo.thai)
        private set

    fun toggleLanguage() { thai = !thai; repo.thai = thai }

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
        if (t.legs.size == n) t else t.copy(legs = List(n) { t.legs.getOrNull(it) ?: Leg() })
    }

    fun setLeg(i: Int, leg: Leg) = update { t ->
        val legs = List(TripLogic.legCount(t)) { t.legs.getOrNull(it) ?: Leg() }.toMutableList()
        if (i in legs.indices) legs[i] = leg
        t.copy(legs = legs)
    }

    fun setLodging(placeId: String, lodging: Lodging) = update { it.copy(lodgings = it.lodgings + (placeId to lodging)) }

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

    fun newTrip() {
        // Keep the old trip (and its photos) in its own folder under "My trips".
        if (trip.stops.isNotEmpty() || trip.photos.isNotEmpty()) repo.archive(trip)
        update { Trip(id = repo.newTripId(), createdAt = System.currentTimeMillis()) }
        CheckInScheduler.cancel(getApplication())
    }

    // ------------------------------------------------------------ Album

    private fun collectingToday() = trip.activated && !trip.albumFinalized && trip.albumDays[LocalDate.now().toString()] == true

    fun answerAlbumToday(yes: Boolean) = update { it.copy(albumDays = it.albumDays + (LocalDate.now().toString() to yes)) }

    fun onAppLeft() { if (collectingToday()) repo.leftAt = System.currentTimeMillis() }

    fun onAppReturned() {
        val since = repo.leftAt
        if (since == 0L || !collectingToday()) return
        repo.leftAt = 0L
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Photos.newPhotos(ctx, since) }
                .filter { c -> trip.photos.none { it.takenAt == c.takenAt } }
            if (found.isEmpty()) return@launch
            if (!Photos.gpsOn(ctx)) { gpsOffNotice = true; return@launch }
            candidates = found
        }
    }

    fun dismissCandidates() { candidates = emptyList() }

    /** Copies the chosen photos into the trip folder, filed under the place where each was taken. */
    fun importPhotos(chosen: List<Candidate>) {
        val ctx = getApplication<Application>()
        val snapshot = trip
        candidates = emptyList()
        viewModelScope.launch {
            val here = withContext(Dispatchers.IO) { Photos.lastLocation(ctx) }
            val added = withContext(Dispatchers.IO) {
                chosen.mapNotNull { c ->
                    val (lat, lon) = Photos.exifLocation(ctx, c.uri) ?: (here?.let { it.latitude to it.longitude } ?: (null to null))
                    val date = java.time.Instant.ofEpochMilli(c.takenAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val placeId = placeFor(snapshot, date, lat, lon)
                    val rel = "photos/${placeId ?: "other"}/${date}_${c.takenAt}.jpg"
                    if (Photos.copyInto(ctx, c.uri, repo.photoFile(snapshot.id, rel))) AlbumPhoto(rel, placeId, date.toString(), c.takenAt, lat, lon) else null
                }
            }
            if (added.isNotEmpty()) update { it.copy(photos = (it.photos + added).sortedBy { p -> p.takenAt }) }
        }
    }

    /** The trip place a photo belongs to: the nearest place within 40 km, else the place planned for that day. */
    private fun placeFor(t: Trip, date: LocalDate, lat: Double?, lon: Double?): String? {
        if (lat != null && lon != null) {
            val near = t.stops.mapNotNull { repo.placeById[it.placeId] }
                .minByOrNull { TripLogic.distanceKm(lat, lon, it.lat, it.lon) }
            if (near != null && TripLogic.distanceKm(lat, lon, near.lat, near.lon) <= 40) return near.id
        }
        return when (val s = TripLogic.status(t, repo.placeById, date)) {
            is TripStatus.AtPlace -> t.stops[s.stopIndex].placeId
            is TripStatus.Travelling -> t.stops.getOrNull(s.legIndex)?.placeId
            is TripStatus.InGap -> t.stops.getOrNull(s.afterStop)?.placeId
            else -> null
        }
    }

    /** Deletes a photo from the current trip, or from an archived one when [tripId] is given. */
    fun deletePhoto(photo: AlbumPhoto, tripId: String = trip.id) {
        repo.photoFile(tripId, photo.file).delete()
        if (tripId == trip.id) update { it.copy(photos = it.photos - photo) }
        else repo.archivedTrip(tripId)?.let { t -> repo.archive(t.copy(photos = t.photos - photo)) }
    }

    fun finalizeAlbum(show: Slideshow) {
        update { it.copy(albumFinalized = true, slideshow = show) }
        repo.archive(trip)
    }

    // ------------------------------------------------------------ slideshow video

    /** Progress of the video being made: null = idle, 0..1 = working. */
    var exportProgress by mutableStateOf<Float?>(null)
        private set
    var exportError by mutableStateOf<String?>(null)
        private set
    var exportingTrip by mutableStateOf<String?>(null)
        private set
    private var exportJob: Job? = null

    fun videoFile(tripId: String): File = File(repo.tripDir(tripId), "slideshow.mp4")

    /** Builds the slideshow video: for each place a decorated title card, its photos, and its music looped to fit. */
    fun exportVideo(tripId: String) {
        if (exportJob?.isActive == true) return
        val t = tripById(tripId) ?: return
        val show = t.slideshow ?: return
        val isThai = thai
        exportError = null
        exportingTrip = tripId
        exportProgress = 0f
        exportJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val byPlace = t.photos.groupBy { it.placeId }
                val parts = show.sections.map { sec -> sec to byPlace[sec.placeId].orEmpty().sortedBy { it.takenAt } }
                val withPhotos = parts.count { it.second.isNotEmpty() }
                var n = 0
                val contents = parts.map { (sec, photos) ->
                    if (photos.isNotEmpty()) n++
                    val place = sec.placeId?.let { repo.placeById[it] }
                    SectionContent(
                        name = place?.name?.get(isThai) ?: if (isThai) "ระหว่างการเดินทาง" else "On the way",
                        subtitle = place?.let { if (!isThai) it.province.en else if (it.province.th.startsWith("กรุงเทพ")) it.province.th else "จังหวัด${it.province.th}" } ?: "",
                        dates = fmtDates(photos.map { LocalDate.parse(it.date) }.distinct(), isThai),
                        badge = if (isThai) "จุดหมายที่ $n จาก $withPhotos" else "STOP $n OF $withPhotos",
                        photos = photos.map { repo.photoFile(t.id, it.file) },
                    )
                }
                val timeline = SlideTimeline.build(show.sections.mapIndexed { i, sec ->
                    SlideTimeline.Section(contents[i].photos.size, sec.music?.tempo ?: Tempo.MEDIUM, sec.transition)
                })
                val pieces = HashMap<MusicChoice, ShortArray>()
                val pcm = if (show.sections.any { it.music != null }) timeline.soundtrack(MusicGen.RATE) { i ->
                    show.sections[i].music?.let { m -> pieces.getOrPut(m) { MusicGen.render(m) } }
                } else null
                val out = videoFile(t.id)
                VideoExporter.export(out, timeline, FrameRenderer(VideoExporter.WIDTH, VideoExporter.HEIGHT, contents), pcm, MusicGen.RATE) { p ->
                    exportProgress = p
                }
                VideoExporter.saveToGallery(getApplication(), out, "${t.id}.mp4")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                exportError = e.message ?: e.javaClass.simpleName
            } finally {
                exportProgress = null
                exportingTrip = null
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    /** The current trip if [id] matches it, otherwise an archived trip. */
    fun tripById(id: String): Trip? = if (id == trip.id) trip else repo.archivedTrip(id)
}
