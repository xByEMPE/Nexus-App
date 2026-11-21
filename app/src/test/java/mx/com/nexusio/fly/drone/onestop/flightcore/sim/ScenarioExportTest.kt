package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.LaserMeasurementSampler
import mx.com.nexusio.fly.drone.onestop.MeasurementDistanceSource
import mx.com.nexusio.fly.drone.onestop.MeasurementSensor
import mx.com.nexusio.fly.drone.onestop.SensorProfile
import mx.com.nexusio.fly.drone.onestop.VisionSectors
import mx.com.nexusio.fly.drone.onestop.flightcore.Angle
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.BladeTwist
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.ComponentIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.DroneLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoMath
import mx.com.nexusio.fly.drone.onestop.flightcore.GeoPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.GimbalLimits
import mx.com.nexusio.fly.drone.onestop.flightcore.InspectionDefinition
import mx.com.nexusio.fly.drone.onestop.flightcore.MissionPipeline
import mx.com.nexusio.fly.drone.onestop.flightcore.PathIndex
import mx.com.nexusio.fly.drone.onestop.flightcore.PauseTiming
import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementLocation
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineMeasurementPoint
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.VirtualTurbineScene
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import mx.com.nexusio.fly.drone.onestop.job.DroneCatalog
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Genera `build/sim-output/dashboard-data.json` para el panel de simulación: 3 turbinas × 2 drones × 6 escenarios
 * (calma, viento de 9 m/s del norte, del sur, del este y del oeste, y pérdida de enlace sin recuperación, delante y detrás
 * del rotor; detrás, también con la configuración anterior para comparar).
 * El rotor mira al norte y el punto de despegue está 60 m delante de él. Las mediciones se toman a 5 m, como la app.
 */
class ScenarioExportTest {
    private data class TurbineCase(val id: String, val name: String, val hub: Double, val blade: Double) {
        val dims get() = TurbineDimensions.SAMPLE.copy(rotorDiameter = blade * 2, towerHeight = hub)
    }

    private data class DroneCase(
        val id: String, val name: String, val lens: String, val fov: CameraFov, val sensor: SensorProfile,
        val enduranceMin: Double, val gimbalSettleS: Double,
    )

    /**
     * [lossBehindRotor]: la señal se pierde detrás del rotor (pala de las 12h); si no, bajo la pala de las 4h, delante.
     * [previousRth]: configuración anterior (mantenerse en el sitio y RTH a 100 m) en lugar de la vuelta a casa inmediata
     * con la altura de RTH por tramo.
     */
    private data class Scenario(
        val id: String, val name: String, val windFromDeg: Double?, val windMps: Double,
        val linkLoss: Boolean = false, val lossBehindRotor: Boolean = false, val previousRth: Boolean = false,
    )

    private val turbines = listOf(
        TurbineCase("t80", "Buje 80 m · pala 38 m", 80.0, 38.0),
        TurbineCase("t120", "Buje 120 m · pala 64 m", 120.0, 64.0),
        TurbineCase("t145", "Buje 145 m · pala 68 m", 145.0, 68.0),
    )
    private val drones = listOf(
        DroneCase("m4e", "Matrice 4E", "Tele media 70 mm (zoom 3×), obturador electrónico", DroneCatalog.fovFromDiagonal(35.0, 4.0 to 3.0),
            SensorProfile.forEquipment("Matrice_4E", null), enduranceMin = 49.0, gimbalSettleS = 1.0),
        DroneCase("m3e", "Mavic 3E", "Gran angular 24 mm, obturador mecánico", DroneCatalog.fovFromDiagonal(84.0, 4.0 to 3.0),
            SensorProfile.forEquipment("Mavic_3E", null), enduranceMin = 45.0, gimbalSettleS = 0.0),
    )
    private val scenarios = listOf(
        Scenario("calm", "Calma", null, 0.0),
        Scenario("n9", "Viento norte 9 m/s", 0.0, 9.0),
        Scenario("s9", "Viento sur 9 m/s", 180.0, 9.0),
        Scenario("e9", "Viento cruzado este 9 m/s", 90.0, 9.0),
        Scenario("w9", "Viento cruzado oeste 9 m/s", 270.0, 9.0),
        Scenario("loss", "Pérdida de señal delante del rotor (viento norte 9 m/s)", 0.0, 9.0, linkLoss = true),
        Scenario("loss2", "Pérdida de señal detrás del rotor (viento norte 9 m/s)", 0.0, 9.0, linkLoss = true, lossBehindRotor = true),
        Scenario("loss2old", "Detrás del rotor con la configuración anterior (viento norte 9 m/s)", 0.0, 9.0, linkLoss = true, lossBehindRotor = true, previousRth = true),
    )
    private val seeds = 1L..10L
    private val towerBase = GeoPoint(16.5, -95.0, 0.0)
    private val home = Vec3(0.0, 60.0, 0.0)
    private val lowBattery = 25.0

