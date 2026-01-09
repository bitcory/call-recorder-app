package com.company.callrecorder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.callrecorder.data.AppDatabase
import com.company.callrecorder.data.FirebaseRepository
import com.company.callrecorder.data.Recording
import com.company.callrecorder.data.RecordingRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordingRepository
    private val firebaseRepository = FirebaseRepository()

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _todayCount = MutableStateFlow(0)
    val todayCount: StateFlow<Int> = _todayCount.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _uploadingIds = MutableStateFlow<Set<String>>(emptySet())
    val uploadingIds: StateFlow<Set<String>> = _uploadingIds.asStateFlow()

    private val _uploadMessage = MutableStateFlow<String?>(null)
    val uploadMessage: StateFlow<String?> = _uploadMessage.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RecordingRepository(database.recordingDao())

        viewModelScope.launch {
            repository.allRecordings.collect { list ->
                _recordings.value = list
            }
        }

        viewModelScope.launch {
            repository.todayCount.collect { count ->
                _todayCount.value = count
            }
        }

        viewModelScope.launch {
            repository.pendingCount.collect { count ->
                _pendingCount.value = count
            }
        }
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            repository.delete(recording)
        }
    }

    fun uploadRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                // 업로드 중 표시
                _uploadingIds.value = _uploadingIds.value + recording.id
                repository.updateUploadStatus(recording.id, "uploading")

                // Firebase Auth에서 현재 사용자 가져오기
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    _uploadMessage.value = "로그인이 필요합니다"
                    repository.updateUploadStatus(recording.id, "failed")
                    _uploadingIds.value = _uploadingIds.value - recording.id
                    return@launch
                }

                // Firestore에서 사용자 정보 가져오기
                val userDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUser.uid)
                    .get()
                    .await()

                if (!userDoc.exists()) {
                    _uploadMessage.value = "사용자 정보를 찾을 수 없습니다"
                    repository.updateUploadStatus(recording.id, "failed")
                    _uploadingIds.value = _uploadingIds.value - recording.id
                    return@launch
                }

                val status = userDoc.getString("status") ?: "pending"
                if (status != "approved") {
                    _uploadMessage.value = "관리자 승인이 필요합니다"
                    repository.updateUploadStatus(recording.id, "failed")
                    _uploadingIds.value = _uploadingIds.value - recording.id
                    return@launch
                }

                val employeeName = userDoc.getString("displayName") ?: ""
                val employeeId = currentUser.uid

                // Firebase 업로드
                val result = firebaseRepository.uploadRecording(
                    recording = recording,
                    employeeName = employeeName,
                    employeeId = employeeId,
                    phoneNumber = currentUser.email ?: ""
                )

                if (result.isSuccess) {
                    repository.updateUploadStatus(recording.id, "done")
                    _uploadMessage.value = "업로드 완료!"
                } else {
                    repository.updateUploadStatus(recording.id, "failed")
                    _uploadMessage.value = "업로드 실패: ${result.exceptionOrNull()?.message}"
                }

            } catch (e: Exception) {
                repository.updateUploadStatus(recording.id, "failed")
                _uploadMessage.value = "업로드 실패: ${e.message}"
            } finally {
                _uploadingIds.value = _uploadingIds.value - recording.id
            }
        }
    }

    fun uploadAllPending() {
        viewModelScope.launch {
            val pendingRecordings = _recordings.value.filter {
                it.uploadStatus == "pending" || it.uploadStatus == "failed"
            }
            pendingRecordings.forEach { recording ->
                uploadRecording(recording)
            }
        }
    }

    fun clearUploadMessage() {
        _uploadMessage.value = null
    }
}
