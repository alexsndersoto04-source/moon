package com.moonshare.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moonshare.data.model.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.moonDataStore by preferencesDataStore(name = "moon_settings")

/**
 * Preferencias de MoonShare. Todo lo "avanzado" que pide la brief vive acá,
 * agrupado por sección para que la pantalla de Ajustes sea un mapeo 1:1.
 */
data class MoonSettings(
    // --- Transferencia ---
    val autoAccept: Boolean = false,
    val saveToGallery: Boolean = true,
    val keepOriginalName: Boolean = true,
    val maxParallelStreams: Int = 1,
    val keepScreenOn: Boolean = true,
    val receiveTimeoutSec: Int = 120,

    // --- Radio / red ---
    val prefer5GHz: Boolean = true,
    val listenPort: Int = DEFAULT_PORT,
    val deviceNickname: String = "",
    val persistGroupAfterSend: Boolean = false,

    // --- Sonido y feedback ---
    val waterSfxEnabled: Boolean = true,
    val waterSfxVolume: Float = 0.55f,
    val hapticsEnabled: Boolean = true,
    val soundOnReceiveOnly: Boolean = false,

    // --- Esfera / vidrio ---
    val orbEnabled: Boolean = true,
    val orbIntensity: Float = 0.85f,
    val reduceMotion: Boolean = false,
    val amoledBlack: Boolean = false,

    // --- NFC ---
    val nfcBumpEnabled: Boolean = true,

    // --- Avanzado ---
    val verboseLogs: Boolean = false,
    val speedLimitKbps: Int = 0,
    val historyEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_PORT = 41795
        const val MAX_PARALLEL = 4
    }
}

class SettingsStore(private val context: Context) {

    private object Key {
        val autoAccept = booleanPreferencesKey("tx.autoAccept")
        val saveToGallery = booleanPreferencesKey("tx.saveToGallery")
        val keepOriginalName = booleanPreferencesKey("tx.keepOriginalName")
        val maxParallel = intPreferencesKey("tx.maxParallel")
        val keepScreenOn = booleanPreferencesKey("tx.keepScreenOn")
        val receiveTimeout = intPreferencesKey("tx.receiveTimeoutSec")

        val prefer5 = booleanPreferencesKey("net.prefer5GHz")
        val port = intPreferencesKey("net.port")
        val nickname = stringPreferencesKey("net.nickname")
        val persistGroup = booleanPreferencesKey("net.persistGroup")

        val sfx = booleanPreferencesKey("snd.enabled")
        val sfxVol = floatPreferencesKey("snd.volume")
        val haptics = booleanPreferencesKey("snd.haptics")
        val sfxOnReceive = booleanPreferencesKey("snd.onReceiveOnly")

        val orb = booleanPreferencesKey("ui.orb")
        val orbIntensity = floatPreferencesKey("ui.orbIntensity")
        val reduceMotion = booleanPreferencesKey("ui.reduceMotion")
        val amoled = booleanPreferencesKey("ui.amoled")

        val nfcBump = booleanPreferencesKey("nfc.bump")

        val logs = booleanPreferencesKey("adv.logs")
        val speedLimit = intPreferencesKey("adv.speedLimitKbps")
        val history = booleanPreferencesKey("adv.history")
        val historyBlob = stringPreferencesKey("adv.historyBlob")
        val onboarded = booleanPreferencesKey("sys.onboarded")
    }

    val settings: Flow<MoonSettings> = context.moonDataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { p ->
            MoonSettings(
                autoAccept = p[Key.autoAccept] ?: false,
                saveToGallery = p[Key.saveToGallery] ?: true,
                keepOriginalName = p[Key.keepOriginalName] ?: true,
                maxParallelStreams = p[Key.maxParallel] ?: 1,
                keepScreenOn = p[Key.keepScreenOn] ?: true,
                receiveTimeoutSec = p[Key.receiveTimeout] ?: 120,

                prefer5GHz = p[Key.prefer5] ?: true,
                listenPort = p[Key.port] ?: MoonSettings.DEFAULT_PORT,
                deviceNickname = p[Key.nickname] ?: "",
                persistGroupAfterSend = p[Key.persistGroup] ?: false,

                waterSfxEnabled = p[Key.sfx] ?: true,
                waterSfxVolume = p[Key.sfxVol] ?: 0.55f,
                hapticsEnabled = p[Key.haptics] ?: true,
                soundOnReceiveOnly = p[Key.sfxOnReceive] ?: false,

                orbEnabled = p[Key.orb] ?: true,
                orbIntensity = p[Key.orbIntensity] ?: 0.85f,
                reduceMotion = p[Key.reduceMotion] ?: false,
                amoledBlack = p[Key.amoled] ?: false,

                nfcBumpEnabled = p[Key.nfcBump] ?: true,

                verboseLogs = p[Key.logs] ?: false,
                speedLimitKbps = p[Key.speedLimit] ?: 0,
                historyEnabled = p[Key.history] ?: true,
            )
        }

