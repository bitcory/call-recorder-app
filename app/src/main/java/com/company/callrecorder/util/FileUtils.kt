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

    /**
     * 파일명에서 전화번호 추출
     * 예: "010-1234-5678_20240109.m4a" -> "01012345678"
     * 예: "제이콥소프트(전산) 이주형 대표님_260109_162929.m4a" -> "" (전화번호 없음)
     */
    fun parsePhoneNumber(fileName: String): String {
        // 1. 일반 전화번호 패턴 찾기 (010-1234-5678, 01012345678, 02-123-4567 등)
        val phoneRegex = Regex("""(\d{2,4}[-.]?\d{3,4}[-.]?\d{4})""")
        val phoneMatch = phoneRegex.find(fileName)
        if (phoneMatch != null) {
            // 날짜 패턴이 아닌지 확인 (YYMMDD_HHMMSS 또는 YYYYMMDD_HHMMSS)
            val matched = phoneMatch.value.replace("-", "").replace(".", "")
            // 날짜 패턴 체크: 6자리(YYMMDD) 또는 8자리(YYYYMMDD)가 아닌 경우만 전화번호로 인식
            if (!isDatePattern(matched)) {
                return matched
            }
        }

        // 2. 짧은 번호 (1502, 114, 1588-xxxx 등) - 날짜/시간 패턴 제외
        val shortNumberRegex = Regex("""(?<![_\d])(\d{3,4})(?![_\d])""")
        val nameWithoutDateTime = fileName.replace(Regex("""_\d{6}_\d{6}"""), "")
            .replace(Regex("""_\d{8}_\d{6}"""), "")
        val shortMatch = shortNumberRegex.find(nameWithoutDateTime)
        if (shortMatch != null) {
            val num = shortMatch.groupValues[1]
            // 1588, 1577, 1544 등 대표번호나 114, 119 등 특수번호
            if (num.startsWith("15") || num.startsWith("16") || num.length == 3) {
                return num
            }
        }

        return ""  // 전화번호 없음
    }

    /**
     * 날짜 패턴인지 확인
     */
    private fun isDatePattern(str: String): Boolean {
        // YYMMDD 패턴 (6자리): 년도가 20~30, 월이 01~12, 일이 01~31
        if (str.length >= 6) {
            val possibleDate = str.take(6)
            val year = possibleDate.take(2).toIntOrNull() ?: return false
            val month = possibleDate.substring(2, 4).toIntOrNull() ?: return false
            val day = possibleDate.substring(4, 6).toIntOrNull() ?: return false
            if (year in 20..35 && month in 1..12 && day in 1..31) {
                return true
            }
        }
        return false
    }

    /**
     * 파일명에서 연락처 이름 추출
     * 예: "제이콥소프트(전산) 이주형 대표님_260109_162929.m4a" -> "제이콥소프트(전산) 이주형 대표님"
     * 예: "통화 녹음 홍길동_20240109_163022.m4a" -> "홍길동"
     */
    fun parseContactName(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast(".")

        // "통화 녹음 " 또는 "Call recording " 등 접두사 제거
        var name = nameWithoutExt
            .replace("통화 녹음 ", "")
            .replace("통화녹음 ", "")
            .replace("통화 녹음_", "")
            .replace("통화녹음_", "")
            .replace("Call recording ", "")
            .replace("Call_", "")
            .replace("Recording_", "")

        // 날짜/시간 패턴 제거
        // _YYMMDD_HHMMSS (예: _260109_162929)
        name = name.replace(Regex("""_\d{6}_\d{6}$"""), "")
        // _YYYYMMDD_HHMMSS (예: _20240109_163022)
        name = name.replace(Regex("""_\d{8}_\d{6}$"""), "")
        // _YYYY-MM-DD_HH-MM-SS
        name = name.replace(Regex("""_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}$"""), "")
        // YYYYMMDDHHMMSS (공백 없이 붙어있는 경우)
        name = name.replace(Regex("""_?\d{14}$"""), "")

        // 전화번호 패턴이 있으면 제거 (연락처 이름에서 전화번호 제외)
        name = name.replace(Regex("""\d{2,4}[-.]?\d{3,4}[-.]?\d{4}"""), "")

        // 앞뒤 언더스코어, 공백, 하이픈 정리
        name = name.trim('_', ' ', '-')

        return name
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
