package mx.com.nexusio.fly.drone

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import mx.com.nexusio.fly.ui.BackendConfig
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import mx.com.nexusio.fly.R
import mx.com.nexusio.fly.ui.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale


class PilotConfigActivity : AppCompatActivity() {

    // VISTAS LOGIN
    private lateinit var layoutLoginForm: LinearLayout
    private lateinit var etPilotEmail: EditText
    private lateinit var etPilotPassword: EditText
    private lateinit var btnPilotLogin: Button

    private val PREFS_SAFETY = "Nexus_Safety_Prefs"
    private lateinit var prefsSafety: SharedPreferences

    // URL Y KEY DE SUPABASE
    private val supabaseUrl = BackendConfig.SUPABASE_URL
    private val anonKey = BackendConfig.SUPABASE_ANON_KEY

    // VISTAS LOGIN (Se ocultarán)

    // VISTAS PERFIL PILOTO
    private lateinit var layoutPilotProfile: LinearLayout
    private lateinit var btnPilotLogout: Button
    private lateinit var tvLoggedPilotName: TextView
    private lateinit var tvLoggedPilotLicense: TextView
    private lateinit var tvCurrentFlightHours: TextView
    private lateinit var etAddFlightHours: EditText
    private lateinit var btnAddHours: Button

    // VISTAS HISTORIAL
    private lateinit var btnSyncHistory: Button
    private lateinit var lvInspectionHistory: ListView

