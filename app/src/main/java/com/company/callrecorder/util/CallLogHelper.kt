package com.company.callrecorder.util

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import kotlin.math.abs

data class CallLogEntry(
    val phoneNumber: String,
    val contactName: String,
    val callType: String,  // "incoming", "outgoing", "missed"
    val timestamp: Long,
    val duration: Int
)

object CallLogHelper {

    /**
     * 통화 기록에서 녹음 시간과 가장 가까운 통화 찾기
     * @param context Context
     * @param recordingTimestamp 녹음 파일의 타임스탬프
     * @param toleranceMs 허용 오차 (밀리초, 기본 5분)
     */
    fun findMatchingCall(
        context: Context,
        recordingTimestamp: Long,
        toleranceMs: Long = 5 * 60 * 1000
    ): CallLogEntry? {
        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            // 녹음 시간 기준 앞뒤 시간 범위 내 통화 검색
            val selection = "${CallLog.Calls.DATE} BETWEEN ? AND ?"
            val selectionArgs = arrayOf(
                (recordingTimestamp - toleranceMs).toString(),
                (recordingTimestamp + toleranceMs).toString()
            )
            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                var bestMatch: CallLogEntry? = null
                var smallestDiff = Long.MAX_VALUE

                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val cachedName = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "unknown"
                    }

                    // 가장 가까운 시간의 통화 찾기
                    val timeDiff = abs(date - recordingTimestamp)
                    if (timeDiff < smallestDiff) {
                        smallestDiff = timeDiff

                        // 연락처 이름이 없으면 연락처에서 조회
                        val contactName = cachedName ?: getContactName(context, number)

                        bestMatch = CallLogEntry(
                            phoneNumber = number,
                            contactName = contactName,
                            callType = callType,
                            timestamp = date,
                            duration = duration
                        )
                    }
                }

                return bestMatch
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            // 권한 없음
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    /**
     * 전화번호로 연락처 이름 조회
     */
    fun getContactName(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""

        try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build()

            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ""
    }

    /**
     * 최근 통화 기록 가져오기 (최근 100개)
     */
    fun getRecentCalls(context: Context, limit: Int = 100): List<CallLogEntry> {
        val calls = mutableListOf<CallLogEntry>()

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit"

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val cachedName = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val duration = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "unknown"
                    }

                    val contactName = cachedName ?: getContactName(context, number)

                    calls.add(
                        CallLogEntry(
                            phoneNumber = number,
                            contactName = contactName,
                            callType = callType,
                            timestamp = date,
                            duration = duration
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return calls
    }
}
