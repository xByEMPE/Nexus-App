package mx.com.nexusio.fly.drone.onestop

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.GoHomeState
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Posición de los cuatro ejes del mando (−660…660, 0 = centro), como los da `RemoteControllerKey.KeyStick*`. */
data class Sticks(val leftH: Int = 0, val leftV: Int = 0, val rightH: Int = 0, val rightV: Int = 0) {
    /** Eje de cabeceo (adelante/atrás) según el modo del mando guardado en `rc_mode`: modo 2 a la derecha, 1 y 3 a la izquierda. */
    fun pitch(rcMode: Int) = if (rcMode == 2) rightV else leftV

    fun allWithin(deadband: Int) = abs(leftH) <= deadband && abs(leftV) <= deadband && abs(rightH) <= deadband && abs(rightV) <= deadband

    /** Ejes fuera de [deadband] con su valor (para decirle al piloto cuál falta por soltar). */
    fun offCenter(deadband: Int): List<Pair<String, Int>> =
        listOf("izq. horizontal" to leftH, "izq. vertical" to leftV, "der. horizontal" to rightH, "der. vertical" to rightV)
            .filter { abs(it.second) > deadband }

    companion object { const val MAX = 660 }
}

/**
 * Tirón brusco del stick de cabeceo hacia atrás durante la misión: pasar de cerca del centro a más allá de
 * [triggerAt] (el mismo umbral que el paro del Three Stop, −500 de −660) en menos de [maxPullMs]. Un movimiento
 * lento no lo dispara. Tras dispararse no vuelve a hacerlo hasta que el stick regresa al centro.
 */
class PullBackDetector(
    private val triggerAt: Int = -500,
    private val nearCenter: Int = 150,
    private val maxPullMs: Long = 600,
) {
    private var lastNearCenterMs: Long? = null
    private var latched = false

    /** Devuelve true una sola vez, en la lectura que completa el tirón. */
    fun update(pitch: Int, nowMs: Long): Boolean {
        if (abs(pitch) <= nearCenter) { lastNearCenterMs = nowMs; latched = false; return false }
        if (latched || pitch > triggerAt) return false
        val from = lastNearCenterMs ?: return false
        latched = true
        return nowMs - from <= maxPullMs
    }

    fun reset() { lastNearCenterMs = null; latched = false }
}

/** Lo que hace falta del SDK para el paro de emergencia (interfaz para poder probarlo sin dron). */
interface EmergencyStopPort {
    /** Detiene del todo la misión de waypoints (no se puede reanudar). */
    suspend fun stopMission()

    /** Cancela una vuelta a casa en curso, si la hay. */
    suspend fun stopGoHome()

    /** Toma el control con el virtual stick y mantiene el dron quieto (las órdenes de los sticks no lo mueven). */
    suspend fun startHold()

    /** Devuelve el control a los sticks del mando. */
    suspend fun releaseHold()
}

/**
 * Paro de emergencia del One Stop, desde el botón de la pantalla o con un tirón del stick de cabeceo hacia atrás:
 * detiene la misión (y una vuelta a casa en curso) y deja el dron en manos del piloto. Si al parar algún stick no
 * está en el centro (el caso del tirón), el dron se mantiene quieto hasta que todos vuelven al centro durante
 * [centerHoldMs]; así el control manual no empieza con el stick a fondo. El margen [deadband] (≈18 % del recorrido)
 * es amplio a propósito: algunos mandos no vuelven exactamente a 0 al soltar el stick.
 */
class EmergencyStopController(
    private val port: EmergencyStopPort,
    val deadband: Int = 120,
    private val centerHoldMs: Long = 300,
) {
    enum class State { IDLE, STOPPING, HOLDING, MANUAL }

    var state = State.IDLE
        private set

    /** Fallos al parar (la misión, la vuelta a casa o la retención); el piloto debe saberlos. */
    val failures = ArrayList<String>()
    private var centeredSince: Long? = null

    /** Ejecuta el paro. [sticks] es la posición del mando en ese momento. */
    suspend fun trigger(sticks: Sticks, nowMs: Long) {
        if (state != State.IDLE) return
        state = State.STOPPING
        failures.clear()
        attempt("misión") { port.stopMission() }
        attempt("vuelta a casa") { port.stopGoHome() }
        if (sticks.allWithin(deadband)) { state = State.MANUAL; return }
        centeredSince = null
        state = if (attempt("retención") { port.startHold() }) State.HOLDING else State.MANUAL
        // Si los sticks ya volvieron al centro mientras se paraba, se suelta en la siguiente lectura.
        onSticks(sticks, nowMs)
    }

    /** Llamar con cada lectura del mando; suelta la retención cuando todo lleva [centerHoldMs] en el centro. */
    suspend fun onSticks(sticks: Sticks, nowMs: Long) {
        if (state != State.HOLDING) return
        if (!sticks.allWithin(deadband)) { centeredSince = null; return }
        val since = centeredSince ?: nowMs.also { centeredSince = it }
        if (nowMs - since < centerHoldMs) return
        // Primero el estado: otra lectura que llegue mientras se suelta no vuelve a soltar.
        state = State.MANUAL
        attempt("soltar retención") { port.releaseHold() }
    }

    /** Vuelve a dejarlo listo para otra misión. */
    fun reset() { state = State.IDLE; failures.clear(); centeredSince = null }

    private suspend fun attempt(name: String, action: suspend () -> Unit): Boolean = try {
        action(); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures += "$name: ${e.message ?: e.javaClass.simpleName}"; false
    }
}

/**
 * [EmergencyStopPort] sobre DJI MSDK v5. La retención es el virtual stick en modo avanzado mandando velocidad cero
 * a 10 Hz (el dron necesita órdenes continuas); mientras está activo, los sticks del mando no mueven el dron.
 */
class DjiEmergencyStopPort(private val stopWaypointMission: suspend () -> Unit) : EmergencyStopPort {
    private val vs get() = VirtualStickManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var holding = false

    class DjiEmergencyException(error: IDJIError) : Exception(error.description())

    private val hover = object : Runnable {
        override fun run() {
            if (!holding) return
            vs.sendVirtualStickAdvancedParam(VirtualStickFlightControlParam().apply {
                pitch = 0.0; roll = 0.0; yaw = 0.0; verticalThrottle = 0.0
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            })
            handler.postDelayed(this, 100)
        }
    }

    override suspend fun stopMission() = stopWaypointMission()

    override suspend fun stopGoHome() {
        val status = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyGoHomeStatus))
        if (status != GoHomeState.RETURNING_TO_HOME && status != GoHomeState.LANDING) return
        suspendCancellableCoroutine<Unit> { cont ->
            KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStopGoHome), null,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(t: EmptyMsg?) { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiEmergencyException(error)) }
                })
        }
    }

    override suspend fun startHold() {
        completion { vs.enableVirtualStick(it) }
        vs.setVirtualStickAdvancedModeEnabled(true)
        holding = true
        handler.post(hover)
    }

    override suspend fun releaseHold() {
        holding = false
        handler.removeCallbacks(hover)
        vs.setVirtualStickAdvancedModeEnabled(false)
        completion { vs.disableVirtualStick(it) }
    }

    private suspend fun completion(call: (CommonCallbacks.CompletionCallback) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        call(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(error: IDJIError) { if (cont.isActive) cont.resumeWithException(DjiEmergencyException(error)) }
        })
    }
}