    // VARIABLES
    private var loggedPilotEmail: String = ""
    private var loggedPilotHours: Double = 0.0
    private var pendingFlightHours: Double = 0.0
    private var currentAccessToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pilot_config)
        supportActionBar?.hide()

        prefsSafety = getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE)

        findViewById<Button>(R.id.btn_back_to_main).setOnClickListener { finish() }

        // Mapear Vistas
        layoutLoginForm = findViewById(R.id.layout_login_form)
        etPilotEmail = findViewById(R.id.et_pilot_email)
        etPilotPassword = findViewById(R.id.et_pilot_password)
        btnPilotLogin = findViewById(R.id.btn_pilot_login)

        layoutPilotProfile = findViewById(R.id.layout_pilot_profile)
        btnPilotLogout = findViewById(R.id.btn_pilot_logout)
        tvLoggedPilotName = findViewById(R.id.tv_logged_pilot_name)
        tvLoggedPilotLicense = findViewById(R.id.tv_logged_pilot_license)
        tvCurrentFlightHours = findViewById(R.id.tv_current_flight_hours)
        etAddFlightHours = findViewById(R.id.et_add_flight_hours)
        btnAddHours = findViewById(R.id.btn_add_hours)
        btnSyncHistory = findViewById(R.id.btn_sync_history)
        lvInspectionHistory = findViewById(R.id.lv_inspection_history)

        // ¡SÚPER IMPORTANTE! Esta línea activa el botón de iniciar sesión
        btnPilotLogin.setOnClickListener { realizarLoginPilotoDJI() }

        btnSyncHistory.setOnClickListener { descargarHistorialDeTurbinas() }
        registrarMonitorDeRed()

        configurarBotonesPerfil()
        verificarSesionPilotoLocal()
        descargarHistorialDeTurbinas(silencioso = true)
    }


    private fun verificarSesionPilotoLocal() {
        val savedEmail = prefsSafety.getString("pilot_email", "") ?: ""
        currentAccessToken = prefsSafety.getString("access_token", "") ?: ""

        if (savedEmail.isNotEmpty()) {
            // SI HAY SESIÓN: Oculta el login y muestra el perfil
            layoutLoginForm.visibility = View.GONE
            layoutPilotProfile.visibility = View.VISIBLE

            loggedPilotEmail = savedEmail

            val name = prefsSafety.getString("pilot_name", "Piloto") ?: "Piloto"
            val license = prefsSafety.getString("pilot_license", "No registrada") ?: "No registrada"
            loggedPilotHours = prefsSafety.getFloat("pilot_hours", 0f).toDouble()
            pendingFlightHours = prefsSafety.getFloat("pending_flight_hours", 0f).toDouble()

            uiMostrarPerfilPiloto(name, license)

            if (currentAccessToken.isNotEmpty() && isNetworkAvailable()) {
                descargarPerfilPiloto()
            }
        } else {
            // SI NO HAY SESIÓN (Bypass de DJI): Mostramos el formulario para que inicie sesión aquí mismo
            layoutPilotProfile.visibility = View.GONE
            layoutLoginForm.visibility = View.VISIBLE
        }
    }

    private fun realizarLoginPilotoDJI() {
        val email = etPilotEmail.text.toString().trim()
        val pass = etPilotPassword.text.toString()

        if (email.isEmpty() || pass.isEmpty()) {
            mostrarToast("Llene todos los campos")
            return
        }

        btnPilotLogin.isEnabled = false
        btnPilotLogin.text = "VERIFICANDO..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$supabaseUrl/auth/v1/token?grant_type=password")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", anonKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val jsonInput = JSONObject().apply {
                    put("email", email)
                    put("password", pass)
                }.toString()

                conn.outputStream.use { os ->
                    val input = jsonInput.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val jsonResponse = JSONObject(response)
                    // Guardamos la sesión en el control DJI (con refresh token para renovarla sin volver a pedir contraseña)
                    SessionManager.saveSession(this@PilotConfigActivity, jsonResponse, email)

                    withContext(Dispatchers.Main) {
                        btnPilotLogin.isEnabled = true
                        btnPilotLogin.text = "INICIAR SESIÓN"
                        mostrarToast("Sesión iniciada correctamente")

                        // Refrescamos la pantalla para ocultar el login y mostrar el perfil
                        verificarSesionPilotoLocal()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        btnPilotLogin.isEnabled = true
                        btnPilotLogin.text = "INICIAR SESIÓN"
                        mostrarToast("Credenciales incorrectas")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnPilotLogin.isEnabled = true
                    btnPilotLogin.text = "INICIAR SESIÓN"
                    mostrarToast("Error de red. Conecta el control DJI a Wi-Fi.")
                }
            }
        }
    }



    private fun descargarPerfilPiloto() {
        if (!isNetworkAvailable()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pilotEndpoint = "$supabaseUrl/rest/v1/pilots?email=eq.$loggedPilotEmail&select=*"
                val pilotConn = URL(pilotEndpoint).openConnection() as HttpURLConnection
                pilotConn.requestMethod = "GET"
                pilotConn.setRequestProperty("apikey", anonKey)
                pilotConn.setRequestProperty("Authorization", "Bearer $currentAccessToken")

                if (pilotConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonArray = JSONArray(pilotConn.inputStream.bufferedReader().use { it.readText() })
                    if (jsonArray.length() > 0) {
                        val pilotData = jsonArray.getJSONObject(0)

                        val name = pilotData.optString("name", "Piloto")
                        val license = pilotData.optString("license", "No registrada")
                        loggedPilotHours = pilotData.optDouble("flight_hours", 0.0)

                        // Actualizar la caché local
                        prefsSafety.edit().apply {
                            putString("pilot_name", name)
                            putString("pilot_license", license)
                            putFloat("pilot_hours", loggedPilotHours.toFloat())
                            apply()
                        }

                        withContext(Dispatchers.Main) {
                            uiMostrarPerfilPiloto(name, license)
                        }
                    }
                }
            } catch (e: Exception) {
                // Falla silenciosa, se mantienen los datos cacheados mostrados
            }
        }
    }

    private fun configurarBotonesPerfil() {
        btnPilotLogout.setOnClickListener {
            prefsSafety.edit().apply {
                remove("pilot_name")
                remove("pilot_license")
                remove("pilot_hours")
                apply()
            }
            SessionManager.logout(this)
            mostrarToast("Sesión cerrada")
            finish() // Regresar a la pantalla anterior
        }

        btnAddHours.setOnClickListener {
            val hoursToAdd = etAddFlightHours.text.toString().toDoubleOrNull()
            if (hoursToAdd == null || hoursToAdd <= 0.0) return@setOnClickListener

            loggedPilotHours += hoursToAdd
            pendingFlightHours += hoursToAdd
            prefsSafety.edit().putFloat("pilot_hours", loggedPilotHours.toFloat()).putFloat("pending_flight_hours", pendingFlightHours.toFloat()).apply()

            etAddFlightHours.setText("")
            actualizarUIHoras()
            sincronizarHorasPendientes(esManual = true)
        }
    }

    private fun uiMostrarPerfilPiloto(name: String, license: String) {
        layoutPilotProfile.visibility = View.VISIBLE
        tvLoggedPilotName.text = name
        tvLoggedPilotLicense.text = "Licencia: $license"
        actualizarUIHoras()
    }

    private fun actualizarUIHoras() {
        val horasFormat = "%.1f".format(Locale.US, loggedPilotHours)

        tvCurrentFlightHours.text = horasFormat
        val tvPending = findViewById<TextView>(R.id.tv_pending_hours)
        if (pendingFlightHours > 0) {
            val pendientesFormat = "%.1f".format(Locale.US, pendingFlightHours)
            tvPending.text = "Sin sincronizar: $pendientesFormat h"
            tvPending.visibility = View.VISIBLE
            tvCurrentFlightHours.setTextColor(ContextCompat.getColor(this, R.color.nx_caution))
        } else {
            tvPending.visibility = View.GONE
            tvCurrentFlightHours.setTextColor(ContextCompat.getColor(this, R.color.nx_telemetry))
        }
    }

    private fun sincronizarHorasPendientes(esManual: Boolean = false) {
        if (pendingFlightHours <= 0.0 || loggedPilotEmail.isEmpty()) return
        if (!isNetworkAvailable()) {
            if (esManual) mostrarToast("Horas guardadas localmente. Se sincronizarán con internet.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val emailCodificado = URLEncoder.encode(loggedPilotEmail, "UTF-8")
                val endpoint = "$supabaseUrl/rest/v1/pilots?email=eq.$emailCodificado"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "PATCH"
                connection.setRequestProperty("apikey", anonKey)
                connection.setRequestProperty("Authorization", "Bearer $currentAccessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Prefer", "return=minimal")
                connection.doOutput = true

                val jsonBody = JSONObject().apply { put("flight_hours", loggedPilotHours) }
                connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

                if (connection.responseCode in 200..204) {
                    pendingFlightHours = 0.0
                    prefsSafety.edit().putFloat("pending_flight_hours", 0f).apply()
                    withContext(Dispatchers.Main) {
                        actualizarUIHoras()
                        if (esManual) mostrarToast("Horas sincronizadas con la nube.")
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun descargarHistorialDeTurbinas(silencioso: Boolean = false) {
        val selectedProjectId = prefsSafety.getString("last_selected_project_id", "") ?: ""

        if (selectedProjectId.isEmpty()) {
            if (!silencioso) mostrarToast("Selecciona un proyecto en la pantalla principal del Dron primero.")
            return
        }

        val inspectedTurbinesJson = prefsSafety.getString("cache_inspected_turbines_$selectedProjectId", "[]") ?: "[]"

        try {
            val insArray = JSONArray(inspectedTurbinesJson)
            val historyList = mutableListOf<Triple<String, String, String>>()

            for (i in 0 until insArray.length()) {
                val obj = insArray.getJSONObject(i)
                val name = obj.getString("turbine_name")
                val status = obj.optString("status", "Sin estado")
                val date = obj.optString("inspection_date", "Fecha no registrada").take(10)

                if (status == "Inspeccionada" || (status != "Pendiente" && status.isNotEmpty()) || date != "null") {
                    historyList.add(Triple(name, status, date))
                }
            }

            if (historyList.isEmpty() && !silencioso) {
                mostrarToast("No hay turbinas inspeccionadas localmente para este parque.")
            }

            lvInspectionHistory.adapter = object : ArrayAdapter<Triple<String, String, String>>(
                this, R.layout.item_nx_history, historyList
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_nx_history, parent, false)
                    val (turbina, estado, fecha) = getItem(position)!!
                    row.findViewById<TextView>(R.id.tv_history_turbine).text = turbina
                    row.findViewById<TextView>(R.id.tv_history_status).text = estado
                    row.findViewById<TextView>(R.id.tv_history_date).text = fecha
                    return row
                }
            }
            findViewById<TextView>(R.id.tv_inspected_count).text = historyList.size.toString()

        } catch (e: Exception) {
            mostrarToast("Error leyendo el historial local.")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registrarMonitorDeRed() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (pendingFlightHours > 0.0 && loggedPilotEmail.isNotEmpty()) sincronizarHorasPendientes()
            }
        })
    }

    private fun mostrarToast(m: String) { runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }
}