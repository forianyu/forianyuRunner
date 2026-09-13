package com.forianyu.runner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One completed, saved run - the persisted counterpart of a finished tracking session. */
@Entity(tableName = "run_records")
data class RunRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startWallClockMs: Long,
    val endWallClockMs: Long,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val kcal: Double,
    val weightKg: Double,
    val stageCount: Int
)
