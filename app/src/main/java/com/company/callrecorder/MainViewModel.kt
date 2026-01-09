package com.company.callrecorder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.company.callrecorder.data.AppDatabase
import com.company.callrecorder.data.FirebaseRepository
import com.company.callrecorder.data.Recording
import com.company.callrecorder.data.RecordingRepository
import com.company.callrecorder.util.CallLogHelper
import com.company.callrecorder.util.FileUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

data class DeviceFile(
    val file: File,
    val phoneNumber: String,
    val contactName: String,
    val callType: String,
    val recordedAt: Long,
    val isAlreadyAdded: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RecordingRepository
    private val firebaseRepository = FirebaseRepository()

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    private val _deviceFiles = MutableStateFlow<List<DeviceFile>>(emptyList())
    val deviceFiles: StateFlow<List<DeviceFile>> = _deviceFiles.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()

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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

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

    // 디바이스에서 녹음 파일 목록 가져오기
    fun scanDeviceFiles() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val files = FileUtils.getRecordingFiles()
                val addedPaths = _recordings.value.map { it.filePath }.toSet()
                val context = getApplication<Application>()

                _deviceFiles.value = files.map { file ->
                    // 통화 기록에서 매칭되는 전화 찾기
                    val callLogEntry = CallLogHelper.findMatchingCall(
                        context = context,
                        recordingTimestamp = file.lastModified()
                    )

                    if (callLogEntry != null) {
                        // 통화 기록에서 정보 가져오기
                        DeviceFile(
                            file = file,
                            phoneNumber = callLogEntry.phoneNumber,
                            contactName = callLogEntry.contactName,
                            callType = callLogEntry.callType,
                            recordedAt = file.lastModified(),
                            isAlreadyAdded = addedPaths.contains(file.absolutePath)
                        )
                    } else {
                        // 통화 기록 없으면 파일명에서 파싱
                        DeviceFile(
                            file = file,
                            phoneNumber = FileUtils.parsePhoneNumber(file.name),
                            contactName = FileUtils.parseContactName(file.name),
                            callType = FileUtils.parseCallType(file.name),
                            recordedAt = file.lastModified(),
                            isAlreadyAdded = addedPaths.contains(file.absolutePath)
                        )
                    }
                }.sortedByDescending { it.recordedAt }

                _selectedFiles.value = emptySet()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isScanning.value = false
            }
        }
    }

    // 파일 선택/해제
    fun toggleFileSelection(filePath: String) {
        val current = _selectedFiles.value.toMutableSet()
        if (current.contains(filePath)) {
            current.remove(filePath)
        } else {
            current.add(filePath)
        }
        _selectedFiles.value = current
    }

    // 전체 선택/해제
    fun toggleSelectAll() {
        val notAdded = _deviceFiles.value.filter { !it.isAlreadyAdded }
        if (_selectedFiles.value.size == notAdded.size) {
            _selectedFiles.value = emptySet()
        } else {
            _selectedFiles.value = notAdded.map { it.file.absolutePath }.toSet()
        }
    }

    // 선택한 파일들 추가 및 업로드
    fun addAndUploadSelected() {
        viewModelScope.launch {
            val selectedPaths = _selectedFiles.value
            val filesToAdd = _deviceFiles.value.filter { selectedPaths.contains(it.file.absolutePath) }

            for (deviceFile in filesToAdd) {
                try {
                    val recording = Recording(
                        id = deviceFile.file.absolutePath.hashCode().toString(),
                        fileName = deviceFile.file.name,
                        filePath = deviceFile.file.absolutePath,
                        phoneNumber = deviceFile.phoneNumber,
                        contactName = deviceFile.contactName,
                        callType = deviceFile.callType,
                        duration = getAudioDuration(deviceFile.file),
                        recordedAt = deviceFile.recordedAt,
                        uploadStatus = "pending"
                    )
                    repository.insert(recording)

                    // 바로 업로드
                    uploadRecording(recording)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            _selectedFiles.value = emptySet()
            // 목록 갱신
            scanDeviceFiles()
        }
    }

    private fun getAudioDuration(file: File): Int {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            (duration?.toLongOrNull() ?: 0L).toInt() / 1000
        } catch (e: Exception) {
            0
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            repository.delete(recording)
        }
    }

    fun uploadRecording(recording: Recording) {
        viewModelScope.launch {
            try {
                _uploadingIds.value = _uploadingIds.value + recording.id
                repository.updateUploadStatus(recording.id, "uploading")

                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    _uploadMessage.value = "로그인이 필요합니다"
                    repository.updateUploadStatus(recording.id, "failed")
                    _uploadingIds.value = _uploadingIds.value - recording.id
                    return@launch
                }

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
