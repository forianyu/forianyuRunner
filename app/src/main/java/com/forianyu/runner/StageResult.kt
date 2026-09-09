package com.forianyu.runner

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** One 단계(stage) of a run: the slice between two "다음 단계" presses (or start/stop). */
@Parcelize
data class StageResult(
    val index: Int,
    val startWallClockMs: Long,
    val endWallClockMs: Long,
    val distanceMeters: Double,
    val durationMs: Long
) : Parcelable
