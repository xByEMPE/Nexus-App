package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

// ───────────────────────── Salida georreferenciada de la misión ─────────────────────────
/** ¿Tiene POI real? El original lo decide con `pointOfInterest != (0,0,0)` con tolerancia 1e-3 por componente. */
fun Waypoint3D.hasPointOfInterest(): Boolean =
    !(abs(pointOfInterest.x) < 0.001 && abs(pointOfInterest.y) < 0.001 && abs(pointOfInterest.z) < 0.001)

data class WaypointGeo(
    val index: Int,
    val pathIndex: PathIndex,
    val pathType: PathType,
    val bladePosition: BladePosition,
    val componentIndex: ComponentIndex,
    val point: GeoPoint,
    val pointOfInterest: GeoPoint?,
    val yawMode: YawMode,
    val droneYaw: Double,
    val waypointActions: List<WaypointAction>,
    val pauseTiming: PauseTiming,
    val pauseSeconds: Double,
    val velocity: Double,
    val stop: Boolean,
    val isFirstInspectionPathPoint: Boolean,
    val isLastInspectionPathPoint: Boolean,
    val isFirstBladePoint: Boolean,
    val isLastBladePoint: Boolean,
) {
    fun hasWaypointActions() = waypointActions.isNotEmpty()

    companion object {
        fun from(worldOrigin: GeoPoint, w: Waypoint3D) = WaypointGeo(
            w.index, w.pathIndex, w.pathType, w.bladePosition, w.componentIndex,
            GeoMath.toGeo(worldOrigin, w.point),
            if (w.hasPointOfInterest()) GeoMath.toGeo(worldOrigin, w.pointOfInterest) else null,
            w.yawMode, w.droneYaw, w.actions, w.pauseTiming, w.pauseDrone, w.velocity, w.stop,
            w.isFirstInspectionPathPoint, w.isLastInspectionPathPoint, w.isFirstBladePoint, w.isLastBladePoint,
        )
    }
}

data class WaypointInfo(
    val index: Int,
    val pathStartIndex: Int,
    val pathIndex: PathIndex,
    val bladePosition: BladePosition,
    val componentIndex: ComponentIndex,
    val point: GeoPoint,
    val dronePausing: Boolean,
    val waypointActions: List<WaypointAction>,
)

/** Índice de la misión: para cada waypoint, dónde empieza su camino y cuál es el último de su pala. */
class WaypointInfoRepository {
    private val infos = ArrayList<WaypointInfo>()

    fun build(wps: List<WaypointGeo>) {
        if (wps.isEmpty()) return
        infos.clear()
        var pathStart = -1
        wps.forEachIndexed { i, w ->
            if (w.isFirstInspectionPathPoint) pathStart = i
            infos += WaypointInfo(i, pathStart, w.pathIndex, w.bladePosition, w.componentIndex, w.point, w.pauseSeconds > 0.0, w.waypointActions)
        }
    }
    fun current(index: Int) = infos.getOrNull(index)
    fun pathStart(index: Int): WaypointInfo? = infos.getOrNull(index)?.let { infos[it.pathStartIndex] }
    fun lastIndexOfBlade(index: Int): Int? {
        val c = infos[index].componentIndex
        if (c == ComponentIndex.X) return null
        return infos.indexOfLast { it.componentIndex == c }.takeIf { it >= 0 }
    }
    val size get() = infos.size
    fun all(): List<WaypointInfo> = infos.toList()
}

// ───────────────────────── Origen del mundo ─────────────────────────
data class DroneLocation(val latitude: Double?, val longitude: Double?, val altitude: Double?)

object DroneLocationHelper {
    private const val EPS = 1e-6

    /** El origen es la primera posición válida del dron. (0,0) se rechaza: es el "sin fix" típico del GNSS. */
    fun worldOrigin(l: DroneLocation): GeoPoint {
        val lat = l.latitude; val lon = l.longitude; val alt = l.altitude
        require(lat != null && abs(lat) > EPS && lon != null && abs(lon) > EPS && alt != null &&
                lat > -90.0 && lat < 90.0 && lon > -180.0 && lon < 180.0) { "invalid drone location: $l" }
        return GeoPoint(lat, lon, alt)
    }
}

