package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

// ───────────────────────── Zero Trust: validación de entradas ─────────────────────────
// Ninguna cifra se asume válida, venga de la UI, de un archivo o del SDK.
internal object Guard {
    fun finite(name: String, v: Double): Double { require(v.isFinite()) { "$name must be finite" }; return v }
    fun inRange(name: String, v: Double, lo: Double, hi: Double): Double {
        require(finite(name, v) in lo..hi) { "$name=$v outside [$lo, $hi]" }; return v
    }
    fun positive(name: String, v: Double): Double { require(finite(name, v) > 0.0) { "$name must be > 0" }; return v }
}

/** Ángulo inmutable; evita mezclar grados y radianes (el original usa Doubles sueltos). */
@JvmInline
value class Angle private constructor(val radians: Double) {
    val degrees: Double get() = Math.toDegrees(radians)
    operator fun plus(o: Angle) = Angle(radians + o.radians)
    operator fun minus(o: Angle) = Angle(radians - o.radians)
    operator fun div(s: Double) = Angle(radians / s)

    companion object {
        fun fromDegrees(d: Double) = Angle(Math.toRadians(d))
        fun fromRadians(r: Double) = Angle(r)
        const val HALF_PI = PI / 2
        /** Envuelve [value] al intervalo [min, max). */
        fun normalize(value: Double, min: Double, max: Double): Double {
            val span = max - min
            return ((value - min) % span + span) % span + min
        }
    }
}

data class GeoPoint(val lat: Double, val lon: Double, val alt: Double) {
    init {
        Guard.inRange("lat", lat, -85.0, 85.0)
        Guard.inRange("lon", lon, -180.0, 180.0)
        Guard.finite("alt", alt)
    }
}

// ───────────────────────── Geodesia local ─────────────────────────
/**
 * Proyección equirectangular local con radio fijo 6 372 800 m (el original no usa WGS-84).
 * Adecuada para un parque eólico (<~1 km); NO para distancias largas.
 */
object GeoMath {
    const val EARTH_RADIUS = 6_372_800.0

    fun toLocal(origin: GeoPoint, p: GeoPoint): Vec3 {
        val k = abs(cos(Math.toRadians(origin.lat))) * EARTH_RADIUS
        val dLon = Angle.normalize(p.lon - origin.lon, -180.0, 180.0)
        return Vec3(k * Math.toRadians(dLon), Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS, p.alt - origin.alt)
    }

    fun toGeo(origin: GeoPoint, v: Vec3): GeoPoint {
        val k = abs(cos(Math.toRadians(origin.lat))) * EARTH_RADIUS
        return GeoPoint(
            origin.lat + Math.toDegrees(v.y / EARTH_RADIUS),
            Angle.normalize(origin.lon + Math.toDegrees(v.x / k), -180.0, 180.0),
            origin.alt + v.z,
        )
    }

    /** Unifica las dos variantes del original (lat. de origen vs. lat. media): aquí siempre la media. */
    fun distance(a: GeoPoint, b: GeoPoint, withAltitude: Boolean = false): Double {
        val midLat = Math.toRadians((a.lat + b.lat) / 2)
        val d2 = hypot(
            Math.toRadians(b.lon - a.lon) * cos(midLat) * EARTH_RADIUS,
            Math.toRadians(b.lat - a.lat) * EARTH_RADIUS,
        )
        return if (withAltitude) hypot(d2, b.alt - a.alt) else d2
    }

    /** Velocidad N/E → ejes del dron. [headingRad]: rumbo, 0 = Norte. */
    fun forwardVelocity(headingRad: Double, vN: Double, vE: Double) =
        cos(headingRad) * vN + cos(Angle.HALF_PI - headingRad) * vE

    fun sidewardsVelocity(headingRad: Double, vN: Double, vE: Double) =
        cos(Angle.HALF_PI + headingRad) * vN + cos(headingRad) * vE
}

// ───────────────────────── Óptica / geometría de inspección ─────────────────────────
object InspectionOptics {
    private val ROT_30 = Angle.fromDegrees(30.0) // inclinación de la pala respecto al encuadre

    /** Huella efectiva tras solape: (1-o)·d·2·tan(aov/2). */
    fun effectiveFov(aovDeg: Double, focusDistance: Double, overlap: Double): Double {
        Guard.positive("focusDistance", focusDistance)
        return (1 - overlap.coerceIn(0.0, 1.0)) * focusDistance * tan(Angle.fromDegrees(aovDeg / 2).radians) * 2
    }

    /** Variante para bordes de ataque/salida: cámara rotada 30° y factor de compresión en [0.75, 1]. */
    fun effectiveFovRotated(aovH: Double, aovV: Double, focus: Double, overlap: Double, squeeze: Double): Double {
        Guard.positive("focus", focus)
        val k = 1 - overlap.coerceIn(0.0, 1.0)
        val w = tan(Angle.fromDegrees(aovH / 2).radians) * focus * 2
        val h = tan(Angle.fromDegrees(aovV / 2).radians) * focus * 2
        return min(w * k / cos(ROT_30.radians), h * k / sin(ROT_30.radians)) * squeeze.coerceIn(0.75, 1.0)
    }

