package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

/** Raíz de pala a partir de tres medidas, sin eje del rotor (versión de 4 argumentos del original). */
fun rootPointFromTipAndBottom(d: TurbineDimensions, tip12: Vec3, bottom12: Vec3, spinner: Vec3): Vec3 {
    val c = Line3(tip12, (bottom12 - tip12).normalize()).closestPointTo(spinner)
    return c + (c - spinner).normalize() * d.bladeFlangeRadius
}

/** Capa de app de FlightPathSafetyCheck: del GPS actual del dron y las mediciones guardadas al veredicto de transferencia. */
object AppTransferCheck {
    fun performTransferCheck(
        drone: DroneLocation, target: GeoPoint, dims: TurbineDimensions, def: InspectionDefinition,
        measurements: Map<TurbineMeasurementLocation, TurbineMeasurementPoint?>,
    ): TransferSafetyResult {
        val origin = DroneLocationHelper.worldOrigin(drone)          // el origen ES la posición actual del dron
        val pts = TurbineMeasurementHelper.measurementPoints(measurements, origin)
        fun l(k: TurbineMeasurementLocation) = GeoMath.toLocal(origin, pts.getValue(k))
        val sp = measurements.getValue(TurbineMeasurementLocation.SPINNER)!!.droneLocation
        val droneAtSpinner = GeoMath.toLocal(origin, GeoPoint(sp.latitude!!, sp.longitude!!, sp.altitude!!))
        val spinner = l(TurbineMeasurementLocation.SPINNER)
        val root = rootPointFromTipAndBottom(dims, l(TurbineMeasurementLocation.TIP_12), l(TurbineMeasurementLocation.BOTTOM_12), spinner)
        val check = FlightPathSafetyCheck(
            droneAtSpinner, bladeLeftTip = l(TurbineMeasurementLocation.TIP_8), bladeRightTip = l(TurbineMeasurementLocation.TIP_4),
            bladeTopTip = l(TurbineMeasurementLocation.TIP_12),
            bladeTopBottom = root, // el original pasa aquí la raíz calculada, no la medición BOTTOM_12
            spinner = spinner, dims = dims, def = def,
        )
        return check.checkTransfer(Vec3.ZERO, GeoMath.toLocal(origin, target))
    }
}

/** Decide si una medición guardada de la turbina se puede reutilizar en otro vuelo. */
object ReuseVerification {
    const val DIFFERENT_TURBINE_DISTANCE_M = 150.0
    const val DRONE_POSITION_TRIPWIRE_M = 10.0
    const val MAX_AGE_MILLIS = 28_800_000L          // 8 h
    const val SPINNER_MATCH_TOLERANCE_M = 2.0

    sealed interface SpinnerComparison {
        data object Invalid : SpinnerComparison
        data object DifferentTurbine : SpinnerComparison
        data class Match(val diff: Double, val horizontal: Double, val vertical: Double) : SpinnerComparison
        data class Mismatch(val diff: Double, val horizontal: Double, val vertical: Double) : SpinnerComparison
        data class WrongPosition(val dronePositionDelta: Double) : SpinnerComparison
    }

    // Misma aproximación que GeoLocation.geoDistanceApproximation del original (coseno de la latitud del PRIMER punto).
    private fun approx(a: GeoPoint, b: GeoPoint, withAlt: Boolean): Double {
        val dx = abs(cos(Math.toRadians(a.lat))) * GeoMath.EARTH_RADIUS * (Math.toRadians(b.lon) - Math.toRadians(a.lon))
        val dy = (Math.toRadians(b.lat) - Math.toRadians(a.lat)) * GeoMath.EARTH_RADIUS
        val d2 = sqrt(dx * dx + dy * dy)
        return if (withAlt) sqrt(d2 * d2 + (a.alt - b.alt).pow(2)) else d2
    }

    private fun geoOrNull(d: DroneLocation) =
        if (d.latitude != null && d.longitude != null && d.altitude != null) runCatching { GeoPoint(d.latitude, d.longitude, d.altitude) }.getOrNull() else null

    fun compareSpinner(stored: TurbineMeasurementPoint, fresh: TurbineMeasurementPoint): SpinnerComparison {
        val so = geoOrNull(stored.droneLocation) ?: return SpinnerComparison.Invalid
        val fo = geoOrNull(fresh.droneLocation) ?: return SpinnerComparison.Invalid
        return try {
            val a = stored.toGeo(so); val b = fresh.toGeo(so)       // ambas respecto al origen guardado
            val horizontal = approx(a, b, false)
            val vertical = abs(a.alt - b.alt)
            val total = approx(a, b, true)
            if (total > DIFFERENT_TURBINE_DISTANCE_M) return SpinnerComparison.DifferentTurbine
            val droneDelta = approx(so, fo, true)
            when {
                total > SPINNER_MATCH_TOLERANCE_M -> SpinnerComparison.Mismatch(total, horizontal, vertical)
                droneDelta > DRONE_POSITION_TRIPWIRE_M -> SpinnerComparison.WrongPosition(droneDelta)
                else -> SpinnerComparison.Match(total, horizontal, vertical)
            }
        } catch (e: Exception) { SpinnerComparison.Invalid }
    }

    /** Puertas estáticas: la medición debe tener sello de tiempo, id de inspección y tiempo de vuelo; y no ser futura ni vieja (>8 h, salvo modo experto). */
    fun passesStaticGates(timestampMillis: Long?, inspectionId: String?, flightTimeDeciSeconds: Int?, expertMode: Boolean, nowMillis: Long): Boolean {
        if (timestampMillis == null || inspectionId == null || flightTimeDeciSeconds == null) return false
        val age = nowMillis - timestampMillis
        if (age < 0) return false
        return age <= MAX_AGE_MILLIS || expertMode
    }
}

/** Aviso (NO bloqueante) si el dron está a >100 m de la turbina registrada en la inspección. */
object TurbineProximity {
    const val WARNING_DISTANCE_M = 100.0
    fun warningMeters(droneLat: Double, droneLon: Double, turbineLat: Double, turbineLon: Double): Int? {
        val d = GeoMath.distance(GeoPoint(droneLat, droneLon, 0.0), GeoPoint(turbineLat, turbineLon, 0.0))
        return if (d > WARNING_DISTANCE_M) d.roundToInt() else null
    }
}
