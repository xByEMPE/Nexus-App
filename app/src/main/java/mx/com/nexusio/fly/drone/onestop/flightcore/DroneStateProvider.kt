package mx.com.nexusio.fly.drone.onestop.flightcore

import android.os.SystemClock
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import kotlin.math.abs

/**
 * Estado en vivo del dron (GPS, altitud relativa, actitud, gimbal, láser, torre) para la UI de medición
 * y la vigilancia de seguridad.
 */
class DroneStateProvider {
    @Volatile var latitude = 0.0; private set
    @Volatile var longitude = 0.0; private set
    /** Altitud relativa al punto de despegue (m); coincide con heightMode=relativeToStartPoint del KMZ. */
    @Volatile var altitude = 0.0; private set
    @Volatile var yawDeg = 0.0; private set
    /** Pitch del propio dron (no del gimbal): inclina los sensores de visión, que van fijos al cuerpo. */
    @Volatile var aircraftPitchDeg = 0.0; private set
    @Volatile var gimbalPitchDeg = 0.0; private set
    @Volatile private var laserRaw = -1f
    @Volatile var laserState: LaserMeasureState? = null; private set
    @Volatile private var visionRaw = -1f
    @Volatile private var towerRaw = 999f

    val laserDistance: Float get() = laserRaw
    /** Cuántas lecturas válidas del láser han llegado; si no cambia entre dos ticks, no hubo lectura nueva. */
    @Volatile var laserSampleCount = 0L; private set
    /** Último barrido horizontal completo de los sensores de visión (mm, índice 0 = morro, sentido horario). */
    @Volatile var horizontalSectorsMm: List<Int> = emptyList(); private set
    /** Cuántos barridos de visión han llegado (para saber si hay uno nuevo). */
    @Volatile var visionSampleCount = 0L; private set
    val visionDistance: Float get() = visionRaw
    val towerDistance: Float get() = towerRaw

    // Hora (elapsedRealtime) de la última lectura de cada sensor: un valor viejo no se muestra como si fuera actual.
    @Volatile private var laserAtMs = 0L
    @Volatile private var visionAtMs = 0L

    /** Distancia del láser si llegó hace menos de [maxAgeMs]; null si no hay lectura reciente. */
    fun freshLaserM(maxAgeMs: Long = 1000): Double? =
        laserRaw.takeIf { it > 0f && SystemClock.elapsedRealtime() - laserAtMs <= maxAgeMs }?.toDouble()

    /** Último barrido de visión si llegó hace menos de [maxAgeMs]; vacío si no. */
    fun freshSectorsMm(maxAgeMs: Long = 1000): List<Int> =
        if (SystemClock.elapsedRealtime() - visionAtMs <= maxAgeMs) horizontalSectorsMm else emptyList()

    val hasGpsFix get() = abs(latitude) > 1e-6 && abs(longitude) > 1e-6

    fun location() = DroneLocation(latitude.takeIf { hasGpsFix }, longitude.takeIf { hasGpsFix }, altitude)

    private val obstacleListener = ObstacleDataListener { data ->
        val d = data.horizontalObstacleDistance
        if (!d.isNullOrEmpty() && data.horizontalAngleInterval != 0) applySectors(d.toList())
    }

    private fun applySectors(d: List<Int>) {
        if (d.isNotEmpty()) {
            horizontalSectorsMm = d
            visionSampleCount++
            visionAtMs = SystemClock.elapsedRealtime()
        }
        if (d.size >= 72) {
            val front = listOf(d[71], d[0], d[1]).filter { it in 100..30000 }
            visionRaw = if (front.isNotEmpty()) front.min() / 1000f else -1f
            val valid = d.filter { it in 100..30000 }
            towerRaw = if (valid.isNotEmpty()) valid.min() / 1000f else 999f
        } else {
            visionRaw = -1f
            towerRaw = 999f
        }
    }

    fun start(owner: Any) {
        val laserKey = KeyTools.createKey(CameraKey.KeyLaserMeasureInformation, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().listen(laserKey, owner) { _, v ->
            if (v != null) {
                laserState = v.laserMeasureState
                val dist = v.distance.toFloat()
                laserRaw = if (dist in 0.1f..200f) dist else -1f
                if (laserRaw > 0f) { laserSampleCount++; laserAtMs = SystemClock.elapsedRealtime() }
            } else laserRaw = -1f
        }
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation), owner) { _, v ->
            if (v != null && v.latitude != 0.0 && v.longitude != 0.0) {
                latitude = v.latitude; longitude = v.longitude
            }
        }
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAltitude), owner) { _, v ->
            if (v != null) altitude = v
        }
        KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude), owner) { _, v: Attitude? ->
            if (v != null) { yawDeg = v.yaw; aircraftPitchDeg = v.pitch }
        }
        KeyManager.getInstance().listen(KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN), owner) { _, v: Attitude? ->
            if (v != null) gimbalPitchDeg = v.pitch
        }
        PerceptionManager.getInstance().addObstacleDataListener(obstacleListener)
    }

    fun stop(owner: Any) {
        PerceptionManager.getInstance().removeObstacleDataListener(obstacleListener)
        KeyManager.getInstance().cancelListen(owner)
    }
}
