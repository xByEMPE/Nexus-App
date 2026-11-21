package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

// ───────────────────────── Utilidades geométricas del planificador (Computation del original) ─────────────────────────
internal object PlanMath {
    /** Longitud de segmento <= maxSegment que divide la línea en tramos iguales. */
    fun segmentLength(lineLength: Double, maxSegment: Double): Double =
        if (lineLength >= maxSegment) min(lineLength / (floor(lineLength / maxSegment) + 1), maxSegment) else lineLength

    /** Punto de la recta [line] equidistante a [p1] y [p2]; NaN si no existe. */
    fun equidistantPointOnLine(p1: Vec3, p2: Vec3, line: Line3, tol: Double = 1e-6): Vec3 {
        if ((p1 - p2).norm < tol) return p1
        val dir = line.direction.normalize()
        val o = line.point
        val a = p1 - o; val b = p2 - o
        val den = (b - a) dot dir
        if (abs(den) < tol) return Vec3.NAN
        val c = o + dir * ((((b - a) dot (b + a)) / 2.0) / den)
        return if (abs((p1 - c).norm - (p2 - c).norm) > 10 * tol) Vec3.NAN else c
    }

    fun radiusOffsetForStep(start: Double, end: Double, step: Int): Double {
        if (step < 0) return start
        val d = end - start
        return sign(d) * abs(d) * (1.0 - exp(-step.toDouble()))
    }
}

private fun transferWp(
    index: Int, pathType: PathType, bp: BladePosition, comp: ComponentIndex, point: Vec3, poi: Vec3, yaw: Double,
    def: InspectionDefinition, stop: Boolean, dComponent: Double, dRoot: Double,
) = Waypoint3D(index, PathIndex.UNDEFINED, pathType, bp, comp, point, poi, YawMode.TOWARD_POI, yaw, emptyList(),
    PauseTiming.UNDEFINED, 0.0, def.transferVelocity, stop, false, false, false, false, dComponent, dRoot)

// ───────────────────────── Transiciones entre caminos de una misma pala ─────────────────────────
object PathTransitionPlanner {
    private const val MAX_ANGLE_STEP = 15.0

    fun computeTransferWaypoints(
        turbineDir: Vec3, previous: Vec3, next: Vec3, axisDir: Vec3, axisPoint: Vec3,
        bp: BladePosition, comp: ComponentIndex, from: PathIndex, to: PathIndex,
        bladeRoot: Vec3, bladeTip: Vec3, def: InspectionDefinition,
    ): List<Waypoint3D> {
        if (bp == BladePosition.FOUR && from == PathIndex.SS && to == PathIndex.TE)
            return belowRightBlade(turbineDir, previous, next, axisDir, axisPoint, bp, comp, bladeRoot, def)

        val poiPoint = if ((bp == BladePosition.FOUR && to == PathIndex.PS) || (bp == BladePosition.EIGHT && to == PathIndex.SS))
            bladeTip + (bladeRoot - bladeTip) * 0.5 else axisPoint
        val line = Line3(axisPoint, axisDir)
        val cPrev = line.closestPointTo(previous); val dPrev = (cPrev - bladeRoot).norm
        val cNext = line.closestPointTo(next); val dNext = (cNext - bladeRoot).norm
        val d = if ((from == PathIndex.LE && to == PathIndex.SS) || (from == PathIndex.TE && to == PathIndex.PS)) 2.0 else 0.0
        val prev2 = previous + (previous - cPrev).normalize() * d
        val ang = Geo3D.signedAngleBetween(prev2 - cPrev, (next + (next - cNext).normalize() * d) - cNext, axisDir)
        val seg = PlanMath.segmentLength(ang.degrees, MAX_ANGLE_STEP)
        val n = ((ang.degrees + 1e-6) / seg).toInt()
        val step = (dNext - dPrev) / n
        val out = ArrayList<Waypoint3D>()
        for (i in 1 until n) {
            val axial = if (from == PathIndex.SS && to == PathIndex.TE) PlanMath.radiusOffsetForStep(dPrev, dNext, i) else i * step
            val pt = Geo3D.rotateAbout(prev2, axisDir, Angle.fromDegrees(i * seg), cPrev) + axisDir * axial
            out += transferWp(i - 1, PathType.PATH_TRANSFER, bp, comp, pt, poiPoint, Geo3D.yawOf(cPrev - pt), def, false,
                (pt - poiPoint).norm, (pt - bladeRoot).norm)
        }
        return out
    }

