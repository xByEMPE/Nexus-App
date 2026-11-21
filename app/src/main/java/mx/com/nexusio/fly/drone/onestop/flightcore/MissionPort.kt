package mx.com.nexusio.fly.drone.onestop.flightcore

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ───────────────────────── Desacoplamiento: el núcleo solo conoce este puerto ─────────────────────────
// Un adaptador (módulo aparte) lo implementa con DJI MSDK v5:
//   upload  -> WaypointMissionManager.pushKMZFileToAircraft
//   start   -> startMission(waylineId)   pause/resume/stop -> idem
// El núcleo matemático NO importa dji.* ni android.*; se puede testear en JVM puro y portar a C++.

/** Acción ejecutada al llegar al waypoint (varias por waypoint en los barridos de yaw/pitch). */
data class PlannedAction(
    val yawDeg: Double,
    val gimbalPitchDeg: Double,
    val takePhoto: Boolean,
    val rotateGimbal: Boolean,
    val rotateDroneYaw: Boolean,
)

data class PlannedWaypoint(
    val position: GeoPoint,
    val yawDeg: Double,
    val gimbalPitchDeg: Double,
    val takePhoto: Boolean,
    val hoverSeconds: Double = 0.0,
    val actions: List<PlannedAction> = emptyList(),
    /** Velocidad hacia este waypoint (m/s); null = la del plan. */
    val speedMps: Double? = null,
    /** true = el dron se detiene en el waypoint; false = lo atraviesa sin parar (curvatura continua), como el original. */
    val stop: Boolean = true,
    /** true = la pausa ([hoverSeconds]) va antes de la foto (tras girar dron y gimbal); false = después de todo. */
    val hoverBeforePhoto: Boolean = false,
)

data class MissionPlan(val id: String, val speedMps: Double, val waypoints: List<PlannedWaypoint>)

interface MissionPort {
    suspend fun upload(plan: MissionPlan, onProgress: (Double) -> Unit)
    suspend fun start(planId: String)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop(planId: String)
}

/** Límites de seguridad inyectados (no embebidos en el algoritmo). */
data class SafetyLimits(val maxSpeedMps: Double, val maxWaypoints: Int, val maxGimbalUp: Double, val maxGimbalDown: Double)

// ───────────────────────── Zero Trust: el plan se firma al crearlo y se verifica antes de subirlo ─────────────────────────
class SignedMissionGateway(
    private val port: MissionPort,
    private val limits: SafetyLimits,
    private val key: ByteArray, // proviene de Android Keystore, nunca de código/recursos
) {
    private val signatures = HashMap<String, ByteArray>()

    /** Debe llamarse únicamente desde el planificador (mismo proceso, mismo módulo). */
    fun seal(plan: MissionPlan): MissionPlan {
        validate(plan)
        signatures[plan.id] = mac(plan)
        return plan
    }

    suspend fun upload(plan: MissionPlan, onProgress: (Double) -> Unit) {
        val sig = signatures[plan.id] ?: error("plan ${plan.id} was never sealed")
        check(MessageDigest.isEqual(sig, mac(plan))) { "plan ${plan.id} modified after sealing" }
        validate(plan) // doble verificación: no confiar en el estado previo
        port.upload(plan, onProgress)
    }

    suspend fun start(planId: String) = port.start(planId)
    suspend fun pause() = port.pause()
    suspend fun resume() = port.resume()
    suspend fun stop(planId: String) = port.stop(planId)

    private fun validate(p: MissionPlan) {
        require(p.waypoints.isNotEmpty() && p.waypoints.size <= limits.maxWaypoints) { "waypoint count out of bounds" }
        Guard.inRange("speed", p.speedMps, 0.1, limits.maxSpeedMps)
        p.waypoints.forEach {
            Guard.inRange("gimbal", it.gimbalPitchDeg, -limits.maxGimbalDown, limits.maxGimbalUp)
            Guard.inRange("yaw", it.yawDeg, -180.0, 180.0)
            Guard.inRange("hover", it.hoverSeconds, 0.0, 60.0)
            it.speedMps?.let { v -> Guard.inRange("waypoint speed", v, 0.1, limits.maxSpeedMps) }
            it.actions.forEach { a ->
                Guard.inRange("action gimbal", a.gimbalPitchDeg, -limits.maxGimbalDown, limits.maxGimbalUp)
                Guard.inRange("action yaw", a.yawDeg, -180.0, 180.0)
            }
        }
    }

    private fun mac(p: MissionPlan): ByteArray {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        m.update("${p.id}|${p.speedMps}".toByteArray())
        p.waypoints.forEach {
            m.update("|${it.position}|${it.yawDeg}|${it.gimbalPitchDeg}|${it.takePhoto}|${it.hoverSeconds}|${it.actions}".toByteArray())
            m.update("|${it.speedMps}|${it.stop}|${it.hoverBeforePhoto}".toByteArray())
        }
        return m.doFinal()
    }
}
