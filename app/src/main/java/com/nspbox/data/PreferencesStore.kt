package com.nspbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nspbox_preferences")

object PreferencesStore {
    private val SHOW_STARTUP = booleanPreferencesKey("show_startup_disclaimer")

    fun shouldShowStartup(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[SHOW_STARTUP] ?: true
        }
    }

    suspend fun setShowStartup(context: Context, show: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SHOW_STARTUP] = show
        }
    }
}
