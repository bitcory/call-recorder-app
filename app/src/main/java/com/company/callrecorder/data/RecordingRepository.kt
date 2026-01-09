package com.company.callrecorder.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {

    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    val pendingCount: Flow<Int> = recordingDao.getPendingCount()

    val todayCount: Flow<Int> = recordingDao.getTodayCount()

    fun getRecordingsByStatus(status: String): Flow<List<Recording>> {
        return recordingDao.getRecordingsByStatus(status)
    }

    suspend fun getRecordingById(id: String): Recording? {
        return recordingDao.getRecordingById(id)
    }

    suspend fun insert(recording: Recording) {
        recordingDao.insert(recording)
    }

    suspend fun update(recording: Recording) {
        recordingDao.update(recording)
    }

    suspend fun delete(recording: Recording) {
        recordingDao.delete(recording)
    }

    suspend fun updateUploadStatus(id: String, status: String) {
        recordingDao.getRecordingById(id)?.let { recording ->
            recordingDao.update(recording.copy(uploadStatus = status))
        }
    }
}