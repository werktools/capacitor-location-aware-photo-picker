package com.locationawarephotopicker.plugin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.getcapacitor.FileUtils
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android-only Capacitor plugin that picks photos from the gallery via `ACTION_GET_CONTENT`
 * (through AndroidX's `GetMultipleContents` contract) instead of the system Photo Picker, so that
 * original GPS EXIF data can be recovered where possible.
 *
 * This picker choice - and the whole "why does my picked photo have no GPS" premise - is backed by
 * empirical testing in [warting/exif_picker_lab](https://github.com/warting/exif_picker_lab), a
 * reference app that measures which picker/EXIF-read-method combinations actually preserve GPS on
 * real devices. Their findings (see this plugin's README for the full table): `PickVisualMedia`
 * (the modern Photo Picker) strips GPS with no recovery path at all; `OpenDocument` (Storage Access
 * Framework) preserves EXIF but needs an API-31+ URI-conversion step to use `setRequireOriginal`;
 * `GetContent` preserves EXIF *and* supports `setRequireOriginal` directly, with no conversion step
 * needed, on their tested device. `GetMultipleContents` is the multi-select variant of the same
 * `ACTION_GET_CONTENT` mechanism.
 *
 * IMPORTANT: the exif_picker_lab README is explicit that its findings are *empirical, per-device*
 * measurements, not a documented Android platform guarantee the way `ACCESS_MEDIA_LOCATION` +
 * `setRequireOriginal` against a classic MediaStore URI is. Behaviour may differ across OEMs and
 * Android versions. This plugin is written to work whether or not GPS survives the initial read:
 * it re-attempts recovery via `setRequireOriginal` regardless, as defense in depth - see
 * `recoverGPSIfPossible` below.
 *
 * See this plugin's README for the full explanation of why this exists, what it costs in picker
 * UX, and the exact cases where GPS recovery is (and isn't) possible.
 */
@CapacitorPlugin(
    name = "LocationAwarePhotoPicker",
    permissions = [
        Permission(strings = [Manifest.permission.ACCESS_MEDIA_LOCATION], alias = LocationAwarePhotoPickerPlugin.MEDIA_LOCATION)
    ]
)
class LocationAwarePhotoPickerPlugin : Plugin() {

    companion object {
        const val MEDIA_LOCATION = "mediaLocation"
        private const val JPEG_QUALITY_DEFAULT = 100
    }

    private lateinit var pickerLauncher: ActivityResultLauncher<String>
    // Fire-and-forget, best-effort, never blocks or fails chooseFromGallery - state is re-checked
    // lazily in recoverGPSIfPossible whenever it's actually needed. Mirrors the exact pattern used
    // for this same permission in capacitor-camera's own IonCameraFlow.
    private lateinit var mediaLocationPermissionLauncher: ActivityResultLauncher<String>

    private var currentCall: PluginCall? = null
    private var localFileCounter = 0

    override fun load() {
        // GetMultipleContents wraps ACTION_GET_CONTENT with EXTRA_ALLOW_MULTIPLE=true - the
        // mechanism exif_picker_lab found preserves EXIF GPS. It's marked @Deprecated in AndroidX
        // in favor of PickMultipleVisualMedia (the modern Photo Picker's multi-select contract) -
        // that deprecation is precisely why this plugin exists: PickMultipleVisualMedia routes
        // through the Photo Picker, which strips GPS with no recovery path at all. Do not "clean
        // up" this deprecation warning by swapping to the suggested replacement.
        @Suppress("DEPRECATION")
        pickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris -> handlePickerResult(uris) }

        mediaLocationPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // No-op - see comment on the property declaration above.
        }
    }

    @PluginMethod
    fun chooseFromGallery(call: PluginCall) {
        currentCall = call

        val includeMetadata = call.getBoolean("includeMetadata") ?: false

        // Best-effort, non-blocking, in parallel with the picker itself - exactly mirrors
        // capacitor-camera's own openGallery. Only worth asking when metadata (and therefore GPS)
        // was actually requested. Unlike the OpenDocument/SAF variant of this plugin, no API-31
        // gate is needed here: GetContent's URI supports setRequireOriginal directly (per
        // exif_picker_lab's findings), the same way a classic MediaStore URI does, from API 29.
        if (includeMetadata &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

        try {
            pickerLauncher.launch("image/*")
        } catch (e: Exception) {
            call.reject("Unable to open the photo picker.", "PICKER_UNAVAILABLE", e)
            currentCall = null
        }
    }

    private fun handlePickerResult(uris: List<Uri>) {
        val call = currentCall ?: return
        currentCall = null

        if (uris.isEmpty()) {
            call.reject("No photo was selected.", "NO_SELECTION")
            return
        }

        val limit = call.getInt("limit") ?: 0
        val selectedUris = if (limit > 0) uris.take(limit) else uris

        val quality = call.getInt("quality") ?: JPEG_QUALITY_DEFAULT
        val targetWidth = call.getInt("targetWidth") ?: 0
        val targetHeight = call.getInt("targetHeight") ?: 0
        val correctOrientation = call.getBoolean("correctOrientation") ?: true
        val includeMetadata = call.getBoolean("includeMetadata") ?: false

        val results = JSArray()
        for (uri in selectedUris) {
            try {
                results.put(
                    processUri(uri, quality, targetWidth, targetHeight, correctOrientation, includeMetadata)
                )
            } catch (e: Exception) {
                // Best-effort per photo: one bad/unreadable selection shouldn't fail the whole
                // batch when the user picked several. Silently skipped from the results array.
            }
        }

        if (results.length() == 0) {
            call.reject("None of the selected photos could be processed.", "PROCESS_IMAGE_ERROR")
            return
        }

        val ret = JSObject()
        ret.put("results", results)
        call.resolve(ret)
    }

    /**
     * Copies the picked photo into this app's own storage, applies resize/quality/orientation
     * processing if requested, recovers GPS EXIF where possible, and builds the JS-facing result.
     */
    @Throws(IOException::class)
    private fun processUri(
        pickedUri: Uri,
        quality: Int,
        targetWidth: Int,
        targetHeight: Int,
        correctOrientation: Boolean,
        includeMetadata: Boolean
    ): JSObject {
        val localFile = File(context.cacheDir, "OPP_${System.currentTimeMillis()}_${localFileCounter++}.jpg")

        context.contentResolver.openInputStream(pickedUri)?.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        } ?: throw IOException("Unable to open the selected photo")

        val needsReencode = quality < JPEG_QUALITY_DEFAULT || targetWidth > 0 || targetHeight > 0 || correctOrientation

        if (needsReencode) {
            reencode(localFile, quality, targetWidth, targetHeight, correctOrientation)
        }

        // GPS recovery happens after any reencoding above, so it's the last thing written - a
        // resize/orientation pass rewrites the whole file and would otherwise wipe out recovered
        // GPS tags along with everything else non-GPS-related.
        if (includeMetadata) {
            recoverGPSIfPossible(pickedUri, localFile)
        }

        val fileUri = Uri.fromFile(localFile)
        val webPath = FileUtils.getPortablePath(context, bridge.localUrl, fileUri)

        val ret = JSObject()
        ret.put("type", "picture")
        ret.put("uri", fileUri.toString())
        ret.put("webPath", webPath)

        if (includeMetadata) {
            val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
            val metadata = JSObject()
            metadata.put("size", localFile.length().toString())
            metadata.put("format", "jpeg")

            if (bitmap != null) {
                metadata.put("resolution", "${bitmap.width}x${bitmap.height}")
                val exif = ImageUtils.getExifData(context, bitmap, fileUri)
                metadata.put("exif", exif.toJson())
            } else {
                metadata.put("exif", JSObject())
            }

            ret.put("metadata", metadata)
        }

        return ret
    }

    /**
     * Re-encodes localFile as JPEG, applying resize (preserving aspect ratio) and/or orientation
     * correction and/or quality compression as requested. Re-encoding via Bitmap strips all EXIF,
     * so the original file's non-GPS EXIF is copied back onto the result afterward via
     * ExifWrapper.copyExif - matching exactly how capacitor-camera's own IonCameraFlow/ImageUtils
     * handle this same trade-off for takePhoto.
     */
    private fun reencode(
        localFile: File,
        quality: Int,
        targetWidth: Int,
        targetHeight: Int,
        correctOrientation: Boolean
    ) {
        var bitmap = BitmapFactory.decodeFile(localFile.absolutePath) ?: return

        val originalExif = ImageUtils.getExifData(context, bitmap, Uri.fromFile(localFile))

        if (targetWidth > 0 || targetHeight > 0) {
            bitmap = ImageUtils.resize(bitmap, targetWidth, targetHeight)
        }

        if (correctOrientation) {
            try {
                bitmap = ImageUtils.correctOrientation(context, bitmap, Uri.fromFile(localFile), originalExif)
            } catch (_: IOException) {
                // Best-effort - keep the un-rotated bitmap rather than failing the whole selection.
            }
        }

        FileOutputStream(localFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }

        // Restore non-GPS EXIF (camera make/model, date taken, etc.) that Bitmap re-encoding just
        // stripped. GPS itself is handled separately, afterward, by recoverGPSIfPossible - see the
        // ordering note in processUri.
        originalExif.copyExif(localFile.absolutePath)
    }

    /**
     * Recovers GPS EXIF data for a photo picked via `ACTION_GET_CONTENT`.
     *
     * Per [exif_picker_lab](https://github.com/warting/exif_picker_lab)'s empirical testing,
     * `ACTION_GET_CONTENT` now routes through a picker variant whose URI is treated as
     * MediaStore-equivalent: plain `openInputStream`/`openFileDescriptor` reads (what
     * `processUri`'s initial copy and `ImageUtils.getExifData` both already do) preserve GPS EXIF
     * directly, with no special handling needed - unlike the system Photo Picker (which strips GPS
     * unconditionally, no recovery possible) or SAF's `ACTION_OPEN_DOCUMENT` (which preserves EXIF
     * too, but returns a different provider's URI that needs an extra conversion step before
     * `setRequireOriginal` can be used).
     *
     * Their findings also show `setRequireOriginal` succeeding directly against this same
     * `ACTION_GET_CONTENT` URI, with no conversion step - so this method still attempts it, as
     * defense in depth. This is deliberately redundant on their tested configuration (GPS should
     * already be present by the time this runs) but cheap, and the exif_picker_lab README is
     * explicit that its findings are empirical, per-device measurements, not a documented Android
     * platform guarantee - so this fallback is what actually makes GPS recovery robust across
     * devices/OS versions that might behave differently than their specific test device.
     *
     * Entirely best-effort: permission not granted, no GPS on the original, or any failure along
     * the way all silently leave the photo without GPS - never turns a successful selection into a
     * failed one.
     */
    private fun recoverGPSIfPossible(pickedUri: Uri, localFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // setRequireOriginal requires API 29+

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val unredactedUri = MediaStore.setRequireOriginal(pickedUri)

            context.contentResolver.openInputStream(unredactedUri)?.use { stream ->
                val sourceExif = ExifInterface(stream)
                val latLong = sourceExif.latLong
                if (latLong == null || GpsUtils.isNullIsland(latLong)) return // no real GPS data to recover

                ExifWrapper(sourceExif).copyGpsExif(localFile.absolutePath)
            }
        } catch (_: Exception) {
            // Best-effort - see doc comment above. In particular, some devices/OS versions may
            // throw here (e.g. if this URI type isn't recognised as MediaStore-equivalent the way
            // exif_picker_lab found on their test device) - that's fine, since the initial plain
            // read earlier in processUri already had the best chance at recovering GPS anyway.
        }
    }
}
