package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.PauseTiming
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Viento horizontal: media [speedMps] soplando DESDE [fromDeg] (0 = Norte) más turbulencia (proceso Ornstein-Uhlenbeck por eje,
 * desviación [turbulenceIntensity]·velocidad, constante de tiempo [gustTimeConstSec]).
 * Con [shearRefHeightM] la velocidad media crece con la altura como (z/ref)^[shearExponent] (perfil potencial).
 * [lateralTurbulenceRatio] < 1 hace la turbulencia anisótropa, más fuerte en la dirección del viento que de lado
 * (IEC 61400-1: lateral ≈ 0.7 de la longitudinal); 1 = isótropa (comportamiento previo).
 */
data class WindModel(
    val speedMps: Double,
    val fromDeg: Double,
    val turbulenceIntensity: Double = 0.15,
    val gustTimeConstSec: Double = 8.0,
    val shearRefHeightM: Double? = null,
    val shearExponent: Double = 0.14,
    val seed: Long = 1L,
    val lateralTurbulenceRatio: Double = 1.0,
) {
    /** Un paso del proceso de ráfagas (Ornstein-Uhlenbeck) a la altura [z]. */
    fun stepGust(g: Vec3, dt: Double, z: Double, rng: java.util.Random): Vec3 {
        val tau = gustTimeConstSec; val sigma = turbulenceIntensity * speedAt(z); val k = Math.sqrt(2 * dt / tau)
        if (lateralTurbulenceRatio == 1.0)
            return Vec3(g.x + (-g.x / tau) * dt + sigma * k * rng.nextGaussian(), g.y + (-g.y / tau) * dt + sigma * k * rng.nextGaussian(), 0.0)
        val to = Math.toRadians(fromDeg + 180.0)
        val along = Vec3(sin(to), cos(to), 0.0); val across = Vec3(cos(to), -sin(to), 0.0)
        val gl = g dot along; val gt = g dot across
        val nl = gl + (-gl / tau) * dt + sigma * k * rng.nextGaussian()
        val nt = gt + (-gt / tau) * dt + lateralTurbulenceRatio * sigma * k * rng.nextGaussian()
        return along * nl + across * nt
    }

    fun speedAt(z: Double) = if (shearRefHeightM == null) speedMps else speedMps * Math.pow(max(z, 1.0) / shearRefHeightM, shearExponent)

    /** Velocidad del aire (hacia donde sopla) a la altura [z], en ENU. */
    fun meanAt(z: Double): Vec3 {
        val to = Math.toRadians(fromDeg + 180.0)
        return Vec3(sin(to), cos(to), 0.0) * speedAt(z)
    }
}

/** Parámetros del dron virtual y de los criterios de aceptación. */
data class SimConfig(
    val dt: Double = 0.1,
    /** Umbral duro de distancia a la turbina (mismo que usa el planificador). */
    val minSafeDistance: Double = 4.5,
    val maxYawRateDegPerSec: Double = 60.0,
    val maxGimbalRateDegPerSec: Double = 90.0,
    /** Distancia máxima cámara→punto para que una foto cuente como cobertura útil. */
    val maxPhotoDistance: Double = 30.0,
    /** Separación entre puntos de control a lo largo del eje de cada pala. */
    val coverageStep: Double = 0.5,
    /** Metros antes de la punta que se exigen cubiertos (la punta exacta no es fotografiable). */
    val tipClearance: Double = 1.0,
    /** Viento a simular; null = aire en calma. */
    val wind: WindModel? = null,
    /** Ganancias del seguimiento de trayectoria (PI sobre el error de posición) y velocidad respecto al aire máxima utilizable. */
    val trackKp: Double = 0.6,
    val trackKi: Double = 0.1,
    val maxAirspeedMps: Double = 15.0,
    /** Viento medio a partir del cual el fabricante no garantiza el vuelo (p. ej. 12 m/s en Matrice 300/350). */
    val windResistanceMps: Double = 12.0,
)

data class SimSample(val t: Double, val pos: Vec3, val yawDeg: Double, val gimbalPitchDeg: Double, val nextWaypoint: Int, val distance: Double)

/** Punto de control sobre el eje de una pala y si alguna foto lo ve. */
data class CoveragePoint(val blade: BladePosition, val pos: Vec3, val covered: Boolean)

data class PhotoPose(val waypoint: Int, val pos: Vec3, val yawDeg: Double, val pitchDeg: Double)

