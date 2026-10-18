package jp.imamura.nokori

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * シーンの「動き」の時刻の計算（端末に頼らないので単体テストできる）。
 * 人や車などは、決まった周期ごとに一度だけ現れる。どこにいるかは今の時刻だけで決まる（状態を持たない）。
 */
object LifeMath {
    /** 何回目の周期か（offset で、ほかの動きと時刻をずらす） */
    fun cycle(now: Long, period: Long, offset: Long): Long = Math.floorDiv(now + offset, period)

    /** 周期の始まりから dur ミリ秒の間だけ動く。その間の進み具合（0〜1）。動いていなければ null */
    fun progress(now: Long, period: Long, dur: Long, offset: Long): Float? {
        val t = Math.floorMod(now + offset, period)
        return if (t < dur) t.toFloat() / dur else null
    }

    /** 次の周期が始まるまで（ミリ秒） */
    fun untilStart(now: Long, period: Long, offset: Long): Long = period - Math.floorMod(now + offset, period)

    /** 回ごとの「くじ」（0〜999）。向きや色を回ごとに変えるのに使う */
    fun pick(cycle: Long, salt: Int): Int {
        var x = cycle * -0x61c8864680b583ebL + salt * 0x2545F4914F6CDD1DL
        x = x xor (x ushr 31)
        x *= -0x4b47d5b1d6d2b52bL
        x = x xor (x ushr 29)
        return Math.floorMod(x, 1000L).toInt()
    }

    /** 動きの頻度：0＝なし、1＝ふつう、2＝多め。多めは周期を半分に（ただし動いている時間より短くしない） */
    fun period(base: Long, dur: Long, level: Int): Long =
        if (level >= 2) maxOf(dur + 2_000L, base / 2) else base
}

/** ドット絵の小さな絵（文字で描いた絵を、色を当てて画像にする） */
class Sprite(private val rows: List<String>, private val colors: Map<Char, Int>) {
    val width = rows.maxOf { it.length }
    val height = rows.size
    val bitmap: Bitmap by lazy {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in rows.indices) for (x in rows[y].indices) {
            colors[rows[y][x]]?.let { b.setPixel(x, y, it) }
        }
        b
    }
}

/**
 * シーンの中の動くもの（人・車・バス・電車・飛行機・雲・流れ星・窓の明かり）。
 * 絵の座標（270×600 のドット）で描く。呼ぶ側で、画面に合わせて拡大してある。
 */
class SceneLife {
    private val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val box = Paint().apply { isAntiAlias = false }

    /** 次に描くまでの時間（この回の描画の中で、動いているものの都合を集める） */
    private var want = Long.MAX_VALUE

    private fun col(s: String) = (0xFF000000 or s.removePrefix("#").toLong(16)).toInt()

    // ── 絵 ──
    private fun person(color: String) = listOf(
        // 歩く2コマ（足を開く・閉じる）。右向き
        Sprite(listOf(
            "...##...", "..####..", "..####..", "...##...", "..####..", ".######.", "#.####.#", "#.####.#",
            "..####..", "..####..", "..####..", "..#..#..", "..#..#..", ".#....#.", ".#....#.", "#.....#.",
        ), mapOf('#' to col(color))),
        Sprite(listOf(
            "...##...", "..####..", "..####..", "...##...", "..####..", ".######.", ".######.", ".######.",
            "..####..", "..####..", "..####..", "...##...", "...##...", "...##...", "...##...", "...###..",
        ), mapOf('#' to col(color))),
    )

    private val peopleDusk by lazy { person("#1E1426") }
    private val peopleNight by lazy { person("#090C14") }
    private val peopleLobby by lazy { person("#3E4450") }

    private fun car(body: String) = Sprite(listOf(
        ".......#########........",
        "......#ww#ww#wwww#......",
        ".....#ww#ww##wwwww#.....",
        "t#####################hh",
        "t######################h",
        "########################",
        "####oo##########oo######",
        "....oo..........oo......",
    ), mapOf('#' to col(body), 'w' to col("#6A5A7A"), 'h' to col("#FFF2B0"), 't' to col("#FF4A4A"), 'o' to col("#0D0A10")))

    private val cars by lazy { listOf(car("#2E2438"), car("#3A2A2A"), car("#22303A"), car("#3B3446")) }

    private val cloud by lazy {
        Sprite(listOf(
            ".......####.........",
            "....##########......",
            "..###############...",
            ".##################.",
            "####################",
            ".ssssssssssssssssss.",
        ), mapOf('#' to col("#F4F7FB"), 's' to col("#DCE3EC")))
    }

