package mx.com.nexusio.fly.drone

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthStateTest {
    @Test fun `sin datos recibidos es NO_DATA aunque la lista este vacia`() =
        assertEquals(HealthState.NO_DATA, HealthStatus.of(false, emptyList()))

    @Test fun `recibido y vacio es NORMAL`() =
        assertEquals(HealthState.NORMAL, HealthStatus.of(true, emptyList()))

    @Test fun `solo avisos leves es WARNING`() =
        assertEquals(HealthState.WARNING, HealthStatus.of(true, listOf(Severity.NOTICE, Severity.CAUTION)))

    @Test fun `un WARNING o SERIOUS es FAILURE`() {
        assertEquals(HealthState.FAILURE, HealthStatus.of(true, listOf(Severity.NOTICE, Severity.WARNING)))
        assertEquals(HealthState.FAILURE, HealthStatus.of(true, listOf(Severity.SERIOUS)))
    }

    @Test fun `etiquetas en singular y plural`() {
        assertEquals("Buscando…", HealthStatus.label(HealthState.NO_DATA, 0))
        assertEquals("Normal", HealthStatus.label(HealthState.NORMAL, 0))
        assertEquals("1 aviso", HealthStatus.label(HealthState.WARNING, 1))
        assertEquals("3 avisos", HealthStatus.label(HealthState.WARNING, 3))
        assertEquals("1 falla", HealthStatus.label(HealthState.FAILURE, 1))
        assertEquals("2 fallas", HealthStatus.label(HealthState.FAILURE, 2))
    }
}
