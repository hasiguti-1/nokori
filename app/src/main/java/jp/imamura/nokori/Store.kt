package jp.imamura.nokori

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** 種類 */
enum class Kind(val label: String) {
    GAME("ゲーム"), DEADLINE("締切"), EVENT("イベント"), AGE("年齢"), LIFE("寿命"), REPEAT("くり返し");

    /** 生年月日から数える種類か */
    val usesBirth: Boolean get() = this == AGE || this == LIFE

    companion object {
        /** 自分で登録できる種類（くり返しは設定画面で選ぶ） */
        val editable: List<Kind> get() = values().filter { it != REPEAT }

        fun from(name: String?): Kind = values().firstOrNull { it.name == name } ?: EVENT
    }
}

/** 残りの表示単位 */
enum class DisplayUnit(val label: String) {
    AUTO("日＋時:分:秒"),
    HOURS("時間（時:分:秒）"),
    DAYS("日数だけ"),
    WEEKS("週＋日"),
    YEARS("年＋日"),
    PERCENT("残りの割合（%）");

    companion object {
        fun from(name: String?) = values().firstOrNull { it.name == name } ?: AUTO
    }
}

/** 表示用に分解した残り時間。big が null なら大きい数字は出さない。clockMs が null なら時計は出さない */
data class LeftParts(val big: String?, val unit: String?, val clockMs: Long?)

/** 1件分のデータ */
data class Item(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: Kind,
    /** 期日（エポックミリ秒）。寿命のときは使わない */
    val target: Long? = null,
    /** 生年月日 yyyy-MM-dd（寿命のときだけ） */
    val birth: String? = null,
    /** 目標の年齢・想定寿命（歳）（年齢・寿命のときだけ） */
    val lifeAge: Double? = null,
    /** 進み具合バーの開始日（エポックミリ秒）。未設定なら登録日 */
    val start: Long? = null,
    val note: String = "",
    val showInWidget: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 残りの表示単位 */
    val unit: DisplayUnit = DisplayUnit.AUTO,
) {
    /** 期日を計算（年齢・寿命は 生年月日 ＋ 年齢） */
    fun targetMillis(): Long? {
        if (kind.usesBirth) {
            val b = birthMillis() ?: return null
            val age = lifeAge ?: return null
            // 整数の年齢なら誕生日ちょうど、小数なら年数で換算
            if (age == Math.floor(age)) {
                return LocalDate.parse(birth!!).plusYears(age.toLong())
                    .atStartOfDay(Calc.zone()).toInstant().toEpochMilli()
            }
            return b + (age * Calc.YEAR_MS).toLong()
        }
        return target
    }

    /** 進み具合バーの起点（年齢・寿命は開始日がなければ生年月日、それ以外は開始日か登録日） */
    fun startMillis(): Long? = if (kind.usesBirth) (start ?: birthMillis()) else (start ?: createdAt)

    fun birthMillis(): Long? = try {
        if (birth.isNullOrBlank()) null
        else LocalDate.parse(birth).atStartOfDay(Calc.zone()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("kind", kind.name)
        put("target", target ?: JSONObject.NULL)
        put("birth", birth ?: JSONObject.NULL)
        put("lifeAge", lifeAge ?: JSONObject.NULL)
        put("start", start ?: JSONObject.NULL)
        put("note", note)
        put("showInWidget", showInWidget)
        put("createdAt", createdAt)
        put("unit", unit.name)
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", ""),
            kind = Kind.from(o.optString("kind")),
            target = if (o.isNull("target")) null else o.optLong("target"),
            birth = if (o.isNull("birth")) null else o.optString("birth"),
            lifeAge = if (o.isNull("lifeAge")) null else o.optDouble("lifeAge"),
            start = if (!o.has("start") || o.isNull("start")) null else o.optLong("start"),
            note = o.optString("note", ""),
            showInWidget = o.optBoolean("showInWidget", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            unit = DisplayUnit.from(o.optString("unit", "AUTO")),
        )
    }
}

/** 端末内（SharedPreferences）への保存 */
object Store {
    private const val PREF = "nokori"
    private const val KEY = "items"

    fun load(ctx: Context): MutableList<Item> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return seed().toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Item.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        CountdownWidget.refreshAll(ctx)
    }

    /** 初回だけ入れておく項目（だれが入れても意味が通る見本：次の元日まで） */
    private fun seed(): List<Item> {
        val next = LocalDate.now(Calc.zone()).year + 1
        return listOf(
            Item(
                title = "${next}年になるまで", kind = Kind.EVENT,
                target = Calc.parseDateTime("$next-01-01", "00:00"),
            ),
        )
    }
}

/** 計算と表示用の整形 */
object Calc {
    const val DAY_MS = 86_400_000L
    const val YEAR_MS = 365.2425 * 86_400_000.0

    /** 平均余命の出典（アプリ内の表示にも使う） */
    const val LIFE_SOURCE = "厚生労働省「令和7年簡易生命表」主な年齢の平均余命"
    const val LIFE_SOURCE_URL = "https://www.mhlw.go.jp/toukei/saikin/hw/life/life25/dl/life25-15.pdf"

    /** 令和7年簡易生命表（厚生労働省）男性の平均余命 */
    private val LIFE_M = listOf(
        0 to 81.35, 5 to 76.57, 10 to 71.60, 15 to 66.63, 20 to 61.72, 25 to 56.85,
        30 to 51.98, 35 to 47.12, 40 to 42.29, 45 to 37.52, 50 to 32.82, 55 to 28.26,
        60 to 23.86, 65 to 19.68, 70 to 15.78, 75 to 12.24, 80 to 9.10, 85 to 6.44, 90 to 4.38,
    )

