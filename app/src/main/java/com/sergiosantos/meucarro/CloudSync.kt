package com.sergiosantos.meucarro

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sincronização na nuvem via Firestore (REST) com login anônimo automático.
 * Não usa arquivos de credencial: só a chave pública da API e login anônimo.
 * Os dados ficam num documento cujo ID é o "código de sincronização" do usuário.
 */
object CloudSync {
    private const val API_KEY = "AIzaSyAMhFQxYSz9kBFIBKNuraCLhUVvGZp7iNs"
    private const val PROJECT_ID = "meu-carro-app-c1818"
    private const val COLLECTION = "sync"

    private var cachedToken: String? = null
    private var tokenTime: Long = 0L

    interface Callback { fun onResult(success: Boolean, message: String) }

    fun sanitizeCode(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")

    private fun anonToken(): String? {
        val t = cachedToken
        if (t != null && System.currentTimeMillis() - tokenTime < 50 * 60 * 1000L) return t
        val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write("{\"returnSecureToken\":true}") }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use(BufferedReader::readText)
            if (code in 200..299) {
                val tok = JSONObject(body).optString("idToken", "")
                if (tok.isNotEmpty()) { cachedToken = tok; tokenTime = System.currentTimeMillis(); tok } else null
            } else null
        } catch (e: Exception) { null } finally { conn.disconnect() }
    }

    private fun docUrl(code: String) =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/$COLLECTION/$code"

    /** Envia os dados locais para a nuvem (cria/atualiza o documento). */
    fun push(context: Context, codeRaw: String, cb: Callback?) {
        val code = sanitizeCode(codeRaw)
        val appCtx = context.applicationContext
        val act = context as? Activity
        Thread {
            val result: Pair<Boolean, String> = try {
                if (code.isEmpty()) Pair(false, "Defina um código de sincronização")
                else {
                    val token = anonToken()
                    if (token == null) Pair(false, "Falha ao autenticar na nuvem")
                    else {
                        val fields = JSONObject()
                        fields.put("data", JSONObject().put("stringValue", Storage.exportJson(appCtx)))
                        fields.put("updated", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
                        val docJson = JSONObject().put("fields", fields)
                        val conn = URL(docUrl(code)).openConnection() as HttpURLConnection
                        conn.requestMethod = "PATCH"
                        conn.doOutput = true
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        OutputStreamWriter(conn.outputStream).use { it.write(docJson.toString()) }
                        val rc = conn.responseCode
                        conn.disconnect()
                        if (rc in 200..299) Pair(true, "Enviado para a nuvem") else Pair(false, "Erro ao enviar ($rc)")
                    }
                }
            } catch (e: Exception) { Pair(false, "Sem conexão com a nuvem") }
            if (cb != null) {
                if (act != null) act.runOnUiThread { cb.onResult(result.first, result.second) }
                else cb.onResult(result.first, result.second)
            }
        }.start()
    }

    /** Baixa os dados da nuvem e restaura localmente. */
    fun pull(context: Context, codeRaw: String, cb: Callback?) {
        val code = sanitizeCode(codeRaw)
        val appCtx = context.applicationContext
        val act = context as? Activity
        Thread {
            val result: Pair<Boolean, String> = try {
                if (code.isEmpty()) Pair(false, "Defina um código de sincronização")
                else {
                    val token = anonToken()
                    if (token == null) Pair(false, "Falha ao autenticar na nuvem")
                    else {
                        val conn = URL(docUrl(code)).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        val rc = conn.responseCode
                        if (rc == 404) { conn.disconnect(); Pair(false, "Nenhum dado na nuvem para este código ainda") }
                        else {
                            val stream = if (rc in 200..299) conn.inputStream else conn.errorStream
                            val body = stream.bufferedReader().use(BufferedReader::readText)
                            conn.disconnect()
                            if (rc in 200..299) {
                                val data = JSONObject(body).optJSONObject("fields")
                                    ?.optJSONObject("data")?.optString("stringValue", "") ?: ""
                                if (data.isNotEmpty() && Storage.importJson(appCtx, data)) Pair(true, "Dados baixados da nuvem")
                                else Pair(false, "Dado da nuvem inválido")
                            } else Pair(false, "Erro ao baixar ($rc)")
                        }
                    }
                }
            } catch (e: Exception) { Pair(false, "Sem conexão com a nuvem") }
            if (cb != null) {
                if (act != null) act.runOnUiThread { cb.onResult(result.first, result.second) }
                else cb.onResult(result.first, result.second)
            }
        }.start()
    }
}
