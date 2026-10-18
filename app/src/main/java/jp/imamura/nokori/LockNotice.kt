package jp.imamura.nokori

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * ロック画面に残り時間を出すための、常駐の通知（1件）。
 * 出す項目は「固定した項目のいちばん上」、固定がなければ「期日がいちばん近い項目」。
 * 秒は通知の標準の時計（Chronometer）が数える（字体は端末の標準）。
 * 描き直しはウィジェットと同じアラームで行う（CountdownWidget.render から呼ぶ）。
 */
object LockNotice {
    private const val CHANNEL = "lock_screen"
    private const val ID = 1
    const val ACTION_DISMISSED = "jp.imamura.nokori.NOTICE_DISMISSED"
    private const val AMBER = 0xFFFFB23E.toInt()

    /** 通知に出す内容と、次に描き直す時刻（端末に頼らない計算だけ。単体テスト用に分けてある） */
    data class Plan(val text: String?, val whenMs: Long?, val next: Long)

    fun plan(parts: LeftParts, target: Long, now: Long): Plan {
        val text = parts.big?.let { "あと ${it}${parts.unit ?: ""}" }
        val clock = parts.clockMs
        return if (clock != null && clock > 0) {
            // 時計が0になる瞬間に「◯日」が1つ減るので、そこで描き直す
            Plan(text, now + clock, minOf(now + clock, target) + 500)
        } else {
            // 時計のない表示（日数だけ・割合など）は15分ごと
            Plan(text, null, minOf(now + 15 * 60_000L, target + 500))
        }
    }

    /** 通知を出してよいか（Android 13 以降は利用者の許可が要る） */
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 通知を出し直す（出さない設定なら消す）。戻り値は次に描き直す時刻 */
    fun update(ctx: Context, on: Boolean, item: Item?, now: Long): Long {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return Long.MAX_VALUE
        val parts = item?.let { Calc.parts(it, now) }
        val target = item?.targetMillis()
        if (!on || item == null || parts == null || target == null || target <= now || !permitted(ctx)) {
            nm.cancel(ID)
            return Long.MAX_VALUE
        }
        ensureChannel(nm)
        val p = plan(parts, target, now)

        val open = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 通知を消されたら「出す」設定を切る（消したのに毎分出し直されないように）
        val dismissed = PendingIntent.getBroadcast(
            ctx, 11, Intent(ctx, CountdownWidget::class.java).setAction(ACTION_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(item.title)
            .setColor(AMBER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            // ロック画面で「内容を隠す」設定でも、残り時間は見せる
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
        p.text?.let { b.setContentText(it) }
        if (p.whenMs != null) {
            b.setShowWhen(true).setWhen(p.whenMs).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        nm.notify(ID, b.build())
        return p.next
    }

    /**
     * 通知の種類。音とバイブはなし。重要度は「標準」にする
     * （「低」だと、ロック画面で控えめな通知を隠す設定の機種では出なくなるため）
     */
    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "ロック画面の残り時間", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "固定した項目の残り時間を、ロック画面と通知欄に出し続けます"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }
}
