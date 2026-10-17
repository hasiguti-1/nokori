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
            Intent.ACTION_DATE_CHANGED -> refreshAll(ctx)
        }
    }

    override fun onDisabled(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.cancel(tickIntent(ctx))
    }

    companion object {
        private const val ACTION_TICK = "jp.imamura.nokori.TICK"
        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2)
        private val TITLES = intArrayOf(R.id.title0, R.id.title1, R.id.title2)
        private val DAYS = intArrayOf(R.id.days0, R.id.days1, R.id.days2)
        private val CHRONOS = intArrayOf(R.id.chrono0, R.id.chrono1, R.id.chrono2)

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CountdownWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val shown = Calc.sorted(Store.load(ctx), now)
                .filter { it.showInWidget }
                .filter { (it.targetMillis() ?: Long.MIN_VALUE) > now }
                .take(3)

            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setViewVisibility(R.id.empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)

            var nextRefresh = Long.MAX_VALUE
            for (i in 0 until 3) {
                val item = shown.getOrNull(i)
                if (item == null) {
                    views.setViewVisibility(ROWS[i], View.GONE)
                    continue
                }
                val left = item.targetMillis()!! - now
                val days = left / Calc.DAY_MS
                val rem = left % Calc.DAY_MS
                views.setViewVisibility(ROWS[i], View.VISIBLE)
                views.setTextViewText(TITLES[i], item.title)
                views.setTextViewText(DAYS[i], String.format(Locale.JAPAN, "%,d日", days))
                views.setChronometer(CHRONOS[i], elapsedNow + rem, "%s", true)
                views.setChronometerCountDown(CHRONOS[i], true)
                // この項目の「日」が1つ減る瞬間
                nextRefresh = minOf(nextRefresh, now + rem + 500)
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
