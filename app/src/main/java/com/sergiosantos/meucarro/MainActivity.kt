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
import android.text.InputType
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
    private lateinit var tvDest: TextView
    private lateinit var tvTotals: TextView
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
    private val REQ_LOCATION = 100
    private val REQ_NOTIF = 101

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
        tvDest = findViewById(R.id.tvDest)
        tvTotals = findViewById(R.id.tvTotals)
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
        btnModeCircle.postDelayed({ refresh() }, 500)
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
                  else "FALTA - Acesso de uso NAO concedido.\nToque em \"Conceder acesso de uso\".\n\n")
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

    private fun refresh() {
        val loc = Locale("pt", "BR")
        val kmP = Storage.getMetersPessoal(this) / 1000.0
        val kmU = Storage.getMetersUber(this) / 1000.0
        val mode = Storage.getMode(this)
        val auto = Storage.isAutoEnabled(this)

        // Estado do botão redondo premium: PARADO / AUTO / UBER / PESSOAL
        when {
            auto -> {
                val u = recentUber()
                btnModeCircle.text = "AUTO\n" + (if (u) "UBER" else "PESSOAL")
                btnModeCircle.setTextColor(0xFFFFFFFF.toInt())
                applyOrbVisual(1)
                tvStatus.text = "● AUTOMÁTICO — decidindo sozinho: " + (if (u) "UBER" else "PESSOAL") +
                    "\nConta ao dirigir. Toque no círculo para trocar (vai a UBER)."
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

        val db = StringBuilder("DESTINOS — " + monthLabel(ym) + "\n")
        var anyDest = false
        for (n in Storage.getDestinations(this).sorted()) {
            val cnt = Storage.getDestCount(this, n, ym)
            val dkm = Storage.getDestMeters(this, n, ym) / 1000.0
            if (cnt > 0 || dkm > 0) { anyDest = true; db.append(String.format(loc, "%s: %dx · %.1f km\n", n, cnt, dkm)) }
        }
        if (!anyDest) db.append("Nenhuma visita neste mês. Os destinos são salvos sozinhos quando você chega e para.")
        tvDest.text = db.toString()

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
