package mx.com.nexusio.fly.drone

import android.widget.TextView
import android.view.View
import androidx.core.content.ContextCompat
import dji.v5.manager.diagnostic.WarningLevel
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import android.widget.Button
import android.widget.Toast
import android.content.res.ColorStateList
import com.google.android.material.button.MaterialButton

// Imports HMS
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener
import dji.v5.manager.diagnostic.DeviceHealthManager

// IMPORTS MSDK V5
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import mx.com.nexusio.fly.BuildConfig
import mx.com.nexusio.fly.R
import mx.com.nexusio.fly.ui.UpdateManager
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

@SuppressLint("SetTextI18n")
class AircraftSettingsActivity : AppCompatActivity() {

    // Persistencia
    private lateinit var prefs: SharedPreferences

    // Variables HMS
    private val propulsionErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val avionicaErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val posicionamientoErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val bateriaErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val rcErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val transmisionErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val camaraErrors = mutableListOf<DJIDeviceHealthInfo>()
    private val codigosIgnorados = listOf("0X1900D004", "0X1AFC0140", "0X18000000")
    /** Hay datos de salud solo si el dron está enlazado (KeyConnection), no solo porque llegó un callback. */
    private var diagnosticoRecibido = false

    private val healthListener = DJIDeviceHealthInfoChangeListener { infos ->
        runOnUiThread { procesarDiagnosticoDelDron(infos) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aircraft_settings)

        prefs = getSharedPreferences("Nexus_Safety_Prefs", MODE_PRIVATE)

        findViewById<View>(R.id.btn_settings_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_settings_app_version).text = "Aplicación · v${BuildConfig.VERSION_NAME}"

        configurarClickTarjeta(R.id.card_propulsion, "Propulsion", propulsionErrors)
        configurarClickTarjeta(R.id.card_avionica, "Avionica", avionicaErrors)
        configurarClickTarjeta(R.id.card_posicionamiento, "Posicionamiento", posicionamientoErrors)
        configurarClickTarjeta(R.id.card_bateria, "Bateria", bateriaErrors)
        configurarClickTarjeta(R.id.card_rc, "Control Remoto", rcErrors)
        configurarClickTarjeta(R.id.card_transmision, "Transmision", transmisionErrors)
        configurarClickTarjeta(R.id.card_camara, "Camara", camaraErrors)

        configurarSpinners()
        configurarBotonesDeAjuste()
        configurarSlidersSensores()
        configurarPuntoDeOrigen()

        // Cargar valores guardados o aplicar defaults
        cargarYEnviarConfiguracion()

        val btnCheckUpdates = findViewById<Button>(R.id.btn_check_updates)
        btnCheckUpdates.setOnClickListener {
            Toast.makeText(this, "Buscando actualizaciones...", Toast.LENGTH_SHORT).show()
            // Le pasamos 'true' al final para que nos dé respuesta visual
            UpdateManager(this).checkForUpdates(BuildConfig.VERSION_CODE, true)
        }
        // ... (Tu código actual del botón btn_check_updates) ...

        // CARGAR HISTORIAL DE VERSIONES
        val historyContainer = findViewById<LinearLayout>(R.id.layout_version_history)
        val updateManager = UpdateManager(this)

        updateManager.fetchVersionHistory { jsonArray ->
            if (jsonArray == null || jsonArray.length() == 0) {
                val tvEmpty = TextView(this).apply {
                    text = "No hay versiones anteriores disponibles."
                    setTextColor(ContextCompat.getColor(this@AircraftSettingsActivity, R.color.nx_fg_muted))
                    setPadding(0, 16, 0, 16)
                }
                historyContainer.addView(tvEmpty)
                return@fetchVersionHistory
            }

            // Construimos un botón por cada versión en la base de datos
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val vCode = item.getInt("version_code")
                val vName = item.getString("version_name")
                val apkUrl = item.getString("apk_url")

                val btnVersion = MaterialButton(this).apply {
                    text = "Versión $vName (Instalar)"
                    isAllCaps = false
                    // Estilo de botón secundario (Gris con letras oscuras)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@AircraftSettingsActivity, R.color.nx_surface_raised))
                    strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this@AircraftSettingsActivity, R.color.nx_border_strong))
                    strokeWidth = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    cornerRadius = (6 * resources.displayMetrics.density).toInt()
                    setTextColor(ContextCompat.getColor(this@AircraftSettingsActivity, R.color.nx_fg))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }

