package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Medidas que el piloto toma con el dron (en coordenadas locales): tres puntas de pala, parte inferior de la pala a las 12 y el spinner. */
data class TurbineMeasurements(
    val droneAtSpinner: Vec3,
    val blade4Tip: Vec3,
    val blade12Tip: Vec3,
    val blade8Tip: Vec3,
    val blade12Bottom: Vec3,
    val spinner: Vec3,
) {
    fun translated(by: Vec3) = TurbineMeasurements(droneAtSpinner + by, blade4Tip + by, blade12Tip + by, blade8Tip + by, blade12Bottom + by, spinner + by)

    /** Gira todo el conjunto [degrees] (mano derecha, antihorario visto desde arriba) alrededor del eje vertical que pasa por el origen. */
    fun rotatedAboutZ(degrees: Double): TurbineMeasurements {
        fun r(v: Vec3) = Geo3D.rotate(v, Vec3.Z, Angle.fromDegrees(degrees))
        return TurbineMeasurements(r(droneAtSpinner), r(blade4Tip), r(blade12Tip), r(blade8Tip), r(blade12Bottom), r(spinner))
    }
}

private data class TurbineAngles(val rotorIndex: Angle, val rotorAxis: Angle, val bladeTilt: Angle)

/**
 * Modelo geométrico de la turbina reconstruido a partir de las mediciones y las dimensiones nominales.
 * Es el objeto que consume el planificador para (a) ubicar raíz/puntas y (b) medir distancias de seguridad.
 */
