package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import kotlin.math.abs
import kotlin.math.max

/** Sensor con el que se miden los puntos de la turbina. */
enum class MeasurementSensor { LASER, VISION }

/**
 * Qué sensor de distancia usa cada equipo y con qué alcance (según la configuración por modelo del original).
 *
 * - Láser: drones/cámaras con telémetro (Matrice 4E/4T, M30/M30T, Zenmuse H30/H30T).
 * - Visión: el resto (Mavic 3E/3T, Zenmuse P1), con los sensores de obstáculos frontales, de frente y a la altura del punto.
 *
 * Con los dos se mide desde [preciseMaxM] o menos (6 m; 7 m en el Matrice 400), como el original, que aplica ese
 * alcance también con láser. Cerca, un error de 1° en rumbo o inclinación desplaza el punto ~0.1 m (a 20 m, ~0.35 m)
 * y cualquier lectura de fondo (otra turbina, la torre detrás del buje, el terreno) queda fuera de rango.
 */
data class SensorProfile(
    val sensor: MeasurementSensor,
    val preciseMaxM: Double = 6.0,
) {
    /** Lectura más larga que acepta la medición (el original tolera 0.2 m sobre el alcance de precisión). */
    val maxMeasurementDistanceM get() = preciseMaxM + 0.2

    companion object {
        /** Distancia mínima recomendada al medir (el original avisa "aléjese" por debajo; no rechaza). */
        const val MIN_RECOMMENDED_DISTANCE_M = 4.0

        private val LASER_DRONES = setOf("Matrice_4E", "Matrice_4T", "Matrice_M30", "Matrice_M30T")
        private val LASER_PAYLOADS = setOf("Zenmuse_H30", "Zenmuse_H30T")

        /** [droneKey] y [payload] son las claves del catálogo (`values.json`). */
        fun forEquipment(droneKey: String, payload: String?): SensorProfile {
            val laser = droneKey in LASER_DRONES || payload in LASER_PAYLOADS
            val preciseMax = if (droneKey == "Matrice_400") 7.0 else 6.0
            return SensorProfile(if (laser) MeasurementSensor.LASER else MeasurementSensor.VISION, preciseMaxM = preciseMax)
        }
    }
}

/** Un sector horizontal de los sensores de visión: distancia (m) y ángulo central respecto al morro (grados, horario). */
data class VisionSector(val distanceM: Double, val centerDeg: Double, val widthDeg: Double)

object VisionSectors {
    /**
     * Sectores a partir de `ObstacleData.horizontalObstacleDistance` (mm, índice 0 = morro, sentido horario),
     * como `DistanceMeasurementFactory.createMeasurementBetween(distancias, 0, 360)` del original:
     * el sector i se centra en (i + 0.5)·360/n.
     */
    fun fromMillimeters(distancesMm: List<Int>): List<VisionSector> {
        if (distancesMm.isEmpty()) return emptyList()
        val width = 360.0 / distancesMm.size
        return distancesMm.mapIndexed { i, mm ->
            VisionSector(mm / 1000.0, Angle.normalize((i + 0.5) * width, -180.0, 180.0), width)
        }
    }
}

/**
 * Obstáculo más cercano dentro de una ventana frontal (`ObstacleDetector` del original: ±35° y 0.7–25 m).
 * Un sector cuenta si se solapa con la ventana.
 */
class FrontObstacleDetector(
    private val centerDeg: Double = 0.0,
    private val fovDeg: Double = 70.0,
    private val minM: Double = 0.7,
    private val maxM: Double = 25.0,
) {
    fun detect(sectors: List<VisionSector>): VisionSector? = sectors
        .filter { abs(Angle.normalize(it.centerDeg - centerDeg, -180.0, 180.0)) <= (it.widthDeg + fovDeg) / 2.0 }
        .filter { it.distanceM in minM..maxM }
        .minByOrNull { it.distanceM }
}

/**
 * Distancia que se le muestra al piloto en todo momento (midiendo, antes y durante la misión): la del sensor de medición
 * del equipo y, si no tiene lectura, la del otro. Valores nulos, ≤ 0 o no finitos = sin lectura.
 */
object DistanceReadout {
    data class Reading(val sensor: MeasurementSensor, val distanceM: Double)

    enum class Level {
        /** Más cerca que el freno por proximidad: aléjese. */
        TOO_CLOSE,
        /** Midiendo y dentro del alcance de la medición. */
        IN_RANGE,
        NORMAL,
    }

    fun pick(preferred: MeasurementSensor, laserM: Double?, visionM: Double?): Reading? {
        val laser = laserM?.takeIf { it > 0.0 && it.isFinite() }?.let { Reading(MeasurementSensor.LASER, it) }
        val vision = visionM?.takeIf { it > 0.0 && it.isFinite() }?.let { Reading(MeasurementSensor.VISION, it) }
        return if (preferred == MeasurementSensor.LASER) laser ?: vision else vision ?: laser
    }

