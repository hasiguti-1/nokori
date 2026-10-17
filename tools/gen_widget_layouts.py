"""ウィジェットのレイアウトを字体ごとに生成する。

使い方: python3 tools/gen_widget_layouts.py
（ウィジェットの見た目を変えるときは、このファイルを直して実行し直す）
"""
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
KINDS = ["game", "deadline", "event", "age", "life", "repeat"]
FONTS = {
    "mono": "monospace",
    "sans": "sans-serif",
    "light": "sans-serif-light",
    "condensed": "sans-serif-condensed",
    "serif": "serif",
}

# 見本（ウィジェットを選ぶ画面）用の中身
SAMPLE = [
    ("ゲーム", "chip_game", "#9FE1CB", "GTA6 発売日", "32", "07:22:57", "#FFB23E", "bar_game", 62),
    ("締切", "chip_deadline", "#F5C4B3", "書類の提出", "3", "04:51:09", "#FF6B5E", "bar_deadline", 91),
    ("くり返し", "chip_repeat", "#D3D1C7", "今日が終わるまで", "0", "06:37:03", "#FFB23E", "bar_repeat", 72),
]


def row(i, font, sample=None):
    """1件分の行。sample があれば見本用に文字を埋め、時計の代わりに普通の文字にする。"""
    if sample:
        chip, chip_bg, chip_fg, title, days, hms, color, bar, pct = sample
        chip_attr = f'android:text="{chip}" android:background="@drawable/{chip_bg}" android:textColor="{chip_fg}"'
        title_attr = f'android:text="{title}"'
        days_attr = f'android:text="{days}" android:textColor="{color}"'
        clock = f'''<TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="{font}"
                android:includeFontPadding="false"
                android:text="{hms}"
                android:textColor="{color}"
                android:textSize="18sp" />'''
        bars = f'''<ProgressBar
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="3dp"
                android:max="100"
                android:progress="{pct}"
                android:progressDrawable="@drawable/{bar}" />'''
        rid = ""
    else:
        chip_attr = f'android:id="@+id/chip{i}"'
        title_attr = f'android:id="@+id/title{i}"'
        days_attr = f'android:id="@+id/days{i}" android:textColor="#FFB23E"'
        clock = f'''<Chronometer
                android:id="@+id/chrono{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="{font}"
                android:includeFontPadding="false"
                android:textColor="#FFB23E"
                android:textSize="18sp" />'''
        bars = "\n".join(f'''<ProgressBar
                android:id="@+id/bar{i}_{k}"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="3dp"
                android:max="1000"
                android:progressDrawable="@drawable/bar_{k}"
                android:visibility="gone" />''' for k in KINDS)
        rid = f'android:id="@+id/row{i}"'
    return f'''
    <LinearLayout
        {rid}
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="vertical">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">
            <TextView
                {chip_attr}
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:paddingStart="6dp"
                android:paddingEnd="6dp"
                android:paddingTop="1dp"
                android:paddingBottom="1dp"
                android:textSize="11sp" />
            <TextView
                {title_attr}
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:ellipsize="end"
                android:maxLines="1"
                android:textColor="#E7EAF2"
                android:textSize="16sp" />
        </LinearLayout>
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="end|bottom"
            android:orientation="horizontal">
            <TextView
                {days_attr}
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="{font}"
                android:includeFontPadding="false"
                android:textSize="26sp" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="3dp"
                android:layout_marginEnd="8dp"
                android:text="日"
                android:textColor="#8A93A8"
                android:textSize="12sp" />
            {clock}
        </LinearLayout>
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="3dp"
            android:layout_marginTop="4dp">
            {bars}
        </FrameLayout>
    </LinearLayout>'''


def layout(font, preview=False):
    clock = (f'''<TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="{font}"
            android:text="16:37:03"
            android:textColor="#8A93A8"
            android:textSize="11sp" />''' if preview else f'''<TextClock
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="{font}"
            android:format12Hour="HH:mm:ss"
            android:format24Hour="HH:mm:ss"
            android:letterSpacing="0.08"
            android:textColor="#8A93A8"
            android:textSize="11sp" />''')
    rows = "".join(row(i, font, SAMPLE[i] if preview else None) for i in range(3))
    empty = "" if preview else '''
    <TextView
        android:id="@+id/empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="アプリで発売日や締切を登録してください"
        android:textColor="#8A93A8"
        android:textSize="13sp"
        android:visibility="gone" />'''
    root_id = "" if preview else 'android:id="@+id/root"'
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- tools/gen_widget_layouts.py で生成。直接編集しない -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    {root_id}
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:paddingStart="14dp"
    android:paddingEnd="14dp"
    android:paddingTop="10dp"
    android:paddingBottom="10dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">
        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:letterSpacing="0.08"
            android:text="残り時間"
            android:textColor="#8A93A8"
            android:textSize="11sp" />
        {clock}
    </LinearLayout>
    <ImageView
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="5dp"
        android:scaleType="fitXY"
        android:src="@drawable/divider" />
{empty}
{rows}
</LinearLayout>
'''


if __name__ == "__main__":
    for name, fam in FONTS.items():
        (RES / f"layout/widget_{name}.xml").write_text(layout(fam), encoding="utf-8")
    (RES / "layout/widget_preview.xml").write_text(layout("monospace", preview=True), encoding="utf-8")
    old = RES / "layout/widget.xml"
    if old.exists():
        old.unlink()
    print("generated:", sorted(p.name for p in (RES / "layout").glob("widget_*.xml")))
