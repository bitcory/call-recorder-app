package com.company.callrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey
    val id: String,
    val fileName: String,
    val filePath: String,
    val phoneNumber: String,      // 전화번호 (없으면 빈 문자열)
    val contactName: String = "", // 연락처 이름
    val callType: String,  // "incoming" or "outgoing"
    val duration: Int,     // 초 단위
    val recordedAt: Long,  // timestamp
    val uploadStatus: String = "pending",  // pending, uploading, done, failed
    val createdAt: Long = System.currentTimeMillis()
)