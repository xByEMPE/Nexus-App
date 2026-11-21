package mx.com.nexusio.fly.drone

import mx.com.nexusio.fly.ui.NexusThemeMode
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import mx.com.nexusio.fly.ui.BackendConfig
import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import mx.com.nexusio.fly.BuildConfig
import mx.com.nexusio.fly.R
import mx.com.nexusio.fly.ui.SessionManager
import mx.com.nexusio.fly.ui.LoginDialog
import mx.com.nexusio.fly.ui.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import mx.com.nexusio.fly.drone.onestop.RotorOrientationView
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import mx.com.nexusio.fly.drone.onestop.job.OneStopJobDialog
import mx.com.nexusio.fly.drone.onestop.job.ProjectTurbineStore
import mx.com.nexusio.fly.drone.onestop.job.WtgmRemoteSource

// Imports DJI MSDK V5
import dji.v5.common.error.IDJIError
import dji.open.pilot.client.manager.ServerState
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.common.register.InitSettings
import dji.v5.common.register.SDKLinKMode
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.ControlMode
import dji.v5.common.callback.CommonCallbacks
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), SDKManagerCallback {

    private val TAG = "Nexus_MainActivity"
    private val PERMISSION_REQUEST_CODE = 12345
    private val MAX_REGISTER_ATTEMPTS = 6
    private val REGISTER_RETRY_MS = 5000L

    // Llaves para persistencia
    private val PREFS_NAME = "Nexus_Inspection_State"
    private val KEY_COMPLETED_BLADES = "completed_blades_set"
    private val PREFS_SAFETY = "Nexus_Safety_Prefs"

    // URL Y KEY DE SUPABASE
    private val supabaseUrl = BackendConfig.SUPABASE_URL
    private val anonKey = BackendConfig.SUPABASE_ANON_KEY

    private lateinit var prefsSafety: SharedPreferences
    private lateinit var tvDroneStatus: TextView
    private lateinit var btnAircraftSettings: Button
    private lateinit var btnFlight: Button
    private lateinit var btnReset: Button
    private lateinit var spinnerBlade: Spinner
    private lateinit var spinnerRcMode: Spinner
    private lateinit var etBladeLength: EditText
    private lateinit var rotorOrientationView: RotorOrientationView


    // Elementos de Jerarquía Central
    private lateinit var spinnerProject: Spinner
    private lateinit var tvProjectClient: TextView
    private lateinit var tvProjectFarm: TextView
    private lateinit var lvPendingTurbines: ListView
    private lateinit var btnSyncTurbines: Button

    // Listas para almacenar los datos
    private var projectsList = mutableListOf<JSONObject>()
    private var turbinesList = mutableListOf<JSONObject>()

    private var selectedTurbineName: String? = null
    private var selectedProjectId: String? = null

    // Datos de turbina One-Stop (job + altura de buje), guardados por proyecto
    private lateinit var turbineStore: ProjectTurbineStore
    private lateinit var tvJobSummary: TextView
    private var jobDialog: OneStopJobDialog? = null
    private var selectedWindFarm: String? = null
    private val jobPromptedProjects = mutableSetOf<String>()
    private val jobPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        jobDialog?.onFilePicked(uri)
    }

    private var isRcModeInitialized = false
    private var isDroneConnected = false

    private val skyConditions = arrayOf(
        "Indefinido", "Sin nubes significativas", "Cielo soleado", "Cielo despejado",
        "Parcialmente nublado (25%)", "Parcialmente nublado (50%)", "Cielo nublado",
        "Cielo nublado (75%)", "Casi cubierto / Encapotado", "Cubierto por completo",
        "Totalmente oculto (niebla o nieve)"
    )

    private val windConditions = arrayOf(
        "Entre 1 a 3 m/s",
        "Entre 4 a 6 m/s",
        "Entre 7 a 9 m/s",
        "Mayor a 10 m/s"
    )

    private val REQUIRED_PERMISSION_LIST = arrayOf(
        Manifest.permission.VIBRATE,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val flightLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val finishedBlade = result.data?.getStringExtra("COMPLETED_BLADE")
            val isOneStopCompleted = result.data?.getBooleanExtra("ONE_STOP_COMPLETED", false) ?: false

            val turbineToMark = selectedTurbineName ?: prefsSafety.getString("last_selected_turbine", "")
            val currentProjId = selectedProjectId ?: prefsSafety.getString("last_selected_project_id", "")

            // CASO 1: Viene de "One Stop" (Terminó la turbina completa de un solo vuelo)
            if (isOneStopCompleted || finishedBlade == "ALL") {
                mostrarToast("¡Inspección One Stop de turbina finalizada con éxito!")

                if (!turbineToMark.isNullOrEmpty() && !currentProjId.isNullOrEmpty()) {
                    marcarTurbinaComoInspeccionadaLocalmente(turbineToMark, currentProjId)
                }

                borrarProgresoInspeccion()
                selectedTurbineName = null
                prefsSafety.edit().remove("last_selected_turbine").apply()

                if (projectsList.isNotEmpty() && spinnerProject.selectedItemPosition >= 0) {
                    val currentProject = projectsList[spinnerProject.selectedItemPosition]
                    descargarTurbinasDelProyecto(currentProject)
                }

                actualizarDisenoSpinner()
                actualizarVisibilidadRotorYValidar()

                // CASO 2: Viene de "Three-stop" (Inspección pala por pala convencional)
            } else if (finishedBlade != null) {
                guardarPalaCompletada(finishedBlade)
                val completadas = getPalasCompletadas()

                if (completadas.size >= 3) {
                    mostrarToast("¡Inspección de turbina finalizada con éxito!")

                    if (!turbineToMark.isNullOrEmpty() && !currentProjId.isNullOrEmpty()) {
                        marcarTurbinaComoInspeccionadaLocalmente(turbineToMark, currentProjId)
                    }

                    borrarProgresoInspeccion()
                    selectedTurbineName = null
                    prefsSafety.edit().remove("last_selected_turbine").apply()

                    if (projectsList.isNotEmpty() && spinnerProject.selectedItemPosition >= 0) {
                        val currentProject = projectsList[spinnerProject.selectedItemPosition]
                        descargarTurbinasDelProyecto(currentProject)
                    }
                } else {
                    mostrarToast("Pala $finishedBlade guardada. Faltan ${3 - completadas.size} palas para terminar la turbina.")
                }

                actualizarDisenoSpinner()
                actualizarVisibilidadRotorYValidar()
            }
        }
    }

    override fun onDestroy() {
        registerHandler.removeCallbacks(retryRegisterRunnable)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NexusThemeMode.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ocultarBarraDeEstado()
        configurarBotonModo()

        prefsSafety = getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE)
        turbineStore = ProjectTurbineStore(prefsSafety)
        jobDialog = OneStopJobDialog(
            activity = this,
            store = turbineStore,
            catalog = DroneCatalog.fromAssets(this),
            remote = WtgmRemoteSource(supabaseUrl, anonKey) {
                when (val t = SessionManager.freshToken(this, supabaseUrl, anonKey)) {
                    is SessionManager.Token.Valid -> t.value
                    is SessionManager.Token.Offline -> t.value
                    SessionManager.Token.LoginRequired -> ""
                }
            },
            pickFile = { jobPicker.launch(arrayOf("*/*")) },
            onSaved = { actualizarVisibilidadRotorYValidar() },
        )

        // 1. INICIALIZACIÓN DE TODAS LAS VISTAS (OBLIGATORIO AL INICIO)
        tvDroneStatus = findViewById(R.id.tv_drone_status)
        btnAircraftSettings = findViewById(R.id.btn_aircraft_settings)
        btnFlight = findViewById(R.id.btn_flight)
        btnReset = findViewById(R.id.btn_reset_inspection)
        spinnerBlade = findViewById(R.id.spinner_blade)
        spinnerRcMode = findViewById(R.id.spinner_rc_mode)
        etBladeLength = findViewById(R.id.et_blade_length)
        rotorOrientationView = findViewById(R.id.rotor_orientation_view)
        tvJobSummary = findViewById(R.id.tv_job_summary)
        findViewById<Button>(R.id.btn_job_configure).setOnClickListener { abrirDialogoJob() }
        rotorOrientationView.onOrientationChangedListener = {
            // Cada vez que el piloto toca una pala, evaluamos si ya podemos activar el botón
            actualizarVisibilidadRotorYValidar()
        }

        spinnerProject = findViewById(R.id.spinner_project)
        tvProjectClient = findViewById(R.id.tv_project_client)
        tvProjectFarm = findViewById(R.id.tv_project_farm)
        lvPendingTurbines = findViewById(R.id.lv_pending_turbines)
        btnSyncTurbines = findViewById(R.id.btn_sync_turbines)

        val rbThreeStop = findViewById<RadioButton>(R.id.rb_three_stop)
        val rbSingleStop = findViewById<RadioButton>(R.id.rb_single_stop)
        val rgInspectionType = findViewById<RadioGroup>(R.id.rg_inspection_type)

        // 2. LÓGICA Y LISTENERS
        rbThreeStop.isChecked = true
        rbSingleStop.visibility = View.VISIBLE

        rgInspectionType.setOnCheckedChangeListener { _, _ ->
            actualizarVisibilidadRotorYValidar()
            solicitarJobSiHaceFalta()
        }

        etBladeLength.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                actualizarVisibilidadRotorYValidar()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        findViewById<TextView>(R.id.tv_config_title).setOnClickListener {
            if (SessionManager.email(this).isNotEmpty()) {
                startActivity(Intent(this, PilotConfigActivity::class.java))
            } else {
                LoginDialog.show(this) {
                    actualizarPildoraPiloto()
                    descargarProyectos()
                }
            }
        }

        actualizarDisenoSpinner()
        configurarSpinnerModoPalancas()

        btnSyncTurbines.setOnClickListener { descargarProyectos() }
        btnFlight.setOnClickListener { mostrarDialogoConfirmacionVuelo() }

        btnAircraftSettings.setOnClickListener {
            startActivity(Intent(this, AircraftSettingsActivity::class.java))
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reiniciar Inspección")
                .setMessage("¿Desea borrar el registro de las palas ya inspeccionadas?")
                .setPositiveButton("SÍ, BORRAR") { _, _ ->
                    borrarProgresoInspeccion()
                    actualizarDisenoSpinner()
                    mostrarToast("Registro de inspección reiniciado")
                }
                .setNegativeButton("CANCELAR", null)
                .show()
        }

        val lastLength = prefsSafety.getFloat("last_blade_length", 0f)
        if (lastLength > 0f) etBladeLength.setText(lastLength.toString())

        selectedTurbineName = prefsSafety.getString("last_selected_turbine", null)
        selectedProjectId = prefsSafety.getString("last_selected_project_id", null)

        checkAndRequestPermissions()

        val tvAppVersion = findViewById<TextView>(R.id.tv_app_version)
        tvAppVersion.text = "Nexus App v${BuildConfig.VERSION_NAME}"
        UpdateManager(this).checkForUpdates(BuildConfig.VERSION_CODE)
    }

    override fun onResume() {
        super.onResume()
        actualizarPildoraPiloto()
        if (!sdkRegistered) { registerAttempts = 0; registrarDJISDK() }
        if (SessionManager.email(this).isNotEmpty() && projectsList.isEmpty()) {
            descargarProyectos()
        }
    }

    // ==========================================
    // LÓGICA OFFLINE-FIRST: PROYECTOS -> TURBINAS
    // ==========================================
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun descargarProyectos() {
        // El correo se conserva aunque la sesión venza: con él se lee la lista guardada sin borrar datos de la app.
        val email = SessionManager.email(this)

        if (email.isEmpty()) {
            mostrarToast("Inicia sesión desde tu perfil de piloto para ver tus proyectos.")
            return
        }

        btnSyncTurbines.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cached = prefsSafety.getString("cache_projects_$email", "[]") ?: "[]"
                var jsonResponse = cached
                val session = SessionManager.freshToken(this@MainActivity, supabaseUrl, anonKey)

                if (session is SessionManager.Token.Valid && isNetworkAvailable()) {
                    withContext(Dispatchers.Main) { mostrarToast("Actualizando proyectos...") }

                    val endpoint = "$supabaseUrl/rest/v1/projects?select=id,name,client,wind_farm,staff_list&project_type=eq.DRONE"
                    val url = URL(endpoint)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("apikey", anonKey)
                    connection.setRequestProperty("Authorization", "Bearer ${session.value}")

                    when (connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                            prefsSafety.edit().putString("cache_projects_$email", jsonResponse).apply()
                        }
                        HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                            // Sesión de una versión anterior (sin refresh token) ya vencida
                            SessionManager.expire(this@MainActivity)
                            withContext(Dispatchers.Main) { mostrarToast("Tu sesión venció: inicia sesión de nuevo para actualizar. Se muestran los proyectos guardados.") }
                        }
                        else -> withContext(Dispatchers.Main) { mostrarToast("No se pudo actualizar: se muestran los proyectos guardados.") }
                    }
                } else if (session is SessionManager.Token.LoginRequired) {
                    withContext(Dispatchers.Main) { mostrarToast("Tu sesión venció: inicia sesión de nuevo para actualizar. Se muestran los proyectos guardados.") }
                } else {
                    withContext(Dispatchers.Main) { mostrarToast("Modo Offline: Cargando proyectos locales...") }
                }

                val jsonArray = JSONArray(jsonResponse.ifBlank { "[]" })
                projectsList.clear()
                val nombresProyectos = mutableListOf<String>()

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val staffListStr = item.optString("staff_list", "")
                    if (staffListStr.contains(email, ignoreCase = true)) {
                        projectsList.add(item)
                        nombresProyectos.add(item.getString("name"))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (projectsList.isEmpty()) {
                        mostrarToast(if (isNetworkAvailable()) "No tienes proyectos asignados." else "No hay proyectos guardados para modo offline.")
                        btnSyncTurbines.isEnabled = true
                        spinnerProject.adapter = null
                        lvPendingTurbines.adapter = null
                        tvProjectClient.text = "Cliente: -"
                        tvProjectFarm.text = "Parque: -"
                        return@withContext
                    }

                    val adapter = ArrayAdapter(this@MainActivity, R.layout.item_nx_spinner, nombresProyectos).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
                    spinnerProject.adapter = adapter
                    btnSyncTurbines.isEnabled = true

                    val lastSavedProjectId = prefsSafety.getString("last_selected_project_id", "")
                    var savedIndex = 0

                    if (!lastSavedProjectId.isNullOrEmpty()) {
                        val foundIndex = projectsList.indexOfFirst { it.getString("id") == lastSavedProjectId }
                        if (foundIndex != -1) savedIndex = foundIndex
                    }

                    spinnerProject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                            val project = projectsList[pos]
                            selectedProjectId = project.getString("id")
                            selectedWindFarm = project.optString("wind_farm", "").ifEmpty { null }

                            prefsSafety.edit().putString("last_selected_project_id", selectedProjectId).apply()

                            tvProjectClient.text = "Cliente: ${project.optString("client", "No definido")}"
                            tvProjectFarm.text = "Parque: ${project.optString("wind_farm", "No definido")}"

                            descargarTurbinasDelProyecto(project)
                            actualizarVisibilidadRotorYValidar()
                            solicitarJobSiHaceFalta()
                        }
                        override fun onNothingSelected(p0: AdapterView<*>?) {}
                    }

                    if (projectsList.isNotEmpty()) {
                        spinnerProject.setSelection(savedIndex)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mostrarToast("Fallo al procesar proyectos.")
                    btnSyncTurbines.isEnabled = true
                }
            }
        }
    }

    private fun descargarTurbinasDelProyecto(project: JSONObject) {
        val projectId = project.getString("id")
        val windFarmName = project.optString("wind_farm", "")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = (SessionManager.freshToken(this@MainActivity, supabaseUrl, anonKey) as? SessionManager.Token.Valid)?.value ?: anonKey

                var farmId = prefsSafety.getString("cache_farm_id_$windFarmName", "") ?: ""
                var masterTurbinesJson = prefsSafety.getString("cache_master_turbines_$farmId", "[]") ?: "[]"

                val localInspectedStr = prefsSafety.getString("cache_inspected_turbines_$projectId", "[]") ?: "[]"
                val localMap = mutableMapOf<String, JSONObject>()
                try {
                    val localArray = JSONArray(localInspectedStr)
                    for (i in 0 until localArray.length()) {
                        val obj = localArray.getJSONObject(i)
                        localMap[obj.getString("turbine_name")] = obj
                    }
                } catch (e: Exception) {}

                if (isNetworkAvailable()) {
                    val encodedFarm = URLEncoder.encode(windFarmName, "UTF-8")
                    val farmUrl = URL("$supabaseUrl/rest/v1/wind_farms?name=eq.$encodedFarm&select=id")
                    val farmConn = farmUrl.openConnection() as HttpURLConnection
                    farmConn.setRequestProperty("apikey", anonKey)
                    farmConn.setRequestProperty("Authorization", "Bearer $token")

                    if (farmConn.responseCode == HttpURLConnection.HTTP_OK) {
                        val farmArray = JSONArray(farmConn.inputStream.bufferedReader().use { it.readText() })
                        if (farmArray.length() > 0) {
                            farmId = farmArray.getJSONObject(0).getString("id")
                            prefsSafety.edit().putString("cache_farm_id_$windFarmName", farmId).apply()
                        }
                    }

                    if (farmId.isNotEmpty()) {
                        val masterUrl = URL("$supabaseUrl/rest/v1/wind_turbines?farm_id=eq.$farmId&select=name&order=name.asc")
                        val masterConn = masterUrl.openConnection() as HttpURLConnection
                        masterConn.setRequestProperty("apikey", anonKey)
                        masterConn.setRequestProperty("Authorization", "Bearer $token")

                        if (masterConn.responseCode == HttpURLConnection.HTTP_OK) {
                            masterTurbinesJson = masterConn.inputStream.bufferedReader().use { it.readText() }
                            prefsSafety.edit().putString("cache_master_turbines_$farmId", masterTurbinesJson).apply()
                        }

                        val projectTurbinesUrl = URL("$supabaseUrl/rest/v1/turbines?project_id=eq.$projectId&select=turbine_name,status,inspection_date,blade_length")
                        val projectConn = projectTurbinesUrl.openConnection() as HttpURLConnection
                        projectConn.setRequestProperty("apikey", anonKey)
                        projectConn.setRequestProperty("Authorization", "Bearer $token")

                        if (projectConn.responseCode == HttpURLConnection.HTTP_OK) {
                            val supaStr = projectConn.inputStream.bufferedReader().use { it.readText() }
                            val supaArray = JSONArray(supaStr)

                            for (i in 0 until supaArray.length()) {
                                val supaObj = supaArray.getJSONObject(i)
                                val tName = supaObj.getString("turbine_name")

                                val localObj = localMap[tName]
                                if (localObj == null) {
                                    localMap[tName] = supaObj
                                } else {
                                    val localStatus = localObj.optString("status", "")
                                    if (localStatus != "Inspeccionada") {
                                        localMap[tName] = supaObj
                                    }
                                }
                            }

                            val mergedArray = JSONArray()
                            localMap.values.forEach { mergedArray.put(it) }
                            prefsSafety.edit().putString("cache_inspected_turbines_$projectId", mergedArray.toString()).apply()
                        }
                    }
                }

                if (farmId.isEmpty() || masterTurbinesJson == "[]") {
                    withContext(Dispatchers.Main) {
                        mostrarToast("No hay datos de turbinas descargados para el parque '$windFarmName'.")
                        lvPendingTurbines.adapter = null
                    }
                    return@launch
                }

                val masterTurbines = mutableListOf<String>()
                val turbArray = JSONArray(masterTurbinesJson)
                for (i in 0 until turbArray.length()) {
                    masterTurbines.add(turbArray.getJSONObject(i).getString("name"))
                }

                val inspectedSet = mutableSetOf<String>()
                val turbLengthMap = mutableMapOf<String, Double>()

                localMap.values.forEach { obj ->
                    val tName = obj.getString("turbine_name")
                    val tStatus = obj.optString("status", "")
                    val tDate = obj.optString("inspection_date", "null")

                    if (obj.has("blade_length")) {
                        turbLengthMap[tName] = obj.optDouble("blade_length", 0.0)
                    }

                    if (tStatus == "Inspeccionada" || (tStatus != "Pendiente" && tStatus.isNotEmpty()) || tDate != "null") {
                        inspectedSet.add(tName)
                    }
                }

                withContext(Dispatchers.Main) {
                    turbinesList.clear()
                    masterTurbines.forEach { tName ->
                        val obj = JSONObject().apply { put("turbine_name", tName) }
                        turbinesList.add(obj)
                    }

                    val adapter = object : ArrayAdapter<String>(this@MainActivity, R.layout.item_nx_list, masterTurbines) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val view = super.getView(position, convertView, parent) as TextView
                            val tName = getItem(position) ?: ""

                            if (tName == selectedTurbineName) {
                                view.setBackgroundResource(R.drawable.bg_nx_row_selected)
                                view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg))
                                view.text = if (inspectedSet.contains(tName)) "$tName ✓" else tName
                            } else if (inspectedSet.contains(tName)) {
                                view.setBackgroundColor(Color.TRANSPARENT)
                                view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_telemetry))
                                view.text = "$tName ✓"
                            } else {
                                view.setBackgroundColor(Color.TRANSPARENT)
                                view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg))
                                view.text = tName
                            }
                            return view
                        }
                    }

                    lvPendingTurbines.adapter = adapter

                    lvPendingTurbines.setOnItemClickListener { _, _, position, _ ->
                        selectedTurbineName = masterTurbines[position]
                        prefsSafety.edit().putString("last_selected_turbine", selectedTurbineName).apply()

                        val bLen = turbLengthMap[selectedTurbineName] ?: 0.0
                        if (bLen > 0.0) {
                            etBladeLength.setText(bLen.toString())
                        }

                        adapter.notifyDataSetChanged()
                        actualizarVisibilidadRotorYValidar()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { mostrarToast("Error cruzando datos de turbinas.") }
            }
        }
    }

    // ==========================================
    // VALIDACIONES Y CONTROL DEL ROTOR
    // ==========================================
    private fun actualizarVisibilidadRotorYValidar() {
        val isOneStop = findViewById<RadioButton>(R.id.rb_single_stop).isChecked
        val layoutThreeStopParams = findViewById<LinearLayout>(R.id.layout_three_stop_params)
        val layoutOneStopRotor = findViewById<LinearLayout>(R.id.layout_one_stop_rotor_selector)

        findViewById<LinearLayout>(R.id.layout_one_stop_job).visibility = if (isOneStop) View.VISIBLE else View.GONE
        actualizarResumenJob()

        if (isOneStop) {
            layoutThreeStopParams.visibility = View.GONE
            // El rotor solo se muestra si ya hay una turbina seleccionada
            if (selectedTurbineName != null) {
                layoutOneStopRotor.visibility = View.VISIBLE
            } else {
                layoutOneStopRotor.visibility = View.GONE
            }
        } else {
            layoutThreeStopParams.visibility = View.VISIBLE
            layoutOneStopRotor.visibility = View.GONE
        }

        validarBotonContinuar()
    }

    private fun largoDePala() = etBladeLength.text.toString().trim().replace(",", ".").toDoubleOrNull() ?: 0.0

    private fun abrirDialogoJob() {
        val projectId = selectedProjectId
        if (projectId == null) { mostrarToast("Seleccione primero un proyecto"); return }
        jobDialog?.show(projectId, largoDePala(), selectedWindFarm)
    }

    /** El job y la altura de buje se piden una sola vez por proyecto; después quedan guardados. */
    private fun solicitarJobSiHaceFalta() {
        val projectId = selectedProjectId ?: return
        val isOneStop = findViewById<RadioButton>(R.id.rb_single_stop).isChecked
        if (!isOneStop || turbineStore.hasHubHeight(projectId) || largoDePala() <= 0.0) return
        if (jobPromptedProjects.add(projectId)) abrirDialogoJob()
    }

    private fun actualizarResumenJob() {
        val projectId = selectedProjectId
        val hub = turbineStore.loadHubHeight(projectId)
        tvJobSummary.text = if (hub == null) getString(R.string.onestop_job_summary_pending)
        else getString(
            R.string.onestop_job_summary_ready, hub,
            turbineStore.loadJob(projectId)?.name ?: getString(R.string.onestop_job_summary_no_job)
        )
    }

    private fun validarBotonContinuar() {
        val isOneStop = findViewById<RadioButton>(R.id.rb_single_stop).isChecked
        val isLengthValid = etBladeLength.text.isNotEmpty()
        val isTurbineSelected = selectedTurbineName != null

        val isReady = if (isOneStop) {
            val isRotorMapped = rotorOrientationView.isMappingValid()
            // One-Stop necesita el largo de pala (rotor) y la altura de buje del proyecto
            val isJobReady = isLengthValid && turbineStore.hasHubHeight(selectedProjectId)
            isDroneConnected && isTurbineSelected && isRotorMapped && isJobReady
        } else {
            // El Three-Stop tradicional sí sigue requiriendo longitud y pala seleccionada
            val selectedBlade = spinnerBlade.selectedItem?.toString() ?: ""
            val isBladeValid = selectedBlade != "Seleccionar Pala"
            isDroneConnected && isBladeValid && isLengthValid && isTurbineSelected
        }

        btnFlight.isEnabled = isReady
    }

    private fun mostrarDialogoConfirmacionVuelo() {
        val selectedBlade = spinnerBlade.selectedItem?.toString() ?: ""
        val bladeLength = etBladeLength.text.toString().trim().replace(",", ".").toFloatOrNull() ?: 0f
        val farmName = tvProjectFarm.text.toString().replace("Parque: ", "").trim()
        val turbineName = selectedTurbineName ?: "No seleccionada"

        prefsSafety.edit().apply {
            putString("last_selected_turbine", turbineName)
            putString("last_selected_project_id", selectedProjectId)
            apply()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }

        val isOneStop = findViewById<RadioButton>(R.id.rb_single_stop).isChecked

        val bladeMap = rotorOrientationView.getBladeMap()
        val mappedBladesText = if (isOneStop) "12h: ${bladeMap["12H"]} | 4h: ${bladeMap["4H"]} | 8h: ${bladeMap["8H"]}" else selectedBlade

        val tvResumen = TextView(this).apply {
            text = "Resumen de Inspección:\n\n• Parque: $farmName\n• Turbina: $turbineName\n• Pala(s): $mappedBladesText\n"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg))
            setPadding(0, 0, 0, 10)
        }
        layout.addView(tvResumen)

        val tvSkyLabel = TextView(this).apply {
            text = "Condición del Cielo:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg_muted))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10
            }
        }
        layout.addView(tvSkyLabel)

        val spinnerSkyDialog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, R.layout.item_nx_spinner, skyConditions).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
            setSelection(prefsSafety.getInt("last_sky_condition_pos", 0))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layout.addView(spinnerSkyDialog)

        val tvWindLabel = TextView(this).apply {
            text = "Velocidad del Viento:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg_muted))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20
            }
        }
        layout.addView(tvWindLabel)

        val spinnerWindDialog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, R.layout.item_nx_spinner, windConditions).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
            setSelection(prefsSafety.getInt("last_wind_condition_pos", 0))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        layout.addView(spinnerWindDialog)

        // La pantalla del control es baja: sin desplazamiento, el selector de viento queda fuera del diálogo.
        val scroll = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Confirmar Vuelo")
            .setView(scroll)
            .setPositiveButton("INICIAR VUELO") { _, _ ->
                val skyVal = spinnerSkyDialog.selectedItem.toString()
                val windValStr = spinnerWindDialog.selectedItem.toString()

                prefsSafety.edit().apply {
                    putFloat("last_blade_length", bladeLength)
                    putInt("last_sky_condition_pos", spinnerSkyDialog.selectedItemPosition)
                    putInt("last_wind_condition_pos", spinnerWindDialog.selectedItemPosition)
                    apply()
                }

                val isOneStop = findViewById<RadioButton>(R.id.rb_single_stop).isChecked

                val targetIntent = if (isOneStop) {
                    val rotorView = findViewById<RotorOrientationView>(R.id.rotor_orientation_view)
                    val bladeMap = rotorView?.getBladeMap() ?: mapOf("12H" to "A", "4H" to "B", "8H" to "C")

                    Intent(this, mx.com.nexusio.fly.drone.onestop.OneStopFlightActivity::class.java).apply {
                        putExtra("BLADE_LENGTH", bladeLength)
                        putExtra("PROJECT_ID", selectedProjectId)
                        putExtra("TURBINE_NAME", turbineName)
                        putExtra("SKY_CONDITION", skyVal)
                        putExtra("WIND_SPEED_STR", windValStr)
                        putExtra("BLADE_12H", bladeMap["12H"])
                        putExtra("BLADE_4H", bladeMap["4H"])
                        putExtra("BLADE_8H", bladeMap["8H"])
                    }
                } else {
                    Intent(this, FlightActivity::class.java).apply {
                        putExtra("IS_THREE_STOP", true)
                        putExtra("BLADE_LENGTH", bladeLength)
                        putExtra("PALA_ID", selectedBlade)
                        putExtra("HAS_ID_PHOTO", getPalasCompletadas().isNotEmpty())
                        putExtra("TURBINE_NAME", turbineName)
                        putExtra("SKY_CONDITION", skyVal)
                        putExtra("WIND_SPEED_STR", windValStr)
                    }
                }
                flightLauncher.launch(targetIntent)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    // ==========================================
    // SPINNERS SECUNDARIOS Y GUARDADO LOCAL
    // ==========================================
    private fun actualizarDisenoSpinner() {
        val completadas = getPalasCompletadas()
        val listaPalas = arrayOf("Seleccionar Pala", "Pala A", "Pala B", "Pala C")

        val adapter = object : ArrayAdapter<String>(this, R.layout.item_nx_spinner, listaPalas) {
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                val itemText = getItem(position) ?: ""

                if (completadas.contains(itemText)) {
                    view.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.nx_surface_raised))
                    view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_telemetry))
                    view.text = "$itemText  ✓"
                } else {
                    view.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.nx_surface_raised))
                    view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.nx_fg))
                }
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.item_nx_spinner_dropdown)
        spinnerBlade.adapter = adapter

        spinnerBlade.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                actualizarVisibilidadRotorYValidar()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun configurarSpinnerModoPalancas() {
        val rcModes = arrayOf("Modo 2 (Estándar)", "Modo 1", "Modo 3")
        val adapter = ArrayAdapter(this, R.layout.item_nx_spinner, rcModes).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
        spinnerRcMode.adapter = adapter

        val savedMode = prefsSafety.getInt("rc_mode", 2)
        val selectedIndex = when (savedMode) { 1 -> 1; 3 -> 2; else -> 0 }
        spinnerRcMode.setSelection(selectedIndex)

        spinnerRcMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isRcModeInitialized) { isRcModeInitialized = true; return }

                val (modeToSave, hardwareMode) = when (position) {
                    1 -> Pair(1, ControlMode.JP)
                    2 -> Pair(3, ControlMode.CH)
                    else -> Pair(2, ControlMode.USA)
                }

                prefsSafety.edit().putInt("rc_mode", modeToSave).apply()

                val rcModeKey = KeyTools.createKey(RemoteControllerKey.KeyControlMode)
                KeyManager.getInstance().setValue(rcModeKey, hardwareMode, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { Log.d(TAG, "Hardware RC configurado en MODO $modeToSave") }
                    override fun onFailure(error: IDJIError) { Log.e(TAG, "Error configurando RC: ${error.description()}") }
                })
                try {
                    val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                } catch (e: Exception) { e.printStackTrace() }
                mostrarToast("Control configurado en MODO $modeToSave")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun marcarTurbinaComoInspeccionadaLocalmente(turbineName: String, projectId: String) {
        val cacheKey = "cache_inspected_turbines_$projectId"
        val jsonStr = prefsSafety.getString(cacheKey, "[]") ?: "[]"

        try {
            val jsonArray = JSONArray(jsonStr)
            var existe = false

            val fechaActual = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("turbine_name") == turbineName) {
                    obj.put("status", "Inspeccionada")
                    obj.put("inspection_date", fechaActual)
                    existe = true
                    break
                }
            }

            if (!existe) {
                val newObj = JSONObject()
                newObj.put("turbine_name", turbineName)
                newObj.put("status", "Inspeccionada")
                newObj.put("inspection_date", fechaActual)
                jsonArray.put(newObj)
            }

            prefsSafety.edit().putString(cacheKey, jsonArray.toString()).apply()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // DJI SDK INIT & MEMORIA
    // ==========================================
    private fun getPalasCompletadas(): MutableSet<String> {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getStringSet(KEY_COMPLETED_BLADES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }
    private fun guardarPalaCompletada(bladeId: String) {
        val actuales = getPalasCompletadas()
        actuales.add(bladeId)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(KEY_COMPLETED_BLADES, actuales).apply()
    }
    private fun borrarProgresoInspeccion() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_COMPLETED_BLADES).apply()
    }

    private fun mostrarToast(m: String) { runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSION_LIST.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) registrarDJISDK() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // El registro no depende de que el usuario conceda todos los permisos (p. ej. WRITE_EXTERNAL_STORAGE se deniega siempre en Android 11+).
        if (requestCode == PERMISSION_REQUEST_CODE) registrarDJISDK()
    }

    // Registro del SDK: idempotente y con reintentos, porque el primer registro necesita internet para validar la API key.
    private var sdkInitStarted = false
    private var sdkInitComplete = false
    private var sdkRegistered = false
    private var registerAttempts = 0
    private val registerHandler = Handler(Looper.getMainLooper())
    private val retryRegisterRunnable = Runnable { intentarRegistroSDK() }

    private fun registrarDJISDK() {
        if (sdkRegistered) return
        if (!sdkInitStarted) {
            sdkInitStarted = true
            // MSDK 5.20: init(context, InitSettings, callback); LOCAL = la app controla el dron directamente (no plugin de DJI Pilot)
            try { SDKManager.getInstance().init(this, InitSettings(SDKLinKMode.LOCAL), this) } catch (e: Exception) {
                sdkInitStarted = false
                Log.e(TAG, e.message ?: "")
                programarReintentoRegistro()
            }
        } else if (sdkInitComplete) {
            intentarRegistroSDK()
        }
    }

    // Se llama siempre a registerApp(), con o sin internet: tras el primer registro en línea, DJI valida desde su caché
    // y el registro funciona en campo sin señal. Solo el primer registro necesita conexión.
    private fun intentarRegistroSDK() {
        if (sdkRegistered || !sdkInitComplete) return
        try { SDKManager.getInstance().registerApp() } catch (e: Exception) {
            Log.e(TAG, e.message ?: "")
            programarReintentoRegistro()
        }
    }

    private fun programarReintentoRegistro() {
        registerHandler.removeCallbacks(retryRegisterRunnable)
        if (sdkRegistered || registerAttempts >= MAX_REGISTER_ATTEMPTS) return
        registerAttempts++
        registerHandler.postDelayed(retryRegisterRunnable, REGISTER_RETRY_MS)
    }

    override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
        if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
            sdkInitComplete = true
            intentarRegistroSDK()
        }
    }
    override fun onRegisterSuccess() {
        sdkRegistered = true
        registerHandler.removeCallbacks(retryRegisterRunnable)
        initDroneConnectionListener()
    }
    override fun onRegisterFailure(error: IDJIError?) {
        Log.e(TAG, "Registro del SDK fallido: ${error?.description()}")
        val sinRed = if (isNetworkAvailable()) "" else " (sin internet: si es la primera vez en este control, conéctalo a una red una vez)"
        mostrarToast("No se pudo registrar el SDK de DJI: ${error?.description() ?: "error desconocido"}$sinRed")
        programarReintentoRegistro()
    }
    override fun onProductConnect(productId: Int) = Unit
    override fun onProductDisconnect(productId: Int) = Unit
    override fun onProductChanged(productId: Int) = Unit
    @Deprecated("Obsoleto en MSDK 5.20")
    override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit

    // MSDK 5.20: solo aplican al modo plugin de DJI Pilot (SDKLinKMode.REMOTE_PILOT); en LOCAL no se usan.
    override fun onRemoteServerStateUpdated(serverState: ServerState?) = Unit
    override fun onRemoteServerCompatibleError(error: IDJIError?) {
        Log.e(TAG, "Servidor remoto incompatible: ${error?.description()}")
    }

    private fun initDroneConnectionListener() {
        val connectionKey = KeyTools.createKey(FlightControllerKey.KeyConnection)
        KeyManager.getInstance().listen(connectionKey, this) { _, newValue ->
            isDroneConnected = newValue ?: false
            runOnUiThread {
                actualizarInterfazDron(isDroneConnected)
                actualizarVisibilidadRotorYValidar()
            }
        }
    }

    /** Botón sol/luna: muestra el modo al que se cambia y alterna día/noche. */
    private fun configurarBotonModo() {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_theme_mode)
        val noche = NexusThemeMode.isNight(this)
        btn.setIconResource(if (noche) R.drawable.ic_nx_sun else R.drawable.ic_nx_moon)
        btn.contentDescription = if (noche) "Cambiar a modo día" else "Cambiar a modo noche"
        btn.setOnClickListener { NexusThemeMode.toggle(this) }
    }

    /** Pantalla completa: en el mando cada dp vertical cuenta (la barra reaparece deslizando). */
    private fun ocultarBarraDeEstado() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Píldora del piloto en la barra superior: nombre con sesión, "Iniciar sesión" sin ella. */
    private fun actualizarPildoraPiloto() {
        val email = SessionManager.email(this)
        val nombre = prefsSafety.getString("pilot_name", null)?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.tv_config_title).text = when {
            email.isEmpty() -> "Iniciar sesión"
            nombre != null -> nombre
            else -> email.substringBefore("@")
        }
    }

    private fun actualizarInterfazDron(isConnected: Boolean) {
        val color = if (isConnected) R.color.nx_telemetry else R.color.nx_caution
        tvDroneStatus.text = if (isConnected) "Dron conectado" else "Dron no conectado"
        tvDroneStatus.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        tvDroneStatus.setTextColor(ContextCompat.getColor(this, if (isConnected) R.color.nx_fg else R.color.nx_fg_muted))
    }
}