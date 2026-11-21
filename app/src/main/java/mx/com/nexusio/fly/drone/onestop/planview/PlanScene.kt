package mx.com.nexusio.fly.drone.onestop.planview

import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import mx.com.nexusio.fly.drone.onestop.flightcore.Waypoint3D
import mx.com.nexusio.fly.drone.onestop.flightcore.WindTurbine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Pieza de la turbina, para darle color en el visor (una por pala, gris torre/góndola). */
enum class WireKind { BLADE_4, BLADE_12, BLADE_8, TOWER, NACELLE, HUB }

/** Lo que el visor necesita de un waypoint: posición local, si el dron para y si dispara. */
data class PlanPoint(val pos: Vec3, val stop: Boolean, val photo: Boolean)

/**
 * Escena del visor de plan (lógica pura, sin Android): turbina en alambre, ruta y waypoints en el sistema local
 * del plan (ENU, metros, origen en el punto de medición del buje), con cámara orbital ortográfica y encuadre automático.
 */
class PlanScene(turbine: WindTurbine, waypoints: List<Waypoint3D>, stops: List<Boolean>) {
    /** Suelo: el pie de la torre. El origen del plan es el dron al medir el spinner, a la altura del buje. */
    val ground: Vec3 = turbine.towerBottom

    data class Segment(val a: Vec3, val b: Vec3)
    data class Screen(val x: Float, val y: Float, val depth: Double)

    val points: List<PlanPoint> = waypoints.mapIndexed { i, w -> PlanPoint(w.point, stops.getOrElse(i) { false }, w.actions.any { it.takePhoto }) }

    /** Alambre de cada tronco de cono: anillos de 20 lados en los extremos y una generatriz cada 4. */
    val wire: Map<WireKind, List<Segment>> = turbine.collisionSolids.groupBy { kindOf(it.name) }.mapValues { (_, solids) ->
        solids.flatMap { s ->
            val axis = (s.top - s.base).normalize()
            val r0 = ring(s.base, axis, s.rBase); val r1 = ring(s.top, axis, s.rTop)
            buildList {
                for (i in 0 until SIDES) {
                    val j = (i + 1) % SIDES
                    if (s.rBase > 0.01) add(Segment(r0[i], r0[j]))
                    if (s.rTop > 0.01) add(Segment(r1[i], r1[j]))
                    if (i % 4 == 0) add(Segment(r0[i], r1[i]))
                }
            }
        }
    }

    val bladeTips: Map<BladePosition, Vec3> = listOf(BladePosition.FOUR, BladePosition.TWELVE, BladePosition.EIGHT).associateWith { turbine.bladeTip(it) }
    private val bladeRoot = turbine.bladeRoot

    /** Rumbo (0 = norte, horario) hacia el que mira el rotor. */
    val rotorFacingDeg: Double = turbine.turbineDirection.let { (Math.toDegrees(atan2(it.x, it.y)) + 360.0) % 360.0 }

    // ── cámara orbital ──
    var azimuth = Math.toRadians(215.0); private set
    var elevation = Math.toRadians(18.0); private set
    var zoom = 1.0; private set

    enum class Preset(val azDeg: Double, val elDeg: Double) { FRONT(180.0, 4.0), SIDE(270.0, 4.0), TOP(180.0, 89.0), ISO(215.0, 18.0) }

    /** Vistas fijas relativas al rotor: "frente" es siempre delante del rotor, mire donde mire. */
    fun preset(p: Preset) {
        azimuth = Math.toRadians(p.azDeg + rotorFacingDeg); elevation = Math.toRadians(p.elDeg); zoom = 1.0
        panX = 0.0; panY = 0.0
    }

