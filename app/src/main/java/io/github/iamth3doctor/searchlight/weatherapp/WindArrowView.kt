package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import io.github.iamth3doctor.searchlight.weather.ScenePhysics
import kotlin.math.atan2
import kotlin.math.min

/**
 * The wind, as an arrow pointing the way it physically blows (north up: a north-easterly points
 * down and to the left), longer the stronger it is, with a faint compass ring. Eases to new
 * directions.
 */
class WindArrowView(context: Context) : View(context) {

    /** Where the wind blows to (0 north, 90 east), as drawn. */
    var towardDeg = 0.0
        private set
    var mph = 0.0
        private set

    var color = 0xFFFFFFFF.toInt()
        set(value) { field = value; invalidate() }

    fun set(towardDeg: Double, mph: Double) {
        this.towardDeg = towardDeg
        this.mph = mph
        contentDescription = context.getString(io.github.iamth3doctor.searchlight.R.string.weather_wind_arrow_desc,
            Math.round(mph).toInt(), compass8(towardDeg))
        invalidate()
    }

    private fun compass8(deg: Double) = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[
        ((((deg % 360) + 360) % 360) / 45 + 0.5).toInt() % 8]

    /** The arrow's screen angle (degrees clockwise from straight up). */
    val screenAngle: Double get() {
        val (x, y) = ScenePhysics.arrow(towardDeg)
        return Math.toDegrees(atan2(x, -y))
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.82f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.06f
        paint.color = (color and 0x00FFFFFF) or 0x40000000
        canvas.drawCircle(cx, cy, r, paint)
        // Calm: just a dot.
        paint.style = Paint.Style.FILL
        paint.color = color
        if (mph < 1) {
            canvas.drawCircle(cx, cy, r * 0.12f, paint)
            return
        }
        val len = r * (0.55f + (mph / 30.0).coerceIn(0.0, 1.0).toFloat() * 0.4f)
        canvas.save()
        canvas.rotate(screenAngle.toFloat(), cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.12f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy + len * 0.75f, cx, cy - len * 0.55f, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx, cy - len)
        path.lineTo(cx - r * 0.3f, cy - len * 0.45f)
        path.lineTo(cx + r * 0.3f, cy - len * 0.45f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
