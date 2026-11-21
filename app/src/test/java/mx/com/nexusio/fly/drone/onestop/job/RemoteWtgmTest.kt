package mx.com.nexusio.fly.drone.onestop.job

import mx.com.nexusio.fly.drone.onestop.wtgmfile.WtgmReader
import mx.com.nexusio.fly.drone.onestop.wtgmfile.toTurbineJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteWtgmTest {
    private val list = """
        [{"id":"a","wind_farm":"Bii Stinu","manufacturer":"Siemens Gamesa","wtg_model":"G80","tower_height_m":"78","rotor_diameter_m":"80","blade_length_m":"40","created_at":"2026-10-25 17:18:33.342859+00"},
         {"id":"b","wind_farm":"El Cortijo","manufacturer":"Nordex Windpower","wtg_model":"AW-125/3000","tower_height_m":120,"rotor_diameter_m":125,"blade_length_m":62.5,"created_at":"2026-10-24 20:16:26+00"},
         {"id":"c","wind_farm":"Santo Domingo","manufacturer":null,"wtg_model":"G80","tower_height_m":null,"rotor_diameter_m":null,"blade_length_m":null,"created_at":"2026-10-24 21:12:43+00"}]
    """.trimIndent()

    @Test fun `lee la lista con numeros como texto, numeros y nulos`() {
        val rows = RemoteWtgmParser.parseList(list)
        assertEquals(3, rows.size)
        assertEquals(78.0, rows[0].towerHeightM!!, 0.0)
        assertEquals(62.5, rows[1].bladeLengthM!!, 0.0)
        assertNull(rows[2].towerHeightM)
        assertEquals("", rows[2].manufacturer)
        assertEquals("25/10/2026", rows[0].createdDay)
    }

    @Test fun `el parque del proyecto va primero aunque cambien acentos y mayusculas`() {
        val rows = RemoteWtgmParser.parseList(list)
        assertEquals(listOf("b", "a", "c"), RemoteWtgmParser.sorted(rows, "  el CORTIJO ").map { it.id })
        assertEquals(listOf("a", "c", "b"), RemoteWtgmParser.sorted(rows, null).map { it.id })
        assertTrue(RemoteWtgmParser.sameFarm("Eólica del sur", "eolica DEL sur"))
        assertFalse(RemoteWtgmParser.sameFarm("", ""))
    }

    @Test fun `una respuesta que no es lista se rechaza`() {
        try { RemoteWtgmParser.parseList("{\"message\":\"error\"}"); fail() } catch (e: RemoteWtgmException) { }
    }

    @Test fun `el wtgm bajado de la base pasa el lector con su checksum`() {
        val stored = javaClass.getResource("/wtgm/db_El_Cortijo.wtgm")!!.readText().trim()
        val text = RemoteWtgmParser.parseWtgm("[{\"wtgm\":$stored}]")
        val file = WtgmReader.parse(text)
        assertEquals("AW-125/3000", file.turbine.type)
        assertEquals(120.0, file.turbine.towerHeight!!, 0.0)
        assertEquals(62.5, file.turbine.bladeLength!!, 0.0)
        assertEquals(11, file.toTurbineJob().dimensions.size)
    }

    @Test fun `si el job ya no existe se avisa`() {
        try { RemoteWtgmParser.parseWtgm("[]"); fail() } catch (e: RemoteWtgmException) { assertTrue(e.message!!.contains("ya no existe")) }
    }
}
