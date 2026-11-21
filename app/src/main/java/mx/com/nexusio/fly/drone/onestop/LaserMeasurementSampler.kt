package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Medición de un punto de la turbina con muchas lecturas del láser en lugar de una sola.
 *
 * Reproduce el bucle de `DistanceMeasurementController.performMeasurement` del original (SmartPilot v3):
 * - Un tick cada [Config.tickMs] (100 ms). Cada lectura nueva y dentro de rango se guarda con la posición, el rumbo y el pitch.
 * - Éxito en cuanto hay ≥ [Config.minSamples] muestras, han pasado ≥ [Config.minTimeMs] y la desviación típica es ≤ [Config.maxStdDev].
 * - Al llegar a [Config.maxTimeMs] se evalúa por última vez; si faltan muestras o la desviación es alta, falla.
 * - Sin lecturas nuevas durante ≥ [Config.stalePauseMs] el reloj de medición se congela (pausa); ≥ [Config.staleAbortMs] aborta.
 * - Aborta si se pierde el GPS o si el tiempo real supera [Config.wallClockLimitMs] (incluidas las pausas).
 * - El resultado es la media de posición, distancia y pitch, y la media circular del rumbo.
 *
 * Es lógica pura (sin Android ni DJI) para poder probarla con tiempos simulados. Sirve igual para el láser y para los
 * sensores de visión: [Config.maxDistance] sale de [SensorProfile.maxMeasurementDistanceM] (6.2 m; 7.2 m en el
 * Matrice 400, como el original). Las lecturas descartadas (fuera de rango o [Reading.rejected]) se cuentan en `ignored`.
 */
class LaserMeasurementSampler(private val config: Config = Config()) {

    data class Config(
        val tickMs: Long = 100,
        val minSamples: Int = 20,
        val minTimeMs: Long = 7_000,
        val maxTimeMs: Long = 15_000,
        val maxStdDev: Double = 0.3,
        val maxDistance: Double = 6.2,
        val stalePauseMs: Long = 600,
        val staleAbortMs: Long = 20_000,
        val wallClockLimitMs: Long = 60_000,
    )

    /**
     * Lo que se lee del dron en un tick. [fresh] = llegó una lectura nueva del sensor desde el tick anterior.
     * [rejected] = hubo lectura pero no es del objetivo (p. ej. el láser midió algo detrás de lo que ve la visión).
     */
    data class Reading(
        val fresh: Boolean,
        val distance: Double,
        val location: DroneLocation,
        val headingDeg: Double,
        val pitchDeg: Double,
        val hasGpsFix: Boolean,
        val rejected: Boolean = false,
    )

    enum class FailureReason { GPS_LOST, LASER_LOST, WALL_CLOCK, NOT_ENOUGH_SAMPLES, STD_DEV_TOO_HIGH }

    sealed interface Status {
        data class Running(val samples: Int, val elapsedMs: Long, val paused: Boolean, val ignored: Int = 0) : Status
        data class Success(val point: TurbineMeasurementPoint, val samples: Int, val stdDev: Double) : Status
        data class Failure(val reason: FailureReason, val samples: Int, val stdDev: Double?, val ignored: Int = 0) : Status
    }

    private val distances = ArrayList<Double>()
    private val locations = ArrayList<DroneLocation>()
    private val headings = ArrayList<Double>()
    private val pitches = ArrayList<Double>()
    private var elapsedMs = 0L
    private var staleMs = 0L
    private var ignored = 0
    private var done: Status? = null

    val sampleCount get() = distances.size

    /** Procesa un tick. [wallClockElapsedMs] es el tiempo real desde que empezó la medición. */
    fun tick(r: Reading, wallClockElapsedMs: Long): Status {
        done?.let { return it }
        if (elapsedMs >= config.maxTimeMs) return finish()
        if (!r.hasGpsFix) return fail(FailureReason.GPS_LOST)
        if (wallClockElapsedMs >= config.wallClockLimitMs) return fail(FailureReason.WALL_CLOCK)

        val loc = r.location
        val usable = r.fresh && (r.rejected || r.distance > 0.0) && loc.latitude != null && loc.longitude != null && loc.altitude != null
        if (usable) {
            staleMs = 0
            // Igual que el original: una lectura fuera de rango se ignora, pero el tiempo de medición avanza.
            if (!r.rejected && r.distance <= config.maxDistance) {
                distances += r.distance
                locations += loc
                headings += Angle.normalize(r.headingDeg, -180.0, 180.0)
                pitches += r.pitchDeg
            } else {
                ignored++
            }
            elapsedMs += config.tickMs
            if (distances.size >= config.minSamples && elapsedMs >= config.minTimeMs) {
                val std = stdDev(distances)
                if (std <= config.maxStdDev) return succeed(std)
            }
            if (elapsedMs >= config.maxTimeMs) return finish()
            return Status.Running(distances.size, elapsedMs, paused = false, ignored = ignored)
        }

        staleMs += config.tickMs
        if (staleMs >= config.staleAbortMs) return fail(FailureReason.LASER_LOST)
        val paused = staleMs >= config.stalePauseMs
        if (!paused) elapsedMs += config.tickMs   // un hueco corto cuenta como tiempo; uno largo congela el reloj
        if (elapsedMs >= config.maxTimeMs) return finish()
        return Status.Running(distances.size, elapsedMs, paused, ignored)
    }

    private fun finish(): Status {
        if (distances.size < config.minSamples) return fail(FailureReason.NOT_ENOUGH_SAMPLES)
        val std = stdDev(distances)
        return if (std > config.maxStdDev) fail(FailureReason.STD_DEV_TOO_HIGH, std) else succeed(std)
    }

    private fun succeed(std: Double): Status {
        val point = TurbineMeasurementPoint(
            droneLocation = DroneLocation(
                locations.map { it.latitude!! }.average(),
                locations.map { it.longitude!! }.average(),
                locations.map { it.altitude!! }.average(),
            ),
            headingDeg = circularMeanDeg(headings),
            pitchDeg = pitches.average(),
            distance = distances.average(),
        )
        return Status.Success(point, distances.size, std).also { done = it }
    }

    private fun fail(reason: FailureReason, std: Double? = null): Status =
        Status.Failure(reason, distances.size, std, ignored).also { done = it }

    companion object {
        /** Desviación típica poblacional (divide por n), como `computeStandardDeviation` del original. */
        fun stdDev(values: List<Double>): Double {
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }

        /** Media circular en grados, en [-180, 180]: 179° y -179° dan 180°, no 0°. */
        fun circularMeanDeg(values: List<Double>): Double {
            val s = values.sumOf { sin(Math.toRadians(it)) }
            val c = values.sumOf { cos(Math.toRadians(it)) }
            return Angle.normalize(Math.toDegrees(atan2(s, c)), -180.0, 180.0)
        }
    }
}
