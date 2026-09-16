package fr.hermesmusic.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/** Session Jellyfin. Le mot de passe n'est JAMAIS conservé (uniquement le jeton). */
data class Session(
    val serverUrl: String = "",
    val token: String = "",
    val userId: String = "",
    val userName: String = "",
    val deviceId: String = "",
) {
    val isConnected: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank() && userId.isNotBlank()
}

/** Préférences d'apparence (persistées, appliquées au démarrage). */
data class Appearance(
    val dark: Boolean = true,
    val accentIndex: Int = 0,
)

class SettingsStore(private val context: Context) {

    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("access_token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val deviceId = stringPreferencesKey("device_id")
        val dark = booleanPreferencesKey("theme_dark")
        val accent = intPreferencesKey("theme_accent")
    }

    val session: Flow<Session> = context.dataStore.data.map { p ->
        Session(
            serverUrl = p[K.serverUrl] ?: "",
            token = p[K.token] ?: "",
            userId = p[K.userId] ?: "",
            userName = p[K.userName] ?: "",
            deviceId = p[K.deviceId] ?: "",
        )
    }

    suspend fun current(): Session = session.first()

    /** Identifiant d'appareil STABLE : indispensable pour un historique Jellyfin cohérent. */
    suspend fun ensureDeviceId(): String {
        val existing = context.dataStore.data.first()[K.deviceId]
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[K.deviceId] = id }
        return id
    }

    suspend fun saveConnection(serverUrl: String, token: String, userId: String, userName: String) {
        context.dataStore.edit {
            it[K.serverUrl] = serverUrl.trim().trimEnd('/')
            it[K.token] = token
            it[K.userId] = userId
            it[K.userName] = userName
        }
    }

    suspend fun saveServerUrl(serverUrl: String) {
        context.dataStore.edit { it[K.serverUrl] = serverUrl.trim().trimEnd('/') }
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(K.token) }
    }

    /* --- apparence --- */

    suspend fun appearance(): Appearance {
        val p = context.dataStore.data.first()
        return Appearance(
            dark = p[K.dark] ?: true,
            accentIndex = p[K.accent] ?: 0,
        )
    }

    suspend fun saveAppearance(dark: Boolean, accentIndex: Int) {
        context.dataStore.edit {
            it[K.dark] = dark
            it[K.accent] = accentIndex
        }
    }
}
