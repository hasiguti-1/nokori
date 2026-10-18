package jp.imamura.nokori

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 試作B（秒の回転盤）の検査。AOSP の AnalogClock が秒針の角度を決める計算を写し、
 * 盤の上端に見える数字が、本当の残り秒数と一致するかを確かめる。
 * （実物の部品ではなく計算の写し。実物の動きは実機で確認する）
 */
class RingSecondsTest {

    private val now0 = 1_790_000_000_000L

    /**
     * AnalogClock（秒針あり・毎秒1回）が示す秒：指定タイムゾーンの今の秒（小数）を四捨五入。
     * AOSP: rawSeconds = 秒 + ナノ秒/1e9、mSeconds = round(rawSeconds × fps) / fps（fps = 1）
     */
    private fun analogSeconds(nowMs: Long, zone: String): Int {
        val t = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zone)).toLocalTime()
        val raw = t.second + t.nano / 1_000_000_000f
        return Math.round(raw)
    }

    /** 盤の数字 k は上から時計回りに 6k 度。秒針が 6s 度回ると、上端に来るのは k = -s */
    private fun shownOnRing(nowMs: Long, target: Long): Int =
        Math.floorMod(-analogSeconds(nowMs, RingSeconds.zone(target)), 60)

    private fun trueSeconds(nowMs: Long, target: Long): Int =
        Math.floorMod(Math.floorDiv(target - nowMs, 1000L), 60L).toInt()

    @Test
    fun タイムゾーンの文字列はjava_timeで読める() {
        for (sec in 0 until 60) {
            val target = now0 - now0 % 60_000 + 3_600_000 + sec * 1000L
            val z = ZoneId.of(RingSeconds.zone(target)) // 読めなければ例外
            val offset = z.rules.getOffset(Instant.EPOCH).totalSeconds
            assertEquals(-RingSeconds.shiftSec(target), offset)
        }
    }

    @Test
    fun 毎秒の切り替わり直後に正しい秒が見える() {
        // 目標が分ちょうど・秒ちょうど・ミリ秒なしの3通り。秒の切り替わりから少し遅れて描かれる場合を含む
        val base = now0 - now0 % 60_000 + 5 * Calc.DAY_MS
        for (target in listOf(base, base + 17_000, base + 42_000)) {
            for (sec in 0 until 3 * 60) {
                for (late in listOf(1L, 16L, 120L, 450L)) {
                    val n = now0 - now0 % 1000 + sec * 1000L + late
                    assertEquals("目標 $target 経過 $sec 秒 遅れ $late ms",
                        trueSeconds(n, target), shownOnRing(n, target))
                }
            }
        }
    }

    @Test
    fun 見えない時間のあとも計算し直すだけで正しい() {
        // 状態を持たないので、何時間見えなくても、戻った瞬間の時刻だけで決まる
        val target = now0 - now0 % 60_000 + 3 * Calc.DAY_MS
        for (hidden in listOf(0L, 999L, 37_401L, 25 * 60_000L, 9 * 3_600_000L + 123L)) {
            val n = now0 - now0 % 1000 + hidden - hidden % 1000 + 5
            assertEquals("見えなかった時間 $hidden", trueSeconds(n, target), shownOnRing(n, target))
        }
    }

    @Test
    fun 見え始めの瞬間は最大0点5秒だけ早く進むことがある() {
        // AnalogClock は秒の途中で見え始めると四捨五入するため、0.5秒以降は次の秒を先に出す
        // （次の秒の切り替わりで本来の値に戻る）。この差を把握しておくための検査
        val target = now0 - now0 % 60_000 + Calc.DAY_MS
        val n = now0 - now0 % 1000 + 700
        assertEquals(Math.floorMod(trueSeconds(n, target) - 1, 60), shownOnRing(n, target))
    }
}