    /** 令和7年簡易生命表（厚生労働省）女性の平均余命 */
    private val LIFE_F = listOf(
        0 to 87.33, 5 to 82.54, 10 to 77.56, 15 to 72.60, 20 to 67.67, 25 to 62.77,
        30 to 57.85, 35 to 52.94, 40 to 48.05, 45 to 43.20, 50 to 38.41, 55 to 33.70,
        60 to 29.07, 65 to 24.52, 70 to 20.10, 75 to 15.88, 80 to 11.95, 85 to 8.47, 90 to 5.66,
    )

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** 年齢 age 歳の平均余命（5歳刻みの間は直線で補間）。female=true なら女性の表 */
    fun remainingLife(age: Double, female: Boolean = false): Double {
        val table = if (female) LIFE_F else LIFE_M
        if (age <= 0) return table.first().second
        for (i in 0 until table.size - 1) {
            val (a0, r0) = table[i]
            val (a1, r1) = table[i + 1]
            if (age >= a0 && age <= a1) return r0 + (r1 - r0) * (age - a0) / (a1 - a0)
        }
        return table.last().second
    }

    /** 40.0 → "40"、81.25 → "81.25" */
    fun fmtAge(a: Double?): String {
        if (a == null) return ""
        return if (a == Math.floor(a)) a.toLong().toString() else String.format(Locale.JAPAN, "%.2f", a)
    }

    fun ageNow(birthMillis: Long, now: Long = System.currentTimeMillis()): Double =
        (now - birthMillis) / YEAR_MS

    fun parseDateTime(date: String, time: String): Long =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(zone()).toInstant().toEpochMilli()

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPANESE)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy年M月d日（E） H:mm", Locale.JAPANESE)

    fun formatDate(ms: Long, withTime: Boolean = true): String {
        val z = Instant.ofEpochMilli(ms).atZone(zone())
        return if (withTime && (z.hour != 0 || z.minute != 0)) z.format(dateTimeFmt) else z.format(dateFmt)
    }

    /** 残り（ミリ秒）を表示単位に合わせて分解する */
    fun parts(item: Item, now: Long = System.currentTimeMillis()): LeftParts? {
        val t = item.targetMillis() ?: return null
        val left = maxOf(0L, t - now)
        val days = left / DAY_MS
        val rem = left % DAY_MS
        return when (item.unit) {
            DisplayUnit.AUTO -> LeftParts(String.format(Locale.JAPAN, "%,d", days), "日", rem)
            DisplayUnit.HOURS -> LeftParts(null, null, left)
            DisplayUnit.DAYS -> LeftParts(String.format(Locale.JAPAN, "%,d", days), "日", null)
            DisplayUnit.WEEKS -> LeftParts(String.format(Locale.JAPAN, "%,d週 %d", days / 7, days % 7), "日", null)
            DisplayUnit.YEARS -> {
                val y = (left / YEAR_MS).toLong()
                val d = ((left - y * YEAR_MS) / DAY_MS).toLong()
                LeftParts(String.format(Locale.JAPAN, "%,d年 %d", y, d), "日", null)
            }
            DisplayUnit.PERCENT -> {
                val s = item.startMillis()
                if (s == null || t <= s) LeftParts(String.format(Locale.JAPAN, "%,d", days), "日", rem)
                else LeftParts(String.format(Locale.JAPAN, "%.1f", left * 100.0 / (t - s)), "%", null)
            }
        }
    }

    /** 時間の合計を「792:10:05」の形に */
    fun hms(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms % 3_600_000 / 60_000
        val s = ms % 60_000 / 1000
        return String.format(Locale.JAPAN, "%d:%02d:%02d", h, m, s)
    }

    /** 一覧用：表示単位に合わせた1行（過ぎていれば経過を日＋時:分:秒で） */
    fun formatLeft(item: Item, now: Long = System.currentTimeMillis()): String {
        val t = item.targetMillis() ?: return ""
        if (t < now) return formatLeft(t, now)
        val p = parts(item, now) ?: return ""
        val big = p.big?.let { "$it${p.unit}" } ?: ""
        val clock = p.clockMs?.let { (if (big.isEmpty()) "" else " ") + hms(it) } ?: ""
        return big + clock
    }

    /** 「33日 12:04:55」の形にする（過ぎていれば「経過」） */
    fun formatLeft(target: Long, now: Long = System.currentTimeMillis()): String {
        var diff = target - now
        val past = diff < 0
        if (past) diff = -diff
        val days = diff / DAY_MS
        val rem = diff % DAY_MS
        val h = rem / 3_600_000
        val m = rem % 3_600_000 / 60_000
        val s = rem % 60_000 / 1000
        val body = String.format(Locale.JAPAN, "%,d日 %02d:%02d:%02d", days, h, m, s)
        return if (past) "経過 $body" else body
    }

    /** 近い順（未来 → 過去 → 日付なし） */
    fun sorted(items: List<Item>, now: Long = System.currentTimeMillis()): List<Item> =
        items.sortedWith(compareBy<Item>({
            val t = it.targetMillis()
            when {
                t == null -> 2
                t < now -> 1
                else -> 0
            }
        }, {
            val t = it.targetMillis()
            if (t == null) 0L else if (t < now) -t else t
        }))
}
