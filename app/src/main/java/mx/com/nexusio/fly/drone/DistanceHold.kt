package mx.com.nexusio.fly.drone

import kotlin.math.abs

/**
 * Mantiene al dron a [target] m de la pala en el Three Stop con el láser y los sensores de visión trabajando juntos.
 *
 * El láser es la fuente principal. En los bordes de la pala (sobre todo en la punta) se pierde a cada rato; entonces la
 * visión lo acompaña: mientras ven los dos se aprende la diferencia entre ambos ([offset]) y, cuando el láser se
 * pierde, la distancia sigue con la visión más ese ajuste, así no hay saltos al cambiar de fuente. Cada fuente descarta
 * los picos sueltos (una lectura distinta solo se acepta si se mantiene [CONFIRM_MS]) y las lecturas de más de
 * [MAX_RANGE_M] (el fondo, no la pala). Sin ninguna de las dos más de [STALE_MS] no se inventa una distancia: no hay
 * corrección automática y el piloto acerca o aleja con el stick.
 *
 * Reemplaza al filtro anterior, que descartaba toda lectura a más de 0,4 m de la última "válida" y partía de 3,8 m: si
 * el dron arrancaba a 4,3 m, o el viento lo corría medio metro, el control creía seguir a 3,8 m y no corregía.
 */
class DistanceHold(private val target: Float = 3.8f) {
    enum class Source { LASER, VISION, NONE }

    companion object {
        /** Salto máximo entre lecturas seguidas de una fuente que se acepta de inmediato. */
        const val JUMP_M = 0.4f
        /** Las lecturas de un cambio real deben quedar dentro de esta banda entre sí... */
        const val CONFIRM_BAND_M = 0.25f
        /** ...durante este tiempo para aceptarlas. */
        const val CONFIRM_MS = 600L
        /** El láser cuenta como "viendo" si su última lectura aceptada tiene menos de esto. */
        const val LASER_FRESH_MS = 500L
        const val VISION_FRESH_MS = 500L
        /** Margen extra para seguir con la última lectura del láser si tampoco hay visión. */
        const val STALE_MS = 1000L
        /** Las dos fuentes se comparan solo si ambas tienen una lectura de menos de esto. */
        const val PAIR_MS = 300L
        const val OFFSET_ALPHA = 0.1f
        const val MAX_OFFSET_M = 0.8f
        /** Más lejos que esto lo que se ve es el fondo, no la pala. */
        const val MAX_RANGE_M = 10f
        const val MIN_RANGE_M = 0.3f
        const val GAIN_CLOSER = 0.4
        const val MAX_RETREAT = 0.8
        const val GAIN_FARTHER = 0.25
        const val MAX_APPROACH = 0.6
        /** La visión es menos precisa que el láser: con ella solo, la corrección se limita más. */
        const val VISION_MAX_RETREAT = 0.5
        const val VISION_MAX_APPROACH = 0.4
    }

    /** Una fuente de distancia con su filtro de picos. */
    private class Channel {
        var valid: Float? = null
        var validAt = 0L
        private var candidate: Float? = null
        private var candidateSince = 0L
        private var lastStamp = Long.MIN_VALUE

        /** @return true si la lectura se aceptó. */
        fun feed(raw: Float, stampMs: Long): Boolean {
            if (raw < MIN_RANGE_M || raw > MAX_RANGE_M || stampMs == lastStamp) return false
            lastStamp = stampMs
            val v = valid
            if (v == null || abs(raw - v) <= JUMP_M) { accept(raw, stampMs); return true }
            val c = candidate
            if (c == null || abs(raw - c) > CONFIRM_BAND_M) {
                candidate = raw; candidateSince = stampMs
            } else if (stampMs - candidateSince >= CONFIRM_MS) {
                accept(raw, stampMs); return true
            }
            return false
        }

        private fun accept(raw: Float, stampMs: Long) { valid = raw; validAt = stampMs; candidate = null }

        fun value(nowMs: Long, maxAgeMs: Long): Float? = valid?.takeIf { nowMs - validAt <= maxAgeMs }

        fun reset() { valid = null; candidate = null; lastStamp = Long.MIN_VALUE }
    }

    private val laser = Channel()
    private val vision = Channel()
    /** Láser menos visión, suavizado: lo que hay que sumarle a la visión para que coincida con el láser. */
    var offset = 0f
        private set

    /** Una lectura del láser con su hora ([stampMs], la del propio láser). [raw] <= 0 o una hora repetida no cuentan. */
    fun feedLaser(raw: Float, stampMs: Long) { if (laser.feed(raw, stampMs)) learnOffset(stampMs) }

    /** Distancia al frente según los sensores de visión (mínimo de los sectores frontales), con su hora. */
    fun feedVision(raw: Float, stampMs: Long) { if (vision.feed(raw, stampMs)) learnOffset(stampMs) }

    private fun learnOffset(nowMs: Long) {
        val l = laser.value(nowMs, PAIR_MS) ?: return
        val v = vision.value(nowMs, PAIR_MS) ?: return
        offset = (offset + OFFSET_ALPHA * ((l - v) - offset)).coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M)
    }

    fun source(nowMs: Long): Source = when {
        laser.value(nowMs, LASER_FRESH_MS) != null -> Source.LASER
        vision.value(nowMs, VISION_FRESH_MS) != null -> Source.VISION
        laser.value(nowMs, STALE_MS) != null -> Source.LASER
        else -> Source.NONE
    }

    /** Distancia vigente a la pala, o null si ni el láser ni la visión la ven (el piloto controla con el stick). */
    fun distance(nowMs: Long): Float? = when (source(nowMs)) {
        Source.LASER -> laser.value(nowMs, STALE_MS)
        Source.VISION -> vision.value(nowMs, VISION_FRESH_MS)?.let { (it + offset).coerceAtLeast(MIN_RANGE_M) }
        Source.NONE -> null
    }

    /** Velocidad hacia la pala (m/s, positiva = acercarse) para volver a [target]; 0 sin lectura útil. */
    fun velocity(dist: Float?, viaVision: Boolean = false): Double {
        if (dist == null || dist <= 0f || dist >= MAX_RANGE_M) return 0.0
        val error = dist - target
        val maxRetreat = if (viaVision) VISION_MAX_RETREAT else MAX_RETREAT
        val maxApproach = if (viaVision) VISION_MAX_APPROACH else MAX_APPROACH
        return if (error < 0) (error * GAIN_CLOSER).coerceIn(-maxRetreat, 0.0) else (error * GAIN_FARTHER).coerceIn(0.0, maxApproach)
    }

    fun reset() { laser.reset(); vision.reset(); offset = 0f }
}
