package mx.com.nexusio.fly.drone.onestop.flightcore

import kotlin.math.*

/**
 * Vector y punto 3D en un único tipo (el original separa Point3D y Vector3D).
 * Ejes del mundo local: x = Este, y = Norte, z = Arriba, en metros.
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm: Double get() = sqrt(x * x + y * y + z * z)
    /** Sin protección ante vector nulo: produce NaN */
    fun normalize() = this * (1.0 / norm)
    fun horizontal() = Vec3(x, y, 0.0)
    val isNotNaN: Boolean get() = !(x.isNaN() || y.isNaN() || z.isNaN())

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        val NAN = Vec3(Double.NaN, Double.NaN, Double.NaN)
    }
}

object Geo3D {
    private const val EPS = 1e-6

    fun angleBetween(a: Vec3, b: Vec3): Angle {
        val na = a.norm; val nb = b.norm
        if (na < EPS || nb < EPS) return Angle.fromRadians(0.0)
        // El original no recorta el coseno (puede dar NaN por redondeo); aquí sí.
        return Angle.fromRadians(acos(((a dot b) / (na * nb)).coerceIn(-1.0, 1.0)))
    }

    fun signedAngleBetween(a: Vec3, b: Vec3, referenceAxis: Vec3): Angle {
        val na = a.norm; val nb = b.norm
        if (na < EPS || nb < EPS) return Angle.fromRadians(0.0)
        var ang = acos(((a dot b) / (na * nb)).coerceIn(-1.0, 1.0))
        if (((a cross b) dot referenceAxis) < 0.0) ang = -ang
        return Angle.fromRadians(ang)
    }

    fun projectionOnPlane(v: Vec3, normal: Vec3): Vec3 {
        val n = normal.normalize()
        return v - n * (v dot n)
    }

    /**
     * Rotación de mano derecha de +[angle] alrededor de [axis] (verificado numéricamente contra
     * RotationMatrix.fromAxisAngle del original). Fórmula de Rodrigues.
     */
    fun rotate(v: Vec3, axis: Vec3, angle: Angle): Vec3 {
        if (axis.norm <= EPS || abs(angle.radians) <= EPS) return v
        val k = axis.normalize(); val c = cos(angle.radians); val s = sin(angle.radians)
        return v * c + (k cross v) * s + k * ((k dot v) * (1 - c))
    }

    fun rotateAbout(p: Vec3, axis: Vec3, angle: Angle, center: Vec3) = rotate(p - center, axis, angle) + center

    /** Rumbo en grados (0 = Norte, +90 = Este) de una dirección. Equivale a atan2(x, y) salvo casos degenerados. */
    fun yawOf(direction: Vec3): Double =
        signedAngleBetween(Vec3.Y, direction.horizontal(), -Vec3.Z).degrees

    fun pointToPlaneDistance(p: Vec3, planePoint: Vec3, planeNormal: Vec3) =
        abs(planeNormal dot (p - planePoint)) / planeNormal.norm
}

/** Recta con dirección UNITARIA (el original asume que la dirección ya está normalizada). */
class Line3(val point: Vec3, val direction: Vec3) {
    fun closestPointTo(p: Vec3) = point + direction * ((p - point) dot direction)

    /** Puntos más cercanos entre dos rectas: (en esta, en la otra). */
    fun closestPointsTo(o: Line3): Pair<Vec3, Vec3> {
        val w = point - o.point
        val a = direction dot direction; val b = direction dot o.direction; val c = o.direction dot o.direction
        val d = w dot direction; val e = w dot o.direction
        val den = a * c - b * b
        val eps = 1e-9
        if (abs(den) >= eps) return (point + direction * ((b * e - c * d) / den)) to (o.point + o.direction * ((a * e - b * d) / den))
        if (a < eps) return point to (o.point + o.direction * (((point - o.point) dot o.direction) / c))
        val p1 = point + direction * (d / a)
        return if (c < eps) p1 to o.point else p1 to (o.point + o.direction * (((p1 - o.point) dot o.direction) / c))
    }
}

