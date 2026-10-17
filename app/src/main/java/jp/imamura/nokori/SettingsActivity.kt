package jp.imamura.nokori

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** 設定画面：字体・くり返しカウンター・週の始まり・ウィジェットに出す種類 */
class SettingsActivity : Activity() {

    private lateinit var s: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "設定"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        s = Settings(this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }

        // ── 字体 ──
        box.addView(heading("ウィジェットの字体"))
        box.addView(note("Android に入っている字体から選びます。数字の見え方が変わります"))
        val fonts = RadioGroup(this)
        FontChoice.values().forEachIndexed { i, f ->
            fonts.addView(RadioButton(this).apply {
                id = 1000 + i
                text = "${f.label}　33日 08:12:44"
                typeface = Typeface.create(f.family, Typeface.NORMAL)
                textSize = 18f
                isChecked = s.font == f
                setOnClickListener { s.font = f }
            })
        }
        box.addView(fonts)

        // ── くり返しカウンター ──
        box.addView(heading("くり返しカウンター"))
        box.addView(note("オンにすると自動で並びます。期日が来ると次の回に切り替わります"))
        box.addView(check("今日が終わるまで", s.repDay) { s.repDay = it })
        box.addView(check("今週が終わるまで", s.repWeek) { s.repWeek = it })
        box.addView(check("今月が終わるまで", s.repMonth) { s.repMonth = it })
        box.addView(check("今年が終わるまで", s.repYear) { s.repYear = it })

        val workBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, 0)
        }
        val timeBtn = Button(this)
        fun showTime() { timeBtn.text = "定時：${s.workTime}" }
        showTime()
        timeBtn.setOnClickListener {
            val (h, m) = s.workTime.split(":").map { it.toInt() }
            TimePickerDialog(this, { _, hh, mm ->
                s.workTime = String.format(Locale.JAPAN, "%02d:%02d", hh, mm); showTime()
            }, h, m, true).show()
        }
        workBox.addView(timeBtn)
        workBox.addView(check("土日は数えない", s.workSkipWeekend) { s.workSkipWeekend = it })
        box.addView(check("定時まで", s.repWork) {
            s.repWork = it; workBox.visibility = if (it) View.VISIBLE else View.GONE
        })
        workBox.visibility = if (s.repWork) View.VISIBLE else View.GONE
        box.addView(workBox)

        val payBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(32), 0, 0, 0)
        }
        val payDay = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(s.payday.toString())
            minEms = 3
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toIntOrNull()?.let { s.payday = it }
            }
        }
        payBox.addView(TextView(this).apply { text = "毎月 " })
        payBox.addView(payDay)
        payBox.addView(TextView(this).apply { text = " 日（月末より大きい日は月末）" })
        box.addView(check("毎月決まった日まで（給料日など）", s.repPayday) {
            s.repPayday = it; payBox.visibility = if (it) View.VISIBLE else View.GONE
        })
        payBox.visibility = if (s.repPayday) View.VISIBLE else View.GONE
        box.addView(payBox)

        // ── 週の始まり ──
        box.addView(heading("週の始まり"))
        val week = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        week.addView(RadioButton(this).apply {
            id = 2000; text = "月曜"; isChecked = s.weekStartsMonday
            setOnClickListener { s.weekStartsMonday = true }
        })
        week.addView(RadioButton(this).apply {
            id = 2001; text = "日曜"; isChecked = !s.weekStartsMonday
            setOnClickListener { s.weekStartsMonday = false }
        })
        box.addView(week)

        // ── ウィジェットに出す件数 ──
        box.addView(heading("ウィジェットに出す件数"))
        box.addView(note("件数を増やしたら、ホーム画面でウィジェットを長押しして縦に広げてください"))
        val count = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (n in 1..Settings.MAX_ROWS) {
            count.addView(RadioButton(this).apply {
                id = 2100 + n; text = "$n"; isChecked = s.widgetCount == n
                setOnClickListener { s.widgetCount = n }
            })
        }
        box.addView(count)

        // ── ウィジェットに出す種類 ──
        box.addView(heading("ウィジェットに出す種類"))
        box.addView(note("チェックを外した種類はウィジェットに出ません（アプリの一覧には出ます）"))
        Kind.values().forEach { k ->
            box.addView(check(k.label, s.widgetShows(k)) { s.setWidgetShows(k, it) })
        }

        setContentView(ScrollView(this).apply { addView(box) })
        this.payDay = payDay
    }

    private var payDay: EditText? = null

    override fun onPause() {
        super.onPause()
        payDay?.text?.toString()?.toIntOrNull()?.let { s.payday = it }
        CountdownWidget.refreshAll(this)
    }

    override fun onNavigateUp(): Boolean {
        finish(); return true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(getColor(R.color.muted))
        setPadding(0, 0, 0, dp(6))
    }

    private fun check(label: String, on: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label; isChecked = on
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
