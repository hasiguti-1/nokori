package jp.imamura.nokori

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
            // ロック画面の通知を消されたら、出す設定を切る
            LockNotice.ACTION_DISMISSED -> Settings(ctx).lockNotice = false
        }
    }

    /** ウィジェットの大きさが変わったとき（ドット文字の画像は幅に合わせて描くので描き直す） */
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        refreshAll(ctx)
    }

    override fun onDisabled(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am?.cancel(tickIntent(ctx))
        // ウィジェットがなくなっても、ロック画面の通知のための描き直しは続ける
        refreshAll(ctx)
    }

    companion object {
        private const val ACTION_TICK = "jp.imamura.nokori.TICK"
        /** 「時刻ぴったりの更新」の許可が変わったとき（Android 12 以降） */
        private const val ACTION_EXACT_ALARM_PERMISSION =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        /** 行ごとの部品のID（レイアウトは tools/gen_widget_layouts.py で生成）。
         *  検証用の別アプリID（jp.imamura.nokori.exp）でも見つかるように、
         *  R の場所（jp.imamura.nokori）で引き、なければアプリIDで引く（普段の版では両者は同じ） */
        private val RES_PKG = R::class.java.name.substringBeforeLast('.')
        private fun id(ctx: Context, name: String): Int =
            ctx.resources.getIdentifier(name, "id", RES_PKG).takeIf { it != 0 }
                ?: ctx.resources.getIdentifier(name, "id", ctx.packageName)

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
            // ウィジェットがなくても、ロック画面の通知のために動かす
            render(ctx, mgr, ids)
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
            var rot: Rotation? = null
            val shown: List<Item>
            val anim = settings.rotateAnim
            if (settings.rotate && anim != RotateAnim.NONE && rest.size > 1) {
                // 動きを付けて切り替える：めくり部品に全部の項目を入れておく（アラームで起こさない）
                val period = settings.rotateSec * 1000L
                val max = if (settings.font.isImage) MAX_ROT_DOT else MAX_ROT_TEXT
                val list = rest.take(max)
                rot = Rotation(list, ((now / period) % list.size).toInt(), period, anim)
                shown = pinned
            } else if (settings.rotate) {
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
                val (views, next) = build(ctx, mgr, wid, settings, shown, now, elapsedNow, rotRow, rot)
                try {
                    mgr.updateAppWidget(wid, views)
                } catch (e: RuntimeException) {
                    // 画像が多すぎてホーム画面に送れなかったとき：切り替える項目を2件に減らして送り直す
                    val small = rot?.let { it.copy(items = it.items.take(2), start = it.start % minOf(2, it.items.size)) }
                    val (v2, _) = build(ctx, mgr, wid, settings, shown, now, elapsedNow, rotRow, small)
                    try { mgr.updateAppWidget(wid, v2) } catch (e2: RuntimeException) { }
                }
                nextRefresh = minOf(nextRefresh, next)
            }
            // ウィジェットがないときは、ウィジェット用の描き直しは要らない
            if (ids.isEmpty()) nextRefresh = Long.MAX_VALUE
            // ロック画面の通知：固定した項目のいちばん上、なければ期日がいちばん近い項目
            val notice = pinned.firstOrNull() ?: rest.firstOrNull()
            nextRefresh = minOf(nextRefresh, LockNotice.update(ctx, settings.lockNotice, notice, now))
            scheduleNext(ctx, nextRefresh)
        }

        /** 動きを付けて切り替える項目（start は最初に出す項目、periodMs は切り替えの間隔） */
        private data class Rotation(val items: List<Item>, val start: Int, val periodMs: Long, val anim: RotateAnim)

        /** 動きを付けて切り替える項目の上限（ドット文字は画像が多く、送れる量を超えないよう少なめ） */
        private const val MAX_ROT_TEXT = 10
        private const val MAX_ROT_DOT = 5

        /** 切り替えの中の1項目のレイアウト（字体ごと） */
        private fun rowLayout(font: FontChoice, ringFallback: Boolean) = when (font) {
            FontChoice.MONO -> R.layout.row_mono
            FontChoice.SANS -> R.layout.row_sans
            FontChoice.LIGHT -> R.layout.row_light
            FontChoice.CONDENSED -> R.layout.row_condensed
            FontChoice.SERIF -> R.layout.row_serif
            FontChoice.DOT -> R.layout.row_dot
            FontChoice.DOT_SEC -> R.layout.row_dotsec
            FontChoice.DOT_RING -> if (ringFallback) R.layout.row_dotsec else R.layout.row_dotring
        }

        /** 1つのウィジェットの中身を作る。戻り値は（表示内容, 次に描き直す時刻） */
        private fun build(
            ctx: Context, mgr: AppWidgetManager, wid: Int,
            settings: Settings, shown: List<Item>, now: Long, elapsedNow: Long,
            rotRow: Pair<Int, String>?, rot: Rotation?,
        ): Pair<RemoteViews, Long> {
            val font = settings.font
            val dot = font.isImage
            // 秒の回転盤は Android 12 以上だけ。それより前は「秒あり・時刻は等幅」で出す
            val ring = font == FontChoice.DOT_RING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val ringFallback = font == FontChoice.DOT_RING && !ring
            val secs = font == FontChoice.DOT_SEC || ringFallback
            val views = RemoteViews(ctx.packageName, if (ringFallback) R.layout.widget_dotsec else font.layout)
            val nothing = shown.isEmpty() && (rot == null || rot.items.isEmpty())
            views.setViewVisibility(R.id.empty, if (nothing) View.VISIBLE else View.GONE)

            // ウィジェットの幅（縦向き画面での幅＝MIN_WIDTH。左右の余白14dp×2を引く）
            val widthDp = mgr.getAppWidgetOptions(wid)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 300
            val innerPx = (DotRenderer.dp(ctx, widthDp.toFloat()) - DotRenderer.dp(ctx, 28f)).toInt()

            var nextRefresh = Long.MAX_VALUE
            if (dot) {
                val t = java.time.LocalTime.now(Calc.zone())
                views.setImageViewBitmap(R.id.headerlabel, DotRenderer.header(ctx, "残り時間"))
                views.setImageViewBitmap(R.id.headertime,
                    DotRenderer.header(ctx, String.format(Locale.JAPAN, "%02d:%02d", t.hour, t.minute)))
                // 時計の分が変わる瞬間
                nextRefresh = now + (60_000L - now % 60_000L) + 200
            }

            /**
             * 1件分の行を埋める（v はウィジェット全体か、切り替えの中の1項目の部品）。
             * pos は切り替え中の「2/5」の表示。戻り値は、この行のために次に描き直す時刻
             */
            fun fillRow(v: RemoteViews, i: Int, item: Item, pos: String?): Long {
                var next = Long.MAX_VALUE
                val target = item.targetMillis()!!
                val left = target - now
                val parts = Calc.parts(item, now)!!
                v.setViewVisibility(id(ctx, "row$i"), View.VISIBLE)

                // 切り替え中の行だけ「2/5」を出す
                val posId = id(ctx, "pos$i")
                v.setTextViewText(posId, pos ?: "")
                v.setViewVisibility(posId, if (pos != null) View.VISIBLE else View.GONE)

                // 種類ラベル
                val chip = id(ctx, "chip$i")
                v.setTextViewText(chip, item.kind.label)
                v.setInt(chip, "setBackgroundResource", chipBg(item.kind))
                v.setTextColor(chip, chipText(item.kind))

                // 残り（締切は7日を切ったら赤）
                val color = if (item.kind == Kind.DEADLINE && left < 7 * Calc.DAY_MS) URGENT else AMBER

                if (dot) {
                    // 名前と数字をドット文字の画像で（種類ラベルの幅ぶん名前を短く）
                    val titleMax = innerPx - DotRenderer.dp(ctx, if (pos != null) 110f else 72f).toInt()
                    v.setImageViewBitmap(id(ctx, "titleimg$i"),
                        DotRenderer.text(ctx, item.title, 16f, DotRenderer.TITLE, maxOf(50, titleMax)))
                    val clock = parts.clockMs
                    // 秒ありは、画像を「◯日」までにして、「時:分:秒」は標準の時計部品に数えさせる。
                    // 時計部品は端末の経過時間から計算するので、ほかのアプリから戻ったときもずれない。
                    // 「時」まで時計部品に任せるので、描き直しが遅れても時の境目で表示が乱れない
                    val num = DotRenderer.number(ctx, parts, color, clockByChrono = secs, ringSeconds = ring)
                    val numId = id(ctx, "numimg$i")
                    if (num.bitmap != null) {
                        v.setImageViewBitmap(numId, num.bitmap)
                        v.setViewVisibility(numId, View.VISIBLE)
                    } else {
                        v.setViewVisibility(numId, View.GONE)
                    }
                    if (secs) {
                        val cId = id(ctx, "secc$i")
                        if (clock != null) {
                            v.setViewVisibility(cId, View.VISIBLE)
                            v.setChronometer(cId, elapsedNow + clock, "%s", true)
                            v.setChronometerCountDown(cId, true)
                            v.setTextColor(cId, color)
                            v.setTextViewTextSize(cId, TypedValue.COMPLEX_UNIT_SP, num.clockSp)
                        } else {
                            v.setViewVisibility(cId, View.GONE)
                        }
                        // 秒ありの描き直しは、見出しの時刻（毎分）と日数の変わり目（下で設定）だけ
                    } else if (ring) {
                        // 秒は回転盤（AnalogClock）が時刻から毎秒計算する。
                        // 「時:分:」の画像は、残りの分が変わる瞬間に描き直す
                        val big = num.clockSp > 20f
                        for ((tag, isBig) in listOf("a" to false, "b" to true)) {
                            val winId = id(ctx, "ringw$tag$i")
                            if (clock == null || isBig != big) {
                                v.setViewVisibility(winId, View.GONE)
                                continue
                            }
                            val cId = id(ctx, "ring$tag$i")
                            val res = when {
                                color == URGENT && isBig -> R.drawable.dotring_red_26
                                color == URGENT -> R.drawable.dotring_red_18
                                isBig -> R.drawable.dotring_amber_26
                                else -> R.drawable.dotring_amber_18
                            }
                            v.setViewVisibility(winId, View.VISIBLE)
                            v.setIcon(cId, "setSecondHand", Icon.createWithResource(ctx, res))
                            v.setString(cId, "setTimeZone", RingSeconds.zone(target))
                        }
                        if (clock != null) next = minOf(next, now + left % 60_000L + 30)
                    } else {
                        // 秒なしは画像の「時:分」を変える
                        next = minOf(next, now + left % 60_000L + 200)
                    }
                } else {
                    v.setTextViewText(id(ctx, "title$i"), item.title)
                    // 表示単位に合わせて数字・単位・時計を出し分け
                    val daysId = id(ctx, "days$i")
                    val unitId = id(ctx, "unit$i")
                    val chronoId = id(ctx, "chrono$i")
                    if (parts.big != null) {
                        v.setViewVisibility(daysId, View.VISIBLE)
                        v.setViewVisibility(unitId, View.VISIBLE)
                        v.setTextViewText(daysId, parts.big)
                        v.setTextViewText(unitId, parts.unit ?: "")
                        v.setTextColor(daysId, color)
                    } else {
                        v.setViewVisibility(daysId, View.GONE)
                        v.setViewVisibility(unitId, View.GONE)
                    }
                    if (parts.clockMs != null) {
                        v.setViewVisibility(chronoId, View.VISIBLE)
                        v.setChronometer(chronoId, elapsedNow + parts.clockMs, "%s", true)
                        v.setChronometerCountDown(chronoId, true)
                        v.setTextColor(chronoId, color)
                    } else {
                        v.setViewVisibility(chronoId, View.GONE)
                    }
                }

                // 進み具合バー（その種類の色だけ表示）
                val start = item.startMillis()
                for (k in Kind.values()) {
                    val barId = id(ctx, "bar${i}_${k.name.lowercase()}")
                    if (k == item.kind && start != null && target > start) {
                        val p = (((now - start).toDouble() / (target - start)) * 1000).toInt().coerceIn(0, 1000)
                        v.setViewVisibility(barId, View.VISIBLE)
                        v.setProgressBar(barId, 1000, p, false)
                    } else {
                        v.setViewVisibility(barId, View.GONE)
                    }
                }

                // 次に描き直す時刻：日数が変わる瞬間（＝期日も含む）
                next = minOf(next, now + left % Calc.DAY_MS + 500)
                // 割合表示は時計がないので15分ごとに更新
                if (item.unit == DisplayUnit.PERCENT) next = minOf(next, now + 15 * 60_000L)
                return next
            }

            for (i in 0 until Settings.MAX_ROWS) {
                val rowId = id(ctx, "row$i")
                val item = shown.getOrNull(i)
                if (item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    continue
                }
                val pos = rotRow?.takeIf { it.first == i }?.second
                nextRefresh = minOf(nextRefresh, fillRow(views, i, item, pos))
            }

            // 「固定＋切り替え」で動きを付けるとき：固定の下の箱に、めくり部品と各項目の行を入れる。
            // めくり部品はホーム画面の側で一定間隔でめくるので、切り替えのためにアプリを起こさなくてよい
            views.removeAllViews(R.id.rotbox)
            if (rot != null && rot.items.isNotEmpty()) {
                val fl = RemoteViews(ctx.packageName, rot.anim.layout)
                rot.items.forEachIndexed { j, item ->
                    val rv = RemoteViews(ctx.packageName, rowLayout(font, ringFallback))
                    nextRefresh = minOf(nextRefresh, fillRow(rv, 0, item, "${j + 1}/${rot.items.size}"))
                    fl.addView(R.id.flipper, rv)
                }
                fl.setInt(R.id.flipper, "setFlipInterval", rot.periodMs.toInt())
                // 描き直しのたびに最初の項目へ戻らないよう、時刻から決めた項目から始める
                fl.setDisplayedChild(R.id.flipper, rot.start)
                views.addView(R.id.rotbox, fl)
                views.setViewVisibility(R.id.rotbox, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.rotbox, View.GONE)
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
