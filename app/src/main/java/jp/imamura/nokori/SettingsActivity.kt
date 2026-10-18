package jp.imamura.nokori

import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
import android.widget.Toast
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
            // 秒もドットの表示は Android 12 以上だけ（それより前の機種では選択肢に出さない）
            if (f == FontChoice.DOT_RING && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@forEachIndexed
            fonts.addView(RadioButton(this).apply {
                id = 1000 + i
                text = when (f) {
                    FontChoice.DOT -> "${f.label}　33日 8:12"
                    FontChoice.DOT_SEC, FontChoice.DOT_RING -> "${f.label}　33日 8:12:44"
                    else -> "${f.label}　33日 08:12:44"
                }
                typeface = if (f.isImage) resources.getFont(R.font.dotgothic16)
                           else Typeface.create(f.family, Typeface.NORMAL)
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

        // ── 表示のしかた（並べる／固定＋切り替え） ──
        box.addView(heading("ウィジェットの表示のしかた"))
        box.addView(note("固定は、一覧で項目を長押し →「ウィジェットに固定する」（最大${Settings.MAX_PINNED}件）。どちらの表示でも固定した項目が上に出ます"))
        val rotBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), 0, 0, 0)
        }
        val mode = RadioGroup(this)
        mode.addView(RadioButton(this).apply {
            id = 2200; text = "近い順に並べる（件数は下で選ぶ）"; isChecked = !s.rotate
            setOnClickListener { s.rotate = false; rotBox.visibility = View.GONE }
        })
        mode.addView(RadioButton(this).apply {
            id = 2201; text = "固定＋切り替え（固定の下で1件ずつ切り替える）"; isChecked = s.rotate
            setOnClickListener { s.rotate = true; rotBox.visibility = View.VISIBLE }
        })
        box.addView(mode)
        rotBox.addView(note("切り替えの間隔（画面が消えている間は止まります）"))
        // 6つ横に並べると画面からはみ出すので縦に並べる
        val interval = RadioGroup(this)
        Settings.ROTATE_CHOICES.forEachIndexed { i, sec ->
            interval.addView(RadioButton(this).apply {
                id = 2300 + i; text = if (sec >= 60) "${sec / 60}分" else "${sec}秒"
                isChecked = s.rotateSec == sec
                setOnClickListener { s.rotateSec = sec }
            })
        }
        rotBox.addView(interval)
        rotBox.addView(note("切り替えの動き（ホーム画面のウィジェットとライブ壁紙の両方。パタパタは、ライブ壁紙では1列ずつめくれます。動きがあるときは、ほかのアプリから戻ると止まった項目の続きから再開します）"))
        val animGroup = RadioGroup(this)
        RotateAnim.values().forEachIndexed { i, a ->
            animGroup.addView(RadioButton(this).apply {
                id = 2400 + i; text = a.label
                isChecked = s.rotateAnim == a
                setOnClickListener { s.rotateAnim = a; CountdownWidget.refreshAll(this@SettingsActivity) }
            })
        }
        rotBox.addView(animGroup)
        rotBox.visibility = if (s.rotate) View.VISIBLE else View.GONE
        box.addView(rotBox)

        // ── ウィジェットに出す件数 ──
        box.addView(heading("ウィジェットに出す件数"))
        box.addView(note("「近い順に並べる」のときの件数です。増やしたら、ホーム画面でウィジェットを長押しして縦に広げてください"))
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

        // ── 時刻ぴったりの更新（Android 12 以降は許可が必要） ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            box.addView(heading("時刻ぴったりの更新"))
            box.addView(note("許可すると、日付や分が変わった瞬間にウィジェットを描き直します。許可しなくても使えますが、表示の切り替わりが最大1分ほど遅れることがあります"))
            val st = TextView(this).apply { textSize = 14f }
            val btn = Button(this).apply {
                text = "許可の画面を開く"
                setOnClickListener {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    }
                }
            }
            box.addView(st)
            box.addView(btn)
            exactStatus = st
            exactButton = btn
        }

        // ── ライブ壁紙 ──
        box.addView(heading("ライブ壁紙（ロック画面にも出す）"))
        box.addView(note("ウィジェットと同じボードを壁紙に描きます。ロック画面にも同じ壁紙が使われる機種なら、ロック画面にも出ます。出す項目と件数はウィジェットと同じです"))
        box.addView(note("シーン：掲示板のある風景の絵の中に、残り時間をはめこみます（背景の写真の代わりになります）"))
        val sceneGroup = RadioGroup(this)
        sceneGroup.addView(RadioButton(this).apply {
            id = 2500; text = "シーンなし（写真または濃紺の背景）"; isChecked = s.wallScene == null
            setOnClickListener { s.wallScene = null }
        })
        Scene.values().forEachIndexed { i, sc ->
            sceneGroup.addView(RadioButton(this).apply {
                id = 2501 + i; text = "${sc.label}（${sc.rows}行）"; isChecked = s.wallScene == sc
                setOnClickListener { s.wallScene = sc }
            })
        }
        box.addView(sceneGroup)
        box.addView(check("名前が入りきらないときは電光掲示板のように流す（電池をやや多く使う）", s.wallMarquee) {
            s.wallMarquee = it
        })
        box.addView(Button(this).apply {
            text = "背景の写真を選ぶ"
            setOnClickListener {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    }, REQ_BG)
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "写真を選ぶ画面を開けませんでした", Toast.LENGTH_LONG).show()
                }
            }
        })
        box.addView(Button(this).apply {
            text = "背景の写真を外す（濃紺にする）"
            setOnClickListener {
                BoardWallpaper.bgFile(this@SettingsActivity).delete()
                Toast.makeText(this@SettingsActivity, "背景を濃紺にしました", Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(Button(this).apply {
            text = "ライブ壁紙に設定する"
            setOnClickListener {
                val cn = android.content.ComponentName(this@SettingsActivity, BoardWallpaper::class.java)
                try {
                    startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                    } catch (e2: Exception) {
                        Toast.makeText(this@SettingsActivity,
                            "壁紙の画面を開けませんでした。設定 → 壁紙 →「その他のライブ壁紙」から選んでください",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        // ── ロック画面 ──
        box.addView(heading("ロック画面"))
        box.addView(note("固定した項目のいちばん上（固定がなければ期日がいちばん近い項目）を、通知として出し続けます。字体は端末の標準です。機種によってはロック画面には出ません（ロック画面にはライブ壁紙のほうが確実です）。通知を消したときは、ここで入れ直してください"))
        val lc = check("ロック画面に残り時間を出す（通知）", s.lockNotice) { on ->
            s.lockNotice = on
            if (on && !LockNotice.permitted(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTICE)
            }
            CountdownWidget.refreshAll(this)
        }
        box.addView(lc)
        lockCheck = lc

        // ── このアプリについて ──
        box.addView(heading("このアプリについて"))
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }
        box.addView(note("バージョン $ver"))
        box.addView(note("登録した内容はこのスマホの中だけに保存します。外部への送信はしません"))
        box.addView(link("プライバシーポリシー", PRIVACY_URL))
        box.addView(note("平均余命：${Calc.LIFE_SOURCE}をもとに作成（5歳刻みの値の間は直線で補間）"))
        box.addView(link("出典（厚生労働省）", Calc.LIFE_SOURCE_URL))
        box.addView(note("ドット文字：DotGothic16（SIL Open Font License 1.1）"))

        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
        // Android 15 以降は画面の端まで描くので、ナビゲーションバーに重ならないよう余白を足す
        scroll.padForSystemBars()
        this.payDay = payDay
    }

    private var payDay: EditText? = null
    private var exactStatus: TextView? = null
    private var exactButton: Button? = null
    private var lockCheck: CheckBox? = null

    @Deprecated("Activity の結果の受け取り（このアプリは androidx を使わないため）")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            // 選んだ写真をアプリの中に写す（元の写真の場所が変わっても壁紙が消えないように）
            contentResolver.openInputStream(uri)?.use { input ->
                BoardWallpaper.bgFile(this).outputStream().use { input.copyTo(it) }
            }
            Toast.makeText(this, "背景の写真を設定しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "写真を読み込めませんでした", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTICE) return
        if (!LockNotice.permitted(this)) {
            s.lockNotice = false
            lockCheck?.isChecked = false
            Toast.makeText(this,
                "通知が許可されていないため出せません。設定 → アプリ → 残り時間 → 通知 で許可してください",
                Toast.LENGTH_LONG).show()
        }
        CountdownWidget.refreshAll(this)
    }

    override fun onResume() {
        super.onResume()
        // 通知を消すと設定が切れるので、画面に戻ったときに合わせる
        lockCheck?.isChecked = s.lockNotice
        // 許可の画面から戻ってきたときに表示を合わせる
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            exactStatus?.text = if (ok) "いまの状態：許可されています" else "いまの状態：許可されていません"
            exactButton?.text = if (ok) "許可の画面を開く（取り消す場合）" else "許可の画面を開く"
            CountdownWidget.refreshAll(this)
        }
    }

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

    /** 押すとブラウザで開く文字 */
    private fun link(label: String, url: String) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(getColor(R.color.led))
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(2), 0, dp(10))
        setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
        }
    }

    companion object {
        private const val REQ_NOTICE = 1
        private const val REQ_BG = 2
        const val PRIVACY_URL = "https://github.com/hasiguti-1/nokori/blob/main/docs/privacy.md"
    }

    private fun check(label: String, on: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label; isChecked = on
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
