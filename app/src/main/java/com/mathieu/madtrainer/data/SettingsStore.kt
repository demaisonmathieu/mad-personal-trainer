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
    /** Cardiofréquencemètre / montre mémorisé. */
    val hrAddress: String? = null,
    val hrName: String? = null,
    /** Coach IA : clé API Anthropic (optionnelle) et modèle. */
    val anthropicKey: String = "",
    val anthropicModel: String = "claude-sonnet-5-5",
    /** Strava : application déclarée par l'utilisateur + jetons OAuth. */
    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val stravaAccess: String = "",
    val stravaRefresh: String = "",
    val stravaExpiresAt: Long = 0,
    val includeStrava: Boolean = true,
    /** Mode global de l'app. */
    val sport: Sport = Sport.ELLIPTICAL,
) {
    val stravaConnected: Boolean get() = stravaRefresh.isNotBlank()
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("madtrainer", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings

    private fun read() = Settings(
        resistanceScale = prefs.getInt("resistanceScale", 10),
        wideResistance = prefs.getBoolean("wideResistance", false),
        lastAddress = prefs.getString("lastAddress", null),
        lastName = prefs.getString("lastName", null),
        hrAddress = prefs.getString("hrAddress", null),
        hrName = prefs.getString("hrName", null),
        anthropicKey = prefs.getString("anthropicKey", "") ?: "",
        anthropicModel = prefs.getString("anthropicModel", "claude-sonnet-5-5") ?: "claude-sonnet-5-5",
        stravaClientId = prefs.getString("stravaClientId", "") ?: "",
        stravaClientSecret = prefs.getString("stravaClientSecret", "") ?: "",
        stravaAccess = prefs.getString("stravaAccess", "") ?: "",
        stravaRefresh = prefs.getString("stravaRefresh", "") ?: "",
        stravaExpiresAt = prefs.getLong("stravaExpiresAt", 0),
        includeStrava = prefs.getBoolean("includeStrava", true),
        sport = runCatching { Sport.valueOf(prefs.getString("sport", "ELLIPTICAL")!!) }.getOrDefault(Sport.ELLIPTICAL),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putInt("resistanceScale", s.resistanceScale)
            .putBoolean("wideResistance", s.wideResistance)
            .putString("lastAddress", s.lastAddress)
            .putString("lastName", s.lastName)
            .putString("hrAddress", s.hrAddress)
            .putString("hrName", s.hrName)
            .putString("anthropicKey", s.anthropicKey)
            .putString("anthropicModel", s.anthropicModel)
            .putString("stravaClientId", s.stravaClientId)
            .putString("stravaClientSecret", s.stravaClientSecret)
            .putString("stravaAccess", s.stravaAccess)
            .putString("stravaRefresh", s.stravaRefresh)
            .putLong("stravaExpiresAt", s.stravaExpiresAt)
            .putBoolean("includeStrava", s.includeStrava)
            .putString("sport", s.sport.name)
            .apply()
        _settings.value = s
    }
}
