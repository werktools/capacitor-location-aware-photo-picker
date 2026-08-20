package com.locationawarephotopicker.plugin

/**
 * Small, pure GPS-related helpers with no Android framework or Capacitor dependency - kept in
 * their own file specifically so they're directly unit-testable with plain JUnit, no Robolectric
 * or Activity/Plugin test harness required. See `GpsUtilsTest`.
 */
internal object GpsUtils {

    /**
     * True when `latLong` is exactly (0, 0) - "Null Island", a point in the Atlantic Ocean with no
     * land nearby. Some cameras and apps write this as a placeholder for "no GPS fix was available"
     * rather than omitting the GPS tags entirely, so treating it as real location data would be
     * misleading. Uses exact equality deliberately, not a small-radius check: a genuine GPS fix
     * landing on exactly (0.0, 0.0) is astronomically unlikely, whereas a deliberately-written
     * placeholder is always written as exactly 0/1 in the underlying EXIF rational, which parses
     * back to exactly 0.0 - so exact equality catches real placeholders without risking false
     * positives on real (if coincidentally nearby) coordinates.
     */
    fun isNullIsland(latLong: DoubleArray): Boolean {
        return latLong.size == 2 && latLong[0] == 0.0 && latLong[1] == 0.0
    }
}
