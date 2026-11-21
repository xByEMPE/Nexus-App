package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Vuelta a casa (RTH) que no cruza el rotor si el dron pierde el enlace.
 *
 * La RTH de DJI con altura fija sube en vertical hasta la altura de RTH (si va más bajo), vuela en línea recta a casa
 * y baja en vertical; si ya va más alto, vuelve a su altura. Con una sola altura no hay forma segura de volver desde
 * toda la misión:
 * - baja (la app auditada deja 20 m): desde detrás del rotor la recta a casa atraviesa el rotor;
 * - por encima del rotor: debajo de las palas de las 4h y las 8h la subida vertical choca con la pala.
 *
 * Por eso hay dos perfiles y la app cambia la altura de RTH del dron según el tramo de la misión ([schedule]):
 * [Profile.LEVEL] (altura mínima: vuelve a la altura actual) donde la recta a casa queda libre, y [Profile.CLIMB]
 * (por encima del punto más alto que puede alcanzar una pala, con el rotor en cualquier posición) donde no.
 * Cada tramo se elige con margen por delante y por detrás, así que el cambio llega al dron antes de hacer falta.
 *
 * Si el punto está a menos de [NEAR_HOME_M] de casa en horizontal algunos DJI vuelven sin subir: se comprueban los dos
 * perfiles de vuelo y se toma el peor.
 */
object ReturnHomeSafety {
    /** Margen sobre el punto más alto del rotor; cubre la deriva del barómetro sin RTK. */
    const val ROTOR_MARGIN_M = 20.0

    /** Holgura mínima aceptable durante la RTH: por encima del freno de la evasión (3 m) y de las ráfagas. */
    const val MIN_CLEARANCE_M = 5.0

    /** Distancia horizontal a casa por debajo de la cual el dron puede volver sin subir. */
    const val NEAR_HOME_M = 50.0

    /** Altura de RTH mínima habitual de DJI (m sobre el despegue). */
    const val DJI_MIN_RTH_HEIGHT_M = 20

    /**
     * Margen de la misión que debe cubrir la altura de RTH elegida más allá del tramo de su waypoint: por delante, lo
     * que avanza el dron mientras la app detecta el cambio y el dron lo acepta (la misión va a 1 m/s: 6 s); por detrás,
     * el error de posición al decidir qué waypoint tiene más cerca.
     */
    const val LOOK_AHEAD_M = 6.0
    const val LOOK_BACK_M = 2.0

    enum class Leg { CLIMB, CRUISE, DESCENT }

    enum class Profile { LEVEL, CLIMB }

    data class Check(
        val minClearanceM: Double,
        /** Índice del waypoint en el que empieza (o hacia el que va) la RTH más ajustada. */
        val waypointIndex: Int,
        val component: String,
        val leg: Leg,
    ) {
        val isSafe get() = minClearanceM >= MIN_CLEARANCE_M
    }

    /** Alturas de RTH (m sobre el despegue) de cada perfil. */
    data class Heights(val levelM: Int, val climbM: Int) {
        fun of(p: Profile) = if (p == Profile.LEVEL) levelM else climbM
    }

    /** Perfil de RTH para cada waypoint (el que el dron tiene más cerca) y la peor holgura con esa elección. */
    data class Schedule(val heights: Heights, val profiles: List<Profile>, val worst: Check) {
        val isSafe get() = worst.isSafe
        fun profileAt(index: Int) = profiles[index.coerceIn(0, profiles.lastIndex)]
        fun heightAt(index: Int) = heights.of(profileAt(index))
    }

    /** Punto más alto del disco del rotor (z local): raíz + largo de pala, sea cual sea la posición de las palas. */
    fun rotorTopZ(t: WindTurbine): Double = t.bladeRoot.z + t.dims.bladeLength

    /** Altura de RTH por encima del rotor, en metros sobre el punto de despegue ([home]), redondeada hacia arriba. */
    fun climbHeightAboveHomeM(t: WindTurbine, home: Vec3): Int = ceil(rotorTopZ(t) + ROTOR_MARGIN_M - home.z).toInt()

    /** Altura de RTH por encima del rotor antes de medir: con la altura de buje y el largo de pala, despegue al pie de la torre. */
    fun climbHeightBeforeMeasuringM(hubHeightM: Double, bladeLengthM: Double): Int = ceil(hubHeightM + bladeLengthM + ROTOR_MARGIN_M).toInt()

