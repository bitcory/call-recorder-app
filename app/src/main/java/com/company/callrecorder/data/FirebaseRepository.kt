package com.company.callrecorder.data

import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

class FirebaseRepository {

    private val storage = FirebaseStorage.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val recordingsCollection = firestore.collection("recordings")

    // 녹음 파일 업로드
    suspend fun uploadRecording(
        recording: Recording,
        employeeName: String,
        employeeId: String,
        phoneNumber: String
    ): Result<String> {
        return try {
            val file = File(recording.filePath)
            if (!file.exists()) {
                return Result.failure(Exception("파일이 존재하지 않습니다"))
            }

            // Storage에 파일 업로드
            val fileName = "${employeeId}_${recording.recordedAt}_${file.name}"
            val storageRef = storage.reference.child("recordings/$employeeId/$fileName")

            val uploadTask = storageRef.putFile(Uri.fromFile(file)).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            // Firestore에 메타데이터 저장
            val recordingData = hashMapOf(
                "id" to recording.id,
                "fileName" to recording.fileName,
                "downloadUrl" to downloadUrl,
                "storagePath" to "recordings/$employeeId/$fileName",
                "phoneNumber" to recording.phoneNumber,
                "contactName" to recording.contactName,
                "callType" to recording.callType,
                "duration" to recording.duration,
                "recordedAt" to recording.recordedAt,
                "uploadedAt" to System.currentTimeMillis(),
                "employeeName" to employeeName,
                "employeeId" to employeeId,
                "employeePhone" to phoneNumber,
                "fileSize" to file.length()
            )

            recordingsCollection.document(recording.id).set(recordingData).await()

            Result.success(downloadUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 모든 녹음 가져오기
    suspend fun getAllRecordings(): Result<List<Map<String, Any>>> {
        return try {
            val snapshot = recordingsCollection
                .orderBy("recordedAt", Query.Direction.DESCENDING)
                .get()
                .await()

            val recordings = snapshot.documents.mapNotNull { it.data }
            Result.success(recordings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 직원별 녹음 가져오기
    suspend fun getRecordingsByEmployee(employeeId: String): Result<List<Map<String, Any>>> {
        return try {
            val snapshot = recordingsCollection
                .whereEqualTo("employeeId", employeeId)
                .orderBy("recordedAt", Query.Direction.DESCENDING)
                .get()
                .await()

            val recordings = snapshot.documents.mapNotNull { it.data }
            Result.success(recordings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 녹음 삭제
    suspend fun deleteRecording(recordingId: String, storagePath: String): Result<Unit> {
        return try {
            // Storage에서 파일 삭제
            storage.reference.child(storagePath).delete().await()
            // Firestore에서 문서 삭제
            recordingsCollection.document(recordingId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}