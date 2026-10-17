package th.trip.planner.model

import kotlinx.serialization.Serializable

/** Text in Thai and English. */
@Serializable
data class Txt(val th: String, val en: String) {
    fun get(thai: Boolean) = if (thai) th else en
}

enum class Region { NORTH, NORTHEAST, CENTRAL, BANGKOK, EAST, WEST, SOUTH_ANDAMAN, SOUTH_GULF }

/** A festival or a season that makes a place worth visiting at a certain time. */
@Serializable
data class Highlight(
    val name: Txt,
    /** Months 1..12 when it usually happens. */
    val months: List<Int>,
    val reason: Txt,
)

/** A known closure, e.g. a national park closed in the rainy season. Months 1..12. */
@Serializable
data class Closure(val months: List<Int>, val note: Txt)

@Serializable
data class Place(
    val id: String,
    val name: Txt,
    val province: Txt,
    val region: Region,
    val lat: Double,
    val lon: Double,
    val description: Txt,
    /** English Wikipedia article titles used to fetch freely licensed photos. */
    val wikiTitles: List<String> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val closures: List<Closure> = emptyList(),
    val island: Boolean = false,
    /** Ids of transport hubs that serve this place. */
    val hubIds: List<String> = emptyList(),
)

enum class HubType { AIRPORT, BUS, TRAIN, PIER }

@Serializable
data class Hub(
    val id: String,
    val name: Txt,
    val type: HubType,
    val province: Txt,
    val lat: Double,
    val lon: Double,
    val phone: String? = null,
    val website: String? = null,
)

enum class TravelMode { FLIGHT, TRAIN, BUS, BOAT, CAR }

enum class GapPurpose { WORK, VISIT, TRAVEL, OTHER }

/** One place in the trip with the calendar dates (ISO yyyy-MM-dd) spent there. Dates may be non-contiguous. */
@Serializable
data class Stop(val placeId: String, val dates: List<String> = emptyList())

/** A travel leg. index 0 = origin -> first stop, i = stop i-1 -> stop i, last = last stop -> end hub. */
@Serializable
data class Leg(
    val mode: TravelMode? = null,
    val date: String? = null,
    val note: String = "",
)

@Serializable
data class Lodging(val name: String = "", val url: String = "", val note: String = "")

/** A photo the traveller added to the trip album. [file] is relative to the trip folder. */
@Serializable
data class AlbumPhoto(
    val file: String,
    val placeId: String? = null,
    /** ISO date the photo was taken. */
    val date: String,
    val takenAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
)

enum class MusicStyle { THAI_LOCAL, MUSIC_BOX, HARP, JAZZ, SAMBA, FLUTE }

enum class Tempo { SLOW, MEDIUM, FAST }

enum class Transition { MORPH, CROSSFADE_ZOOM, SLIDE }

@Serializable
data class MusicChoice(val style: MusicStyle, val tempo: Tempo, val variant: Int)

/** One place's part of the slideshow. [music] null = no music. */
@Serializable
data class SlideSection(val placeId: String?, val music: MusicChoice? = null, val transition: Transition = Transition.MORPH)

@Serializable
data class Slideshow(val sections: List<SlideSection>, val createdAt: Long)

@Serializable
data class Trip(
    /** Folder name for this trip's photos and slideshow. */
    val id: String = "",
    val createdAt: Long = 0,
    /** Stops in the visiting order chosen by the user. */
    val stops: List<Stop> = emptyList(),
    /** Purpose of each date that falls in a gap between visiting days. key = ISO date. */
    val gapPurposes: Map<String, GapPurpose> = emptyMap(),
    val warningsConfirmed: Boolean = false,
    val lodgings: Map<String, Lodging> = emptyMap(),
    val originCity: String = "",
    val originCountry: String = "Thailand",
    val originHubId: String? = null,
    val endHubId: String? = null,
    val legs: List<Leg> = emptyList(),
    val activated: Boolean = false,
    val finished: Boolean = false,
    /** Daily check-in answers. key = ISO date, value = on plan? */
    val checkIns: Map<String, Boolean> = emptyMap(),
    /** Daily answer to "collect photos for the album today?". key = ISO date. */
    val albumDays: Map<String, Boolean> = emptyMap(),
    val photos: List<AlbumPhoto> = emptyList(),
    /** True once the album is complete: no more imports. */
    val albumFinalized: Boolean = false,
    val slideshow: Slideshow? = null,
)
