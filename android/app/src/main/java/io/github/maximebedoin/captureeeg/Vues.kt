package io.github.maximebedoin.captureeeg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** zone claire (cercle au centre) entourée de gris : la tête doit la remplir */
class MasqueView(ctx: Context, private val zone: () -> Double) : View(ctx) {
    private val gris = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val bord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(217, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val chemin = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = (min(w, h) * zone() / 2).toFloat()
        chemin.reset()
        chemin.fillType = Path.FillType.EVEN_ODD
        chemin.addRect(0f, 0f, w, h, Path.Direction.CW)
        chemin.addCircle(w / 2, h / 2, r, Path.Direction.CW)
        c.drawPath(chemin, gris)
        c.drawCircle(w / 2, h / 2, r, bord)
    }
}

/** cadran vu du dessus : centre = sommet, bord = 45° sous l'horizontale, haut = face ; rouge → vert */
class CadranView(ctx: Context, private val remplissage: (Int) -> Float) : View(ctx) {
    var position: DoubleArray? = null  // [élévation, azimut relatif]
    private val fond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val blanc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val chemin = Path()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat(); val cx = s / 2
        val rr = cx - s * 0.04f
        blanc.textSize = s * 0.07f
        fun rayon(e: Double) = ((90 - min(e, 90.0)) / 135 * rr).toFloat()
        fun pt(r: Float, deg: Double) = floatArrayOf(
            cx + r * cos(Math.toRadians(deg)).toFloat(), cx + r * sin(Math.toRadians(deg)).toFloat())
        c.drawCircle(cx, cx, cx, fond)
        var d = 0
        for (b in BANDES) {
            for (k in 0 until b.n) {
                val a0 = k * 360.0 / b.n - 90; val a1 = (k + 1) * 360.0 / b.n - 90
                chemin.reset()
                for (i in 0..8) { val p = pt(rayon(b.max), a0 + (a1 - a0) * i / 8); if (i == 0) chemin.moveTo(p[0], p[1]) else chemin.lineTo(p[0], p[1]) }
                for (i in 8 downTo 0) { val p = pt(rayon(b.min), a0 + (a1 - a0) * i / 8); chemin.lineTo(p[0], p[1]) }
                chemin.close()
                val f = remplissage(d + k)
                plein.color = Color.HSVToColor(204, floatArrayOf(f * 120, 0.85f, 0.8f))
                c.drawPath(chemin, plein)
                c.drawPath(chemin, trait)
            }
            d += b.n
        }
        c.drawText("face", cx, blanc.textSize, blanc)
        position?.let {
            val p = pt(min(rayon(it[0]), rr + s * 0.02f), it[1] - 90)
            c.drawCircle(p[0], p[1], s * 0.03f, blanc)
        }
    }
}
