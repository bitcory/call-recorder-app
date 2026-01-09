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
        // 1. 먼저 전화번호 패턴 찾기 (010-1234-5678, 01012345678, 02-123-4567 등)
        val phoneRegex = Regex("""(\d{2,4}[-.]?\d{3,4}[-.]?\d{4})""")
        val phoneMatch = phoneRegex.find(fileName)
        if (phoneMatch != null) {
            return phoneMatch.value.replace("-", "").replace(".", "")
        }

        // 2. 짧은 번호 (1502, 114, 1588-xxxx 등)
        val shortNumberRegex = Regex("""[^\d](\d{3,4})[^\d]""")
        val shortMatch = shortNumberRegex.find(fileName)

        // 3. 전화번호가 없으면 파일명에서 연락처 이름 추출
        // 삼성 형식: "통화 녹음 연락처이름_날짜_시간.m4a" 또는 "Call recording 연락처이름_날짜_시간.m4a"
        val nameWithoutExt = fileName.substringBeforeLast(".")

        // "통화 녹음 " 또는 "Call recording " 제거
        var name = nameWithoutExt
            .replace("통화 녹음 ", "")
            .replace("통화녹음 ", "")
            .replace("통화 녹음_", "")
            .replace("통화녹음_", "")
            .replace("Call recording ", "")
            .replace("Call_", "")
            .replace("Recording_", "")

        // 날짜/시간 패턴 제거 (예: _20240109_163022 또는 _2024-01-09_16-30-22)
        name = name.replace(Regex("""_?\d{8}_\d{6}$"""), "")
        name = name.replace(Regex("""_?\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}$"""), "")
        name = name.replace(Regex("""_?\d{4}\d{2}\d{2}\d{6}$"""), "")

        // 앞뒤 언더스코어 정리
        name = name.trim('_', ' ')

        // 짧은 번호가 발견되고 이름이 비어있으면 짧은 번호 사용
        if (name.isEmpty() && shortMatch != null) {
            return shortMatch.groupValues[1]
        }

        return name.ifEmpty { "알 수 없음" }
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
