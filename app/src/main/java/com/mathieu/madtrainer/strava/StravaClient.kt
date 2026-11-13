package com.mathieu.madtrainer.strava

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * API Strava v3 (OAuth 2). L'utilisateur déclare sa propre application sur
 * https://www.strava.com/settings/api avec « Authorization Callback Domain » = localhost.
 */
object StravaClient {
    const val REDIRECT_URI = "madtrainer://localhost/strava"
    private const val API = "https://www.strava.com/api/v3"
    private const val TOKEN_URL = "https://www.strava.com/oauth/token"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** URL d'autorisation (ouvre l'app Strava si elle est installée, sinon le navigateur). */
    fun authorizeUri(clientId: String): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", clientId.trim())
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "read,profile:read_all,activity:read_all")
        .build()

    data class AuthResult(val tokens: StravaTokens, val athleteName: String)

    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): AuthResult =
        tokenRequest(
            mapOf(
                "client_id" to clientId.trim(),
                "client_secret" to clientSecret.trim(),
                "code" to code,
                "grant_type" to "authorization_code",
            ),
        )

    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): StravaTokens =
        tokenRequest(
            mapOf(
                "client_id" to clientId.trim(),
                "client_secret" to clientSecret.trim(),
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        ).tokens

    private suspend fun tokenRequest(params: Map<String, String>): AuthResult = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) error(if (code == 400 || code == 401) "Strava a refusé l'accès (ID / secret ou code invalides)" else "Erreur Strava $code")
            val tokens = json.decodeFromString(StravaTokens.serializer(), text)
            val athlete = runCatching { json.parseToJsonElement(text).jsonObject["athlete"]?.jsonObject }.getOrNull()
            val name = listOfNotNull(
                athlete?.get("firstname")?.jsonPrimitive?.contentOrNull,
                athlete?.get("lastname")?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ")
            AuthResult(tokens, name)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun get(path: String, accessToken: String): String = withContext(Dispatchers.IO) {
        val conn = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = conn.responseCode
            if (code == 401) error("Session Strava expirée, reconnecte-toi")
            if (code == 429) error("Limite de requêtes Strava atteinte, réessaie dans 15 min")
            if (code !in 200..299) error("Erreur Strava $code")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /** Poids déclaré sur le profil Strava (kg), s'il est renseigné. */
    suspend fun athleteWeight(accessToken: String): Double? {
        val o = json.parseToJsonElement(get("/athlete", accessToken)).jsonObject
        return o["weight"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }
    }

    /** Activités depuis [afterEpochSec], au plus [maxPages] pages de 100. */
    suspend fun activities(accessToken: String, afterEpochSec: Long, maxPages: Int = 3): List<StravaActivity> {
        val all = mutableListOf<StravaActivity>()
        for (page in 1..maxPages) {
            val text = get("/athlete/activities?after=$afterEpochSec&per_page=100&page=$page", accessToken)
            val list = json.decodeFromString(ListSerializer(StravaActivity.serializer()), text)
            all += list
            if (list.size < 100) break
        }
        return all.sortedByDescending { it.start_date }
    }
}
