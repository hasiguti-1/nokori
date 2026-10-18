package jp.imamura.nokori

/**
 * 「固定＋切り替え」の計算（ライブ壁紙で使う。端末に頼らないので単体テストできる）。
 * 何件目を出すか・動きの途中かは、すべて今の時刻から決める（状態を持たない）。
 */
object RotateMath {
    /** 今出す項目（0 始まり） */
    fun index(now: Long, periodMs: Long, count: Int): Int =
        Math.floorMod(Math.floorDiv(now, periodMs), count.toLong()).toInt()

    /** 1つ前に出ていた項目 */
    fun prevIndex(now: Long, periodMs: Long, count: Int): Int =
        Math.floorMod(Math.floorDiv(now, periodMs) - 1, count.toLong()).toInt()

    /** 今の回が始まってからの経過（ミリ秒） */
    fun elapsed(now: Long, periodMs: Long): Long = Math.floorMod(now, periodMs)

    /** 次の回が始まるまで（ミリ秒） */
    fun untilNext(now: Long, periodMs: Long): Long = periodMs - elapsed(now, periodMs)

    /** 切り替えの動きの進み具合（0〜1）。動きの最中でなければ null */
    fun progress(now: Long, periodMs: Long, durMs: Long): Float? {
        val e = elapsed(now, periodMs)
        return if (e < durMs) e.toFloat() / durMs else null
    }

    /** パタパタ：左から k 番目の列の進み具合（0〜1）。列ごとに少しずつ遅れてめくれる */
    fun flapCell(elapsedMs: Float, k: Int, staggerMs: Float, flipMs: Float): Float =
        ((elapsedMs - k * staggerMs) / flipMs).coerceIn(0f, 1f)

    /** パタパタ全体にかかる時間 */
    fun flapDuration(cells: Int, staggerMs: Float, flipMs: Float): Long =
        ((maxOf(1, cells) - 1) * staggerMs + flipMs).toLong()

    /** 電光掲示板：流れている文字の位置（0 〜 cycle 未満） */
    fun marqueeOffset(now: Long, pxPerSec: Float, cycle: Float): Float =
        if (cycle <= 0f) 0f else ((now % 3_600_000L) * pxPerSec / 1000f) % cycle
}
