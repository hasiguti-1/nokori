package jp.imamura.nokori

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import java.util.Locale

/**
 * ドット文字（DotGothic16）で文字を画像に描く。
 * ホーム画面がアプリ同梱の字体を受け付けない機種でも、画像なら表示できる。
 */
object DotRenderer {
    private var cached: Typeface? = null
    val MUTED = 0xFF8A93A8.toInt()
    val TITLE = 0xFFE7EAF2.toInt()

    private fun typeface(ctx: Context): Typeface =
        cached ?: ctx.resources.getFont(R.font.dotgothic16).also { cached = it }

    private fun px(ctx: Context, sp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)

    /** 画面の密度に合わせた空の画像（ホーム画面で拡大縮小されないように） */
    private fun blank(ctx: Context, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(maxOf(1, w), maxOf(1, h), Bitmap.Config.ARGB_8888).apply {
            density = ctx.resources.displayMetrics.densityDpi
        }

    fun dp(ctx: Context, dp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics)

    private fun paint(ctx: Context, sp: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typeface(ctx)
        textSize = px(ctx, sp)
        this.color = color
    }

    /** 1行の文字。maxWidthPx を超えるときは末尾を「…」にする */
    fun text(ctx: Context, s: String, sp: Float, color: Int, maxWidthPx: Int): Bitmap {
        val p = paint(ctx, sp, color)
        val shown = TextUtils.ellipsize(s, p, maxWidthPx.toFloat(), TextUtils.TruncateAt.END).toString()
        val fm = p.fontMetricsInt
        val w = maxOf(1, Math.ceil(p.measureText(shown).toDouble()).toInt())
        val h = fm.descent - fm.ascent
        val bmp = blank(ctx, w, h)
        Canvas(bmp).drawText(shown, 0f, -fm.ascent.toFloat(), p)
        return bmp
    }

    /** 見出し：左に「残り時間」、右に今の時刻（時:分） */
    fun header(ctx: Context, widthPx: Int, time: String): Bitmap {
        val p = paint(ctx, 11f, MUTED).apply { letterSpacing = 0.08f }
        val fm = p.fontMetricsInt
        val h = fm.descent - fm.ascent
        val bmp = blank(ctx, widthPx, h)
        val c = Canvas(bmp)
        c.drawText("残り時間", 0f, -fm.ascent.toFloat(), p)
        c.drawText(time, widthPx - p.measureText(time), -fm.ascent.toFloat(), p)
        return bmp
    }

    /** 数字画像と、後ろに並べる時計の文字の大きさ。bitmap が null なら画像は出さない */
    data class Number(val bitmap: Bitmap?, val clockSp: Float)

    /** 時計の字の大きさ（時計だけのとき＝「今日」などは大きい数字と同じ大きさに） */
    fun clockSp(parts: LeftParts): Float = if (parts.big == null) 26f else 18f

    /**
     * 残りの数字。大きい数字＋単位（小さく灰色）＋時:分 を下揃えで並べる。
     * clockByChrono のときは時計を画像に入れない（後ろに標準の時計部品で「時:分:秒」が並ぶ）。
     * そのときは画像の下の余白を等幅の字体にそろえ、横に並べたときに文字の下端が合うようにする
     */
    fun number(ctx: Context, parts: LeftParts, color: Int, clockByChrono: Boolean = false): Number {
        data class Seg(val s: String, val paint: TextPaint, val gapAfter: Float)
        val segs = mutableListOf<Seg>()
        val clockSp = clockSp(parts)
        val chrono = clockByChrono && parts.clockMs != null
        parts.big?.let { segs += Seg(it, paint(ctx, 26f, color), dp(ctx, 3f)) }
        parts.unit?.let { if (parts.big != null) segs += Seg(it, paint(ctx, 12f, MUTED), dp(ctx, 8f)) }
        if (!chrono) parts.clockMs?.let { segs += Seg(hm(it), paint(ctx, clockSp, color), 0f) }
        if (segs.isEmpty()) {
            if (chrono) return Number(null, clockSp) // 時計だけの行は画像なし
            segs += Seg("-", paint(ctx, 18f, color), 0f)
        }

        val ascent = segs.maxOf { -it.paint.fontMetricsInt.ascent }
        val descent = if (chrono) {
            // 標準の等幅字体の、文字の下端から行の下までの高さ
            TextPaint().apply { typeface = Typeface.MONOSPACE; textSize = px(ctx, clockSp) }.fontMetricsInt.descent
        } else segs.maxOf { it.paint.fontMetricsInt.descent }
        val width = segs.sumOf { (it.paint.measureText(it.s) + it.gapAfter).toDouble() }
        val bmp = blank(ctx, Math.ceil(width).toInt(), ascent + descent)
        val c = Canvas(bmp)
        var x = 0f
        for (seg in segs) {
            c.drawText(seg.s, x, ascent.toFloat(), seg.paint)
            x += seg.paint.measureText(seg.s) + seg.gapAfter
        }
        return Number(bmp, clockSp)
    }

    /** ミリ秒を「時:分」に（秒は切り捨て） */
    fun hm(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        return String.format(Locale.JAPAN, "%d:%02d", h, m)
    }
}
