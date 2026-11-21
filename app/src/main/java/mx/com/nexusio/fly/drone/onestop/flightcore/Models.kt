package mx.com.nexusio.fly.drone.onestop.flightcore

// ───────────────────────── Enumeraciones del dominio ─────────────────────────
enum class PathIndex { UNDEFINED, LE, PS, TE, SS }              // borde de ataque, cara presión, borde de salida, cara succión
enum class PathDirection { ROOT_TO_TIP, TIP_TO_ROOT }
enum class PathType { UNDEFINED, INSPECTION, PATH_TRANSFER, BLADE_TRANSFER }
enum class BladePosition { UNDEFINED, FOUR, EIGHT, TWELVE }     // posición horaria de la pala
enum class ComponentIndex { X, A, B, C, T, S, N }               // X=ninguno, A/B/C=palas, T=torre, S=spinner, N=nacelle
enum class YawMode { FOLLOW_WAYLINE, FIXED, MANUAL, TOWARD_POI, SMOOTH_TRANSITION, UNDEFINED }
enum class PauseTiming { UNDEFINED, BEFORE, AFTER }

class MissionPlanningException(message: String, val userMessage: String = message) : Exception(message) {
    companion object {
        fun require(ok: Boolean, message: String, userMessage: String = message) {
            if (!ok) throw MissionPlanningException(message, userMessage)
        }
    }
}

// ───────────────────────── Parámetros de entrada ─────────────────────────
/** Valores por defecto = InspectionDefinition.createDefault() del original. */
data class InspectionDefinition(
    val observationDistanceRoot: Double = 10.0,
    val observationDistanceTip: Double = 5.5,
    val observationAngleRoot: Double = 0.0,
    val observationAngleTip: Double = 0.0,
    val nacelleSafetyDistance: Double = 8.0,
    val towerSafetyAngle: Double = 55.0,
    val bladeSafetyAngle: Double = 25.0,
    val pathSafetyDistance: Double = 5.0,
    val flangeMargin: Double = 0.0,
    val tipMargin: Double = 4.0,
    val exposureSettingPause: Double = 2.0,
    val imageOverlap: Double = 0.25,
    val imageMargin: Double = 0.2,
    val inspectionVelocity: Double = 1.0,
    val transferVelocity: Double = 1.0,
)

data class CameraFov(val horizontal: Double, val vertical: Double, val diagonal: Double) {
    companion object {
        fun default(is35mm: Boolean = true) = if (is35mm) CameraFov(54.5, 37.9, 63.5) else CameraFov(39.6, 27.0, 46.8)
    }
}

data class GimbalLimits(val maxPitchUp: Double = 30.0, val maxPitchDown: Double = 60.0, val maxRoll: Double = 0.0, val maxYaw: Double = 360.0)

data class BladeTwist(val angleRoot: Double, val angleMiddle: Double, val angleTip: Double) {
    companion object { fun default() = BladeTwist(-30.0, 5.0, 10.0) }
}

/** Orden de campos idéntico al constructor del original. */
data class TurbineDimensions(
    val rotorDiameter: Double,
    val hubLength: Double,
    val bladeFlangeRadius: Double,
    val bladeMaxChordWidth: Double,
    val bladeTiltAngle: Double,
    val rotorAxisAngle: Double,
    val hubCenterToBladeFlange: Double,
    val hubCenterToMaxChordWidth: Double,
    val towerCenterToHubCenter: Double,
    val towerHeight: Double,
    val towerBottomRadius: Double,
    val towerTopRadius: Double,
    val nacelleThickness: Double,
) {
    val bladeLength: Double get() = rotorDiameter / 2.0

    companion object {
        /** Primera plantilla de createDefault() del original (útil para pruebas). */
        val SAMPLE = TurbineDimensions(112.0, 2.5, 1.255, 3.8, 4.5, 9.0, 2.0, 12.4, 3.0, 120.0, 3.0, 2.0, 2.5)
    }
}

/** Anchura de pala y radio en función de la distancia a la raíz. */
class BladeThickness(val dims: TurbineDimensions) {
    private val tipMinus1mWidth = 0.75
    private val radiusInterp = LinearInterpolator(0.0, dims.bladeFlangeRadius, dims.bladeLength, 0.0)
    private val leInterp = PiecewiseLinearInterpolator(listOf(
        0.0 to dims.bladeFlangeRadius,
        dims.bladeLength - 1.0 to tipMinus1mWidth / 2.0,
        dims.bladeLength to 0.0,
    ))
    private val teInterp = PiecewiseLinearInterpolator(listOf(
        0.0 to dims.bladeFlangeRadius,
        dims.hubCenterToMaxChordWidth to dims.bladeMaxChordWidth - radiusInterp.interpolate(dims.hubCenterToMaxChordWidth),
        dims.bladeLength - 1.0 to tipMinus1mWidth / 2.0,
        dims.bladeLength to 0.0,
    ))
    val bladeFlangeRadius get() = dims.bladeFlangeRadius

    fun thicknessAt(radius: Double, path: PathIndex): Double = when (path) {
        PathIndex.LE -> leInterp.interpolate(radius)
        PathIndex.TE -> teInterp.interpolate(radius)
        else -> radiusInterp.interpolate(radius)
    }
}

// ───────────────────────── Salida del planificador ─────────────────────────
data class WaypointAction(
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val focusDistance: Double = 0.0,
    val distanceToRoot: Double = 0.0,
    val takePhoto: Boolean = true,
    val rotateGimbal: Boolean = false,
    val rotateDroneYaw: Boolean = false,
)

data class Waypoint3D(
    val index: Int,
    val pathIndex: PathIndex,
    val pathType: PathType,
    val bladePosition: BladePosition,
    val componentIndex: ComponentIndex,
    val point: Vec3,
    val pointOfInterest: Vec3,
    val yawMode: YawMode,
    val droneYaw: Double,
    val actions: List<WaypointAction>,
    val pauseTiming: PauseTiming,
    val pauseDrone: Double,
    val velocity: Double,
    val stop: Boolean,
    val isFirstInspectionPathPoint: Boolean,
    val isLastInspectionPathPoint: Boolean,
    val isFirstBladePoint: Boolean,
    val isLastBladePoint: Boolean,
    val distanceToComponent: Double,
    val distanceToRoot: Double,
) {
    fun reIndex(i: Int) = copy(index = i)
}
