package com.sergiosantos.meucarro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvTank: TextView

    private var pendingMode: String? = null

    private val REQ_LOCATION = 100
    private val REQ_NOTIF = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTotals = findViewById(R.id.tvTotals)
        tvTank = findViewById(R.id.tvTank)

        findViewById<Button>(R.id.btnPessoal).setOnClickListener { requestStart(Storage.MODE_PESSOAL) }
        findViewById<Button>(R.id.btnUber).setOnClickListener { requestStart(Storage.MODE_UBER) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTracking() }
        findViewById<Button>(R.id.btnAbastecer).setOnClickListener { showFillDialog() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { confirmReset() }

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

    /** Descobre se o app foi aberto por um atalho/voz pedindo um modo especifico. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val fromComponent = intent.component?.className ?: ""
        val fromData = intent.data?.host ?: ""
        val fromExtra = intent.getStringExtra(TrackingService.EXTRA_MODE) ?: ""

        val mode = when {
            fromComponent.endsWith("AliasUber") || fromData.equals("uber", true) ||
                fromExtra == Storage.MODE_UBER -> Storage.MODE_UBER
            fromComponent.endsWith("AliasPessoal") || fromData.equals("pessoal", true) ||
                fromExtra == Storage.MODE_PESSOAL -> Storage.MODE_PESSOAL
            else -> null
        }
        if (mode != null) requestStart(mode)
    }

    private fun requestStart(mode: String) {
        pendingMode = mode
        if (!hasLocation()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            return
        }
        startTracking(mode)
    }

    private fun startTracking(mode: String) {
        pendingMode = null
        val i = Intent(this, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_MODE, mode)
        ContextCompat.startForegroundService(this, i)
        val label = if (mode == Storage.MODE_UBER) "UBER" else "PESSOAL"
        Toast.makeText(this, "Modo $label iniciado", Toast.LENGTH_SHORT).show()
        tvStatus.postDelayed({ refresh() }, 400)
        maybeSuggestBackground()
    }

    private fun stopTracking() {
        val i = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
        startService(i)
        Storage.setMode(this, Storage.MODE_NONE)
        Toast.makeText(this, "Registro parado", Toast.LENGTH_SHORT).show()
        tvStatus.postDelayed({ refresh() }, 300)
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
            }
        }
    }

    private fun maybeSuggestBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bg = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bg) {
                // Dica unica: para maior precisao com tela apagada, permitir "o tempo todo".
                // Nao bloqueia o funcionamento (o servico em primeiro plano ja mantem o GPS).
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMode?.let { startTracking(it) }
            } else {
                Toast.makeText(
                    this, "Preciso da permissao de localizacao para registrar os km.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------- Tanque / abastecimento ----------------

    private fun showFillDialog() {
        val ctx = this
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etLiters = EditText(ctx).apply {
            hint = "Litros abastecidos (ex: 40)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etPrice = EditText(ctx).apply {
            hint = "Valor total pago R$ (ex: 250)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(etLiters)
        container.addView(etPrice)

        AlertDialog.Builder(ctx)
            .setTitle("Registrar abastecimento")
            .setMessage("Isso marca o ponto zero do tanque. A partir daqui conto os km de cada modo.")
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

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Zerar totais gerais?")
            .setMessage("Isso apaga a quilometragem total acumulada (Pessoal e Uber). O historico de abastecimentos e mantido.")
            .setPositiveButton("Zerar") { _, _ ->
                Storage.resetTotals(this)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Atualizacao da tela ----------------

    private fun refresh() {
        val kmP = Storage.getMetersPessoal(this) / 1000.0
        val kmU = Storage.getMetersUber(this) / 1000.0

        val mode = Storage.getMode(this)
        tvStatus.text = when (mode) {
            Storage.MODE_UBER -> "● REGISTRANDO: MODO UBER"
            Storage.MODE_PESSOAL -> "● REGISTRANDO: MODO PESSOAL"
            else -> "○ Parado (nenhum modo ativo)"
        }

        tvTotals.text = String.format(
            Locale("pt", "BR"),
            "TOTAL GERAL\nPessoal: %.2f km\nUber: %.2f km\nSoma: %.2f km",
            kmP, kmU, kmP + kmU
        )

        val fill = Storage.lastFill(this)
        if (fill == null) {
            tvTank.text = "TANQUE ATUAL\nNenhum abastecimento registrado ainda.\nToque em \"Registrar abastecimento\" ao encher o tanque."
        } else {
            val mP0 = fill.optDouble("meters_pessoal_at_fill", 0.0)
            val mU0 = fill.optDouble("meters_uber_at_fill", 0.0)
            val liters = fill.optDouble("liters", 0.0)
            val price = fill.optDouble("price", 0.0)
            val ts = fill.optLong("timestamp", 0L)

            val kmPTank = (Storage.getMetersPessoal(this) - mP0) / 1000.0
            val kmUTank = (Storage.getMetersUber(this) - mU0) / 1000.0
            val kmTotalTank = kmPTank + kmUTank

            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
            val sb = StringBuilder()
            sb.append("TANQUE ATUAL (desde ").append(sdf.format(Date(ts))).append(")\n")
            sb.append(String.format(Locale("pt","BR"), "Pessoal: %.2f km\n", kmPTank))
            sb.append(String.format(Locale("pt","BR"), "Uber: %.2f km\n", kmUTank))
            sb.append(String.format(Locale("pt","BR"), "Total rodado: %.2f km\n", kmTotalTank))
            if (liters > 0) {
                sb.append(String.format(Locale("pt","BR"), "Consumo medio: %.2f km/L\n", if (liters>0) kmTotalTank/liters else 0.0))
            }
            if (price > 0 && kmTotalTank > 0) {
                val custoKm = price / kmTotalTank
                sb.append(String.format(Locale("pt","BR"), "Custo por km: R$ %.2f\n", custoKm))
                sb.append(String.format(Locale("pt","BR"), "Gasto Pessoal: R$ %.2f\n", custoKm * kmPTank))
                sb.append(String.format(Locale("pt","BR"), "Gasto Uber: R$ %.2f", custoKm * kmUTank))
            }
            tvTank.text = sb.toString()
        }
    }
}