data class SimReport(
    val samples: List<SimSample>,
    val photos: List<PhotoPose>,
    val minDistance: Double,
    val minDistanceAt: Vec3,
    val minDistanceComponent: String,
    val flightTimeSec: Double,
    val pathLengthM: Double,
    /** Desviación máxima y RMS entre la posición real y la planificada (0 sin viento). */
    val maxTrackingErrorM: Double,
    val rmsTrackingErrorM: Double,
    /** Fracción [0,1] del eje de cada pala (raíz→punta) visible en alguna foto. */
    val coverage: Map<BladePosition, Double>,
    val coveragePoints: List<CoveragePoint>,
    val violations: List<String>,
) {
    fun summary() = buildString {
        appendLine("waypoints visitados: ${samples.maxOfOrNull { it.nextWaypoint } ?: 0}, fotos: ${photos.size}")
        appendLine("tiempo: %.0f s, trayectoria: %.0f m".format(flightTimeSec, pathLengthM))
        if (maxTrackingErrorM > 0.0) appendLine("error de seguimiento: máx %.2f m, rms %.2f m".format(maxTrackingErrorM, rmsTrackingErrorM))
        appendLine("distancia mínima a la turbina: %.2f m (%s) en %s".format(minDistance, minDistanceComponent, minDistanceAt))
        appendLine("cobertura: " + coverage.entries.joinToString { "${it.key}=%.1f%%".format(it.value * 100) })
        appendLine(if (violations.isEmpty()) "sin violaciones" else "violaciones (${violations.size}):\n  " + violations.take(10).joinToString("\n  "))
    }

    fun writeCsv(file: File) {
        file.parentFile?.mkdirs()
        file.printWriter().use { w ->
            w.println("t,x,y,z,yaw,gimbal_pitch,next_wp,distance")
            for (s in samples) w.println("%.2f,%.3f,%.3f,%.3f,%.2f,%.2f,%d,%.3f".format(java.util.Locale.ROOT, s.t, s.pos.x, s.pos.y, s.pos.z, s.yawDeg, s.gimbalPitchDeg, s.nextWaypoint, s.distance))
        }
    }
}

/**
 * Dron virtual que recorre la lista de waypoints sobre una turbina virtual y mide lo que importa de un vuelo real:
 * holgura a lo largo de TODO el recorrido (no solo en los waypoints), límites de gimbal/yaw y cobertura fotográfica de las palas.
 *
 * Modelo: tramos rectos a la velocidad del waypoint de destino (como la misión de waypoints de DJI, sin suavizado de esquinas),
 * yaw y gimbal interpolados linealmente durante el tramo y pausas en los waypoints que las piden.
 */
