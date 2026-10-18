package jp.imamura.nokori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 残り時間の計算と、ウィジェットの時計部品（Chronometer）に渡す値の検査。
 * 端末なしで動く（Android の機能は使わない部分だけを検査する）。
 */
class RemainingTimeTest {

    private val H = 3_600_000L
    private val M = 60_000L
    private val S = 1_000L
    private val now0 = 1_790_000_000_000L // 2026年ごろの適当な時刻

    private fun item(target: Long, unit: DisplayUnit = DisplayUnit.AUTO) =
        Item(title = "テスト", kind = Kind.GAME, target = target, createdAt = now0 - Calc.DAY_MS, unit = unit)

    /** AOSP の Chronometer（カウントダウン）と同じ計算：(base - 今) を 1000 で割って切り捨て */
    private fun chronoSeconds(base: Long, elapsed: Long) = (base - elapsed) / 1000

    @Test
    fun 日と時分秒に分ける() {
        val p = Calc.parts(item(now0 + 3 * Calc.DAY_MS + 5 * H + 6 * M + 7 * S + 500), now0)!!
        assertEquals("3", p.big)
        assertEquals("日", p.unit)
        assertEquals(5 * H + 6 * M + 7 * S + 500, p.clockMs)
    }

    @Test
    fun 残りは毎回_目標と今の差から計算される() {
        // 状態を持たないので、何秒止まっていても、あとで計算し直せば正しい値になる
        val it = item(now0 + 2 * Calc.DAY_MS + 10 * H)
        for (pause in listOf(0L, 999L, 1_000L, 37_400L, 25 * M, 9 * H)) {
            val p = Calc.parts(it, now0 + pause)!!
            assertEquals("止まっていた時間 $pause", 10 * H - pause, p.clockMs)
        }
    }

    @Test
    fun 時間表示は合計の時間をそのまま出す() {
        val p = Calc.parts(item(now0 + 33 * Calc.DAY_MS + 0 * H + 4 * M, DisplayUnit.HOURS), now0)!!
        assertNull(p.big)
        assertEquals(792 * H + 4 * M, p.clockMs)
    }

    @Test
    fun 期日を過ぎたら0() {
        val p = Calc.parts(item(now0 - 5 * S), now0)!!
        assertEquals("0", p.big)
        assertEquals(0L, p.clockMs)
    }

    @Test
    fun 時計部品は見えない時間があっても正しい秒を出す() {
        // 描き直した瞬間：壁時計 now0、端末の経過時間 el0
        val el0 = 123_456_789L
        val target = now0 + 4 * H + 12 * M + 34 * S + 250
        val base = el0 + Calc.parts(item(target), now0)!!.clockMs!! // ウィジェットで渡す値と同じ
        // その後、ホーム画面が見えない時間（アプリを開いている・画面オフ）があっても、
        // 時計部品は見えた瞬間の経過時間から計算し直す。時の境目をまたいでも正しい
        for (d in listOf(0L, 250L, 251L, 1_000L, 25_000L, 37_400L, 12 * M + 34 * S, 2 * H + 5 * S)) {
            val shown = chronoSeconds(base, el0 + d)
            val real = (target - (now0 + d)) / 1000 // 本当の残り（その日のうち）の秒（切り捨て）
            assertEquals("経過 $d ミリ秒", real, shown)
        }
    }

    @Test
    fun 日の境目で時計部品は0になり_そこで描き直す() {
        val el0 = 5_000L
        val target = now0 + 1 * Calc.DAY_MS + 30 * S
        val p = Calc.parts(item(target), now0)!!
        assertEquals("1", p.big)
        val base = el0 + p.clockMs!!
        assertEquals(0L, chronoSeconds(base, el0 + 30 * S))
        // 境目の直後（描き直しは 500 ミリ秒後）。切り捨てなので「−」は付かず 0 のまま
        assertEquals(0L, chronoSeconds(base, el0 + 30 * S + 500))
        // 描き直した後は「0日」＋「23:59:59」
        val after = Calc.parts(item(target), now0 + 30 * S + 500)!!
        assertEquals("0", after.big)
        assertEquals(23L * 3600 + 59 * 60 + 59, after.clockMs!! / 1000)
    }
}
