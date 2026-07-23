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
    private const val KEY_SYNC_CODE = "sync_code"
    private const val KEY_DESTINATIONS = "destinations"
    private const val KEY_CUR_DEST = "cur_dest"
    private const val KEY_DEST_LOCS = "dest_locs"

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

    fun getSyncCode(c: Context): String = p(c).getString(KEY_SYNC_CODE, "") ?: ""
    fun setSyncCode(c: Context, v: String) { p(c).edit().putString(KEY_SYNC_CODE, v).apply() }

    /** Garante que sempre exista um código (gera um automático na 1ª vez). */
    fun ensureSyncCode(c: Context): String {
        var s = getSyncCode(c)
        if (s.isEmpty()) {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val r = java.util.Random()
            val sb = StringBuilder("mc-")
            repeat(6) { sb.append(chars[r.nextInt(chars.length)]) }
            s = sb.toString()
            setSyncCode(c, s)
        }
        return s
    }

    /** Verdadeiro quando não há nada salvo localmente (ex.: logo após reinstalar). */
    fun isLocalEmpty(c: Context): Boolean =
        getMonths(c).isEmpty() && getFills(c).length() == 0 &&
        getMetersPessoal(c) == 0.0 && getMetersUber(c) == 0.0

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

    // ---- Destinos / viagens ----

    private fun destMetersKey(name: String, ym: String) = "dm_${name}_$ym"
    private fun destCountKey(name: String, ym: String) = "dc_${name}_$ym"

    fun getCurDest(c: Context): String = p(c).getString(KEY_CUR_DEST, "") ?: ""
    fun setCurDest(c: Context, name: String) { p(c).edit().putString(KEY_CUR_DEST, name).apply() }

    fun getDestinations(c: Context): List<String> {
        val s = p(c).getString(KEY_DESTINATIONS, "[]") ?: "[]"
        val arr = try { JSONArray(s) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun addDestination(c: Context, name: String) {
        if (name.isBlank()) return
        val list = getDestinations(c)
        if (list.contains(name)) return
        val arr = JSONArray()
        for (n in list) arr.put(n)
        arr.put(name)
        p(c).edit().putString(KEY_DESTINATIONS, arr.toString()).apply()
    }

    private fun getDestLocsArr(c: Context): JSONArray {
        val sVal = p(c).getString(KEY_DEST_LOCS, "[]") ?: "[]"
        return try { JSONArray(sVal) } catch (e: Exception) { JSONArray() }
    }

    /** Procura um destino já salvo perto (metros) desta coordenada. Retorna o nome ou null. */
    fun findNearbyDest(c: Context, lat: Double, lon: Double, thresholdM: Float): String? {
        val arr = getDestLocsArr(c)
        val res = FloatArray(1)
        var best: String? = null
        var bestDist = thresholdM
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            android.location.Location.distanceBetween(lat, lon, o.optDouble("lat"), o.optDouble("lon"), res)
            if (res[0] <= bestDist) { bestDist = res[0]; best = o.optString("name") }
        }
        return best
    }

    /** Salva a coordenada de um destino novo. */
    fun createDestLoc(c: Context, name: String, lat: Double, lon: Double) {
        val arr = getDestLocsArr(c)
        val o = JSONObject().put("name", name).put("lat", lat).put("lon", lon)
        arr.put(o)
        p(c).edit().putString(KEY_DEST_LOCS, arr.toString()).apply()
        addDestination(c, name)
    }

    /** Renomeia um destino (na lista de localizacoes e migra os dados de km/contagem). */
    fun renameDest(c: Context, oldName: String, newName: String) {
        if (oldName == newName || newName.isBlank()) return
        // localizacoes
        val arr = getDestLocsArr(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") == oldName) o.put("name", newName)
        }
        p(c).edit().putString(KEY_DEST_LOCS, arr.toString()).apply()
        // lista de nomes
        val names = getDestinations(c).toMutableList()
        val idx = names.indexOf(oldName)
        if (idx >= 0) { names[idx] = newName } else { names.add(newName) }
        val narr = JSONArray(); for (n in names) narr.put(n)
        p(c).edit().putString(KEY_DESTINATIONS, narr.toString()).apply()
        // migra meses (km e contagem) de todos os meses conhecidos
        val e = p(c).edit()
        for (ym in getMonths(c)) {
            val m = getDestMeters(c, oldName, ym); val cnt = getDestCount(c, oldName, ym)
            if (m > 0) e.putLong(destMetersKey(newName, ym), (getDestMeters(c, newName, ym) + m).toRawBits())
            if (cnt > 0) e.putInt(destCountKey(newName, ym), getDestCount(c, newName, ym) + cnt)
            e.remove(destMetersKey(oldName, ym)); e.remove(destCountKey(oldName, ym))
        }
        e.apply()
    }

    fun getDestMeters(c: Context, name: String, ym: String): Double =
        Double.fromBits(p(c).getLong(destMetersKey(name, ym), 0L))

    fun getDestCount(c: Context, name: String, ym: String): Int =
        p(c).getInt(destCountKey(name, ym), 0)

    fun addDestMeters(c: Context, name: String, meters: Double) {
        if (name.isBlank() || meters <= 0.0) return
        val ym = currentYm()
        val cur = getDestMeters(c, name, ym)
        p(c).edit().putLong(destMetersKey(name, ym), (cur + meters).toRawBits()).apply()
        registerMonth(c, ym)
    }

    /** Conta +1 viagem para o destino no mes atual (e salva o destino na lista). */
    fun incDestTrip(c: Context, name: String) {
        if (name.isBlank()) return
        addDestination(c, name)
        val ym = currentYm()
        val cur = getDestCount(c, name, ym)
        p(c).edit().putInt(destCountKey(name, ym), cur + 1).apply()
        registerMonth(c, ym)
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

    // ---- Backup (exportar / importar) ----

    fun exportJson(c: Context): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("exported_at", System.currentTimeMillis())
        o.put("meters_pessoal", getMetersPessoal(c))
        o.put("meters_uber", getMetersUber(c))
        val marr = JSONArray()
        val mdata = JSONObject()
        for (ym in getMonths(c)) {
            marr.put(ym)
            mdata.put(ym + "|P", getMonthMeters(c, MODE_PESSOAL, ym))
            mdata.put(ym + "|U", getMonthMeters(c, MODE_UBER, ym))
        }
        o.put("months", marr)
        o.put("months_data", mdata)
        val darr = JSONArray()
        val dd = JSONObject()
        for (n in getDestinations(c)) {
            darr.put(n)
            val perMonth = JSONObject()
            for (ym in getMonths(c)) {
                val m = getDestMeters(c, n, ym)
                val cnt = getDestCount(c, n, ym)
                if (m > 0 || cnt > 0) perMonth.put(ym, JSONObject().put("m", m).put("c", cnt))
            }
            if (perMonth.length() > 0) dd.put(n, perMonth)
        }
        o.put("destinations", darr)
        o.put("dest_data", dd)
        o.put("fills", getFills(c))
        return o.toString(2)
    }

    fun importJson(c: Context, text: String): Boolean {
        return try {
            val o = JSONObject(text)
            val e = p(c).edit()
            e.putLong(KEY_METERS_PESSOAL, o.optDouble("meters_pessoal", 0.0).toRawBits())
            e.putLong(KEY_METERS_UBER, o.optDouble("meters_uber", 0.0).toRawBits())
            val marr = o.optJSONArray("months") ?: JSONArray()
            e.putString(KEY_MONTHS, marr.toString())
            val mdata = o.optJSONObject("months_data") ?: JSONObject()
            for (i in 0 until marr.length()) {
                val y = marr.getString(i)
                e.putLong(monthKey(MODE_PESSOAL, y), mdata.optDouble(y + "|P", 0.0).toRawBits())
                e.putLong(monthKey(MODE_UBER, y), mdata.optDouble(y + "|U", 0.0).toRawBits())
            }
            val darr = o.optJSONArray("destinations") ?: JSONArray()
            e.putString(KEY_DESTINATIONS, darr.toString())
            val dd = o.optJSONObject("dest_data") ?: JSONObject()
            val dkeys = dd.keys()
            while (dkeys.hasNext()) {
                val n = dkeys.next()
                val perMonth = dd.getJSONObject(n)
                val yms = perMonth.keys()
                while (yms.hasNext()) {
                    val ym = yms.next()
                    val obj = perMonth.getJSONObject(ym)
                    e.putLong(destMetersKey(n, ym), obj.optDouble("m", 0.0).toRawBits())
                    e.putInt(destCountKey(n, ym), obj.optInt("c", 0))
                }
            }
            val fills = o.optJSONArray("fills")
            if (fills != null) e.putString(KEY_FILLS, fills.toString())
            e.apply()
            true
        } catch (ex: Exception) { false }
    }

    fun lastFill(c: Context): JSONObject? {
        val arr = getFills(c)
        if (arr.length() == 0) return null
        return arr.getJSONObject(arr.length() - 1)
    }

    /** Remove um abastecimento pelo índice (0 = mais antigo). */
    fun removeFill(c: Context, index: Int) {
        val arr = getFills(c)
        if (index < 0 || index >= arr.length()) return
        val novo = JSONArray()
        for (i in 0 until arr.length()) if (i != index) novo.put(arr.getJSONObject(i))
        p(c).edit().putString(KEY_FILLS, novo.toString()).apply()
    }
}