// ───────────────────────── Mediciones del piloto (láser/rumbo/pitch) ─────────────────────────
enum class TurbineMeasurementLocation { TIP_4, TIP_12, TIP_8, BOTTOM_12, SPINNER }

/** Un punto medido: el dron apunta con [headingDeg]/[pitchDeg] y el telémetro devuelve [distance]. */
data class TurbineMeasurementPoint(val droneLocation: DroneLocation, val headingDeg: Double, val pitchDeg: Double, val distance: Double) {
    fun toGeo(worldOrigin: GeoPoint): GeoPoint {
        val dl = droneLocation
        check(dl.latitude != null && dl.longitude != null && dl.altitude != null) { "incomplete drone location" }
        val dronePos = GeoMath.toLocal(worldOrigin, GeoPoint(dl.latitude, dl.longitude, dl.altitude))
        val heading = Angle.normalize(headingDeg, -180.0, 180.0)
        // Y (norte) → elevar por pitch alrededor de X → girar por rumbo alrededor de -Z (sentido horario visto desde arriba)
        val dir = Geo3D.rotate(Geo3D.rotate(Vec3.Y, Vec3.X, Angle.fromDegrees(pitchDeg)), -Vec3.Z, Angle.fromDegrees(heading))
        return GeoMath.toGeo(worldOrigin, dronePos + dir * distance)
    }
}

object TurbineMeasurementHelper {
    fun measurementPoints(m: Map<TurbineMeasurementLocation, TurbineMeasurementPoint?>, origin: GeoPoint): Map<TurbineMeasurementLocation, GeoPoint> {
        fun get(l: TurbineMeasurementLocation, msg: String) = (m[l] ?: error(msg)).toGeo(origin)
        return mapOf(
            TurbineMeasurementLocation.TIP_8 to get(TurbineMeasurementLocation.TIP_8, "Left tip measurement is missing"),
            TurbineMeasurementLocation.TIP_4 to get(TurbineMeasurementLocation.TIP_4, "Right tip measurement is missing"),
            TurbineMeasurementLocation.TIP_12 to get(TurbineMeasurementLocation.TIP_12, "Top tip measurement is missing"),
            TurbineMeasurementLocation.BOTTOM_12 to get(TurbineMeasurementLocation.BOTTOM_12, "Top blade root measurement is missing"),
            TurbineMeasurementLocation.SPINNER to get(TurbineMeasurementLocation.SPINNER, "Hub measurement is missing"),
        )
    }
}

// ───────────────────────── Verificación del punto apuntado antes de medir ─────────────────────────
enum class TargetViolation { TOO_CLOSE_TO_ROOT, TOO_CLOSE_TO_TIP, NOT_ON_TOP_BLADE_AXIS, AIM_TOO_LOW, AIM_TOO_HIGH, WRONG_SIDE, DRONE_TOO_LOW, DRONE_TOO_HIGH }

object TargetGeometry {
    const val AIM_MARGIN_DEGREES = 10.0
    const val BOTTOM_12_MAX_AXIS_DISTANCE_M = 4.0
    const val BOTTOM_12_MAX_RADIUS_M = 10.0
    const val BOTTOM_12_MIN_RADIUS_M = 3.0
    const val MAX_TIP_RADIUS_FRACTION = 0.95
    const val MIN_TIP_RADIUS_FRACTION = 0.85
    const val SIDE_BEARING_TOLERANCE_DEGREES = 45.0
    const val SPINNER_ALTITUDE_TOLERANCE_M = 20.0

