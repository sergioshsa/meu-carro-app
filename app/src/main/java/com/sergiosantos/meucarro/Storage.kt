package com.sergiosantos.meucarro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Armazenamento em SharedPreferences.
 * Guarda km por modo (total e por mês) e o histórico de abastecimentos.
 * Distâncias em METROS (Double) convertidas para km na tela.
 */
object Storage {
    private const val PREFS = "meucarro_prefs"
    private const val KEY_METERS_PESSOAL = "meters_pessoal"
    private const val KEY_METERS_UBER = "meters_uber"
    private const val KEY_MODE = "current_mode"
    private const val KEY_FILLS = "fills"
    private const val KEY_MONTHS = "months_index"
    private const val KEY_AUTO = "auto_enabled"

    const val MODE_NONE = ""
    const val MODE_PESSOAL = "PESSOAL"
    const val MODE_UBER = "UBER"
    const val MODE_AUTO = "AUTO"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentYm(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun getMetersPessoal(c: Context): Double = Double.fromBits(p(c).getLong(KEY_METERS_PESSOAL, 0L))
    fun getMetersUber(c: Context): Double = Double.fromBits(p(c).getLong(KEY_METERS_UBER, 0L))

    private fun monthKey(mode: String, ym: String) = "m_${mode}_$ym"

    fun getMonthMeters(c: Context, mode: String, ym: String): Double =
        Double.fromBits(p(c).getLong(monthKey(mode, ym), 0L))

    fun addMeters(c: Context, mode: String, meters: Double) {
        if (meters <= 0.0) return
        if (mode != MODE_PESSOAL && mode != MODE_UBER) return
        val ym = currentYm()
        val e = p(c).edit()
        // total acumulado
        val totalKey = if (mode == MODE_UBER) KEY_METERS_UBER else KEY_METERS_PESSOAL
        val curTotal = Double.fromBits(p(c).getLong(totalKey, 0L))
        e.putLong(totalKey, (curTotal + meters).toRawBits())
        // por mês
        val mk = monthKey(mode, ym)
        val curMonth = Double.fromBits(p(c).getLong(mk, 0L))
        e.putLong(mk, (curMonth + meters).toRawBits())
        e.apply()
        registerMonth(c, ym)
    }

    private fun registerMonth(c: Context, ym: String) {
        val arr = getMonthsArray(c)
        var exists = false
        for (i in 0 until arr.length()) if (arr.getString(i) == ym) { exists = true; break }
        if (!exists) {
            arr.put(ym)
            p(c).edit().putString(KEY_MONTHS, arr.toString()).apply()
        }
    }

    private fun getMonthsArray(c: Context): JSONArray {
        val s = p(c).getString(KEY_MONTHS, "[]") ?: "[]"
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    /** Meses registrados, do mais recente para o mais antigo. */
    fun getMonths(c: Context): List<String> {
        val arr = getMonthsArray(c)
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        list.sortDescending()
        return list
    }

    fun getMode(c: Context): String = p(c).getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
    fun setMode(c: Context, mode: String) { p(c).edit().putString(KEY_MODE, mode).apply() }

    fun isAutoEnabled(c: Context): Boolean = p(c).getBoolean(KEY_AUTO, false)
    fun setAutoEnabled(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_AUTO, v).apply() }

    fun resetTotals(c: Context) {
        // zera totais, meses e índice de meses
        val e = p(c).edit()
        e.putLong(KEY_METERS_PESSOAL, 0L)
        e.putLong(KEY_METERS_UBER, 0L)
        for (ym in getMonths(c)) {
            e.remove(monthKey(MODE_PESSOAL, ym))
            e.remove(monthKey(MODE_UBER, ym))
        }
        e.remove(KEY_MONTHS)
        e.apply()
    }

    // ---- Abastecimentos ----
    fun getFills(c: Context): JSONArray {
        val s = p(c).getString(KEY_FILLS, "[]") ?: "[]"
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

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

    fun lastFill(c: Context): JSONObject? {
        val arr = getFills(c)
        if (arr.length() == 0) return null
        return arr.getJSONObject(arr.length() - 1)
    }
}