/**
 * Puntos más cercanos entre esta recta y [o], restringiendo el resultado de esta recta al tramo [p1, p2]
 * (ambos deben estar en la recta, tolerancia 1e-9). Si el punto más cercano cae fuera del tramo, devuelve el extremo
 * del tramo más próximo a [o] junto con su proyección sobre [o].
 */
fun Line3.closestPointsToLineBetween(o: Line3, p1: Vec3, p2: Vec3): Pair<Vec3, Vec3> {
    require((p1 - closestPointTo(p1)).norm <= 1e-9 && (p2 - closestPointTo(p2)).norm <= 1e-9) { "point1 and point2 must be on this line" }
    val (a, b) = closestPointsTo(o)
    if (((a - p1) dot (a - p2)) <= 0.0) return a to b
    val q1 = o.closestPointTo(p1); val q2 = o.closestPointTo(p2)
    return if ((p1 - q1).norm < (p2 - q2).norm) p1 to q1 else p2 to q2
}

class Segment3(val start: Vec3, val end: Vec3) {
    fun closestPointTo(p: Vec3, onSegment: Boolean = true): Vec3 {
        val v = end - start
        val dd = v dot v
        if (dd < 1e-12) return start
        var t = ((p - start) dot v) / dd
        if (onSegment) t = t.coerceIn(0.0, 1.0)
        return start + v * t
    }
}

/**
 * Tronco de cono sólido. Cubre los tres sólidos del original:
 *  cilindro = (r, r); cono con ápice en [top] = (r, 0); tronco = (rBase, rTop).
 * Devuelve el primer punto de entrada del segmento p1→p2, o null.
 */
class Solid(private val base: Vec3, private val top: Vec3, private val rBase: Double, private val rTop: Double) {
    private val eps = 1e-12

    fun entryPoint(p1: Vec3, p2: Vec3): Vec3? {
        val axisV = top - base; val h = axisV.norm
        if (h < eps || (rBase <= 0.0 && rTop <= 0.0)) return null
        val a = axisV * (1.0 / h)
        val d = p2 - p1; val q0 = p1 - base
        val s0 = q0 dot a; val sd = d dot a
        val w0 = q0 - a * s0; val wd = d - a * sd
        val k = (rTop - rBase) / h
        val hits = ArrayList<Double>()
        fun add(t: Double) { if (!t.isNaN() && t >= -eps && t <= 1 + eps) hits += t.coerceIn(0.0, 1.0) }
        fun lateral(t: Double) {
            val s = s0 + sd * t
            if (s < -eps || s > h + eps || rBase + k * s < -eps) return
            add(t)
        }
        val r0 = rBase + k * s0; val rd = k * sd
        val qa = (wd dot wd) - rd * rd
        val qb = 2 * ((w0 dot wd) - r0 * rd)
        val qc = (w0 dot w0) - r0 * r0
        if (abs(qa) >= eps) {
            val disc = qb * qb - 4 * qa * qc
            if (disc >= -eps) {
                val sq = sqrt(max(0.0, disc))
                lateral((-qb - sq) / (2 * qa)); lateral((-qb + sq) / (2 * qa))
            }
        } else if (abs(qb) > eps) lateral(-qc / qb)
        if (abs(sd) > eps) {
            val t0 = -s0 / sd
            if (t0 in 0.0..1.0 && (w0 + wd * t0).norm <= rBase + eps) add(t0)
            val t1 = (h - s0) / sd
            if (t1 in 0.0..1.0 && (w0 + wd * t1).norm <= rTop + eps) add(t1)
        }
        return hits.minOrNull()?.let { p1 + d * it }
    }

