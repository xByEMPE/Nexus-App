package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.VirtualTurbineScene
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Misión completa (medición con láser virtual → plan → vuelo) con viento sobre una turbina de 120 m de buje y palas de 64 m. */
class WindFlightTest {
    private val dims = TurbineDimensions.SAMPLE.copy(rotorDiameter = 128.0, towerHeight = 120.0)
    // Viento del norte: la góndola se orienta a favor del viento, así que el rotor mira al norte (rumbo 0°).
    private val scene = VirtualTurbineScene(GeoPoint(40.0, -3.0, 0.0), dims, facingDeg = 0.0, laserNoiseSigmaM = 0.05)
    private val plan = VirtualPilot.planFromLaser(scene)
    private val waypoints = VirtualPilot.inSceneFrame(scene, plan)
    private val seeds = 1L..30L

    private fun fly(wind: WindModel?) = FlightSimulator(scene.turbine, config = SimConfig(wind = wind)).run(waypoints)

    private data class Row(val label: String, val reports: List<SimReport>) {
        val worst get() = reports.minBy { it.minDistance }
        fun line() = "%-34s dmin(peor)=%5.2f m  cobertura(peor)=%5.1f%%  desvío máx=%5.2f m  violaciones=%d/%d".format(
            label, worst.minDistance, reports.minOf { it.coverage.values.min() } * 100, reports.maxOf { it.maxTrackingErrorM },
            reports.count { it.violations.isNotEmpty() }, reports.size)
    }

    private fun row(label: String, wind: (Long) -> WindModel?) = rowWith(label, SimConfig()) { wind(it) }

    private fun rowWith(label: String, base: SimConfig, wind: (Long) -> WindModel?) =
        Row(label, seeds.map { FlightSimulator(scene.turbine, config = base.copy(wind = wind(it))).run(waypoints) })

    @Test
    fun northWind8ms() {
        val calm = fly(null)
        val windy = row("viento N 8 m/s uniforme") { WindModel(8.0, 0.0, seed = it) }
        // Si 8 m/s es la medida a 10 m del suelo, a la altura del buje (120 m) es bastante mayor.
        val shear = row("8 m/s a 10 m (cizalla) → ≈11 m/s buje") { WindModel(8.0, 0.0, shearRefHeightM = 10.0, seed = it) }
        val strong = row("viento N 12 m/s") { WindModel(12.0, 0.0, seed = it) }
        val gale = row("viento N 15 m/s") { WindModel(15.0, 0.0, seed = it) }
        val table = listOf(Row("calma", listOf(calm)), windy, shear, strong, gale)
        println("== Turbina buje 120 m, pala 64 m, rotor mirando al norte, ${waypoints.size} waypoints, ${seeds.count()} semillas de turbulencia")
        table.forEach { println(it.line()) }
        println(windy.worst.summary())

        // El resultado depende de lo bien que el piloto automático sigue la trayectoria (ganancia) y de la turbulencia: se barren ambas.
        println("-- sensibilidad con 8 m/s (peor de ${seeds.count()} semillas) --")
        val sens = listOf(0.3, 0.6, 1.2).flatMap { kp -> listOf(0.10, 0.15, 0.25).map { ti ->
            rowWith("seguimiento Kp=$kp, turbulencia ${(ti * 100).toInt()}%", SimConfig(trackKp = kp, trackKi = kp / 6)) { WindModel(8.0, 0.0, turbulenceIntensity = ti, seed = it) }
        } }
        sens.forEach { println(it.line()) }
        File("build/sim-output/wind-8ms-sensibilidad.txt").writeText(sens.joinToString("\n") { it.line() })

        val w = windy.worst
        ViewerExporter.export(File("build/sim-output/viewer-wind-8ms-norte.html"), "Viento N 8 m/s (peor de ${seeds.count()} semillas)",
            scene.turbine, waypoints, w, CameraFov.default())
        println("peor caso: ${w.minDistanceComponent} en ${w.minDistanceAt}")
        File("build/sim-output/wind-8ms-norte.txt").writeText(table.joinToString("\n") { it.line() })

        assertTrue("con 8 m/s: ${w.violations}", windy.reports.all { it.violations.isEmpty() })
    }
}
