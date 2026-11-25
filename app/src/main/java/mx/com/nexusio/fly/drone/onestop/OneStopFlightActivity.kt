package mx.com.nexusio.fly.drone.onestop

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import mx.com.nexusio.fly.drone.onestop.planview.PlanScene
import mx.com.nexusio.fly.drone.onestop.planview.PlanView3D
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import mx.com.nexusio.fly.BuildConfig
import mx.com.nexusio.fly.R
import mx.com.nexusio.fly.drone.onestop.flightcore.CaptureInfo
import mx.com.nexusio.fly.drone.onestop.flightcore.PhotoRecord
import mx.com.nexusio.fly.drone.onestop.flightcore.PlannedPhoto
import mx.com.nexusio.fly.drone.onestop.flightcore.AppTransferCheck
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.DjiMissionPort
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocationHelper
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneStateProvider
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoMath
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionMapper
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPipeline
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPlan
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPlanningException
import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety
import mx.com.nexusio.fly.drone.onestop.flightcore.SafetyLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.SignedMissionGateway
import mx.com.nexusio.fly.drone.onestop.flightcore.TargetGeometry
import mx.com.nexusio.fly.drone.onestop.flightcore.TargetViolation
import mx.com.nexusio.fly.drone.onestop.flightcore.TransferSafetyResult
import mx.com.nexusio.fly.drone.onestop.flightcore.KmzAircraftInfo
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import mx.com.nexusio.fly.drone.onestop.job.DroneDetector
import mx.com.nexusio.fly.drone.onestop.job.ProjectTurbineStore
import mx.com.nexusio.fly.drone.onestop.job.TurbineDimensionsBuilder
import mx.com.nexusio.fly.drone.onestop.job.TurbineJob
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// DJI MSDK V5
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.camera.CameraExposureCompensation
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CustomExpandNameSettings
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EnCodingType
import dji.sdk.keyvalue.value.common.RelativePosition
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import dji.v5.ux.core.widget.fpv.FPVWidget

class OneStopFlightActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BLADE_LENGTH = "BLADE_LENGTH"
        private const val PREFS_SAFETY = "Nexus_Safety_Prefs" // mismo archivo que usa MainActivity
        private const val LOOP_PERIOD_MS = 50L // 20 Hz para la vigilancia del láser
        private const val RTH_TAG = "Nexus_RTH"
        private val MEASURE_ORDER = listOf(
            TurbineMeasurementLocation.SPINNER,
            TurbineMeasurementLocation.TIP_12,
            TurbineMeasurementLocation.BOTTOM_12,
            TurbineMeasurementLocation.TIP_4,
            TurbineMeasurementLocation.TIP_8,
        )
    }

    // Pasos de la interfaz (el vuelo lo ejecuta el dron con el KMZ, no esta máquina)
    private enum class InspectionState { ID_PHOTO, MEASURE, PLANNING, BRIGHTNESS, UPLOADING, FLYING, EMERGENCY, FINISHED }

    private var inspectionState = InspectionState.ID_PHOTO
    private var measureIndex = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val photoHandler = Handler(Looper.getMainLooper())

    private val proximityGuard = ProximityGuard()
    private var safetyPausePending = false

    // Sensor de medición según el equipo (láser o visión, como el original) y medición en curso; null si no se mide
    private var sensorProfile = SensorProfile(MeasurementSensor.LASER)

    // Cámara según el equipo (lente, obturador, exposición manual por escalones), como la app auditada
    private var captureProfile = CaptureProfile()
    private lateinit var cameraPort: CameraPort
    private var equipmentKey = ""
    private var exposureIndex = 0
    private var manualExposureActive = false
    // M4E: gran angular para la foto ID y las 5 medidas; tele desde el ajuste de brillo hasta el final de la misión
    private var inspectionLensActive = false
    private val avoidancePort = DjiObstacleAvoidancePort()

    // Vuelta a casa: altura por tramo para no cruzar el rotor si se pierde el enlace (ver ReturnHomeSafety)
    private val returnHomePort = DjiReturnHomePort()
    private val rthController = ReturnHomeHeightController(returnHomePort)
    private var rthProblem: String? = null
    private var rthIndex = 0
    /** Distancia frente al rotor a la que habría que poner casa (del último diagnóstico no seguro), para el botón H. */
    private var requiredHomeAheadM: Double? = null
    private lateinit var btnHome: Button

    // Paro de emergencia: botón en pantalla o tirón brusco del stick de cabeceo hacia atrás durante la misión
    private val emergencyPort = DjiEmergencyStopPort { plan?.id?.let { gateway.stop(it) } }
    private val emergencyStop = EmergencyStopController(emergencyPort)
    private val pullBack = PullBackDetector()
    private var sticks = Sticks()
    private var rcMode = 2
    private lateinit var btnEmergency: Button

    // Visor 3D del plan (miniatura abajo a la derecha / ampliado)
    private lateinit var planFrame: FrameLayout
    private lateinit var planView: PlanView3D
    private lateinit var planControls: View
    private lateinit var planHint: TextView
    private var planThumbParams: ConstraintLayout.LayoutParams? = null
    private var planExpanded = false
    private var planOrigin: GeoPoint? = null
    // Vídeo del dron: a pantalla completa, o en miniatura mientras el visor 3D está ampliado
    private lateinit var fpvWidget: FPVWidget
    private var fpvFullParams: ConstraintLayout.LayoutParams? = null
    private var videoMini = false
    private var planTurbine: WindTurbine? = null
    private val planBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cerrarVisorPlan()
    }
    private var sampler: LaserMeasurementSampler? = null
    private var distanceSource: MeasurementDistanceSource? = null
    private var samplingStartMs = 0L
    private var lastSensorSample = 0L
    private lateinit var droneState: DroneStateProvider
    private lateinit var gateway: SignedMissionGateway

    // Mapeo de palas recibido de MainActivity (letra por posición horaria)
    private var blade12Label = "A"
    private var blade4Label = "B"
    private var blade8Label = "C"
    private var bladeLengthM = 0.0
    private var hubHeightM = 0.0
    private var cameraFov = CameraFov.default()
    private var turbineJob: TurbineJob? = null
    private var projectId: String? = null
    private var turbineName: String? = null

    // Archivo de resultado: pala, cara, distancia a la raíz y lo que de verdad pasó en cada foto de la misión
    private var planResult: MissionPipeline.Result? = null
    private val captureFeed = DjiCaptureFeed()
    private var photoRecord: PhotoRecord? = null
    private var resultFile: File? = null

    private val measurements = LinkedHashMap<TurbineMeasurementLocation, TurbineMeasurementPoint?>()
    private val aimedPoints = HashMap<TurbineMeasurementLocation, GeoPoint>()
    private var plan: MissionPlan? = null
    private var firstWaypoint: GeoPoint? = null
    private var dimensions: TurbineDimensions? = null
    private var missionPaused = false

    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var crosshair: CrosshairView
    private lateinit var tvInfo: TextView
    private lateinit var btnAction: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var layoutBrightness: LinearLayout
    private lateinit var stepViews: Map<TurbineMeasurementLocation, TextView>

    private val missionStateListener = WaypointMissionExecuteStateListener { state ->
        if (state == WaypointMissionExecuteState.FINISHED && inspectionState == InspectionState.FLYING) {
            runOnUiThread { finalizarMision() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()
        setContentView(R.layout.activity_one_stop_flight)

        blade12Label = intent.getStringExtra("BLADE_12H") ?: "A"
        blade4Label = intent.getStringExtra("BLADE_4H") ?: "B"
        blade8Label = intent.getStringExtra("BLADE_8H") ?: "C"
        bladeLengthM = intent.getFloatExtra(EXTRA_BLADE_LENGTH, 0f).toDouble()

        // Datos guardados del proyecto (Cargar JOB): altura de buje obligatoria + job opcional
        val turbineStore = ProjectTurbineStore(getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE))
        projectId = intent.getStringExtra("PROJECT_ID")
        turbineName = intent.getStringExtra("TURBINE_NAME")
        val hub = turbineStore.loadHubHeight(projectId)
        if (hub == null || bladeLengthM <= 0.0) {
            Toast.makeText(this, R.string.onestop_error_no_hub, Toast.LENGTH_LONG).show()
            finish(); return
        }
        hubHeightM = hub
        turbineJob = turbineStore.loadJob(projectId)

        // Dron y cámara del enlace actual (el simulador DJI puede no reportarlos)
        val setup = DroneDetector.resolveSetup(DroneCatalog.fromAssets(this), turbineStore)
        if (setup == null) {
            Toast.makeText(this, R.string.onestop_error_no_aircraft, Toast.LENGTH_LONG).show()
            finish(); return
        }
        val aircraft = setup.aircraft
        cameraFov = setup.cameraFov
        sensorProfile = SensorProfile.forEquipment(setup.droneKey, setup.payload)
        captureProfile = CaptureProfile.forEquipment(setup.droneKey, setup.payload)
        cameraPort = DjiCameraPort(captureProfile.lens)
        equipmentKey = setup.droneKey
        exposureIndex = getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE).getInt(exposureKey(), 0)
            .coerceIn(0, (captureProfile.exposureSteps.size - 1).coerceAtLeast(0))

        droneState = DroneStateProvider()
        gateway = SignedMissionGateway(
            DjiMissionPort(this, aircraft.copy(gimbalSettleHoverS = captureProfile.gimbalSettleHoverS)),
            SafetyLimits(maxSpeedMps = 5.0, maxWaypoints = 3000, maxGimbalUp = 90.0, maxGimbalDown = 90.0),
            // TODO: sustituir por una clave del Android Keystore; esta vive solo en memoria y por sesión.
            ByteArray(32).also { SecureRandom().nextBytes(it) },
        )

        inicializarVistas()
        configurarBotonPrincipal()
        droneState.start(this)
        mainHandler.post(distanceLoop)
        WaypointMissionManager.getInstance().addWaypointMissionExecuteStateListener(missionStateListener)
        activarEvasionDeObstaculos()
        prepararVueltaACasa()
        rcMode = getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE).getInt("rc_mode", 2)
        escucharSticks()
        escucharBotonesBrillo()
        prepararCamara()
    }

    /** C1 oscurece y C2 aclara las fotos, en cualquier momento (también con la misión en vuelo). */
    private fun escucharBotonesBrillo() {
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyCustomButton1Down), this) { _, pressed ->
            if (pressed == true) mainHandler.post { cambiarBrillo(false) }
        }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyCustomButton2Down), this) { _, pressed ->
            if (pressed == true) mainHandler.post { cambiarBrillo(true) }
        }
    }

    /** Lente, zoom, tamaño de foto y corrección de distorsión del perfil del equipo; si la cámara rechaza algo, se avisa. */
    private fun prepararCamara() {
        scope.launch { avisarFallosCamara(CameraSetup.apply(cameraPort, CameraSetup.preparation(captureProfile))) }
    }

    /** De vuelta al gran angular: para volver a medir o para el vuelo manual tras la misión. */
    private fun usarLenteDeMedicion() {
        if (!inspectionLensActive || !::cameraPort.isInitialized) return
        inspectionLensActive = false
        scope.launch { avisarFallosCamara(CameraSetup.apply(cameraPort, CameraSetup.measurementLens(captureProfile))) }
    }

    private fun avisarFallosCamara(failures: List<Pair<CameraSetting, String>>) {
        if (failures.isEmpty()) return
        tvInfo.text = getString(R.string.onestop_camera_setup_failed, failures.joinToString { "${it.first}: ${it.second}" })
        tvInfo.visibility = View.VISIBLE
    }

    private fun exposureKey() = "onestop_exposure_index_$equipmentKey"

    /**
     * Exposición manual al escalón actual (el piloto lo ajusta con +/- antes de despegar la misión, como en el original).
     * La primera vez también pone el modo manual y, si el equipo lo tiene, el obturador mecánico.
     */
    private fun aplicarExposicion() {
        val step = captureProfile.exposureSteps.getOrNull(exposureIndex) ?: return
        val firstTime = !manualExposureActive
        tvInfo.text = getString(R.string.onestop_exposure_step, exposureIndex + 1, captureProfile.exposureSteps.size, step.label)
        tvInfo.visibility = View.VISIBLE
        scope.launch {
            val failures = CameraSetup.apply(cameraPort, CameraSetup.manualExposure(captureProfile, step, includeMode = firstTime))
            if (failures.none { it.first is CameraSetting.ManualExposureMode }) manualExposureActive = true
            avisarFallosCamara(failures)
            mantenerGranAngular()
        }
    }

    /**
     * Al salir del One Stop: devuelve el dron a como estaba (cámara: lente, zoom, tamaño de foto, corrección de
     * distorsión, obturador y exposición; y la evasión de obstáculos) para que el método Three Stop no herede nada.
     */
    private fun restaurarDron() {
        manualExposureActive = false
        // Ámbito propio: debe completarse aunque la actividad se esté cerrando.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            if (::cameraPort.isInitialized) runCatching { cameraPort.restorePrevious() }
            runCatching { avoidancePort.restorePrevious() }
            runCatching { returnHomePort.restorePrevious() }
        }
    }

    /**
     * Ante pérdida de enlace, volver a casa por la ruta de altura fija; hasta tener plan, a la altura actual (se mide
     * delante del rotor). Luego la altura la cambia [rthController] por tramo.
     */
    private fun prepararVueltaACasa() {
        scope.launch {
            val failures = ReturnHomeSetup.prepare(returnHomePort).filterNot { it.optional }
            if (failures.isNotEmpty()) {
                tvInfo.text = getString(R.string.onestop_rth_setup_failed, failures.joinToString { it.step })
                tvInfo.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Programa de altura de RTH para el plan (dos perfiles, ver [ReturnHomeSafety]) con el punto de despegue real y las
     * alturas que acepta el dron. Devuelve el motivo si desde algún tramo la vuelta a casa no sería segura.
     */
    private suspend fun programarVueltaACasa(result: MissionPipeline.Result): String? {
        rthController.schedule = null
        val home = returnHomePort.homeLocation() ?: return getString(R.string.onestop_rth_no_home)
        // Las altitudes del dron son relativas al despegue: casa está a 0 m.
        val homeLocal = GeoMath.toLocal(result.worldOrigin, GeoPoint(home.first, home.second, 0.0))
        val range = returnHomePort.rthHeightRange()
        val needed = ReturnHomeSafety.climbHeightAboveHomeM(result.turbine, homeLocal)
        val h = ReturnHomeSetup.heightsWithin(range, needed)
        val waypoints = result.localWaypoints.map { it.point }
        val schedule = withContext(Dispatchers.Default) {
            ReturnHomeSafety.schedule(result.turbine, waypoints, homeLocal, h.heights)
        }
        val w = schedule.worst
        Log.i(RTH_TAG, "rth_check safe=${schedule.isSafe} wp=${w.waypointIndex + 1}/${waypoints.size} clearance=%.2f component=${w.component} leg=${w.leg} heights=${h.heights} climbNeeded=$needed clamped=${h.climbClamped} range=$range home=$homeLocal homeToTower=%.1f climbWps=${schedule.profiles.count { it == ReturnHomeSafety.Profile.CLIMB }}"
            .format(w.minClearanceM, ReturnHomeSafety.horizontalToTowerM(result.turbine, homeLocal)))
        if (schedule.isSafe) {
            rthController.schedule = schedule
            requiredHomeAheadM = null
            return null
        }
        if (h.climbClamped) return getString(R.string.onestop_rth_limit, needed, h.heights.climbM)
        // Dónde está el problema y desde dónde habría que despegar para que no lo haya.
        val d = withContext(Dispatchers.Default) {
            ReturnHomeSafety.diagnose(result.turbine, waypoints, homeLocal, w) { candidate ->
                ReturnHomeSetup.heightsWithin(range, ReturnHomeSafety.climbHeightAboveHomeM(result.turbine, candidate))
                    .takeUnless { it.climbClamped }?.heights
            }
        }
        requiredHomeAheadM = d.safeHomeAheadM
        Log.w(RTH_TAG, "rth_unsafe homeToTower=%.1f wpToHome=%.1f wpAheadOfTower=%.1f wpZ=%.1f nearHome=${d.nearHome} safeHomeAhead=${d.safeHomeAheadM}"
            .format(d.homeToTowerM, d.waypointToHomeM, d.waypointAheadOfTowerM, d.waypointZ))
        val part = rthPart(w.component)
        val leg = getString(when (w.leg) {
            ReturnHomeSafety.Leg.CLIMB -> R.string.onestop_rth_leg_climb
            ReturnHomeSafety.Leg.CRUISE -> R.string.onestop_rth_leg_cruise
            ReturnHomeSafety.Leg.DESCENT -> R.string.onestop_rth_leg_descent
        })
        val problem = if (d.nearHome) getString(R.string.onestop_rth_home_too_close, d.homeToTowerM, w.waypointIndex + 1, d.waypointToHomeM, part, leg)
            else getString(R.string.onestop_rth_unsafe, w.waypointIndex + 1, w.minClearanceM, part, leg)
        val fix = d.safeHomeAheadM?.let { getString(R.string.onestop_rth_move_home, it) }
            ?: getString(R.string.onestop_rth_no_safe_home, ReturnHomeSafety.SAFE_HOME_CANDIDATES_M.last())
        return "$problem $fix"
    }

    /** Nombre de la pieza de la turbina para el piloto (los nombres salen de [WindTurbine.distance]). */
    private fun rthPart(component: String): String = when {
        component == "Tower" -> getString(R.string.onestop_rth_part_tower)
        component == "Nacelle" -> getString(R.string.onestop_rth_part_nacelle)
        component == "Spinner" -> getString(R.string.onestop_rth_part_spinner)
        component == "Flange" -> getString(R.string.onestop_rth_part_flange)
        component.endsWith("TWELVE") -> getString(R.string.onestop_rth_part_blade, "12h")
        component.endsWith("FOUR") -> getString(R.string.onestop_rth_part_blade, "4h")
        component.endsWith("EIGHT") -> getString(R.string.onestop_rth_part_blade, "8h")
        else -> component
    }

    /**
     * Tras medir el buje: si casa está demasiado cerca de la turbina, la comprobación de la vuelta a casa no pasará al
     * final (a menos de [ReturnHomeSafety.NEAR_HOME_M] el dron vuelve sin subir y desde detrás del rotor lo cruza).
     * Se avisa ya, para que el piloto no mida las 5 posiciones en balde. No bloquea.
     */
    private fun avisarCasaCercana(spinner: GeoPoint, reportOk: Boolean = false) {
        scope.launch {
            val home = runCatching { returnHomePort.homeLocation() }.getOrNull() ?: return@launch
            val v = GeoMath.toLocal(spinner, GeoPoint(home.first, home.second, spinner.alt))
            val distance = kotlin.math.hypot(v.x, v.y)
            Log.i(RTH_TAG, "home_to_spinner=%.1f".format(distance))
            if (inspectionState != InspectionState.MEASURE) return@launch
            if (distance < ReturnHomeSafety.NEAR_HOME_M) {
                tvInfo.text = getString(R.string.onestop_rth_home_early_warning, distance, ReturnHomeSafety.NEAR_HOME_M)
                tvInfo.visibility = View.VISIBLE
            } else if (reportOk) {
                tvInfo.text = getString(R.string.onestop_home_far_enough, distance)
                tvInfo.visibility = View.VISIBLE
            }
        }
    }

    // ==========================================
    // BOTÓN H: FIJAR EL PUNTO DE CASA
    // ==========================================
    /** Con la misión subiendo o en vuelo no se cambia casa: el programa de RTH ya está calculado para la casa actual. */
    private fun casaEditable() =
        inspectionState != InspectionState.UPLOADING && inspectionState != InspectionState.FLYING && inspectionState != InspectionState.EMERGENCY

    /** Confirma antes de cambiar casa, diciendo dónde está el dron respecto a la turbina (si ya se sabe). */
    private fun pedirFijarCasa() {
        if (!casaEditable()) { Toast.makeText(this, R.string.onestop_home_set_busy, Toast.LENGTH_SHORT).show(); return }
        if (!droneState.hasGpsFix) { Toast.makeText(this, R.string.onestop_home_set_no_gps, Toast.LENGTH_SHORT).show(); return }
        val lines = mutableListOf(getString(R.string.onestop_home_set_message))
        val turbine = planTurbine
        val origin = planOrigin
        val spinner = aimedPoints[TurbineMeasurementLocation.SPINNER]
        if (turbine != null && origin != null) {
            val local = GeoMath.toLocal(origin, GeoPoint(droneState.latitude, droneState.longitude, 0.0))
            val toTower = ReturnHomeSafety.horizontalToTowerM(turbine, local)
            val ahead = (local - turbine.towerTop).dot(turbine.turbineDirection) >= 0.0
            lines += getString(if (ahead) R.string.onestop_home_drone_ahead else R.string.onestop_home_drone_behind, toTower)
            requiredHomeAheadM?.let { lines += getString(R.string.onestop_home_drone_needed, it) }
        } else if (spinner != null) {
            val v = GeoMath.toLocal(spinner, GeoPoint(droneState.latitude, droneState.longitude, spinner.alt))
            lines += getString(R.string.onestop_home_drone_to_hub, kotlin.math.hypot(v.x, v.y))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.onestop_home_set_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.onestop_home_set_confirm) { _, _ -> fijarCasa() }
            .setNegativeButton(R.string.onestop_home_set_cancel, null)
            .show()
    }

    /**
     * Casa en la posición actual del dron. Se comprueba que el dron la puso ahí y se repite la comprobación que dependa
     * de casa: la de la vuelta a casa si ya hay plan, o el aviso de casa cercana si se está midiendo.
     */
    private fun fijarCasa() {
        val lat = droneState.latitude
        val lon = droneState.longitude
        scope.launch {
            try {
                returnHomePort.setHomeToAircraftLocation()
            } catch (e: Exception) {
                Log.w(RTH_TAG, "set_home failed: ${e.message}")
                tvInfo.text = getString(R.string.onestop_home_set_failed, e.message ?: e.javaClass.simpleName)
                tvInfo.visibility = View.VISIBLE
                return@launch
            }
            val home = runCatching { returnHomePort.homeLocation() }.getOrNull()
            val offset = home?.let {
                val v = GeoMath.toLocal(GeoPoint(lat, lon, 0.0), GeoPoint(it.first, it.second, 0.0))
                kotlin.math.hypot(v.x, v.y)
            }
            Log.i(RTH_TAG, "set_home ok home=$home drone=($lat,$lon) offset=$offset")
            if (offset == null || offset > 10.0) {
                tvInfo.text = getString(R.string.onestop_home_set_moved, offset ?: Double.NaN)
                tvInfo.visibility = View.VISIBLE
                return@launch
            }
            Toast.makeText(this@OneStopFlightActivity, R.string.onestop_home_set_ok, Toast.LENGTH_SHORT).show()
            val result = planResult
            when {
                inspectionState == InspectionState.BRIGHTNESS && result != null ->
                    comprobarVueltaACasa(result, getString(R.string.onestop_home_set_ok))
                inspectionState == InspectionState.MEASURE ->
                    aimedPoints[TurbineMeasurementLocation.SPINNER]?.let { avisarCasaCercana(it, reportOk = true) }
            }
        }
    }

    // ==========================================
    // DISTANCIA AL OBJETO DE ENFRENTE (siempre visible)
    // ==========================================
    /**
     * 5 Hz desde que se abre la pantalla: láser o visión (el sensor de medición del equipo primero), en rojo si está más
     * cerca que el freno por proximidad y en verde si se está midiendo dentro del alcance. Antes solo se pintaba durante la
     * misión, así que al medir siempre salía "-- m".
     */
    private val distanceLoop = object : Runnable {
        override fun run() {
            val vision = FrontObstacleDetector().detect(VisionSectors.fromMillimeters(droneState.freshSectorsMm()))?.distanceM
            val reading = DistanceReadout.pick(sensorProfile.sensor, droneState.freshLaserM(), vision)
            actualizarMira(reading)
            if (reading == null) {
                tvDistance.setText(R.string.onestop_distance_none)
                tvDistance.setBackgroundColor(Color.WHITE)
                tvDistance.setTextColor(Color.BLACK)
            } else {
                val sensor = getString(if (reading.sensor == MeasurementSensor.LASER) R.string.onestop_sensor_laser else R.string.onestop_sensor_vision)
                tvDistance.text = getString(R.string.onestop_distance_fmt, sensor, reading.distanceM)
                val (bg, fg) = when (DistanceReadout.level(reading.distanceM, inspectionState == InspectionState.MEASURE, sensorProfile)) {
                    DistanceReadout.Level.TOO_CLOSE -> Color.parseColor("#D32F2F") to Color.WHITE
                    DistanceReadout.Level.IN_RANGE -> Color.parseColor("#4CAF50") to Color.WHITE
                    DistanceReadout.Level.NORMAL -> Color.WHITE to Color.BLACK
                }
                tvDistance.setBackgroundColor(bg)
                tvDistance.setTextColor(fg)
            }
            btnHome.visibility = if (casaEditable()) View.VISIBLE else View.GONE
            mainHandler.postDelayed(this, 200)
        }
    }

    /**
     * Mira en el centro del vídeo durante la Foto ID y la medición: blanca sin lectura o fuera de alcance, verde si se está
     * midiendo dentro del alcance (4–6.2 m) y roja si está más cerca que el freno por proximidad. Oculta con el vídeo en miniatura.
     */
    private fun actualizarMira(reading: DistanceReadout.Reading?) {
        val aiming = !videoMini && (inspectionState == InspectionState.ID_PHOTO || inspectionState == InspectionState.MEASURE)
        crosshair.visibility = if (aiming) View.VISIBLE else View.GONE
        if (!aiming) return
        val level = reading?.let { DistanceReadout.level(it.distanceM, inspectionState == InspectionState.MEASURE, sensorProfile) }
        crosshair.setColor(
            when (level) {
                DistanceReadout.Level.TOO_CLOSE -> Color.parseColor("#FF5252")
                DistanceReadout.Level.IN_RANGE -> Color.parseColor("#69F0AE")
                else -> Color.WHITE
            }
        )
    }

    /** Evasión de obstáculos del dron en modo FRENO (3 m), como el original; si el dron rechaza algún ajuste se avisa. */
    private fun activarEvasionDeObstaculos() {
        scope.launch {
            val failures = ObstacleAvoidanceSetup.apply(avoidancePort)
            if (failures.isNotEmpty()) {
                tvInfo.text = getString(R.string.onestop_avoidance_failed, failures.joinToString { it.step })
                tvInfo.visibility = View.VISIBLE
            }
        }
    }

    private fun inicializarVistas() {
        fpvWidget = findViewById(R.id.widget_onestop_fpv)
        fpvWidget.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)
        fpvWidget.isCameraSourceNameVisible = false
        fpvWidget.isCameraSourceSideVisible = false

        tvStatus = findViewById(R.id.tv_onestop_status)
        tvDistance = findViewById(R.id.tv_onestop_distance)
        crosshair = findViewById(R.id.view_onestop_crosshair)
        tvInfo =findViewById(R.id.tv_onestop_info)
        btnAction = findViewById(R.id.btn_onestop_action)
        btnPause = findViewById(R.id.btn_onestop_pause)
        btnEmergency = findViewById(R.id.btn_onestop_emergency)
        btnEmergency.setOnClickListener { ejecutarParoEmergencia() }
        btnHome = findViewById(R.id.btn_onestop_home)
        btnHome.setOnClickListener { pedirFijarCasa() }
        layoutBrightness = findViewById(R.id.layout_brightness)

        stepViews = mapOf(
            TurbineMeasurementLocation.SPINNER to findViewById(R.id.step_hub),
            TurbineMeasurementLocation.TIP_12 to findViewById(R.id.step_tip12),
            TurbineMeasurementLocation.BOTTOM_12 to findViewById(R.id.step_bottom12),
            TurbineMeasurementLocation.TIP_4 to findViewById(R.id.step_tip4),
            TurbineMeasurementLocation.TIP_8 to findViewById(R.id.step_tip8),
        )
        pintarPasos()

        findViewById<Button>(R.id.btn_ev_plus).setOnClickListener { cambiarBrillo(true) }
        findViewById<Button>(R.id.btn_ev_minus).setOnClickListener { cambiarBrillo(false) }
        btnPause.setOnClickListener { alternarPausa() }
        configurarVisorPlan()

        tvStatus.setText(R.string.onestop_status_photo)
    }

    // ==========================================
    // MÁQUINA DE ESTADOS DE LA UI
    // ==========================================
    private fun configurarBotonPrincipal() {
        btnAction.setOnClickListener {
            when (inspectionState) {
                InspectionState.ID_PHOTO -> {
                    tvStatus.setText(R.string.onestop_status_saving_photo)
                    dispararFoto("ID", "ID", CameraMode.PHOTO_NORMAL) {
                        runOnUiThread {
                            inspectionState = InspectionState.MEASURE
                            measureIndex = 0
                            mostrarPasoDeMedicion()
                        }
                    }
                }
                InspectionState.MEASURE -> if (sampler == null) iniciarMedicion() else cancelarMedicion()
                InspectionState.BRIGHTNESS -> iniciarMision()
                InspectionState.FLYING -> abortarMision()
                InspectionState.PLANNING, InspectionState.UPLOADING, InspectionState.EMERGENCY, InspectionState.FINISHED -> Unit
            }
        }
    }

    private fun mostrarPasoDeMedicion() {
        val paso = getString(
            when (MEASURE_ORDER[measureIndex]) {
                TurbineMeasurementLocation.SPINNER -> R.string.onestop_status_measure_hub
                TurbineMeasurementLocation.TIP_12 -> R.string.onestop_status_measure_tip12
                TurbineMeasurementLocation.BOTTOM_12 -> R.string.onestop_status_measure_bottom12
                TurbineMeasurementLocation.TIP_4 -> R.string.onestop_status_measure_tip4
                TurbineMeasurementLocation.TIP_8 -> R.string.onestop_status_measure_tip8
            }
        )
        // Con o sin láser se mide de cerca (4–6 m); sin láser, además, de frente y a la altura del punto.
        val pista = if (sensorProfile.sensor == MeasurementSensor.VISION) R.string.onestop_vision_mode_hint else R.string.onestop_laser_mode_hint
        tvStatus.text = paso + "\n" + getString(pista, SensorProfile.MIN_RECOMMENDED_DISTANCE_M, sensorProfile.preciseMaxM)
        pintarPasos()
    }

    /**
     * Empieza a muestrear el sensor del equipo (≥20 lecturas en 7–15 s con desviación ≤ 0.3 m); el resultado llega a
     * [registrarMedicion]. Con visión, solo arranca si hay algo delante dentro del alcance de precisión.
     */
    private fun iniciarMedicion() {
        if (!droneState.hasGpsFix) { tvStatus.setText(R.string.onestop_status_no_fix); return }
        when (sensorProfile.sensor) {
            MeasurementSensor.LASER -> {
                val laser = droneState.laserDistance.toDouble()
                if (laser <= 0.0) { tvStatus.setText(R.string.onestop_status_no_laser); return }
                if (laser > sensorProfile.maxMeasurementDistanceM) {
                    tvStatus.text = getString(R.string.onestop_status_closer, laser, sensorProfile.preciseMaxM)
                    return
                }
            }
            MeasurementSensor.VISION -> {
                val ahead = FrontObstacleDetector().detect(VisionSectors.fromMillimeters(droneState.horizontalSectorsMm))
                if (ahead == null) { tvStatus.setText(R.string.onestop_status_no_vision); return }
                if (ahead.distanceM > sensorProfile.maxMeasurementDistanceM) {
                    tvStatus.text = getString(R.string.onestop_status_closer, ahead.distanceM, sensorProfile.preciseMaxM)
                    return
                }
            }
        }
        sampler = LaserMeasurementSampler(LaserMeasurementSampler.Config(maxDistance = sensorProfile.maxMeasurementDistanceM))
        distanceSource = MeasurementDistanceSource(sensorProfile)
        samplingStartMs = System.currentTimeMillis()
        lastSensorSample = sensorSampleCount()
        tvInfo.visibility = View.GONE
        btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        mainHandler.post(samplingLoop)
    }

    private fun cancelarMedicion() {
        terminarMuestreo()
        tvInfo.setText(R.string.onestop_measure_cancelled)
        tvInfo.visibility = View.VISIBLE
        mostrarPasoDeMedicion()
    }

    private fun sensorSampleCount() =
        if (sensorProfile.sensor == MeasurementSensor.LASER) droneState.laserSampleCount else droneState.visionSampleCount

    private fun terminarMuestreo() {
        mainHandler.removeCallbacks(samplingLoop)
        sampler = null
        distanceSource = null
        btnAction.setImageResource(android.R.drawable.ic_media_play)
    }

    private val samplingLoop = object : Runnable {
        override fun run() {
            val s = sampler ?: return
            val src = distanceSource ?: return
            val count = sensorSampleCount()
            val newData = count != lastSensorSample
            lastSensorSample = count
            val loc = droneState.location()
            val gps = droneState.hasGpsFix
            val yaw = droneState.yawDeg
            // Láser: la dirección es la del gimbal. Visión: los sensores van fijos al cuerpo (pitch del dron, como el original).
            val pitch = if (sensorProfile.sensor == MeasurementSensor.LASER) droneState.gimbalPitchDeg else droneState.aircraftPitchDeg
            val reading = if (!newData) LaserMeasurementSampler.Reading(false, -1.0, loc, yaw, pitch, gps)
            else when (val r = src.evaluate(droneState.laserDistance.toDouble(), VisionSectors.fromMillimeters(droneState.horizontalSectorsMm), droneState.gimbalPitchDeg)) {
                is MeasurementDistanceSource.Result.Ok -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw + r.bearingOffsetDeg, pitch, gps)
                is MeasurementDistanceSource.Result.Rejected -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw, pitch, gps, rejected = true)
                MeasurementDistanceSource.Result.NoReading -> LaserMeasurementSampler.Reading(false, -1.0, loc, yaw, pitch, gps)
            }
            when (val st = s.tick(reading, System.currentTimeMillis() - samplingStartMs)) {
                is LaserMeasurementSampler.Status.Running -> {
                    tvStatus.text = when {
                        st.paused -> getString(R.string.onestop_status_measuring_paused, st.samples)
                        st.ignored > 0 -> getString(R.string.onestop_status_measuring_ignored, st.samples, st.elapsedMs / 1000.0, st.ignored)
                        else -> getString(R.string.onestop_status_measuring, st.samples, st.elapsedMs / 1000.0)
                    }
                    mainHandler.postDelayed(this, 100)
                }
                is LaserMeasurementSampler.Status.Success -> {
                    terminarMuestreo()
                    registrarMedicion(st.point)
                }
                is LaserMeasurementSampler.Status.Failure -> {
                    terminarMuestreo()
                    val motivo = getString(failureRes(st.reason)) +
                        if (st.ignored > 0) getString(R.string.onestop_measure_ignored, st.ignored) else ""
                    tvInfo.text = getString(R.string.onestop_measure_failed, motivo)
                    tvInfo.visibility = View.VISIBLE
                    mostrarPasoDeMedicion()
                }
            }
        }
    }

    private fun failureRes(r: LaserMeasurementSampler.FailureReason) = when (r) {
        LaserMeasurementSampler.FailureReason.GPS_LOST -> R.string.onestop_measure_fail_gps
        LaserMeasurementSampler.FailureReason.LASER_LOST -> R.string.onestop_measure_fail_laser
        LaserMeasurementSampler.FailureReason.WALL_CLOCK -> R.string.onestop_measure_fail_timeout
        LaserMeasurementSampler.FailureReason.NOT_ENOUGH_SAMPLES -> R.string.onestop_measure_fail_samples
        LaserMeasurementSampler.FailureReason.STD_DEV_TOO_HIGH -> R.string.onestop_measure_fail_std
    }

    /** Guarda la medición promediada (dron + rumbo + pitch del gimbal + distancia láser) tras validar el punto apuntado. */
    private fun registrarMedicion(point: TurbineMeasurementPoint) {
        val target = MEASURE_ORDER[measureIndex]

        val origin = measurements[TurbineMeasurementLocation.SPINNER]?.droneLocation ?: point.droneLocation
        val aimed = try {
            point.toGeo(DroneLocationHelper.worldOrigin(origin))
        } catch (e: Exception) {
            tvStatus.text = e.message; return
        }

        val violations = validarObjetivo(target, aimed)
        if (violations.isNotEmpty()) {
            val text = violations.joinToString(", ") { getString(violationRes(it)) }
            tvInfo.text = getString(R.string.onestop_violation_prefix, text)
            tvInfo.visibility = View.VISIBLE
            return
        } else {
            tvInfo.visibility = View.GONE
        }

        measurements[target] = point
        aimedPoints[target] = aimed
        if (target == TurbineMeasurementLocation.SPINNER) avisarCasaCercana(aimed)
        measureIndex++
        if (measureIndex < MEASURE_ORDER.size) mostrarPasoDeMedicion() else calcularPlan()
    }

    private fun validarObjetivo(target: TurbineMeasurementLocation, aimed: GeoPoint): List<TargetViolation> {
        val spinner = aimedPoints[TurbineMeasurementLocation.SPINNER]
        val spinnerHeading = measurements[TurbineMeasurementLocation.SPINNER]?.headingDeg
        return when (target) {
            // El dron debe estar a la altura del buje (±20 m); la altitud es relativa al despegue.
            TurbineMeasurementLocation.SPINNER -> TargetGeometry.checkSpinner(droneState.altitude, hubHeightM)
            TurbineMeasurementLocation.TIP_12, TurbineMeasurementLocation.TIP_4, TurbineMeasurementLocation.TIP_8 ->
                if (spinner == null || spinnerHeading == null) emptyList()
                else TargetGeometry.checkTip(target, aimed, spinner, spinnerHeading, bladeLengthM)
            TurbineMeasurementLocation.BOTTOM_12 -> {
                val tip12 = aimedPoints[TurbineMeasurementLocation.TIP_12]
                if (spinner == null || tip12 == null) emptyList() else TargetGeometry.checkBottom12(aimed, spinner, tip12)
            }
        }
    }

    private fun violationRes(v: TargetViolation) = when (v) {
        TargetViolation.TOO_CLOSE_TO_ROOT -> R.string.onestop_violation_TOO_CLOSE_TO_ROOT
        TargetViolation.TOO_CLOSE_TO_TIP -> R.string.onestop_violation_TOO_CLOSE_TO_TIP
        TargetViolation.NOT_ON_TOP_BLADE_AXIS -> R.string.onestop_violation_NOT_ON_TOP_BLADE_AXIS
        TargetViolation.AIM_TOO_LOW -> R.string.onestop_violation_AIM_TOO_LOW
        TargetViolation.AIM_TOO_HIGH -> R.string.onestop_violation_AIM_TOO_HIGH
        TargetViolation.WRONG_SIDE -> R.string.onestop_violation_WRONG_SIDE
        TargetViolation.DRONE_TOO_LOW -> R.string.onestop_violation_DRONE_TOO_LOW
        TargetViolation.DRONE_TOO_HIGH -> R.string.onestop_violation_DRONE_TOO_HIGH
    }

    // ==========================================
    // PLANIFICACIÓN (núcleo flightcore)
    // ==========================================
    private fun calcularPlan() {
        inspectionState = InspectionState.PLANNING
        tvStatus.setText(R.string.onestop_status_planning)
        pintarPasos()

        val spinnerMeasure = measurements.getValue(TurbineMeasurementLocation.SPINNER)!!
        // Longitud de pala (app) + altura de buje (piloto) + dimensiones del job; lo que falte queda genérico.
        val dims = TurbineDimensionsBuilder.build(bladeLengthM, hubHeightM, turbineJob)
        val definition = InspectionDefinition()

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    MissionPipeline.plan(
                        firstDroneLocation = spinnerMeasure.droneLocation,
                        measurements = measurements,
                        dimensions = dims,
                        definition = definition,
                        bladeTwistInput = BladeTwist.default(),
                        cam = cameraFov,
                        gimbal = GimbalLimits(),
                        rtkSetting = null,
                        stopAtEach = captureProfile.stopAtEachWaypoint,
                        blade4 = ComponentIndex.valueOf(blade4Label),
                        blade12 = ComponentIndex.valueOf(blade12Label),
                        blade8 = ComponentIndex.valueOf(blade8Label),
                    )
                }
                val missionId = "onestop_${System.currentTimeMillis()}"
                val missionPlan = gateway.seal(
                    MissionMapper.toMissionPlan(missionId, definition.inspectionVelocity, result.waypoints)
                )
                dimensions = dims
                plan = missionPlan
                firstWaypoint = missionPlan.waypoints.first().position

                inspectionState = InspectionState.BRIGHTNESS
                tvStatus.setText(R.string.onestop_status_brightness)
                tvInfo.text = getString(R.string.onestop_plan_summary, missionPlan.waypoints.size, result.rotorIndexAngleDeg)
                tvInfo.visibility = View.VISIBLE
                layoutBrightness.visibility = View.VISIBLE
                btnAction.setImageResource(android.R.drawable.ic_media_play)
                // El vídeo sigue en el gran angular hasta que el plan esté cargado en el dron (ver iniciarMision).
                if (captureProfile.manualExposure) aplicarExposicion()
                mostrarPlan(result)
                planResult = result
                comprobarVueltaACasa(result, tvInfo.text.toString())
            } catch (e: MissionPlanningException) {
                reiniciarCalibracion(getString(R.string.onestop_error_plan, e.userMessage))
            } catch (e: Exception) {
                reiniciarCalibracion(getString(R.string.onestop_error_plan, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Comprueba la vuelta a casa del plan con el punto de casa actual y deja el resultado en pantalla tras [baseText]. */
    private suspend fun comprobarVueltaACasa(result: MissionPipeline.Result, baseText: String) {
        rthProblem = runCatching { programarVueltaACasa(result) }
            .getOrElse { getString(R.string.onestop_rth_unsafe_error, it.message ?: it.javaClass.simpleName) }
        tvInfo.text = rthProblem ?: (baseText + "\n" + rthController.schedule?.let {
            getString(R.string.onestop_rth_plan, it.heights.levelM, it.heights.climbM)
        })
        tvInfo.visibility = View.VISIBLE
    }

    private fun reiniciarCalibracion(message: String) {
        terminarMuestreo()
        usarLenteDeMedicion()
        ocultarPlan()
        measurements.clear()
        aimedPoints.clear()
        plan = null
        measureIndex = 0
        inspectionState = InspectionState.MEASURE
        layoutBrightness.visibility = View.GONE
        btnAction.setImageResource(android.R.drawable.ic_media_play)
        tvInfo.text = message
        tvInfo.visibility = View.VISIBLE
        mostrarPasoDeMedicion()
    }

    // ==========================================
    // MISIÓN (subida y control vía MissionPort)
    // ==========================================
    private fun iniciarMision() {
        val missionPlan = plan ?: return
        val dims = dimensions ?: return
        val firstWp = firstWaypoint ?: return

        // Comprobación de trayecto desde la posición actual hasta el primer waypoint
        val transfer = try {
            AppTransferCheck.performTransferCheck(
                drone = droneState.location(), target = firstWp, dims = dims, def = InspectionDefinition(),
                measurements = measurements,
            )
        } catch (e: Exception) {
            tvInfo.text = getString(R.string.onestop_error_plan, e.message ?: e.javaClass.simpleName)
            tvInfo.visibility = View.VISIBLE
            return
        }
        if (transfer is TransferSafetyResult.Unsafe) {
            tvInfo.text = getString(R.string.onestop_error_transfer, transfer.obstacle.name)
            tvInfo.visibility = View.VISIBLE
            return
        }
        // Sin un programa de vuelta a casa seguro no se vuela la misión.
        val rthSchedule = rthController.schedule
        if (rthProblem != null || rthSchedule == null) {
            tvInfo.text = rthProblem ?: getString(R.string.onestop_rth_no_home)
            tvInfo.visibility = View.VISIBLE
            return
        }

        layoutBrightness.visibility = View.GONE
        inspectionState = InspectionState.UPLOADING
        tvStatus.text = getString(R.string.onestop_status_uploading, 0)
        emergencyStop.reset()
        pullBack.reset()
        btnEmergency.visibility = View.VISIBLE

        scope.launch {
            try {
                iniciarRegistroFotos(missionPlan.id)
                // La altura de RTH del primer tramo tiene que estar puesta antes de despegar la misión.
                rthIndex = 0
                rthController.update(rthIndex)
                check(rthController.applied == rthSchedule.heightAt(rthIndex)) { getString(R.string.onestop_rth_rejected) }
                gateway.upload(missionPlan) { p ->
                    runOnUiThread { tvStatus.text = getString(R.string.onestop_status_uploading, (p * 100).toInt()) }
                }
                // Paro pulsado mientras se subía la misión: no se despega.
                if (inspectionState != InspectionState.UPLOADING) return@launch
                // Calibración terminada y plan cargado: recién ahora el vídeo pasa a la cámara de inspección.
                avisarFallosCamara(CameraSetup.apply(cameraPort, CameraSetup.inspectionLens(captureProfile)))
                inspectionLensActive = true
                gateway.start(missionPlan.id)
                proximityGuard.reset()
                safetyPausePending = false
                inspectionState = InspectionState.FLYING
                missionPaused = false
                tvStatus.setText(R.string.onestop_status_flying)
                btnAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                btnPause.visibility = View.VISIBLE
                mainHandler.post(safetyLoop)
            } catch (e: Exception) {
                if (inspectionState != InspectionState.UPLOADING) return@launch
                btnEmergency.visibility = View.GONE
                inspectionState = InspectionState.BRIGHTNESS
                layoutBrightness.visibility = View.VISIBLE
                tvInfo.text = getString(R.string.onestop_error_upload, e.message ?: e.javaClass.simpleName)
                tvInfo.visibility = View.VISIBLE
                tvStatus.setText(R.string.onestop_status_brightness)
            }
        }
    }

    private fun alternarPausa() {
        if (inspectionState != InspectionState.FLYING) return
        scope.launch {
            try {
                if (missionPaused) gateway.resume() else gateway.pause()
                missionPaused = !missionPaused
                tvStatus.setText(if (missionPaused) R.string.onestop_status_paused else R.string.onestop_status_flying)
            } catch (e: Exception) {
                tvInfo.text = getString(R.string.onestop_error_upload, e.message ?: e.javaClass.simpleName)
                tvInfo.visibility = View.VISIBLE
            }
        }
    }

    // ==========================================
    // ARCHIVO DE RESULTADO POR FOTO
    // ==========================================
    /**
     * Antes de subir la misión: carpeta propia en la tarjeta (sufijo con la fecha y hora UTC, como la app auditada),
     * archivo de resultado con todas las fotos planificadas y escucha de las fotos que guarda el dron.
     * Se guarda en `Android/data/<app>/files/inspecciones/<turbina>_<carpeta>.json` tras cada foto.
     */
    private suspend fun iniciarRegistroFotos(missionId: String) {
        val result = planResult ?: return
        cerrarRegistroFotos(finished = false)
        val folder = captureFeed.newFolderName()
        runCatching { captureFeed.prepareFolder(folder) }.onFailure {
            tvInfo.text = getString(R.string.onestop_result_folder_failed, it.message ?: it.javaClass.simpleName)
            tvInfo.visibility = View.VISIBLE
        }
        val prefs = getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE)
        val header = linkedMapOf<String, Any?>(
            "app" to "Nexus App", "appVersion" to BuildConfig.VERSION_NAME, "inspectionDate" to isoUtc(Date()),
            "project" to projectId, "turbine" to turbineName, "pilot" to prefs.getString("pilot_name", null),
            "drone" to equipmentKey, "lens" to captureProfile.lens.name,
            "hubHeight" to hubHeightM, "bladeLength" to bladeLengthM, "rotorIndexAngle" to result.rotorIndexAngleDeg,
            "blade12h" to blade12Label, "blade4h" to blade4Label, "blade8h" to blade8Label,
            "photoOrder" to "Palas 4h, 12h, 8h; caras LE (raíz→punta), SS (punta→raíz), TE (raíz→punta), PS (punta→raíz)",
        )
        photoRecord = PhotoRecord(missionId, folder, PlannedPhoto.fromPlan(result.waypoints), header)
        val safeName = (turbineName ?: "turbina").replace(Regex("[^A-Za-z0-9_-]+"), "_")
        resultFile = File(getExternalFilesDir("inspecciones"), "${safeName}_$folder.json")
        guardarResultado(finished = false)
        captureFeed.start { saved -> runOnUiThread { registrarFoto(saved) } }
    }

    /** Una foto guardada por el dron: se asigna a su foto planificada con el waypoint que se ejecutaba. */
    private fun registrarFoto(saved: DjiCaptureFeed.SavedPhoto) {
        val rec = photoRecord ?: return
        val pos = if (droneState.hasGpsFix) GeoPoint(droneState.latitude, droneState.longitude, droneState.altitude) else null
        val useLaser = droneState.laserDistance > 0f
        val sensorM = when { useLaser -> droneState.laserDistance.toDouble(); droneState.visionDistance > 0f -> droneState.visionDistance.toDouble(); else -> null }
        val clearance = pos?.let { p -> planOrigin?.let { o -> planTurbine?.distance(GeoMath.toLocal(o, p))?.first } }
        val capture = CaptureInfo(
            timeIso = isoUtc(Date()), fileIndex = saved.fileIndex, dirNo = saved.dirNo, fileNo = saved.fileNo,
            expectedFileName = saved.expectedFileName, waypointAtCapture = captureFeed.currentWaypoint, position = pos,
            yawDeg = droneState.yawDeg, gimbalPitchDeg = droneState.gimbalPitchDeg, sensorDistanceM = sensorM,
            sensor = if (sensorM == null) null else if (useLaser) "laser" else "vision", modelClearanceM = clearance,
            iso = saved.iso, shutterS = saved.shutterS, aperture = saved.aperture,
        )
        val match = rec.assign(capture)
        guardarResultado(finished = false)
    }

    /** Cierra el registro (fin, aborto o paro): guarda el archivo y quita los sufijos de carpeta y archivo de la cámara. */
    private fun cerrarRegistroFotos(finished: Boolean) {
        if (photoRecord == null) return
        captureFeed.stop()
        guardarResultado(finished)
        photoRecord = null
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { captureFeed.resetNames() }
    }

    private fun guardarResultado(finished: Boolean) {
        val rec = photoRecord ?: return
        val file = resultFile ?: return
        runCatching { file.parentFile?.mkdirs(); file.writeText(rec.toJson(finished).toString(2)) }
    }

    private fun isoUtc(d: Date) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)

    // ==========================================
    // PARO DE EMERGENCIA
    // ==========================================
    /** Escucha los cuatro ejes del mando: tirón de paro durante la misión y vuelta al centro tras el paro. */
    private fun escucharSticks() {
        val km = KeyManager.getInstance()
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftHorizontal), this) { _, v -> v?.let { mainHandler.post { alMoverSticks(sticks.copy(leftH = it)) } } }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftVertical), this) { _, v -> v?.let { mainHandler.post { alMoverSticks(sticks.copy(leftV = it)) } } }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightHorizontal), this) { _, v -> v?.let { mainHandler.post { alMoverSticks(sticks.copy(rightH = it)) } } }
        km.listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightVertical), this) { _, v -> v?.let { mainHandler.post { alMoverSticks(sticks.copy(rightV = it)) } } }
    }

    private fun alMoverSticks(s: Sticks) {
        sticks = s
        val armed = inspectionState == InspectionState.UPLOADING || inspectionState == InspectionState.FLYING
        if (pullBack.update(s.pitch(rcMode), SystemClock.elapsedRealtime()) && armed) ejecutarParoEmergencia()
    }

    /**
     * Detiene por completo la inspección (misión y vuelta a casa en curso) y entrega el control a los sticks. Si algún
     * stick no está en el centro, el dron se queda quieto hasta que vuelven todos (ver [EmergencyStopController]).
     */
    private fun ejecutarParoEmergencia() {
        if (emergencyStop.state != EmergencyStopController.State.IDLE) return
        if (inspectionState != InspectionState.UPLOADING && inspectionState != InspectionState.FLYING) return
        inspectionState = InspectionState.EMERGENCY
        mainHandler.removeCallbacks(safetyLoop)
        btnPause.visibility = View.GONE
        // Paro en marcha (por el botón o por los sticks): el botón ya no hace falta.
        btnEmergency.visibility = View.GONE
        tvStatus.setText(R.string.onestop_emergency_stopping)
        scope.launch {
            emergencyStop.trigger(sticks, SystemClock.elapsedRealtime())
            if (emergencyStop.state == EmergencyStopController.State.HOLDING) {
                tvStatus.setText(R.string.onestop_emergency_holding)
                mainHandler.post(holdLoop)
            } else {
                entregarControlManual()
            }
        }
    }

    /** Mientras se retiene el dron: comprueba (10 Hz) si los sticks ya volvieron al centro para soltarlo. */
    private val holdLoop = object : Runnable {
        override fun run() {
            if (emergencyStop.state != EmergencyStopController.State.HOLDING) return
            val pending = sticks.offCenter(emergencyStop.deadband)
            if (pending.isNotEmpty()) tvStatus.text = getString(R.string.onestop_emergency_holding_detail,
                pending.joinToString { "${it.first} ${it.second}" })
            else tvStatus.setText(R.string.onestop_emergency_holding)
            scope.launch {
                emergencyStop.onSticks(sticks, SystemClock.elapsedRealtime())
                if (emergencyStop.state == EmergencyStopController.State.MANUAL) entregarControlManual()
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    private fun entregarControlManual() {
        mainHandler.removeCallbacks(holdLoop)
        cerrarRegistroFotos(finished = false)
        val failures = emergencyStop.failures.toList()
        btnEmergency.isEnabled = true
        btnEmergency.visibility = View.GONE
        btnAction.isEnabled = true
        btnAction.setImageResource(android.R.drawable.ic_media_play)
        btnAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        reiniciarEstadoCompleto()
        tvStatus.setText(R.string.onestop_emergency_manual)
        if (failures.isNotEmpty()) {
            tvInfo.text = getString(R.string.onestop_emergency_failed, failures.joinToString())
            tvInfo.visibility = View.VISIBLE
        }
        Toast.makeText(this, R.string.onestop_emergency_manual, Toast.LENGTH_LONG).show()
    }

    private fun abortarMision() {
        cerrarRegistroFotos(finished = false)
        btnEmergency.visibility = View.GONE
        mainHandler.removeCallbacks(safetyLoop)
        val id = plan?.id
        if (id != null) {
            scope.launch { runCatching { gateway.stop(id) } }
        }
        btnPause.visibility = View.GONE
        btnAction.isEnabled = true
        btnAction.setImageResource(android.R.drawable.ic_media_play)
        btnAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        reiniciarEstadoCompleto()
        tvStatus.setText(R.string.onestop_status_aborted)
        Toast.makeText(this, R.string.onestop_status_aborted, Toast.LENGTH_SHORT).show()
    }

    private fun reiniciarEstadoCompleto() {
        terminarMuestreo()
        usarLenteDeMedicion()
        ocultarPlan()
        measurements.clear()
        aimedPoints.clear()
        plan = null
        measureIndex = 0
        inspectionState = InspectionState.ID_PHOTO
        tvInfo.visibility = View.GONE
        pintarPasos()
    }

    /** El dron terminó el wayline: se avisa a MainActivity de que la turbina quedó inspeccionada. */
    /**
     * El dron terminó el wayline: la turbina queda marcada como inspeccionada, pero la pantalla sigue en el vídeo con el
     * piloto al mando hasta que aterriza; entonces vuelve a la pantalla principal (también puede salir con atrás).
     */
    private fun finalizarMision() {
        cerrarRegistroFotos(finished = true)
        mainHandler.removeCallbacks(safetyLoop)
        inspectionState = InspectionState.FINISHED
        setResult(RESULT_OK, Intent().apply {
            putExtra("ONE_STOP_COMPLETED", true)
            putExtra("COMPLETED_BLADE", "ALL")
        })
        btnPause.visibility = View.GONE
        btnEmergency.visibility = View.GONE
        btnAction.visibility = View.GONE
        tvStatus.setText(R.string.onestop_status_finished)
        // El piloto trae el dron a mano: gran angular para orientarse.
        usarLenteDeMedicion()
        var wasFlying = true
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyIsFlying), this) { _, flying ->
            if (flying == false && wasFlying) {
                wasFlying = false
                runOnUiThread { finish() }
            }
        }
    }

    // ==========================================
    // VIGILANCIA DE DISTANCIA (Kalman + láser) durante el vuelo
    // ==========================================
    private val safetyLoop = object : Runnable {
        override fun run() {
            if (inspectionState != InspectionState.FLYING) return

            // La distancia en pantalla la pinta distanceLoop. Que no haya lectura no pausa la misión: el plan vuela a
            // 8–12 m de la pala y la visión no siempre la tiene delante; lo peligroso es acercarse, no dejar de verla.
            // Freno por proximidad: cualquier sensor que vea la turbina a < 4 m durante 3 ticks pausa la misión.
            val tooClose = proximityGuard.update(
                droneState.laserDistance.toDouble(), droneState.visionDistance.toDouble(), droneState.towerDistance.toDouble(),
            )
            if (tooClose) pausarPorSeguridad(getString(R.string.onestop_status_proximity, proximityGuard.closestM ?: 0.0))
            mainHandler.postDelayed(this, LOOP_PERIOD_MS)
        }
    }

    /**
     * Pausa la misión una sola vez aunque el bucle de 20 Hz lo pida en cada tick (la pausa del SDK es asíncrona).
     * No reanuda nunca sola: lo decide el piloto.
     */
    private fun pausarPorSeguridad(message: String) {
        if (missionPaused || safetyPausePending) return
        safetyPausePending = true
        tvStatus.text = message
        scope.launch {
            try {
                gateway.pause()
                missionPaused = true
            } catch (e: Exception) {
                tvInfo.text = getString(R.string.onestop_error_upload, e.message ?: e.javaClass.simpleName)
                tvInfo.visibility = View.VISIBLE
            } finally {
                safetyPausePending = false
            }
        }
    }

    // ==========================================
    // VISOR 3D DEL PLAN
    // ==========================================
    private fun configurarVisorPlan() {
        planFrame = findViewById(R.id.plan_view_frame)
        planView = findViewById(R.id.plan_view)
        planControls = findViewById(R.id.plan_view_controls)
        planHint = findViewById(R.id.plan_view_hint)
        planView.onTap = { abrirVisorPlan() }
        planView.onVideoTap = { cerrarVisorPlan() }
        planView.videoHoleEnabled = true
        findViewById<Button>(R.id.btn_plan_back).setOnClickListener { cerrarVisorPlan() }
        findViewById<Button>(R.id.btn_plan_front).setOnClickListener { planView.scene?.preset(PlanScene.Preset.FRONT); planView.invalidate() }
        findViewById<Button>(R.id.btn_plan_side).setOnClickListener { planView.scene?.preset(PlanScene.Preset.SIDE); planView.invalidate() }
        findViewById<Button>(R.id.btn_plan_top).setOnClickListener { planView.scene?.preset(PlanScene.Preset.TOP); planView.invalidate() }
        findViewById<Button>(R.id.btn_plan_iso).setOnClickListener { planView.resetView() }
        onBackPressedDispatcher.addCallback(this, planBackCallback)
    }

    /** Plan recién calculado: se carga en el visor y se abre ampliado para revisarlo antes de iniciar. */
    private fun mostrarPlan(result: MissionPipeline.Result) {
        val stops = result.waypoints.map { it.stop || it.isFirstInspectionPathPoint || it.isLastInspectionPathPoint }
        planView.cameraFov = cameraFov
        planView.scene = PlanScene(result.turbine, result.localWaypoints, stops)
        planOrigin = result.worldOrigin
        planTurbine = result.turbine
        planFrame.visibility = View.VISIBLE
        abrirVisorPlan()
        tvInfo.text = getString(R.string.onestop_plan_review, result.localWaypoints.size)
        tvInfo.visibility = View.VISIBLE
        mainHandler.removeCallbacks(planLoop)
        mainHandler.post(planLoop)
    }

    private fun ocultarPlan() {
        mainHandler.removeCallbacks(planLoop)
        if (!::planFrame.isInitialized) return
        cerrarVisorPlan()
        planFrame.visibility = View.GONE
        planView.scene = null
        planView.clearDrone()
        planOrigin = null; planTurbine = null
    }

    /** Amplía el visor a toda la pantalla, dejando visibles la barra superior y los controles de la derecha. */
    private fun abrirVisorPlan() {
        if (planExpanded) return
        val p = planFrame.layoutParams as ConstraintLayout.LayoutParams
        planThumbParams = ConstraintLayout.LayoutParams(p)
        val full = ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = R.id.panel_top_bar
            endToStart = R.id.layout_master_controls
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            marginEnd = (12 * resources.displayMetrics.density).toInt()
        }
        planFrame.layoutParams = full
        // Los controles de la derecha dejan de apoyarse en la miniatura y se centran en la pantalla.
        (findViewById<View>(R.id.layout_master_controls).layoutParams as ConstraintLayout.LayoutParams).apply {
            bottomToTop = ConstraintLayout.LayoutParams.UNSET; bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }.also { findViewById<View>(R.id.layout_master_controls).layoutParams = it }
        planView.interactive = true
        mostrarVideoEnMiniatura(true)
        planControls.visibility = View.VISIBLE
        planHint.visibility = View.GONE
        planExpanded = true
        planBackCallback.isEnabled = true
    }

    /** Vuelve a la vista del dron: el visor queda como miniatura abajo a la derecha. */
    private fun cerrarVisorPlan() {
        if (!planExpanded) return
        planThumbParams?.let { planFrame.layoutParams = it }
        (findViewById<View>(R.id.layout_master_controls).layoutParams as ConstraintLayout.LayoutParams).apply {
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET; bottomToTop = R.id.plan_view_frame
        }.also { findViewById<View>(R.id.layout_master_controls).layoutParams = it }
        planView.interactive = false
        mostrarVideoEnMiniatura(false)
        planControls.visibility = View.GONE
        planHint.visibility = View.VISIBLE
        planExpanded = false
        planBackCallback.isEnabled = false
        // De vuelta a la cámara antes de iniciar: se recuerda el escalón de exposición que se está usando.
        if (inspectionState == InspectionState.BRIGHTNESS) captureProfile.exposureSteps.getOrNull(exposureIndex)?.let {
            tvInfo.text = getString(R.string.onestop_exposure_step, exposureIndex + 1, captureProfile.exposureSteps.size, it.label)
            tvInfo.visibility = View.VISIBLE
        }
    }

    /**
     * Intercambio estilo DJI: con el 3D ampliado el vídeo pasa a una miniatura abajo a la derecha (solo imagen, sin
     * controles) justo bajo el hueco transparente que deja el visor; al volver recupera la pantalla completa.
     * El horizonte artificial y la brújula se ocultan mientras tanto para que no se dibujen sobre el vídeo pequeño.
     */
    private fun mostrarVideoEnMiniatura(mini: Boolean) {
        videoMini = mini
        val pfd = findViewById<View>(R.id.widget_pfd)
        val hsi = findViewById<View>(R.id.widget_hsi)
        if (mini) {
            fpvFullParams = ConstraintLayout.LayoutParams(fpvWidget.layoutParams as ConstraintLayout.LayoutParams)
            val d = resources.displayMetrics.density
            fpvWidget.layoutParams = ConstraintLayout.LayoutParams((PlanView3D.VIDEO_W_DP * d).toInt(), (PlanView3D.VIDEO_H_DP * d).toInt()).apply {
                endToEnd = R.id.plan_view_frame
                bottomToBottom = R.id.plan_view_frame
                marginEnd = (PlanView3D.VIDEO_MARGIN_DP * d).toInt()
                bottomMargin = (PlanView3D.VIDEO_MARGIN_DP * d).toInt()
            }
            pfd.visibility = View.INVISIBLE
            hsi.visibility = View.INVISIBLE
        } else {
            fpvFullParams?.let { fpvWidget.layoutParams = it }
            pfd.visibility = View.VISIBLE
            hsi.visibility = View.VISIBLE
        }
    }

    /** Posición del dron en el visor (5 Hz) mientras haya plan: sistema local del plan, rumbo, gimbal y holgura; y la altura de RTH. */
    private val planLoop = object : Runnable {
        override fun run() {
            val origin = planOrigin ?: return
            val turbine = planTurbine ?: return
            if (droneState.hasGpsFix) {
                val local = GeoMath.toLocal(origin, GeoPoint(droneState.latitude, droneState.longitude, droneState.altitude))
                planView.updateDrone(local, droneState.yawDeg, droneState.gimbalPitchDeg, turbine.distance(local).first)
                // En misión, la altura de RTH sigue al waypoint más cercano (solo se escribe cuando cambia de perfil).
                if (inspectionState == InspectionState.FLYING) {
                    planView.scene?.let { rthIndex = it.progressIndex(local, rthIndex) }
                    scope.launch { rthController.update(rthIndex) }
                }
            }
            mainHandler.postDelayed(this, 200)
        }
    }

    // ==========================================
    // UI Y CÁMARA
    // ==========================================
    private fun pintarPasos() {
        val current = if (inspectionState == InspectionState.MEASURE) MEASURE_ORDER.getOrNull(measureIndex) else null
        stepViews.forEach { (loc, view) ->
            val color = when {
                measurements.containsKey(loc) -> "#4CAF50"
                loc == current -> "#FFC107"
                else -> "#444444"
            }
            val drawable = ContextCompat.getDrawable(this, R.drawable.circle_bg)?.mutate()
            drawable?.setTint(Color.parseColor(color))
            view.background = drawable
        }
    }

    private fun cambiarBrillo(mas: Boolean) {
        if (captureProfile.manualExposure) {
            // Escalones del original: + más claro (tiempo más largo, diafragma más abierto, más ISO), − más oscuro.
            exposureIndex = (exposureIndex + if (mas) 1 else -1).coerceIn(0, captureProfile.exposureSteps.size - 1)
            getSharedPreferences(PREFS_SAFETY, MODE_PRIVATE).edit().putInt(exposureKey(), exposureIndex).apply()
            aplicarExposicion()
            return
        }
        val evKey = KeyTools.createKey(CameraKey.KeyExposureCompensation, ComponentIndexType.LEFT_OR_MAIN)
        val actual = KeyManager.getInstance().getValue(evKey) ?: return
        val lista = CameraExposureCompensation.values().filter { it.name != "UNKNOWN" }
        val idx = lista.indexOf(actual)
        val nuevoIdx = if (mas) (idx + 1).coerceAtMost(lista.size - 1) else (idx - 1).coerceAtLeast(0)
        KeyManager.getInstance().setValue(evKey, lista[nuevoIdx], null)
    }

    /** Hasta que el plan esté cargado, el vídeo y las fotos de calibración van en el gran angular (ajustes de la lente zoom pueden moverlo). */
    private suspend fun mantenerGranAngular() {
        if (inspectionLensActive || !captureProfile.useZoomStream) return
        avisarFallosCamara(CameraSetup.apply(cameraPort, CameraSetup.measurementLens(captureProfile)))
    }

    private fun dispararFoto(dirSuffix: String?, fileSuffix: String, mode: CameraMode, onComplete: () -> Unit) {
        scope.launch { mantenerGranAngular() }
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN), mode, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                scope.launch { mantenerGranAngular() }
                photoHandler.postDelayed({
                    val configureFileAndShoot = {
                        val nameSettings = CustomExpandNameSettings().apply { customContent = fileSuffix; forceCreateFolder = false; priority = 0; encodingType = EnCodingType.UTF8; relativePosition = RelativePosition.POSITION_END }
                        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCustomExpandFileNameSettings, ComponentIndexType.LEFT_OR_MAIN), nameSettings, null)

                        photoHandler.postDelayed({
                            KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)
                            onComplete()
                        }, 1000)
                    }

                    if (dirSuffix != null) {
                        val dirSettings = CustomExpandNameSettings().apply { customContent = dirSuffix; forceCreateFolder = true; priority = 0; encodingType = EnCodingType.UTF8; relativePosition = RelativePosition.POSITION_END }
                        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCustomExpandDirectoryNameSettings, ComponentIndexType.LEFT_OR_MAIN), dirSettings, object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { photoHandler.postDelayed({ configureFileAndShoot() }, 1000) }
                            override fun onFailure(e: IDJIError) { configureFileAndShoot() }
                        })
                    } else {
                        configureFileAndShoot()
                    }
                }, 500)
            }
            override fun onFailure(error: IDJIError) { onComplete() }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        photoHandler.removeCallbacksAndMessages(null)
        WaypointMissionManager.getInstance().removeWaypointMissionExecuteStateListener(missionStateListener)
        KeyManager.getInstance().cancelListen(this)
        // Si se cierra con el dron retenido tras un paro, se le devuelve el control al mando.
        if (emergencyStop.state == EmergencyStopController.State.HOLDING) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { runCatching { emergencyPort.releaseHold() } }
        }
        cerrarRegistroFotos(finished = false)
        if (::droneState.isInitialized) droneState.stop(this)
        // Si se sale con la misión en curso se detiene (comportamiento previo: devolver el control manual).
        if (inspectionState == InspectionState.FLYING) {
            plan?.id?.let { id -> runCatching { WaypointMissionManager.getInstance().stopMission(id, null) } }
        }
        // Se restaura al cerrar la pantalla (también tras terminar la misión, que la cierra); tras abortar se sigue en el
        // One Stop con sus ajustes para volver a medir.
        restaurarDron()
        scope.cancel()
    }
}
