package mx.com.nexusio.fly.drone.onestop.flightcore.sim

import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Qué hace el dron tras perder el enlace con el mando sin recuperarlo, con la evasión de obstáculos en FRENO del One Stop.
 * - [LinkLossConfig.immediateRth] = true: configuración actual del KMZ de Nexus App (`executeRCLostAction = goBack`): vuelve
 *   a casa en el acto, con la altura de RTH que la app dejó puesta para ese tramo ([ReturnHomeSafety.schedule]).
 * - false: configuración anterior (`executeRCLostAction = hover`, la de la app auditada): se mantiene en el sitio hasta
 *   la batería baja.
 *
 * Supuestos (no medidos; documentados en el informe):
 * - Sin enlace, la app no puede pausar ni frenar: solo actúan el dron y su evasión de obstáculos (frena a [brakeDistanceM]).
 * - Sale de la misión (con el mismo seguimiento PI y viento que el simulador de vuelo).
 * - Vuelta a casa (RTH): sube en vertical hasta [rthAltitudeM] (si está más bajo), va en línea recta a casa a
 *   [rthSpeedMps] y baja a [descentMps]; si ya va más alto, vuelve a su altura.
 * - Si durante la RTH algo queda a menos de [brakeDistanceM] y se acerca, el dron frena y queda parado (no rodea).
 * - Con batería ≤ [criticalBatteryPct] aterriza en vertical donde esté, sin poder cancelarlo.
 */
data class LinkLossConfig(
    val dt: Double = 0.1,
    val rthAltitudeM: Double = 100.0,
    val immediateRth: Boolean = false,
    val climbMps: Double = 5.0,
    val rthSpeedMps: Double = 10.0,
    val descentMps: Double = 3.0,
    val brakeDistanceM: Double = 3.0,
    val lowBatteryPct: Double = 25.0,
    val criticalBatteryPct: Double = 10.0,
    val contactDistanceM: Double = 0.3,
    val trackKp: Double = 0.6,
    val trackKi: Double = 0.1,
    val maxAirspeedMps: Double = 15.0,
)

enum class FlightState { MISSION, HOVER_NO_LINK, RTH_CLIMB, RTH_CRUISE, RTH_DESCENT, BLOCKED, FORCED_LANDING, LANDED, CONTACT }

data class LossSample(val t: Double, val pos: Vec3, val distance: Double, val battery: Double, val state: FlightState)

data class LinkLossResult(
    val samples: List<LossSample>,
    val events: List<Pair<Double, String>>,
    val minDistance: Double,
    val minComponent: String,
    val outcome: String,
    val endTime: Double,
    val endBattery: Double,
    val contact: Boolean,
)

