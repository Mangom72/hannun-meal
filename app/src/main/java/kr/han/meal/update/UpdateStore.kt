package kr.han.meal.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kr.han.meal.BuildConfig
import java.security.KeyStore

private val Context.updatesDataStore by preferencesDataStore(name="updates")
class UpdateStore(private val store:DataStore<Preferences>,private val removeLegacyKey:()->Unit={
    runCatching {KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry("hannun-github-updates")}
}) {
    constructor(context:Context):this(context.updatesDataStore)
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    private val configKey=stringPreferencesKey("config")
    private val legacyTokenKey=stringPreferencesKey("token_ciphertext")
    private val snapshotKey=stringPreferencesKey("snapshot")
    private fun official(config:UpdateConfig)=config.copy(repository=BuildConfig.UPDATE_REPOSITORY)
    val settings=store.data.map {p->UpdateSettings(official(p[configKey]?.let {json.decodeFromString<UpdateConfig>(it)} ?: UpdateConfig()))}.distinctUntilChanged()
    val snapshot=store.data.map {p->p[snapshotKey]?.let {json.decodeFromString<UpdateSnapshot>(it)} ?: UpdateSnapshot()}
    suspend fun migrateLegacyCredentials()=withContext(Dispatchers.IO) {
        store.edit {p->
            p.remove(legacyTokenKey)
            val previous=p[configKey]?.let {json.decodeFromString<UpdateConfig>(it)} ?: UpdateConfig()
            p[configKey]=json.encodeToString(official(previous))
            if(previous.repository!=BuildConfig.UPDATE_REPOSITORY) p.remove(snapshotKey)
            else p[snapshotKey]?.let {raw->
                val saved=json.decodeFromString<UpdateSnapshot>(raw)
                if(saved.error?.let {"토큰" in it || "비공개" in it}==true) p[snapshotKey]=json.encodeToString(saved.copy(error=null))
            }
        }
        // Delete only the obsolete device-side updater encryption key, never the APK signing key.
        removeLegacyKey()
    }
    suspend fun configure(config:UpdateConfig)=withContext(Dispatchers.IO) {
        val current=official(config)
        if(!UpdateRules.validRepository(current.repository)) throw UpdateException(UpdateError.CONFIG)
        store.edit {p->p[configKey]=json.encodeToString(current);p.remove(legacyTokenKey);p.remove(snapshotKey)}
    }
    suspend fun save(snapshot:UpdateSnapshot) {store.edit {it[snapshotKey]=json.encodeToString(snapshot)}}
}
