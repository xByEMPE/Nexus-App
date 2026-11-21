package mx.com.nexusio.fly.drone.onestop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perfiles de cámara copiados de la app auditada para Mavic 3E y Matrice 4E. */
class CaptureProfileTest {

    @Test
    fun mavic3eUsesWideLensMechanicalShutterAndDewarping() {
        val p = CaptureProfile.forEquipment("Mavic_3E", null)
        assertEquals(CaptureLens.WIDE, p.lens)
        assertTrue(p.mechanicalShutter)
        assertTrue(p.dewarping)
        assertEquals(0.0, p.gimbalSettleHoverS, 0.0)
        assertFalse(p.stopAtEachWaypoint)
        assertEquals(null, p.zoomRatio)
        assertEquals(
            listOf("ISO 100 · 1/1000 s · f/8", "ISO 100 · 1/800 s · f/8", "ISO 100 · 1/640 s · f/8",
                "ISO 100 · 1/1000 s · f/5.6", "ISO 100 · 1/800 s · f/5.6", "ISO 100 · 1/640 s · f/5.6",
                "ISO 200 · 1/640 s · f/5.6", "ISO 400 · 1/640 s · f/5.6", "ISO 800 · 1/640 s · f/5.6",
                "ISO 1600 · 1/640 s · f/5.6", "ISO 1600 · 1/640 s · f/4", "ISO 1600 · 1/640 s · f/2.8",
                "ISO 3200 · 1/640 s · f/2.8", "ISO 6400 · 1/640 s · f/2.8"),
            p.exposureSteps.map { it.label },
        )
    }

    @Test
    fun matrice4eUsesTheSeventyMillimetreZoomWithFastElectronicShutter() {
        val p = CaptureProfile.forEquipment("Matrice_4E", null)
        assertEquals(CaptureLens.ZOOM, p.lens)
        assertEquals(3.0, p.zoomRatio!!, 0.0)
        assertTrue(p.useZoomStream)
        assertTrue(p.largePhoto)
        assertFalse(p.mechanicalShutter)                      // la tele media no tiene obturador mecánico
        assertEquals(1.0, p.gimbalSettleHoverS, 0.0)
        assertEquals(listOf(8000, 6400, 5000, 2500, 1250, 640, 640, 640, 640, 640, 640, 640), p.exposureSteps.map { it.shutterDenominator })
        assertEquals(listOf(100, 100, 100, 100, 100, 100, 200, 400, 800, 1600, 3200, 6400), p.exposureSteps.map { it.iso })
        assertTrue(p.exposureSteps.all { it.aperture == null })
    }

    @Test
    fun noStepEverGoesSlowerThanOneSixHundredFortieth() {
        for (p in listOf(CaptureProfile.MAVIC_3E, CaptureProfile.MATRICE_4E)) {
            assertTrue(p.exposureSteps.all { it.shutterDenominator >= 640 })
            // De más oscuro a más claro: nunca se pierde luz al subir un escalón.
            val light = p.exposureSteps.map { it.iso.toDouble() / it.shutterDenominator / ((it.aperture ?: 2.8) * (it.aperture ?: 2.8)) }
            assertEquals(light.sorted(), light)
        }
    }

    @Test
    fun otherEquipmentKeepsThePreviousBehaviour() {
        val p = CaptureProfile.forEquipment("Mavic_3T", null)
        assertFalse(p.manualExposure)
        assertTrue(CameraSetup.preparation(p).isEmpty())
        assertTrue(CaptureProfile.forEquipment("Matrice_400", "Zenmuse_P1").stopAtEachWaypoint)   // como el original
    }

    @Test
    fun settingsAreAppliedInTheOriginalOrder() {
        // M4E: se mide viendo el gran angular; la tele y su zoom llegan al terminar las 5 medidas.
        assertEquals(
            listOf(CameraSetting.VideoSource(zoom = false), CameraSetting.LargePhoto),
            CameraSetup.preparation(CaptureProfile.MATRICE_4E),
        )
        assertEquals(
            listOf(CameraSetting.VideoSource(zoom = true), CameraSetting.ZoomRatio(3.0)),
            CameraSetup.inspectionLens(CaptureProfile.MATRICE_4E),
        )
        assertEquals(listOf(CameraSetting.VideoSource(zoom = false)), CameraSetup.measurementLens(CaptureProfile.MATRICE_4E))
        // M3E: siempre gran angular, no hay cambio de lente.
        assertEquals(listOf(CameraSetting.Dewarping(true)), CameraSetup.preparation(CaptureProfile.MAVIC_3E))
        assertTrue(CameraSetup.inspectionLens(CaptureProfile.MAVIC_3E).isEmpty())
        assertTrue(CameraSetup.measurementLens(CaptureProfile.MAVIC_3E).isEmpty())
        val m3e = CaptureProfile.MAVIC_3E
        assertEquals(
            listOf(CameraSetting.ManualExposureMode(true), CameraSetting.MechanicalShutter(true),
                CameraSetting.Iso(100), CameraSetting.Shutter(1000), CameraSetting.Aperture(8.0)),
            CameraSetup.manualExposure(m3e, m3e.exposureSteps[0]),
        )
        // Al cambiar de escalón solo se reenvían ISO, tiempo y diafragma.
        assertEquals(listOf(CameraSetting.Iso(800), CameraSetting.Shutter(640), CameraSetting.Aperture(5.6)),
            CameraSetup.manualExposure(m3e, m3e.exposureSteps[8], includeMode = false))
        val m4e = CaptureProfile.MATRICE_4E
        assertEquals(listOf(CameraSetting.ManualExposureMode(true), CameraSetting.Iso(100), CameraSetting.Shutter(8000)),
            CameraSetup.manualExposure(m4e, m4e.exposureSteps[0]))
    }

    @Test
    fun journalKeepsTheFirstPreviousValueAndRestoresInReverseOrder() {
        val j = SettingsJournal<String, String>()
        j.recordIfFirst("source", "WIDE_CAMERA")
        j.recordIfFirst("zoom", "1.0")
        j.recordIfFirst("mode", "PROGRAM")
        j.recordIfFirst("iso", "ISO_AUTO")
        j.recordIfFirst("iso", "ISO_100")                       // segundo escalón: no pisa el valor original
        assertEquals(listOf("iso" to "ISO_AUTO", "mode" to "PROGRAM", "zoom" to "1.0", "source" to "WIDE_CAMERA"), j.restoreOrder())
        j.clear()
        assertTrue(j.restoreOrder().isEmpty())
    }

    @Test
    fun aRejectedSettingIsReportedAndTheRestStillApplied() {
        val applied = ArrayList<CameraSetting>()
        val port = object : CameraPort {
            override suspend fun apply(setting: CameraSetting) {
                applied += setting
                if (setting is CameraSetting.MechanicalShutter) throw IllegalStateException("no soportado")
            }
        }
        val m3e = CaptureProfile.MAVIC_3E
        val failures = runBlocking { CameraSetup.apply(port, CameraSetup.manualExposure(m3e, m3e.exposureSteps[2])) }
        assertEquals(listOf(CameraSetting.MechanicalShutter(true) to "no soportado"), failures)
        assertEquals(5, applied.size)
    }
}