    private val cloudSmall by lazy {
        Sprite(listOf(
            "....####....",
            "..########..",
            ".##########.",
            "############",
            ".ssssssssss.",
        ), mapOf('#' to col("#F4F7FB"), 's' to col("#DCE3EC")))
    }

    /** 小さい絵を、左上 (x, y) に scale 倍で描く。flip なら左右反転（左向き） */
    private fun put(c: Canvas, s: Sprite, x: Float, y: Float, scale: Float = 1f, flip: Boolean = false) {
        c.save()
        c.translate(Math.round(x).toFloat(), Math.round(y).toFloat())
        c.scale(scale, scale)
        if (flip) {
            c.scale(-1f, 1f, s.width / 2f, 0f)
        }
        c.drawBitmap(s.bitmap, 0f, 0f, bmpPaint)
        c.restore()
    }

    private fun rect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: String) {
        box.color = col(color)
        c.drawRect(Math.round(x0).toFloat(), Math.round(y0).toFloat(), Math.round(x1).toFloat(), Math.round(y1).toFloat(), box)
    }

    /**
     * 周期 base ごとに dur ミリ秒だけ動くものの進み具合を返す（動いていなければ null）。
     * 動いている間は fps 回/秒で描き直すよう、動いていなければ次に現れるときに描くよう、want を更新する
     */
    private fun act(now: Long, level: Int, base: Long, dur: Long, offset: Long, fps: Int): Pair<Float, Long>? {
        if (level <= 0) return null
        val period = LifeMath.period(base, dur, level)
        val p = LifeMath.progress(now, period, dur, offset)
        return if (p != null) {
            want = minOf(want, 1000L / fps)
            p to LifeMath.cycle(now, period, offset)
        } else {
            want = minOf(want, LifeMath.untilStart(now, period, offset) + 5)
            null
        }
    }

    /** 人が端から端まで歩く（feetY は足もとの高さ、scale は絵の倍率） */
    private fun walker(c: Canvas, now: Long, level: Int, frames: List<Sprite>, feetY: Float, scale: Float,
                       base: Long, offset: Long, salt: Int, luggage: Boolean = false) {
        val w = frames[0].width * scale
        val speed = 12f // ドット/秒
        val dur = ((Scene.WIDTH + w * 2) / speed * 1000).toLong()
        val (p, cyc) = act(now, level, base, dur, offset, 8) ?: return
        val toRight = LifeMath.pick(cyc, salt) < 500
        val dist = Scene.WIDTH + w * 2
        val x = if (toRight) -w + p * dist - w else Scene.WIDTH + w - p * dist
        val frame = frames[((now / 250) % 2).toInt()]
        val top = feetY - frame.height * scale
        if (luggage) {
            // 引いているスーツケース（歩く向きの後ろ）
            val lx = if (toRight) x - 5 * scale else x + w + 1 * scale
            rect(c, lx, feetY - 6 * scale, lx + 4 * scale, feetY, "#2F3A4A")
            rect(c, lx + 1 * scale, feetY - 8 * scale, lx + 3 * scale, feetY - 6 * scale, "#2F3A4A")
        }
        put(c, frame, x, top, scale, flip = !toRight)
    }

    /** 車が通り過ぎる */
    private fun carPass(c: Canvas, now: Long, level: Int, y: Float, toRight: Boolean, base: Long, offset: Long, salt: Int) {
        val dur = 5_000L
        val (p, cyc) = act(now, level, base, dur, offset, 30) ?: return
        val s = cars[LifeMath.pick(cyc, salt) % cars.size]
        val scale = 2f
        val w = s.width * scale
        val dist = Scene.WIDTH + w * 2
        val x = if (toRight) -w * 1.5f + p * dist else Scene.WIDTH + w * 0.5f - p * dist
        put(c, s, x, y - s.height * scale, scale, flip = !toRight)
    }

    /**
     * 描く。戻り値は次に描くまでの時間（ミリ秒。動くものがしばらくなければ大きな値）。
     * level：0＝動かさない、1＝ふつう、2＝多め
     */
    fun draw(c: Canvas, scene: Scene, now: Long, level: Int): Long {
        want = Long.MAX_VALUE
        when (scene) {
            Scene.BUSSTOP -> busStop(c, now, level)
            Scene.STATION -> station(c, now, level)
            Scene.AIRPORT -> airport(c, now, level)
            Scene.ROOFTOP -> rooftop(c, now, level)
        }
        return want
    }

    // ────────── 夕焼けのバス停：歩く人・車・バス ──────────
    private fun busStop(c: Canvas, now: Long, level: Int) {
        // 歩道を歩く人（バスや車より奥）
        walker(c, now, level, peopleDusk, 478f, 3f, 34_000L, 0L, 1)
        walker(c, now, level, peopleDusk, 480f, 3f, 51_000L, 17_000L, 2)
        // バス：右から来て停留所で止まり、ドアを開けて、左へ発車する
        act(now, level, 150_000L, 16_000L, 60_000L, 20)?.let { (p, _) -> bus(c, p) }
        // 奥の車線（左へ）と手前の車線（右へ）
        carPass(c, now, level, 508f, false, 23_000L, 5_000L, 3)
        carPass(c, now, level, 552f, true, 29_000L, 14_000L, 4)
    }

    private fun bus(c: Canvas, p: Float) {
        val stopX = 112f
        val x = when {
            p < 0.35f -> { val t = p / 0.35f; stopX + (1f - t) * (1f - t) * 170f } // 減速して止まる
            p < 0.65f -> stopX
            else -> { val t = (p - 0.65f) / 0.35f; stopX - t * t * 260f } // 加速して出ていく
        }
        val top = 448f
        val bottom = 494f
        rect(c, x, top, x + 120f, bottom, "#D9DEE6")             // 車体
        rect(c, x, top, x + 120f, top + 3f, "#B9C0CB")            // 屋根
        rect(c, x, bottom - 12f, x + 120f, bottom - 8f, "#2F6DB3") // 帯
        rect(c, x + 4f, top + 6f, x + 18f, top + 12f, "#1A1A1A")  // 行き先表示
        rect(c, x + 6f, top + 8f, x + 16f, top + 10f, "#FFB23E")
        var wx = x + 22f
        while (wx < x + 112f) {                                    // 窓（明かり）
            rect(c, wx, top + 6f, wx + 10f, top + 18f, "#FFE9B0")
            wx += 13f
        }
        val doorOpen = p in 0.40f..0.60f
        rect(c, x + 8f, top + 20f, x + 18f, bottom - 2f, if (doorOpen) "#FFE9B0" else "#9AA3B5") // ドア
        rect(c, x, top + 22f, x + 3f, top + 26f, "#FFF2B0")        // 前照灯（左向きに走るので左が前）
        rect(c, x + 117f, top + 22f, x + 120f, top + 26f, "#FF4A4A")
        for (ww in listOf(x + 22f, x + 92f)) {                      // 車輪
            rect(c, ww, bottom - 4f, ww + 12f, bottom + 4f, "#0D0A10")
        }
    }

    // ────────── 夜の駅のホーム：通過する電車・ホームを歩く人 ──────────
    private fun station(c: Canvas, now: Long, level: Int) {
        act(now, level, 45_000L, 3_200L, 8_000L, 30)?.let { (p, cyc) ->
            val toLeft = LifeMath.pick(cyc, 7) < 500
            val len = 3 * 92f
            val dist = Scene.WIDTH + len
            val x = if (toLeft) Scene.WIDTH - p * dist else -len + p * dist
            for (k in 0 until 3) {
                val cx = x + k * 92f
                rect(c, cx, 436f, cx + 88f, 465f, "#3A4456")       // 車体
                rect(c, cx, 458f, cx + 88f, 460f, "#2F6DB3")       // 帯
                var wx = cx + 6f
                while (wx < cx + 84f) {
                    rect(c, wx, 441f, wx + 7f, 449f, "#FFE9B0")   // 窓の明かり
                    wx += 11f
                }
            }
            // 手前の柱は電車より前にある
            for (px in listOf(4f, 258f)) {
                rect(c, px, 268f, px + 9f, 561f, "#2A2F3B")
                rect(c, px, 268f, px + 2f, 561f, "#3C4352")
            }
        }
        walker(c, now, level, peopleNight, 590f, 3f, 37_000L, 3_000L, 5)
        walker(c, now, level, peopleNight, 584f, 3f, 59_000L, 30_000L, 6)
    }

    // ────────── 朝の空港：流れる雲・離陸する飛行機・ロビーを歩く人 ──────────
    private fun airport(c: Canvas, now: Long, level: Int) {
        // 雲：いつもゆっくり流れる（1秒に1回描けば足りる速さ）。動かさない設定なら止める
        val t = if (level > 0) now / 1000f else 0f
        fun drift(speed: Float, start: Float, span: Float) = Math.floorMod((start + t * speed).toInt(), span.toInt()).toFloat() - 40f
        put(c, cloud, drift(0.5f, 40f, 350f), 62f)
        put(c, cloudSmall, drift(0.35f, 220f, 350f), 112f)
        put(c, cloud, drift(0.25f, 150f, 350f), 160f)
        // 離陸する飛行機：滑走路を走って、斜めに上がっていく（右から左へ）
        act(now, level, 80_000L, 14_000L, 20_000L, 20)?.let { (p, _) ->
            val (x, y, up) = if (p < 0.45f) {
                val q = p / 0.45f
                Triple(300f - q * q * 120f, 283f, false)
            } else {
                val q = (p - 0.45f) / 0.55f
                Triple(180f - q * 230f, 283f - q * 150f, true)
            }
            plane(c, x, y, up)
        }
        // 窓の枠（雲や飛行機より手前）
        var mx = 0f
        while (mx < Scene.WIDTH) {
            rect(c, mx, 0f, mx + 4f, 300f, "#2E3640")
            mx += 54f
        }
        rect(c, 0f, 100f, Scene.WIDTH.toFloat(), 104f, "#2E3640")
        rect(c, 0f, 200f, Scene.WIDTH.toFloat(), 204f, "#2E3640")
        walker(c, now, level, peopleLobby, 594f, 3f, 31_000L, 6_000L, 8, luggage = true)
        walker(c, now, level, peopleLobby, 588f, 3f, 53_000L, 33_000L, 9)
    }

    private fun plane(c: Canvas, x: Float, y: Float, up: Boolean) {
        val lift = if (up) 1f else 0f
        rect(c, x, y - lift * 2, x + 6f, y + 2f - lift * 2, "#F3F5F8")     // 機首側（上がるときは少し上）
        rect(c, x + 5f, y - lift, x + 14f, y + 2f - lift, "#F3F5F8")
        rect(c, x + 13f, y, x + 18f, y + 2f, "#F3F5F8")
        rect(c, x + 15f, y - 4f, x + 18f, y, "#2F6DB3")                    // 尾翼
        rect(c, x + 7f, y + 2f - lift, x + 12f, y + 3f - lift, "#AEB6C2")  // 主翼
    }

    // ────────── 屋上の電光看板：窓の明かり・航空障害灯・飛行機の灯り・流れ星 ──────────
    private fun rooftop(c: Canvas, now: Long, level: Int) {
        val win = Scene.ROOFTOP_WINDOWS
        val lit = arrayOf("#FFD27A", "#7FF5FF", "#FF9FD8")
        var i = 0
        while (i + 2 < win.size) {
            val k = i / 3
            // 窓ごとに11秒前後でついたり消えたりする（時刻をずらして、一斉には変わらない）
            val slot = if (level > 0) Math.floorDiv(now + k * 1_371L, 11_000L) else 0L
            val r = LifeMath.pick(slot, k)
            val color = if (r < 300) lit[r % lit.size] else if (win[i + 2] == 0) "#160F2C" else "#0E0A1E"
            rect(c, win[i].toFloat(), win[i + 1].toFloat(), win[i].toFloat() + 2f, win[i + 1].toFloat() + 2f, color)
            i += 3
        }
        // 看板の上の航空障害灯：1秒ごとに点滅
        val on = level <= 0 || (now / 1000) % 2 == 0L
        rect(c, 246f, 268f, 249f, 271f, if (on) "#FF3B3B" else "#3A1520")
        if (level > 0) want = minOf(want, 1000L - now % 1000 + 5)

        // 遠くを横切る飛行機の灯り（白と赤が交互に光る）
        act(now, level, 60_000L, 30_000L, 10_000L, 5)?.let { (p, cyc) ->
            val toRight = LifeMath.pick(cyc, 11) < 500
            val x = if (toRight) -5f + p * 280f else 275f - p * 280f
            val y = 140f + LifeMath.pick(cyc, 12) % 60 - p * 10f
            val phase = (now / 300) % 4
            if (phase == 0L) rect(c, x, y, x + 1f, y + 1f, "#FFFFFF")
            if (phase == 2L) rect(c, x + 2f, y, x + 3f, y + 1f, "#FF4A4A")
        }
        // 流れ星：ときどき、空を一瞬だけ斜めに流れる
        act(now, level, 70_000L, 700L, 33_000L, 30)?.let { (p, cyc) ->
            val sx = 60f + LifeMath.pick(cyc, 13) % 180
            val sy = 20f + LifeMath.pick(cyc, 14) % 90
            val hx = sx - p * 60f
            val hy = sy + p * 30f
            val tail = arrayOf("#FFFFFF", "#E6ECFF", "#C9D6FF", "#A9B8F0", "#8FA2D9", "#6F7FB8")
            for (k in tail.indices) {
                val tx = hx + k * 2f
                val ty = hy - k * 1f
                if (tx <= sx) rect(c, tx, ty, tx + 1f, ty + 1f, tail[k])
            }
        }
    }
}
