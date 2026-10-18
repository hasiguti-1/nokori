package jp.imamura.nokori

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * ホーム画面ウィジェット。固定した項目（最大2件）を上に出し、その下は
 * 「近い順に並べる」か「1件ずつ一定間隔で切り替える」かを設定で選ぶ。
 * 切り替えは描き直しのアラームで行い、何件目を出すかは時刻から決める。
 * 「◯日」は文字（ドット文字では画像）で表示し、1日未満の部分（時:分:秒）は
 * Android 標準の Chronometer に数えさせる。Chronometer は端末の経過時間から
 * 毎回計算するので、ホーム画面が見えない間があっても、戻ったときに正しい値になる。
 * 日数が変わる瞬間にアラームで描き直す。
 */
class CountdownWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, mgr, ids)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_EXACT_ALARM_PERMISSION -> refreshAll(ctx)
        }
    }

    /** ウィジェットの大きさが変わったとき（ドット文字の画像は幅に合わせて描くので描き直す） */
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        refreshAll(ctx)
    }

    override fun onDisabled(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.cancel(tickIntent(ctx))
    }

    companion object {
        private const val ACTION_TICK = "jp.imamura.nokori.TICK"
        /** 「時刻ぴったりの更新」の許可が変わったとき（Android 12 以降） */
        private const val ACTION_EXACT_ALARM_PERMISSION =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        /** 行ごとの部品のID（レイアウトは tools/gen_widget_layouts.py で生成） */
        private fun id(ctx: Context, name: String): Int =
            ctx.resources.getIdentifier(name, "id", ctx.packageName)

        /** 種類ラベルの背景と文字色 */
        private fun chipBg(k: Kind) = when (k) {
            Kind.GAME -> R.drawable.chip_game
            Kind.DEADLINE -> R.drawable.chip_deadline
            Kind.EVENT -> R.drawable.chip_event
            Kind.AGE -> R.drawable.chip_age
            Kind.REPEAT -> R.drawable.chip_repeat
            Kind.LIFE -> R.drawable.chip_life
        }

        private fun chipText(k: Kind) = when (k) {
            Kind.GAME -> 0xFF9FE1CB.toInt()
            Kind.DEADLINE -> 0xFFF5C4B3.toInt()
            Kind.EVENT -> 0xFFB5D4F4.toInt()
            Kind.AGE -> 0xFFF4C0D1.toInt()
            Kind.REPEAT -> 0xFFD3D1C7.toInt()
            Kind.LIFE -> 0xFFCECBF6.toInt()
        }

        private val AMBER = 0xFFFFB23E.toInt()
        private val URGENT = 0xFFFF6B5E.toInt()

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CountdownWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            // 今の時刻と、時計部品が数える基準の時刻を同じ瞬間に取る（ずれると秒が1つずれるため）
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val settings = Settings(ctx)
            val all = Calc.sorted(Store.load(ctx) + Repeats.build(ctx, now), now)
                .filter { (it.targetMillis() ?: Long.MIN_VALUE) > now }
            // 固定した項目（固定した順。固定は「ウィジェットに出す」設定より優先）
            val pinned = settings.pinned.mapNotNull { pid -> all.firstOrNull { it.id == pid } }
                .take(Settings.MAX_PINNED)
            val pinnedIds = pinned.map { it.id }.toSet()
            val rest = all.filter { it.id !in pinnedIds }
                .filter { it.showInWidget && settings.widgetShows(it.kind) }

            var nextRefresh = Long.MAX_VALUE
            // 切り替え中の行（行番号と「2/5」の表示）
            var rotRow: Pair<Int, String>? = null
            val shown: List<Item>
            if (settings.rotate) {
                // 固定の下に1行だけ出し、一定間隔で次の項目に切り替える。
                // 何件目を出すかは時刻から決めるので、状態を保存しなくてよい
                val period = settings.rotateSec * 1000L
                val slot = now / period
                val pick = rest.getOrNull((slot % maxOf(1, rest.size)).toInt())
                shown = pinned + listOfNotNull(pick)
                if (pick != null && rest.size > 1) {
                    rotRow = pinned.size to "${(slot % rest.size).toInt() + 1}/${rest.size}"
                    nextRefresh = (slot + 1) * period + 50
                }
            } else {
                shown = (pinned + rest).take(maxOf(settings.widgetCount, pinned.size))
            }

            // ドット文字は幅に合わせて画像を描くので、ウィジェットごとに作る
            for (wid in ids) {
                val (views, next) = build(ctx, mgr, wid, settings, shown, now, elapsedNow, rotRow)
                mgr.updateAppWidget(wid, views)
                nextRefresh = minOf(nextRefresh, next)
            }
            scheduleNext(ctx, nextRefresh)
        }

        /** 1つのウィジェットの中身を作る。戻り値は（表示内容, 次に描き直す時刻） */
        private fun build(
            ctx: Context, mgr: AppWidgetManager, wid: Int,
            settings: Settings, shown: List<Item>, now: Long, elapsedNow: Long,
            rotRow: Pair<Int, String>?,
        ): Pair<RemoteViews, Long> {
            val dot = settings.font.isImage
            val secs = settings.font == FontChoice.DOT_SEC
            val views = RemoteViews(ctx.packageName, settings.font.layout)
            views.setViewVisibility(R.id.empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)

            // ウィジェットの幅（縦向き画面での幅＝MIN_WIDTH。左右の余白14dp×2を引く）
            val widthDp = mgr.getAppWidgetOptions(wid)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
            val innerPx = (DotRenderer.dp(ctx, widthDp.toFloat()) - DotRenderer.dp(ctx, 28f)).toInt()

            var nextRefresh = Long.MAX_VALUE
            if (dot) {
                val t = java.time.LocalTime.now(Calc.zone())
                views.setImageViewBitmap(R.id.headerimg,
                    DotRenderer.header(ctx, innerPx, String.format(Locale.JAPAN, "%02d:%02d", t.hour, t.minute)))
                // 時計の分が変わる瞬間
                nextRefresh = now + (60_000L - now % 60_000L) + 200
            }

            for (i in 0 until Settings.MAX_ROWS) {
                val rowId = id(ctx, "row$i")
                val item = shown.getOrNull(i)
                if (item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    continue
                }
                val target = item.targetMillis()!!
                val left = target - now
                val parts = Calc.parts(item, now)!!
                views.setViewVisibility(rowId, View.VISIBLE)

                // 切り替え中の行だけ「2/5」を出す
                val posId = id(ctx, "pos$i")
                val pos = rotRow?.takeIf { it.first == i }?.second
                views.setTextViewText(posId, pos ?: "")
                views.setViewVisibility(posId, if (pos != null) View.VISIBLE else View.GONE)

                // 種類ラベル
                val chip = id(ctx, "chip$i")
                views.setTextViewText(chip, item.kind.label)
                views.setInt(chip, "setBackgroundResource", chipBg(item.kind))
                views.setTextColor(chip, chipText(item.kind))

                // 残り（締切は7日を切ったら赤）
                val color = if (item.kind == Kind.DEADLINE && left < 7 * Calc.DAY_MS) URGENT else AMBER

                if (dot) {
                    // 名前と数字をドット文字の画像で（種類ラベルの幅ぶん名前を短く）
                    val titleMax = innerPx - DotRenderer.dp(ctx, if (pos != null) 110f else 72f).toInt()
                    views.setImageViewBitmap(id(ctx, "titleimg$i"),
                        DotRenderer.text(ctx, item.title, 16f, DotRenderer.TITLE, maxOf(50, titleMax)))
                    val clock = parts.clockMs
                    // 秒ありは、画像を「◯日」までにして、「時:分:秒」は標準の時計部品に数えさせる。
                    // 時計部品は端末の経過時間から計算するので、ほかのアプリから戻ったときもずれない。
                    // 「時」まで時計部品に任せるので、描き直しが遅れても時の境目で表示が乱れない
                    val num = DotRenderer.number(ctx, parts, color, clockByChrono = secs)
                    val numId = id(ctx, "numimg$i")
                    if (num.bitmap != null) {
                        views.setImageViewBitmap(numId, num.bitmap)
                        views.setViewVisibility(numId, View.VISIBLE)
                    } else {
                        views.setViewVisibility(numId, View.GONE)
                    }
                    if (secs) {
                        val cId = id(ctx, "secc$i")
                        if (clock != null) {
                            views.setViewVisibility(cId, View.VISIBLE)
                            views.setChronometer(cId, elapsedNow + clock, "%s", true)
                            views.setChronometerCountDown(cId, true)
                            views.setTextColor(cId, color)
                            views.setTextViewTextSize(cId, TypedValue.COMPLEX_UNIT_SP, num.clockSp)
                        } else {
                            views.setViewVisibility(cId, View.GONE)
                        }
                        // 秒ありの描き直しは、見出しの時刻（毎分）と日数の変わり目（下で設定）だけ
                    } else {
                        // 秒なしは画像の「時:分」を変える
                        nextRefresh = minOf(nextRefresh, now + left % 60_000L + 200)
                    }
                } else {
                    views.setTextViewText(id(ctx, "title$i"), item.title)
                    // 表示単位に合わせて数字・単位・時計を出し分け
                    val daysId = id(ctx, "days$i")
                    val unitId = id(ctx, "unit$i")
                    val chronoId = id(ctx, "chrono$i")
                    if (parts.big != null) {
                        views.setViewVisibility(daysId, View.VISIBLE)
                        views.setViewVisibility(unitId, View.VISIBLE)
                        views.setTextViewText(daysId, parts.big)
                        views.setTextViewText(unitId, parts.unit ?: "")
                        views.setTextColor(daysId, color)
                    } else {
                        views.setViewVisibility(daysId, View.GONE)
                        views.setViewVisibility(unitId, View.GONE)
                    }
                    if (parts.clockMs != null) {
                        views.setViewVisibility(chronoId, View.VISIBLE)
                        views.setChronometer(chronoId, elapsedNow + parts.clockMs, "%s", true)
                        views.setChronometerCountDown(chronoId, true)
                        views.setTextColor(chronoId, color)
                    } else {
                        views.setViewVisibility(chronoId, View.GONE)
                    }
                }

                // 進み具合バー（その種類の色だけ表示）
                val start = item.startMillis()
                for (k in Kind.values()) {
                    val barId = id(ctx, "bar${i}_${k.name.lowercase()}")
                    if (k == item.kind && start != null && target > start) {
                        val p = (((now - start).toDouble() / (target - start)) * 1000).toInt().coerceIn(0, 1000)
                        views.setViewVisibility(barId, View.VISIBLE)
                        views.setProgressBar(barId, 1000, p, false)
                    } else {
                        views.setViewVisibility(barId, View.GONE)
                    }
                }

                // 次に描き直す時刻：日数が変わる瞬間（＝期日も含む）
                nextRefresh = minOf(nextRefresh, now + left % Calc.DAY_MS + 500)
                // 割合表示は時計がないので15分ごとに更新
                if (item.unit == DisplayUnit.PERCENT) nextRefresh = minOf(nextRefresh, now + 15 * 60_000L)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.root, open)
            return views to nextRefresh
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, CountdownWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** 次に描き直す時刻をセット */
        private fun scheduleNext(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = tickIntent(ctx)
            am.cancel(pi)
            if (at == Long.MAX_VALUE) return
            // 正確なアラームが使えるなら使う（Android 12 以降、時間枠つきは最大10分ほど遅れるため）。
            // RTC（起こさない）なので、画面が消えている間は鳴らず、点いたときにまとめて更新される
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExact(AlarmManager.RTC, at, pi)
                else am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
            } catch (e: SecurityException) {
                // 判定と予約の間に許可が取り消されたとき
                am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
            }
        }
    }
}
