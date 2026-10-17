package jp.imamura.nokori

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15 以降（このアプリは Android 16 向けに作っている）は、画面が端まで描かれ、
 * ステータスバーやナビゲーションバーの下に中身が潜り込む。
 * もとの余白に、システムバーのぶんを足して重ならないようにする。
 * （受け取る値は、上のタイトルバーなどですでに使われたぶんを引いた残りなので、二重にはならない）
 */
fun View.padForSystemBars() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val (il, itp, ir, ib) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(s.left, s.top, s.right, s.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(l + il, t + itp, r + ir, b + ib)
        insets
    }
    requestApplyInsets()
}
