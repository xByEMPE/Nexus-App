package mx.com.nexusio.fly.drone.onestop.wtgmfile

import mx.com.nexusio.fly.drone.onestop.job.TurbineDimensionsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WtgmReaderTest {
    private val example = javaClass.getResource("/wtgm/ejemplo_WTG-07.wtgm")!!.readText()

    @Test fun `lee el archivo de ejemplo`() {
        val f = WtgmReader.parse(example)
        assertEquals("WTG-07", f.turbine.id)
        assertEquals(94.0, f.turbine.towerHeight!!, 0.0)
        assertEquals(2.5, f.geometry.hubLength, 0.0)
        assertEquals(9.0, f.geometry.rotorAxisAngle, 0.0)
        assertNull(f.turbine.rotorDiameter)
    }

    @Test fun `se convierte en datos de turbina para el planificador`() {
        val job = WtgmReader.parse(example).toTurbineJob()
        assertEquals("WTG-07", job.name)
        assertEquals(11, job.dimensions.size)
        val dims = TurbineDimensionsBuilder.build(54.65, 94.0, job)
        assertEquals(4.5, dims.bladeTiltAngle, 0.0)
        assertEquals(94.0, dims.towerHeight, 0.0)
    }

    @Test fun `un valor editado a mano rompe el checksum`() {
        expectError(example.replace("\"hubLength\": 2.5", "\"hubLength\": 2.6"), "Checksum")
    }

    @Test fun `rechaza json invalido, formato desconocido y campo faltante`() {
        expectError("no es json", "JSON")
        expectError(example.replace("\"format\": \"wtgm\"", "\"format\": \"otro\""), "Formato")
        expectError(example.replace("\"nacelleThickness\": 2.5", "\"x\": 1"), "nacelleThickness")
    }

    @Test fun `rechaza una version futura`() {
        expectError(example.replace("\"formatVersion\": 1", "\"formatVersion\": 2"), "Versión")
    }

    private fun expectError(json: String, fragment: String) {
        try { WtgmReader.parse(json); fail("debía fallar: $fragment") }
        catch (e: WtgmException) { assertTrue(e.message, e.message!!.contains(fragment)) }
    }
}