    fun radiusMeters(from: GeoPoint, to: GeoPoint) = hypot(horizontalDistance(from, to), to.alt - from.alt)
    private fun horizontalDistance(a: GeoPoint, b: GeoPoint): Double {
        val l = GeoMath.toLocal(a, b); return hypot(l.x, l.y)
    }
    fun elevationDegrees(ref: GeoPoint, p: GeoPoint): Double {
        val l = GeoMath.toLocal(ref, p); return Math.toDegrees(atan2(l.z, hypot(l.x, l.y)))
    }
    fun bearingDegrees(ref: GeoPoint, p: GeoPoint): Double {
        val l = GeoMath.toLocal(ref, p); return Geo3D.yawOf(Vec3(l.x, l.y, 0.0))
    }
    /** Distancia perpendicular de [p] al eje spinner→[axisEnd]. */
    fun axisDistanceMeters(ref: GeoPoint, p: GeoPoint, axisEnd: GeoPoint): Double {
        val v = GeoMath.toLocal(ref, p); val a = GeoMath.toLocal(ref, axisEnd).let { Vec3(it.x, it.y, it.z) }.normalize()
        return (v - a * (v dot a)).norm
    }

    /** Banda de elevación aceptable al apuntar a cada punta: laterales −45…−15°, la de arriba (90−5−10)…90°. */
    fun elevationBand(loc: TurbineMeasurementLocation): Pair<Double, Double> = when (loc) {
        TurbineMeasurementLocation.TIP_8, TurbineMeasurementLocation.TIP_4 -> -45.0 to -15.0
        TurbineMeasurementLocation.TIP_12 -> (90.0 - max(5.0, 5.0) - AIM_MARGIN_DEGREES) to 90.0
        else -> throw IllegalArgumentException("no elevation band for $loc")
    }

    fun checkTip(loc: TurbineMeasurementLocation, aimed: GeoPoint, spinner: GeoPoint, spinnerHeading: Double, bladeLength: Double): List<TargetViolation> {
        val v = ArrayList<TargetViolation>()
        val r = radiusMeters(spinner, aimed); val frac = r / bladeLength
        if (frac < MIN_TIP_RADIUS_FRACTION) v += TargetViolation.TOO_CLOSE_TO_ROOT
        if (frac > MAX_TIP_RADIUS_FRACTION) v += TargetViolation.TOO_CLOSE_TO_TIP
        val el = elevationDegrees(spinner, aimed); val (lo, hi) = elevationBand(loc)
        if (el < lo) v += TargetViolation.AIM_TOO_LOW
        if (el > hi) v += TargetViolation.AIM_TOO_HIGH
        if (loc != TurbineMeasurementLocation.TIP_12) {
            val side = spinnerHeading + (if (loc == TurbineMeasurementLocation.TIP_8) -1.0 else 1.0) * 90.0
            if (abs(Angle.normalize(bearingDegrees(spinner, aimed) - side, -180.0, 180.0)) > SIDE_BEARING_TOLERANCE_DEGREES) v += TargetViolation.WRONG_SIDE
        }
        return v
    }

    fun checkBottom12(aimed: GeoPoint, spinner: GeoPoint, tip12: GeoPoint): List<TargetViolation> {
        val v = ArrayList<TargetViolation>()
        val r = radiusMeters(spinner, aimed)
        if (r < BOTTOM_12_MIN_RADIUS_M) v += TargetViolation.TOO_CLOSE_TO_ROOT
        if (r > BOTTOM_12_MAX_RADIUS_M) v += TargetViolation.TOO_CLOSE_TO_TIP
        if (axisDistanceMeters(spinner, aimed, tip12) > BOTTOM_12_MAX_AXIS_DISTANCE_M) v += TargetViolation.NOT_ON_TOP_BLADE_AXIS
        return v
    }

    fun checkSpinner(droneAltitude: Double, towerHeight: Double): List<TargetViolation> = when {
        droneAltitude < towerHeight - SPINNER_ALTITUDE_TOLERANCE_M -> listOf(TargetViolation.DRONE_TOO_LOW)
        droneAltitude > towerHeight + SPINNER_ALTITUDE_TOLERANCE_M -> listOf(TargetViolation.DRONE_TOO_HIGH)
        else -> emptyList()
    }

    fun aimedPoint(dl: DroneLocation, droneYaw: Double, dronePitch: Double, distance: Double, distanceAngle: Angle, origin: GeoPoint): GeoPoint =
        TurbineMeasurementPoint(dl, Angle.normalize(droneYaw + distanceAngle.degrees, -180.0, 180.0), dronePitch, distance).toGeo(origin)
}

