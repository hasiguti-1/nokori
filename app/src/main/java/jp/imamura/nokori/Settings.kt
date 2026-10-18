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
    SERIF("明朝風", "serif", R.layout.widget_serif),
    /** 文字を画像で描く。秒は出さず1分ごとに更新 */
    DOT("ドット文字（秒なし）", "", R.layout.widget_dot),
    /** 「◯日」まではドット文字の画像、「時:分:秒」は標準の時計部品（等幅）で、端末の経過時間から数える */
    DOT_SEC("ドット文字（秒あり・時刻は等幅）", "", R.layout.widget_dotsec),
    /**
     * 「◯日 時:分:」はドット文字の画像（毎分描き直す）、秒はドット文字の回転盤（AnalogClock の秒針）で、
     * 時刻から毎秒計算する。Android 12 以上だけ（それより前は DOT_SEC と同じ表示になる）
     */
    DOT_RING("ドット文字（秒あり・秒もドット）", "", R.layout.widget_dotring);

    /** 文字を画像で描く字体か */
    val isImage: Boolean get() = this == DOT || this == DOT_SEC || this == DOT_RING

    companion object {
        fun from(name: String?) = values().firstOrNull { it.name == name } ?: MONO
    }
}

/**
 * 「固定＋切り替え」で項目が入れ替わるときの動き。
 * ウィジェットでは Android のめくり部品（ViewFlipper）に動きを付けて切り替える（layout は動きごとの部品）。
 * ライブ壁紙ではアプリが自分で描く（パタパタは1文字ずつめくれる）。
 */
enum class RotateAnim(val label: String, val layout: Int) {
    NONE("なし（パッと切り替わる）", 0),
    SLIDE_UP("縦スライド（下から上へ・発車標風）", R.layout.flip_slide_up),
    SLIDE_LEFT("横スライド（右から左へ）", R.layout.flip_slide_left),
    SLIDE_RIGHT("横スライド（左から右へ）", R.layout.flip_slide_right),
    FLAP("パタパタ（つぶれて開く）", R.layout.flip_flap),
    FADE("フェード", R.layout.flip_fade),
    ZOOM("ズーム", R.layout.flip_zoom);

    companion object {
        fun from(name: String?) = values().firstOrNull { it.name == name } ?: NONE
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

    /** ロック画面（通知）に残り時間を出すか */
    var lockNotice: Boolean
        get() = p.getBoolean("lock_notice", false)
        set(v) = p.edit().putBoolean("lock_notice", v).apply()

    /** 平均余命の計算に女性の表を使うか（最後に選んだほうを覚えておく） */
    var lifeFemale: Boolean
        get() = p.getBoolean("life_female", false)
        set(v) = p.edit().putBoolean("life_female", v).apply()

    /** 週の始まり（true=月曜、false=日曜） */
    var weekStartsMonday: Boolean
        get() = p.getBoolean("week_monday", true)
        set(v) = p.edit().putBoolean("week_monday", v).apply()

    /** ウィジェットに出す件数（1〜6） */
    var widgetCount: Int
        get() = p.getInt("widget_count", 3).coerceIn(1, Settings.MAX_ROWS)
        set(v) = p.edit().putInt("widget_count", v.coerceIn(1, Settings.MAX_ROWS)).apply()

    /** くり返しカウンターの表示単位（「今日」は日数が常に0なので時間表示を既定に） */
    fun unitFor(repId: String): DisplayUnit =
        DisplayUnit.from(p.getString("unit_" + repId, if (repId == "rep_day") "HOURS" else "AUTO"))
    fun setUnitFor(repId: String, u: DisplayUnit) = p.edit().putString("unit_" + repId, u.name).apply()

    /** ウィジェットに出す種類 */
    fun widgetShows(k: Kind): Boolean = p.getBoolean("widget_" + k.name, true)
    fun setWidgetShows(k: Kind, v: Boolean) = p.edit().putBoolean("widget_" + k.name, v).apply()

    /** 固定した項目のID（固定した順。くり返しカウンターは "rep_day" などのID） */
    var pinned: List<String>
        get() = (p.getString("pinned", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("pinned", v.distinct().take(MAX_PINNED).joinToString(",")).apply()

    fun isPinned(id: String) = id in pinned

    /** 固定をつける／はずす。上限を超えるときは false を返して何もしない */
    fun setPinned(id: String, on: Boolean): Boolean {
        val now = pinned
        if (on) {
            if (id in now) return true
            if (now.size >= MAX_PINNED) return false
            pinned = now + id
        } else {
            pinned = now - id
        }
        return true
    }

    /** ウィジェットを「固定＋切り替え」で表示するか（false なら近い順に並べる） */
    var rotate: Boolean
        get() = p.getBoolean("rotate", false)
        set(v) = p.edit().putBoolean("rotate", v).apply()

    /** 切り替えの間隔（秒） */
    var rotateSec: Int
        get() = p.getInt("rotate_sec", 10).let { if (it in ROTATE_CHOICES) it else 10 }
        set(v) = p.edit().putInt("rotate_sec", v).apply()

    /** 切り替えの動き（ウィジェットとライブ壁紙の両方） */
    var rotateAnim: RotateAnim
        get() = RotateAnim.from(p.getString("rotate_anim", null))
        set(v) = p.edit().putString("rotate_anim", v.name).apply()

    /** ライブ壁紙で、入りきらない名前を電光掲示板のように流すか */
    var wallMarquee: Boolean
        get() = p.getBoolean("wall_marquee", false)
        set(v) = p.edit().putBoolean("wall_marquee", v).apply()

    companion object {
        const val MAX_ROWS = 6
        /** 固定できる件数 */
        const val MAX_PINNED = 2
        /** 切り替え間隔の選択肢（秒） */
        val ROTATE_CHOICES = listOf(5, 10, 15, 20, 30, 60)
    }
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
            out += item(s, "rep_day", "今日が終わるまで", ms(today), ms(today.plusDays(1)))
        }
        if (s.repWeek) {
            val first = if (s.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
            val start = today.with(TemporalAdjusters.previousOrSame(first))
            out += item(s, "rep_week", "今週が終わるまで", ms(start), ms(start.plusWeeks(1)))
        }
        if (s.repMonth) {
            val start = today.withDayOfMonth(1)
            out += item(s, "rep_month", "今月が終わるまで", ms(start), ms(start.plusMonths(1)))
        }
        if (s.repYear) {
            val start = today.withDayOfYear(1)
            out += item(s, "rep_year", "今年が終わるまで", ms(start), ms(start.plusYears(1)))
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
            out += item(s, "rep_work", "定時（${s.workTime}）まで", msT(pd.atTime(t)), target)
        }
        if (s.repPayday) {
            fun dayIn(m: LocalDate): LocalDate = m.withDayOfMonth(minOf(s.payday, m.lengthOfMonth()))
            var next = dayIn(today.withDayOfMonth(1))
            if (ms(next) <= now) next = dayIn(today.withDayOfMonth(1).plusMonths(1))
            val prev = dayIn(next.withDayOfMonth(1).minusMonths(1))
            out += item(s, "rep_payday", "毎月${s.payday}日まで", ms(prev), ms(next))
        }
        return out
    }

    private fun item(settings: Settings, id: String, title: String, start: Long, target: Long) = Item(
        id = id, title = title, kind = Kind.REPEAT, target = target, start = start, createdAt = start,
        unit = settings.unitFor(id),
    )
}
