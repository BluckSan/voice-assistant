package com.hakkiiler.voiceassistant.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Список объектов, хранящийся в DataStore как JSON. Для первой версии этого достаточно. */
open class JsonListRepository<T>(
    private val context: Context,
    scope: CoroutineScope,
    keyName: String,
    itemSerializer: KSerializer<T>,
    private val defaults: List<T> = emptyList(),
) {
    private val key = stringPreferencesKey(keyName)
    private val ser = ListSerializer(itemSerializer)

    val items: StateFlow<List<T>> = context.appDataStore.data.map { p ->
        p[key]?.let { runCatching { AppJson.decodeFromString(ser, it) }.getOrNull() } ?: defaults
    }.stateIn(scope, SharingStarted.Eagerly, defaults)

    /** Свежие данные прямо из хранилища (StateFlow может ещё не успеть загрузиться). */
    suspend fun loadNow(): List<T> =
        context.appDataStore.data.first()[key]?.let { runCatching { AppJson.decodeFromString(ser, it) }.getOrNull() } ?: defaults

    protected suspend fun update(transform: (List<T>) -> List<T>) {
        context.appDataStore.edit { p ->
            val current = p[key]?.let { runCatching { AppJson.decodeFromString(ser, it) }.getOrNull() } ?: defaults
            p[key] = AppJson.encodeToString(ser, transform(current))
        }
    }
}
