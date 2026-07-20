package com.sergiosantos.meucarro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Armazenamento simples em SharedPreferences.
 * Guarda a quilometragem total por modo e o historico de abastecimentos.
 * Todas as distancias sao guardadas em METROS (Double) e convertidas para km na tela.
 */
object Storage {
    private const val PREFS = "meucarro_prefs"
    private const val KEY_METERS_PESSOAL = "meters_pessoal"
    private const val KEY_METERS_UBER = "meters_uber"
    private const val KEY_MODE = "current_mode"       // "", "PESSOAL", "UBER"
    private const val KEY_FILLS = "fills"             // JSONArray de abastecimentos

    const val MODE_NONE = ""
    const val MODE_PESSOAL = "PESSOAL"
    const val MODE_UBER = "UBER"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMetersPessoal(c: Context): Double =
        Double.fromBits(p(c).getLong(KEY_METERS_PESSOAL, 0L))

    fun getMetersUber(c: Context): Double =
        Double.fromBits(p(c).getLong(KEY_METERS_UBER, 0L))

    fun addMeters(c: Context, mode: String, meters: Double) {
        if (meters <= 0.0) return
        val e = p(c).edit()
        when (mode) {
            MODE_PESSOAL -> e.putLong(KEY_METERS_PESSOAL, (getMetersPessoal(c) + meters).toRawBits())
            MODE_UBER -> e.putLong(KEY_METERS_UBER, (getMetersUber(c) + meters).toRawBits())
        }
        e.apply()
    }

    fun getMode(c: Context): String = p(c).getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
    fun setMode(c: Context, mode: String) { p(c).edit().putString(KEY_MODE, mode).apply() }

    fun resetTotals(c: Context) {
        p(c).edit()
            .putLong(KEY_METERS_PESSOAL, 0L)
            .putLong(KEY_METERS_UBER, 0L)
            .apply()
    }

    // ---- Abastecimentos (tanque) ----

    fun getFills(c: Context): JSONArray {
        val s = p(c).getString(KEY_FILLS, "[]") ?: "[]"
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    /** Registra um abastecimento com um "marco zero" da quilometragem atual. */
    fun addFill(c: Context, liters: Double, price: Double) {
        val arr = getFills(c)
        val o = JSONObject()
        o.put("timestamp", System.currentTimeMillis())
        o.put("liters", liters)
        o.put("price", price)
        o.put("meters_pessoal_at_fill", getMetersPessoal(c))
        o.put("meters_uber_at_fill", getMetersUber(c))
        arr.put(o)
        p(c).edit().putString(KEY_FILLS, arr.toString()).apply()
    }

    /** Ultimo abastecimento registrado, ou null. */
    fun lastFill(c: Context): JSONObject? {
        val arr = getFills(c)
        if (arr.length() == 0) return null
        return arr.getJSONObject(arr.length() - 1)
    }
}
