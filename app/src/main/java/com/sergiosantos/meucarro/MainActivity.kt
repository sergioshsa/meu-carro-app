package com.sergiosantos.meucarro

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUsageWarn: TextView
    private lateinit var tvMonth: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvTank: TextView
    private lateinit var etCalcLiters: EditText
    private lateinit var etCalcKm: EditText
    private lateinit var tvCalcResult: TextView

    private var pendingAuto = false
    private var selectedYm = Storage.currentYm()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(Storage.exportJson(this).toByteArray()) }
                Toast.makeText(this, "Backup salvo com sucesso", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao salvar o backup", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (Storage.importJson(this, text)) {
                    Toast.makeText(this, "Backup restaurado", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, "Arquivo de backup inválido", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao importar o backup", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val REQ_LOCATION = 100
    private val REQ_NOTIF = 101

    private val monthNames = arrayOf(
        "Janeiro","Fevereiro","Março","Abril","Maio","Junho",
        "Julho","Agosto","Setembro","Outubro","Novembro","Dezembro"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvUsageWarn = findViewById(R.id.tvUsageWarn)
        tvMonth = findViewById(R.id.tvMonth)
        tvTotals = findViewById(R.id.tvTotals)
        tvTank = findViewById(R.id.tvTank)
        etCalcLiters = findViewById(R.id.etCalcLiters)
        etCalcKm = findViewById(R.id.etCalcKm)
        tvCalcResult = findViewById(R.id.tvCalcResult)

        findViewById<Button>(R.id.btnAuto).setOnClickListener { startAuto() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTracking() }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { openUsageAccess() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testDetection() }
        findViewById<Button>(R.id.btnAbastecer).setOnClickListener { showFillDialog() }
        findViewById<Button>(R.id.btnCalc).setOnClickListener { calcTank() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        findViewById<Button>(R.id.btnPrevMonth).setOnClickListener { selectedYm = shiftYm(selectedYm, -1); refresh() }
        findViewById<Button>(R.id.btnNextMonth).setOnClickListener { selectedYm = shiftYm(selectedYm, 1); refresh() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLauncher.launch("meucarro_backup.json") }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importLauncher.launch(arrayOf("application/json", "*/*")) }

        askNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Atalhos de voz (opcionais) ainda funcionam para fixar um modo manual. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val comp = intent.component?.className ?: ""
        val host = intent.data?.host ?: ""
        val extra = intent.getStringExtra(TrackingService.EXTRA_MODE) ?: ""
        val mode = when {
            comp.endsWith("AliasUber") || host.equals("uber", true) || extra == Storage.MODE_UBER -> Storage.MODE_UBER
            comp.endsWith("AliasPessoal") || host.equals("pessoal", true) || extra == Storage.MODE_PESSOAL -> Storage.MODE_PESSOAL
            else -> null
        }
        if (mode != null) startFixed(mode)
    }

    private fun startAuto() {
        if (!hasLocation()) {
            pendingAuto = true
            requestLocation()
            return
        }
        if (!hasUsageAccess()) {
            AlertDialog.Builder(this)
                .setTitle("Falta o Acesso de uso")
                .setMessage("Para detectar quando o Uber Driver está em uso, ative o \"Acesso de uso\" para o Meu Carro. Vou abrir a tela agora.")
                .setPositiveButton("Abrir") { _, _ -> openUsageAccess() }
                .setNegativeButton("Continuar assim", null)
                .show()
        }
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_MODE, Storage.MODE_AUTO)
        ContextCompat.startForegroundService(this, i)
        Toast.makeText(this, "Modo automático ativado", Toast.LENGTH_SHORT).show()
        tvStatus.postDelayed({ refresh() }, 500)
    }

    private fun startFixed(mode: String) {
        if (!hasLocation()) { requestLocation(); return }
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_MODE, mode)
        ContextCompat.startForegroundService(this, i)
        val label = if (mode == Storage.MODE_UBER) "UBER" else "PESSOAL"
        Toast.makeText(this, "Modo $label (manual) iniciado", Toast.LENGTH_SHORT).show()
        tvStatus.postDelayed({ refresh() }, 400)
    }

    private fun stopTracking() {
        val i = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
        startService(i)
        Storage.setMode(this, Storage.MODE_NONE)
        Toast.makeText(this, "Registro parado", Toast.LENGTH_SHORT).show()
        tvStatus.postDelayed({ refresh() }, 300)
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION
        )
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                appOps.unsafeCheckOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            else
                appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }

    private fun openUsageAccess() {
        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        catch (e: Exception) { Toast.makeText(this, "Abra: Configurações > Acesso de uso", Toast.LENGTH_LONG).show() }
    }

    private fun uberLastUsed(): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24L * 60 * 60 * 1000, now)
            var last = 0L
            if (stats != null) for (s in stats) {
                if (s.packageName == "com.ubercab.driver" && s.lastTimeUsed > last) last = s.lastTimeUsed
            }
            last
        } catch (e: Exception) { 0L }
    }

    private fun recentUber(): Boolean {
        if (!hasUsageAccess()) return false
        val last = uberLastUsed()
        return last > 0 && (System.currentTimeMillis() - last) <= 10 * 60 * 1000L
    }

    private fun testDetection() {
        val access = hasUsageAccess()
        val last = uberLastUsed()
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append(if (access) "OK - Acesso de uso: CONCEDIDO\n\n"
                  else "FALTA - Acesso de uso NAO concedido.\nToque em \"Conceder acesso de uso\" e ative o Meu Carro na lista.\n\n")
        if (last <= 0L) sb.append("Uber Driver: nenhum uso detectado nas ultimas 24h.\n")
        else sb.append("Uber Driver usado ha " + ((now - last) / 60000) + " min.\n")
        val detected = if (access && last > 0 && (now - last) <= 10 * 60 * 1000L) "UBER" else "PESSOAL"
        sb.append("\nModo detectado agora: " + detected)
        AlertDialog.Builder(this).setTitle("Teste de deteccao").setMessage(sb.toString())
            .setPositiveButton("OK", null).show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingAuto) { pendingAuto = false; startAuto() }
            } else {
                Toast.makeText(this, "Preciso da localização para registrar os km.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- Abastecimento (marca ponto zero do tanque) ----------------
    private fun showFillDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val etLiters = EditText(ctx).apply { hint = "Litros abastecidos (ex: 40)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val etPrice = EditText(ctx).apply { hint = "Valor total pago R$ (ex: 250)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        container.addView(etLiters); container.addView(etPrice)
        AlertDialog.Builder(ctx)
            .setTitle("Registrar abastecimento")
            .setMessage("Marca o ponto zero do tanque. A partir daqui conto os km de cada modo.")
            .setView(container)
            .setPositiveButton("Salvar") { _, _ ->
                val liters = etLiters.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val price = etPrice.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                Storage.addFill(ctx, liters, price)
                Toast.makeText(ctx, "Tanque registrado. Contagem zerada.", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Calculadora do tanque (litros + km) ----------------
    private fun calcTank() {
        val liters = etCalcLiters.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
        val kmTotal = etCalcKm.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
        if (kmTotal <= 0.0) {
            tvCalcResult.text = "Informe os km rodados com o tanque."
            return
        }
        // proporção Uber x Pessoal: usa o rastreado desde o último abastecimento; se não houver, usa o total geral
        val fill = Storage.lastFill(this)
        val mP: Double
        val mU: Double
        if (fill != null) {
            mP = Storage.getMetersPessoal(this) - fill.optDouble("meters_pessoal_at_fill", 0.0)
            mU = Storage.getMetersUber(this) - fill.optDouble("meters_uber_at_fill", 0.0)
        } else {
            mP = Storage.getMetersPessoal(this); mU = Storage.getMetersUber(this)
        }
        val tracked = mP + mU
        val loc = Locale("pt", "BR")
        val sb = StringBuilder()
        if (tracked <= 0.0) {
            // sem rastreamento: divide meio a meio e avisa
            val metade = kmTotal / 2.0
            sb.append("Ainda não há rastreamento de km por modo neste tanque.\n")
            sb.append(String.format(loc, "Consumo: %.2f km/L\n", if (liters > 0) kmTotal / liters else 0.0))
            sb.append(String.format(loc, "Sem base para dividir: %.1f km cada (estimativa 50/50).", metade))
        } else {
            val ratioU = mU / tracked
            val kmUber = kmTotal * ratioU
            val kmPessoal = kmTotal - kmUber
            sb.append(String.format(loc, "Consumo médio: %.2f km/L\n", if (liters > 0) kmTotal / liters else 0.0))
            sb.append(String.format(loc, "Uber: %.1f km (%.0f%%)\n", kmUber, ratioU * 100))
            sb.append(String.format(loc, "Pessoal: %.1f km (%.0f%%)\n", kmPessoal, (1 - ratioU) * 100))
            if (liters > 0) {
                sb.append(String.format(loc, "Litros Uber: %.1f L | Litros Pessoal: %.1f L", liters * ratioU, liters * (1 - ratioU)))
            }
        }
        tvCalcResult.text = sb.toString()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Zerar totais e meses?")
            .setMessage("Apaga a quilometragem acumulada (total e por mês) de Pessoal e Uber. O histórico de abastecimentos é mantido.")
            .setPositiveButton("Zerar") { _, _ -> Storage.resetTotals(this); refresh() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Tela ----------------
    private fun shiftYm(ym: String, delta: Int): String {
        return try {
            val parts = ym.split("-")
            var y = parts[0].toInt()
            var m = parts[1].toInt() + delta
            while (m < 1) { m += 12; y-- }
            while (m > 12) { m -= 12; y++ }
            String.format("%04d-%02d", y, m)
        } catch (e: Exception) { Storage.currentYm() }
    }

    private fun monthLabel(ym: String): String {
        return try {
            val parts = ym.split("-")
            val m = parts[1].toInt()
            "${monthNames[m - 1]}/${parts[0]}"
        } catch (e: Exception) { ym }
    }

    private fun refresh() {
        val loc = Locale("pt", "BR")
        val kmP = Storage.getMetersPessoal(this) / 1000.0
        val kmU = Storage.getMetersUber(this) / 1000.0

        val mode = Storage.getMode(this)
        val auto = Storage.isAutoEnabled(this)
        tvStatus.text = if (auto) {
            val live = if (recentUber()) "UBER" else "PESSOAL"
            "● AUTOMÁTICO ligado — detectando agora: $live"
        } else when (mode) {
            Storage.MODE_UBER -> "● REGISTRANDO: UBER (manual)"
            Storage.MODE_PESSOAL -> "● REGISTRANDO: PESSOAL (manual)"
            else -> "○ Parado (toque em Ativar modo automático)"
        }

        tvUsageWarn.text = if (!hasUsageAccess())
            "⚠ Acesso de uso não concedido: sem ele, tudo conta como Pessoal. Toque em \"Conceder acesso de uso\"."
        else "✓ Acesso de uso concedido."

        // Mês selecionado (navegável com os botões)
        val ym = selectedYm
        val mp = Storage.getMonthMeters(this, Storage.MODE_PESSOAL, ym) / 1000.0
        val mu = Storage.getMonthMeters(this, Storage.MODE_UBER, ym) / 1000.0
        val atual = if (ym == Storage.currentYm()) "  (mês atual)" else ""
        tvMonth.text = String.format(loc,
            "MÊS: %s%s\nPessoal: %.2f km\nUber: %.2f km\nTotal: %.2f km",
            monthLabel(ym), atual, mp, mu, mp + mu)

        // Totais + histórico por mês
        val sb = StringBuilder()
        sb.append(String.format(loc, "TOTAL GERAL\nPessoal: %.2f km\nUber: %.2f km\nSoma: %.2f km", kmP, kmU, kmP + kmU))
        val months = Storage.getMonths(this)
        if (months.size > 1 || (months.size == 1 && months[0] != ym)) {
            sb.append("\n\nHISTÓRICO POR MÊS")
            for (m in months) {
                val p = Storage.getMonthMeters(this, Storage.MODE_PESSOAL, m) / 1000.0
                val u = Storage.getMonthMeters(this, Storage.MODE_UBER, m) / 1000.0
                sb.append(String.format(loc, "\n%s: Pessoal %.1f | Uber %.1f", monthLabel(m), p, u))
            }
        }
        tvTotals.text = sb.toString()

        // Tanque
        val fill = Storage.lastFill(this)
        if (fill == null) {
            tvTank.text = "TANQUE ATUAL\nNenhum abastecimento registrado.\nUse \"Registrar abastecimento\" ao encher o tanque, ou a calculadora abaixo."
        } else {
            val mP0 = fill.optDouble("meters_pessoal_at_fill", 0.0)
            val mU0 = fill.optDouble("meters_uber_at_fill", 0.0)
            val liters = fill.optDouble("liters", 0.0)
            val price = fill.optDouble("price", 0.0)
            val ts = fill.optLong("timestamp", 0L)
            val kmPTank = (Storage.getMetersPessoal(this) - mP0) / 1000.0
            val kmUTank = (Storage.getMetersUber(this) - mU0) / 1000.0
            val kmTotalTank = kmPTank + kmUTank
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", loc)
            val t = StringBuilder()
            t.append("TANQUE ATUAL (desde ").append(sdf.format(Date(ts))).append(")\n")
            t.append(String.format(loc, "Pessoal: %.2f km\nUber: %.2f km\nTotal rodado: %.2f km\n", kmPTank, kmUTank, kmTotalTank))
            if (liters > 0) t.append(String.format(loc, "Consumo: %.2f km/L\n", if (liters > 0) kmTotalTank / liters else 0.0))
            if (price > 0 && kmTotalTank > 0) {
                val custoKm = price / kmTotalTank
                t.append(String.format(loc, "Custo/km: R$ %.2f\nGasto Pessoal: R$ %.2f | Gasto Uber: R$ %.2f", custoKm, custoKm * kmPTank, custoKm * kmUTank))
            }
            tvTank.text = t.toString()
        }
    }
}
