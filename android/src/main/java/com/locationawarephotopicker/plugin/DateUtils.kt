package com.locationawarephotopicker.plugin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure date-formatting helpers with no Android framework dependency - kept in their own file
 * specifically so they're directly unit-testable with plain JUnit, no Robolectric or
 * Activity/Plugin test harness required. See `DateUtilsTest`.
 */
internal object DateUtils {

    private const val EXIF_DATETIME_PATTERN = "yyyy:MM:dd HH:mm:ss"
    private const val ISO_LOCAL_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private const val ISO_UTC_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    // EXIF's OffsetTime/OffsetTimeOriginal/OffsetTimeDigitized tags are always "±HH:MM" (EXIF
    // 2.31+), e.g. "+01:00" or "-04:00" - never a bare "Z" or any other shorthand.
    private val OFFSET_PATTERN = Regex("^[+-]\\d{2}:\\d{2}$")

    /**
     * Converts an EXIF `DateTimeOriginal`/`DateTime` tag value (`"yyyy:MM:dd HH:mm:ss"`, per the
     * EXIF spec) into a timezone-*unqualified* ISO 8601 string - use this only when no paired
     * offset tag is available; prefer [exifDateTimeWithOffsetToIso] when one is.
     *
     * Deliberately has no trailing `Z` or offset: EXIF's `DateTimeOriginal`/`DateTime` alone is the
     * camera's local wall-clock time with no timezone information attached. Asserting UTC here
     * would simply be wrong for any camera not physically in that timezone, so the caller gets an
     * honest "local time, zone unknown" string instead of a falsely-precise one.
     *
     * Returns `null` if `exifDateTime` isn't in the expected format, rather than throwing.
     */
    fun exifDateTimeToIso(exifDateTime: String): String? {
        return try {
            val parsed = SimpleDateFormat(EXIF_DATETIME_PATTERN, Locale.US).parse(exifDateTime) ?: return null
            SimpleDateFormat(ISO_LOCAL_PATTERN, Locale.US).format(parsed)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Combines an EXIF `DateTimeOriginal`/`DateTime` value with its *paired* `OffsetTimeOriginal`/
     * `OffsetTime` value (EXIF 2.31+ - not populated by every camera, but increasingly common) into
     * a genuinely timezone-aware ISO 8601 string.
     *
     * EXIF already stores both halves in directly ISO-8601-compatible form - local wall-clock time,
     * and a "±HH:MM" offset - so this is reformatting plus validated concatenation, not real
     * timezone arithmetic: `"2026:01:15 22:13:20"` + `"+01:00"` -> `"2026-01-15T22:13:20+01:00"`.
     *
     * Callers must pass the *matching* pair - `DateTimeOriginal` with `OffsetTimeOriginal`, or
     * `DateTime` with `OffsetTime`, never mixed, since a photo's capture and last-modified instants
     * could genuinely have been recorded in different timezones (e.g. edited after a flight).
     *
     * Returns `null` - rather than guessing or throwing - if the date isn't in the expected format,
     * or `offset` isn't in the expected `"±HH:MM"` shape; callers should fall back to
     * [exifDateTimeToIso] (timezone-unqualified) in that case.
     */
    fun exifDateTimeWithOffsetToIso(exifDateTime: String, offset: String): String? {
        if (!OFFSET_PATTERN.matches(offset)) return null
        val localIso = exifDateTimeToIso(exifDateTime) ?: return null
        return localIso + offset
    }

    /**
     * Converts a genuine UTC epoch-milliseconds timestamp (e.g. MediaStore's `DATE_TAKEN` column)
     * into an ISO 8601 UTC string, with a trailing `Z`. Unlike [exifDateTimeToIso], this source
     * *is* an unambiguous UTC instant, so asserting `Z` here is accurate.
     */
    fun epochMillisToUtcIso(millis: Long): String {
        val formatter = SimpleDateFormat(ISO_UTC_PATTERN, Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(millis))
    }
}