    /** Trayectorias posibles de la RTH desde [start] (subida, crucero y bajada; y sin subir si está cerca de casa). */
    fun paths(start: Vec3, home: Vec3, rthZ: Double): List<List<Pair<Leg, Pair<Vec3, Vec3>>>> {
        val top = max(rthZ, start.z)
        val climbed = listOf(
            Leg.CLIMB to (start to Vec3(start.x, start.y, top)),
            Leg.CRUISE to (Vec3(start.x, start.y, top) to Vec3(home.x, home.y, top)),
            Leg.DESCENT to (Vec3(home.x, home.y, top) to home),
        )
        if (hypot(home.x - start.x, home.y - start.y) > NEAR_HOME_M) return listOf(climbed)
        val level = listOf(
            Leg.CRUISE to (start to Vec3(home.x, home.y, start.z)),
            Leg.DESCENT to (Vec3(home.x, home.y, start.z) to home),
        )
        return listOf(climbed, level)
    }

    /**
     * Holgura mínima de la RTH que empieza en [start]. Avanza a pasos de al menos [sampleStepM]; lejos de la turbina el
     * paso crece con la distancia (la distancia cambia como mucho lo que se avanza), sin saltarse un acercamiento por
     * debajo de [MIN_CLEARANCE_M].
     */
    fun clearance(t: WindTurbine, start: Vec3, home: Vec3, rthZ: Double, index: Int = 0, sampleStepM: Double = 1.0): Check {
        var best = Check(Double.MAX_VALUE, index, "", Leg.CLIMB)
        for (path in paths(start, home, rthZ)) for ((leg, seg) in path) {
            val (a, b) = seg
            val len = (b - a).norm
            var s = 0.0
            while (true) {
                val (d, name) = t.distance(if (len < 1e-9) a else a + (b - a) * (min(s, len) / len))
                if (d < best.minClearanceM) best = Check(d, index, name, leg)
                if (s >= len) break
                s += max(sampleStepM, (d - MIN_CLEARANCE_M) / 2.0)
            }
        }
        return best
    }

    /** Puntos de la misión cada [stepM] metros: posición, distancia recorrida y waypoint más cercano en ese tramo. */
    private data class Sample(val pos: Vec3, val along: Double)