    /** Caso especial: de SS a TE en la pala de las 4 h, pasando por debajo de la pala. */
    private fun belowRightBlade(
        turbineDir: Vec3, previous: Vec3, next: Vec3, axisDir: Vec3, axisPoint: Vec3,
        bp: BladePosition, comp: ComponentIndex, root: Vec3, def: InspectionDefinition,
    ): List<Waypoint3D> {
        val side = Geo3D.rotate(turbineDir, Vec3.Z, Angle.fromDegrees(90.0))
        val start = previous + axisDir * (
            if ((previous - root) dot side < 0.0) 1.0 + Geo3D.pointToPlaneDistance(previous, root, side) / cos(Angle.fromDegrees(30.0).radians) else 1.0)
        val line = Line3(axisPoint, axisDir)
        val end = Vec3(next.x, next.y,
            start.z - abs((line.closestPointTo(next) - root).norm - (line.closestPointTo(start) - root).norm) / cos(Angle.fromDegrees(60.0).radians))
        val c = PlanMath.equidistantPointOnLine(start, end, Line3(start, -turbineDir))
        require(c.isNotNaN) { "could not compute equidistant point for transfer between inspection paths" }
        val v1 = (start - c).normalize(); val v2 = (end - c).normalize()
        val rotAxis = v1 cross v2
        val ang = Geo3D.signedAngleBetween(v1, v2, rotAxis)
        val seg = PlanMath.segmentLength(ang.degrees, MAX_ANGLE_STEP)
        val n = ((ang.degrees + 1e-6) / seg).toInt()
        val out = ArrayList<Waypoint3D>()
        out += transferWp(0, PathType.PATH_TRANSFER, bp, comp, start, c, Geo3D.yawOf(-turbineDir), def, false, (start - c).norm, (start - root).norm)
        for (i in 1..n) {
            val pt = Geo3D.rotateAbout(start, rotAxis, Angle.fromDegrees(i * seg), c)
            out += transferWp(i, PathType.PATH_TRANSFER, bp, comp, pt, c, Geo3D.yawOf(c - pt), def, false, (pt - c).norm, (pt - root).norm)
        }
        return out
    }
}

