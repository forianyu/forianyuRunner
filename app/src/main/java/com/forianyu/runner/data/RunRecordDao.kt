package com.forianyu.runner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RunRecordDao {
    @Insert
    suspend fun insert(record: RunRecord): Long

    /** Records whose start time falls in [fromMs, toMs), newest first. */
    @Query(
        "SELECT * FROM run_records WHERE startWallClockMs >= :fromMs AND startWallClockMs < :toMs " +
            "ORDER BY startWallClockMs DESC"
    )
    suspend fun getInRange(fromMs: Long, toMs: Long): List<RunRecord>
}
