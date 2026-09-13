package com.forianyu.runner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RunRecord::class], version = 1, exportSchema = false)
abstract class RunDatabase : RoomDatabase() {
    abstract fun runRecordDao(): RunRecordDao

    companion object {
        @Volatile
        private var instance: RunDatabase? = null

        fun getInstance(context: Context): RunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "runner.db"
                ).build().also { instance = it }
            }
    }
}
