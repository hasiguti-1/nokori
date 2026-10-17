package jp.imamura.nokori

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** ウィジェットの字体（Android に標準で入っているもの） */
enum class FontChoice(val label: String, val family: String, val layout: Int) {
    MONO("等幅", "monospace", R.layout.widget_mono),
    SANS("標準", "sans-serif", R.layout.widget_sans),
    LIGHT("細字", "sans-serif-light", R.layout.widget_light),
    CONDENSED("細長", "sans-serif-condensed", R.layout.widget_condensed),
    SERIF("明朝風", "serif", R.layout.widget_serif);

    companion object {
        fun from(name: String?) = values().firstOrNull { it.name == name } ?: MONO
    }
}

/** 設定（端末内に保存） */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var font: FontChoice
        get() = FontChoice.from(p.getString("font", null))
        set(v) = p.edit().putString("font", v.name).apply()

    var repDay: Boolean
        get() = p.getBoolean("rep_day", true)
        set(v) = p.edit().putBoolean("rep_day", v).apply()
    var repWeek: Boolean
        get() = p.getBoolean("rep_week", false)
        set(v) = p.edit().putBoolean("rep_week", v).apply()
    var repMonth: Boolean
        get() = p.getBoolean("rep_month", false)
        set(v) = p.edit().putBoolean("rep_month", v).apply()
    var repYear: Boolean
        get() = p.getBoolean("rep_year", false)
        set(v) = p.edit().putBoolean("rep_year", v).apply()

    var repWork: Boolean
        get() = p.getBoolean("rep_work", false)
        set(v) = p.edit().putBoolean("rep_work", v).apply()
    /** 定時 "HH:mm" */
    var workTime: String
        get() = p.getString("work_time", "17:30") ?: "17:30"
        set(v) = p.edit().putString("work_time", v).apply()
    var workSkipWeekend: Boolean
        get() = p.getBoolean("work_skip_weekend", true)
        set(v) = p.edit().putBoolean("work_skip_weekend", v).apply()

    var repPayday: Boolean
        get() = p.getBoolean("rep_payday", false)
        set(v) = p.edit().putBoolean("rep_payday", v).apply()
    /** 毎月◯日（1〜31。月末より大きければ月末） */
    var payday: Int
        get() = p.getInt("payday", 25)
        set(v) = p.edit().putInt("payday", v.coerceIn(1, 31)).apply()

    /** 週の始まり（true=月曜、false=日曜） */
    var weekStartsMonday: Boolean
        get() = p.getBoolean("week_monday", true)
        set(v) = p.edit().putBoolean("week_monday", v).apply()

    /** ウィジェットに出す種類 */
    fun widgetShows(k: Kind): Boolean = p.getBoolean("widget_" + k.name, true)
    fun setWidgetShows(k: Kind, v: Boolean) = p.edit().putBoolean("widget_" + k.name, v).apply()
}

/** くり返しカウンター（保存はせず、毎回その場で計算する） */
object Repeats {

    fun build(ctx: Context, now: Long = System.currentTimeMillis()): List<Item> {
        val s = Settings(ctx)
        val z = Calc.zone()
        val nowZ = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), z)
        val today = nowZ.toLocalDate()
        fun msT(d: LocalDateTime) = d.atZone(z).toInstant().toEpochMilli()
        fun ms(d: LocalDate) = msT(d.atStartOfDay())
        val out = mutableListOf<Item>()

        if (s.repDay) {
            out += item("rep_day", "今日が終わるまで", ms(today), ms(today.plusDays(1)))
        }
        if (s.repWeek) {
            val first = if (s.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
            val start = today.with(TemporalAdjusters.previousOrSame(first))
            out += item("rep_week", "今週が終わるまで", ms(start), ms(start.plusWeeks(1)))
        }
        if (s.repMonth) {
            val start = today.withDayOfMonth(1)
            out += item("rep_month", "今月が終わるまで", ms(start), ms(start.plusMonths(1)))
        }
        if (s.repYear) {
            val start = today.withDayOfYear(1)
            out += item("rep_year", "今年が終わるまで", ms(start), ms(start.plusYears(1)))
        }
        if (s.repWork) {
            val t = runCatching { LocalTime.parse(s.workTime) }.getOrDefault(LocalTime.of(17, 30))
            fun ok(d: LocalDate) = !s.workSkipWeekend ||
                (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY)
            // 次の定時
            var d = today
            while (!(ok(d) && msT(d.atTime(t)) > now)) d = d.plusDays(1)
            val target = msT(d.atTime(t))
            // ひとつ前の定時（バーの起点）
            var pd = d.minusDays(1)
            while (!ok(pd)) pd = pd.minusDays(1)
            out += item("rep_work", "定時（${s.workTime}）まで", msT(pd.atTime(t)), target)
        }
        if (s.repPayday) {
            fun dayIn(m: LocalDate): LocalDate = m.withDayOfMonth(minOf(s.payday, m.lengthOfMonth()))
            var next = dayIn(today.withDayOfMonth(1))
            if (ms(next) <= now) next = dayIn(today.withDayOfMonth(1).plusMonths(1))
            val prev = dayIn(next.withDayOfMonth(1).minusMonths(1))
            out += item("rep_payday", "毎月${s.payday}日まで", ms(prev), ms(next))
        }
        return out
    }

    private fun item(id: String, title: String, start: Long, target: Long) = Item(
        id = id, title = title, kind = Kind.REPEAT, target = target, start = start, createdAt = start,
    )
}
