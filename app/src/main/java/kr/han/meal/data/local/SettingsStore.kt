package kr.han.meal.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kr.han.meal.domain.model.*

private val Context.settingsDataStore by preferencesDataStore(name="settings")
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context): this(context.settingsDataStore)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val appKey = stringPreferencesKey("app")
    val settings: Flow<AppSettings> = store.data.map { p -> p[appKey]?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings() }
    // Remove an obsolete client key while preserving every other serialized setting and widget identity.
    suspend fun migrateLegacyCredentials() {store.edit {p->p[appKey]?.let {raw->
        val old=json.parseToJsonElement(raw).jsonObject
        val obsolete=setOf("apiKey","mealApiUrl","schoolDirectoryUrl")
        if(old.keys.any {it in obsolete}) p[appKey]=JsonObject(old.filterKeys {it !in obsolete}).toString()
    }}}
    val widgets: Flow<List<WidgetConfig>> = store.data.map { p -> p.asMap().filterKeys { it.name.startsWith("widget_") }.values.mapNotNull { (it as? String)?.let { value -> json.decodeFromString<WidgetConfig>(value) } }.sortedBy { it.id } }
    suspend fun update(transform: (AppSettings)->AppSettings) = store.edit { p ->
        val old = p[appKey]?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
        p[appKey] = json.encodeToString(transform(old))
    }
    suspend fun saveWidget(config: WidgetConfig) { store.edit { it[stringPreferencesKey("widget_${config.id}")] = json.encodeToString(config) } }
    suspend fun updateWidget(id: Int, transform: (WidgetConfig)->WidgetConfig) { store.edit { p ->
        val k = stringPreferencesKey("widget_$id")
        p[k]?.let { p[k] = json.encodeToString(transform(json.decodeFromString(it))) }
    } }
    suspend fun removeWidget(id: Int) { store.edit { it.remove(stringPreferencesKey("widget_$id")) } }
    suspend fun reset() { update { AppSettings() } }
}