    /**
     * ¿Está [p] dentro del sólido? Válido para cilindros y troncos. (El `contains` de Cone3D del original está invertido
     * respecto a su `intersect` y no se usa en ninguna ruta de vuelo, así que no se porta tal cual.)
     */
    fun contains(p: Vec3): Boolean {
        val axisV = top - base; val h = axisV.norm
        if (h < eps) return false
        val a = axisV * (1.0 / h)
        val w = p - base; val s = w dot a
        if (s < -eps || s > h + eps) return false
        val r = rBase + (rTop - rBase) * (s / h)
        return r > 0.0 && (w - a * s).norm <= r + eps
    }

    fun touches(p1: Vec3, p2: Vec3): Boolean = entryPoint(p1, p2) != null || (contains(p1) && contains(p2))

    /** Distancia de [p] al punto de entrada del segmento p→[axisPoint]; Double.MAX_VALUE si no lo atraviesa. */
    fun distanceFrom(p: Vec3, axisPoint: Vec3): Double =
        entryPoint(p, axisPoint)?.let { (it - p).norm } ?: Double.MAX_VALUE
}

/** Ajuste de plano por mínimos cuadrados: normal = autovector de menor autovalor de la covarianza (Jacobi 3×3). */
object PlaneFitter {
    fun normal(points: List<Vec3>): Vec3 {
        require(points.size >= 3) { "need at least 3 points" }
        val n = points.size.toDouble()
        val c = Vec3(points.sumOf { it.x } / n, points.sumOf { it.y } / n, points.sumOf { it.z } / n)
        val m = Array(3) { DoubleArray(3) }
        for (p in points) {
            val d = doubleArrayOf(p.x - c.x, p.y - c.y, p.z - c.z)
            for (i in 0..2) for (j in 0..2) m[i][j] += d[i] * d[j] / n
        }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(50) {
            var p = 0; var q = 1; var best = abs(m[0][1])
            if (abs(m[0][2]) > best) { p = 0; q = 2; best = abs(m[0][2]) }
            if (abs(m[1][2]) > best) { p = 1; q = 2; best = abs(m[1][2]) }
            if (best < 1e-15) return@repeat
            val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
            val t = sign(theta).let { if (it == 0.0) 1.0 else it } / (abs(theta) + sqrt(theta * theta + 1))
            val cs = 1 / sqrt(t * t + 1); val sn = t * cs
            for (k in 0..2) { val a = m[k][p]; val b = m[k][q]; m[k][p] = cs * a - sn * b; m[k][q] = sn * a + cs * b }
            for (k in 0..2) { val a = m[p][k]; val b = m[q][k]; m[p][k] = cs * a - sn * b; m[q][k] = sn * a + cs * b }
            for (k in 0..2) { val a = v[k][p]; val b = v[k][q]; v[k][p] = cs * a - sn * b; v[k][q] = sn * a + cs * b }
        }
        val idx = (0..2).minByOrNull { m[it][it] }!!
        return Vec3(v[0][idx], v[1][idx], v[2][idx])
    }
}

fun interface Interpolator { fun interpolate(x: Double): Double }

/** Lineal con saturación en ambos extremos (el original NO extrapola). */
class LinearInterpolator(private val x0: Double, private val y0: Double, private val x1: Double, private val y1: Double) : Interpolator {
    override fun interpolate(x: Double): Double = when {
        x < x0 -> y0
        x > x1 -> y1
        else -> y0 + (y1 - y0) * ((x - x0) / (x1 - x0))
    }
}

class PiecewiseLinearInterpolator(private val pts: List<Pair<Double, Double>>) : Interpolator {
    init {
        require(pts.size >= 2) { "at least two points required" }
        for (i in 1 until pts.size) require(pts[i].first > pts[i - 1].first) { "x values must be strictly increasing" }
    }
    override fun interpolate(x: Double): Double {
        if (x <= pts.first().first) return pts.first().second
        if (x >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (xa, ya) = pts[i - 1]; val (xb, yb) = pts[i]
            if (x in xa..xb) return ya + (yb - ya) * ((x - xa) / (xb - xa))
        }
        error("Interpolation failed. Check point ordering.")
    }
}
