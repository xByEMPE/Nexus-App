package mx.com.nexusio.fly.drone

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import mx.com.nexusio.fly.R

// Imports FPV y Video
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.sdk.keyvalue.value.common.ComponentIndexType

// Imports KeyManager, Cámara y Control
import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.RemoteControllerKey

// Valores Oficiales de Cámara para MSDK V5
import dji.sdk.keyvalue.value.camera.PhotoIntervalShootSettings
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.CameraExposureCompensation
import dji.sdk.keyvalue.value.camera.CustomExpandNameSettings
import dji.sdk.keyvalue.value.common.EnCodingType
import dji.sdk.keyvalue.value.common.RelativePosition
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.flightcontroller.FailsafeAction

// Virtual Sticks
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem

// Sensores de Visión
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError

class FlightActivity : AppCompatActivity() {

    // ==========================================
    // CONSTANTES ÓPTICAS Y VELOCIDAD REDUCIDA
    // ==========================================
    // Distancia ajustada a 3.8m
    private val TARGET_DISTANCE = 3.8f

    // Velocidad más lenta y suave para inspección
    private val CRUISE_SPEED = 1.0
    private val PHOTO_INTERVAL = 2.0

    // Frenado inteligente extendido y suave
    private val TIP_SAFE_SPEED = 0.2
    private val BRAKING_ZONE_METERS = 7.0
    // ==========================================

    private var bladeLength: Float = 0f
    private var isThreeStop: Boolean = false
    private var selectedBladeId: String = ""
    private var bladeLetter: String = ""
    private var hasIdPhoto: Boolean = false

    private enum class InspectionState { ID_PHOTO, POSITIONING, BRIGHTNESS, AUTO_FLIGHT, WAITING_LANDING }
    private enum class BladeSection { TE, PS, LE, SS }

    private var currentState = InspectionState.ID_PHOTO
    private var currentSection = BladeSection.TE
    private var isGimbalDown = false
    private var isDistanceValid = false

    @Volatile private var currentLaserDistance: Float = -1f
    @Volatile private var currentVisionDistance: Float = -1f
    @Volatile private var lastLaserState: LaserMeasureState? = null
    @Volatile private var areMotorsOn: Boolean = false
    private var dialogMotores: AlertDialog? = null


    // VARIABLES DE VUELO AUTÓNOMO
    private var isAutoFlying: Boolean = false
    @Volatile private var isVerticalMovementAllowed: Boolean = false
    @Volatile private var isHoverLocked: Boolean = false

    // TELEMETRÍA
    @Volatile private var currentAltitude: Double = 0.0
    private var startAltitude: Double = 0.0

    @Volatile private var currentDjiFlightMode: String = "DESCONOCIDO"
    private var flightModeDialog: AlertDialog? = null
    private val virtualStickHandler = Handler(Looper.getMainLooper())
    // Distancia a la pala: láser y visión trabajando juntos (la visión acompaña cuando el láser se pierde). Ver DistanceHold.
    private val distanceHold = DistanceHold(TARGET_DISTANCE)
    @Volatile private var laserStampMs: Long = 0L
    @Volatile private var visionStampMs: Long = 0L
    private var shownSource: DistanceHold.Source? = null
    private var seenSource: DistanceHold.Source? = null
    private var seenSourceSinceMs = 0L

    @Volatile private var manualRollInput: Double = 0.0
    @Volatile private var manualPitchInput: Double = 0.0
    @Volatile private var manualYawInput: Double = 0.0

    private val photoHandler = Handler(Looper.getMainLooper())

    private lateinit var btnMainAction: ImageButton
    private lateinit var btnGimbalToggle: ImageButton
    private lateinit var tvInstruction: TextView
    private lateinit var tvLaserDistance: TextView
    private lateinit var layoutBrightness: LinearLayout
    private lateinit var stepId: TextView
    private lateinit var stepTe: TextView
    private lateinit var stepPs: TextView
    private lateinit var stepLe: TextView
    private lateinit var stepSs: TextView
    private lateinit var stepSave: TextView

    private val blacklistDeAlertas = listOf("0x1900D004", "一致性检查", "存在炸机日志", "炸机日志", "Remote ID", "Remote ID functionality normal")

