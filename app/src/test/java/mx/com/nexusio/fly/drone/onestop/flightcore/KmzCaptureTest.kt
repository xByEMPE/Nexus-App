package mx.com.nexusio.fly.drone.onestop.flightcore

import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Cómo se traduce cada waypoint al WPML: sin paradas entre waypoints y acciones de foto en el orden del original. */
class KmzCaptureTest {
    private val origin = GeoPoint(16.5, -95.0, 0.0)
    private val turbine = WindTurbine.fromDimensions(TurbineDimensions.SAMPLE)
    private val local = InspectionPlanner(BladeTwist.default(), turbine, InspectionDefinition(), CameraFov.default(), GimbalLimits())
        .plan(ComponentIndex.B, ComponentIndex.A, ComponentIndex.C)
    private val geo = local.map { WaypointGeo.from(origin, it) }
    private val plan = MissionMapper.toMissionPlan("cap", 1.0, geo)

    private data class Pm(val turnMode: String, val speed: Double, val actions: List<String>)

    /** Placemarks de `waylines.wpml`: modo de giro, velocidad y secuencia de acciones (hover con su tiempo). */
    private fun placemarks(p: MissionPlan, aircraft: KmzAircraftInfo = KmzAircraftInfo(), entry: String = "wpmz/waylines.wpml"): List<Pm> {
        val dir = Files.createTempDirectory("kmzcap").toFile()
        try {
            ZipFile(KmzWaylineWriter.write(p, dir, aircraft)).use { z ->
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(z.getInputStream(z.getEntry(entry)))
                val pms = doc.getElementsByTagName("Placemark")
                return (0 until pms.length).map { i ->
                    val e = pms.item(i) as Element
                    fun text(tag: String) = e.getElementsByTagNameNS("*", tag).item(0)?.textContent ?: ""
                    val acts = e.getElementsByTagNameNS("*", "action")
                    Pm(text("waypointTurnMode"), text("waypointSpeed").toDouble(), (0 until acts.length).map { j ->
                        val a = acts.item(j) as Element
                        val f = a.getElementsByTagNameNS("*", "actionActuatorFunc").item(0).textContent
                        if (f == "hover") "hover " + a.getElementsByTagNameNS("*", "hoverTime").item(0).textContent else f
                    })
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun onlyPathStartsEndsAndPlannedStopsHaltTheDrone() {
        for ((w, pw) in geo.zip(plan.waypoints)) {
            assertEquals("wp ${w.index}", w.stop || w.isFirstInspectionPathPoint || w.isLastInspectionPathPoint, pw.stop)
        }
        val pms = placemarks(plan)
        assertEquals(plan.waypoints.size, pms.size)
        val passing = pms.count { it.turnMode == "toPointAndPassWithContinuityCurvature" }
        val stopping = pms.count { it.turnMode == "toPointAndStopWithDiscontinuityCurvature" }
        println("== KMZ: ${pms.size} waypoints, $passing sin parar, $stopping con parada")
        assertEquals(pms.size, passing + stopping)
        // La gran mayoría de fotos se toma sin detenerse: en cada recorrido solo paran el primero y el último.
        assertTrue("sin parar: $passing de ${pms.size}", passing > pms.size / 2)
        for ((pm, pw) in pms.zip(plan.waypoints)) assertEquals(pw.stop, pm.turnMode == "toPointAndStopWithDiscontinuityCurvature")
    }

    @Test
    fun eachWaypointFliesAtItsOwnPlannedSpeed() {
        val pms = placemarks(MissionMapper.toMissionPlan("v", 3.0, geo))      // la del plan (3 m/s) ya no se impone
        for ((pm, w) in pms.zip(geo)) assertEquals(w.velocity, pm.speed, 1e-9)
    }

    @Test
    fun templateWaypointsDoNotFallBackToTheGlobalStopMode() {
        val dir = Files.createTempDirectory("kmztpl").toFile()
        try {
            ZipFile(KmzWaylineWriter.write(plan, dir)).use { z ->
                val t = z.getInputStream(z.getEntry("wpmz/template.kml")).bufferedReader().readText()
                assertEquals(plan.waypoints.size, Regex("<wpml:useGlobalTurnParam>0</wpml:useGlobalTurnParam>").findAll(t).count())
                assertEquals(plan.waypoints.size, Regex("<wpml:useGlobalSpeed>0</wpml:useGlobalSpeed>").findAll(t).count())
            }
        } finally { dir.deleteRecursively() }
        assertTrue(placemarks(plan, entry = "wpmz/template.kml").any { it.turnMode == "toPointAndPassWithContinuityCurvature" })
    }

    private fun wp(vararg actions: PlannedAction, hover: Double = 0.0, before: Boolean = false) = PlannedWaypoint(
        GeoPoint(16.5, -95.0, 80.0), 10.0, -20.0, actions.any { it.takePhoto }, hover, actions.toList(), 1.0, stop = false, hoverBeforePhoto = before)

    private fun single(w: PlannedWaypoint, settle: Double) =
        placemarks(MissionPlan("a", 1.0, listOf(w)), KmzAircraftInfo(gimbalSettleHoverS = settle)).single().actions

    @Test
    fun matrice4eWaitsOneSecondAfterMovingTheGimbalBeforeThePhoto() {
        val moveAndShoot = wp(PlannedAction(10.0, -20.0, takePhoto = true, rotateGimbal = true, rotateDroneYaw = true))
        assertEquals(listOf("rotateYaw", "gimbalRotate", "hover 1.00", "takePhoto"), single(moveAndShoot, 1.0))
        // Mavic 3E (sin pausa de estabilización): mismo orden, sin hover.
        assertEquals(listOf("rotateYaw", "gimbalRotate", "takePhoto"), single(moveAndShoot, 0.0))
        // Si el gimbal no se mueve, no hace falta esperar: foto al pasar.
        val shootOnly = wp(PlannedAction(10.0, -20.0, takePhoto = true, rotateGimbal = false, rotateDroneYaw = false))
        assertEquals(listOf("takePhoto"), single(shootOnly, 1.0))
    }

    @Test
    fun exposurePauseGoesBeforeOrAfterThePhotoLikeTheOriginal() {
        val a = PlannedAction(10.0, -20.0, takePhoto = true, rotateGimbal = true, rotateDroneYaw = false)
        // Pausa antes (inicio de recorrido): sustituye a la de estabilización.
        assertEquals(listOf("gimbalRotate", "hover 2.00", "takePhoto"), single(wp(a, hover = 2.0, before = true), 1.0))
        // Pausa después: al final, tras la foto.
        assertEquals(listOf("gimbalRotate", "hover 1.00", "takePhoto", "hover 2.00"), single(wp(a, hover = 2.0, before = false), 1.0))
        // Pausa "antes" sin foto: el original no la añade.
        val noPhoto = PlannedAction(10.0, -20.0, takePhoto = false, rotateGimbal = true, rotateDroneYaw = true)
        assertEquals(listOf("rotateYaw", "gimbalRotate"), single(wp(noPhoto, hover = 2.0, before = true), 1.0))
    }

    @Test
    fun sealedPlanDetectsChangesToTheNewFields() {
        val gw = SignedMissionGateway(object : MissionPort {
            override suspend fun upload(plan: MissionPlan, onProgress: (Double) -> Unit) {}
            override suspend fun start(planId: String) {}
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun stop(planId: String) {}
        }, SafetyLimits(5.0, 3000, 90.0, 90.0), ByteArray(32) { it.toByte() })
        gw.seal(plan)
        val tampered = plan.copy(waypoints = plan.waypoints.mapIndexed { i, w -> if (i == 3) w.copy(stop = !w.stop) else w })
        val err = runCatching { kotlinx.coroutines.runBlocking { gw.upload(tampered) {} } }.exceptionOrNull()
        assertTrue("$err", err is IllegalStateException)
    }

    @Test
    fun onLinkLossTheDroneLeavesTheMissionAndReturnsHome() {
        val dir = Files.createTempDirectory("kmzrc").toFile()
        try {
            ZipFile(KmzWaylineWriter.write(plan, dir)).use { z ->
                for (entry in listOf("wpmz/template.kml", "wpmz/waylines.wpml")) {
                    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(z.getInputStream(z.getEntry(entry)))
                    fun text(tag: String) = doc.getElementsByTagNameNS("*", tag).item(0).textContent
                    assertEquals(entry, "executeLostAction", text("exitOnRCLost"))
                    assertEquals(entry, "goBack", text("executeRCLostAction"))
                }
            }
        } finally { dir.deleteRecursively() }
    }
}
