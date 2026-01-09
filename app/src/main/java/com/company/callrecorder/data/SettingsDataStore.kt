package com.company.callrecorder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        val EMPLOYEE_NAME = stringPreferencesKey("employee_name")
        val EMPLOYEE_ID = stringPreferencesKey("employee_id")
        val SERVER_URL = stringPreferencesKey("server_url")
        val PHONE_NUMBER = stringPreferencesKey("phone_number")
    }

    val employeeName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[EMPLOYEE_NAME] ?: ""
    }

    val employeeId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[EMPLOYEE_ID] ?: ""
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SERVER_URL] ?: ""
    }

    val phoneNumber: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PHONE_NUMBER] ?: ""
    }

    suspend fun saveEmployeeName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[EMPLOYEE_NAME] = name
        }
    }

    suspend fun saveEmployeeId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[EMPLOYEE_ID] = id
        }
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_URL] = url
        }
    }

    suspend fun savePhoneNumber(phone: String) {
        context.dataStore.edit { prefs ->
            prefs[PHONE_NUMBER] = phone
        }
    }

    suspend fun saveAll(name: String, id: String, phone: String, serverUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[EMPLOYEE_NAME] = name
            prefs[EMPLOYEE_ID] = id
            prefs[PHONE_NUMBER] = phone
            prefs[SERVER_URL] = serverUrl
        }
    }
}