package com.company.callrecorder.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {

    // 삼성 통화녹음 기본 경로
    private val SAMSUNG_RECORDING_PATHS = listOf(
        "${Environment.getExternalStorageDirectory()}/Recordings/Call",
        "${Environment.getExternalStorageDirectory()}/Call",
        "${Environment.getExternalStorageDirectory()}/DCIM/Call",
        "${Environment.getExternalStorageDirectory()}/Sounds/Call"
    )

    fun getRecordingDirectory(): File? {
        for (path in SAMSUNG_RECORDING_PATHS) {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                return dir
            }
        }
        return null
    }

    fun getRecordingFiles(): List<File> {
        val dir = getRecordingDirectory() ?: return emptyList()
        return dir.listFiles { file ->
            file.isFile && (file.extension.lowercase() in listOf("m4a", "mp3", "amr", "3gp", "wav"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun parsePhoneNumber(fileName: String): String {
        // 파일명에서 전화번호 추출 (삼성 형식: "통화 녹음 010-1234-5678_20240108_143022.m4a")
        val regex = Regex("""(\d{2,4}[-.]?\d{3,4}[-.]?\d{4})""")
        return regex.find(fileName)?.value?.replace("-", "")?.replace(".", "") ?: "Unknown"
    }

    fun parseCallType(fileName: String): String {
        return when {
            fileName.contains("수신") || fileName.contains("incoming", ignoreCase = true) -> "incoming"
            fileName.contains("발신") || fileName.contains("outgoing", ignoreCase = true) -> "outgoing"
            else -> "unknown"
        }
    }

    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }

    fun formatDateTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}