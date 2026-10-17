package jp.imamura.nokori

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * ホーム画面ウィジェット。近い順に3件を表示する。
 * 「◯日」は文字で表示し、1日未満の部分（時:分:秒）は Android 標準の
 * Chronometer に数えさせるので、アプリが動いていなくても秒が進む。
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
            Intent.ACTION_BOOT_COMPLETED -> refreshAll(ctx)
        }
    }

    override fun onDisabled(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.cancel(tickIntent(ctx))
    }

    companion object {
        private const val ACTION_TICK = "jp.imamura.nokori.TICK"
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
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val settings = Settings(ctx)
            val shown = Calc.sorted(Store.load(ctx) + Repeats.build(ctx, now), now)
                .filter { it.showInWidget && settings.widgetShows(it.kind) }
                .filter { (it.targetMillis() ?: Long.MIN_VALUE) > now }
                .take(settings.widgetCount)

            val views = RemoteViews(ctx.packageName, settings.font.layout)
            views.setViewVisibility(R.id.empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)

            var nextRefresh = Long.MAX_VALUE
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

                // 種類ラベルと名前
                val chip = id(ctx, "chip$i")
                views.setTextViewText(chip, item.kind.label)
                views.setInt(chip, "setBackgroundResource", chipBg(item.kind))
                views.setTextColor(chip, chipText(item.kind))
                views.setTextViewText(id(ctx, "title$i"), item.title)

                // 残り（締切は7日を切ったら赤）。表示単位に合わせて数字・単位・時計を出し分け
                val color = if (item.kind == Kind.DEADLINE && left < 7 * Calc.DAY_MS) URGENT else AMBER
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

                // 次に描き直す時刻：日数が変わる瞬間、時計の切れ目、期日
                val rem = left % Calc.DAY_MS
                nextRefresh = minOf(nextRefresh, now + rem + 500)
                // 割合表示は時計がないので15分ごとに更新
                if (item.unit == DisplayUnit.PERCENT) nextRefresh = minOf(nextRefresh, now + 15 * 60_000L)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.root, open)
            mgr.updateAppWidget(ids, views)

            scheduleNext(ctx, nextRefresh)
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, CountdownWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** 次に描き直す時刻をセット（正確なアラーム権限は不要な方式。最大1分ほど遅れることがある） */
        private fun scheduleNext(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = tickIntent(ctx)
            am.cancel(pi)
            if (at == Long.MAX_VALUE) return
            am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
        }
    }
}
