package mx.com.nexusio.fly.drone.onestop.planview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import mx.com.nexusio.fly.drone.onestop.flightcore.BladePosition
import mx.com.nexusio.fly.drone.onestop.flightcore.CameraFov
import mx.com.nexusio.fly.drone.onestop.flightcore.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Visor 3D del plan de vuelo One Stop: turbina en alambre (un color por pala), ruta y waypoints, y el dron con su
 * cono de cámara y el color de su holgura a la turbina. Dibujo en Canvas, sin OpenGL.
 *
 * En miniatura ([interactive] = false) un toque llama a [onTap]; ampliado, arrastrar con un dedo gira, con dos dedos
 * desplaza la vista (subir o bajar por la turbina), pellizcar acerca y un doble toque vuelve a la vista inicial.
 *
 * Con [videoHoleEnabled], ampliado deja sin pintar un recuadro abajo a la derecha ([videoHole]) para que se vea el
 * vídeo del dron (un SurfaceView que se dibuja bajo la ventana); tocarlo llama a [onVideoTap].
 */
class PlanView3D @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var scene: PlanScene? = null
        set(value) { field = value; reachedIndex = 0; invalidate() }

    /** Ampliado (gestos y textos completos) o miniatura (solo toque para abrir). */
    var interactive = false
        set(value) { field = value; invalidate() }

    var onTap: (() -> Unit)? = null
    var onVideoTap: (() -> Unit)? = null

    /** Deja el hueco transparente para la miniatura del vídeo (solo con la vista ampliada). */
    var videoHoleEnabled = false
        set(value) { field = value; invalidate() }

    /** Recuadro de la miniatura del vídeo en coordenadas de esta vista, o null si no hay. */
    fun videoHole(): RectF? {
        if (!interactive || !videoHoleEnabled || width == 0) return null
        val m = VIDEO_MARGIN_DP * dp
        return RectF(width - m - VIDEO_W_DP * dp, height - m - VIDEO_H_DP * dp, width - m, height - m)
    }
    var cameraFov: CameraFov = CameraFov.default()

    private var drone: Vec3? = null
    private var yawDeg = 0.0
    private var pitchDeg = 0.0
    private var clearanceM = Double.NaN
    private var reachedIndex = 0

    /** Posición local del dron (sistema del plan), rumbo, inclinación del gimbal y holgura a la turbina. */
    fun updateDrone(local: Vec3, yawDeg: Double, gimbalPitchDeg: Double, clearanceM: Double) {
        drone = local; this.yawDeg = yawDeg; pitchDeg = gimbalPitchDeg; this.clearanceM = clearanceM
        scene?.let { reachedIndex = it.progressIndex(local, reachedIndex) }
        invalidate()
    }

    fun clearDrone() { drone = null; invalidate() }

    fun resetView() { scene?.preset(PlanScene.Preset.ISO); invalidate() }

    // ── pinturas ──
    private val dp = resources.displayMetrics.density
    private val bg = Paint().apply { color = BG }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID; strokeWidth = 1f }
    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * dp }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CAMERA }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHIP }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = BORDER; strokeWidth = 1.5f * dp }
    private val dash = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f)
    private val clip = Path()
    private val rect = RectF()

    // ── gestos ──
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { scene?.zoomBy(d.scaleFactor.toDouble()); invalidate(); return true }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!interactive) { onTap?.invoke(); return true }
            if (videoHole()?.contains(e.x, e.y) == true) { onVideoTap?.invoke(); return true }
            return false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { if (interactive) resetView(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!interactive) return false
            // dx/dy de Android son "anterior − actual" (del punto medio de los dedos cuando hay varios).
            if (e2.pointerCount >= 2) { scene?.panBy(-dx, -dy); invalidate(); return true }
            if (scaleDetector.isInProgress) return false
            scene?.rotateBy(-dx, -dy); invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (interactive) scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && !interactive) performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // ── dibujo ──
    override fun onDraw(canvas: Canvas) {
        val w = width; val h = height
        val radius = if (interactive) 0f else 10f * dp
        clip.reset(); rect.set(0f, 0f, w.toFloat(), h.toFloat()); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)
        // Hueco del vídeo: nada se pinta ahí, así se ve el vídeo del dron que queda debajo.
        val hole = videoHole()
        if (hole != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipOutRect(hole)
            else @Suppress("DEPRECATION") canvas.clipRect(hole, Region.Op.DIFFERENCE)
        }
        canvas.drawRect(rect, bg)
        val s = scene
        if (s == null) { canvas.restore(); return }
        val small = !interactive
        s.layout(w, h, (if (small) 8f else 24f) * dp, (if (small) 8f else 64f) * dp, (if (small) 8f else 28f) * dp)
        textPaint.textSize = (if (small) 9f else 12f) * dp

        drawGround(canvas, s, small)

        // Turbina en alambre
        wirePaint.strokeWidth = (if (small) 0.8f else 1.2f) * dp
        for ((kind, segs) in s.wire) {
            wirePaint.color = colorOf(kind); wirePaint.alpha = 230
            for (seg in segs) { val a = s.project(seg.a); val b = s.project(seg.b); canvas.drawLine(a.x, a.y, b.x, b.y, wirePaint) }
        }

        // Ruta: lo pendiente discontinuo y tenue, lo ya recorrido en verde; paradas como cuadros.
        val pts = s.points
        if (pts.size > 1) {
            pathPaint.strokeWidth = (if (small) 1.2f else 1.6f) * dp; pathPaint.color = PENDING; pathPaint.pathEffect = dash
            for (i in max(1, reachedIndex) until pts.size) { val a = s.project(pts[i - 1].pos); val b = s.project(pts[i].pos); canvas.drawLine(a.x, a.y, b.x, b.y, pathPaint) }
            pathPaint.pathEffect = null; pathPaint.color = DONE; pathPaint.strokeWidth = (if (small) 2f else 2.8f) * dp
            for (i in 1..min(reachedIndex, pts.lastIndex)) { val a = s.project(pts[i - 1].pos); val b = s.project(pts[i].pos); canvas.drawLine(a.x, a.y, b.x, b.y, pathPaint) }
            val r = (if (small) 1.4f else 2.2f) * dp
            for ((i, p) in pts.withIndex()) {
                val q = s.project(p.pos)
                dotPaint.color = if (i < reachedIndex) DONE else WAYPOINT
                if (p.stop) canvas.drawRect(q.x - r * 1.3f, q.y - r * 1.3f, q.x + r * 1.3f, q.y + r * 1.3f, dotPaint)
                else if (p.photo) canvas.drawCircle(q.x, q.y, r, dotPaint)
            }
            // Siguiente waypoint
            val next = s.project(pts[min(reachedIndex, pts.lastIndex)].pos)
            borderPaint.color = TEXT; canvas.drawCircle(next.x, next.y, (if (small) 3.5f else 6f) * dp, borderPaint); borderPaint.color = BORDER
        }

        if (!small) {
            for (b in listOf(BladePosition.FOUR, BladePosition.TWELVE, BladePosition.EIGHT)) {
                val q = s.project(s.bladeLabelPoint(b))
                label(canvas, q.x, q.y, "Pala " + mapOf(BladePosition.FOUR to "4h", BladePosition.TWELVE to "12h", BladePosition.EIGHT to "8h")[b], bladeColor(b))
            }
        }

        drone?.let { drawDrone(canvas, s, it, small) }

        if (!small) drawHud(canvas, s, w)
        canvas.restore()
        hole?.let { drawVideoFrame(canvas, it) }
        if (small) { rect.inset(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2); canvas.drawRoundRect(rect, radius, radius, borderPaint) }
    }

    private fun drawGround(canvas: Canvas, s: PlanScene, small: Boolean) {
        val g = 60.0; val step = if (small) 20.0 else 10.0
        val o = s.ground
        var v = -g
        gridPaint.alpha = 160
        while (v <= g + 1e-6) {
            val a = s.project(Vec3(o.x + v, o.y - g, o.z)); val b = s.project(Vec3(o.x + v, o.y + g, o.z)); canvas.drawLine(a.x, a.y, b.x, b.y, gridPaint)
            val c = s.project(Vec3(o.x - g, o.y + v, o.z)); val d = s.project(Vec3(o.x + g, o.y + v, o.z)); canvas.drawLine(c.x, c.y, d.x, d.y, gridPaint)
            v += step
        }
        if (!small) {
            val n = s.project(Vec3(o.x, o.y + g, o.z)); label(canvas, n.x, n.y, "N", BG, TEXT)
        }
    }

    private fun drawDrone(canvas: Canvas, s: PlanScene, pos: Vec3, small: Boolean) {
        // Cono de visión: rumbo del dron + inclinación del gimbal, con el campo de visión H × V de la cámara.
        val yaw = Math.toRadians(yawDeg); val pit = Math.toRadians(pitchDeg)
        val fwd = Vec3(cos(pit) * sin(yaw), cos(pit) * cos(yaw), sin(pit))
        val right = Vec3(cos(yaw), -sin(yaw), 0.0)
        val up = right cross fwd
        val len = (if (clearanceM.isFinite()) clearanceM * 1.15 else 12.0).coerceIn(8.0, 30.0)
        val th = tan(Math.toRadians(cameraFov.horizontal / 2)); val tv = tan(Math.toRadians(cameraFov.vertical / 2))
        val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) -> s.project(pos + (fwd + right * (th * sx) + up * (tv * sy)) * len) }
        val o = s.project(pos)
        val cone = Path().apply { moveTo(corners[0].x, corners[0].y); corners.forEach { lineTo(it.x, it.y) }; close() }
        conePaint.style = Paint.Style.FILL; conePaint.alpha = 50
        for (c in corners.indices) {
            val a = corners[c]; val b = corners[(c + 1) % corners.size]
            canvas.drawPath(Path().apply { moveTo(o.x, o.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }, conePaint)
        }
        canvas.drawPath(cone, conePaint)
        conePaint.style = Paint.Style.STROKE; conePaint.alpha = 230; conePaint.strokeWidth = (if (small) 1f else 1.4f) * dp
        corners.forEach { canvas.drawLine(o.x, o.y, it.x, it.y, conePaint) }
        canvas.drawPath(cone, conePaint)
        // Dron: color de su holgura con aro blanco.
        dotPaint.color = clearanceColor(clearanceM)
        canvas.drawCircle(o.x, o.y, (if (small) 4f else 6f) * dp, dotPaint)
        borderPaint.color = Color.WHITE; canvas.drawCircle(o.x, o.y, (if (small) 5.5f else 8f) * dp, borderPaint); borderPaint.color = BORDER
    }

    private fun drawHud(canvas: Canvas, s: PlanScene, w: Int) {
        val side = when (s.side) {
            PlanScene.Side.FRONT -> "Frente del rotor"; PlanScene.Side.BEHIND -> "Detrás del rotor (lado de la torre)"
            PlanScene.Side.LATERAL -> "Lateral del rotor"; PlanScene.Side.TOP -> "Vista cenital"
        }
        val lines = mutableListOf(side, "Mirando desde el ${PlanScene.cardinal(s.viewerBearingDeg)} · rotor hacia el ${PlanScene.cardinal(s.rotorFacingDeg)}",
            "Waypoint ${min(reachedIndex + 1, s.points.size)} / ${s.points.size}")
        if (clearanceM.isFinite()) lines += "Holgura a la turbina %.1f m".format(clearanceM)
        val pad = 8f * dp; val lh = textPaint.textSize * 1.45f
        val boxW = lines.maxOf { textPaint.measureText(it) } + 2 * pad
        rect.set(12f * dp, 12f * dp, 12f * dp + boxW, 12f * dp + lines.size * lh + pad)
        canvas.drawRoundRect(rect, 6f * dp, 6f * dp, chipPaint)
        lines.forEachIndexed { i, t ->
            textPaint.color = if (i == lines.lastIndex && clearanceM.isFinite()) clearanceColor(clearanceM) else TEXT
            canvas.drawText(t, rect.left + pad, rect.top + pad + lh * (i + 0.75f), textPaint)
        }
        textPaint.color = TEXT
    }

    /** Marco del vídeo en miniatura y la indicación para volver a la vista del dron. */
    private fun drawVideoFrame(canvas: Canvas, hole: RectF) {
        val r = 8f * dp
        borderPaint.color = Color.WHITE; borderPaint.strokeWidth = 2f * dp
        canvas.drawRoundRect(hole, r, r, borderPaint)
        borderPaint.color = BORDER; borderPaint.strokeWidth = 1.5f * dp
        textPaint.textSize = 11f * dp
        val text = "Cámara del dron · toque para volver"
        val tw = textPaint.measureText(text)
        label(canvas, hole.right - tw / 2 - 6f * dp, hole.top - 12f * dp, text, TEXT)
    }

    private fun label(canvas: Canvas, x: Float, y: Float, text: String, color: Int, bgColor: Int = CHIP) {
        val pad = 5f * dp; val tw = textPaint.measureText(text)
        rect.set(x - tw / 2 - pad, y - textPaint.textSize * 0.8f, x + tw / 2 + pad, y + textPaint.textSize * 0.55f)
        chipPaint.color = bgColor; canvas.drawRoundRect(rect, 4f * dp, 4f * dp, chipPaint); chipPaint.color = CHIP
        textPaint.color = color; textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, y + textPaint.textSize * 0.25f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.color = TEXT
    }

    companion object {
        private val BG = Color.rgb(14, 17, 22)
        private val GRID = Color.rgb(42, 50, 62)
        private val TEXT = Color.rgb(230, 233, 238)
        private val CHIP = Color.argb(225, 21, 26, 34)
        private val BORDER = Color.argb(200, 230, 233, 238)
        private val PENDING = Color.argb(150, 139, 149, 165)
        private val DONE = Color.rgb(48, 164, 108)
        private val WAYPOINT = Color.rgb(207, 214, 223)
        private val CAMERA = Color.rgb(240, 122, 69)

        /** Tamaño y margen de la miniatura del vídeo (la actividad coloca ahí el FPVWidget). */
        const val VIDEO_W_DP = 240f
        const val VIDEO_H_DP = 135f
        const val VIDEO_MARGIN_DP = 16f

        fun bladeColor(b: BladePosition) = when (b) {
            BladePosition.TWELVE -> Color.rgb(76, 194, 255); BladePosition.FOUR -> Color.rgb(255, 180, 84); else -> Color.rgb(183, 148, 246)
        }

        fun colorOf(k: WireKind) = when (k) {
            WireKind.BLADE_12 -> bladeColor(BladePosition.TWELVE); WireKind.BLADE_4 -> bladeColor(BladePosition.FOUR)
            WireKind.BLADE_8 -> bladeColor(BladePosition.EIGHT); WireKind.TOWER -> Color.rgb(125, 135, 149)
            WireKind.NACELLE -> Color.rgb(154, 164, 178); WireKind.HUB -> Color.rgb(201, 209, 220)
        }

        /** Holgura: rojo en 4.5 m o menos, ámbar hacia 12 m, verde en 25 m o más (misma escala que el panel). */
        fun clearanceColor(d: Double): Int {
            if (!d.isFinite()) return WAYPOINT
            val t = ((d - 4.5) / (25.0 - 4.5)).coerceIn(0.0, 1.0)
            val (c0, c1, f) = if (t < 0.5) Triple(intArrayOf(229, 72, 77), intArrayOf(245, 165, 36), t / 0.5) else Triple(intArrayOf(245, 165, 36), intArrayOf(48, 164, 108), (t - 0.5) / 0.5)
            return Color.rgb((c0[0] + (c1[0] - c0[0]) * f).toInt(), (c0[1] + (c1[1] - c0[1]) * f).toInt(), (c0[2] + (c1[2] - c0[2]) * f).toInt())
        }
    }
}
