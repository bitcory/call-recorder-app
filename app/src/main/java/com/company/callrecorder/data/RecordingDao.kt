package com.company.callrecorder.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY recordedAt DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE uploadStatus = :status")
    fun getRecordingsByStatus(status: String): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("SELECT COUNT(*) FROM recordings WHERE uploadStatus = 'pending'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM recordings WHERE date(recordedAt/1000, 'unixepoch', 'localtime') = date('now', 'localtime')")
    fun getTodayCount(): Flow<Int>
}