    /**
     * Cache en memoria para el código no-suspenso (el servidor de sockets, el
     * SfxPlayer). Se refresca desde el ViewModel en cada emisión.
     */
    @Volatile
    var current: MoonSettings = MoonSettings()
        private set

    internal fun cache(settings: MoonSettings) {
        current = settings
    }

    // ---------------------------------------------------------------- escritura
    private suspend inline fun write(crossinline block: (MutablePreferences) -> Unit) {
        context.moonDataStore.edit { prefs -> block(prefs) }
    }

    suspend fun setAutoAccept(v: Boolean) = write { it[Key.autoAccept] = v }
    suspend fun setSaveToGallery(v: Boolean) = write { it[Key.saveToGallery] = v }
    suspend fun setKeepOriginalName(v: Boolean) = write { it[Key.keepOriginalName] = v }
    suspend fun setMaxParallel(v: Int) = write { it[Key.maxParallel] = v.coerceIn(1, MoonSettings.MAX_PARALLEL) }
    suspend fun setKeepScreenOn(v: Boolean) = write { it[Key.keepScreenOn] = v }
    suspend fun setReceiveTimeout(v: Int) = write { it[Key.receiveTimeout] = v.coerceIn(15, 900) }

    suspend fun setPrefer5GHz(v: Boolean) = write { it[Key.prefer5] = v }
    suspend fun setListenPort(v: Int) = write { it[Key.port] = v.coerceIn(1024, 65535) }
    suspend fun setNickname(v: String) = write { it[Key.nickname] = v.trim().take(24) }
    suspend fun setPersistGroup(v: Boolean) = write { it[Key.persistGroup] = v }

    suspend fun setSfx(v: Boolean) = write { it[Key.sfx] = v }
    suspend fun setSfxVolume(v: Float) = write { it[Key.sfxVol] = v.coerceIn(0f, 1f) }
    suspend fun setHaptics(v: Boolean) = write { it[Key.haptics] = v }
    suspend fun setSoundOnReceiveOnly(v: Boolean) = write { it[Key.sfxOnReceive] = v }

    suspend fun setOrb(v: Boolean) = write { it[Key.orb] = v }
    suspend fun setOrbIntensity(v: Float) = write { it[Key.orbIntensity] = v.coerceIn(0.2f, 1f) }
    suspend fun setReduceMotion(v: Boolean) = write { it[Key.reduceMotion] = v }
    suspend fun setAmoled(v: Boolean) = write { it[Key.amoled] = v }

    suspend fun setNfcBump(v: Boolean) = write { it[Key.nfcBump] = v }

    suspend fun setVerboseLogs(v: Boolean) = write { it[Key.logs] = v }
    suspend fun setSpeedLimitKbps(v: Int) = write { it[Key.speedLimit] = v.coerceIn(0, 5_000_000) }
    suspend fun setHistoryEnabled(v: Boolean) = write { it[Key.history] = v }

    suspend fun setOnboarded(v: Boolean) = write { it[Key.onboarded] = v }

    suspend fun resetAll() {
        context.moonDataStore.edit { it.clear() }
    }

    // ---------------------------------------------------------------- historial
    private val historyCap = 250

    suspend fun appendHistory(entry: HistoryEntry) {
        context.moonDataStore.edit { p ->
            val list = ArrayList(parse(p[Key.historyBlob]))
            list.add(0, entry)
            while (list.size > historyCap) list.removeAt(list.size - 1)
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("t", e.timestampMs).put("d", e.direction)
                        .put("n", e.name).put("s", e.size)
                        .put("p", e.peer).put("o", e.ok)
                )
            }
            p[Key.historyBlob] = arr.toString()
        }
    }

    suspend fun readHistory(): List<HistoryEntry> {
        val raw = context.moonDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[Key.historyBlob] ?: "[]" }
            .first()
        return parse(raw)
    }

    suspend fun clearHistory() {
        context.moonDataStore.edit { it.remove(Key.historyBlob) }
    }

    private fun parse(raw: String?): List<HistoryEntry> {
        val arr = try {
            JSONArray(raw ?: "[]")
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HistoryEntry(
                    timestampMs = o.optLong("t"),
                    direction = o.optString("d", "Send"),
                    name = o.optString("n", ""),
                    size = o.optLong("s"),
                    peer = o.optString("p", ""),
                    ok = o.optBoolean("o", true),
                )
            )
        }
        return out
    }

}