// ───────────────────────── Una pala completa: LE → SS → TE → PS ─────────────────────────
class BladeMissionPlanner(
    private val turbine: WindTurbine,
    private val def: InspectionDefinition,
    private val cam: CameraFov,
    private val gimbal: GimbalLimits,
    private val twist: BladeTwist,
    private val stopAtEach: Boolean,
) {
    private fun directionToFront(axisDir: Vec3, turbineDir: Vec3, pos: BladePosition): Vec3 {
        if (pos == BladePosition.TWELVE) return turbineDir
        val n = Vec3(-axisDir.y, axisDir.x, 0.0).normalize()
        return if ((n dot turbineDir) < 0.0) n * -1.0 else n
    }

    fun plan(pos: BladePosition, comp: ComponentIndex, usingRtk: Boolean): List<Waypoint3D> {
        require(comp != ComponentIndex.X) { "blade index must be one of A, B, C" }
        val d = turbine.dims
        val bt = BladeThickness(d)
        val root = turbine.bladeRoot
        val turbineDir = turbine.turbineDirection
        val tip = turbine.bladeTip(pos)
        val axis = (tip - root).normalize()

        // El margen de punta nunca puede ser menor que la huella efectiva en la punta
        val effTip = InspectionOptics.effectiveFov(cam.vertical, def.observationDistanceTip, 0.0)
        val dd = if (def.tipMargin < effTip) def.copy(tipMargin = effTip) else def

        val safetyPt = root + axis * InspectionOptics.safetyDistanceFromRoot(dd.observationDistanceRoot, d.bladeFlangeRadius, d.hubCenterToBladeFlange, dd.nacelleSafetyDistance)
        val flangePt = root + axis * d.hubCenterToBladeFlange
        val tipPt = root + axis * (d.bladeLength + dd.tipMargin)
        val front = directionToFront(axis, turbineDir, pos)

        fun path(p: PathIndex, dir: PathDirection, width: Double, a: Vec3, b: Vec3, wd: Vec3, tw: BladeTwist?) =
            PathPlanner.computeInspectionWaypoints(p, dir, width, a, b, wd, pos, comp, tw, root, cam, gimbal, dd, bt,
                d.hubCenterToMaxChordWidth, d.hubLength, usingRtk, stopAtEach, turbine)

        val le = path(PathIndex.LE, PathDirection.ROOT_TO_TIP, d.bladeFlangeRadius * 2, flangePt, tipPt, front, null)
        val ss = path(PathIndex.SS, PathDirection.TIP_TO_ROOT, d.bladeMaxChordWidth, tipPt, safetyPt, Geo3D.rotate(front, axis, Angle.fromDegrees(90.0)), null)
        val teTwist = if (pos == BladePosition.TWELVE) twist else BladeTwist(0.0, twist.angleTip / 2, twist.angleTip)
        val te = path(PathIndex.TE, PathDirection.ROOT_TO_TIP, d.bladeFlangeRadius * 2,
            safetyPt + axis * (d.nacelleThickness + dd.nacelleSafetyDistance), tipPt, Geo3D.rotate(front, axis, Angle.fromDegrees(180.0)), teTwist)
        val ps = path(PathIndex.PS, PathDirection.TIP_TO_ROOT, d.bladeMaxChordWidth, tipPt, safetyPt, Geo3D.rotate(front, axis, Angle.fromDegrees(270.0)), null)

        fun transfer(a: List<Waypoint3D>, b: List<Waypoint3D>, axisPoint: Vec3, from: PathIndex, to: PathIndex) =
            PathTransitionPlanner.computeTransferWaypoints(turbineDir, a.last().point, b.first().point, axis, axisPoint, pos, comp, from, to, root, tip, dd)

        val all = listOf(le, transfer(le, ss, tipPt, PathIndex.LE, PathIndex.SS), ss, transfer(ss, te, safetyPt, PathIndex.SS, PathIndex.TE),
            te, transfer(te, ps, tipPt, PathIndex.TE, PathIndex.PS), ps).flatten()
        return all.mapIndexed { i, w -> w.reIndex(i) }
    }
}

// ───────────────────────── Salto entre palas y salida ─────────────────────────
class ComponentTransitionPlanner(private val def: InspectionDefinition, private val dims: TurbineDimensions) {
    private val maxAngleStep = 15.0

    /** Arco alrededor del eje del rotor para pasar de una pala a otra manteniendo la distancia al buje. */
    fun plan(turbineDir: Vec3, bladeRoot: Vec3, start: Vec3, arcEnd: Vec3): List<Waypoint3D> {
        val line = Line3(bladeRoot, turbineDir)
        val p0 = start + turbineDir * dims.bladeMaxChordWidth
        val out = ArrayList<Waypoint3D>()
        out += transferWp(0, PathType.PATH_TRANSFER, BladePosition.UNDEFINED, ComponentIndex.X, p0, bladeRoot,
            Geo3D.yawOf(bladeRoot - p0), def, false, (bladeRoot - p0).norm, (p0 - bladeRoot).norm)
        val c = PlanMath.equidistantPointOnLine(p0, arcEnd, line, 1e-6)
        require(c.isNotNaN) { "could not compute equidistant point for transition between blades" }
        val v1 = (p0 - c).normalize(); val v2 = (arcEnd - c).normalize()
        val axis = v1 cross v2
        val ang = Geo3D.signedAngleBetween(v1, v2, axis)
        val seg = PlanMath.segmentLength(ang.degrees, maxAngleStep)
        val n = ((ang.degrees + 1e-6) / seg).toInt()
        for (i in 1 until n) {
            val pt = Geo3D.rotateAbout(p0, axis, Angle.fromDegrees(i * seg), c)
            out += transferWp(i, PathType.BLADE_TRANSFER, BladePosition.UNDEFINED, ComponentIndex.X, pt, bladeRoot,
                Geo3D.yawOf(pt - c), def, false, (bladeRoot - pt).norm, (bladeRoot - pt).norm)
        }
        return out
    }
}

