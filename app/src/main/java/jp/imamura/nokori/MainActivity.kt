package jp.imamura.nokori

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var items: MutableList<Item>
    private lateinit var adapter: ItemAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = Store.load(this)

        val list = ListView(this).apply {
            dividerHeight = 1
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToPadding = false
        }
        val empty = TextView(this).apply {
            text = "右上の「＋」から発売日や締切を登録してください"
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val frame = android.widget.FrameLayout(this)
        frame.addView(list)
        frame.addView(empty)
        list.emptyView = empty
        setContentView(frame)

        adapter = ItemAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openEditor(adapter.getItem(pos)) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(adapter.getItem(pos)); true
        }
        // 初回の初期データを保存しておく
        Store.save(this, items)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "＋ 追加").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            openEditor(null); return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun persist() {
        Store.save(this, items)
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setMessage("「${item.title}」を削除しますか？")
            .setPositiveButton("削除する") { _, _ ->
                items.removeAll { it.id == item.id }
                persist()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 追加・編集の画面（ダイアログ） */
    private fun openEditor(orig: Item?) {
        var date: LocalDate? = orig?.target?.let { Instant.ofEpochMilli(it).atZone(Calc.zone()).toLocalDate() }
        var time: String = orig?.target?.let {
            val z = Instant.ofEpochMilli(it).atZone(Calc.zone())
            String.format(Locale.JAPAN, "%02d:%02d", z.hour, z.minute)
        } ?: "00:00"
        var birth: LocalDate? = orig?.birth?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        var startDate: LocalDate? = orig?.start?.let { Instant.ofEpochMilli(it).atZone(Calc.zone()).toLocalDate() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 12f; setPadding(0, dp(10), 0, dp(2))
        }

        val name = EditText(this).apply {
            setText(orig?.title ?: ""); hint = "例：〇〇 発売日"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val kinds = Kind.values()
        val kind = Spinner(this).apply {
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { it.label }))
            setSelection(kinds.indexOf(orig?.kind ?: Kind.GAME))
        }

        // 日付・時刻（寿命以外）
        val dateBtn = Button(this)
        val timeBtn = Button(this)
        fun showDate() { dateBtn.text = date?.let { "日付：$it" } ?: "日付を選ぶ" }
        fun showTime() { timeBtn.text = "時刻：$time" }
        showDate(); showTime()
        dateBtn.setOnClickListener {
            val d = date ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd); showDate() },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            val (h, m) = time.split(":").map { it.toInt() }
            TimePickerDialog(this, { _, hh, mm ->
                time = String.format(Locale.JAPAN, "%02d:%02d", hh, mm); showTime()
            }, h, m, true).show()
        }
        val startBtn = Button(this)
        fun showStart() {
            startBtn.text = startDate?.let { "バーの開始日：$it" } ?: "バーの開始日：登録日（変更する）"
        }
        showStart()
        startBtn.setOnClickListener {
            val d = startDate ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, dd -> startDate = LocalDate.of(y, m + 1, dd); showStart() },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        val startNote = TextView(this).apply {
            textSize = 11f
            text = "進み具合のバーは、この日から期日までの経過を表します（例：発表日）"
        }
        val dateBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dateBtn); addView(timeBtn); addView(startBtn); addView(startNote)
        }

        // 寿命
        val birthBtn = Button(this)
        fun showBirth() { birthBtn.text = birth?.let { "生年月日：$it" } ?: "生年月日を選ぶ" }
        showBirth()
        birthBtn.setOnClickListener {
            val d = birth ?: LocalDate.of(1990, 1, 1)
            DatePickerDialog(this, { _, y, m, dd -> birth = LocalDate.of(y, m + 1, dd); showBirth() },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        val ageLabel = TextView(this).apply { textSize = 12f; setPadding(0, dp(10), 0, dp(2)) }
        val age = EditText(this).apply {
            hint = "例：40"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            orig?.lifeAge?.let { setText(Calc.fmtAge(it)) }
        }
        val calcOut = TextView(this).apply { textSize = 12f }
        val calcBtn = Button(this).apply {
            text = "平均余命から計算"
            setOnClickListener {
                val b = birth
                if (b == null) {
                    calcOut.text = "先に生年月日を選んでください"; return@setOnClickListener
                }
                val bm = b.atStartOfDay(Calc.zone()).toInstant().toEpochMilli()
                val a = Calc.ageNow(bm)
                val r = Calc.remainingLife(a)
                val life = Math.round((a + r) * 100) / 100.0
                age.setText(Calc.fmtAge(life))
                calcOut.text = String.format(Locale.JAPAN, "今 %.1f歳 ＋ 平均余命 %.2f年 ＝ %.2f歳", a, r, life)
            }
        }
        val lifeNote = TextView(this).apply {
            textSize = 11f
            text = "厚生労働省「令和7年簡易生命表」男性の平均余命を使います（5歳刻みの間は直線補間）"
        }
        val lifeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(birthBtn); addView(ageLabel); addView(age); addView(calcBtn); addView(calcOut); addView(lifeNote)
        }

        val note = EditText(this).apply {
            setText(orig?.note ?: ""); hint = "メモ・出典（任意）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val widget = CheckBox(this).apply {
            text = "ホーム画面のウィジェットに出す"
            isChecked = orig?.showInWidget ?: true
        }

        fun syncKind() {
            val k = kinds[kind.selectedItemPosition]
            lifeBox.visibility = if (k.usesBirth) View.VISIBLE else View.GONE
            dateBox.visibility = if (k.usesBirth) View.GONE else View.VISIBLE
            // 平均余命の計算は寿命のときだけ
            val life = k == Kind.LIFE
            calcBtn.visibility = if (life) View.VISIBLE else View.GONE
            lifeNote.visibility = if (life) View.VISIBLE else View.GONE
            ageLabel.text = if (life) "想定寿命（歳）" else "何歳になるまで（歳）"
            if (!life) calcOut.text = "生まれた日から、この年齢の誕生日までをバーで表示します"
        }
        kind.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncKind()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        box.addView(label("名前")); box.addView(name)
        box.addView(label("種類")); box.addView(kind)
        box.addView(dateBox); box.addView(lifeBox)
        box.addView(label("メモ")); box.addView(note)
        box.addView(widget)
        syncKind()

        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (orig == null) "追加" else "編集")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("やめる", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isEmpty()) {
                    name.error = "名前を入れてください"; return@setOnClickListener
                }
                val k = kinds[kind.selectedItemPosition]
                val newItem: Item
                if (k.usesBirth) {
                    val b = birth
                    if (b == null) {
                        calcOut.text = "生年月日を選んでください"; return@setOnClickListener
                    }
                    val a = age.text.toString().toDoubleOrNull()
                    if (a == null || a <= 0) {
                        age.error = if (k == Kind.LIFE) "想定寿命を入れるか「平均余命から計算」を押してください" else "年齢を入れてください"
                        return@setOnClickListener
                    }
                    newItem = Item(
                        id = orig?.id ?: java.util.UUID.randomUUID().toString(),
                        title = title, kind = k, birth = b.toString(), lifeAge = a,
                        note = note.text.toString().trim(), showInWidget = widget.isChecked,
                        createdAt = orig?.createdAt ?: System.currentTimeMillis(),
                    )
                } else {
                    val d = date
                    if (d == null) {
                        dateBtn.error = "日付を選んでください"; return@setOnClickListener
                    }
                    newItem = Item(
                        id = orig?.id ?: java.util.UUID.randomUUID().toString(),
                        title = title, kind = k, target = Calc.parseDateTime(d.toString(), time),
                        start = startDate?.atStartOfDay(Calc.zone())?.toInstant()?.toEpochMilli(),
                        note = note.text.toString().trim(), showInWidget = widget.isChecked,
                        createdAt = orig?.createdAt ?: System.currentTimeMillis(),
                    )
                }
                val i = items.indexOfFirst { it.id == newItem.id }
                if (i >= 0) items[i] = newItem else items.add(newItem)
                persist()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** 一覧の1行 */
    private inner class ItemAdapter : BaseAdapter() {
        private var view: List<Item> = emptyList()

        override fun notifyDataSetChanged() {
            view = Calc.sorted(items)
            super.notifyDataSetChanged()
        }

        init { view = Calc.sorted(items) }

        override fun getCount() = view.size
        override fun getItem(position: Int): Item = view[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val kindTv = row.getChildAt(0) as TextView
            val titleTv = row.getChildAt(1) as TextView
            val leftTv = row.getChildAt(2) as TextView
            val metaTv = row.getChildAt(3) as TextView
            val bar = row.getChildAt(4) as ProgressBar

            val item = view[position]
            val now = System.currentTimeMillis()
            val t = item.targetMillis()
            kindTv.text = item.kind.label
            titleTv.text = item.title

            if (t == null) {
                leftTv.text = "日付が未設定です（タップして編集）"
                leftTv.setTextColor(getColor(R.color.muted))
                leftTv.textSize = 14f
            } else {
                leftTv.text = Calc.formatLeft(t, now)
                leftTv.textSize = 24f
                leftTv.setTextColor(getColor(when {
                    t < now -> R.color.muted
                    item.kind == Kind.DEADLINE && t - now < 7 * Calc.DAY_MS -> R.color.urgent
                    else -> R.color.led
                }))
            }

            val meta = mutableListOf<String>()
            if (t != null) {
                if (item.kind.usesBirth) {
                    meta += "${Calc.fmtAge(item.lifeAge)}歳 → ${Calc.formatDate(t, false)}"
                    item.birthMillis()?.let { b -> meta += String.format(Locale.JAPAN, "今 %.1f歳", Calc.ageNow(b, now)) }
                    if (t > now) meta += String.format(Locale.JAPAN, "約%.1f年", (t - now) / Calc.YEAR_MS)
                } else {
                    meta += Calc.formatDate(t)
                }
            }
            if (item.note.isNotBlank()) meta += item.note
            metaTv.text = meta.joinToString("　")

            val start = item.startMillis()
            if (t != null && start != null && t > start) {
                bar.visibility = View.VISIBLE
                bar.progress = (((now - start).toDouble() / (t - start)) * 1000).toInt().coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
            return row
        }

        private fun buildRow(): LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(context).apply { textSize = 11f; setTextColor(getColor(R.color.muted)) })
            addView(TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(context).apply { typeface = resources.getFont(R.font.dotgothic16) })
            addView(TextView(context).apply { textSize = 12f; setTextColor(getColor(R.color.muted)) })
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) }
            })
        }
    }
}
