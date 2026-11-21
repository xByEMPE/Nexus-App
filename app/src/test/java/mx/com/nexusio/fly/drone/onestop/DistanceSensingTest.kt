package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.MeasurementDistanceSource.RejectReason
import mx.com.nexusio.fly.drone.onestop.MeasurementDistanceSource.Result
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceSensingTest {
    private val laser = SensorProfile(MeasurementSensor.LASER)
    private val vision = SensorProfile(MeasurementSensor.VISION)

    /** 72 sectores de 5° sin obstáculos (60 m = "nada") salvo los indicados: índice → metros. */
    private fun sectors(vararg hits: Pair<Int, Double>, n: Int = 72): List<VisionSector> {
        val mm = MutableList(n) { 60_000 }
        for ((i, m) in hits) mm[i] = (m * 1000).toInt()
        return VisionSectors.fromMillimeters(mm)
    }

    // ---------- sectores y detector (copiados del original) ----------

    @Test
    fun sectorsAreCenteredClockwiseFromTheNose() {
        val s = sectors()
        assertEquals(2.5, s[0].centerDeg, 1e-9)
        assertEquals(-2.5, s[71].centerDeg, 1e-9)
        assertEquals(92.5, s[18].centerDeg, 1e-9)       // a la derecha
        assertEquals(5.0, s[0].widthDeg, 1e-9)
        assertEquals(60.0, s[5].distanceM, 1e-9)
    }

    @Test
    fun frontDetectorKeepsTheNearestSectorInsideSeventyDegreesAndRange() {
        val d = FrontObstacleDetector()
        // Torre a 3 m a 90° (fuera de la ventana), pala a 5.5 m a 12.5°, algo a 0.5 m (ruido del propio dron) delante.
        val s = sectors(18 to 3.0, 2 to 5.5, 0 to 0.5)
        val hit = d.detect(s)!!
        assertEquals(5.5, hit.distanceM, 1e-9)
        assertEquals(12.5, hit.centerDeg, 1e-9)
        assertNull(d.detect(sectors(18 to 3.0)))         // solo hay algo al lado: no es el objetivo
        assertNull(d.detect(sectors(1 to 26.0)))         // más allá de 25 m no cuenta
        assertEquals(7.0, d.detect(sectors(7 to 7.0))!!.distanceM, 1e-9)   // sector 35–40°: se solapa con el borde
        assertNull(d.detect(sectors(8 to 7.0)))          // sector 40–45°: fuera
    }

    @Test
    fun singleOutlierIsReplacedButARepeatedJumpIsAccepted() {
        val f = SingleOutlierFilter()
        fun r(m: Double) = f.filter(VisionSector(m, 0.0, 5.0)).distanceM
        assertEquals(5.0, r(5.0), 1e-9)
        assertEquals(5.2, r(5.2), 1e-9)
        assertEquals(5.2, r(12.0), 1e-9)                 // salto de 6.8 m: se ignora
        assertEquals(12.1, r(12.1), 1e-9)                // se repite: era real (p. ej. el dron se alejó)
    }

    // ---------- láser: límite de rango y contraste con la visión ----------

    @Test
    fun laserBeyondThePreciseRangeIsRejectedLikeTheOriginal() {
        val src = MeasurementDistanceSource(laser)
        assertEquals(Result.Rejected(RejectReason.OUT_OF_RANGE, 300.0), src.evaluate(300.0, sectors(), 0.0))   // otra turbina
        assertEquals(Result.Rejected(RejectReason.OUT_OF_RANGE, 18.0), src.evaluate(18.0, sectors(), 0.0))     // demasiado lejos
        assertEquals(Result.Ok(5.0, 0.0), src.evaluate(5.0, sectors(), 0.0))
        assertTrue(src.evaluate(6.2, sectors(), 0.0) is Result.Ok)                                            // 6 m + 0.2 m
        assertEquals(Result.NoReading, src.evaluate(-1.0, sectors(), 0.0))
    }

    @Test
    fun laserFartherThanWhatVisionSeesAheadIsRejected() {
        val src = MeasurementDistanceSource(laser)
        // La visión ve el spinner a 3 m justo delante; el láser marca 5.5 m: lo rozó y midió la góndola de detrás.
        val spinnerAhead = sectors(0 to 3.0, 71 to 3.1)
        assertEquals(Result.Rejected(RejectReason.BEHIND_CLOSER_OBJECT, 5.5), src.evaluate(5.5, spinnerAhead, 5.0))
        // Coinciden dentro del margen (máx(1.5 m, 15 %)): se acepta.
        assertTrue(src.evaluate(4.4, spinnerAhead, 5.0) is Result.Ok)
        // El láser ve algo más cerca que la visión (objeto fino): se acepta, el láser es más fiable.
        assertTrue(src.evaluate(2.5, spinnerAhead, 5.0) is Result.Ok)
    }

    @Test
    fun crossCheckIgnoresObjectsOffTheLaserLineAndSteepAims() {
        val src = MeasurementDistanceSource(laser)
        // La torre a 25° del morro está dentro de la ventana de la visión (±35°) pero no en la línea del láser (±10°).
        assertTrue(src.evaluate(5.5, sectors(5 to 3.0), 0.0) is Result.Ok)
        // Con la cámara a 60°, los sensores horizontales no miran donde apunta el láser: no se contrasta.
        assertTrue(src.evaluate(5.5, sectors(0 to 3.0), 60.0) is Result.Ok)
    }

    // ---------- visión: medición sin láser ----------

    @Test
    fun visionMeasuresTheNearestFrontObstacleWithItsBearing() {
        val src = MeasurementDistanceSource(vision)
        assertEquals(Result.Ok(5.0, -7.5), src.evaluate(-1.0, sectors(70 to 5.0, 18 to 2.0), 0.0))
        assertEquals(Result.NoReading, src.evaluate(-1.0, sectors(), 0.0))
        // El láser se ignora con el perfil de visión.
        assertEquals(Result.NoReading, src.evaluate(5.0, sectors(), 0.0))
    }

    @Test
    fun visionRejectsReadingsBeyondThePreciseRange() {
        val src = MeasurementDistanceSource(vision)
        assertEquals(Result.Rejected(RejectReason.OUT_OF_RANGE, 6.5), src.evaluate(-1.0, sectors(0 to 6.5), 0.0))
        assertTrue(src.evaluate(-1.0, sectors(0 to 6.2), 0.0) is Result.Ok)   // alcance 6 m + 0.2 m de tolerancia
        val m400 = MeasurementDistanceSource(SensorProfile(MeasurementSensor.VISION, preciseMaxM = 7.0))
        assertTrue(m400.evaluate(-1.0, sectors(0 to 6.5), 0.0) is Result.Ok)
    }

    @Test
    fun samplerCountsRejectedReadingsAndFailsWhenTheTargetIsNeverSeen() {
        val src = MeasurementDistanceSource(laser)
        val sampler = LaserMeasurementSampler(LaserMeasurementSampler.Config(maxDistance = laser.maxMeasurementDistanceM))
        val loc = mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation(16.5, -95.0, 80.0)
        var st: LaserMeasurementSampler.Status = LaserMeasurementSampler.Status.Running(0, 0, false)
        // El láser apunta al buje pero sale por encima y mide la turbina de atrás a 320 m durante 15 s.
        for (i in 0 until 200) {
            val r = src.evaluate(320.0, sectors(), 0.0)
            st = sampler.tick(LaserMeasurementSampler.Reading(true, (r as Result.Rejected).distanceM, loc, 0.0, 0.0, true, rejected = true), i * 100L)
            if (st !is LaserMeasurementSampler.Status.Running) break
        }
        st as LaserMeasurementSampler.Status.Failure
        assertEquals(LaserMeasurementSampler.FailureReason.NOT_ENOUGH_SAMPLES, st.reason)
        assertEquals(0, st.samples)
        assertEquals(150, st.ignored)
    }

    // ---------- perfil por equipo ----------

    private fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.first { it.exists() }

    @Test
    fun sensorProfileFollowsTheCatalogEquipment() {
        val catalog = DroneCatalog.parse(repoFile("values.json").readText())
        fun profile(drone: String, payload: String?) = catalog.setup(drone, payload, null)!!.let { SensorProfile.forEquipment(it.droneKey, it.payload) }
        assertEquals(MeasurementSensor.VISION, profile("Mavic_3E", null).sensor)
        assertEquals(MeasurementSensor.VISION, profile("Mavic_3T", null).sensor)
        assertEquals(MeasurementSensor.LASER, profile("Matrice_4T", null).sensor)
        assertEquals(MeasurementSensor.LASER, profile("Matrice_4E", null).sensor)
        profile("Matrice_400", "Zenmuse_P1").let { assertEquals(MeasurementSensor.VISION, it.sensor); assertEquals(7.2, it.maxMeasurementDistanceM, 1e-9) }
        profile("Matrice_400", "Zenmuse_H30T").let { assertEquals(MeasurementSensor.LASER, it.sensor); assertEquals(7.2, it.maxMeasurementDistanceM, 1e-9) }
        profile("Matrice_300_RTK", "Zenmuse_P1").let { assertEquals(MeasurementSensor.VISION, it.sensor); assertEquals(6.2, it.maxMeasurementDistanceM, 1e-9) }
        assertEquals(6.2, profile("Matrice_4T", null).maxMeasurementDistanceM, 1e-9)       // con láser, el mismo alcance
    }

    @Test
    fun readoutShowsTheMeasurementSensorFirstAndFallsBackToTheOther() {
        assertEquals(DistanceReadout.Reading(MeasurementSensor.LASER, 5.2), DistanceReadout.pick(MeasurementSensor.LASER, 5.2, 7.0))
        assertEquals(DistanceReadout.Reading(MeasurementSensor.VISION, 7.0), DistanceReadout.pick(MeasurementSensor.LASER, -1.0, 7.0))
        assertEquals(DistanceReadout.Reading(MeasurementSensor.VISION, 7.0), DistanceReadout.pick(MeasurementSensor.VISION, 5.2, 7.0))
        assertEquals(DistanceReadout.Reading(MeasurementSensor.LASER, 5.2), DistanceReadout.pick(MeasurementSensor.VISION, 5.2, null))
        assertNull(DistanceReadout.pick(MeasurementSensor.LASER, null, Double.NaN))
    }

    @Test
    fun readoutColoursTooCloseAndInMeasurementRange() {
        assertEquals(DistanceReadout.Level.TOO_CLOSE, DistanceReadout.level(3.9, measuring = true, profile = laser))
        assertEquals(DistanceReadout.Level.TOO_CLOSE, DistanceReadout.level(3.9, measuring = false, profile = laser))
        assertEquals(DistanceReadout.Level.IN_RANGE, DistanceReadout.level(5.0, measuring = true, profile = laser))
        assertEquals(DistanceReadout.Level.IN_RANGE, DistanceReadout.level(6.2, measuring = true, profile = laser))
        assertEquals(DistanceReadout.Level.NORMAL, DistanceReadout.level(6.3, measuring = true, profile = laser))
        assertEquals(DistanceReadout.Level.NORMAL, DistanceReadout.level(5.0, measuring = false, profile = laser))
    }
}