    fun level(distanceM: Double, measuring: Boolean, profile: SensorProfile, tooCloseM: Double = SensorProfile.MIN_RECOMMENDED_DISTANCE_M): Level = when {
        distanceM < tooCloseM -> Level.TOO_CLOSE
        measuring && distanceM <= profile.maxMeasurementDistanceM -> Level.IN_RANGE
        else -> Level.NORMAL
    }
}

/**
 * `SingleOutlierFilter` del original: un salto ≥ [maxJumpM] respecto a la lectura anterior se sustituye por el último
 * valor aceptado. Si el salto se repite (dos lecturas seguidas coherentes entre sí), se acepta: era un cambio real.
 */
class SingleOutlierFilter(private val maxJumpM: Double = 1.5) {
    private var lastRaw: VisionSector? = null
    private var lastAccepted: VisionSector? = null

    fun filter(reading: VisionSector): VisionSector {
        val prev = lastRaw
        val accepted = lastAccepted
        lastRaw = reading
        val out = if (prev == null || accepted == null || abs(prev.distanceM - reading.distanceM) < maxJumpM) reading else accepted
        lastAccepted = out
        return out
    }
}

/**
 * Convierte las lecturas crudas de un tick en la distancia de medición, descartando las que no son del objetivo.
 * Se crea uno por medición (el filtro de saltos guarda estado).
 */
class MeasurementDistanceSource(
    private val profile: SensorProfile,
    /** Ventana estrecha para contrastar el láser: solo lo que la visión ve justo delante. */
    private val crossCheckDetector: FrontObstacleDetector = FrontObstacleDetector(fovDeg = 20.0),
    /** Con la cámara más inclinada que esto, los sensores horizontales no miran donde apunta el láser: no se contrasta. */
    private val crossCheckMaxPitchDeg: Double = 20.0,
) {
    enum class RejectReason {
        /** Más lejos de lo que puede estar el objetivo (fondo). */
        OUT_OF_RANGE,
        /** La visión ve algo más cerca justo delante: el láser pasó de largo y midió algo detrás. */
        BEHIND_CLOSER_OBJECT,
    }

    sealed interface Result {
        /** Distancia aceptada y desvío del rayo respecto al morro (grados, horario). */
        data class Ok(val distanceM: Double, val bearingOffsetDeg: Double) : Result
        data class Rejected(val reason: RejectReason, val distanceM: Double) : Result
        /** El sensor no dio lectura en este tick. */
        data object NoReading : Result
    }

    private val frontDetector = FrontObstacleDetector()
    private val outlierFilter = SingleOutlierFilter()

    /**
     * @param laserM lectura del láser (≤ 0 = sin lectura); se ignora con el perfil de visión
     * @param sectors sectores horizontales de visión de este tick
     * @param cameraPitchDeg inclinación del gimbal (para decidir si el contraste con la visión tiene sentido)
     */
    fun evaluate(laserM: Double, sectors: List<VisionSector>, cameraPitchDeg: Double): Result = when (profile.sensor) {
        MeasurementSensor.LASER -> evaluateLaser(laserM, sectors, cameraPitchDeg)
        MeasurementSensor.VISION -> evaluateVision(sectors)
    }

    private fun evaluateLaser(laserM: Double, sectors: List<VisionSector>, pitchDeg: Double): Result {
        if (laserM <= 0.0 || !laserM.isFinite()) return Result.NoReading
        if (laserM > profile.maxMeasurementDistanceM) return Result.Rejected(RejectReason.OUT_OF_RANGE, laserM)
        if (abs(pitchDeg) <= crossCheckMaxPitchDeg) {
            val ahead = crossCheckDetector.detect(sectors)
            if (ahead != null && laserM > ahead.distanceM + crossCheckToleranceM(ahead.distanceM)) {
                return Result.Rejected(RejectReason.BEHIND_CLOSER_OBJECT, laserM)
            }
        }
        return Result.Ok(laserM, 0.0)
    }

    private fun evaluateVision(sectors: List<VisionSector>): Result {
        val obstacle = frontDetector.detect(sectors) ?: return Result.NoReading
        val f = outlierFilter.filter(obstacle)
        if (f.distanceM > profile.maxMeasurementDistanceM) return Result.Rejected(RejectReason.OUT_OF_RANGE, f.distanceM)
        return Result.Ok(f.distanceM, f.centerDeg)
    }

    companion object {
        /** Margen para la discrepancia láser/visión: los sensores de visión tienen más error a más distancia. */
        fun crossCheckToleranceM(visionM: Double) = max(1.5, 0.15 * visionM)
    }
}
