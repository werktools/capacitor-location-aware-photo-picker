package com.locationawarephotopicker.plugin

import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

/**
 * Exercises ExifWrapper.copyGpsExif/toJson against real ExifInterface file I/O via Robolectric.
 * These need a real (if minimal) JPEG file on disk - ExifInterface.saveAttributes() requires a
 * genuinely parseable image container to write into, not an arbitrary byte array. Run with
 * `./gradlew test` (Robolectric runs on the JVM - no emulator or device needed).
 */
@RunWith(RobolectricTestRunner::class)
class ExifWrapperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // A real, minimal, valid 4x4 JPEG (generated with Pillow: Image.new('RGB', (4, 4)).save(...,
    // format='JPEG')), base64-encoded. Any valid JPEG works here; this one is deliberately tiny to
    // keep the fixture readable inline and the test fast.
    private val minimalJpegBase64 =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUV" +
            "DA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBT/wAARCAAEAAQDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA" +
            "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZ" +
            "WmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl" +
            "5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEE" +
            "BSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hp" +
            "anN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP0" +
            "9fb3+Pn6/9oADAMBAAIRAxEAPwD50ooor8MP9Uz/2Q=="

    private fun writeFixtureJpeg(name: String): File {
        val file = tempFolder.newFile(name)
        val bytes = Base64.decode(minimalJpegBase64, Base64.DEFAULT)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    @Test
    fun copyGpsExif_copiesLatitudeAndLongitude() {
        val source = writeFixtureJpeg("source1.jpg")
        val sourceExif = ExifInterface(source.absolutePath)
        sourceExif.setLatLong(37.7749, -122.4194)
        sourceExif.saveAttributes()

        val dest = writeFixtureJpeg("dest1.jpg")

        ExifWrapper(ExifInterface(source.absolutePath)).copyGpsExif(dest.absolutePath)

        val destLatLong = ExifInterface(dest.absolutePath).latLong
        assertEquals(37.7749, destLatLong!![0], 0.0001)
        assertEquals(-122.4194, destLatLong[1], 0.0001)
    }

    @Test
    fun copyGpsExif_copiesTheWholeGpsDictionary_notJustLatLong() {
        // Regression test: an earlier version of this recovery logic only recovered lat/long.
        // copyGpsExif is specifically meant to carry over the whole GPS IFD.
        val source = writeFixtureJpeg("source2.jpg")
        val sourceExif = ExifInterface(source.absolutePath)
        sourceExif.setLatLong(51.5074, -0.1278)
        sourceExif.setAltitude(35.0)
        sourceExif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:01:15")
        sourceExif.saveAttributes()

        val dest = writeFixtureJpeg("dest2.jpg")

        ExifWrapper(ExifInterface(source.absolutePath)).copyGpsExif(dest.absolutePath)

        val destExif = ExifInterface(dest.absolutePath)
        assertEquals("2026:01:15", destExif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        assertEquals(35.0, destExif.getAltitude(-1.0), 0.1)
    }

    @Test
    fun copyGpsExif_doesNotTouchNonGpsTags() {
        // Regression test: copyGpsExif must stay scoped to GPS tags only. A destination file's
        // own, legitimately-different non-GPS metadata (e.g. after a resize) must survive.
        val source = writeFixtureJpeg("source3.jpg")
        val sourceExif = ExifInterface(source.absolutePath)
        sourceExif.setLatLong(10.0, 20.0)
        sourceExif.setAttribute(ExifInterface.TAG_MAKE, "SourceCameraMake")
        sourceExif.saveAttributes()

        val dest = writeFixtureJpeg("dest3.jpg")
        val destExifBefore = ExifInterface(dest.absolutePath)
        destExifBefore.setAttribute(ExifInterface.TAG_MAKE, "DestCameraMake")
        destExifBefore.saveAttributes()

        ExifWrapper(ExifInterface(source.absolutePath)).copyGpsExif(dest.absolutePath)

        val destExifAfter = ExifInterface(dest.absolutePath)
        // GPS was copied from the source...
        assertEquals(10.0, destExifAfter.latLong!![0], 0.0001)
        // ...but the destination's own, different, pre-existing non-GPS tag was left alone.
        assertEquals("DestCameraMake", destExifAfter.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun toJson_includesGpsRefsForSouthernAndEasternHemisphere() {
        val file = writeFixtureJpeg("sydney.jpg")
        val exif = ExifInterface(file.absolutePath)
        exif.setLatLong(-33.8688, 151.2093) // Sydney
        exif.saveAttributes()

        val json = ExifWrapper(ExifInterface(file.absolutePath)).toJson()

        assertEquals("S", json.getString(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertEquals("E", json.getString(ExifInterface.TAG_GPS_LONGITUDE_REF))
    }

    @Test
    fun toJson_onNullExif_returnsEmptyObjectRatherThanCrashing() {
        val json = ExifWrapper(null).toJson()
        assertEquals(0, json.length())
    }

    @Test
    fun copyGpsExif_onFileWithNoGps_leavesDestinationUnchanged() {
        val source = writeFixtureJpeg("nogps.jpg")
        // No setLatLong call - source genuinely has no GPS tags.

        val dest = writeFixtureJpeg("dest4.jpg")

        ExifWrapper(ExifInterface(source.absolutePath)).copyGpsExif(dest.absolutePath)

        assertNull(ExifInterface(dest.absolutePath).latLong)
    }
}
