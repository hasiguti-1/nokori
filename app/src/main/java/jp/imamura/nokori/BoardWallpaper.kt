package jp.imamura.nokori

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
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
 * 「固定＋切り替え」の動きや、名前を流す表示の間だけは、なめらかに描き直す。
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

        /** 切り替えの動きの長さ（パタパタ以外） */
        private const val ANIM_MS = 450L
        /** パタパタ：列の幅、列ごとの遅れ、1列がめくれる時間 */
        private const val FLAP_CELL_DP = 12f
        private const val FLAP_STAGGER = 14f
        private const val FLAP_FLIP = 220f
        /** 電光掲示板の流れる速さ（dp/秒） */
        private const val MARQUEE_DP_PER_SEC = 40f

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

        /** いつも出す行（固定＋近い順。切り替えのときは固定だけ） */
        private var staticItems: List<Item> = emptyList()
        /** 「固定＋切り替え」で順に出す項目（切り替えないときは空） */
        private var rotItems: List<Item> = emptyList()
        private var periodMs = 10_000L
        private var anim = RotateAnim.NONE
        private var marquee = false
        private var rotate = false
        private var pinnedItems: List<Item> = emptyList()
        private var restItems: List<Item> = emptyList()
        private var scene: Scene? = null
        private var sceneCache: Pair<Scene, Bitmap>? = null
        private var itemsLoadedAt = 0L

        private var bg: Bitmap? = null
        private var bgStamp = -1L

        private val dot: Typeface by lazy { resources.getFont(R.font.dotgothic16) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        private val tick = object : Runnable {
            override fun run() {
                val delay = draw()
                if (visible) handler.postDelayed(this, delay)
            }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(tick)
            if (v) {
                itemsLoadedAt = 0L // 見え始めたら項目と設定を読み直す（アプリで変えた内容を反映）
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

        private fun lineH(p: Paint) = p.fontMetrics.let { it.descent - it.ascent }

        /** 出す項目：ウィジェットと同じく、固定した項目＋近い順（件数・切り替えはウィジェットの設定） */
        private fun loadItems(now: Long) {
            if (now - itemsLoadedAt < 60_000L) return
            val s = Settings(this@BoardWallpaper)
            val all = Calc.sorted(Store.load(this@BoardWallpaper) + Repeats.build(this@BoardWallpaper, now), now)
                .filter { (it.targetMillis() ?: Long.MIN_VALUE) > now }
            val pinned = s.pinned.mapNotNull { pid -> all.firstOrNull { it.id == pid } }.take(Settings.MAX_PINNED)
            val ids = pinned.map { it.id }.toSet()
            val rest = all.filter { it.id !in ids }.filter { it.showInWidget && s.widgetShows(it.kind) }
            if (s.rotate && rest.isNotEmpty()) {
                staticItems = pinned
                rotItems = rest
            } else {
                staticItems = (pinned + rest).take(maxOf(s.widgetCount, pinned.size))
                rotItems = emptyList()
            }
            periodMs = s.rotateSec * 1000L
            anim = s.rotateAnim
            marquee = s.wallMarquee
            rotate = s.rotate
            pinnedItems = pinned
            restItems = rest
            scene = s.wallScene
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

        /** 1回描く。戻り値は次に描くまでの時間（ミリ秒） */
        private fun draw(): Long {
            val holder = surfaceHolder
            if (width == 0 || height == 0) return 1000L
            val now = System.currentTimeMillis()
            loadItems(now)
            loadBg()
            val c: Canvas = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return 1000L
            var delay = 1000L
            try {
                val sc = scene
                if (sc != null) {
                    delay = drawScene(c, now, sc)
                } else {
                    drawBackground(c)
                    delay = drawBoard(c, now)
                }
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { }
            }
            return delay
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

        // ── 寸法（毎回同じ） ──
        private val margin get() = dp(16f)
        private val padX get() = dp(14f)
        private val padY get() = dp(10f)
        /** パネルの左右（壁紙では画面の余白の内側、シーンでは表示面の中の座標） */
        private var boxL = 0f
        private var boxR = 0f
        private val panelLeft get() = boxL
        private val panelRight get() = boxR
        /** 数字・名前の色と、電光掲示板らしいにじみ（シーンごと） */
        private var ledColor = AMBER
        private var titleColor = TITLE
        private var glow = false
        private val innerLeft get() = panelLeft + padX
        private val innerRight get() = panelRight - padX

        private val header by lazy { text(11f, MUTED).apply { letterSpacing = 0.08f } }
        private val titleP by lazy { text(16f, TITLE) }
        private val chipP by lazy { text(11f, 0) }
        private val bigP by lazy { text(26f, AMBER) }
        private val unitP by lazy { text(12f, MUTED) }
        private val posP by lazy { text(11f, MUTED) }

        /** 1行の高さ（上の余白8dpを含む） */
        private fun rowH() = dp(8f) + maxOf(lineH(titleP), lineH(chipP) + dp(2f)) + dp(2f) +
            lineH(bigP) + dp(4f) + dp(3f)

        /** ボード：画面の下半分に、ウィジェットと同じ並びで描く。戻り値は次に描くまでの時間 */
        private fun drawBoard(c: Canvas, now: Long): Long {
            boxL = margin
            boxR = width - margin
            ledColor = AMBER
            titleColor = TITLE
            glow = false
            val (fixed, pool) = layoutRows(null)
            val rowCount = fixed.size + (if (pool.isNotEmpty()) 1 else 0)
            val panelH = contentHeight(rowCount, true, rowCount == 0)
            // 上はロック画面の時計を避けて画面の42%から。下にはみ出す場合は上へずらす
            val bottomLimit = height - dp(72f)
            var top = height * 0.42f
            if (top + panelH > bottomLimit) top = maxOf(dp(24f), bottomLimit - panelH)
            fill.color = PANEL
            c.drawRoundRect(RectF(panelLeft, top, panelRight, top + panelH), dp(18f), dp(18f), fill)
            return drawContent(c, now, top, true, fixed, pool)
        }

        /**
         * シーン：掲示板のある風景の絵を画面いっぱいに描き、絵の中の表示面（四隅）に
         * ボードの中身をぴったり重ねる（斜めの表示面でも、四隅に合わせてゆがめて描く）
         */
        private fun drawScene(c: Canvas, now: Long, sc: Scene): Long {
            val b = sceneBitmap(sc)
            val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val ox = (width - b.width * scale) / 2
            val oy = (height - b.height * scale) / 2
            // ドット絵なので、ぼかさずに拡大する
            c.drawBitmap(b, null, RectF(ox, oy, ox + b.width * scale, oy + b.height * scale), Paint())

            // 表示面の四隅（画面の座標）
            val q = FloatArray(8) { i -> if (i % 2 == 0) ox + sc.quad[i] * scale else oy + sc.quad[i] * scale }
            fun dist(i: Int, j: Int) = Math.hypot((q[2 * i] - q[2 * j]).toDouble(), (q[2 * i + 1] - q[2 * j + 1]).toDouble()).toFloat()
            val wq = (dist(0, 1) + dist(3, 2)) / 2
            val hq = (dist(0, 3) + dist(1, 2)) / 2

            ledColor = sc.led
            titleColor = sc.title
            glow = true
            val (fixed, pool) = layoutRows(sc.rows)
            // 中身の高さは行数で決まる。幅は表示面の縦横比に合わせる（文字が伸び縮みしないように）
            val hc = contentHeight(sc.rows, sc.header, false)
            val wc = hc * wq / hq
            boxL = 0f
            boxR = wc
            val m = Matrix()
            m.setPolyToPoly(floatArrayOf(0f, 0f, wc, 0f, wc, hc, 0f, hc), 0, q, 0, 4)
            c.save()
            c.concat(m)
            c.clipRect(0f, 0f, wc, hc)
            val delay = drawContent(c, now, 0f, sc.header, fixed, pool)
            c.restore()
            return delay
        }

        /** シーンの絵（一度読んだら使い回す） */
        private fun sceneBitmap(sc: Scene): Bitmap {
            sceneCache?.let { (k, bmp) -> if (k == sc) return bmp }
            val bmp = BitmapFactory.decodeResource(resources, sc.image, BitmapFactory.Options().apply { inScaled = false })
            sceneCache = sc to bmp
            return bmp
        }

        /**
         * 出す行を決める。maxRows が null なら壁紙の設定どおり（ウィジェットと同じ）。
         * シーンでは表示面の行数に収める（切り替えるときは、最後の1行を切り替えに使う）。
         * 戻り値は（いつも出す行, 切り替えで順に出す項目）
         */
        private fun layoutRows(maxRows: Int?): Pair<List<Item>, List<Item>> {
            if (maxRows == null) return staticItems to rotItems
            return if (rotate && (pinnedItems.size + restItems.size) > maxRows) {
                val fixed = pinnedItems.take(maxRows - 1)
                fixed to (pinnedItems.drop(maxRows - 1) + restItems)
            } else {
                (pinnedItems + restItems).take(maxRows) to emptyList()
            }
        }

        /** 中身の高さ（上下の余白・見出し・行） */
        private fun contentHeight(rows: Int, header: Boolean, empty: Boolean): Float {
            val headerH = if (header) lineH(this.header) + dp(5f) + dp(1f) else 0f
            val emptyH = if (empty) dp(8f) + lineH(text(13f, MUTED)) else 0f
            return padY * 2 + headerH + rows * rowH() + emptyH
        }

        /** 見出しと行を top から描く（左右は boxL〜boxR）。戻り値は次に描くまでの時間 */
        private fun drawContent(c: Canvas, now: Long, top: Float, showHeader: Boolean,
                                fixed: List<Item>, pool: List<Item>): Long {
            val rot = pool.isNotEmpty()
            val rowCount = fixed.size + (if (rot) 1 else 0)
            val rh = rowH()
            var y = top + padY
            if (showHeader) {
                val hfm = header.fontMetrics
                val t = java.time.LocalTime.now(Calc.zone())
                val time = String.format(Locale.JAPAN, "%02d:%02d", t.hour, t.minute)
                c.drawText("残り時間", innerLeft, y - hfm.ascent, header)
                c.drawText(time, innerRight - header.measureText(time), y - hfm.ascent, header)
                y += lineH(header) + dp(5f)
                fill.color = LINE
                c.drawRect(innerLeft, y, innerRight, y + dp(1f), fill)
                y += dp(1f)
            }

            // 次に描くまで：ふだんは次の秒の切り替わりの少しあと（期日は秒ちょうどなので、残りの秒もそこで変わる）
            var delay = 1000 - now % 1000 + 15
            if (rowCount == 0) {
                val p = text(13f, MUTED)
                c.drawText("アプリで発売日や締切を登録してください", innerLeft, y + dp(8f) - p.fontMetrics.ascent, p)
                return delay
            }

            var moving = false
            for (item in fixed) {
                if (drawRow(c, item, now, y, null)) moving = true
                y += rh
            }
            if (rot) {
                val n = pool.size
                val idx = RotateMath.index(now, periodMs, n)
                val cur = pool[idx]
                val curPos = if (n > 1) "${idx + 1}/$n" else null
                val cells = maxOf(1, Math.ceil(((panelRight - panelLeft) / dp(FLAP_CELL_DP)).toDouble()).toInt())
                val dur = if (anim == RotateAnim.FLAP) RotateMath.flapDuration(cells, FLAP_STAGGER, FLAP_FLIP) else ANIM_MS
                val p = if (anim != RotateAnim.NONE && n > 1) RotateMath.progress(now, periodMs, dur) else null
                if (p == null) {
                    if (drawRow(c, cur, now, y, curPos)) moving = true
                } else {
                    val pi = RotateMath.prevIndex(now, periodMs, n)
                    drawTransition(c, now, y, rh, pool[pi], "${pi + 1}/$n", cur, curPos, p, cells,
                        RotateMath.elapsed(now, periodMs))
                    // 動きの最中はなめらかに（約60回/秒）
                    return 16L
                }
                // 次の切り替えの始まりに合わせて描く
                if (n > 1) delay = minOf(delay, RotateMath.untilNext(now, periodMs) + 5)
            }
            // 名前が流れているときは約30回/秒
            if (moving) delay = minOf(delay, 33L)
            return delay
        }

        /** 切り替えの動き（古い行 → 新しい行）。行の範囲の外には描かない */
        private fun drawTransition(
            c: Canvas, now: Long, top: Float, rh: Float,
            old: Item, oldPos: String, cur: Item, curPos: String?, p: Float, cells: Int, elapsedMs: Long,
        ) {
            val rect = RectF(panelLeft, top, panelRight, top + rh)
            val w = rect.width()
            val ease = 1f - (1f - p) * (1f - p) // 終わりに向けてゆっくり
            c.save()
            c.clipRect(rect)
            when (anim) {
                RotateAnim.SLIDE_UP -> {
                    c.save(); c.translate(0f, -ease * rh); drawRow(c, old, now, top, oldPos); c.restore()
                    c.save(); c.translate(0f, (1f - ease) * rh); drawRow(c, cur, now, top, curPos); c.restore()
                }
                RotateAnim.SLIDE_LEFT -> {
                    c.save(); c.translate(-ease * w, 0f); drawRow(c, old, now, top, oldPos); c.restore()
                    c.save(); c.translate((1f - ease) * w, 0f); drawRow(c, cur, now, top, curPos); c.restore()
                }
                RotateAnim.SLIDE_RIGHT -> {
                    c.save(); c.translate(ease * w, 0f); drawRow(c, old, now, top, oldPos); c.restore()
                    c.save(); c.translate(-(1f - ease) * w, 0f); drawRow(c, cur, now, top, curPos); c.restore()
                }
                RotateAnim.FADE -> {
                    c.saveLayerAlpha(rect, ((1f - p) * 255).toInt()); drawRow(c, old, now, top, oldPos); c.restore()
                    c.saveLayerAlpha(rect, (p * 255).toInt()); drawRow(c, cur, now, top, curPos); c.restore()
                }
                RotateAnim.ZOOM -> {
                    val cx = rect.centerX(); val cy = rect.centerY()
                    c.saveLayerAlpha(rect, ((1f - p) * 255).toInt())
                    c.scale(1f + 0.2f * p, 1f + 0.2f * p, cx, cy); drawRow(c, old, now, top, oldPos); c.restore()
                    c.saveLayerAlpha(rect, (p * 255).toInt())
                    c.scale(0.6f + 0.4f * ease, 0.6f + 0.4f * ease, cx, cy); drawRow(c, cur, now, top, curPos); c.restore()
                }
                RotateAnim.FLAP -> {
                    // 本格パタパタ：行を細い列に分け、左から順に1列ずつめくる。
                    // めくる途中は、古い表示が縦につぶれて消え、新しい表示が縦に開いて出る
                    val cw = w / cells
                    val cy = rect.centerY()
                    for (k in 0 until cells) {
                        val pk = RotateMath.flapCell(elapsedMs.toFloat(), k, FLAP_STAGGER, FLAP_FLIP)
                        val x0 = rect.left + k * cw
                        c.save()
                        c.clipRect(x0, rect.top, x0 + cw + 0.5f, rect.bottom)
                        if (pk < 0.5f) {
                            c.scale(1f, 1f - 2f * pk, 0f, cy); drawRow(c, old, now, top, oldPos)
                        } else {
                            c.scale(1f, 2f * pk - 1f, 0f, cy); drawRow(c, cur, now, top, curPos)
                        }
                        c.restore()
                        // めくれ目の線（板の継ぎ目）
                        if (pk > 0f && pk < 1f) {
                            fill.color = LINE
                            c.drawRect(x0, cy - dp(0.5f), x0 + cw, cy + dp(0.5f), fill)
                        }
                    }
                }
                RotateAnim.NONE -> drawRow(c, cur, now, top, curPos)
            }
            c.restore()
        }

        /**
         * 1件分の行を top から描く（上の余白8dpを含む）。
         * 戻り値は、名前を電光掲示板のように流している（なめらかに描き直す必要がある）か
         */
        private fun drawRow(c: Canvas, item: Item, now: Long, top: Float, pos: String?): Boolean {
            val parts = Calc.parts(item, now) ?: return false
            var y = top + dp(8f)
            val target = item.targetMillis()!!
            val left = target - now
            val color = if (item.kind == Kind.DEADLINE && left < 7 * Calc.DAY_MS) URGENT else ledColor
            var moving = false
            titleP.color = titleColor
            // シーンでは、光る文字のように少しにじませる
            if (glow) titleP.setShadowLayer(dp(3f), 0f, 0f, (titleColor and 0x00FFFFFF) or 0x99000000.toInt())
            else titleP.clearShadowLayer()

            // 1行目：種類ラベル＋名前（＋切り替え中は「2/5」）
            val (chipBg, chipFg) = chipColors(item.kind)
            chipP.color = chipFg
            val chipW = chipP.measureText(item.kind.label) + dp(12f)
            val chipH = lineH(chipP) + dp(2f)
            val line1H = maxOf(lineH(titleP), chipH)
            val chipTop = y + (line1H - chipH) / 2
            fill.color = chipBg
            c.drawRoundRect(RectF(innerLeft, chipTop, innerLeft + chipW, chipTop + chipH), dp(4f), dp(4f), fill)
            c.drawText(item.kind.label, innerLeft + dp(6f), chipTop + dp(1f) - chipP.fontMetrics.ascent, chipP)
            var titleRight = innerRight
            if (pos != null) {
                val pw = posP.measureText(pos)
                c.drawText(pos, innerRight - pw, y + (line1H - lineH(posP)) / 2 - posP.fontMetrics.ascent, posP)
                titleRight = innerRight - pw - dp(8f)
            }
            val titleLeft = innerLeft + chipW + dp(8f)
            val avail = titleRight - titleLeft
            val titleBase = y + (line1H - lineH(titleP)) / 2 - titleP.fontMetrics.ascent
            val tw = titleP.measureText(item.title)
            if (marquee && tw > avail && avail > 0) {
                // 電光掲示板：入りきらない名前を右から左へ流し続ける
                val gap = dp(40f)
                val off = RotateMath.marqueeOffset(now, dp(MARQUEE_DP_PER_SEC), tw + gap)
                c.save()
                c.clipRect(titleLeft, y, titleRight, y + line1H)
                c.drawText(item.title, titleLeft - off, titleBase, titleP)
                c.drawText(item.title, titleLeft - off + tw + gap, titleBase, titleP)
                c.restore()
                moving = true
            } else {
                val shown = TextUtils.ellipsize(item.title, titleP, maxOf(0f, avail), TextUtils.TruncateAt.END).toString()
                c.drawText(shown, titleLeft, titleBase, titleP)
            }
            y += line1H + dp(2f)

            // 2行目：大きい数字＋単位＋時:分:秒（右寄せ、下揃え）
            bigP.color = color
            if (glow) bigP.setShadowLayer(dp(3f), 0f, 0f, (color and 0x00FFFFFF) or 0x99000000.toInt())
            else bigP.clearShadowLayer()
            val clockP = text(DotRenderer.clockSp(parts), color)
            if (glow) clockP.setShadowLayer(dp(3f), 0f, 0f, (color and 0x00FFFFFF) or 0x99000000.toInt())
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
            return moving
        }
    }
}
