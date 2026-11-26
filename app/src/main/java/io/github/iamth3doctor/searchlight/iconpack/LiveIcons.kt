package io.github.iamth3doctor.searchlight.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * The theme's live clock and calendar, drawn by Searchlight in its own look: frosted glass (a pale,
 * slightly translucent face with a soft edge and shadow) and natural colors (ink, and a warm clay
 * red). Sized like the system's round icons, so they sit among them. A new bitmap and paints each
 * time: two icon threads may draw at once.
 */
object LiveIcons {

    /** The print: Searchlight's ink. */
    const val INK = 0xFF1C1C21.toInt()
    /** The warm accent: a natural clay red. */
    const val CLAY = 0xFFB9553D.toInt()
    /** The frosted glass, top to bottom, and its edge. */
    const val GLASS_TOP = 0xF7FBFBFC.toInt()
    const val GLASS_BOTTOM = 0xEDE6E9EE.toInt()
    const val EDGE = 0x24000000
    const val SHADOW = 0x38000000

    /** How much of the icon the round face fills (as the system's round icons do). */
    const val FACE = 0.44f

    /** A clock at [hm] (hours 0-23, minutes): ink hands on a frosted face, a clay center. */
    fun clock(size: Int, hm: Pair<Int, Int>): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        val cx = s / 2
        val cy = s / 2
        val r = s * FACE
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // The frosted face, with its soft shadow below and a fine edge.
        p.setShadowLayer(s * 0.03f, 0f, s * 0.015f, SHADOW)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.clearShadowLayer()
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.012f
        p.color = EDGE
        c.drawCircle(cx, cy, r - p.strokeWidth / 2, p)
        // The hours: twelve marks, the quarters longer.
        p.strokeCap = Paint.Cap.ROUND
        p.color = INK
        for (k in 0 until 12) {
            val a = Math.PI * 2 * k / 12
            val quarter = k % 3 == 0
            p.strokeWidth = s * if (quarter) 0.028f else 0.016f
            val r0 = r * if (quarter) 0.72f else 0.78f
            val r1 = r * 0.86f
            c.drawLine(cx + r0 * sin(a).toFloat(), cy - r0 * cos(a).toFloat(), cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(), p)
        }
        // The hands.
        val (ha, ma) = IconTheme.handAngles(hm.first, hm.second)
        fun hand(deg: Double, len: Float, width: Float) {
            val a = Math.toRadians(deg)
            p.strokeWidth = width
            c.drawLine(cx, cy, cx + len * sin(a).toFloat(), cy - len * cos(a).toFloat(), p)
        }
        p.color = INK
        hand(ha, r * 0.5f, s * 0.05f)
        hand(ma, r * 0.74f, s * 0.034f)
        p.style = Paint.Style.FILL
        p.color = CLAY
        c.drawCircle(cx, cy, s * 0.04f, p)
        return out
    }

    /**
     * Today's date for [now]: the day number on a frosted page, and above it (where there's room)
     * the month on a clay band.
     */
    fun calendar(size: Int, now: Long): Bitmap {
        val (month, day) = IconTheme.dateText(now)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        val inset = s * (0.5f - FACE)
        val page = RectF(inset, inset, s - inset, s - inset)
        val corner = s * 0.2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.setShadowLayer(s * 0.03f, 0f, s * 0.015f, SHADOW)
        p.shader = LinearGradient(0f, page.top, 0f, page.bottom, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRoundRect(page, corner, corner, p)
        p.clearShadowLayer()
        p.shader = null
        val roomForMonth = size >= 48
        if (roomForMonth) {
            // The band: the page's top, in clay.
            c.save()
            c.clipRect(page.left, page.top, page.right, page.top + page.height() * 0.3f)
            p.color = CLAY
            c.drawRoundRect(page, corner, corner, p)
            c.restore()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.012f
        p.color = EDGE
        val half = p.strokeWidth / 2
        c.drawRoundRect(RectF(page.left + half, page.top + half, page.right - half, page.bottom - half), corner, corner, p)
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        if (roomForMonth) {
            p.textSize = page.height() * 0.17f
            p.color = 0xFFFFFFFF.toInt()
            c.drawText(month, s / 2, page.top + page.height() * 0.15f + p.textSize * 0.36f, p)
        }
        p.textSize = page.height() * if (roomForMonth) 0.46f else 0.56f
        p.color = INK
        val mid = if (roomForMonth) page.top + page.height() * 0.65f else page.centerY()
        c.drawText(day, s / 2, mid + p.textSize * 0.36f, p)
        return out
    }
}
