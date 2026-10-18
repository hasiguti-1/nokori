package jp.imamura.nokori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ロック画面の通知に出す内容と、次に描き直す時刻の検査（端末なしで動く部分だけ） */
class LockNoticeTest {

    private val H = 3_600_000L
    private val now0 = 1_790_000_000_000L

    private fun item(target: Long, unit: DisplayUnit = DisplayUnit.AUTO) =
        Item(title = "テスト", kind = Kind.EVENT, target = target, createdAt = now0 - Calc.DAY_MS, unit = unit)

    @Test
    fun 日と時計に分けて_時計が0になる瞬間に描き直す() {
        val target = now0 + 74 * Calc.DAY_MS + 8 * H
        val p = LockNotice.plan(Calc.parts(item(target), now0)!!, target, now0)
        assertEquals("あと 74日", p.text)
        assertEquals(now0 + 8 * H, p.whenMs) // 通知の時計は「◯日」を除いた残りを数える
        assertEquals(now0 + 8 * H + 500, p.next)
    }

    @Test
    fun 時間表示は時計だけで_期日に描き直す() {
        val target = now0 + 30 * H
        val p = LockNotice.plan(Calc.parts(item(target, DisplayUnit.HOURS), now0)!!, target, now0)
        assertNull(p.text)
        assertEquals(target, p.whenMs)
        assertEquals(target + 500, p.next)
    }

    @Test
    fun 日数だけの表示は時計なしで15分ごと() {
        val target = now0 + 10 * Calc.DAY_MS
        val p = LockNotice.plan(Calc.parts(item(target, DisplayUnit.DAYS), now0)!!, target, now0)
        assertEquals("あと 10日", p.text)
        assertNull(p.whenMs)
        assertEquals(now0 + 15 * 60_000L, p.next)
    }
}
