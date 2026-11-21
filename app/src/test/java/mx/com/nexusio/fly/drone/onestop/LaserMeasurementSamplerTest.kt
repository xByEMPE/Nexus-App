package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.LaserMeasurementSampler.FailureReason
import mx.com.nexusio.fly.drone.onestop.LaserMeasurementSampler.Reading
import mx.com.nexusio.fly.drone.onestop.LaserMeasurementSampler.Status
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaserMeasurementSamplerTest {
    private val here = DroneLocation(16.5, -95.0, 80.0)

    private fun reading(distance: Double, fresh: Boolean = true, heading: Double = 30.0, pitch: Double = 10.0, gps: Boolean = true) =
        Reading(fresh, distance, here, heading, pitch, gps)

    /** Ejecuta ticks de 100 ms hasta que termina; [next] da la lectura del tick i. */
    private fun run(sampler: LaserMeasurementSampler = LaserMeasurementSampler(), maxTicks: Int = 2000, next: (Int) -> Reading): Pair<Status, Int> {
        for (i in 0 until maxTicks) {
            val s = sampler.tick(next(i), wallClockElapsedMs = i * 100L)
            if (s !is Status.Running) return s to i
        }
        error("la medición no terminó")
    }

    @Test
    fun stableLaserSucceedsAtMinimumTimeWithAveragedValues() {
        val rnd = Random(1)
        val (s, tick) = run { reading(5.0 + rnd.nextDouble(-0.05, 0.05)) }
        s as Status.Success
        assertEquals(69, tick)                       // 70 ticks × 100 ms = 7 s, el mínimo
        assertEquals(70, s.samples)
        assertEquals(5.0, s.point.distance, 0.02)
        assertEquals(30.0, s.point.headingDeg, 1e-9)
        assertEquals(10.0, s.point.pitchDeg, 1e-9)
        assertTrue(s.stdDev <= 0.3)
    }

    @Test
    fun noisyLaserFailsAtMaximumTime() {
        val rnd = Random(2)
        val (s, tick) = run { reading(5.0 + rnd.nextDouble(-1.5, 1.5)) }
        s as Status.Failure
        assertEquals(FailureReason.STD_DEV_TOO_HIGH, s.reason)
        assertEquals(149, tick)                      // 15 s
        assertTrue(s.stdDev!! > 0.3)
    }

    @Test
    fun aFewStrayReadingsKeepTheDeviationHighAndFail() {
        // Cinco lecturas a 6.2 m (el láser rozó el borde y midió algo detrás) entre lecturas a 4 m: como en el original, no se descartan,
        // la desviación queda alta hasta los 15 s y la medición falla en vez de aceptar un punto contaminado.
        val (s, _) = run { i -> reading(if (i < 5) 6.2 else 4.0) }
        assertTrue(s is Status.Failure && s.reason == FailureReason.STD_DEV_TOO_HIGH)
    }

    @Test
    fun outOfRangeReadingsAreIgnoredAndCanLeaveTooFewSamples() {
        // Solo 1 de cada 10 lecturas está dentro de rango → 15 muestras en 15 s, menos de las 20 exigidas.
        val (s, _) = run { i -> reading(if (i % 10 == 0) 5.0 else 500.0) }
        s as Status.Failure
        assertEquals(FailureReason.NOT_ENOUGH_SAMPLES, s.reason)
        assertEquals(15, s.samples)
    }

    @Test
    fun shortGapsKeepTheClockRunningAndLongGapsFreezeIt() {
        val sampler = LaserMeasurementSampler()
        var status: Status = Status.Running(0, 0, false)
        // 5 lecturas buenas, luego 10 ticks sin lectura nueva (1 s): a partir de 600 ms el reloj se congela.
        for (i in 0 until 5) status = sampler.tick(reading(5.0), i * 100L)
        for (i in 5 until 15) status = sampler.tick(reading(5.0, fresh = false), i * 100L)
        status as Status.Running
        assertTrue(status.paused)
        assertEquals(500 + 500, status.elapsedMs)    // 5 ticks buenos + 5 ticks de hueco (100..500 ms) antes de congelar
        val resumed = sampler.tick(reading(5.0), 1500L) as Status.Running
        assertTrue(!resumed.paused)
        assertEquals(6, resumed.samples)
    }

    @Test
    fun laserLostForTwentySecondsAborts() {
        val (s, tick) = run { i -> reading(5.0, fresh = i < 5) }
        s as Status.Failure
        assertEquals(FailureReason.LASER_LOST, s.reason)
        assertEquals(5 + 199, tick)                  // 200 ticks sin lectura = 20 s
    }

    @Test
    fun invalidLaserValueCountsAsMissingReading() {
        val (s, _) = run { reading(-1.0) }
        assertEquals(FailureReason.LASER_LOST, (s as Status.Failure).reason)
    }

    @Test
    fun gpsLossAbortsImmediately() {
        val (s, tick) = run { i -> reading(5.0, gps = i < 30) }
        assertEquals(FailureReason.GPS_LOST, (s as Status.Failure).reason)
        assertEquals(30, tick)
    }

    @Test
    fun wallClockCeilingAbortsEvenWhilePaused() {
        val sampler = LaserMeasurementSampler()
        sampler.tick(reading(5.0), 0)
        // Láser intermitente: el reloj de medición casi no avanza, pero el reloj real sí.
        val s = sampler.tick(reading(5.0, fresh = false), 60_000)
        assertEquals(FailureReason.WALL_CLOCK, (s as Status.Failure).reason)
    }

    @Test
    fun finishedSamplerKeepsReturningTheSameResult() {
        val sampler = LaserMeasurementSampler()
        val (s, _) = run(sampler) { reading(5.0) }
        assertEquals(s, sampler.tick(reading(99.0), 0))
    }

    @Test
    fun headingAverageHandlesTheMinus180To180Seam() {
        val (s, _) = run { i -> reading(5.0, heading = if (i % 2 == 0) 179.0 else -179.0) }
        val h = (s as Status.Success).point.headingDeg
        assertTrue("rumbo medio $h", abs(abs(h) - 180.0) < 1e-6)
    }

    @Test
    fun averagingBeatsASingleNoisyReadingOnTheAimedPoint() {
        // Láser con ruido de ±0.25 m (dentro del umbral). El punto medido con la media cae más cerca del real que el peor tiro único.
        val rnd = Random(7)
        val readings = List(70) { 5.0 + rnd.nextDouble(-0.25, 0.25) }
        val (s, _) = run { i -> reading(readings[i % readings.size]) }
        val origin = GeoPoint(16.5, -95.0, 0.0)
        val truth = (s as Status.Success).point.copy(distance = 5.0).toGeo(origin)
        fun err(d: Double) = s.point.copy(distance = d).toGeo(origin).let { p ->
            val dz = p.alt - truth.alt; val dlat = (p.lat - truth.lat) * 111_000; val dlon = (p.lon - truth.lon) * 111_000
            kotlin.math.sqrt(dz * dz + dlat * dlat + dlon * dlon)
        }
        val averaged = err(s.point.distance)
        val worstSingle = readings.maxOf { err(it) }
        assertTrue("media $averaged m vs peor lectura $worstSingle m", averaged < worstSingle / 3)
    }
}