class LinkLossSimulator(
    private val turbine: WindTurbine,
    private val wind: WindModel?,
    private val home: Vec3,
    private val cfg: LinkLossConfig = LinkLossConfig(),
    /** %/s de batería según el viento a la altura del dron. */
    private val drainPctPerSec: (windMps: Double) -> Double,
) {
    fun run(start: Vec3, t0: Double, battery0: Double): LinkLossResult {
        val rng = java.util.Random(wind?.seed ?: 0L)
        val samples = ArrayList<LossSample>()
        val events = ArrayList<Pair<Double, String>>()
        var t = t0; var battery = battery0
        var target = start
        var e = Vec3.ZERO; var gust = Vec3.ZERO
        var integ = wind?.meanAt(start.z) ?: Vec3.ZERO
        var state = if (cfg.immediateRth) FlightState.RTH_CLIMB else FlightState.HOVER_NO_LINK
        var minD = Double.MAX_VALUE; var minName = ""
        var prevD = turbine.distance(start).first
        var contact = false
        events += t to if (cfg.immediateRth)
            "Enlace perdido: el dron sale de la misión y vuelve a casa; sube en vertical a %.0f m si va más bajo".format(cfg.rthAltitudeM)
        else "Enlace perdido: el dron sale de la misión y se mantiene en el sitio (la app ya no puede pausar ni frenar)"

        fun step() {
            if (wind != null) {
                val z = target.z
                gust = wind.stepGust(gust, cfg.dt, z, rng)
                var u = e * cfg.trackKp + integ
                if (u.norm > cfg.maxAirspeedMps) u = u * (cfg.maxAirspeedMps / u.norm)
                e += (wind.meanAt(z) + gust - u) * cfg.dt
                integ += e * (cfg.trackKi * cfg.dt)
                if (integ.norm > cfg.maxAirspeedMps) integ = integ * (cfg.maxAirspeedMps / integ.norm)
            }
            t += cfg.dt
            battery = max(0.0, battery - drainPctPerSec(wind?.speedAt(target.z) ?: 0.0) * cfg.dt)
        }

        var guard = 0
        while (state != FlightState.LANDED && state != FlightState.CONTACT && guard++ < 200_000) {
            val pos = target + e
            val (d, name) = turbine.distance(pos)
            if (d < minD) { minD = d; minName = name }
            samples += LossSample(t, pos, d, battery, state)
            if (d < cfg.contactDistanceM) {
                contact = true; state = FlightState.CONTACT
                events += t to "Contacto con la turbina ($name) en fase ${samples.last().state}"
                break
            }
            val approaching = d < prevD - 1e-6
            prevD = d

            // Transiciones por batería
            if (battery <= cfg.criticalBatteryPct && state != FlightState.FORCED_LANDING) {
                state = FlightState.FORCED_LANDING
                events += t to "Batería crítica (%.0f %%): aterrizaje forzado en vertical donde esté".format(battery)
            } else if (battery <= cfg.lowBatteryPct && state == FlightState.HOVER_NO_LINK) {
                state = FlightState.RTH_CLIMB
                events += t to "Batería baja (%.0f %%): vuelta a casa; sube en vertical a %.0f m".format(battery, max(cfg.rthAltitudeM, target.z))
            }

            // Freno de la evasión de obstáculos del dron durante la RTH
            if (state in setOf(FlightState.RTH_CLIMB, FlightState.RTH_CRUISE, FlightState.RTH_DESCENT) && d < cfg.brakeDistanceM && approaching) {
                state = FlightState.BLOCKED
                events += t to "La evasión de obstáculos frena a %.1f m de %s: la vuelta a casa queda detenida".format(d, name)
            }

            when (state) {
                FlightState.HOVER_NO_LINK, FlightState.BLOCKED -> Unit
                FlightState.RTH_CLIMB -> {
                    val top = max(cfg.rthAltitudeM, start.z)
                    target = Vec3(target.x, target.y, min(top, target.z + cfg.climbMps * cfg.dt))
                    if (target.z >= top - 1e-9) { state = FlightState.RTH_CRUISE; events += t to "Altura de vuelta alcanzada: va en línea recta a casa" }
                }
                FlightState.RTH_CRUISE -> {
                    val dx = home.x - target.x; val dy = home.y - target.y
                    val h = hypot(dx, dy)
                    val stepLen = cfg.rthSpeedMps * cfg.dt
                    target = if (h <= stepLen) Vec3(home.x, home.y, target.z) else Vec3(target.x + dx / h * stepLen, target.y + dy / h * stepLen, target.z)
                    if (h <= stepLen) { state = FlightState.RTH_DESCENT; events += t to "Sobre el punto de despegue: desciende" }
                }
                FlightState.RTH_DESCENT, FlightState.FORCED_LANDING -> {
                    target = Vec3(target.x, target.y, max(0.0, target.z - cfg.descentMps * cfg.dt))
                    if (target.z <= 0.0) {
                        val where = if (hypot(target.x - home.x, target.y - home.y) < 2.0) "en el punto de despegue" else "lejos de casa (a %.0f m)".format(hypot(target.x - home.x, target.y - home.y))
                        state = FlightState.LANDED; events += t to "Aterrizado $where"
                    }
                }
                else -> Unit
            }
            step()
        }
        val outcome = when {
            contact -> "Contacto con la turbina"
            events.any { it.second.startsWith("La evasión") } && events.any { it.second.startsWith("Batería crítica") } ->
                "Vuelta a casa bloqueada; aterrizaje forzado sin contacto"
            else -> "Vuelve a casa y aterriza sin contacto"
        }
        return LinkLossResult(samples, events, minD, minName, outcome, t, battery, contact)
    }
}
