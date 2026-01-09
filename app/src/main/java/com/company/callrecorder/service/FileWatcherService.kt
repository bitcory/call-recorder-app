package com.company.callrecorder.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.company.callrecorder.R
import com.company.callrecorder.data.AppDatabase
import com.company.callrecorder.data.FirebaseRepository
import com.company.callrecorder.data.Recording
import com.company.callrecorder.data.RecordingRepository
import com.company.callrecorder.util.FileUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

class FileWatcherService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fileObserver: FileObserver? = null
    private lateinit var repository: RecordingRepository
    private val firebaseRepository = FirebaseRepository()

    companion object {
        const val CHANNEL_ID = "CallRecorderChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()

        val database = AppDatabase.getDatabase(applicationContext)
        repository = RecordingRepository(database.recordingDao())

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startWatching()

        // 시작할 때 기존 파일들 스캔
        scanExistingFiles()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "통화녹음 감시",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "통화녹음 파일을 감시하고 자동 업로드합니다"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("통화녹음 업로더")
            .setContentText("녹음 파일 감시 및 자동 업로드 중...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startWatching() {
        val recordingDir = FileUtils.getRecordingDirectory()

        if (recordingDir != null) {
            fileObserver = object : FileObserver(recordingDir.path, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && !path.startsWith(".")) {
                        val file = File(recordingDir, path)
                        if (file.exists() && file.isFile) {
                            // 파일 쓰기 완료 대기 후 처리
                            serviceScope.launch {
                                delay(3000) // 파일 쓰기 완료 대기
                                processNewFile(file)
                            }
                        }
                    }
                }
            }
            fileObserver?.startWatching()
        }
    }

    private fun scanExistingFiles() {
        serviceScope.launch {
            val files = FileUtils.getRecordingFiles()
            for (file in files) {
                val existingRecording = repository.getRecordingById(file.absolutePath.hashCode().toString())
                if (existingRecording == null) {
                    processNewFile(file)
                }
            }
        }
    }

    private suspend fun processNewFile(file: File) {
        try {
            val recording = Recording(
                id = file.absolutePath.hashCode().toString(),
                fileName = file.name,
                filePath = file.absolutePath,
                phoneNumber = FileUtils.parsePhoneNumber(file.name),
                callType = FileUtils.parseCallType(file.name),
                duration = getAudioDuration(file),
                recordedAt = file.lastModified(),
                uploadStatus = "pending"
            )
            repository.insert(recording)

            // 자동 업로드 시도
            autoUpload(recording)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun autoUpload(recording: Recording) {
        try {
            // Firebase Auth에서 현재 로그인된 사용자 확인
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                // 로그인 안 됨 - 나중에 수동 업로드
                return
            }

            // Firestore에서 사용자 정보 가져오기
            val userDoc = FirebaseFirestore.getInstance()
                .collection("users")
                .document(currentUser.uid)
                .get()
                .await()

            if (!userDoc.exists()) {
                return
            }

            val status = userDoc.getString("status") ?: "pending"
            if (status != "approved") {
                // 승인되지 않은 사용자 - 업로드 불가
                return
            }

            val employeeName = userDoc.getString("displayName") ?: ""
            val employeeId = currentUser.uid

            // 업로드 상태 변경
            repository.updateUploadStatus(recording.id, "uploading")

            // Firebase에 업로드
            val result = firebaseRepository.uploadRecording(
                recording = recording,
                employeeName = employeeName,
                employeeId = employeeId,
                phoneNumber = currentUser.email ?: ""
            )

            if (result.isSuccess) {
                repository.updateUploadStatus(recording.id, "done")
                updateNotification("업로드 완료: ${recording.phoneNumber}")
            } else {
                repository.updateUploadStatus(recording.id, "failed")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            repository.updateUploadStatus(recording.id, "failed")
        }
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("통화녹음 업로더")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        serviceScope.cancel()
    }
}
