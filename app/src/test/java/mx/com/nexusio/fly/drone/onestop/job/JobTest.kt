package mx.com.nexusio.fly.drone.onestop.job

import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTest {
    private val validJob = """
        {"jobVersion":1,"turbine":{"name":"WTG-1","model":"V112"},
         "dimensions":{"hubLength":3.0,"towerBottomRadius":3.5,"towerTopRadius":2.1,"nacelleThickness":4}}
    """.trimIndent()

    private fun repoFile(relative: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.first { it.exists() }

    // ---------- JobParser ----------
    @Test fun parsesValidJob() {
        val r = JobParser.parse(validJob) as JobParseResult.Ok
        assertEquals("WTG-1", r.job.name)
        assertEquals(3.0, r.job.dimensions["hubLength"]!!, 1e-9)
        assertEquals(4.0, r.job.dimensions["nacelleThickness"]!!, 1e-9) // entero aceptado como número
        assertTrue(r.rejected.isEmpty())
    }

    @Test fun exampleFileFromDocsParses() {
        val r = JobParser.parse(repoFile("docs/ejemplo_turbina.job").readText()) as JobParseResult.Ok
        assertEquals(12, r.job.dimensions.size)
        assertTrue(r.rejected.isEmpty())
    }

    @Test fun rejectsWholeFileOnBadVersionNameOrJson() {
        assertTrue(JobParser.parse("no json") is JobParseResult.Invalid)
        assertTrue(JobParser.parse("""{"jobVersion":2,"turbine":{"name":"a"},"dimensions":{}}""") is JobParseResult.Invalid)
        assertTrue(JobParser.parse("""{"jobVersion":1,"turbine":{"name":" "},"dimensions":{}}""") is JobParseResult.Invalid)
        assertTrue(JobParser.parse("""{"jobVersion":1,"turbine":{"name":"a"}}""") is JobParseResult.Invalid)
    }

    @Test fun badFieldsFallBackToGenericWithoutRejectingFile() {
        val r = JobParser.parse(
            """{"jobVersion":1,"turbine":{"name":"a"},
            "dimensions":{"hubLength":"3 m","bladeTiltAngle":99,"nacelleThickness":3.0,"unknown":1}}"""
        ) as JobParseResult.Ok
        assertEquals(setOf("hubLength", "bladeTiltAngle"), r.rejected.toSet())
        assertEquals(mapOf("nacelleThickness" to 3.0), r.job.dimensions)
    }

    @Test fun incoherentTowerRadiiBothDropped() {
        val r = JobParser.parse(
            """{"jobVersion":1,"turbine":{"name":"a"},
            "dimensions":{"towerBottomRadius":2.0,"towerTopRadius":3.0}}"""
        ) as JobParseResult.Ok
        assertTrue(r.job.dimensions.isEmpty())
        assertEquals(2, r.rejected.size)
    }

    @Test fun towerHeightInJobIsIgnored() {
        val r = JobParser.parse("""{"jobVersion":1,"turbine":{"name":"a"},"dimensions":{"towerHeight":999}}""") as JobParseResult.Ok
        assertFalse(r.job.dimensions.containsKey("towerHeight"))
    }

    @Test fun storedJsonRoundTrip() {
        val job = (JobParser.parse(validJob) as JobParseResult.Ok).job
        assertEquals(job, JobParser.fromStoredJson(JobParser.toJson(job)))
        assertNull(JobParser.fromStoredJson("garbage"))
    }

    // ---------- TurbineDimensionsBuilder ----------
    @Test fun builderMixesAppHubJobAndGenerics() {
        val job = (JobParser.parse(validJob) as JobParseResult.Ok).job
        val d = TurbineDimensionsBuilder.build(bladeLengthM = 55.0, hubHeightM = 95.0, job = job)
        assertEquals(110.0, d.rotorDiameter, 1e-9)
        assertEquals(95.0, d.towerHeight, 1e-9)
        assertEquals(3.0, d.hubLength, 1e-9) // del job
        assertEquals(TurbineDimensions.SAMPLE.bladeMaxChordWidth, d.bladeMaxChordWidth, 1e-9) // genérico
    }

    @Test fun rotorDiameterOfJobNeverOverridesBladeLength() {
        val job = (JobParser.parse("""{"jobVersion":1,"turbine":{"name":"a"},"dimensions":{"rotorDiameter":120}}""") as JobParseResult.Ok).job
        assertEquals(110.0, TurbineDimensionsBuilder.build(55.0, 95.0, job).rotorDiameter, 1e-9)
        assertTrue(TurbineDimensionsBuilder.rotorDiameterMismatch(job, 55.0))
        assertFalse(TurbineDimensionsBuilder.rotorDiameterMismatch(job, 60.0))
        assertFalse(TurbineDimensionsBuilder.rotorDiameterMismatch(null, 55.0))
    }

    // ---------- HubHeightValidator ----------
    @Test fun hubHeightRules() {
        assertEquals(HubHeightValidator.Result.Empty, HubHeightValidator.validate("  ", 55.0))
        assertEquals(HubHeightValidator.Result.OutOfRange, HubHeightValidator.validate("abc", 55.0))
        assertEquals(HubHeightValidator.Result.OutOfRange, HubHeightValidator.validate("10", 0.0))
        assertEquals(HubHeightValidator.Result.OutOfRange, HubHeightValidator.validate("301", 55.0))
        assertEquals(HubHeightValidator.Result.BelowBlade, HubHeightValidator.validate("50", 55.0))
        assertEquals(HubHeightValidator.Result.Valid(95.5), HubHeightValidator.validate("95,5", 55.0))
    }

    // ---------- DroneCatalog ----------
    private fun catalog() = DroneCatalog.parse(repoFile("values.json").readText())

    @Test fun catalogMatchesValuesJson() {
        val c = catalog()
        assertEquals(9, c.drones.size)
        assertEquals(103, c.find("Matrice_400")!!.droneEnum)
        assertEquals(setOf("Zenmuse_P1", "Zenmuse_H30", "Zenmuse_H30T"), c.find("Matrice_400")!!.payloads.keys)
        assertTrue(c.find("Matrice_4E")!!.hasSinglePayload)
    }

    @Test fun assetCatalogIsIdenticalToValuesJson() {
        assertEquals(repoFile("values.json").readText(), repoFile("app/src/main/assets/drone_catalog.json").readText())
    }

    @Test fun detectsIntegratedCameras() {
        val c = catalog()
        val cases = mapOf(
            "M4E" to Triple(99, 0, 88), "M4T" to Triple(99, 1, 89), "M3E" to Triple(77, 0, 66),
            "M3T" to Triple(77, 1, 67), "M30" to Triple(67, 0, 52), "M30T" to Triple(67, 1, 53),
        )
        for ((camera, expected) in cases) {
            val d = c.detect("ANY", camera) as DroneDetection.Recognized
            val info = c.resolve(d.drone.key, d.payload)!!
            assertEquals(camera, expected, Triple(info.droneEnum, info.droneSubEnum, info.payloadEnum))
        }
    }

    @Test fun detectsInterchangeablePlatforms() {
        val c = catalog()
        val m400 = c.detect("DJI_MATRICE_400", "ZENMUSE_H30T") as DroneDetection.Recognized
        val i400 = c.resolve(m400.drone.key, m400.payload)!!
        assertEquals(Triple(103, 0, 83), Triple(i400.droneEnum, i400.droneSubEnum, i400.payloadEnum))
        val m350 = c.detect("M350_RTK", "ZENMUSE_P1") as DroneDetection.Recognized
        assertEquals(89, c.resolve(m350.drone.key, m350.payload)!!.droneEnum)
        assertTrue(c.detect("M300_RTK", null) is DroneDetection.NeedsPayload)
        assertEquals(DroneDetection.Unknown, c.detect(null, null))
        assertEquals(DroneDetection.Unknown, c.detect("P4", "P4A"))
    }

    // ---------- Campo de visión por cámara/lente ----------
    @Test fun fovFromDiagonalMatchesKnownP1Values() {
        val f = DroneCatalog.fovFromDiagonal(63.5, 3.0 to 2.0)
        assertEquals(54.5, f.horizontal, 0.1)
        assertEquals(37.9, f.vertical, 0.1)
        assertEquals(63.5, f.diagonal, 1e-9)
        val g = DroneCatalog.fovFromDiagonal(84.0, 4.0 to 3.0)
        val sumSq = Math.pow(Math.tan(Math.toRadians(g.horizontal / 2)), 2.0) + Math.pow(Math.tan(Math.toRadians(g.vertical / 2)), 2.0)
        assertEquals(Math.pow(Math.tan(Math.toRadians(42.0)), 2.0), sumSq, 1e-9)
    }

    @Test fun catalogReadsLensesFromUnifiedValuesJson() {
        val c = catalog()
        // M4E: la tele media (como la app auditada); el resto solo la gran angular: ni zoom/tele ni térmica
        assertEquals(listOf("medium_tele_camera"), c.lensesFor(c.find("Matrice_4E")!!, null).map { it.name })
        assertEquals(listOf("wide_camera"), c.lensesFor(c.find("Mavic_3E")!!, null).map { it.name })
        assertEquals(listOf("wide_camera"), c.lensesFor(c.find("Matrice_400")!!, "Zenmuse_H30T").map { it.name })
        assertEquals(listOf("wide_camera"), c.lensesFor(c.find("Mavic_3T")!!, null).map { it.name })
        assertEquals(3, c.lensesFor(c.find("Matrice_350_RTK")!!, "Zenmuse_P1").size) // lentes fijas del P1
    }

    @Test fun setupUsesCameraLensFovAndLensIndex() {
        val c = catalog()
        // M4E: tele media de 70 mm (35° de diagonal, 4:3), fotografiada con la lente "zoom", como la app auditada
        val m4e = c.setup("Matrice_4E", null, null)!!
        assertEquals(35.0, m4e.cameraFov.diagonal, 1e-9)
        assertEquals("zoom", m4e.aircraft.payloadLensIndex)
        assertEquals(88, m4e.aircraft.payloadEnum)
        // aunque se haya guardado la gran angular, la inspección del M4E usa la tele media
        assertEquals(35.0, c.setup("Matrice_4E", null, "wide_camera")!!.cameraFov.diagonal, 1e-9)

        // Mavic 3E: gran angular; pedir la tele no es posible
        val m3e = c.setup("Mavic_3E", null, "tele_camera")!!
        assertEquals(84.0, m3e.cameraFov.diagonal, 1e-9)
        assertEquals("wide", m3e.aircraft.payloadLensIndex)

        val h30 = c.setup("Matrice_400", "Zenmuse_H30", null)!!
        assertEquals(82.1, h30.cameraFov.diagonal, 1e-9)
        assertEquals(82, h30.aircraft.payloadEnum)

        val p1 = c.setup("Matrice_350_RTK", "Zenmuse_P1", "lens_35mm_standard")!!
        assertEquals(54.5, p1.cameraFov.horizontal, 0.1) // P1 es 3:2
        assertEquals("visable", p1.aircraft.payloadLensIndex)

        assertNull(c.setup("Matrice_350_RTK", null, null)) // cámara intercambiable sin elegir
        assertNull(c.setup("Inexistente", null, null))
    }

    @Test fun everyDroneHasUsableFovForAllItsCameras() {
        val c = catalog()
        for (d in c.drones) for (p in d.payloads.keys) assertNotNull("${d.key}/$p", c.setup(d.key, p, null))
    }

    @Test fun resolveRequiresPayloadForMultiCameraPlatforms() {
        val c = catalog()
        assertNull(c.resolve("Matrice_350_RTK", null))
        assertNull(c.resolve("Matrice_350_RTK", "Zenmuse_X"))
        assertNotNull(c.resolve("Matrice_350_RTK", "Zenmuse_H30"))
        assertNull(c.resolve("Inexistente", null))
    }
}