    /** Distancia mínima para que un objeto de [targetSize] m quepa dejando [margin] relativo por lado. */
    fun minObservationDistance(fovDeg: Double, margin: Double, targetSize: Double): Double {
        Guard.inRange("fov", fovDeg, 0.1, 99.9)
        Guard.inRange("margin", margin, 0.01, 0.99)
        Guard.positive("targetSize", targetSize)
        return (targetSize / (1 - 2 * margin)) / (2 * tan(Angle.fromDegrees(fovDeg / 2).radians))
    }

    /** Dirección de visión (Este, Norte, Arriba). */
    fun viewDirection(pitchRad: Double, yawRad: Double) =
        Vec3(cos(pitchRad) * sin(yawRad), cos(pitchRad) * cos(yawRad), sin(pitchRad))

    /** Distancia de seguridad al encastre de la pala (cono de 30°). */
    fun safetyDistanceFromRoot(observationDistance: Double, bladeRadius: Double, flangeRadius: Double, pathSafety: Double): Double {
        val t = tan(ROT_30.radians)
        val d0 = bladeRadius / sin(ROT_30.radians)
        val d = if (observationDistance > d0) max(pathSafety - (observationDistance - d0) * t, 0.0)
                else (d0 - observationDistance) * t + pathSafety
        return max(d, flangeRadius)
    }

    /** Separaciones entre waypoints con FOV efectivo variable raíz→punta, reescaladas a [pathLength]. */
    fun waypointIntervals(pathLength: Double, fovStart: Double, fovEnd: Double): List<Double> {
        Guard.positive("pathLength", pathLength); Guard.positive("fovStart", fovStart); Guard.positive("fovEnd", fovEnd)
        val steps = ArrayList<Double>()
        val interp = LinearInterpolator(0.0, fovStart, pathLength, fovEnd) // satura fuera de [0, pathLength]
        var pos = 0.0
        while (pos < pathLength + fovEnd) {
            val step = interp.interpolate(pos)
            steps += step; pos += step
        }
        val scale = pathLength / pos
        return steps.map { it * scale }
    }

    /** Nº de poses de gimbal (pitch) / giro (yaw) para barrer start→end con solape en [0.1, 0.3]. */
    /** Pitch: el rango de solape es EXCLUSIVO (0.1, 0.3), como en el original (`overlap <= 0.1 || overlap >= 0.3` lanza). */
    fun stepsPitch(startDeg: Double, endDeg: Double, dist: Double, vFov: Double, overlap: Double): Int {
        require(overlap > 0.1 && overlap < 0.3) { "overlap must be in range [0.1, 0.3]" }
        return steps(startDeg, endDeg, dist, vFov, overlap)
    }

    /** Yaw: el rango de solape es INCLUSIVO [0.1, 0.3] (`0.1 > overlap || overlap > 0.3` lanza). */
    fun stepsYaw(startDeg: Double, endDeg: Double, dist: Double, hFov: Double, overlap: Double): Int {
        require(overlap in 0.1..0.3) { "overlap must be in range [0.1, 0.3]" }
        return steps(startDeg, endDeg, dist, hFov, overlap)
    }

    // El cuerpo del cálculo es idéntico para yaw y pitch en el original; solo difiere la validación de arriba.
    private fun steps(s: Double, e: Double, dist: Double, fov: Double, overlap: Double): Int {
        Guard.positive("dist", dist)
        if (s == e) return 1
        val half = Angle.fromDegrees(fov / 2)
        fun top(a: Double) = tan((Angle.fromDegrees(a) + half).radians) * dist
        fun bot(a: Double) = tan((Angle.fromDegrees(a) - half).radians) * dist
        val frame = abs(top(s) - bot(s)) * (1 - overlap)
        val span = if (e > s) bot(e) - top(s) else bot(s) - top(e)
        return if (span <= 0) 2 else ceil(span / frame).toInt() + 2
    }

    /** (yaw°, pitch°) hacia la raíz desde el primer punto del borde de salida. */
    fun yawPitchToRoot(distToBlade: Double, distToRoot: Double, twelveOClock: Boolean, rightSide: Boolean): Pair<Double, Double> {
        if (twelveOClock) return 0.0 to -Math.toDegrees(atan(distToRoot / distToBlade))
        val s = sin(ROT_30.radians) * distToRoot
        val q = sqrt(distToRoot * distToRoot + distToBlade * distToBlade - s * s)
        val yaw = Math.toDegrees(acos((distToBlade / q).coerceIn(-1.0, 1.0))) * if (rightSide) 1 else -1
        return yaw to Math.toDegrees(atan(s / q))
    }
}