    /** Arrastre de [dx], [dy] píxeles: a la derecha gira la escena a la derecha; hacia arriba la inclina hacia arriba. */
    fun rotateBy(dxPx: Float, dyPx: Float) {
        azimuth += dxPx * ROTATE_PER_PX
        elevation = (elevation - dyPx * ROTATE_PER_PX).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    fun zoomBy(factor: Double) { zoom = (zoom * factor).coerceIn(0.5, 8.0) }

    /** Desplazamiento de la vista en píxeles (dos dedos): sube, baja o se mueve a los lados por la turbina. */
    var panX = 0.0; private set
    var panY = 0.0; private set

    fun panBy(dxPx: Float, dyPx: Float) { panX += dxPx; panY += dyPx }

    // ── proyección ──
    private val center: Vec3
    private var fit = Fit(1.0, 0.0, 0.0, 0f, 0f)
    private data class Fit(val scale: Double, val midX: Double, val midY: Double, val cx: Float, val cy: Float)

    init {
        preset(Preset.ISO)
        val all = fitPoints()
        center = Vec3((all.minOf { it.x } + all.maxOf { it.x }) / 2, (all.minOf { it.y } + all.maxOf { it.y }) / 2, (all.minOf { it.z } + all.maxOf { it.z }) / 2)
    }

    private fun fitPoints() = wire.values.flatten().flatMap { listOf(it.a, it.b) } + points.map { it.pos }

    /** [horizontal en pantalla, vertical en pantalla, profundidad] en metros, con la rotación actual. */
    private fun rotated(p: Vec3): DoubleArray {
        val dx = p.x - center.x; val dy = p.y - center.y; val dz = p.z - center.z
        val ca = cos(azimuth); val sa = sin(azimuth); val ce = cos(elevation); val se = sin(elevation)
        val x1 = dx * ca - dy * sa; val depth = dx * sa + dy * ca
        return doubleArrayOf(x1, dz * ce + depth * se, depth * ce - dz * se)
    }

    /** Encuadra turbina y ruta en [width] × [height] dejando márgenes; llamar antes de proyectar en cada dibujo. */
    fun layout(width: Int, height: Int, padSide: Float, padTop: Float, padBottom: Float) {
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (p in fitPoints()) { val r = rotated(p); x0 = min(x0, r[0]); x1 = max(x1, r[0]); y0 = min(y0, r[1]); y1 = max(y1, r[1]) }
        val s = min((width - 2 * padSide) / max(1.0, x1 - x0), (height - padTop - padBottom) / max(1.0, y1 - y0)) * zoom
        fit = Fit(s, (x0 + x1) / 2, (y0 + y1) / 2, width / 2f, padTop + (height - padTop - padBottom) / 2f)
    }

    fun project(p: Vec3): Screen {
        val r = rotated(p)
        return Screen((fit.cx + panX + (r[0] - fit.midX) * fit.scale).toFloat(), (fit.cy + panY - (r[1] - fit.midY) * fit.scale).toFloat(), r[2])
    }

    /** Píxeles por metro del encuadre actual. */
    val pixelsPerMeter: Double get() = fit.scale

    // ── orientación ──
    /** Rumbo desde el que mira el observador (dónde está la cámara de la vista respecto a la turbina). */
    val viewerBearingDeg: Double get() = (Math.toDegrees(atan2(-sin(azimuth), -cos(azimuth))) + 360.0) % 360.0

    enum class Side { FRONT, BEHIND, LATERAL, TOP }

    val side: Side get() {
        if (elevation > Math.toRadians(77.0)) return Side.TOP
        val diff = abs(((viewerBearingDeg - rotorFacingDeg + 540.0) % 360.0) - 180.0)
        return when { diff < 50.0 -> Side.FRONT; diff > 130.0 -> Side.BEHIND; else -> Side.LATERAL }
    }

    // ── avance ──
    /**
     * Waypoint hacia el que va el dron: el más cercano a [drone] sin retroceder respecto a [lastIndex]
     * (busca en una ventana por delante para no saltar a un tramo vecino de otra cara de la pala).
     */
    fun progressIndex(drone: Vec3, lastIndex: Int): Int {
        if (points.isEmpty()) return 0
        val from = lastIndex.coerceIn(0, points.lastIndex)
        val to = min(points.lastIndex, from + LOOKAHEAD)
        var best = from; var bestD = Double.MAX_VALUE
        for (i in from..to) { val d = (points[i].pos - drone).norm; if (d < bestD) { bestD = d; best = i } }
        return best
    }

    /** Extremo de la pala un poco más allá de la punta (para la etiqueta con su nombre). */
    fun bladeLabelPoint(b: BladePosition): Vec3 { val tip = bladeTips.getValue(b); return tip + (tip - bladeRoot) * 0.08 }

    companion object {
        const val SIDES = 20
        const val ROTATE_PER_PX = 0.008
        const val LOOKAHEAD = 12
        val MIN_ELEVATION = -0.1
        val MAX_ELEVATION = 1.55

        fun kindOf(name: String) = when (name) {
            "Blade-FOUR" -> WireKind.BLADE_4
            "Blade-TWELVE" -> WireKind.BLADE_12
            "Blade-EIGHT" -> WireKind.BLADE_8
            "Tower" -> WireKind.TOWER
            "Nacelle" -> WireKind.NACELLE
            else -> WireKind.HUB
        }

        /** Punto cardinal (8 rumbos) de un rumbo en grados. */
        fun cardinal(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[((((deg % 360) + 360) % 360) / 45.0).roundToInt() % 8]

        private fun ring(c: Vec3, axis: Vec3, r: Double): List<Vec3> {
            val ref = if (abs(axis.z) < 0.9) Vec3(0.0, 0.0, 1.0) else Vec3(1.0, 0.0, 0.0)
            val u = (axis cross ref).normalize(); val w = axis cross u
            return List(SIDES) { i -> val a = 2 * PI * i / SIDES; c + u * (r * cos(a)) + w * (r * sin(a)) }
        }
    }
}
