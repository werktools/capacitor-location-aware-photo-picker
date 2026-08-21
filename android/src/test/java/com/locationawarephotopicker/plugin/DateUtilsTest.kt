package com.locationawarephotopicker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit test - DateUtils has zero Android framework dependency, so this needs no
 * Robolectric, no Activity, no Capacitor Bridge/Plugin instance. Run with `./gradlew test`.
 */
class DateUtilsTest {

    @Test
    fun exifDateTimeToIso_parsesValidExifDateTime() {
        assertEquals("2026-01-15T22:13:20", DateUtils.exifDateTimeToIso("2026:01:15 22:13:20"))
    }

    @Test
    fun exifDateTimeToIso_hasNoTrailingZOrOffset() {
        // Deliberate: EXIF DateTimeOriginal carries no timezone information, so the result must
        // not claim UTC (or any other zone) - see exifDateTimeToIso's doc comment.
        val result = DateUtils.exifDateTimeToIso("2026:01:15 22:13:20")
        assertEquals(false, result?.endsWith("Z"))
        assertEquals(false, result?.contains("+"))
    }

    @Test
    fun exifDateTimeToIso_onMalformedInput_returnsNullRatherThanThrowing() {
        assertNull(DateUtils.exifDateTimeToIso("not a date"))
        assertNull(DateUtils.exifDateTimeToIso(""))
        assertNull(DateUtils.exifDateTimeToIso("2026-01-15 22:13:20")) // wrong separator (- not :)
    }

    @Test
    fun exifDateTimeWithOffsetToIso_combinesPositiveOffsetCorrectly() {
        assertEquals(
            "2026-01-15T22:13:20+01:00",
            DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", "+01:00")
        )
    }

    @Test
    fun exifDateTimeWithOffsetToIso_combinesNegativeOffsetCorrectly() {
        assertEquals(
            "2026-03-29T11:52:09-04:00",
            DateUtils.exifDateTimeWithOffsetToIso("2026:03:29 11:52:09", "-04:00")
        )
    }

    @Test
    fun exifDateTimeWithOffsetToIso_passesThroughZeroOffsetAsIs() {
        // "+00:00" is left as-is rather than simplified to "Z" - both are valid ISO 8601, and EXIF
        // never writes a bare "Z" for these tags in the first place.
        assertEquals(
            "2026-01-15T22:13:20+00:00",
            DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", "+00:00")
        )
    }

    @Test
    fun exifDateTimeWithOffsetToIso_onMalformedOffset_returnsNull() {
        assertNull(DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", "PST"))
        assertNull(DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", "+1:00")) // missing leading zero
        assertNull(DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", ""))
        assertNull(DateUtils.exifDateTimeWithOffsetToIso("2026:01:15 22:13:20", "Z"))
    }

    @Test
    fun exifDateTimeWithOffsetToIso_onMalformedDate_returnsNullRegardlessOfValidOffset() {
        assertNull(DateUtils.exifDateTimeWithOffsetToIso("not a date", "+01:00"))
    }

    @Test
    fun epochMillisToUtcIso_formatsKnownTimestampCorrectly() {
        // 1700000000000ms = 2023-11-14T22:13:20Z (a commonly-cited reference epoch value).
        assertEquals("2023-11-14T22:13:20Z", DateUtils.epochMillisToUtcIso(1_700_000_000_000L))
    }

    @Test
    fun epochMillisToUtcIso_hasTrailingZ() {
        // Deliberate, opposite of exifDateTimeToIso: this source IS a genuine UTC instant.
        assertEquals(true, DateUtils.epochMillisToUtcIso(1_700_000_000_000L).endsWith("Z"))
    }

    @Test
    fun epochMillisToUtcIso_handlesEpochZero() {
        assertEquals("1970-01-01T00:00:00Z", DateUtils.epochMillisToUtcIso(0L))
    }
}
