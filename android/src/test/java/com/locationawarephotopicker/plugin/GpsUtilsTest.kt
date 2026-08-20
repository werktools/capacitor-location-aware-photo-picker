package com.locationawarephotopicker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit test - GpsUtils has zero Android framework dependency, so this needs no Robolectric,
 * no Activity, no Capacitor Bridge/Plugin instance. Run with `./gradlew test`.
 */
class GpsUtilsTest {

    @Test
    fun exactZeroZeroIsNullIsland() {
        assertTrue(GpsUtils.isNullIsland(doubleArrayOf(0.0, 0.0)))
    }

    @Test
    fun realCoordinateIsNotNullIsland() {
        assertFalse(GpsUtils.isNullIsland(doubleArrayOf(37.7749, -122.4194)))
    }

    @Test
    fun negativeCoordinatesAreNotNullIsland() {
        assertFalse(GpsUtils.isNullIsland(doubleArrayOf(-33.8688, 151.2093)))
    }

    @Test
    fun latitudeZeroButLongitudeNonzeroIsNotNullIsland() {
        assertFalse(GpsUtils.isNullIsland(doubleArrayOf(0.0, 12.34)))
    }

    @Test
    fun longitudeZeroButLatitudeNonzeroIsNotNullIsland() {
        assertFalse(GpsUtils.isNullIsland(doubleArrayOf(56.78, 0.0)))
    }

    @Test
    fun coordinateVeryCloseToButNotExactlyZeroIsNotNullIsland() {
        // Deliberately NOT treated as null island - see isNullIsland's doc comment for why exact
        // equality is used rather than a small-radius check.
        assertFalse(GpsUtils.isNullIsland(doubleArrayOf(0.0001, 0.0001)))
    }

    @Test
    fun negativeZeroIsStillNullIsland() {
        // -0.0 == 0.0 is true for Kotlin/Java Double equality (IEEE 754 signed zero), and a
        // rational EXIF value of 0/1 could plausibly be read back as either sign of zero depending
        // on how a writer encoded it - both should be treated as the same placeholder.
        assertTrue(GpsUtils.isNullIsland(doubleArrayOf(-0.0, 0.0)))
    }
}