    private val obstacleDataListener = ObstacleDataListener { obstacleData ->
        val distances = obstacleData.horizontalObstacleDistance
        if (!distances.isNullOrEmpty() && distances.size >= 72) {
            val frontales = listOf(distances[71], distances[0], distances[1]).filter { it in 100..30000 }
            currentVisionDistance = if (frontales.isNotEmpty()) (frontales.minOrNull() ?: -1) / 1000f else -1f
            if (currentVisionDistance > 0f) visionStampMs = SystemClock.elapsedRealtime()
        } else {
            currentVisionDistance = -1f
        }
        actualizarUIYDistancia()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()
        setContentView(R.layout.activity_flight)

        bladeLength = intent.getFloatExtra("BLADE_LENGTH", 0f)
        isThreeStop = intent.getBooleanExtra("IS_THREE_STOP", false)
        selectedBladeId = intent.getStringExtra("PALA_ID") ?: ""
        hasIdPhoto = intent.getBooleanExtra("HAS_ID_PHOTO", false)

        bladeLetter = selectedBladeId.replace("Pala ", "").trim()

        inicializarComponentesUI()

        if (isThreeStop) {
            configurarBotonPrincipal()
            configurarBotonGimbal()
            escucharEstadoMotores()
            prepararCamaraBase()
            aplicarSeguridadObligatoria()

            if (hasIdPhoto) {
                marcarPasoCompletado(stepId)
                currentState = InspectionState.POSITIONING
                tvInstruction.text = "Posicionarse en Borde de Salida (TE)"
                actualizarBotonSiguiente()
                btnGimbalToggle.visibility = View.VISIBLE
            }
        }

        activarFiltroDeAlertas()
        iniciarTelemetroLaser()
        escucharAltitud()
        PerceptionManager.getInstance().addObstacleDataListener(obstacleDataListener)
        escucharJoysticks()
        escucharBotonesPersonalizados()
    }

    // ==========================================
    // ESCUCHA DE TELEMETRÍA
    // ==========================================
    private fun escucharAltitud() {
        val altKey = KeyTools.createKey(FlightControllerKey.KeyAltitude)
        KeyManager.getInstance().listen(altKey, this) { _, newValue ->
            if (newValue != null) {
                currentAltitude = newValue.toDouble()
            }
        }
    }

