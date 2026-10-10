package com.wtifs.photos

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class PhotoMetadata(val location: String = "", val details: String = "No EXIF information")

fun readPhotoMetadata(context: Context, asset: PhotoAsset): PhotoMetadata {
    val original = if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED)
        MediaStore.setRequireOriginal(asset.uri) else asset.uri
    return runCatching {
        val exif = context.contentResolver.openInputStream(original)!!.use { ExifInterface(it) }
        val gps = exif.latLong
        @Suppress("DEPRECATION")
        val address = gps?.let { runCatching { Geocoder(context).getFromLocation(it[0], it[1], 1)?.firstOrNull() }.getOrNull() }
        val fullLocation = address?.let {
            listOfNotNull(it.subLocality, it.locality, it.countryName).distinct().joinToString(", ")
        }?.takeIf { it.isNotBlank() } ?: gps?.let { String.format(Locale.US, "%.4f, %.4f", it[0], it[1]) } ?: ""
        val location = address?.let {
            listOf(it.subLocality, it.locality, it.subAdminArea, it.adminArea, it.countryName)
                .firstOrNull { name -> !name.isNullOrBlank() }
        } ?: fullLocation
        val tags = listOf(
            "Camera" to ExifInterface.TAG_MODEL,
            "Lens" to ExifInterface.TAG_LENS_MODEL,
            "Width" to ExifInterface.TAG_IMAGE_WIDTH,
            "Height" to ExifInterface.TAG_IMAGE_LENGTH,
            "Aperture" to ExifInterface.TAG_F_NUMBER,
            "Exposure (s)" to ExifInterface.TAG_EXPOSURE_TIME,
            "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            "Focal length" to ExifInterface.TAG_FOCAL_LENGTH,
        )
        PhotoMetadata(location, (listOf(asset.displayName) + tags.mapNotNull { (label, tag) ->
            exif.getAttribute(tag)?.let { "$label: $it" }
        } + listOfNotNull(fullLocation.takeIf { it.isNotBlank() })).joinToString("\n"))
    }.getOrElse { PhotoMetadata(details = "Metadata unavailable for this photo.") }
}

/** Prepare and validate a replacement before opening the original for writing. */
fun rotatePhotoLeft(context: Context, asset: PhotoAsset) {
    require(ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        "Allow photo location access in Android settings before rotating, so the original GPS metadata can be preserved."
    }
    val backup = File.createTempFile("rotation-original-", ".bak", context.cacheDir)
    val edited = File.createTempFile("rotation-edited-", ".img", context.cacheDir)
    val originalUri = MediaStore.setRequireOriginal(asset.uri)
    context.contentResolver.openInputStream(originalUri)!!.use { input -> backup.outputStream().use { input.copyTo(it) } }
    backup.copyTo(edited, overwrite = true)
    try {
        val exif = ExifInterface(edited)
        exif.rotate(-90)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        // Patching an existing JPEG orientation leaves every MP4 byte and offset intact.
        if (!patchJpegOrientation(edited, orientation)) {
            require(!asset.mayContainLiveClip) { "This live photo has no editable orientation tag. Rotation was not saved." }
            exif.saveAttributes()
        }
        check(ExifInterface(edited).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == orientation)
        try {
            context.contentResolver.openOutputStream(asset.uri, "wt")!!.use { output -> edited.inputStream().use { it.copyTo(output) } }
        } catch (failure: Exception) {
            runCatching {
                context.contentResolver.openOutputStream(asset.uri, "wt")!!.use { output -> backup.inputStream().use { it.copyTo(output) } }
            }.onFailure { throw java.io.IOException("Could not restore original; backup retained at ${backup.absolutePath}", failure) }
            throw failure
        }
        backup.delete()
    } finally {
        edited.delete()
    }
}

internal fun patchJpegOrientation(file: File, orientation: Int): Boolean {
    RandomAccessFile(file, "rw").use { input ->
        if (input.readUnsignedShort() != 0xffd8) return false
        while (input.filePointer < input.length()) {
            val marker = input.readUnsignedShort()
            if (marker == 0xffda || marker == 0xffd9) return false
            val length = input.readUnsignedShort()
            if (length < 2) return false
            val start = input.filePointer
            if (marker == 0xffe1) {
                val bytes = ByteArray(length - 2)
                input.readFully(bytes)
                if (bytes.size > 14 && bytes.copyOfRange(0, 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                    val order = if (bytes[6] == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    val buffer = ByteBuffer.wrap(bytes).order(order)
                    val directory = 6L + buffer.getInt(10).toLong()
                    if (directory < 14 || directory + 2 > bytes.size) return false
                    val count = buffer.getShort(directory.toInt()).toInt() and 65535
                    repeat(count) { index ->
                        val entry = directory.toInt() + 2 + index * 12
                        if (entry + 12 > bytes.size) return false
                        if ((buffer.getShort(entry).toInt() and 65535) == 274 && buffer.getShort(entry + 2).toInt() == 3 && buffer.getInt(entry + 4) == 1) {
                            input.seek(start + entry + 8)
                            input.write(ByteBuffer.allocate(2).order(order).putShort(orientation.toShort()).array())
                            return true
                        }
                    }
                }
            }
            input.seek(start + length - 2)
        }
    }
    return false
}
