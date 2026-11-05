package com.pecbox.streambox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

class ApiException(val code: Int, message: String) : Exception(message)

/** Executa a requisição fora da thread principal e devolve o corpo como texto. */
suspend fun OkHttpClient.fetch(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw ApiException(resp.code, errorMessage(resp.code, body))
        }
        body
    }
}

private fun errorMessage(code: Int, body: String): String {
    val fromJson = runCatching {
        val obj = AppJson.parseToJsonElement(body).jsonObject
        (obj["error"] ?: obj["message"])?.jsonPrimitive?.content
    }.getOrNull()
    if (!fromJson.isNullOrBlank()) return fromJson
    return when (code) {
        401 -> "Sessão expirada ou usuário/senha inválidos"
        403 -> "Acesso negado"
        404 -> "Não encontrado"
        429 -> "Muitas solicitações, tente mais tarde"
        in 500..599 -> "Servidor indisponível (HTTP $code)"
        else -> "Erro HTTP $code"
    }
}

/** Mensagem amigável para qualquer falha de rede. */
fun Throwable.friendly(): String = when (this) {
    is ApiException -> message ?: "Erro"
    is java.net.UnknownHostException -> "Servidor não encontrado. Confira o endereço."
    is java.net.SocketTimeoutException -> "O servidor demorou para responder."
    is java.net.ConnectException -> "Não foi possível conectar ao servidor."
    is javax.net.ssl.SSLException -> "Falha na conexão segura (HTTPS)."
    else -> message ?: javaClass.simpleName
}
