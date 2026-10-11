package com.hakkiiler.voiceassistant.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.serialization.json.Json

/** Единое локальное хранилище приложения (вместо Firebase в оригинале). */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_assistant")

internal val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