class ExitPlanner(private val def: InspectionDefinition) {
    /** Se aleja 15 m del plano del rotor siguiendo el eje, y 1 m más con parada. */
    fun plan(turbineDir: Vec3, last: Vec3, bp: BladePosition, comp: ComponentIndex, bladeRoot: Vec3): List<Waypoint3D> {
        val yaw = Geo3D.yawOf(-turbineDir)
        val p0 = last + turbineDir * 15.0
        val p1 = p0 + turbineDir * 1.0
        return listOf(
            transferWp(0, PathType.BLADE_TRANSFER, bp, comp, p0, bladeRoot, yaw, def, false, (p0 - bladeRoot).norm, (p0 - bladeRoot).norm),
            transferWp(1, PathType.BLADE_TRANSFER, bp, comp, p1, bladeRoot, yaw, def, true, (p1 - bladeRoot).norm, (p1 - bladeRoot).norm),
        )
    }
}

// ───────────────────────── Orquestador: la misión completa ─────────────────────────
class InspectionPlanner(
    private val twist: BladeTwist,
    private val turbine: WindTurbine,
    private val def: InspectionDefinition,
    private val cam: CameraFov,
    private val gimbal: GimbalLimits,
    private val usingRtk: Boolean = false,
    private val stopAtEach: Boolean = false,
) {
    init {
        MissionPlanningException.require(turbine.rotorDiameter > 49.9, "Rotor diameter under 50 meters is not supported.")
    }

    /** Distancia mínima waypoint→turbina de toda la misión (m). */
    fun minDistanceToTurbine(wps: List<Waypoint3D>): Double = wps.minOfOrNull { turbine.distance(it.point).first } ?: Double.MAX_VALUE

    fun plan(blade4: ComponentIndex, blade12: ComponentIndex, blade8: ComponentIndex): List<Waypoint3D> {
        val out = ArrayList<Waypoint3D>()
        val blade = BladeMissionPlanner(turbine, def, cam, gimbal, twist, stopAtEach)
        val comp = ComponentTransitionPlanner(def, turbine.dims)
        fun append(w: List<Waypoint3D>) { for (x in w) out += x.reIndex(out.size) }

        if (blade4 != ComponentIndex.X) append(blade.plan(BladePosition.FOUR, blade4, usingRtk))
        for ((pos, idx) in listOf(BladePosition.TWELVE to blade12, BladePosition.EIGHT to blade8)) {
            if (idx == ComponentIndex.X) continue
            val b = blade.plan(pos, idx, usingRtk)
            if (out.isNotEmpty()) append(comp.plan(turbine.turbineDirection, turbine.bladeRoot, out.last().point, b.first().point))
            append(b)
        }
        if (out.isNotEmpty()) {
            val last = out.last()
            append(ExitPlanner(def).plan(turbine.turbineDirection, last.point, last.bladePosition, last.componentIndex, turbine.bladeRoot))
        }
        val d = minDistanceToTurbine(out)
        MissionPlanningException.require(d >= 4.5,
            "Closest waypoint distance to turbine is $d, smaller than the threshold of 4.5. There is likely an error in the mission planning.")
        return out
    }
}
