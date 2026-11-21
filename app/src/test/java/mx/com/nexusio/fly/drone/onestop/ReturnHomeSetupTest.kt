package mx.com.nexusio.fly.drone.onestop

import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety
import mx.com.nexusio.fly.drone.onestop.flightcore.ReturnHomeSafety.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnHomeSetupTest {
    private class FakePort(var failHeight: Boolean = false, val noPathMode: Boolean = false) : ReturnHomePort {
        val calls = ArrayList<String>()
        override suspend fun homeLocation() = 16.5 to -95.0
        override suspend fun rthHeightRange() = 20..500
        override suspend fun setFailsafeGoHome() { calls += "failsafe" }
        override suspend fun setFixedHeightPath() { if (noPathMode) error("not supported"); calls += "path" }
        override suspend fun setRthHeight(meters: Int) { if (failHeight) error("busy"); calls += "height $meters" }
    }

    private val schedule = ReturnHomeSafety.Schedule(
        ReturnHomeSafety.Heights(20, 205),
        listOf(Profile.LEVEL, Profile.LEVEL, Profile.CLIMB, Profile.CLIMB, Profile.LEVEL),
        ReturnHomeSafety.Check(7.0, 0, "", ReturnHomeSafety.Leg.CLIMB),
    )

    @Test
    fun prepareSetsGoHomeFixedHeightAndLevelHeight() = runBlocking {
        val port = FakePort()
        assertTrue(ReturnHomeSetup.prepare(port).isEmpty())
        assertEquals(listOf("failsafe", "path", "height 20"), port.calls)
    }

    @Test
    fun aDroneWithoutFixedHeightPathOnlyGetsAnOptionalWarning() = runBlocking {
        val port = FakePort(noPathMode = true)
        val f = ReturnHomeSetup.prepare(port)
        assertEquals(1, f.size)
        assertTrue(f.single().optional)
        assertEquals(listOf("failsafe", "height 20"), port.calls)
    }

    @Test
    fun climbHeightIsClampedToWhatTheDroneAccepts() {
        val ok = ReturnHomeSetup.heightsWithin(20..500, 234)
        assertEquals(ReturnHomeSafety.Heights(20, 234), ok.heights)
        assertFalse(ok.climbClamped)
        val limited = ReturnHomeSetup.heightsWithin(30..120, 234)
        assertEquals(ReturnHomeSafety.Heights(30, 120), limited.heights)
        assertTrue(limited.climbClamped)
        assertEquals(ReturnHomeSafety.Heights(20, 234), ReturnHomeSetup.heightsWithin(null, 234).heights)
    }

    @Test
    fun controllerWritesOnlyWhenTheScheduledHeightChanges() = runBlocking {
        val port = FakePort()
        val c = ReturnHomeHeightController(port)
        assertNull(c.update(0))                       // sin programa no toca nada
        c.schedule = schedule
        assertEquals(20, c.update(0))
        assertNull(c.update(1))
        assertEquals(205, c.update(2))
        assertNull(c.update(3))
        assertEquals(20, c.update(4))
        assertNull(c.update(99))                      // más allá del último waypoint sigue el último perfil
        assertEquals(listOf("height 20", "height 205", "height 20"), port.calls)
    }

    @Test
    fun aRejectedChangeIsRetriedOnTheNextUpdate() = runBlocking {
        val port = FakePort(failHeight = true)
        val c = ReturnHomeHeightController(port).apply { schedule = this@ReturnHomeSetupTest.schedule }
        assertNull(c.update(2))
        assertNull(c.applied)
        port.failHeight = false
        assertEquals(205, c.update(2))
        assertEquals(205, c.applied)
    }
}
