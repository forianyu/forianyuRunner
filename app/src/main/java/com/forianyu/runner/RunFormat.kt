package com.forianyu.runner

import java.util.Locale

/** Shared number formatting for stage/run stats, used by both the running and result screens. */
object RunFormat {
    fun duration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun distanceKm(distanceMeters: Double): String =
        String.format(Locale.US, "%.2fkm", distanceMeters / 1000.0)

    fun speedKmh(distanceMeters: Double, durationMs: Long): String {
        val hours = durationMs / 3_600_000.0
        val kmh = if (hours > 0) (distanceMeters / 1000.0) / hours else 0.0
        return String.format(Locale.US, "%.1fkm/h", kmh)
    }

    // Bare-number variants (no unit suffix) for the cramped stage table,
    // which puts the unit in the column header once instead.
    fun distanceKmValue(distanceMeters: Double): String =
        String.format(Locale.US, "%.2f", distanceMeters / 1000.0)

    fun speedKmhValue(distanceMeters: Double, durationMs: Long): String {
        val hours = durationMs / 3_600_000.0
        val kmh = if (hours > 0) (distanceMeters / 1000.0) / hours else 0.0
        return String.format(Locale.US, "%.1f", kmh)
    }
}