class FlightSimulator(
    private val turbine: WindTurbine,
    private val gimbalLimits: GimbalLimits = GimbalLimits(),
    private val cam: CameraFov = CameraFov.default(),
    private val config: SimConfig = SimConfig(),
) {
    fun run(waypoints: List<Waypoint3D>): SimReport {
        require(waypoints.isNotEmpty()) { "no waypoints" }
        val samples = ArrayList<SimSample>()
        val photos = ArrayList<PhotoPose>()
        val violations = ArrayList<String>()
        var minD = Double.MAX_VALUE; var minAt = waypoints[0].point; var minName = ""
        var t = 0.0; var length = 0.0

        // Viento: el dron real sigue la trayectoria con un error e (PI sobre la posición) que empuja el viento + ráfagas.
        val wind = config.wind
        val rng = java.util.Random(wind?.seed ?: 0L)
        var e = Vec3.ZERO; var gust = Vec3.ZERO
        var integ = wind?.meanAt(waypoints[0].point.z) ?: Vec3.ZERO   // ya estabilizado en el viento antes de empezar
        var maxErr = 0.0; var sumSq = 0.0; var nErr = 0
        fun advance(dt: Double, z: Double) {
            if (wind == null) return
            gust = wind.stepGust(gust, dt, z, rng)
            var u = e * config.trackKp + integ                      // velocidad respecto al aire que se contrarresta
            if (u.norm > config.maxAirspeedMps) u = u * (config.maxAirspeedMps / u.norm)
            e += (wind.meanAt(z) + gust - u) * dt
            integ += e * (config.trackKi * dt)
            if (integ.norm > config.maxAirspeedMps) integ = integ * (config.maxAirspeedMps / integ.norm)
            maxErr = max(maxErr, e.norm); sumSq += e.dot(e); nErr++
        }

        var pos = waypoints[0].point
        var yaw = waypoints[0].droneYaw
        var pitch = waypoints[0].actions.firstOrNull { it.rotateGimbal }?.pitch ?: 0.0

        fun observe(nominal: Vec3, next: Int) {
            val p = nominal + e
            val (d, name) = turbine.distance(p)
            if (d < minD) { minD = d; minAt = p; minName = name }
            samples += SimSample(t, p, yaw, pitch, next, d)
        }
        observe(pos, 0)

        for ((i, wp) in waypoints.withIndex()) {
            val dist = (wp.point - pos).norm
            if (i > 0 && dist > 1e-6) {
                val speed = max(wp.velocity, 0.1)
                val duration = dist / speed
                val steps = max(1, ceil(duration / config.dt).toInt())
                val yawTarget = wp.droneYaw
                val pitchTarget = wp.actions.firstOrNull { it.rotateGimbal }?.pitch ?: pitch
                val yawDelta = Angle.normalize(yawTarget - yaw, -180.0, 180.0)
                val pitchDelta = pitchTarget - pitch
                if (abs(yawDelta) / duration > config.maxYawRateDegPerSec)
                    violations += "wp $i: yaw ${"%.0f".format(yawDelta)}° en ${"%.1f".format(duration)} s supera ${config.maxYawRateDegPerSec}°/s"
                if (abs(pitchDelta) / duration > config.maxGimbalRateDegPerSec)
                    violations += "wp $i: gimbal ${"%.0f".format(pitchDelta)}° en ${"%.1f".format(duration)} s supera ${config.maxGimbalRateDegPerSec}°/s"
                val from = pos; val yaw0 = yaw; val pitch0 = pitch
                for (s in 1..steps) {
                    val f = s.toDouble() / steps
                    t += duration / steps
                    yaw = Angle.normalize(yaw0 + yawDelta * f, -180.0, 180.0)
                    pitch = pitch0 + pitchDelta * f
                    val nominal = from + (wp.point - from) * f
                    advance(duration / steps, nominal.z)
                    observe(nominal, i)
                }
                length += dist
                pos = wp.point
            }
            yaw = wp.droneYaw
            if (wp.pauseTiming != PauseTiming.UNDEFINED && wp.pauseDrone > 0.0) {
                val n = max(1, ceil(wp.pauseDrone / config.dt).toInt())
                repeat(n) { t += wp.pauseDrone / n; advance(wp.pauseDrone / n, wp.point.z); observe(wp.point, i) }
            }
            for (a in wp.actions) {
                if (a.rotateGimbal) pitch = a.pitch
                if (a.rotateDroneYaw) yaw = a.yaw
                if (a.takePhoto) {
                    photos += PhotoPose(i, wp.point + e, if (a.rotateDroneYaw) a.yaw else yaw, a.pitch)
                    if (a.pitch > gimbalLimits.maxPitchUp + 1e-6 || a.pitch < -gimbalLimits.maxPitchDown - 1e-6)
                        violations += "wp $i: pitch de foto ${"%.1f".format(a.pitch)}° fuera de [-${gimbalLimits.maxPitchDown}, ${gimbalLimits.maxPitchUp}]"
                }
            }
        }

        if (wind != null && wind.speedAt(waypoints.maxOf { it.point.z }) >= config.windResistanceMps)
            violations += "viento a la altura máxima %.1f m/s ≥ resistencia del dron (%.0f m/s)".format(wind.speedAt(waypoints.maxOf { it.point.z }), config.windResistanceMps)
        if (minD < config.minSafeDistance)
            violations += "distancia mínima %.2f m < %.2f m (%s) en %s".format(minD, config.minSafeDistance, minName, minAt)

        val points = coveragePoints(photos)
        val perBlade = points.groupBy { it.blade }.mapValues { (_, l) -> l.count { it.covered }.toDouble() / l.size }
        return SimReport(samples, photos, minD, minAt, minName, t, length, maxErr, if (nErr > 0) Math.sqrt(sumSq / nErr) else 0.0, perBlade, points, violations)
    }

    // ── cobertura ──
    private fun coveragePoints(photos: List<PhotoPose>): List<CoveragePoint> {
        val dims = turbine.dims
        val root = turbine.bladeRoot
        val out = ArrayList<CoveragePoint>()
        for (b in listOf(BladePosition.TWELVE, BladePosition.FOUR, BladePosition.EIGHT)) {
            val axis = turbine.bladeAxisDirection(b)
            val r0 = dims.hubCenterToBladeFlange
            val r1 = dims.bladeLength - config.tipClearance
            val n = max(1, ((r1 - r0) / config.coverageStep).toInt())
            for (k in 0..n) {
                val q = root + axis * (r0 + (r1 - r0) * k / n)
                out += CoveragePoint(b, q, photos.any { sees(it, q) })
            }
        }
        return out
    }

    /** ¿Cae [q] dentro del encuadre de la foto? Cámara sin roll: horizonte plano, FOV horizontal/vertical de [cam]. */
    private fun sees(p: PhotoPose, q: Vec3): Boolean {
        val yaw = Math.toRadians(p.yawDeg); val pitch = Math.toRadians(p.pitchDeg)
        val fwd = Vec3(cos(pitch) * sin(yaw), cos(pitch) * cos(yaw), sin(pitch))
        val right = Vec3(cos(yaw), -sin(yaw), 0.0)
        val up = right cross fwd
        val v = q - p.pos
        val f = v dot fwd
        if (f <= 0.0 || v.norm > config.maxPhotoDistance) return false
        val h = Math.toDegrees(atan2(abs(v dot right), f))
        val vv = Math.toDegrees(atan2(abs(v dot up), f))
        return h <= cam.horizontal / 2.0 && vv <= cam.vertical / 2.0
    }
}
