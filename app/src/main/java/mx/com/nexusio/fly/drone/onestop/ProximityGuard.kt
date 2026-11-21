package mx.com.nexusio.fly.drone.onestop

/**
 * Freno por proximidad durante la misión: pide pausar si algún sensor ve un objeto más cerca que [minDistanceM].
 *
 * Por qué es seguro usar cualquier sensor: la distancia de un rayo (láser o sector de visión) hasta lo que toca es siempre
 * mayor o igual que la distancia real al punto más cercano. Si un rayo mide menos de [minDistanceM], la turbina está
 * de verdad más cerca que eso. El planificador garantiza ≥ 4.5 m a la turbina; por debajo de 4.0 m el dron se ha desviado
 * del plan (viento, error de medición), por eso ese es el valor por defecto.
 *
 * Hace falta que la condición dure [confirmTicks] ticks seguidos para no frenar por una sola lectura espuria.
 */
class ProximityGuard(
    private val minDistanceM: Double = 4.0,
    private val confirmTicks: Int = 3,
) {
    private var ticksBelow = 0

    /** Distancia más cercana válida de este tick, o null si ningún sensor dio un valor útil. */
    var closestM: Double? = null
        private set

    /**
     * Procesa un tick con las distancias de los sensores (m). Los valores ≤ 0 se consideran "sin lectura"
     * (convención de [mx.com.nexusio.fly.drone.onestop.flightcore.DroneStateProvider]). Devuelve true si hay que frenar.
     */
    fun update(vararg distancesM: Double): Boolean {
        closestM = distancesM.filter { it > 0.0 && it.isFinite() }.minOrNull()
        val close = closestM?.let { it < minDistanceM } ?: false
        ticksBelow = if (close) ticksBelow + 1 else 0
        return ticksBelow >= confirmTicks
    }

    fun reset() {
        ticksBelow = 0
        closestM = null
    }
}