    private fun samples(waypoints: List<Vec3>, stepM: Double): Pair<List<Sample>, DoubleArray> {
        val out = ArrayList<Sample>()
        val at = DoubleArray(waypoints.size)
        var along = 0.0
        out += Sample(waypoints[0], 0.0)
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]; val b = waypoints[i]
            val len = (b - a).norm
            val n = max(1, ceil(len / stepM).toInt())
            for (k in 1..n) out += Sample(a + (b - a) * (k.toDouble() / n), along + len * k / n)
            along += len
            at[i] = along
        }
        return out to at
    }

    /** Holgura mínima con una sola altura de RTH en toda la misión (la configuración de la app auditada). */
    fun check(t: WindTurbine, waypoints: List<Vec3>, home: Vec3, rthHeightAboveHomeM: Double, startStepM: Double = 2.0): Check {
        require(waypoints.isNotEmpty()) { "plan without waypoints" }
        val (pts, at) = samples(waypoints, startStepM)
        return pts.map { p -> clearance(t, p.pos, home, home.z + rthHeightAboveHomeM, nearestIndex(at, p.along)) }.minBy { it.minClearanceM }
    }

    private fun nearestIndex(at: DoubleArray, along: Double): Int {
        var best = 0
        for (i in at.indices) if (kotlin.math.abs(at[i] - along) < kotlin.math.abs(at[best] - along)) best = i
        return best
    }

    /**
     * Elige el perfil de RTH para cada waypoint. El waypoint i es el que el dron tiene más cerca desde la mitad del tramo
     * anterior hasta la mitad del siguiente; ahí, ampliado [LOOK_BACK_M] hacia atrás y [LOOK_AHEAD_M] hacia delante, la
     * RTH debe ser segura. Se mantiene el perfil anterior mientras sirva (pocos cambios) y se empieza con
     * [Profile.LEVEL] (menos subida, menos batería).
     */
    fun schedule(t: WindTurbine, waypoints: List<Vec3>, home: Vec3, heights: Heights, startStepM: Double = 2.0): Schedule {
        require(waypoints.isNotEmpty()) { "plan without waypoints" }
        val (pts, at) = samples(waypoints, startStepM)
        val byProfile = Profile.values().associateWith { p ->
            pts.map { clearance(t, it.pos, home, home.z + heights.of(p)) }
        }
        val profiles = ArrayList<Profile>(waypoints.size)
        var worst = Check(Double.MAX_VALUE, 0, "", Leg.CLIMB)
        var current = Profile.LEVEL
        var lo = 0
        for (i in waypoints.indices) {
            val from = (at[max(0, i - 1)] + at[i]) / 2 - LOOK_BACK_M
            val to = (at[i] + at[min(waypoints.lastIndex, i + 1)]) / 2 + LOOK_AHEAD_M
            while (lo < pts.size && pts[lo].along < from) lo++
            fun windowMin(p: Profile): Check {
                val c = byProfile.getValue(p)
                var m = Check(Double.MAX_VALUE, i, "", Leg.CLIMB)
                var k = lo
                while (k < pts.size && pts[k].along <= to) { if (c[k].minClearanceM < m.minClearanceM) m = c[k].copy(waypointIndex = i); k++ }
                return m
            }
            val keep = windowMin(current)
            val chosen = if (keep.isSafe) current else {
                val other = if (current == Profile.LEVEL) Profile.CLIMB else Profile.LEVEL
                val alt = windowMin(other)
                if (alt.isSafe || alt.minClearanceM > keep.minClearanceM) other else current
            }
            current = chosen
            profiles += chosen
            val c = windowMin(chosen)
            if (c.minClearanceM < worst.minClearanceM) worst = c
        }
        return Schedule(heights, profiles, worst)
    }

    /** Distancias (m desde el eje de la torre, frente al rotor) que se prueban al buscar dónde poner casa. */
    val SAFE_HOME_CANDIDATES_M: List<Double> = (40..150 step 10).map { it.toDouble() }

    /** Punto a [distanceM] m frente al rotor (del lado del cono), medido desde el eje de la torre, a la altura [z]. */
    fun homeAhead(t: WindTurbine, distanceM: Double, z: Double): Vec3 =
        Vec3(t.towerTop.x, t.towerTop.y, z) + t.turbineDirection * distanceM

    /** Distancia horizontal de [p] al eje de la torre. */
    fun horizontalToTowerM(t: WindTurbine, p: Vec3): Double = hypot(p.x - t.towerTop.x, p.y - t.towerTop.y)

    /** Por qué no es segura la RTH y dónde habría que despegar para que lo sea. */
    data class Diagnosis(
        val worst: Check,
        /** Distancia horizontal de casa al eje de la torre. */
        val homeToTowerM: Double,
        /** Distancia horizontal del waypoint del peor caso a casa. */
        val waypointToHomeM: Double,
        /** Posición del waypoint del peor caso respecto a la torre: + delante del rotor, − detrás. */
        val waypointAheadOfTowerM: Double,
        val waypointZ: Double,
        /** Menor distancia frente al rotor ([SAFE_HOME_CANDIDATES_M]) con la que la misión sería segura; null si ninguna. */
        val safeHomeAheadM: Double?,
    ) {
        /** El peor caso está a menos de [NEAR_HOME_M] de casa: el dron vuelve en línea recta sin subir. */
        val nearHome get() = waypointToHomeM <= NEAR_HOME_M
    }

    /**
     * Diagnóstico de un programa no seguro. [heightsFor] da las alturas de RTH para una casa candidata (null si el dron
     * no admite la altura necesaria). Busca la menor distancia frente al rotor a la que despegar: con casa cerca de la
     * torre, los waypoints de detrás del rotor quedan a menos de [NEAR_HOME_M] y la vuelta sin subir cruza el rotor.
     */
    fun diagnose(t: WindTurbine, waypoints: List<Vec3>, home: Vec3, worst: Check, heightsFor: (Vec3) -> Heights?): Diagnosis {
        val w = waypoints[worst.waypointIndex.coerceIn(0, waypoints.lastIndex)]
        val safe = SAFE_HOME_CANDIDATES_M.firstOrNull { d ->
            val h = homeAhead(t, d, home.z)
            heightsFor(h)?.let { schedule(t, waypoints, h, it).isSafe } ?: false
        }
        return Diagnosis(
            worst = worst,
            homeToTowerM = horizontalToTowerM(t, home),
            waypointToHomeM = hypot(w.x - home.x, w.y - home.y),
            waypointAheadOfTowerM = (w - t.towerTop).dot(t.turbineDirection),
            waypointZ = w.z,
            safeHomeAheadM = safe,
        )
    }
}
