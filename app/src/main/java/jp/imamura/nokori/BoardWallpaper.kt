package jp.imamura.nokori

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.SurfaceHolder
import java.io.File
import java.util.Locale

/**
 * ライブ壁紙「残り時間ボード」。ウィジェットと同じボードを、壁紙として画面の下半分に描く。
 * 壁紙はアプリ自身が描くので、ドット文字を秒まで使える。
 * 画面が見えている間だけ、秒の切り替わりに合わせて1秒ごとに描き直す（見えない間は止まる）。
 * 残りは毎回「目標日時 - 今の時刻」から計算するので、止まっていた時間があってもずれない。
 * ロック画面にも同じ壁紙が使われる機種なら、ロック画面にもボードが出る。
 */
class BoardWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = BoardEngine()

    companion object {
        /** 背景の写真の置き場所（設定画面で選んだものをアプリの中に写す） */
        fun bgFile(ctx: android.content.Context) = File(ctx.filesDir, "wallpaper_bg")

        private val AMBER = 0xFFFFB23E.toInt()
        private val URGENT = 0xFFFF6B5E.toInt()
        private val MUTED = 0xFF8A93A8.toInt()
        private val TITLE = 0xFFE7EAF2.toInt()
        private val PANEL = 0xE60B1020.toInt()
        private val LINE = 0xFF24304A.toInt()
        private val PLAIN_BG = 0xFF0A1122.toInt()

        /** 種類ラベルの（背景, 文字）色、進み具合バーの色（ウィジェットと同じ） */
        private fun chipColors(k: Kind) = when (k) {
            Kind.GAME -> 0xFF085041.toInt() to 0xFF9FE1CB.toInt()
            Kind.DEADLINE -> 0xFF712B13.toInt() to 0xFFF5C4B3.toInt()
            Kind.EVENT -> 0xFF0C447C.toInt() to 0xFFB5D4F4.toInt()
            Kind.AGE -> 0xFF72243E.toInt() to 0xFFF4C0D1.toInt()
            Kind.REPEAT -> 0xFF444441.toInt() to 0xFFD3D1C7.toInt()
            Kind.LIFE -> 0xFF3C3489.toInt() to 0xFFCECBF6.toInt()
        }

        private fun barColor(k: Kind) = when (k) {
            Kind.GAME -> 0xFF1D9E75.toInt()
            Kind.DEADLINE -> 0xFFD85A30.toInt()
            Kind.EVENT -> 0xFF378ADD.toInt()
            Kind.AGE -> 0xFFD4537E.toInt()
            Kind.REPEAT -> 0xFFB4B2A9.toInt()
            Kind.LIFE -> 0xFF7F77DD.toInt()
        }
    }

    inner class BoardEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0

        private var items: List<Item> = emptyList()
        private var itemsLoadedAt = 0L

        private var bg: Bitmap? = null
        private var bgStamp = -1L

        private val dot: Typeface by lazy { resources.getFont(R.font.dotgothic16) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        private val tick = object : Runnable {
            override fun run() {
                draw()
                if (visible) {
                    // 次の秒の切り替わりの少しあと（期日は秒ちょうどなので、残りの秒もそこで変わる）
                    val now = System.currentTimeMillis()
                    handler.postDelayed(this, 1000 - now % 1000 + 15)
                }
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) {
                itemsLoadedAt = 0L // 見え始めたら項目を読み直す（アプリで変えた内容を反映）
                tick.run()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            bgStamp = -1L
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
        private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

        private fun text(size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = dot; textSize = sp(size); this.color = color
        }

        /** 出す項目：ウィジェットと同じく、固定した項目＋近い順（件数はウィジェットの設定） */
        private fun loadItems(now: Long) {
            if (now - itemsLoadedAt < 60_000L) return
            val s = Settings(this@BoardWallpaper)
            val all = Calc.sorted(Store.load(this@BoardWallpaper) + Repeats.build(this@BoardWallpaper, now), now)
                .filter { (it.targetMillis() ?: Long.MIN_VALUE) > now }
            val pinned = s.pinned.mapNotNull { pid -> all.firstOrNull { it.id == pid } }.take(Settings.MAX_PINNED)
            val ids = pinned.map { it.id }.toSet()
            val rest = all.filter { it.id !in ids }.filter { it.showInWidget && s.widgetShows(it.kind) }
            items = (pinned + rest).take(maxOf(s.widgetCount, pinned.size))
            itemsLoadedAt = now
        }

        /** 背景の写真（選んでいれば）。画面の大きさに合わせて縮小して読む */
        private fun loadBg() {
            val f = bgFile(this@BoardWallpaper)
            val stamp = if (f.exists()) f.lastModified() else 0L
            if (stamp == bgStamp) return
            bgStamp = stamp
            bg?.recycle()
            bg = null
            if (stamp == 0L || width == 0) return
            bg = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // 写真の向き（縦横）の情報も反映される
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
                        val scale = maxOf(width.toFloat() / info.size.width, height.toFloat() / info.size.height)
                        if (scale < 1f) dec.setTargetSize(
                            maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    BitmapFactory.decodeFile(f.path)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun draw() {
            val holder = surfaceHolder
            if (width == 0 || height == 0) return
            val now = System.currentTimeMillis()
            loadItems(now)
            loadBg()
            val c: Canvas = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
            try {
                drawBackground(c)
                drawBoard(c, now)
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { }
            }
        }

        /** 背景：写真を画面いっぱいに（はみ出す部分は切る）。写真がなければ濃紺 */
        private fun drawBackground(c: Canvas) {
            val b = bg
            if (b == null) {
                c.drawColor(PLAIN_BG)
                return
            }
            val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale
            val h = b.height * scale
            val left = (width - w) / 2
            val top = (height - h) / 2
            c.drawBitmap(b, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        /** ボード：画面の下半分に、ウィジェットと同じ並びで描く */
        private fun drawBoard(c: Canvas, now: Long) {
            val margin = dp(16f)
            val padX = dp(14f)
            val padY = dp(10f)
            val panelLeft = margin
            val panelRight = width - margin
            val innerLeft = panelLeft + padX
            val innerRight = panelRight - padX

            val header = text(11f, MUTED).apply { letterSpacing = 0.08f }
            val titleP = text(16f, TITLE)
            val chipP = text(11f, 0)
            val bigP = text(26f, AMBER)
            val unitP = text(12f, MUTED)

            fun lineH(p: Paint) = p.fontMetrics.let { it.descent - it.ascent }

            // 先に高さを計算してから、パネルを描く
            val rows = items.mapNotNull { item -> Calc.parts(item, now)?.let { item to it } }
            val rowH = dp(8f) + maxOf(lineH(titleP), lineH(chipP) + dp(2f)) + dp(2f) +
                lineH(bigP) + dp(4f) + dp(3f)
            val headerH = lineH(header) + dp(5f) + dp(1f)
            val emptyH = if (rows.isEmpty()) dp(8f) + lineH(text(13f, MUTED)) else 0f
            val panelH = padY * 2 + headerH + rows.size * rowH + emptyH
            // 上はロック画面の時計を避けて画面の42%から。下にはみ出す場合は上へずらす
            val bottomLimit = height - dp(72f)
            var top = height * 0.42f
            if (top + panelH > bottomLimit) top = maxOf(dp(24f), bottomLimit - panelH)

            fill.color = PANEL
            c.drawRoundRect(RectF(panelLeft, top, panelRight, top + panelH), dp(18f), dp(18f), fill)

            var y = top + padY
            // 見出し
            val hfm = header.fontMetrics
            val t = java.time.LocalTime.now(Calc.zone())
            val time = String.format(Locale.JAPAN, "%02d:%02d", t.hour, t.minute)
            c.drawText("残り時間", innerLeft, y - hfm.ascent, header)
            c.drawText(time, innerRight - header.measureText(time), y - hfm.ascent, header)
            y += lineH(header) + dp(5f)
            fill.color = LINE
            c.drawRect(innerLeft, y, innerRight, y + dp(1f), fill)
            y += dp(1f)

            if (rows.isEmpty()) {
                val p = text(13f, MUTED)
                y += dp(8f)
                c.drawText("アプリで発売日や締切を登録してください", innerLeft, y - p.fontMetrics.ascent, p)
                return
            }

            for ((item, parts) in rows) {
                y += dp(8f)
                val target = item.targetMillis()!!
                val left = target - now
                val color = if (item.kind == Kind.DEADLINE && left < 7 * Calc.DAY_MS) URGENT else AMBER

                // 1行目：種類ラベル＋名前
                val (chipBg, chipFg) = chipColors(item.kind)
                chipP.color = chipFg
                val chipW = chipP.measureText(item.kind.label) + dp(12f)
                val chipH = lineH(chipP) + dp(2f)
                val line1H = maxOf(lineH(titleP), chipH)
                val chipTop = y + (line1H - chipH) / 2
                fill.color = chipBg
                c.drawRoundRect(RectF(innerLeft, chipTop, innerLeft + chipW, chipTop + chipH), dp(4f), dp(4f), fill)
                c.drawText(item.kind.label, innerLeft + dp(6f), chipTop + dp(1f) - chipP.fontMetrics.ascent, chipP)
                val titleLeft = innerLeft + chipW + dp(8f)
                val shown = TextUtils.ellipsize(item.title, titleP, innerRight - titleLeft, TextUtils.TruncateAt.END).toString()
                c.drawText(shown, titleLeft, y + (line1H - lineH(titleP)) / 2 - titleP.fontMetrics.ascent, titleP)
                y += line1H + dp(2f)

                // 2行目：大きい数字＋単位＋時:分:秒（右寄せ、下揃え）
                bigP.color = color
                val clockP = text(DotRenderer.clockSp(parts), color)
                val segs = mutableListOf<Triple<String, Paint, Float>>()
                parts.big?.let { segs += Triple(it, bigP, dp(3f)) }
                parts.unit?.let { if (parts.big != null) segs += Triple(it, unitP, dp(8f)) }
                parts.clockMs?.let { segs += Triple(Calc.hms(it), clockP, 0f) }
                if (segs.isEmpty()) segs += Triple("-", clockP, 0f)
                val baseline = y - bigP.fontMetrics.ascent
                var x = innerRight - segs.sumOf { (s, p, g) -> (p.measureText(s) + g).toDouble() }.toFloat()
                for ((s, p, g) in segs) {
                    c.drawText(s, x, baseline, p)
                    x += p.measureText(s) + g
                }
                y += lineH(bigP) + dp(4f)

                // 進み具合バー
                val start = item.startMillis()
                if (start != null && target > start) {
                    val ratio = ((now - start).toFloat() / (target - start)).coerceIn(0f, 1f)
                    fill.color = LINE
                    c.drawRoundRect(RectF(innerLeft, y, innerRight, y + dp(3f)), dp(2f), dp(2f), fill)
                    fill.color = barColor(item.kind)
                    c.drawRoundRect(RectF(innerLeft, y, innerLeft + (innerRight - innerLeft) * ratio, y + dp(3f)),
                        dp(2f), dp(2f), fill)
                }
                y += dp(3f)
            }
        }
    }
}
