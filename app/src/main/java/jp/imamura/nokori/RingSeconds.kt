package jp.imamura.nokori

import java.util.Locale

/**
 * 試作B（秒の回転盤）で、AnalogClock に渡す「タイムゾーン」を決める。
 *
 * AnalogClock は、指定したタイムゾーンの今の時刻の「秒」s だけ秒針（＝回転盤）を回す。
 * 盤の数字 k は上から時計回りに 6k 度の位置にあるので、上に来るのは k = -s（mod 60）。
 * タイムゾーンを -D 秒ずらすと s = (UTC の秒 - D) なので、見える数字は (D - UTC の秒)。
 * D を目標日時から決めれば、見える数字が「残りの秒（0〜59）」と一致する。
 * 時刻から毎回計算されるので、ホーム画面が見えない間があっても戻った瞬間に正しい。
 */
object RingSeconds {
    /** ずらす秒数 D（0〜59）。残りの秒 = floor((目標 - 今) / 1000) mod 60 と一致させる */
    fun shiftSec(target: Long): Int = Math.floorMod(Math.floorDiv(target - 1, 1000L), 60L).toInt()

    /** AnalogClock.setTimeZone に渡す文字列（java.time の ZoneOffset の書き方。秒まで指定できる） */
    fun zone(target: Long): String {
        val d = shiftSec(target)
        return if (d == 0) "+00:00" else String.format(Locale.ROOT, "-00:00:%02d", d)
    }
}
