package mx.com.nexusio.fly.drone

/** Severidad de una incidencia de salud del dron (independiente del SDK de DJI). */
enum class Severity { NOTICE, CAUTION, WARNING, SERIOUS }

/** Estado de una tarjeta del sistema de gestión sanitaria VANT. */
enum class HealthState { NO_DATA, NORMAL, WARNING, FAILURE }

object HealthStatus {

    fun of(received: Boolean, severities: List<Severity>): HealthState = when {
        !received -> HealthState.NO_DATA
        severities.isEmpty() -> HealthState.NORMAL
        severities.any { it == Severity.WARNING || it == Severity.SERIOUS } -> HealthState.FAILURE
        else -> HealthState.WARNING
    }

    fun label(state: HealthState, count: Int): String = when (state) {
        HealthState.NO_DATA -> "Buscando…"
        HealthState.NORMAL -> "Normal"
        HealthState.WARNING -> if (count == 1) "1 aviso" else "$count avisos"
        HealthState.FAILURE -> if (count == 1) "1 falla" else "$count fallas"
    }
}
