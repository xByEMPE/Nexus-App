package mx.com.nexusio.fly.drone.onestop.flightcore

/** Tronco aproximado por [n] cilindros de radio constante (escalonado), como MultiCylinder3D del original. */
class MultiCylinder(private val base: Vec3, private val top: Vec3, baseRadius: Double, topRadius: Double, n: Int) {
    private val cylinders: List<Solid>

    init {
        val len = (top - base).norm
        val dir = (top - base).normalize()
        val interp = LinearInterpolator(0.0, baseRadius, len, topRadius)
        val step = len / n
        cylinders = (0 until n).map { i ->
            val s0 = i * step
            // El radio de cada escalón es el del INICIO del tramo (el original no usa el máximo): el escalón puede
            // quedar ligeramente por debajo del cono real hacia la punta. Se conserva por fidelidad.
            Solid(base + dir * s0, base + dir * ((i + 1) * step), interp.interpolate(s0), interp.interpolate(s0))
        }
    }

    fun touches(p1: Vec3, p2: Vec3) = cylinders.any { it.touches(p1, p2) }
}

enum class TransferObstacle { TOWER, NACELLE, BLADE_TWELVE, BLADE_EIGHT, BLADE_FOUR }
enum class TransferPathLeg { DIAGONAL, HORIZONTAL_FIRST, VERTICAL_FIRST }

sealed interface TransferSafetyResult {
    val isSafe: Boolean
    data object Safe : TransferSafetyResult { override val isSafe = true }
    data class Unsafe(val obstacle: TransferObstacle, val leg: TransferPathLeg) : TransferSafetyResult { override val isSafe = false }
}

/**
 * ¿Es seguro mover el dron entre dos puntos sin cruzar torre, nacelle o palas (con margen pathSafetyDistance)?
 * Se prueban TRES rutas (diagonal, horizontal→vertical, vertical→horizontal) y se declara inseguro si CUALQUIERA cruza
 * un obstáculo: es una comprobación conservadora ("todas las rutas candidatas deben estar libres").
 */
class FlightPathSafetyCheck(
    droneAtSpinner: Vec3,
    bladeLeftTip: Vec3,
    bladeRightTip: Vec3,
    bladeTopTip: Vec3,
    private val bladeTopBottom: Vec3,
    private val spinner: Vec3,
    private val dims: TurbineDimensions,
    private val def: InspectionDefinition,
) {
    private val turbine = WindTurbine(
        dims, TurbineMeasurements(droneAtSpinner, bladeRightTip, bladeTopTip, bladeLeftTip, bladeTopBottom, spinner),
        emptyMap(),
    )
    private val safety get() = def.pathSafetyDistance

    private fun intersectsTower(a: Vec3, b: Vec3): Boolean {
        val r = turbine.dims.towerBottomRadius + safety // radio de la base para toda la altura (conservador)
        return Solid(turbine.towerBottom, turbine.towerTop, r, r).touches(a, b)
    }

    private fun intersectsNacelleOrSpinner(a: Vec3, b: Vec3): Boolean {
        val hubLength = ((spinner - bladeTopBottom).norm - dims.bladeFlangeRadius).coerceIn(0.0, 100.0)
        val dir = turbine.turbineDirection
        val back = turbine.rotorAxisBack - dir * safety
        val front = turbine.rotorAxisFront + dir * (hubLength + safety)
        val r = turbine.dims.nacelleThickness + safety
        return Solid(back, front, r, r).touches(a, b)
    }

    private fun intersectsBlade(a: Vec3, b: Vec3, pos: BladePosition): Boolean {
        val root = turbine.bladeRoot; val tip = turbine.bladeTip(pos)
        val end = tip + (tip - root).normalize() * safety
        return MultiCylinder(root, end, dims.bladeFlangeRadius + safety, safety, 10).touches(a, b)
    }

    fun checkTransfer(drone: Vec3, target: Vec3): TransferSafetyResult {
        val h = Vec3(target.x, target.y, drone.z)   // primero en horizontal
        val v = Vec3(drone.x, drone.y, target.z)    // primero en vertical
        val legs = listOf(
            TransferPathLeg.DIAGONAL to listOf(drone to target),
            TransferPathLeg.HORIZONTAL_FIRST to listOf(drone to h, h to target),
            TransferPathLeg.VERTICAL_FIRST to listOf(drone to v, v to target),
        )
        for ((leg, segs) in legs) for ((a, b) in segs) {
            if (intersectsTower(a, b)) return TransferSafetyResult.Unsafe(TransferObstacle.TOWER, leg)
            if (intersectsNacelleOrSpinner(a, b)) return TransferSafetyResult.Unsafe(TransferObstacle.NACELLE, leg)
            if (intersectsBlade(a, b, BladePosition.TWELVE)) return TransferSafetyResult.Unsafe(TransferObstacle.BLADE_TWELVE, leg)
            if (intersectsBlade(a, b, BladePosition.EIGHT)) return TransferSafetyResult.Unsafe(TransferObstacle.BLADE_EIGHT, leg)
            if (intersectsBlade(a, b, BladePosition.FOUR)) return TransferSafetyResult.Unsafe(TransferObstacle.BLADE_FOUR, leg)
        }
        return TransferSafetyResult.Safe
    }

    fun isTransferSafe(drone: Vec3, target: Vec3) = checkTransfer(drone, target).isSafe
}
