package th.trip.planner.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * A photo in the phone's gallery that might go into the trip album. [placeId] is the planned
 * place within reach of where it was taken; null means it can't be added (too far, or no location).
 */
data class Candidate(
    val uri: Uri,
    val takenAt: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val placeId: String? = null,
    /** Distance to the nearest planned place, when the photo's location is known. */
    val nearestKm: Double? = null,
    val nearestPlaceId: String? = null,
) {
    val allowed: Boolean get() = placeId != null
}

object Photos {

    /** Permissions needed to see new photos in the gallery and read where they were taken. */
    fun permissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES) else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    fun canReadGallery(ctx: Context): Boolean {
        val granted = { p: String -> ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED }
        return when {
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun gpsOn(ctx: Context): Boolean = LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java))

    /** Photos added to the gallery since [sinceMs] (newest first). */
    fun newPhotos(ctx: Context, sinceMs: Long): List<Candidate> {
        if (!canReadGallery(ctx)) return emptyList()
        val out = mutableListOf<Candidate>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        val sel = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(((sinceMs / 1000) - 5).toString())
        runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = if (c.isNull(takenCol)) c.getLong(addedCol) * 1000 else c.getLong(takenCol)
                    out += Candidate(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), taken)
                }
            }
        }
        return out
    }

    /** Where the photo was taken, from its EXIF data (needs ACCESS_MEDIA_LOCATION on Android 10+). */
    fun exifLocation(ctx: Context, uri: Uri): Pair<Double, Double>? = runCatching {
        val original = if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) MediaStore.setRequireOriginal(uri) else uri
        ctx.contentResolver.openInputStream(original)?.use { s -> ExifInterface(s).latLong?.let { it[0] to it[1] } }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun lastLocation(ctx: Context): Location? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Copies a gallery photo into the trip folder so the album survives gallery clean-ups. */
    fun copyInto(ctx: Context, uri: Uri, dest: File): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } != null
    }.getOrDefault(false)
}
