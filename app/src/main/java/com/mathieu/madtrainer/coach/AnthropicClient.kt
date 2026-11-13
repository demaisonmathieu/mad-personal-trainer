package com.mathieu.madtrainer.coach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** Appel direct à l'API Messages d'Anthropic (clé saisie par l'utilisateur dans l'app). */
object AnthropicClient {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Envoie le prompt et renvoie la réponse complète. Si la réponse est coupée par la limite de longueur
     * (stop_reason = max_tokens), on demande la suite (jusqu'à 2 fois) en reprenant le texte déjà reçu.
     */
    suspend fun complete(apiKey: String, model: String, prompt: String, maxTokens: Int = 16000): String {
        var text = ""
        repeat(3) { attempt ->
            val messages = buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
                // Pré-remplissage : le modèle continue exactement là où il s'était arrêté
                if (attempt > 0) add(buildJsonObject { put("role", "assistant"); put("content", text.trimEnd()) })
            }
            val (part, stop) = callOnce(apiKey, model, messages, maxTokens)
            text = if (attempt == 0) part else text.trimEnd() + part
            if (stop != "max_tokens") return text
        }
        return text
    }

    private suspend fun callOnce(
        apiKey: String,
        model: String,
        messages: kotlinx.serialization.json.JsonArray,
        maxTokens: Int,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("max_tokens", maxTokens)
            put("messages", messages)
        }.toString()

        val conn = (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 300_000 // un plan complet peut prendre plusieurs minutes
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey.trim())
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (code !in 200..299) {
                val msg = root?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                error(
                    when (code) {
                        401 -> "Clé API invalide"
                        404 -> "Modèle inconnu : ${model.ifBlank { DEFAULT_MODEL }}"
                        429 -> "Trop de requêtes, réessaie dans un moment"
                        529 -> "API surchargée, réessaie dans un moment"
                        else -> "Erreur API $code${msg?.let { " : $it" } ?: ""}"
                    },
                )
            }
            val out = root?.get("content")?.jsonArray
                ?.mapNotNull { block ->
                    val o = block.jsonObject
                    if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
                }
                ?.joinToString("\n")
                ?: error("Réponse vide de l'API")
            val stop = root?.get("stop_reason")?.jsonPrimitive?.contentOrNull
            out to stop
        } finally {
            conn.disconnect()
        }
    }
}
