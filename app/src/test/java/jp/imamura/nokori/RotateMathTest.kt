package jp.imamura.nokori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「固定＋切り替え」の計算（ライブ壁紙の動き）の検査 */
class RotateMathTest {

    private val p = 10_000L
    private val base = 1_790_000_000_000L - 1_790_000_000_000L % p // 回の始まりちょうど

    @Test
    fun 何件目を出すかは時刻だけで決まる() {
        assertEquals(RotateMath.index(base, p, 3), RotateMath.index(base + 9_999, p, 3))
        assertEquals((RotateMath.index(base, p, 3) + 1) % 3, RotateMath.index(base + 10_000, p, 3))
        // 1つ前は、今の1つ前（最初の項目の前は最後の項目）
        for (k in 0 until 7) {
            val now = base + k * p + 123
            val n = 3
            assertEquals(Math.floorMod(RotateMath.index(now, p, n) - 1, n), RotateMath.prevIndex(now, p, n))
        }
    }

    @Test
    fun 動きは回の始まりから決まった時間だけ() {
        assertEquals(0f, RotateMath.progress(base, p, 450)!!, 0f)
        assertEquals(0.5f, RotateMath.progress(base + 225, p, 450)!!, 0.0001f)
        assertNull(RotateMath.progress(base + 450, p, 450))
        assertNull(RotateMath.progress(base + 9_000, p, 450))
        assertEquals(1_000L, RotateMath.untilNext(base + 9_000, p))
    }

    @Test
    fun パタパタは左の列から順にめくれ_全体の時間で全部めくれ終わる() {
        val cells = 27
        val stagger = 14f
        val flip = 220f
        val total = RotateMath.flapDuration(cells, stagger, flip)
        // 始まりはどの列もめくれていない
        for (k in 0 until cells) assertEquals(0f, RotateMath.flapCell(0f, k, stagger, flip), 0f)
        // 途中は左の列ほど進んでいる
        val mid = total / 2f
        for (k in 1 until cells) {
            assertTrue(RotateMath.flapCell(mid, k - 1, stagger, flip) >= RotateMath.flapCell(mid, k, stagger, flip))
        }
        // 終わりは全部めくれている
        for (k in 0 until cells) assertEquals(1f, RotateMath.flapCell(total.toFloat(), k, stagger, flip), 0f)
    }

    @Test
    fun 電光掲示板の位置は一周の範囲に収まる() {
        for (t in listOf(0L, 999L, 123_456L, base)) {
            val off = RotateMath.marqueeOffset(t, 100f, 500f)
            assertTrue(off >= 0f && off < 500f)
        }
        assertEquals(0f, RotateMath.marqueeOffset(base, 100f, 0f), 0f)
    }
}
