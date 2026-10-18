package jp.imamura.nokori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** シーンの動き（人・車など）の時刻の計算の検査 */
class LifeMathTest {

    private val base = 1_790_000_040_000L

    @Test
    fun 周期の始まりから決まった時間だけ動く() {
        val period = 30_000L
        val dur = 5_000L
        val start = base - Math.floorMod(base, period) // offset 0 の回の始まり
        assertEquals(0f, LifeMath.progress(start, period, dur, 0)!!, 0f)
        assertEquals(0.5f, LifeMath.progress(start + 2_500, period, dur, 0)!!, 0.0001f)
        assertNull(LifeMath.progress(start + 5_000, period, dur, 0))
        assertEquals(25_000L, LifeMath.untilStart(start + 5_000, period, 0))
    }

    @Test
    fun ずらすと別の時刻に現れる() {
        val period = 30_000L
        val start = base - Math.floorMod(base, period)
        // offset だけ早く始まる
        assertEquals(0f, LifeMath.progress(start - 7_000, period, 1_000, 7_000)!!, 0f)
        assertNull(LifeMath.progress(start, period, 1_000, 7_000))
    }

    @Test
    fun くじは0から999で_回ごとに変わる() {
        val seen = HashSet<Int>()
        for (k in 0L until 200L) {
            val r = LifeMath.pick(k, 3)
            assertTrue(r in 0..999)
            seen += r
        }
        assertTrue("ほぼ毎回違う値になる", seen.size > 150)
        assertNotEquals(LifeMath.pick(5, 1), LifeMath.pick(5, 2))
        assertEquals("同じ回・同じ種なら同じ値（状態を持たない）", LifeMath.pick(42, 7), LifeMath.pick(42, 7))
    }

    @Test
    fun 多めは周期が半分_ただし動く時間より短くしない() {
        assertEquals(30_000L, LifeMath.period(30_000L, 5_000L, 1))
        assertEquals(15_000L, LifeMath.period(30_000L, 5_000L, 2))
        assertEquals(27_000L, LifeMath.period(30_000L, 25_000L, 2))
    }
}
