package com.sergiosantos.meucarro

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnModeCircle: TextView
    private lateinit var ringGlow: View
    private var pulseAnim: ObjectAnimator? = null
    private var ringRotate: ObjectAnimator? = null
    private var ringPulse: ObjectAnimator? = null
    private var currentOrbKind = -1
    private lateinit var tvStatus: TextView
    private lateinit var tvUsageWarn: TextView
    private lateinit var tvMonth: TextView
    private lateinit var etDestFilter: EditText
    private lateinit var tvDestFilterResult: TextView
    private lateinit var llTrips: LinearLayout
    private lateinit var tvTotals: TextView
    private lateinit var tvTankMonthSummary: TextView
    private lateinit var tvTank: TextView
    private lateinit var tvFillHistory: TextView
    private lateinit var etCalcLiters: EditText
    private lateinit var etCalcKm: EditText
    private lateinit var tvCalcResult: TextView
    private lateinit var etSyncCode: EditText
    private lateinit var tvSyncStatus: TextView

    private lateinit var pageModo: View
    private lateinit var pageMeses: View
    private lateinit var pageTanque: View
    private lateinit var pageNuvem: View

    private var pendingAuto = false
    private var selectedYm = Storage.currentYm()
    private var selectedYmTank = Storage.currentYm()
    private val REQ_LOCATION = 100
    private val REQ_NOTIF = 101
    private val REQ_BG = 102

    private val monthNames = arrayOf(
        "Janeiro","Fevereiro","Março","Abril","Maio","Junho",
        "Julho","Agosto","Setembro","Outubro","Novembro","Dezembro"
    )

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
                    autoCloud()
                    Toast.makeText(this, "Backup restaurado", Toast.LENGTH_SHORT).show(); refresh()
                } else {
                    Toast.makeText(this, "Arquivo de backup inválido", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao importar o backup", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pageModo = findViewById(R.id.pageModo)
        pageMeses = findViewById(R.id.pageMeses)
        pageTanque = findViewById(R.id.pageTanque)
        pageNuvem = findViewById(R.id.pageNuvem)

        btnModeCircle = findViewById(R.id.btnModeCircle)
        ringGlow = findViewById(R.id.ringGlow)
        tvStatus = findViewById(R.id.tvStatus)
        tvUsageWarn = findViewById(R.id.tvUsageWarn)
        tvMonth = findViewById(R.id.tvMonth)
        etDestFilter = findViewById(R.id.etDestFilter)
        tvDestFilterResult = findViewById(R.id.tvDestFilterResult)
        llTrips = findViewById(R.id.llTrips)
        tvTotals = findViewById(R.id.tvTotals)
        tvTankMonthSummary = findViewById(R.id.tvTankMonthSummary)
        tvTank = findViewById(R.id.tvTank)
        tvFillHistory = findViewById(R.id.tvFillHistory)
        etCalcLiters = findViewById(R.id.etCalcLiters)
        etCalcKm = findViewById(R.id.etCalcKm)
        tvCalcResult = findViewById(R.id.tvCalcResult)
        etSyncCode = findViewById(R.id.etSyncCode)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        Storage.ensureSyncCode(this)
        etSyncCode.setText(Storage.getSyncCode(this))
        maybeAutoRestore()

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_modo -> showPage(pageModo)
                R.id.nav_meses -> showPage(pageMeses)
                R.id.nav_tanque -> showPage(pageTanque)
                R.id.nav_nuvem -> showPage(pageNuvem)
            }
            true
        }

        btnModeCircle.setOnClickListener { toggleMode() }
        findViewById<Button>(R.id.btnAuto).setOnClickListener { startAuto() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTracking() }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { openUsageAccess() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testDetection() }
        findViewById<Button>(R.id.btnPrevMonth).setOnClickListener { selectedYm = shiftYm(selectedYm, -1); refresh() }
        findViewById<Button>(R.id.btnNextMonth).setOnClickListener { selectedYm = shiftYm(selectedYm, 1); refresh() }
        findViewById<Button>(R.id.btnRenameDest).setOnClickListener { renameDestDialog() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }
        etDestFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        findViewById<Button>(R.id.btnPrevMonthTank).setOnClickListener { selectedYmTank = shiftYm(selectedYmTank, -1); refresh() }
        findViewById<Button>(R.id.btnNextMonthTank).setOnClickListener { selectedYmTank = shiftYm(selectedYmTank, 1); refresh() }
        findViewById<Button>(R.id.btnAbastecer).setOnClickListener { showFillDialog() }
        findViewById<Button>(R.id.btnManageFills).setOnClickListener { manageFillsDialog() }
        findViewById<Button>(R.id.btnCalc).setOnClickListener { calcTank() }
        findViewById<Button>(R.id.btnSaveSync).setOnClickListener { saveSyncCode() }
        findViewById<Button>(R.id.btnPush).setOnClickListener { doPush(true) }
        findViewById<Button>(R.id.btnPull).setOnClickListener { doPull() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLauncher.launch("meucarro_backup.json") }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importLauncher.launch(arrayOf("application/json", "*/*")) }

        askNotificationPermission()
        handleIntent(intent)
    }

    private fun showPage(page: View) {
        pageModo.visibility = if (page === pageModo) View.VISIBLE else View.GONE
        pageMeses.visibility = if (page === pageMeses) View.VISIBLE else View.GONE
        pageTanque.visibility = if (page === pageTanque) View.VISIBLE else View.GONE
        pageNuvem.visibility = if (page === pageNuvem) View.VISIBLE else View.GONE
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleIntent(intent)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onPause() {
        super.onPause()
        val code = Storage.getSyncCode(this)
        if (code.isNotEmpty()) CloudSync.push(this, code, null)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val host = intent.data?.host ?: ""
        val extra = intent.getStringExtra(TrackingService.EXTRA_MODE) ?: ""
        val mode = when {
            host.equals("uber", true) || extra == Storage.MODE_UBER -> Storage.MODE_UBER
            host.equals("pessoal", true) || extra == Storage.MODE_PESSOAL -> Storage.MODE_PESSOAL
            else -> null
        }
        if (mode != null) startFixed(mode)
    }

    /** Ciclo do botão redondo: PARADO → AUTO → UBER → PESSOAL → PARADO. */
    private fun toggleMode() {
        when {
            Storage.isAutoEnabled(this) -> startFixed(Storage.MODE_UBER)   // AUTO → UBER
            Storage.getMode(this) == Storage.MODE_UBER -> startFixed(Storage.MODE_PESSOAL)
            Storage.getMode(this) == Storage.MODE_PESSOAL -> stopTracking() // → PARADO
            else -> startAuto()   // PARADO → AUTO
        }
    }

    private fun startAuto() {
        if (!hasLocation()) { pendingAuto = true; requestLocation(); return }
        if (!hasUsageAccess()) {
            AlertDialog.Builder(this)
                .setTitle("Falta o Acesso de uso")
                .setMessage("Para detectar o Uber Driver, ative o \"Acesso de uso\" para o Meu Carro.")
                .setPositiveButton("Abrir") { _, _ -> openUsageAccess() }
                .setNegativeButton("Continuar", null).show()
        }
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_MODE, Storage.MODE_AUTO)
        ContextCompat.startForegroundService(this, i)
        requestBackgroundIfNeeded()
        btnModeCircle.postDelayed({ refresh() }, 500)
    }

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Pede "Permitir o tempo todo" para registrar km com a tela desligada / app fechado. */
    private fun requestBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
            AlertDialog.Builder(this)
                .setTitle("Localização o tempo todo")
                .setMessage("Para registrar os km mesmo com a tela desligada ou o app fechado, escolha \"Permitir o tempo todo\" na próxima tela de localização.")
                .setPositiveButton("Ajustar agora") { _, _ ->
                    try {
                        ActivityCompat.requestPermissions(this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
                    } catch (e: Exception) {
                        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"))) } catch (e2: Exception) {}
                    }
                }
                .setNegativeButton("Agora não", null).show()
        }
    }

    private fun startFixed(mode: String) {
        if (!hasLocation()) { requestLocation(); return }
        Storage.setAutoEnabled(this, false)
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START).putExtra(TrackingService.EXTRA_MODE, mode)
        ContextCompat.startForegroundService(this, i)
        btnModeCircle.postDelayed({ refresh() }, 400)
    }

    private fun stopTracking() {
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
        Storage.setMode(this, Storage.MODE_NONE)
        Storage.setAutoEnabled(this, false)
        autoCloud()
        btnModeCircle.postDelayed({ refresh() }, 300)
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
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

    private fun appLastUsed(pkg: String): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24L * 60 * 60 * 1000, now)
            var last = 0L
            if (stats != null) for (s in stats) {
                if (s.packageName == pkg && s.lastTimeUsed > last) last = s.lastTimeUsed
            }
            last
        } catch (e: Exception) { 0L }
    }

    private fun uberLastUsed(): Long = appLastUsed("com.ubercab.driver")
    private fun wazeLastUsed(): Long = appLastUsed("com.waze")

    private fun recentUber(): Boolean {
        if (!hasUsageAccess()) return false
        val last = uberLastUsed()
        return last > 0 && (System.currentTimeMillis() - last) <= 10 * 60 * 1000L
    }

    private fun recentWaze(): Boolean {
        if (!hasUsageAccess()) return false
        val last = wazeLastUsed()
        return last > 0 && (System.currentTimeMillis() - last) <= 10 * 60 * 1000L
    }

    private fun testDetection() {
        val access = hasUsageAccess()
        val u = uberLastUsed()
        val w = wazeLastUsed()
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append(if (access) "OK - Acesso de uso: CONCEDIDO\n\n"
                  else "FALTA - Acesso de uso NAO concedido.\nToque em \"Conceder acesso de uso\".\n\n")
        if (u <= 0L) sb.append("Uber Driver: nenhum uso nas ultimas 24h.\n")
        else sb.append("Uber Driver usado ha " + ((now - u) / 60000) + " min.\n")
        if (w <= 0L) sb.append("Waze: nenhum uso nas ultimas 24h.\n")
        else sb.append("Waze usado ha " + ((now - w) / 60000) + " min.\n")
        val uberOpen = access && u > 0 && (now - u) <= 10 * 60 * 1000L
        val wazeOpen = access && w > 0 && (now - w) <= 10 * 60 * 1000L
        val detected = when {
            uberOpen -> "UBER (Uber Driver aberto)"
            wazeOpen -> "PESSOAL (Waze aberto)"
            else -> "NÃO CONTABILIZA (abra o Waze ou o Uber Driver)"
        }
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

    // ---------- Destinos ----------
    private fun renameDestDialog() {
        val dests = Storage.getDestinations(this).sorted()
        if (dests.isEmpty()) { Toast.makeText(this, "Nenhum destino salvo ainda.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Escolha o destino para renomear")
            .setItems(dests.toTypedArray()) { _, which ->
                val old = dests[which]
                val et = EditText(this)
                et.setText(old)
                AlertDialog.Builder(this)
                    .setTitle("Novo nome")
                    .setView(et)
                    .setPositiveButton("Salvar") { _, _ ->
                        val nn = et.text.toString().trim()
                        if (nn.isNotEmpty()) { Storage.renameDest(this, old, nn); autoCloud(); refresh() }
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Fechar", null).show()
    }

    // ---------- Abastecimento ----------
    private fun showFillDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val etLiters = EditText(ctx).apply { hint = "Litros (ex: 40)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val etPrice = EditText(ctx).apply { hint = "Valor total R$ (ex: 250)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        container.addView(etLiters); container.addView(etPrice)
        AlertDialog.Builder(ctx).setTitle("Registrar abastecimento")
            .setMessage("Marca o ponto zero do tanque.")
            .setView(container)
            .setPositiveButton("Salvar") { _, _ ->
                val liters = etLiters.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                val price = etPrice.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
                Storage.addFill(ctx, liters, price)
                autoCloud()
                Toast.makeText(ctx, "Tanque registrado.", Toast.LENGTH_SHORT).show(); refresh()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    /** Monta uma linha-resumo de cada abastecimento (do mais recente ao mais antigo). */
    private fun fillLabels(): List<String> {
        val loc = Locale("pt", "BR")
        val arr = Storage.getFills(this)
        val curP = Storage.getMetersPessoal(this)
        val curU = Storage.getMetersUber(this)
        val sdf = SimpleDateFormat("dd/MM/yyyy", loc)
        val out = ArrayList<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            val ts = o.optLong("timestamp", 0L)
            val liters = o.optDouble("liters", 0.0)
            val price = o.optDouble("price", 0.0)
            val p0 = o.optDouble("meters_pessoal_at_fill", 0.0)
            val u0 = o.optDouble("meters_uber_at_fill", 0.0)
            // fim do tanque = próximo abastecimento (ou totais atuais, se for o último)
            val fim = if (i == arr.length() - 1) Pair(curP, curU)
                      else { val n = arr.getJSONObject(i + 1); Pair(n.optDouble("meters_pessoal_at_fill", 0.0), n.optDouble("meters_uber_at_fill", 0.0)) }
            val kmP = ((fim.first - p0) / 1000.0).coerceAtLeast(0.0)
            val kmU = ((fim.second - u0) / 1000.0).coerceAtLeast(0.0)
            val kmT = kmP + kmU
            val atual = if (i == arr.length() - 1) "  ← tanque atual" else ""
            val b = StringBuilder()
            b.append("▸ ").append(sdf.format(Date(ts))).append(atual).append("\n")
            b.append(String.format(loc, "   %.0f L", liters))
            if (price > 0) b.append(String.format(loc, " · R$ %.2f", price))
            b.append("\n")
            b.append(String.format(loc, "   Rodou: %.1f km (Uber %.1f | Pessoal %.1f)", kmT, kmU, kmP))
            if (liters > 0 && kmT > 0) b.append(String.format(loc, "\n   Consumo: %.2f km/L", kmT / liters))
            out.add(b.toString())
        }
        return out
    }

    private fun manageFillsDialog() {
        val arr = Storage.getFills(this)
        if (arr.length() == 0) { Toast.makeText(this, "Nenhum abastecimento registrado.", Toast.LENGTH_SHORT).show(); return }
        val loc = Locale("pt", "BR")
        val sdf = SimpleDateFormat("dd/MM/yyyy", loc)
        // itens (mais recente primeiro) com o índice real guardado em paralelo
        val labels = ArrayList<String>()
        val idxs = ArrayList<Int>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            labels.add(sdf.format(Date(o.optLong("timestamp", 0L))) +
                String.format(loc, " — %.0f L · R$ %.2f", o.optDouble("liters", 0.0), o.optDouble("price", 0.0)))
            idxs.add(i)
        }
        AlertDialog.Builder(this)
            .setTitle("Apagar qual registro?")
            .setItems(labels.toTypedArray()) { _, which ->
                val realIdx = idxs[which]
                AlertDialog.Builder(this)
                    .setTitle("Apagar este abastecimento?")
                    .setMessage(labels[which])
                    .setPositiveButton("Apagar") { _, _ -> Storage.removeFill(this, realIdx); autoCloud(); refresh() }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Fechar", null).show()
    }

    private fun calcTank() {
        val liters = etCalcLiters.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
        val kmTotal = etCalcKm.text.toString().replace(",", ".").toDoubleOrNull() ?: 0.0
        if (kmTotal <= 0.0) { tvCalcResult.text = "Informe os km rodados com o tanque."; return }
        val fill = Storage.lastFill(this)
        val mP: Double; val mU: Double
        if (fill != null) {
            mP = Storage.getMetersPessoal(this) - fill.optDouble("meters_pessoal_at_fill", 0.0)
            mU = Storage.getMetersUber(this) - fill.optDouble("meters_uber_at_fill", 0.0)
        } else { mP = Storage.getMetersPessoal(this); mU = Storage.getMetersUber(this) }
        val tracked = mP + mU
        val loc = Locale("pt", "BR")
        val sb = StringBuilder()
        if (tracked <= 0.0) {
            sb.append(String.format(loc, "Consumo: %.2f km/L\n", if (liters > 0) kmTotal / liters else 0.0))
            sb.append("Sem rastreamento para dividir ainda.")
        } else {
            val ratioU = mU / tracked
            val kmUber = kmTotal * ratioU
            sb.append(String.format(loc, "Consumo médio: %.2f km/L\n", if (liters > 0) kmTotal / liters else 0.0))
            sb.append(String.format(loc, "Uber: %.1f km (%.0f%%)\n", kmUber, ratioU * 100))
            sb.append(String.format(loc, "Pessoal: %.1f km (%.0f%%)", kmTotal - kmUber, (1 - ratioU) * 100))
        }
        tvCalcResult.text = sb.toString()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Zerar totais e meses?")
            .setMessage("Apaga a quilometragem (total e por mês). Abastecimentos e destinos são mantidos.")
            .setPositiveButton("Zerar") { _, _ -> Storage.resetTotals(this); refresh() }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------- Nuvem ----------
    private fun saveSyncCode() {
        val code = CloudSync.sanitizeCode(etSyncCode.text.toString())
        if (code.isEmpty()) { Toast.makeText(this, "Digite um código com letras/números.", Toast.LENGTH_LONG).show(); return }
        Storage.setSyncCode(this, code); etSyncCode.setText(code)
        Toast.makeText(this, "Código salvo: $code", Toast.LENGTH_SHORT).show(); refresh()
    }

    private fun doPush(showToast: Boolean) {
        val code = Storage.getSyncCode(this)
        if (code.isEmpty()) { Toast.makeText(this, "Salve um código primeiro.", Toast.LENGTH_LONG).show(); return }
        if (showToast) Toast.makeText(this, "Enviando para a nuvem...", Toast.LENGTH_SHORT).show()
        CloudSync.push(this, code, object : CloudSync.Callback {
            override fun onResult(success: Boolean, message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun doPull() {
        val code = Storage.getSyncCode(this)
        if (code.isEmpty()) { Toast.makeText(this, "Salve um código primeiro.", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "Baixando da nuvem...", Toast.LENGTH_SHORT).show()
        CloudSync.pull(this, code, object : CloudSync.Callback {
            override fun onResult(success: Boolean, message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                if (success) refresh()
            }
        })
    }

    // ---------- Sincronização automática ----------
    /** Envia para a nuvem sozinho, sem toast, sempre que algo muda. */
    private fun autoCloud() {
        val code = Storage.getSyncCode(this)
        if (code.isNotEmpty()) CloudSync.push(this, code, null)
    }

    /** Se abriu sem nada salvo (ex.: acabou de reinstalar), baixa da nuvem sozinho. */
    private fun maybeAutoRestore() {
        val code = Storage.getSyncCode(this)
        if (code.isEmpty() || !Storage.isLocalEmpty(this)) return
        CloudSync.pull(this, code, object : CloudSync.Callback {
            override fun onResult(success: Boolean, message: String) {
                if (success) {
                    Toast.makeText(this@MainActivity, "Dados restaurados da nuvem", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        })
    }

    // ---------- Tela ----------
    private fun shiftYm(ym: String, delta: Int): String {
        return try {
            val parts = ym.split("-"); var y = parts[0].toInt(); var m = parts[1].toInt() + delta
            while (m < 1) { m += 12; y-- }; while (m > 12) { m -= 12; y++ }
            String.format("%04d-%02d", y, m)
        } catch (e: Exception) { Storage.currentYm() }
    }

    private fun monthLabel(ym: String): String {
        return try { val p = ym.split("-"); "${monthNames[p[1].toInt() - 1]}/${p[0]}" } catch (e: Exception) { ym }
    }

    // ---------- Visual premium do orbe ----------
    private fun ensurePulse(): ObjectAnimator {
        if (pulseAnim == null) {
            val sx = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.045f)
            val sy = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.045f)
            pulseAnim = ObjectAnimator.ofPropertyValuesHolder(btnModeCircle, sx, sy).apply {
                duration = 1150
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        return pulseAnim!!
    }

    private fun ensureRingRotate(): ObjectAnimator {
        if (ringRotate == null) {
            ringRotate = ObjectAnimator.ofFloat(ringGlow, "rotation", 0f, 360f).apply {
                duration = 3400
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = LinearInterpolator()
            }
        }
        return ringRotate!!
    }

    private fun ensureRingPulse(): ObjectAnimator {
        if (ringPulse == null) {
            ringPulse = ObjectAnimator.ofFloat(ringGlow, "alpha", 0.28f, 0.82f).apply {
                duration = 1350
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        return ringPulse!!
    }

    /** kind: 0 PARADO · 1 AUTO · 2 UBER · 3 PESSOAL. Só reaplica se mudou de estado. */
    private fun applyOrbVisual(kind: Int) {
        if (kind == currentOrbKind) return
        currentOrbKind = kind
        ringRotate?.cancel()
        ringPulse?.cancel()
        when (kind) {
            0 -> {
                btnModeCircle.setBackgroundResource(R.drawable.orb_parado)
                pulseAnim?.cancel(); btnModeCircle.scaleX = 1f; btnModeCircle.scaleY = 1f
                ringGlow.rotation = 0f; ringGlow.alpha = 0f
            }
            1 -> {
                btnModeCircle.setBackgroundResource(R.drawable.orb_auto)
                ringGlow.setBackgroundResource(R.drawable.ring_auto)
                ringGlow.backgroundTintList = null
                ringGlow.alpha = 0.92f
                ensureRingRotate().start()
                ensurePulse().start()
            }
            2 -> {
                btnModeCircle.setBackgroundResource(R.drawable.orb_uber)
                ringGlow.setBackgroundResource(R.drawable.ring_glow)
                ringGlow.backgroundTintList = ColorStateList.valueOf(0xFF84E6A6.toInt())
                ringGlow.rotation = 0f
                ensureRingPulse().start()
                ensurePulse().start()
            }
            3 -> {
                btnModeCircle.setBackgroundResource(R.drawable.orb_pessoal)
                ringGlow.setBackgroundResource(R.drawable.ring_glow)
                ringGlow.backgroundTintList = ColorStateList.valueOf(0xFFF6E6AC.toInt())
                ringGlow.rotation = 0f
                ensureRingPulse().start()
                ensurePulse().start()
            }
        }
    }

    // ---------- Lista de viagens (De: / Para:) ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeInfoText(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(0xFFAAAAAA.toInt())
        textSize = 12f
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun buildTripRow(o: JSONObject): View {
        val loc = Locale("pt", "BR")
        val sdf = SimpleDateFormat("dd/MM · HH:mm", loc)
        val ts = o.optLong("ts", 0L)
        val mode = o.optString("mode", "")
        val origin = o.optString("origin", "?")
        val dest = o.optString("dest", "?")
        val km = o.optDouble("meters", 0.0) / 1000.0
        val isUber = mode == Storage.MODE_UBER
        val modeLabel = if (isUber) "UBER" else "PESSOAL"
        val modeBg = if (isUber) 0xFF1A6B2A.toInt() else 0xFFC9A043.toInt()
        val modeFg = if (isUber) 0xFFFFFFFF.toInt() else 0xFF2A1E05.toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C1C.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            text = "  $modeLabel  "
            setTextColor(modeFg)
            setBackgroundColor(modeBg)
            textSize = 10f
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val dateTv = TextView(this).apply {
            text = "   " + sdf.format(Date(ts))
            setTextColor(0xFF999999.toInt())
            textSize = 11f
        }
        header.addView(badge)
        header.addView(dateTv)
        val body = TextView(this).apply {
            text = String.format(loc, "De: %s\nPara: %s\n%.1f km", origin, dest, km)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        row.addView(header)
        row.addView(body)
        return row
    }

    /** Preenche a lista de viagens: filtrada por local (todos os meses) ou do mês selecionado. */
    private fun refreshTripsList(ym: String) {
        val loc = Locale("pt", "BR")
        llTrips.removeAllViews()
        val filterQ = etDestFilter.text.toString().trim()

        if (filterQ.isNotEmpty()) {
            val q = filterQ.lowercase()
            val allTrips = Storage.getTrips(this)
            val matched = ArrayList<JSONObject>()
            for (i in allTrips.length() - 1 downTo 0) {
                val o = allTrips.getJSONObject(i)
                val org = o.optString("origin", "").lowercase()
                val dst = o.optString("dest", "").lowercase()
                if (org.contains(q) || dst.contains(q)) matched.add(o)
            }
            var visits = 0; var kmTotal = 0.0
            for (n in Storage.getDestinations(this)) {
                if (n.lowercase().contains(q)) {
                    visits += Storage.getDestTotalCount(this, n)
                    kmTotal += Storage.getDestTotalMeters(this, n) / 1000.0
                }
            }
            tvDestFilterResult.visibility = View.VISIBLE
            tvDestFilterResult.text = if (visits > 0)
                String.format(loc, "Você já foi %d vez(es) a \"%s\" — %.1f km no total (todos os meses)", visits, filterQ, kmTotal)
            else "Nenhuma chegada registrada com esse nome ainda."

            if (matched.isEmpty()) llTrips.addView(makeInfoText("Nenhuma viagem encontrada com \"$filterQ\"."))
            else for (o in matched.take(80)) llTrips.addView(buildTripRow(o))
        } else {
            tvDestFilterResult.visibility = View.GONE
            val monthTrips = Storage.getTripsForMonth(this, ym)
            if (monthTrips.isEmpty()) {
                llTrips.addView(makeInfoText(
                    "Nenhuma viagem registrada neste mês ainda.\nAs viagens são salvas sozinhas quando você chega e para, com o AUTO ligado e o Waze (Pessoal) ou o Uber Driver (Uber) aberto."
                ))
            } else for (o in monthTrips.take(80)) llTrips.addView(buildTripRow(o))
        }
    }

    private fun refresh() {
        val loc = Locale("pt", "BR")
        val kmP = Storage.getMetersPessoal(this) / 1000.0
        val kmU = Storage.getMetersUber(this) / 1000.0
        val mode = Storage.getMode(this)
        val auto = Storage.isAutoEnabled(this)

        // Estado do botão redondo premium: PARADO / AUTO / UBER / PESSOAL
        when {
            auto -> {
                val uber = recentUber()
                val waze = recentWaze()
                btnModeCircle.setTextColor(0xFFFFFFFF.toInt())
                applyOrbVisual(1)
                when {
                    uber -> {
                        btnModeCircle.text = "AUTO\nUBER"
                        tvStatus.text = "● AUTOMÁTICO — Uber Driver aberto: contando UBER."
                    }
                    waze -> {
                        btnModeCircle.text = "AUTO\nPESSOAL"
                        tvStatus.text = "● AUTOMÁTICO — Waze aberto (sem Uber): contando PESSOAL."
                    }
                    else -> {
                        btnModeCircle.text = "AUTO\naguardando"
                        tvStatus.text = "○ AUTOMÁTICO ligado, mas NÃO está contando.\nAbra o Waze (Pessoal) ou o Uber Driver (Uber) para registrar."
                    }
                }
            }
            mode == Storage.MODE_UBER -> {
                btnModeCircle.text = "UBER"
                btnModeCircle.setTextColor(0xFFFFFFFF.toInt())
                applyOrbVisual(2)
                tvStatus.text = "● REGISTRANDO: UBER (manual)\nToque no círculo para ir a PESSOAL."
            }
            mode == Storage.MODE_PESSOAL -> {
                btnModeCircle.text = "PESSOAL"
                btnModeCircle.setTextColor(0xFF2A1E05.toInt())
                applyOrbVisual(3)
                tvStatus.text = "● REGISTRANDO: PESSOAL (manual)\nToque no círculo para PARAR."
            }
            else -> {
                btnModeCircle.text = "PARADO"
                btnModeCircle.setTextColor(0xFFFFFFFF.toInt())
                applyOrbVisual(0)
                tvStatus.text = "○ PARADO — nada é registrado agora.\nToque no círculo para ligar o AUTO (conta ao dirigir)."
            }
        }

        tvUsageWarn.text = if (!hasUsageAccess())
            "⚠ Acesso de uso não concedido: sem ele tudo conta como Pessoal."
        else "✓ Acesso de uso concedido."

        val ym = selectedYm
        val mp = Storage.getMonthMeters(this, Storage.MODE_PESSOAL, ym) / 1000.0
        val mu = Storage.getMonthMeters(this, Storage.MODE_UBER, ym) / 1000.0
        val atual = if (ym == Storage.currentYm()) "  (mês atual)" else ""
        tvMonth.text = String.format(loc, "MÊS: %s%s\nPessoal: %.2f km\nUber: %.2f km\nTotal: %.2f km",
            monthLabel(ym), atual, mp, mu, mp + mu)

        refreshTripsList(ym)

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

        val sc = Storage.getSyncCode(this)
        tvSyncStatus.text = if (sc.isEmpty())
            "Nuvem: sem código. Crie um código e toque em Salvar."
        else "✓ Sincronização AUTOMÁTICA ligada (código: $sc).\nSalva sozinho a cada dado novo e ao dirigir. Se reinstalar, restaura sozinho.\nGUARDE este código: com ele você recupera tudo em qualquer celular."

        val ymTank = selectedYmTank
        val fillsMonth = Storage.getFillsForMonth(this, ymTank)
        val litersSum = fillsMonth.sumOf { it.optDouble("liters", 0.0) }
        val priceSum = fillsMonth.sumOf { it.optDouble("price", 0.0) }
        val kmMonthTank = (Storage.getMonthMeters(this, Storage.MODE_PESSOAL, ymTank) +
            Storage.getMonthMeters(this, Storage.MODE_UBER, ymTank)) / 1000.0
        val atualTank = if (ymTank == Storage.currentYm()) "  (mês atual)" else ""
        tvTankMonthSummary.text = if (fillsMonth.isEmpty())
            String.format(loc, "%s%s\nNenhum abastecimento neste mês.\nKm rodados no mês: %.1f km",
                monthLabel(ymTank), atualTank, kmMonthTank)
        else String.format(loc,
            "%s%s\nAbastecimentos: %d\nTotal de litros: %.1f L\nTotal gasto: R$ %.2f\nKm rodados no mês: %.1f km\nConsumo médio do mês: %.2f km/L",
            monthLabel(ymTank), atualTank, fillsMonth.size, litersSum, priceSum, kmMonthTank,
            if (litersSum > 0) kmMonthTank / litersSum else 0.0)

        val fill = Storage.lastFill(this)
        if (fill == null) {
            tvTank.text = "TANQUE ATUAL\nNenhum abastecimento registrado.\nUse \"Registrar abastecimento\" ao encher."
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
            t.append(String.format(loc, "Pessoal: %.2f km\nUber: %.2f km\nTotal: %.2f km\n", kmPTank, kmUTank, kmTotalTank))
            if (liters > 0) t.append(String.format(loc, "Consumo: %.2f km/L\n", if (liters > 0) kmTotalTank / liters else 0.0))
            if (price > 0 && kmTotalTank > 0) {
                val custoKm = price / kmTotalTank
                t.append(String.format(loc, "Custo/km: R$ %.2f\nGasto Pessoal: R$ %.2f | Uber: R$ %.2f", custoKm, custoKm * kmPTank, custoKm * kmUTank))
            }
            tvTank.text = t.toString()
        }

        val labels = fillLabels()
        tvFillHistory.text = if (labels.isEmpty())
            "Nenhum abastecimento salvo ainda.\nCada vez que você registra um, ele fica guardado aqui — nenhum é apagado sozinho."
        else labels.joinToString("\n\n")
    }
}