// ───────────────────────── Ajustes aplicados por la app antes de planificar ─────────────────────────
object PlanningDefaults {
    /** RTK sin configurar (null) se trata como DESACTIVADO → holguras ampliadas (+5.5/+3 m). */
    fun usingRtk(setting: Boolean?) = setting ?: false

    /**
     * Normaliza la torsión de pala introducida por el usuario:
     *  1) convención "autopilot" (angleRoot > 0) → se invierten todos los signos;
     *  2) |angleRoot| < 30 → se fuerza −30;
     *  3) la torsión de punta se anula siempre.
     */
    fun normalizeBladeTwist(t: BladeTwist): BladeTwist {
        var r = if (t.angleRoot > 0.0) BladeTwist(-t.angleRoot, -t.angleMiddle, -t.angleTip) else t
        val d = BladeTwist.default()
        if (abs(r.angleRoot) < abs(d.angleRoot)) r = r.copy(angleRoot = d.angleRoot)
        if (r.angleTip != 0.0) r = r.copy(angleTip = 0.0)
        return r
    }

    /** Con adaptador de gimbal el límite superior de pitch aumenta en el ángulo del adaptador. */
    fun adaptGimbal(l: GimbalLimits, canUseAdapter: Boolean, adapterAngleDeg: Double) =
        if (canUseAdapter) l.copy(maxPitchUp = l.maxPitchUp + adapterAngleDeg) else l
}

// ───────────────────────── Tubería completa: mediciones GPS → waypoints GPS ─────────────────────────
object MissionPipeline {
    data class Result(val worldOrigin: GeoPoint, val turbine: WindTurbine, val waypoints: List<WaypointGeo>, val rotorIndexAngleDeg: Double,
                  /** Los mismos waypoints en el sistema local del plan (ENU con origen en [worldOrigin]). */
                  val localWaypoints: List<Waypoint3D>)

    fun plan(
        firstDroneLocation: DroneLocation,
        measurements: Map<TurbineMeasurementLocation, TurbineMeasurementPoint?>,
        dimensions: TurbineDimensions, definition: InspectionDefinition, bladeTwistInput: BladeTwist,
        cam: CameraFov, gimbal: GimbalLimits, rtkSetting: Boolean?, stopAtEach: Boolean,
        blade4: ComponentIndex, blade12: ComponentIndex, blade8: ComponentIndex,
    ): Result {
        val origin = DroneLocationHelper.worldOrigin(firstDroneLocation)
        val components = mapOf(BladePosition.TWELVE to blade12, BladePosition.FOUR to blade4, BladePosition.EIGHT to blade8)
        val turbine = run {
            val pts = TurbineMeasurementHelper.measurementPoints(measurements, origin)
            val spinnerPt = measurements.getValue(TurbineMeasurementLocation.SPINNER)!!.droneLocation
            val droneAtSpinner = GeoPoint(spinnerPt.latitude!!, spinnerPt.longitude!!, spinnerPt.altitude!!)
            fun loc(l: TurbineMeasurementLocation) = GeoMath.toLocal(origin, pts.getValue(l))
            WindTurbine(
                dimensions,
                TurbineMeasurements(GeoMath.toLocal(origin, droneAtSpinner), loc(TurbineMeasurementLocation.TIP_4), loc(TurbineMeasurementLocation.TIP_12),
                    loc(TurbineMeasurementLocation.TIP_8), loc(TurbineMeasurementLocation.BOTTOM_12), loc(TurbineMeasurementLocation.SPINNER)),
                components,
            )
        }
        val planner = InspectionPlanner(PlanningDefaults.normalizeBladeTwist(bladeTwistInput), turbine, definition, cam, gimbal,
            PlanningDefaults.usingRtk(rtkSetting), stopAtEach)
        val local = planner.plan(blade4, blade12, blade8)
        val wps = local.map { WaypointGeo.from(origin, it) }
        return Result(origin, turbine, wps, round(turbine.rotorIndexAngle.degrees * 10) / 10, local)
    }
}
