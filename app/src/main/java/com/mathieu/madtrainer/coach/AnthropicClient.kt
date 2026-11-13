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

    suspend fun complete(apiKey: String, model: String, prompt: String, maxTokens: Int = 16000): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model.ifBlank { DEFAULT_MODEL })
                put("max_tokens", maxTokens)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", prompt)
                            },
                        )
                    },
                )
            }.toString()

            val conn = (URL(URL_MESSAGES).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 240_000 // un plan complet peut prendre 1 à 3 minutes
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
                root?.get("content")?.jsonArray
                    ?.mapNotNull { block ->
                        val o = block.jsonObject
                        if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") o["text"]?.jsonPrimitive?.contentOrNull else null
                    }
                    ?.joinToString("\n")
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Réponse vide de l'API")
            } finally {
                conn.disconnect()
            }
        }
}