    // ==========================================
    // CARPETAS Y FOTOS SUFIJOS (START, INTERVAL, END)
    // ==========================================
    private fun setDirectoryName(dirSuffix: String, onSuccess: () -> Unit) {
        val nameSettings = CustomExpandNameSettings().apply {
            customContent = dirSuffix
            forceCreateFolder = true
            priority = 0
            encodingType = EnCodingType.UTF8
            relativePosition = RelativePosition.POSITION_END
        }
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCustomExpandDirectoryNameSettings, ComponentIndexType.LEFT_OR_MAIN), nameSettings, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { onSuccess() }
            override fun onFailure(e: IDJIError) { onSuccess() }
        })
    }

    private fun setFileName(fileSuffix: String, onSuccess: () -> Unit) {
        val nameSettings = CustomExpandNameSettings().apply {
            customContent = fileSuffix
            forceCreateFolder = false
            priority = 0
            encodingType = EnCodingType.UTF8
            relativePosition = RelativePosition.POSITION_END
        }
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCustomExpandFileNameSettings, ComponentIndexType.LEFT_OR_MAIN), nameSettings, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { onSuccess() }
            override fun onFailure(e: IDJIError) { onSuccess() }
        })
    }

    private fun setCameraMode(mode: CameraMode, onComplete: () -> Unit) {
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN), mode, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { onComplete() }
            override fun onFailure(error: IDJIError) { onComplete() }
        })
    }

    private fun dispararFoto(dirSuffix: String?, fileSuffix: String, mode: CameraMode, onComplete: () -> Unit) {
        setCameraMode(mode) {
            photoHandler.postDelayed({
                val configureFileAndShoot = {
                    setFileName(fileSuffix) {
                        photoHandler.postDelayed({
                            KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)
                            onComplete()
                        }, 1500)
                    }
                }

                if (dirSuffix != null) {
                    setDirectoryName(dirSuffix) {
                        photoHandler.postDelayed({ configureFileAndShoot() }, 1000)
                    }
                } else {
                    configureFileAndShoot()
                }
            }, 500)
        }
    }

    private fun iniciarSecuenciaDeFotos() {
        val centerRotation = GimbalAngleRotation().apply {
            pitch = 0.0
            roll = 0.0
            yaw = 0.0
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            duration = 0.5
        }
        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN), centerRotation, null)

        tvInstruction.text = "Iniciando Vuelo..."

        photoHandler.postDelayed({
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)

                    startAltitude = currentAltitude
                    distanceHold.reset()
                    shownSource = null; seenSource = null
                    isAutoFlying = true
                    isVerticalMovementAllowed = false
                    virtualStickHandler.post(virtualStickRunnable)

                    val dirName = "BLADE-$bladeLetter-${currentSection.name}"

                    dispararFoto(dirName, "START", CameraMode.PHOTO_NORMAL) {
                        photoHandler.postDelayed({ iniciarIntervalo() }, 2500)
                    }
                }
                override fun onFailure(error: IDJIError) {
                    mostrarToast("Error iniciando Vuelo: ${error.description()}")
                    btnMainAction.isEnabled = true
                }
            })
        }, 2000)
    }

    private fun iniciarIntervalo() {
        if (!isAutoFlying) return
        tvInstruction.text = "Inspeccionando ${currentSection.name}"

        setCameraMode(CameraMode.PHOTO_INTERVAL) {
            photoHandler.postDelayed({
                setFileName("INTERVAL") {
                    photoHandler.postDelayed({
                        val intervalSettings = PhotoIntervalShootSettings().apply {
                            count = 255
                            interval = PHOTO_INTERVAL
                        }
                        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyPhotoIntervalShootSettings, ComponentIndexType.LEFT_OR_MAIN), intervalSettings, null)

                        photoHandler.postDelayed({
                            KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)
                            isVerticalMovementAllowed = true
                        }, 1000)
                    }, 500)
                }
            }, 500)
        }
    }

    private fun detenerSecuenciaYEsperarConfirmacion() {
        isVerticalMovementAllowed = false
        isHoverLocked = true
        manualRollInput = 0.0
        manualPitchInput = 0.0
        manualYawInput = 0.0

        tvInstruction.text = "Cerrando inspección..."

        KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStopShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)

        photoHandler.postDelayed({
            btnMainAction.isEnabled = true
            mostrarDialogoConfirmacion()
        }, 2500)
    }

    // ==========================================
    // BOTÓN PRINCIPAL Y ESTADOS
    // ==========================================
    private fun configurarBotonPrincipal() {
        btnMainAction.setOnClickListener {
            when (currentState) {
                InspectionState.ID_PHOTO -> {
                    btnMainAction.isEnabled = false
                    tvInstruction.text = "Guardando ID..."

                    dispararFoto("ID", "ID", CameraMode.PHOTO_NORMAL) {
                        runOnUiThread {
                            marcarPasoCompletado(stepId)
                            currentState = InspectionState.POSITIONING
                            tvInstruction.text = "Posicionarse en Borde de Salida (TE)"
                            tvInstruction.setTextColor(Color.WHITE)
                            btnMainAction.setImageResource(android.R.drawable.ic_media_next)
                            actualizarBotonSiguiente()
                            btnGimbalToggle.visibility = View.VISIBLE
                        }
                    }
                }
                InspectionState.POSITIONING -> {
                    if (!areMotorsOn) {
                        if (dialogMotores?.isShowing != true) {
                            dialogMotores = AlertDialog.Builder(this)
                                .setTitle("Motores Apagados")
                                .setMessage("Por favor, encienda los motores del dron para continuar con la inspección.")
                                .setCancelable(false)
                                .create()
                            dialogMotores?.show()
                        }
                        return@setOnClickListener
                    }

                    val isForbiddenMode = currentDjiFlightMode.contains("SPORT") || currentDjiFlightMode.contains("CINE") || currentDjiFlightMode.contains("TRIPOD") || currentDjiFlightMode.contains("ATTI")
                    if (isForbiddenMode) {
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ Cambio de Modo Obligatorio")
                            .setMessage("Para iniciar la inspección, DEBE regresar el switch físico del control a la posición N (Normal).")
                            .setPositiveButton("ENTENDIDO", null)
                            .setCancelable(false)
                            .show()
                        return@setOnClickListener
                    }

                    if (!isDistanceValid) {
                        mostrarToast("Acérquese a ${TARGET_DISTANCE} metros de la pala para continuar")
                        return@setOnClickListener
                    }

                    currentState = InspectionState.BRIGHTNESS
                    tvInstruction.text = "Ajustar brillo para continuar"
                    tvInstruction.setTextColor(Color.WHITE)
                    btnMainAction.setImageResource(android.R.drawable.ic_media_play)
                    btnMainAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                    btnGimbalToggle.visibility = View.GONE
                    layoutBrightness.visibility = View.VISIBLE
                }

                InspectionState.BRIGHTNESS -> {
                    if (!areMotorsOn) {
                        mostrarToast("Encienda los motores primero.")
                        return@setOnClickListener
                    }
                    val isForbiddenMode = currentDjiFlightMode.contains("SPORT") || currentDjiFlightMode.contains("CINE") || currentDjiFlightMode.contains("TRIPOD") || currentDjiFlightMode.contains("ATTI")
                    if (isForbiddenMode) {
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ Cambio de Modo Obligatorio")
                            .setMessage("Regrese el switch a la posición N (Normal) para arrancar el Piloto Automático.")
                            .setPositiveButton("ENTENDIDO", null)
                            .setCancelable(false)
                            .show()
                        return@setOnClickListener
                    }

                    layoutBrightness.visibility = View.GONE
                    currentState = InspectionState.AUTO_FLIGHT
                    btnMainAction.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    btnMainAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                    tvInstruction.setTextColor(Color.WHITE)

                    iniciarSecuenciaDeFotos()
                }
                InspectionState.AUTO_FLIGHT -> {
                    btnMainAction.isEnabled = false
                    detenerSecuenciaYEsperarConfirmacion()
                }
                InspectionState.WAITING_LANDING -> mostrarToast("Inspección Terminada")
            }
        }
    }

    // ==========================================
    // LÓGICA DE ABORTO INMEDIATO (SEGURIDAD)
    // ==========================================
    private fun abortarMisionInmediatamente() {
        if (!isAutoFlying) return

        isAutoFlying = false
        isVerticalMovementAllowed = false
        isHoverLocked = false
        virtualStickHandler.removeCallbacks(virtualStickRunnable)

        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {}
        })

        KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStopShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)

        runOnUiThread {
            tvInstruction.text = "Abortado, reinspeccionar tramo"
            tvInstruction.setTextColor(Color.RED)

            btnMainAction.isEnabled = true
            btnMainAction.setImageResource(android.R.drawable.ic_media_play)
            btnMainAction.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))

            currentState = InspectionState.BRIGHTNESS
            layoutBrightness.visibility = View.VISIBLE

            mostrarToast("¡MISIÓN ABORTADA! Control manual total.")
        }
    }

    // ==========================================
    // ESCUCHADOR DE JOYSTICKS Y SEGURIDAD
    // ==========================================
    private fun aplicarSeguridadObligatoria() {
        val failsafeKey = KeyTools.createKey(FlightControllerKey.KeyFailsafeAction)
        KeyManager.getInstance().setValue(failsafeKey, FailsafeAction.HOVER, null)
    }

    private fun procesarJoystick(stickValue: Double, isYaw: Boolean = false): Double {
        val deadband = 30.0
        val maxStick = 660.0
        val maxSpeed = if (isYaw) 25.0 else 2.0
        if (Math.abs(stickValue) > deadband) { return ((stickValue - Math.signum(stickValue) * deadband) / (maxStick - deadband)) * maxSpeed }
        return 0.0
    }

    private fun checkEmergencyAndSetPitch(value: Double): Double {
        if (value < -500.0) {
            runOnUiThread { if (isAutoFlying) abortarMisionInmediatamente() }
            return 0.0
        }
        val deadband = 30.0
        val maxStick = 660.0
        if (Math.abs(value) > deadband) { return ((value - Math.signum(value) * deadband) / (maxStick - deadband)) * 1.5 }
        return 0.0
    }

    private fun escucharJoysticks() {
        val prefs = getSharedPreferences("Nexus_Safety_Prefs", MODE_PRIVATE)
        val rcMode = prefs.getInt("rc_mode", 2)

        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightHorizontal), this) { _, value ->
            if (value != null && isAutoFlying && !isHoverLocked) {
                when (rcMode) { 1, 2 -> manualRollInput = procesarJoystick(value.toDouble(), false); 3 -> manualYawInput = procesarJoystick(value.toDouble(), true) }
            }
        }

        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this) { _, newValue ->
            val modeName = newValue?.name?.uppercase() ?: "DESCONOCIDO"
            currentDjiFlightMode = modeName
            if (currentDjiFlightMode == "DESCONOCIDO" || currentDjiFlightMode == "UNKNOWN") return@listen

            val isForbiddenMode = currentDjiFlightMode.contains("SPORT") || currentDjiFlightMode.contains("CINE") || currentDjiFlightMode.contains("TRIPOD") || currentDjiFlightMode.contains("ATTI")

            runOnUiThread {
                if (isForbiddenMode && currentState != InspectionState.WAITING_LANDING && !isAutoFlying) {
                    if (flightModeDialog?.isShowing != true) {
                        flightModeDialog = AlertDialog.Builder(this@FlightActivity)
                            .setTitle("⚠️ Modo Manual Avanzado")
                            .setMessage("Ha cambiado a un modo de vuelo no asistido ($currentDjiFlightMode).\n\nVuele bajo su propia responsabilidad.")
                            .setPositiveButton("ACEPTO RESPONSABILIDAD", null)
                            .setCancelable(false)
                            .create()
                        flightModeDialog?.show()
                    }
                } else if (!isForbiddenMode) {
                    if (flightModeDialog?.isShowing == true) {
                        flightModeDialog?.dismiss()
                        mostrarToast("Modo Normal restaurado.")
                    }
                }
            }
        }

        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyStickRightVertical), this) { _, value ->
            if (value != null && isAutoFlying && !isHoverLocked) { when (rcMode) { 2 -> manualPitchInput = checkEmergencyAndSetPitch(value.toDouble()) } }
        }
        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftHorizontal), this) { _, value ->
            if (value != null && isAutoFlying && !isHoverLocked) { when (rcMode) { 1, 2 -> manualYawInput = procesarJoystick(value.toDouble(), true); 3 -> manualRollInput = procesarJoystick(value.toDouble(), false) } }
        }
        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyStickLeftVertical), this) { _, value ->
            if (value != null && isAutoFlying && !isHoverLocked) { when (rcMode) { 1, 3 -> manualPitchInput = checkEmergencyAndSetPitch(value.toDouble()) } }
        }
    }

    private fun escucharBotonesPersonalizados() {
        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyCustomButton1Down), this) { _, isPressed ->
            if (isPressed == true) { runOnUiThread { cambiarBrillo(false) } }
        }

        KeyManager.getInstance().listen(KeyTools.createKey(RemoteControllerKey.KeyCustomButton2Down), this) { _, isPressed ->
            if (isPressed == true) { runOnUiThread { cambiarBrillo(true) } }
        }
    }

    // ==========================================
    // CEREBRO DE VUELO (MANUAL HÍBRIDO)
    // ==========================================
    private val virtualStickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isAutoFlying) return
            val param = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

                val mult = if (currentSection == BladeSection.TE || currentSection == BladeSection.LE) 1.0 else -1.0

                // CÁLCULO DE FRENO INTELIGENTE SUAVE
                val distanciaRecorrida = Math.abs(currentAltitude - startAltitude)
                val distanciaRestante = bladeLength - distanciaRecorrida
                // Distancia a la pala: el láser manda y la visión lo acompaña cuando se pierde; null si no ve ninguno
                // más de 1 s (entonces no se corrige solo y el piloto acerca o aleja con el stick)
                val now = SystemClock.elapsedRealtime()
                distanceHold.feedLaser(currentLaserDistance, laserStampMs)
                distanceHold.feedVision(currentVisionDistance, visionStampMs)
                val fuente = distanceHold.source(now)
                val dist = distanceHold.distance(now)
                avisarFuente(fuente, now)

                val factorDistancia = ((dist ?: TARGET_DISTANCE) / TARGET_DISTANCE).toDouble()
                val factorLongitud = Math.sqrt((bladeLength / 45.0).toDouble())

                // NOTA: Se redujo a 0.3 el límite inferior para permitir vuelos muy lentos
                val velocidadCruceroDinamica = (CRUISE_SPEED * factorDistancia * factorLongitud).coerceIn(0.3, 1.5)

                val currentSpeed = if (distanciaRestante <= BRAKING_ZONE_METERS) {
                    val factorFreno = (distanciaRestante / BRAKING_ZONE_METERS).coerceIn(0.0, 1.0)
                    TIP_SAFE_SPEED + (velocidadCruceroDinamica - TIP_SAFE_SPEED) * factorFreno
                } else {
                    velocidadCruceroDinamica
                }

                verticalThrottle = if (isVerticalMovementAllowed) (currentSpeed * mult) else 0.0
                yaw = manualYawInput

                val autoDistanceVelocity = if (isVerticalMovementAllowed && !isHoverLocked) {
                    when {
                        dist != null -> distanceHold.velocity(dist, viaVision = fuente == DistanceHold.Source.VISION)
                        lastLaserState == LaserMeasureState.TOO_CLOSE -> -0.5
                        else -> 0.0
                    }
                } else 0.0

                // INYECCIÓN A MOTORES: Solo Distancia Automática y Corrección Lateral Manual
                roll = if (manualPitchInput != 0.0) manualPitchInput else autoDistanceVelocity
                pitch = manualRollInput
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            virtualStickHandler.postDelayed(this, 50)
        }
    }

    /**
     * Dice en pantalla con qué se está midiendo la distancia. El láser parpadea en los bordes de la pala, así que el
     * cambio de fuente solo se anuncia si dura más de 1,5 s.
     */
    private fun avisarFuente(fuente: DistanceHold.Source, nowMs: Long) {
        if (!isAutoFlying) return
        if (fuente != seenSource) { seenSource = fuente; seenSourceSinceMs = nowMs }
        if (fuente == shownSource || nowMs - seenSourceSinceMs < 1500L) return
        shownSource = fuente
        runOnUiThread {
            if (currentState != InspectionState.AUTO_FLIGHT) return@runOnUiThread
            when (fuente) {
                DistanceHold.Source.LASER -> { tvInstruction.text = "Inspeccionando ${currentSection.name}"; tvInstruction.setTextColor(Color.WHITE) }
                DistanceHold.Source.VISION -> { tvInstruction.text = "Láser perdido: siguiendo con visión"; tvInstruction.setTextColor(Color.parseColor("#80DEEA")) }
                DistanceHold.Source.NONE -> { tvInstruction.text = "Sin láser ni visión: acerca o aleja con el stick (el dron no corrige solo)"; tvInstruction.setTextColor(Color.parseColor("#FFEB3B")) }
            }
        }
    }

    private fun actualizarUIYDistancia() {
        runOnUiThread {
            val distActiva: Float
            val nombreSensor: String

            if (currentLaserDistance > 0.0f) {
                distActiva = currentLaserDistance; nombreSensor = "Laser"
            } else if (currentVisionDistance > 0.0f) {
                distActiva = currentVisionDistance; nombreSensor = "Vision"
            } else {
                distActiva = -1f; nombreSensor = "Ninguno"
            }

            if (distActiva > 0.0f) {
                tvLaserDistance.text = "$nombreSensor: ${String.format("%.1f", distActiva)} m"

                if (distActiva in 3.3f..4.8f) {
                    isDistanceValid = true
                    tvLaserDistance.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    isDistanceValid = false
                    tvLaserDistance.setTextColor(Color.parseColor("#F44336"))
                }
            } else {
                isDistanceValid = false
                tvLaserDistance.setTextColor(Color.parseColor("#F44336"))
                when (lastLaserState) {
                    LaserMeasureState.TOO_CLOSE -> tvLaserDistance.text = "MUY CERCA"
                    LaserMeasureState.NO_SIGNAL -> tvLaserDistance.text = "SIN SEÑAL"
                    else -> tvLaserDistance.text = "BUSCANDO..."
                }
            }
            if (currentState == InspectionState.POSITIONING) actualizarBotonSiguiente()
        }
    }

    private fun actualizarBotonSiguiente() {
        btnMainAction.isEnabled = true
        btnMainAction.backgroundTintList = ColorStateList.valueOf(if (isDistanceValid) Color.parseColor("#4CAF50") else Color.GRAY)
    }

    private fun iniciarTelemetroLaser() {
        val laserDataKey = KeyTools.createKey(CameraKey.KeyLaserMeasureInformation, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().listen(laserDataKey, this) { _, newValue ->
            if (newValue != null) {
                lastLaserState = newValue.laserMeasureState
                val dist = newValue.distance.toFloat()
                currentLaserDistance = if (dist > 0.0f && dist < 200.0f) dist else -1f
                if (currentLaserDistance > 0f) laserStampMs = SystemClock.elapsedRealtime()
                actualizarUIYDistancia()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyLaserMeasureEnabled, ComponentIndexType.LEFT_OR_MAIN), true, null)
        }, 3000)
    }

    private fun inicializarComponentesUI() {
        val fpv: FPVWidget = findViewById(R.id.widget_fpv)
        fpv.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)
        fpv.isCameraSourceNameVisible = false
        fpv.isCameraSourceSideVisible = false

        tvLaserDistance = findViewById(R.id.tv_laser_distance)
        btnMainAction = findViewById(R.id.btn_main_action)
        btnGimbalToggle = findViewById(R.id.btn_gimbal_toggle)
        tvInstruction = findViewById(R.id.tv_instruction)
        layoutBrightness = findViewById(R.id.layout_brightness)

        stepId = findViewById(R.id.step_id); stepTe = findViewById(R.id.step_te)
        stepPs = findViewById(R.id.step_ps); stepLe = findViewById(R.id.step_le)
        stepSs = findViewById(R.id.step_ss); stepSave = findViewById(R.id.step_save)

        findViewById<Button>(R.id.btn_ev_plus).setOnClickListener { cambiarBrillo(true) }
        findViewById<Button>(R.id.btn_ev_minus).setOnClickListener { cambiarBrillo(false) }
    }

    private fun cambiarBrillo(mas: Boolean) {
        val evKey = KeyTools.createKey(CameraKey.KeyExposureCompensation, ComponentIndexType.LEFT_OR_MAIN)
        val actual = KeyManager.getInstance().getValue(evKey) ?: return
        val lista = CameraExposureCompensation.values().filter { it.name != "UNKNOWN" }
        val idx = lista.indexOf(actual)
        val nuevoIdx = if (mas) (idx + 1).coerceAtMost(lista.size - 1) else (idx - 1).coerceAtLeast(0)
        KeyManager.getInstance().setValue(evKey, lista[nuevoIdx], null)
    }

    private fun configurarBotonGimbal() {
        btnGimbalToggle.setOnClickListener {
            isGimbalDown = !isGimbalDown
            val rot = GimbalAngleRotation().apply {
                pitch = if (isGimbalDown) -90.0 else 0.0
                mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
                duration = 0.8
            }
            KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle), rot, null)
        }
    }

    private fun escucharEstadoMotores() {
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn), this) { _, on ->
            areMotorsOn = on == true

            runOnUiThread {
                if (areMotorsOn && dialogMotores?.isShowing == true) {
                    dialogMotores?.dismiss()
                    dialogMotores = null
                    mostrarToast("Motores encendidos. Puede continuar.")
                }

                if (currentState == InspectionState.WAITING_LANDING && areMotorsOn == false) {
                    tvInstruction.text = "Guardando..."
                    Handler(Looper.getMainLooper()).postDelayed({
                        setResult(RESULT_OK, Intent().apply { putExtra("COMPLETED_BLADE", selectedBladeId) })
                        finish()
                    }, 2000)
                }
            }
        }
    }

    private fun liberarControlManual() {
        isAutoFlying = false
        isHoverLocked = false
        virtualStickHandler.removeCallbacks(virtualStickRunnable)
        VirtualStickManager.getInstance().disableVirtualStick(null)
    }

    private fun mostrarDialogoConfirmacion() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tramo Finalizado")
            .setMessage("La inspeccion del tramo ${currentSection.name} es correcta?")
            .setPositiveButton("SI, SIGUIENTE", null)
            .setNegativeButton("REPETIR") { _, _ ->
                liberarControlManual()
                currentState = InspectionState.POSITIONING
                tvInstruction.text = "Reposicionarse en ${currentSection.name}"
                btnMainAction.setImageResource(android.R.drawable.ic_media_next)
                actualizarBotonSiguiente()
                btnGimbalToggle.visibility = View.VISIBLE
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            val btnPositive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val btnNegative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

            btnPositive.setOnClickListener {
                btnPositive.isEnabled = false
                btnNegative.isEnabled = false

                dialog.setTitle("Finalizando...")
                dialog.setMessage("Por favor, espere a que termine el proceso.")

                dispararFoto(null, "END", CameraMode.PHOTO_NORMAL) {
                    runOnUiThread {
                        dialog.dismiss()
                        liberarControlManual()
                        avanzarSeccion()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun avanzarSeccion() {
        when (currentSection) {
            BladeSection.TE -> { marcarPasoCompletado(stepTe); currentSection = BladeSection.PS }
            BladeSection.PS -> { marcarPasoCompletado(stepPs); currentSection = BladeSection.LE }
            BladeSection.LE -> { marcarPasoCompletado(stepLe); currentSection = BladeSection.SS }
            BladeSection.SS -> {
                marcarPasoCompletado(stepSs); marcarPasoCompletado(stepSave)
                currentState = InspectionState.WAITING_LANDING
                tvInstruction.text = "Aterrice la aeronave."
                tvInstruction.setTextColor(Color.WHITE)
                btnMainAction.visibility = View.GONE; return
            }
        }
        currentState = InspectionState.POSITIONING
        tvInstruction.text = "Posicionarse en ${currentSection.name}"
        tvInstruction.setTextColor(Color.WHITE)
        btnMainAction.setImageResource(android.R.drawable.ic_media_next)
        actualizarBotonSiguiente()
        btnGimbalToggle.visibility = View.VISIBLE
    }

    private fun marcarPasoCompletado(v: TextView) {
        val d = ContextCompat.getDrawable(this, R.drawable.circle_bg)?.mutate()
        d?.setTint(Color.parseColor("#4CAF50"))
        v.background = d
    }

    private fun prepararCamaraBase() {
        val idx = ComponentIndexType.LEFT_OR_MAIN
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraMode, idx), CameraMode.PHOTO_NORMAL, null)
        KeyManager.getInstance().setValue(KeyTools.createKey(CameraKey.KeyCameraStorageLocation, idx), CameraStorageLocation.SDCARD, null)
    }

    private fun activarFiltroDeAlertas() {
        val root = findViewById<View>(android.R.id.content)
        root.viewTreeObserver.addOnGlobalLayoutListener { escanearYOcultar(root) }
    }

    private fun escanearYOcultar(v: View) {
        if (v is TextView && blacklistDeAlertas.any { v.text.toString().contains(it, ignoreCase = true) }) v.text = ""
        else if (v is ViewGroup) (0 until v.childCount).forEach { escanearYOcultar(v.getChildAt(it)) }
    }

    private fun mostrarToast(m: String) { runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }

    override fun onDestroy() {
        super.onDestroy()
        KeyManager.getInstance().performAction(KeyTools.createKey(CameraKey.KeyStopShootPhoto, ComponentIndexType.LEFT_OR_MAIN), null, null)
        liberarControlManual()

        flightModeDialog?.dismiss()
        PerceptionManager.getInstance().removeObstacleDataListener(obstacleDataListener)
        KeyManager.getInstance().cancelListen(this)
    }
}