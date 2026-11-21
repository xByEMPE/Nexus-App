package mx.com.nexusio.fly.drone

import mx.com.nexusio.fly.drone.DistanceHold.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceHoldTest {
    private val hold = DistanceHold(3.8f)

    /** Lecturas cada 100 ms desde [from] hasta [to] (ms), todas con [value]. */
    private fun laserUntil(value: Float, from: Long, to: Long) { var t = from; while (t <= to) { hold.feedLaser(value, t); t += 100 } }
    private fun visionUntil(value: Float, from: Long, to: Long) { var t = from; while (t <= to) { hold.feedVision(value, t); t += 100 } }

    @Test fun `arrancar a 4,3 m ya no deja al control ciego`() {
        // el filtro anterior comparaba con 3,8 y descartaba 4,3 para siempre
        laserUntil(4.3f, 0, 500)
        assertEquals(4.3f, hold.distance(500)!!, 0.001f)
        assertEquals(0.125, hold.velocity(hold.distance(500)), 0.001) // 0,5 m de más → avanzar 0,125 m/s
    }

    @Test fun `un pico suelto del laser se ignora`() {
        laserUntil(3.8f, 0, 1000)
        hold.feedLaser(7.5f, 1100)
        hold.feedLaser(3.8f, 1200)
        assertEquals(3.8f, hold.distance(1200)!!, 0.001f)
    }

    @Test fun `un desplazamiento real se acepta cuando se sostiene 600 ms`() {
        laserUntil(3.8f, 0, 1000)
        laserUntil(4.6f, 1100, 1600)
        assertEquals(3.8f, hold.distance(1600)!!, 0.001f) // aún no confirmado
        laserUntil(4.6f, 1700, 1800)
        assertEquals(4.6f, hold.distance(1800)!!, 0.001f)
    }

    @Test fun `lecturas repetidas con la misma hora no cuentan como nuevas`() {
        hold.feedLaser(3.8f, 100)
        repeat(50) { hold.feedLaser(5.0f, 100) }
        assertEquals(3.8f, hold.distance(100)!!, 0.001f)
    }

    @Test fun `si el laser se pierde en la punta la vision lo acompana sin saltos`() {
        // los dos ven la pala: la visión marca 0,2 m menos que el láser
        for (t in 0L..2000L step 100) { hold.feedLaser(3.8f, t); hold.feedVision(3.6f, t) }
        assertEquals(Source.LASER, hold.source(2000))
        assertEquals(0.2f, hold.offset, 0.02f)
        // el láser deja de leer; la visión sigue
        visionUntil(3.6f, 2100, 3000)
        assertEquals(Source.VISION, hold.source(3000))
        assertEquals(3.8f, hold.distance(3000)!!, 0.03f) // misma distancia: no se nota el cambio de fuente
    }

    @Test fun `con la vision sola corrige hacia 3,8 m y con topes mas bajos`() {
        visionUntil(4.6f, 0, 500)
        assertEquals(Source.VISION, hold.source(500))
        val d = hold.distance(500)!!
        assertEquals(4.6f, d, 0.001f)
        assertEquals(0.2, hold.velocity(d, viaVision = true), 0.001)
        assertEquals(0.4, hold.velocity(9.0f, viaVision = true), 0.001)   // tope de avance con visión
        assertEquals(-0.5, hold.velocity(1.0f, viaVision = true), 0.001)  // tope de retroceso con visión
    }

    @Test fun `la vision viendo el fondo no cuenta`() {
        visionUntil(25f, 0, 1000)
        assertNull(hold.distance(1000))
        assertEquals(Source.NONE, hold.source(1000))
    }

    @Test fun `un pico de la vision se ignora igual que el del laser`() {
        visionUntil(3.8f, 0, 1000)
        hold.feedVision(6.5f, 1100)
        hold.feedVision(3.8f, 1200)
        assertEquals(3.8f, hold.distance(1200)!!, 0.001f)
    }

    @Test fun `sin laser ni vision mas de un segundo no hay distancia ni correccion automatica`() {
        laserUntil(3.8f, 0, 500)
        assertNotNull(hold.distance(1400))      // aún dentro del margen
        assertNull(hold.distance(1600))
        assertEquals(Source.NONE, hold.source(1600))
        assertEquals(0.0, hold.velocity(hold.distance(1600)), 0.0)
    }

    @Test fun `corrige hacia 3,8 m con los topes de siempre`() {
        assertEquals(-0.2, hold.velocity(3.3f), 0.001)  // muy cerca: retrocede
        assertEquals(-0.8, hold.velocity(1.0f), 0.001)  // tope de retroceso
        assertEquals(0.0, hold.velocity(3.8f), 0.001)
        assertEquals(0.25, hold.velocity(4.8f), 0.001)  // lejos: avanza
        assertEquals(0.6, hold.velocity(9.0f), 0.001)   // tope de avance
        assertEquals(0.0, hold.velocity(10.5f), 0.0)    // más de 10 m: probablemente el fondo
        assertEquals(0.0, hold.velocity(null), 0.0)
    }
}
