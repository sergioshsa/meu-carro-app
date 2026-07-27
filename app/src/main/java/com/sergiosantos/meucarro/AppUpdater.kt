package com.sergiosantos.meucarro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifica a versão mais recente publicada no GitHub Releases e instala
 * o novo APK sem o usuário precisar abrir o navegador manualmente.
 * O CI grava o número do build no versionCode do app, então basta
 * comparar o build instalado com o número do último release.
 */
object AppUpdater {
    private const val API_URL = "https://api.github.com/repos/sergioshsa/meu-carro-app/releases/latest"

    data class ReleaseInfo(val build: Int, val apkUrl: String, val tag: String)

    interface CheckCallback { fun onChecked(current: Int, remote: ReleaseInfo?, error: String?) }
    interface ProgressCallback { fun onDone(success: Boolean, message: String) }

    fun currentVersionCode(c: Context): Int {
        return try {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (e: Exception) { 0 }
    }

    /** Consulta o GitHub e informa se existe um build mais novo que o instalado. */
    fun check(c: Context, cb: CheckCallback) {
        val current = currentVersionCode(c)
        val act = c as? Activity
        Thread {
            var remote: ReleaseInfo? = null
            var error: String? = null
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code in 200..299) {
                    val o = JSONObject(body)
                    val tag = o.optString("tag_name", "")
                    val build = tag.substringAfterLast("-").toIntOrNull() ?: 0
                    var apkUrl: String? = null
                    val assets = o.optJSONArray("assets")
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk")) { apkUrl = a.optString("browser_download_url", ""); break }
                    }
                    if (build > 0 && !apkUrl.isNullOrEmpty()) remote = ReleaseInfo(build, apkUrl, tag)
                    else error = "Resposta da nuvem sem APK válido"
                } else error = "Erro ao consultar (HTTP $code)"
            } catch (e: Exception) { error = "Sem conexão com a internet" }
            val r = remote; val e = error
            if (act != null) act.runOnUiThread { cb.onChecked(current, r, e) } else cb.onChecked(current, r, e)
        }.start()
    }

    /** Baixa o APK mais novo e abre a tela de instalação do Android. */
    fun downloadAndInstall(c: Context, apkUrl: String, cb: ProgressCallback) {
        val act = c as? Activity
        Thread {
            var success = false
            var message = "Falha ao baixar a atualização"
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code in 200..299) {
                    val dir = c.getExternalFilesDir(null) ?: c.filesDir
                    val file = File(dir, "meucarro_update.apk")
                    conn.inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
                    conn.disconnect()
                    success = true
                    message = "Atualização baixada"
                    if (act != null) act.runOnUiThread { installApk(act, file) }
                } else {
                    message = "Erro ao baixar (HTTP $code)"
                }
            } catch (e: Exception) {
                message = "Erro ao baixar: ${e.message}"
            }
            val s = success; val m = message
            if (act != null) act.runOnUiThread { cb.onDone(s, m) } else cb.onDone(s, m)
        }.start()
    }

    private fun installApk(act: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.startActivity(install)
        } catch (e: Exception) { /* ignora */ }
    }

    /** Verdadeiro se o Android já autoriza este app a instalar outros APKs. */
    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()
}
