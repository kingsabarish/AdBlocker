package com.adblocker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "adblocker_settings")

object AppSettings {
    private val AUTO_START = booleanPreferencesKey("auto_start")

    fun autoStart(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AUTO_START] ?: false }

    suspend fun setAutoStart(context: Context, value: Boolean) {
        context.dataStore.edit { it[AUTO_START] = value }
    }
}
