package mx.com.nexusio.fly.drone.onestop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotorTipLayoutTest {
    private val eps = 0.5f

    @Test fun `vista con la misma proporcion coloca la punta de 12h en la foto`() {
        val t = RotorTipLayout.tipCenters(1792f, 1539f)
        assertEquals(905.0f, t[0].x, eps)
        assertEquals(153.9f, t[0].y, eps)
    }

    @Test fun `vista mas ancha centra la foto en horizontal`() {
        val b = RotorTipLayout.imageBox(2000f, 1539f)
        assertEquals(104f, b.left, eps)
        assertEquals(0f, b.top, eps)
        assertEquals(1792f, b.width, eps)
        assertEquals(1009.0f, RotorTipLayout.tipCenters(2000f, 1539f)[0].x, eps)
    }

    @Test fun `vista mas alta centra la foto en vertical`() {
        val b = RotorTipLayout.imageBox(1792f, 2000f)
        assertEquals(230.5f, b.top, eps)
        assertEquals(1539f, b.height, eps)
    }

    @Test fun `padding reduce la caja`() {
        val b = RotorTipLayout.imageBox(1812f, 1559f, padding = 10f)
        assertEquals(10f, b.left, eps)
        assertEquals(10f, b.top, eps)
        assertEquals(1792f, b.width, eps)
    }

    @Test fun `toque sobre cada punta devuelve su indice`() {
        val t = RotorTipLayout.tipCenters(600f, 400f)
        t.forEachIndexed { i, p -> assertEquals(i, RotorTipLayout.hitTip(p.x, p.y, 600f, 400f, 40f)) }
    }

    @Test fun `toque lejos de las puntas devuelve null`() {
        assertNull(RotorTipLayout.hitTip(300f, 300f, 600f, 400f, 40f))
    }
}