                    // Al presionar, descargará específicamente esta versión
                    setOnClickListener {
                        // Usamos la función inteligente en lugar de descarga directa
                        updateManager.iniciarProcesoDeActualizacion(apkUrl, "nexusapp_v$vCode.apk", vName)
                    }
                }
                historyContainer.addView(btnVersion)
            }
        }
    }

    // ==========================================
    // PERSISTENCIA Y CARGA
    // ==========================================
    private fun cargarYEnviarConfiguracion() {
        val maxHeight = prefs.getInt("max_height", 200)
        val rthHeight = prefs.getInt("rth_height", 220)
        val maxRadius = prefs.getInt("max_radius", 120)

        // Actualizar UI
        findViewById<EditText>(R.id.et_max_height).setText(maxHeight.toString())
        findViewById<EditText>(R.id.et_rth_height).setText(rthHeight.toString())
        findViewById<EditText>(R.id.et_max_radius).setText(maxRadius.toString())

        // Enviar al dron de inmediato
        enviarParametroAlDron(FlightControllerKey.KeyHeightLimit, maxHeight, "Altura Maxima")
        enviarParametroAlDron(FlightControllerKey.KeyGoHomeHeight, rthHeight, "Altura RTH")

        // El limite de distancia requiere activar el switch y luego el valor
        val enableRadiusKey = KeyTools.createKey(FlightControllerKey.KeyDistanceLimitEnabled)
        KeyManager.getInstance().setValue(enableRadiusKey, true, null)
        enviarParametroAlDron(FlightControllerKey.KeyDistanceLimit, maxRadius, "Distancia Maxima")
    }

    // AQUI ESTABA EL ERROR DE TIPO: Cambiamos DJIKey por DJIKeyInfo
    private fun enviarParametroAlDron(keyInfo: DJIKeyInfo<Int>, valor: Int, nombre: String) {
        val key = KeyTools.createKey(keyInfo)
        KeyManager.getInstance().setValue(key, valor, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.d("NEXUS_SETTINGS", "$nombre OK: $valor") }
            override fun onFailure(error: IDJIError) {
                Log.e("NEXUS_SETTINGS", "Error en $nombre: ${error.description()}")
            }
        })
    }

    private fun configurarBotonesDeAjuste() {
        findViewById<Button>(R.id.btn_set_max_height).setOnClickListener {
            val input = findViewById<EditText>(R.id.et_max_height).text.toString()
            if (input.isNotEmpty()) {
                val valor = input.toInt()
                prefs.edit().putInt("max_height", valor).apply() // GUARDAR
                enviarParametroAlDron(FlightControllerKey.KeyHeightLimit, valor, "Altura Maxima")
                mostrarToast("Altura Maxima guardada y aplicada")
            }
        }

        findViewById<Button>(R.id.btn_set_rth_height).setOnClickListener {
            val input = findViewById<EditText>(R.id.et_rth_height).text.toString()
            if (input.isNotEmpty()) {
                val valor = input.toInt()
                prefs.edit().putInt("rth_height", valor).apply() // GUARDAR
                enviarParametroAlDron(FlightControllerKey.KeyGoHomeHeight, valor, "Altura RTH")
                mostrarToast("Altura RTH guardada y aplicada")
            }
        }

        findViewById<Button>(R.id.btn_set_max_radius).setOnClickListener {
            val input = findViewById<EditText>(R.id.et_max_radius).text.toString()
            if (input.isNotEmpty()) {
                val valor = input.toInt()
                prefs.edit().putInt("max_radius", valor).apply() // GUARDAR

                val enableRadiusKey = KeyTools.createKey(FlightControllerKey.KeyDistanceLimitEnabled)
                KeyManager.getInstance().setValue(enableRadiusKey, true, null)

                enviarParametroAlDron(FlightControllerKey.KeyDistanceLimit, valor, "Distancia Maxima")
                mostrarToast("Distancia Maxima guardada y aplicada")
            }
        }
    }

    // ==========================================
    // UI Y COMPONENTES (RESTO DEL CÓDIGO)
    // ==========================================

    private fun configurarSpinners() {
        val spinnerFailsafe = findViewById<Spinner>(R.id.spinner_failsafe_action)
        val spinnerAvoidance = findViewById<Spinner>(R.id.spinner_avoidance_action)

        val failsafeOptions = arrayOf("Mantener Posicion (Hover)") // Forzamos solo Hover por seguridad
        spinnerFailsafe.adapter = ArrayAdapter(this, R.layout.item_nx_spinner, failsafeOptions).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }

        val avoidanceOptions = arrayOf("Activar Sensores (Brake)")
        spinnerAvoidance.adapter = ArrayAdapter(this, R.layout.item_nx_spinner, avoidanceOptions).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
    }

    private fun configurarSlidersSensores() {
        val rangeSlider = findViewById<RangeSlider>(R.id.rs_obstacle_distance)
        val tvValores = findViewById<TextView>(R.id.tv_slider_values)
        rangeSlider.values = listOf(3.0f, 15.0f)
        rangeSlider.addOnChangeListener { slider, _, _ ->
            val freno = slider.values[0]
            val alerta = slider.values[1]
            tvValores.text = "Freno: ${freno.toInt()}m | Alerta: ${alerta.toInt()}m"
        }
    }

    private fun configurarPuntoDeOrigen() {
        findViewById<Button>(R.id.btn_home_to_drone).setOnClickListener {
            val locKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
            KeyManager.getInstance().getValue(locKey, object : CommonCallbacks.CompletionCallbackWithParam<LocationCoordinate3D> {
                override fun onSuccess(aircraftLoc: LocationCoordinate3D?) {
                    if (aircraftLoc != null && aircraftLoc.latitude != 0.0) {
                        val homeLoc = LocationCoordinate2D(aircraftLoc.latitude, aircraftLoc.longitude)
                        KeyManager.getInstance().setValue(KeyTools.createKey(FlightControllerKey.KeyHomeLocation), homeLoc, getCallback("Home fijado"))
                    }
                }
                override fun onFailure(error: IDJIError) { mostrarToast("GPS insuficiente") }
            })
        }
    }

    // HMS Y OTROS METODOS
    override fun onResume() {
        super.onResume()
        try { DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener(healthListener) } catch (_: Exception) { }
        try {
            KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyConnection), this) { _, conectado ->
                runOnUiThread {
                    diagnosticoRecibido = conectado == true
                    procesarDiagnosticoDelDron(estadoActualDeSalud())
                }
            }
        } catch (_: Exception) { }
        diagnosticoRecibido = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyConnection)) == true
        procesarDiagnosticoDelDron(estadoActualDeSalud())
    }

    override fun onPause() {
        super.onPause()
        try { DeviceHealthManager.getInstance().removeDJIDeviceHealthInfoChangeListener(healthListener) } catch (_: Exception) { }
        try { KeyManager.getInstance().cancelListen(this) } catch (_: Exception) { }
    }

    private fun procesarDiagnosticoDelDron(infos: List<DJIDeviceHealthInfo>?) {
        propulsionErrors.clear(); avionicaErrors.clear(); posicionamientoErrors.clear()
        bateriaErrors.clear(); rcErrors.clear(); transmisionErrors.clear(); camaraErrors.clear()

        infos?.forEach { info ->
            val code = info.informationCode()?.uppercase() ?: ""
            if (codigosIgnorados.contains(code)) return@forEach
            val textoClave = "${info.title()} ${info.description()}".lowercase()
            when {
                textoClave.contains("motor") -> propulsionErrors.add(info)
                textoClave.contains("bat") -> bateriaErrors.add(info)
                textoClave.contains("gimbal") -> camaraErrors.add(info)
                textoClave.contains("gps") || textoClave.contains("vision") -> posicionamientoErrors.add(info)
                textoClave.contains("rc") -> rcErrors.add(info)
                textoClave.contains("signal") -> transmisionErrors.add(info)
                else -> avionicaErrors.add(info)
            }
        }
        actualizarColor(R.id.card_propulsion, R.id.tv_status_propulsion, propulsionErrors)
        actualizarColor(R.id.card_avionica, R.id.tv_status_avionica, avionicaErrors)
        actualizarColor(R.id.card_posicionamiento, R.id.tv_status_posicionamiento, posicionamientoErrors)
        actualizarColor(R.id.card_bateria, R.id.tv_status_bateria, bateriaErrors)
        actualizarColor(R.id.card_rc, R.id.tv_status_rc, rcErrors)
        actualizarColor(R.id.card_transmision, R.id.tv_status_transmision, transmisionErrors)
        actualizarColor(R.id.card_camara, R.id.tv_status_camara, camaraErrors)
    }

    private fun actualizarColor(cardId: Int, tvId: Int, errors: List<DJIDeviceHealthInfo>) {
        val card = findViewById<LinearLayout>(cardId)
        val tvStatus = findViewById<TextView>(tvId)
        val severidades = errors.mapNotNull { it.warningLevel().toSeverity() }
        val estado = HealthStatus.of(diagnosticoRecibido, severidades)
        val (fondo, color, icono) = when (estado) {
            HealthState.NO_DATA -> Triple(R.drawable.bg_nx_health_nodata, R.color.nx_fg_muted, 0)
            HealthState.NORMAL -> Triple(R.drawable.bg_nx_health_normal, R.color.nx_telemetry, R.drawable.ic_nx_check_sm)
            HealthState.WARNING -> Triple(R.drawable.bg_nx_health_warning, R.color.nx_caution, R.drawable.ic_nx_alert_sm)
            HealthState.FAILURE -> Triple(R.drawable.bg_nx_health_failure, R.color.nx_critical, R.drawable.ic_nx_alert_sm)
        }
        card.setBackgroundResource(fondo)
        tvStatus.text = HealthStatus.label(estado, severidades.size)
        val c = ContextCompat.getColor(this, color)
        tvStatus.setTextColor(c)
        tvStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(icono, 0, 0, 0)
        tvStatus.compoundDrawableTintList = ColorStateList.valueOf(c)
    }

    /** NORMAL no cuenta como incidencia. */
    private fun WarningLevel?.toSeverity(): Severity? = when (this) {
        WarningLevel.SERIOUS_WARNING -> Severity.SERIOUS
        WarningLevel.WARNING -> Severity.WARNING
        WarningLevel.CAUTION -> Severity.CAUTION
        WarningLevel.NOTICE, WarningLevel.UNKNOWN, null -> Severity.NOTICE
        WarningLevel.NORMAL -> null
    }

    private fun estadoActualDeSalud(): List<DJIDeviceHealthInfo>? =
        try { DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos } catch (_: Exception) { null }

    private fun configurarClickTarjeta(cardId: Int, nombre: String, lista: List<DJIDeviceHealthInfo>) {
        findViewById<LinearLayout>(cardId).setOnClickListener {
            val msg = if (!diagnosticoRecibido) "Sin datos del dron todavía. Enlaza el dron para ver su estado."
            else if (lista.isEmpty()) "Estado óptimo" else lista.joinToString("\n") { "${it.title()}: ${it.description()}" }
            mostrarDialogo(nombre, msg)
        }
    }

    private fun mostrarDialogo(titulo: String, mensaje: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(mensaje).setPositiveButton("CERRAR", null).show()
    }

    private fun getCallback(nombre: String): CommonCallbacks.CompletionCallback {
        return object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { mostrarToast("$nombre OK") }
            override fun onFailure(error: IDJIError) { mostrarToast("Error en $nombre") }
        }
    }

    private fun mostrarToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}