class WindTurbine(
    dimensions: TurbineDimensions,
    m: TurbineMeasurements,
    private val componentOf: Map<BladePosition, ComponentIndex>,
) {
    val bladeRoot: Vec3
    val rotorAxisDirection: Vec3
    val rotorIndexAngle: Angle
    val spinner: Vec3 = m.spinner
    val towerBottom: Vec3
    val towerTop: Vec3
    val dims: TurbineDimensions
    private val tips: Map<BladePosition, Vec3>

    init {
        // 1ª pasada: eje del rotor con las puntas sin corregir → raíz de pala
        val axis0 = fitRotorAxis(m.droneAtSpinner, m.blade4Tip, m.blade12Tip, m.blade8Tip, m.spinner)
        val root = Line3(m.blade12Tip, (m.blade12Bottom - axis0 * dimensions.bladeFlangeRadius - m.blade12Tip).normalize())
            .closestPointTo(m.spinner)
        // 2ª pasada: igualar radios y recalcular el eje
        val (s4, s12, s8) = scaleToEqualRadius(m.blade4Tip, m.blade12Tip, m.blade8Tip, root)
        val axis = fitRotorAxis(m.droneAtSpinner, s4, s12, s8, m.spinner)
        val dirH = axis.horizontal().normalize()
        val hubLength = (( m.spinner - root).norm - dimensions.bladeFlangeRadius).coerceIn(0.0, 100.0)

        // La punta medida está en el borde de ataque: se retrocede el espesor local para apuntar al eje de la pala.
        val bt = BladeThickness(dimensions)
        fun adj(tip: Vec3) = tip - dirH * bt.thicknessAt((tip - root).norm, PathIndex.LE)
        val a4 = adj(m.blade4Tip); val a12 = adj(m.blade12Tip); val a8 = adj(m.blade8Tip)
        val ang = turbineAngles(a4, a12, a8, root, axis)
        // (el original rotula el 1.er límite como "rotor axis angle" pero comprueba el ángulo de índice del rotor)
        MissionPlanningException.require(abs(ang.rotorIndex.degrees) <= 5.0,
            "rotor axis angle too large: ${ang.rotorIndex.degrees} degrees (limit is 5.0 degrees)")
        MissionPlanningException.require(abs(ang.bladeTilt.degrees) <= 10.0,
            "blade tilt angle too large: ${ang.bladeTilt.degrees} degrees (limit is 10.0 degrees)")

        val len = dimensions.bladeLength
        val base = root - dirH * dimensions.towerCenterToHubCenter - Vec3.Z * dimensions.towerHeight
        dims = dimensions.copy(hubLength = hubLength, bladeTiltAngle = ang.bladeTilt.degrees, rotorAxisAngle = ang.rotorAxis.degrees)
        rotorIndexAngle = ang.rotorIndex
        rotorAxisDirection = axis
        bladeRoot = root
        towerBottom = base
        towerTop = base + Vec3.Z * dims.towerHeight
        tips = mapOf(
            BladePosition.FOUR to root + (a4 - root).normalize() * len,
            BladePosition.TWELVE to root + (a12 - root).normalize() * len,
            BladePosition.EIGHT to root + (a8 - root).normalize() * len,
        )
    }

    fun bladeTip(p: BladePosition): Vec3 {
        MissionPlanningException.require(p != BladePosition.UNDEFINED, "valid blade position required (got $p)")
        return tips.getValue(p)
    }
    fun bladeAxisDirection(p: BladePosition) = (bladeTip(p) - bladeRoot).normalize()
    fun componentIndex(p: BladePosition) = componentOf[p] ?: ComponentIndex.X
    val turbineDirection: Vec3 get() = rotorAxisDirection.horizontal().normalize()
    val rotorDiameter get() = dims.rotorDiameter

    // ── distancias de seguridad (punto → primer contacto con cada sólido) ──
    val rotorAxisBack get() = bladeRoot - rotorAxisDirection * dims.towerCenterToHubCenter * 4.0
    val rotorAxisFront get() = bladeRoot
    val spinnerStart get() = bladeRoot + rotorAxisDirection * dims.bladeFlangeRadius
    private val axisBack get() = rotorAxisBack
    private val flangeStart get() = bladeRoot - rotorAxisDirection * dims.bladeFlangeRadius
    private val flangeEnd get() = bladeRoot + rotorAxisDirection * dims.bladeFlangeRadius

    private fun distToBlade(p: Vec3, pos: BladePosition): Double {
        val half = dims.bladeMaxChordWidth / 2.0
        val tip = bladeTip(pos)
        val c = Segment3(bladeRoot, tip).closestPointTo(p)
        val dAxis = (c - p).norm
        val mid = bladeRoot + bladeAxisDirection(pos) * dims.hubCenterToMaxChordWidth
        val dCyl = Solid(bladeRoot, mid, half, half).distanceFrom(p, c)
        val dCone = Solid(mid, tip, half, 0.0).distanceFrom(p, c) // ápice en la punta
        return min(dAxis, min(dCyl, dCone))
    }
    private fun distToNacelle(p: Vec3): Double {
        val c = Segment3(axisBack, bladeRoot).closestPointTo(p)
        return Solid(axisBack, bladeRoot, dims.nacelleThickness, dims.nacelleThickness).distanceFrom(p, c)
    }
    private fun distToSpinner(p: Vec3): Double {
        val start = bladeRoot + rotorAxisDirection * dims.bladeFlangeRadius
        val c = Segment3(start, spinner).closestPointTo(p)
        return Solid(start, spinner, dims.hubCenterToBladeFlange, 0.0).distanceFrom(p, c)
    }
    private fun distToTower(p: Vec3): Double {
        val c = Segment3(towerBottom, towerTop).closestPointTo(p)
        return Solid(towerBottom, towerTop, dims.towerBottomRadius, dims.towerTopRadius).distanceFrom(p, c)
    }
    private fun distToFlange(p: Vec3): Double {
        val c = Segment3(flangeStart, flangeEnd).closestPointTo(p)
        return Solid(flangeStart, flangeEnd, dims.hubCenterToBladeFlange, dims.hubCenterToBladeFlange).distanceFrom(p, c)
    }

    /** Distancia a un componente concreto (null si el índice no es válido). A/B/C → pala en [pos]. */
    /** Sólido (tronco de cono) del modelo de colisión; los mismos que usan las distancias de seguridad. */
    class CollisionSolid(val name: String, val base: Vec3, val top: Vec3, val rBase: Double, val rTop: Double) {
        internal val solid = Solid(base, top, rBase, rTop)
    }

    /** Sólidos del modelo de colisión (torre, góndola, buje, brida y las tres palas en cilindro + cono). */
    val collisionSolids: List<CollisionSolid> by lazy {
        val half = dims.bladeMaxChordWidth / 2.0
        val out = ArrayList<CollisionSolid>()
        out += CollisionSolid("Tower", towerBottom, towerTop, dims.towerBottomRadius, dims.towerTopRadius)
        out += CollisionSolid("Nacelle", axisBack, bladeRoot, dims.nacelleThickness, dims.nacelleThickness)
        out += CollisionSolid("Spinner", spinnerStart, spinner, dims.hubCenterToBladeFlange, 0.0)
        out += CollisionSolid("Flange", flangeStart, flangeEnd, dims.hubCenterToBladeFlange, dims.hubCenterToBladeFlange)
        for (b in listOf(BladePosition.FOUR, BladePosition.TWELVE, BladePosition.EIGHT)) {
            val tip = bladeTip(b)
            val mid = bladeRoot + bladeAxisDirection(b) * dims.hubCenterToMaxChordWidth
            out += CollisionSolid("Blade-$b", bladeRoot, mid, half, half)
            out += CollisionSolid("Blade-$b", mid, tip, half, 0.0)
        }
        out
    }

    /** Distancia desde [origin] al primer sólido que corta el rayo en [direction] (hasta [maxRange] m), o null si no toca nada. */
    fun raycast(origin: Vec3, direction: Vec3, maxRange: Double): Double? {
        val end = origin + direction.normalize() * maxRange
        return collisionSolids.mapNotNull { it.solid.entryPoint(origin, end) }.minOfOrNull { (it - origin).norm }
    }

    fun distanceToComponent(c: ComponentIndex, pos: BladePosition, p: Vec3): Double? = when (c) {
        ComponentIndex.A, ComponentIndex.B, ComponentIndex.C -> distToBlade(p, pos)
        ComponentIndex.T -> distToTower(p)
        ComponentIndex.S -> distToSpinner(p)
        ComponentIndex.N -> distToNacelle(p)
        else -> null
    }

    fun distanceToFixedComponents(p: Vec3) = min(distToTower(p), min(distToNacelle(p), distToSpinner(p)))

    /** (distancia mínima, nombre del componente más cercano). */
    fun distance(p: Vec3): Pair<Double, String> {
        var best = Double.MAX_VALUE; var name = ""
        fun consider(d: Double, n: String) { if (d < best) { best = d; name = n } }
        consider(distToTower(p), "Tower"); consider(distToNacelle(p), "Nacelle")
        consider(distToSpinner(p), "Spinner"); consider(distToFlange(p), "Flange")
        for (b in listOf(BladePosition.FOUR, BladePosition.TWELVE, BladePosition.EIGHT)) consider(distToBlade(p, b), "Blade-$b")
        return best to name
    }

    companion object {
        /**
         * Turbina sintética a partir de dimensiones. Útil para pruebas sin dron.
         * Verificado contra createFromDimensions del original: spinner = hub + dir·(hubLength + bladeFlangeRadius),
         * dron de medición = spinner + horizontal(dir)·5 m, "parte inferior de la pala 12" = hub + 5 m hacia la punta 12.
         */
        fun fromDimensions(d: TurbineDimensions, rotorIndex: Angle = Angle.fromDegrees(0.0)): WindTurbine =
            WindTurbine(d, syntheticMeasurements(d, rotorIndex), defaultComponents())

        fun defaultComponents() = mapOf(BladePosition.TWELVE to ComponentIndex.A, BladePosition.FOUR to ComponentIndex.B, BladePosition.EIGHT to ComponentIndex.C)

        /**
         * Mediciones ideales de una turbina con estas dimensiones (sin ruido), en coordenadas locales con la base de la torre en el origen.
         * Con [bottomOnLeadingEdge] = false reproduce tal cual createFromDimensions del original.
         */
        fun syntheticMeasurements(d: TurbineDimensions, rotorIndex: Angle = Angle.fromDegrees(0.0), bottomOnLeadingEdge: Boolean = false): TurbineMeasurements {
            val towerTop = Vec3(0.0, 0.0, d.towerHeight)
            val hubOffset = Vec3.Y * d.towerCenterToHubCenter
            val axisAng = Angle.fromDegrees(d.rotorAxisAngle)
            val front = Geo3D.rotateAbout(hubOffset, Vec3.X, axisAng, Vec3.ZERO)
            val back = Geo3D.rotateAbout(-hubOffset, Vec3.X, axisAng, Vec3.ZERO)
            val dir = (front - back).normalize()
            fun tip(p: BladePosition): Vec3 {
                val a = rotorIndex + Angle.fromDegrees(when (p) {
                    BladePosition.FOUR -> 60.0; BladePosition.TWELVE -> 180.0; BladePosition.EIGHT -> 300.0
                    else -> error("unsupported bladePosition: $p")
                })
                val tilted = Geo3D.rotateAbout(Vec3(0.0, 0.0, -d.bladeLength), Vec3.X, Angle.fromDegrees(d.bladeTiltAngle), Vec3.ZERO) + front
                val tilted2 = Geo3D.rotateAbout(tilted, Vec3.X, axisAng, front)
                return Geo3D.rotateAbout(tilted2, dir, a, front) + towerTop
            }
            val hub = front + towerTop
            val spinner = hub + dir * (d.hubLength + d.bladeFlangeRadius)
            val t12 = tip(BladePosition.TWELVE)
            return TurbineMeasurements(
                droneAtSpinner = spinner + dir.horizontal() * 5.0,
                blade4Tip = tip(BladePosition.FOUR), blade12Tip = t12, blade8Tip = tip(BladePosition.EIGHT),
                // El original (y el constructor) supone que el punto medido está en el borde de ataque y resta el radio de la brida;
                // con bottomOnLeadingEdge se genera así, y la turbina reconstruida coincide exactamente con las dimensiones.
                blade12Bottom = hub + (t12 - hub).normalize() * 5.0 + (if (bottomOnLeadingEdge) dir * d.bladeFlangeRadius else Vec3.ZERO),
                spinner = spinner,
            )
        }

        // ── validación y ajuste a partir de las mediciones ──
        private fun coneAngle(root: Vec3, axis: Vec3, p: Vec3) =
            Angle.fromDegrees(90.0) - Geo3D.angleBetween((p - root).normalize(), axis)

        private fun fitRotorAxis(drone: Vec3, right: Vec3, top: Vec3, left: Vec3, root: Vec3): Vec3 {
            var n = PlaneFitter.normal(listOf(left, right, top))
            val facing = n dot (drone - root).normalize()
            MissionPlanningException.require(abs(facing) > 0.1,
                "Drone appears to be near the rotor plane (dot=$facing), cannot determine front direction reliably.",
                "The drone position at spinner measurement is too close to the rotor plane. Re-measure the spinner from clearly in front of the turbine.")
            if (facing <= 0.0) n = -n
            validate(n, right, top, left, root)
            val cr = coneAngle(root, n.normalize(), right).degrees
            val ct = coneAngle(root, n.normalize(), top).degrees
            val cl = coneAngle(root, n.normalize(), left).degrees
            MissionPlanningException.require(abs(cr - ct) < 5.0, "Tilt angle between right and top blades does not match")
            MissionPlanningException.require(abs(ct - cl) < 5.0, "Tilt angle between top and left blades does not match")
            MissionPlanningException.require(abs(cl - cr) < 5.0, "Tilt angle between left and right blades does not match")
            return n
        }

        private fun validate(axis: Vec3, right: Vec3, top: Vec3, left: Vec3, root: Vec3) {
            MissionPlanningException.require(axis.norm > 1e-6, "Rotor axis direction is invalid (near-zero vector).")
            val n = axis.normalize()
            for ((label, p) in listOf("Left / Root" to left, "Right / Root" to right, "Top / Root" to top)) {
                val off = n dot (p - root)
                MissionPlanningException.require(abs(off) <= 10.0, "$label is $off m off the plane — exceeds tolerance of 10.0 m.")
            }
            val rl = (left - root).norm; val rt = (top - root).norm; val rr = (right - root).norm
            MissionPlanningException.require(abs(rl - rr) <= 15.0 && abs(rl - rt) <= 15.0 && abs(rt - rr) <= 15.0,
                "Distances to blades differ more than 15 m", "Measure at same radius on all blades.")
            val vr = right - root; val vt = top - root; val vl = left - root
            for ((label, a) in listOf("Left-Right" to Geo3D.angleBetween(vl, vr), "Right-Top" to Geo3D.angleBetween(vr, vt), "Top-Left" to Geo3D.angleBetween(vt, vl)))
                MissionPlanningException.require(abs(a.degrees - 120.0) <= 10.0, "$label angle not ~120°")
        }

        private fun scaleToEqualRadius(right: Vec3, top: Vec3, left: Vec3, root: Vec3): Triple<Vec3, Vec3, Vec3> {
            val vr = right - root; val vt = top - root; val vl = left - root
            val mean = (vr.norm + vt.norm + vl.norm) / 3.0
            return Triple(root + vr.normalize() * mean, root + vt.normalize() * mean, root + vl.normalize() * mean)
        }

        private fun turbineAngles(right: Vec3, top: Vec3, left: Vec3, root: Vec3, axis: Vec3): TurbineAngles {
            val n = axis.normalize()
            fun proj(p: Vec3) = Geo3D.projectionOnPlane((p - root).normalize(), n).normalize()
            val ref = Geo3D.projectionOnPlane(Vec3.Z, n).normalize()
            val aTop = Geo3D.signedAngleBetween(ref, proj(top), n)
            val aLeft = Geo3D.signedAngleBetween(ref, proj(left), n)
            val aRight = Geo3D.signedAngleBetween(ref, proj(right), n)
            val index = ((aTop - Angle.fromDegrees(0.0)) + (aLeft - Angle.fromDegrees(120.0)) + (aRight - Angle.fromDegrees(-120.0))) / 3.0
            return TurbineAngles(
                rotorIndex = index,
                rotorAxis = Geo3D.angleBetween(axis.horizontal(), axis),
                bladeTilt = Angle.fromDegrees(90.0) - Geo3D.angleBetween(axis, top - root),
            )
        }
    }
}
