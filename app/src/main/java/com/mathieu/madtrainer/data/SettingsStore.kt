package com.mathieu.madtrainer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    /** Valeur brute FTMS = niveau × échelle (10 selon la norme, 1 sur certaines consoles). */
    val resistanceScale: Int = 10,
    /** Envoyer la résistance cible sur 2 octets (SINT16) au lieu d'un UINT8. */
    val wideResistance: Boolean = false,
    val lastAddress: String? = null,
    val lastName: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("madtrainer", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    private fun read() = Settings(
        resistanceScale = prefs.getInt("resistanceScale", 10),
        wideResistance = prefs.getBoolean("wideResistance", false),
        lastAddress = prefs.getString("lastAddress", null),
        lastName = prefs.getString("lastName", null),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putInt("resistanceScale", s.resistanceScale)
            .putBoolean("wideResistance", s.wideResistance)
            .putString("lastAddress", s.lastAddress)
            .putString("lastName", s.lastName)
            .apply()
        _settings.value = s
    }
}