    /** Límites mecánicos del gimbal del Matrice 4E y del Mavic 3E (−90° a +35°); el plan usa los genéricos de la app. */
    private val HARDWARE_GIMBAL = GimbalLimits(maxPitchUp = 35.0, maxPitchDown = 90.0)

    // ───────── medición a 5 m (como la app) y plan ─────────

    private fun measure(s: VirtualTurbineScene, sensor: SensorProfile, from: Vec3, target: Vec3): TurbineMeasurementPoint {
        val v = target - from
        val yaw = Angle.normalize(Math.toDegrees(atan2(v.x, v.y)), -180.0, 180.0)
        val g = GeoMath.toGeo(s.towerBase, from)
        val loc = DroneLocation(g.lat, g.lon, g.alt)
        val source = MeasurementDistanceSource(sensor)
        val sampler = LaserMeasurementSampler(LaserMeasurementSampler.Config(maxDistance = sensor.maxMeasurementDistanceM))
        for (i in 0 until 1000) {
            val laser = if (sensor.sensor == MeasurementSensor.LASER) s.laserDistance(g, yaw, 0.0).toDouble() else -1.0
            val mm = (0 until 180).map { k ->
                val d = s.forwardObstacle(g, yaw + (k + 0.5) * 2.0, maxRange = 30.0)
                if (d > 0f) (d * 1000).roundToInt() else 60_000
            }
            val reading = when (val r = source.evaluate(laser, VisionSectors.fromMillimeters(mm), 0.0)) {
                is MeasurementDistanceSource.Result.Ok -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw + r.bearingOffsetDeg, 0.0, true)
                is MeasurementDistanceSource.Result.Rejected -> LaserMeasurementSampler.Reading(true, r.distanceM, loc, yaw, 0.0, true, rejected = true)
                MeasurementDistanceSource.Result.NoReading -> LaserMeasurementSampler.Reading(false, -1.0, loc, yaw, 0.0, true)
            }
            when (val st = sampler.tick(reading, i * 100L)) {
                is LaserMeasurementSampler.Status.Success -> return st.point
                is LaserMeasurementSampler.Status.Failure -> error("medición fallida: $st")
                else -> Unit
            }
        }
        error("la medición no terminó")
    }

    private data class Plan(val result: MissionPipeline.Result, val waypoints: List<Waypoint3D>, val stops: List<Boolean>)

    private fun plan(s: VirtualTurbineScene, d: DroneCase): Plan {
        val t = s.turbine
        val front = Vec3(t.turbineDirection.x, t.turbineDirection.y, 0.0).normalize()
        fun along(b: BladePosition, r: Double) = t.bladeRoot + t.bladeAxisDirection(b) * r
        val len = s.dims.bladeLength
        fun at(target: Vec3) = measure(s, d.sensor, target + front * 5.0, target)
        val m = mapOf(
            TurbineMeasurementLocation.SPINNER to at(t.spinner - t.rotorAxisDirection * 1.0),
            TurbineMeasurementLocation.TIP_12 to at(along(BladePosition.TWELVE, len * 0.9)),
            TurbineMeasurementLocation.BOTTOM_12 to at(along(BladePosition.TWELVE, 6.0)),
            TurbineMeasurementLocation.TIP_4 to at(along(BladePosition.FOUR, len * 0.9)),
            TurbineMeasurementLocation.TIP_8 to at(along(BladePosition.EIGHT, len * 0.9)),
        )
        val r = MissionPipeline.plan(
            firstDroneLocation = m.getValue(TurbineMeasurementLocation.SPINNER).droneLocation, measurements = m, dimensions = s.dims,
            definition = InspectionDefinition(), bladeTwistInput = BladeTwist.default(), cam = d.fov, gimbal = GimbalLimits(),
            rtkSetting = null, stopAtEach = false, blade4 = ComponentIndex.B, blade12 = ComponentIndex.A, blade8 = ComponentIndex.C,
        )
        // Pausa de 1 s del M4E entre mover el gimbal y disparar (como el KMZ): se suma al tiempo en ese waypoint.
        val wps = VirtualPilot.inSceneFrame(s, r).map { w ->
            if (d.gimbalSettleS > 0 && w.pauseTiming != PauseTiming.BEFORE && w.actions.any { it.rotateGimbal && it.takePhoto })
                w.copy(pauseTiming = PauseTiming.AFTER, pauseDrone = w.pauseDrone + d.gimbalSettleS) else w
        }
        val stops = r.waypoints.map { it.stop || it.isFirstInspectionPathPoint || it.isLastInspectionPathPoint }
        return Plan(r, wps, stops)
    }

    // ───────── batería ─────────

    /** %/s: autonomía de fábrica sin viento; el viento aumenta el consumo (supuesto: +50 % a 12 m/s, cuadrático). */
    private fun drain(d: DroneCase) = { w: Double -> 100.0 / (d.enduranceMin * 60.0) * (1.0 + 0.5 * (w / 12.0) * (w / 12.0)) }

    // ───────── telemetría ─────────

    private data class Tel(val t: Double, val p: Vec3, val d: Double, val bat: Double, val wind: Double, val yaw: Double, val pitch: Double, val wp: Int, val st: FlightState)

    private class RunOut(
        val scenario: Scenario, val worstSeed: Long?, val tel: List<Tel>, val photos: List<PhotoPose>, val report: SimReport,
        val minDistance: Double, val minComponent: String, val events: List<Pair<Double, String>>, val outcome: String,
        val seedMins: List<Double>, val endBattery: Double, val usedPct: Double, val flightTime: Double, val pathLength: Double, val violations: List<String>,
    )

    private fun missionTelemetry(r: SimReport, wind: WindModel?, d: DroneCase, battery0: Double = 100.0): List<Tel> {
        val dr = drain(d)
        var bat = battery0; var lastT = 0.0
        val out = ArrayList<Tel>()
        var nextSec = 0.0
        for (s in r.samples) {
            val w = wind?.speedAt(s.pos.z) ?: 0.0
            bat -= dr(w) * (s.t - lastT); lastT = s.t
            if (s.t + 1e-9 >= nextSec) {
                out += Tel(s.t, s.pos, s.distance, bat, w, s.yawDeg, s.gimbalPitchDeg, s.nextWaypoint, FlightState.MISSION)
                nextSec = floor(s.t) + 1.0
            }
        }
        return out
    }

    private fun runScenario(sc: Scenario, s: VirtualTurbineScene, p: Plan, d: DroneCase): RunOut {
        fun wind(seed: Long) = sc.windFromDeg?.let { WindModel(sc.windMps, it, seed = seed, lateralTurbulenceRatio = 0.7) }
        fun fly(seed: Long, wps: List<Waypoint3D>) =
            FlightSimulator(s.turbine, gimbalLimits = HARDWARE_GIMBAL, cam = d.fov, config = SimConfig(wind = wind(seed))).run(wps)

        if (!sc.linkLoss) {
            val runs = (if (sc.windFromDeg == null) listOf(1L) else seeds.toList()).map { it to fly(it, p.waypoints) }
            val (seed, worst) = runs.minBy { it.second.minDistance }
            val tel = missionTelemetry(worst, wind(seed), d)
            val events = ArrayList<Pair<Double, String>>()
            tel.firstOrNull { it.bat <= lowBattery }?.let { events += it.t to "Batería baja (%.0f %%) en el waypoint %d: el dron volvería a casa antes de terminar".format(it.bat, it.wp) }
            val used = 100.0 - tel.last().bat
            val outcome = when {
                worst.violations.isNotEmpty() -> "Con violaciones de seguridad"
                events.isNotEmpty() -> "Segura, pero no cabe en una batería"
                else -> "Completa y segura"
            }
            return RunOut(sc, if (sc.windFromDeg == null) null else seed, tel, worst.photos, worst, worst.minDistance, worst.minDistanceComponent,
                events, outcome, runs.map { it.second.minDistance }, tel.last().bat, used, worst.flightTimeSec, worst.pathLengthM, worst.violations)
        }

        // Delante: a mitad del intradós (PS) de la pala de las 4h. Detrás: el punto más retrasado respecto al rotor en la pala
        // de las 12h, desde donde la línea recta a casa (delante) cruza el plano del rotor.
        val lossAt = if (sc.lossBehindRotor) {
            val rootY = s.turbine.bladeRoot.y
            p.waypoints.indices.filter { p.waypoints[it].bladePosition == BladePosition.TWELVE }.maxBy { rootY - p.waypoints[it].point.y }
        } else {
            val ps = p.waypoints.indices.filter { p.waypoints[it].bladePosition == BladePosition.FOUR && p.waypoints[it].pathIndex == PathIndex.PS }
            ps[ps.size / 2]
        }
        val w = wind(1L)
        val cfg = if (sc.previousRth) LinkLossConfig(rthAltitudeM = 100.0) else {
            val schedule = ReturnHomeSafety.schedule(s.turbine, p.waypoints.map { it.point }, home,
                ReturnHomeSafety.Heights(ReturnHomeSafety.DJI_MIN_RTH_HEIGHT_M, ReturnHomeSafety.climbHeightAboveHomeM(s.turbine, home)))
            LinkLossConfig(immediateRth = true, rthAltitudeM = schedule.heightAt(lossAt).toDouble())
        }
        val partial = fly(1L, p.waypoints.subList(0, lossAt + 1))
        val tel = missionTelemetry(partial, w, d).toMutableList()
        val last = partial.samples.last()
        val sim = LinkLossSimulator(s.turbine, w, home, cfg, drainPctPerSec = drain(d))
        val loss = sim.run(last.pos, last.t, tel.last().bat)
        var nextSec = floor(last.t) + 1.0
        for (ls in loss.samples) if (ls.t + 1e-9 >= nextSec) {
            tel += Tel(ls.t, ls.pos, ls.distance, ls.battery, w?.speedAt(ls.pos.z) ?: 0.0, tel.last().yaw, tel.last().pitch, lossAt, ls.state)
            nextSec = floor(ls.t) + 1.0
        }
        val lastLoss = loss.samples.last()
        tel += Tel(lastLoss.t, lastLoss.pos, lastLoss.distance, lastLoss.battery, w?.speedAt(lastLoss.pos.z) ?: 0.0, tel.last().yaw, tel.last().pitch, lossAt,
            if (loss.contact) FlightState.CONTACT else FlightState.LANDED)
        val minD = minOf(partial.minDistance, loss.minDistance)
        val minC = if (partial.minDistance <= loss.minDistance) partial.minDistanceComponent else loss.minComponent
        val path = tel.zipWithNext { a, b -> (b.p - a.p).norm }.sum()
        val events = listOf(last.t to "Waypoint %d de %d alcanzado; se pierde el enlace".format(lossAt, p.waypoints.size)) + loss.events
        return RunOut(sc, 1L, tel, partial.photos, partial, minD, minC, events, loss.outcome, listOf(minD),
            loss.endBattery, 100.0 - loss.endBattery, loss.endTime, path, partial.violations + (if (loss.contact) listOf("contacto con la turbina tras perder el enlace") else emptyList()))
    }

    // ───────── JSON ─────────

    private fun n(v: Double, dec: Int = 2) = if (v.isFinite()) String.format(Locale.ROOT, "%.${dec}f", v) else "null"
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun v3(v: Vec3) = "[${n(v.x, 1)},${n(v.y, 1)},${n(v.z, 1)}]"

    @Test
    fun exportDashboardData() {
        val sb = StringBuilder()
        sb.append("{\"generated\":${q(java.time.LocalDateTime.now().withNano(0).toString())},")
        sb.append("\"home\":${v3(home)},\"lowBattery\":$lowBattery,")
        sb.append("\"assumptions\":[")
        sb.append(listOf(
            "Turbinas con las dimensiones genéricas de la app (cuerda máxima 3.8 m, brida 1.255 m, torre 3 m/2 m de radio), con la altura de buje y el largo de pala pedidos. Rotor mirando al norte; despegue 60 m delante del rotor.",
            "Plan sin RTK (márgenes ampliados), igual que en la app. Las 5 mediciones se toman a 5 m: Matrice 4E con láser, Mavic 3E con sensores de visión.",
            "Viento de 9 m/s a todas las alturas con un 15 % de turbulencia, más fuerte en la dirección del viento que de lado (IEC 61400-1: 70 %); 10 semillas de turbulencia por escenario y se muestra la peor. El viento constante lo compensa el piloto automático; lo que desvía al dron son las ráfagas. El seguimiento (PI, Kp 0.6) es un supuesto, no medido.",
            "Límites de gimbal de los drones reales (−90° a +35°). El plan pide hasta −85° en las últimas fotos de la punta de la pala 12h con la gran angular del Mavic 3E.",
            "Batería: autonomía de fábrica sin viento (M4E 49 min, M3E 45 min) y +50 % de consumo a 12 m/s (supuesto). Vuelta a casa al 25 %, aterrizaje forzado al 10 %.",
            "Pérdida de enlace: el dron sale de la misión y vuelve a casa en el acto (KMZ goBack) con la altura de RTH que la app dejó para ese tramo: a la altura actual (20 m) o por encima del rotor (punta más alta + 20 m). La RTH sube en vertical si va más bajo, va en línea recta y baja; si la evasión de obstáculos frena (3 m), el dron queda parado y no rodea. Comportamiento de DJI simplificado.",
            "Configuración anterior (escenario de comparación): se mantiene en el sitio hasta la batería baja y vuelve a casa a 100 m, como la app auditada (hover).",
            "El simulador vuela tramos rectos a velocidad constante; no reproduce el redondeo de trayectoria al pasar sin parar por los waypoints.",
        ).joinToString(",") { q(it) })
        sb.append("],\"turbines\":[")
        val summary = ArrayList<String>()
        turbines.forEachIndexed { ti, tc ->
            val scene = VirtualTurbineScene(towerBase, tc.dims, facingDeg = 0.0, laserNoiseSigmaM = 0.05)
            if (ti > 0) sb.append(",")
            sb.append("{\"id\":${q(tc.id)},\"name\":${q(tc.name)},\"hub\":${n(tc.hub, 0)},\"blade\":${n(tc.blade, 0)},\"solids\":[")
            sb.append(scene.turbine.collisionSolids.joinToString(",") { c -> "{\"name\":${q(c.name)},\"a\":${v3(c.base)},\"b\":${v3(c.top)},\"ra\":${n(c.rBase)},\"rb\":${n(c.rTop)}}" })
            sb.append("],\"drones\":[")
            drones.forEachIndexed { di, dc ->
                val p = plan(scene, dc)
                if (di > 0) sb.append(",")
                sb.append("{\"id\":${q(dc.id)},\"name\":${q(dc.name)},\"lens\":${q(dc.lens)},\"sensor\":${q(if (dc.sensor.sensor == MeasurementSensor.LASER) "Láser" else "Visión")},")
                sb.append("\"enduranceMin\":${n(dc.enduranceMin, 0)},\"waypointCount\":${p.waypoints.size},\"photoActions\":${p.waypoints.sumOf { w -> w.actions.count { it.takePhoto } }},")
                sb.append("\"stops\":${p.stops.count { it }},\"rotorIndexDeg\":${n(p.result.rotorIndexAngleDeg, 1)},")
                sb.append("\"waypoints\":[" + p.waypoints.mapIndexed { i, w -> "[${n(w.point.x, 1)},${n(w.point.y, 1)},${n(w.point.z, 1)},${if (p.stops[i]) 1 else 0},${if (w.actions.any { it.takePhoto }) 1 else 0}]" }.joinToString(",") + "],")
                sb.append("\"runs\":[")
                scenarios.forEachIndexed { si, sc ->
                    val r = runScenario(sc, scene, p, dc)
                    if (si > 0) sb.append(",")
                    sb.append("{\"id\":${q(sc.id)},\"name\":${q(sc.name)},\"windMps\":${n(sc.windMps, 0)},\"windFrom\":${sc.windFromDeg?.let { n(it, 0) } ?: "null"},")
                    sb.append("\"seed\":${r.worstSeed ?: "null"},\"seeds\":${r.seedMins.size},\"seedMins\":[${r.seedMins.joinToString(",") { n(it) }}],")
                    sb.append("\"outcome\":${q(r.outcome)},\"flightTime\":${n(r.flightTime, 0)},\"pathLength\":${n(r.pathLength, 0)},\"photos\":${r.photos.size},")
                    sb.append("\"minDistance\":${n(r.minDistance)},\"minComponent\":${q(r.minComponent)},\"maxTrackErr\":${n(r.report.maxTrackingErrorM)},")
                    sb.append("\"coverage\":{" + r.report.coverage.entries.joinToString(",") { "${q(it.key.name)}:${n(it.value, 3)}" } + "},")
                    sb.append("\"endBattery\":${n(r.endBattery, 1)},\"usedPct\":${n(r.usedPct, 1)},\"batteries\":${ceil(r.usedPct / (100.0 - lowBattery)).toInt()},")
                    sb.append("\"violations\":[${r.violations.take(20).joinToString(",") { q(it) }}],")
                    sb.append("\"events\":[${r.events.joinToString(",") { "[${n(it.first, 0)},${q(it.second)}]" }}],")
                    sb.append("\"photoPos\":[${r.photos.joinToString(",") { v3(it.pos) }}],\"photoWp\":[${r.photos.joinToString(",") { it.waypoint.toString() }}],")
                    val tel = r.tel
                    sb.append("\"tel\":{")
                    sb.append("\"t\":[${tel.joinToString(",") { n(it.t, 0) }}],")
                    sb.append("\"x\":[${tel.joinToString(",") { n(it.p.x, 1) }}],\"y\":[${tel.joinToString(",") { n(it.p.y, 1) }}],\"z\":[${tel.joinToString(",") { n(it.p.z, 1) }}],")
                    sb.append("\"d\":[${tel.joinToString(",") { n(it.d, 2) }}],\"bat\":[${tel.joinToString(",") { n(it.bat, 1) }}],")
                    sb.append("\"wind\":[${tel.joinToString(",") { n(it.wind, 1) }}],\"yaw\":[${tel.joinToString(",") { n(it.yaw, 0) }}],")
                    sb.append("\"pitch\":[${tel.joinToString(",") { n(it.pitch, 0) }}],\"wp\":[${tel.joinToString(",") { it.wp.toString() }}],")
                    sb.append("\"st\":[${tel.joinToString(",") { q(it.st.name) }}]")
                    sb.append("}}")
                    summary += "%-6s %-4s %-5s dmin=%6.2f m (%s) t=%5.0f s fotos=%4d bat=%5.1f%% %s".format(
                        tc.id, dc.id, sc.id, r.minDistance, r.minComponent, r.flightTime, r.photos.size, r.endBattery, r.outcome)
                }
                sb.append("]}")
            }
            sb.append("]}")
        }
        sb.append("]}")
        val out = File("build/sim-output/dashboard-data.json")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println("== Escenarios (${out.length() / 1024} KB)")
        summary.forEach { println(it) }
        File("build/sim-output/dashboard-summary.txt").writeText(summary.joinToString("\n"))
        assertTrue(out.length() > 0)
    }
}
