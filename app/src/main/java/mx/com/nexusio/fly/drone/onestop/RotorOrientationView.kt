package mx.com.nexusio.fly.drone.onestop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import mx.com.nexusio.fly.R

/**
 * Selector de orientación de palas sobre la foto real del rotor (turbine_rotor.png).
 * En cada punta (12h, 4h, 8h) hay un botón que cicla ? → A → B → C.
 * La geometría de las puntas vive en [RotorTipLayout].
 */
class RotorOrientationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val imagePadding = dp(8f)
    private val tipRadius = dp(12f)
    private val touchRadius = dp(24f)
    private val corner = dp(6f)

    private val turbine: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.turbine_rotor)?.mutate()?.apply { alpha = 235 }
    private val imageRect = Rect()
    private val frameRect = RectF()

    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.nx_border_subtle)
    }
    private val paintAssigned = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.nx_accent) }
    private val paintHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.nx_accent_halo) }
    private val paintPending = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.nx_surface_raised) }
    private val paintPendingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.nx_border_strong)
    }
    private val paintLetter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9.5f)
        letterSpacing = 0.18f
        color = color(R.color.nx_accent_muted)
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        textAlign = Paint.Align.RIGHT
        color = color(R.color.nx_fg_muted)
    }
    private val colorLetterPending = color(R.color.nx_fg_muted)

    // Index 0: 12h, Index 1: 4h, Index 2: 8h (mismo orden que RotorTipLayout.TIPS_NORM)
    private val assignedLabels = mutableListOf("?", "?", "?")
    private val allowedOptions = listOf("?", "A", "B", "C")

    var onOrientationChangedListener: ((map: Map<String, String>) -> Unit)? = null

    private companion object {
        const val MIN_HEIGHT_FOR_TEXT_DP = 170f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameRect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))
        paintGlow.shader = RadialGradient(
            w / 2f, h * 0.6f, maxOf(w, h, 1) * 0.6f,
            Color.argb(0x1A, 0x2F, 0x7B, 0xFE), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        val box = RotorTipLayout.imageBox(w.toFloat(), h.toFloat(), imagePadding)
        imageRect.set(box.left.toInt(), box.top.toInt(), (box.left + box.width).toInt(), (box.top + box.height).toInt())
        turbine?.bounds = imageRect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(frameRect, corner, corner, paintGlow)
        canvas.drawRoundRect(frameRect, corner, corner, paintFrame)
        turbine?.draw(canvas)

        // En vistas bajas (mando en horizontal) los textos chocarían con los botones de las puntas
        if (height >= dp(MIN_HEIGHT_FOR_TEXT_DP)) {
            canvas.drawText("ORIENTACIÓN DE PALAS", dp(8f), dp(14f), paintLabel)
            canvas.drawText("Toca cada punta para asignar A · B · C", width - dp(8f), height - dp(8f), paintHint)
        }

        val tips = RotorTipLayout.tipCenters(width.toFloat(), height.toFloat(), imagePadding)
        tips.forEachIndexed { i, p ->
            val label = assignedLabels[i]
            if (label == "?") {
                canvas.drawCircle(p.x, p.y, tipRadius, paintPending)
                canvas.drawCircle(p.x, p.y, tipRadius, paintPendingStroke)
                paintLetter.color = colorLetterPending
            } else {
                canvas.drawCircle(p.x, p.y, tipRadius + dp(3f), paintHalo)
                canvas.drawCircle(p.x, p.y, tipRadius, paintAssigned)
                paintLetter.color = Color.WHITE
            }
            val textY = p.y - (paintLetter.descent() + paintLetter.ascent()) / 2
            canvas.drawText(label, p.x, textY, paintLetter)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val index = RotorTipLayout.hitTip(
                event.x, event.y, width.toFloat(), height.toFloat(), touchRadius, imagePadding
            )
            if (index != null) {
                cycleLabelAt(index)
                invalidate()
                notifyListener()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun cycleLabelAt(index: Int) {
        // Cicla entre ?, A, B y C
        val currentIndex = allowedOptions.indexOf(assignedLabels[index])
        assignedLabels[index] = allowedOptions[(currentIndex + 1) % allowedOptions.size]
    }

    fun isMappingValid(): Boolean {
        // Es válido SOLO si se asignó A, B y C exactamente una vez (sin repetir y sin dejar "?")
        return assignedLabels.containsAll(listOf("A", "B", "C")) && !assignedLabels.contains("?")
    }

    private fun notifyListener() {
        onOrientationChangedListener?.invoke(getBladeMap())
    }

    fun getBladeMap(): Map<String, String> = mapOf(
        "12H" to assignedLabels[0],
        "4H" to assignedLabels[1],
        "8H" to assignedLabels[2]
    )
}
