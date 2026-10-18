"""ウィジェットのレイアウトを字体ごとに生成する。

使い方: python3 tools/gen_widget_layouts.py
（ウィジェットの見た目を変えるときは、このファイルを直して実行し直す）
"""
from pathlib import Path

from gen_dot_ring import fmt, geometry as ring_geometry

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
KINDS = ["game", "deadline", "event", "age", "life", "repeat"]
ROWS = 6  # ウィジェットに出せる最大件数（Settings.MAX_ROWS と合わせる）
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

POS = '''<TextView
                android:id="@+id/pos{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:fontFamily="{font}"
                android:textColor="#8A93A8"
                android:textSize="11sp"
                android:visibility="gone" />'''


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
        unit_id = ""
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
        unit_id = f'android:id="@+id/unit{i}"'
    # 切り替え中の行に「2/5」を出す部品（見本には不要）
    pos = "" if sample else POS.format(i=i, font=font)
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
            {pos}
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
                {unit_id}
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
    if preview:
        rows = "".join(row(i, font, SAMPLE[i]) for i in range(len(SAMPLE)))
    else:
        rows = "".join(row(i, font) for i in range(ROWS))
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


def sec_chrono(cid, font="monospace"):
    """秒あり用の「時:分:秒」。Android 標準の時計部品なので、端末の経過時間から数える
    （ホーム画面に戻ったときもずれない）。文字の大きさと色はアプリから入れる。
    font は試作A で "@font/dotgothic16"（アプリ同梱の字体を直接指定できるかの検証）"""
    return f'''<Chronometer
                android:id="@+id/{cid}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="{font}"
                android:includeFontPadding="false"
                android:textColor="#FFB23E"
                android:textSize="18sp"
                android:visibility="gone" />'''


def ring_window(wid, cid, sp):
    """試作B：秒の回転盤。AnalogClock（盤の絵は tools/gen_dot_ring.py）を、
    数字2けたぶんの窓（FrameLayout）で切り抜き、上端の1つだけを見せる"""
    g = ring_geometry(sp)
    return f'''<FrameLayout
                android:id="@+id/{wid}"
                android:layout_width="{fmt(g["window"])}sp"
                android:layout_height="{fmt(g["height"])}sp"
                android:visibility="gone">
                <AnalogClock
                    android:id="@+id/{cid}"
                    android:layout_width="{fmt(g["size"])}sp"
                    android:layout_height="{fmt(g["size"])}sp"
                    android:layout_gravity="top|center_horizontal"
                    android:dial="@drawable/clear_dot"
                    android:hand_hour="@drawable/clear_dot"
                    android:hand_minute="@drawable/clear_dot"
                    android:importantForAccessibility="no" />
            </FrameLayout>'''


def dot_layout(secs=False, sec_font="monospace", ring=False):
    """ドット文字用。文字は画像で描くので ImageView を置く。
    secs=True なら「時:分:秒」を標準の時計部品で出す（「◯日」までは画像）。
    ring=True（試作B）なら「◯日 時:分:」までを画像にし、秒は回転盤で出す（18sp用と26sp用）。"""
    rows = []
    for i in range(ROWS):
        if ring:
            sec_parts = ring_window(f"ringwa{i}", f"ringa{i}", 18) + "\n            " + \
                ring_window(f"ringwb{i}", f"ringb{i}", 26)
        elif secs:
            sec_parts = sec_chrono(f"secc{i}", sec_font)
        else:
            sec_parts = ""
        bars = "\n".join(f'''<ProgressBar
                android:id="@+id/bar{i}_{k}"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="3dp"
                android:max="1000"
                android:progressDrawable="@drawable/bar_{k}"
                android:visibility="gone" />''' for k in KINDS)
        rows.append(f'''
    <LinearLayout
        android:id="@+id/row{i}"
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
                android:id="@+id/chip{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:paddingStart="6dp"
                android:paddingEnd="6dp"
                android:paddingTop="1dp"
                android:paddingBottom="1dp"
                android:textSize="11sp" />
            <ImageView
                android:id="@+id/titleimg{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:scaleType="center" />
            <TextView
                android:id="@+id/pos{i}"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="6dp"
                android:fontFamily="monospace"
                android:gravity="end"
                android:textColor="#8A93A8"
                android:textSize="11sp"
                android:visibility="gone" />
        </LinearLayout>
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:gravity="end|bottom"
            android:orientation="horizontal">
            <ImageView
                android:id="@+id/numimg{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:scaleType="center" />
            {sec_parts}
        </LinearLayout>
        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="3dp"
            android:layout_marginTop="4dp">
            {bars}
        </FrameLayout>
    </LinearLayout>''')
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- tools/gen_widget_layouts.py で生成。直接編集しない -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:orientation="vertical"
    android:paddingStart="14dp"
    android:paddingEnd="14dp"
    android:paddingTop="10dp"
    android:paddingBottom="10dp">

    <ImageView
        android:id="@+id/headerimg"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:scaleType="center" />
    <ImageView
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="5dp"
        android:scaleType="fitXY"
        android:src="@drawable/divider" />

    <TextView
        android:id="@+id/empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="アプリで発売日や締切を登録してください"
        android:textColor="#8A93A8"
        android:textSize="13sp"
        android:visibility="gone" />
{"".join(rows)}
</LinearLayout>
'''


if __name__ == "__main__":
    for name, fam in FONTS.items():
        (RES / f"layout/widget_{name}.xml").write_text(layout(fam), encoding="utf-8")
    (RES / "layout/widget_dot.xml").write_text(dot_layout(), encoding="utf-8")
    (RES / "layout/widget_dotsec.xml").write_text(dot_layout(secs=True), encoding="utf-8")
    # 試作A：時計部品にアプリ同梱のドット字体を XML で直接指定する
    (RES / "layout/widget_dotxml.xml").write_text(
        dot_layout(secs=True, sec_font="@font/dotgothic16"), encoding="utf-8")
    # 試作B：秒をドット文字の回転盤（AnalogClock）で出す。Android 12 以上で使う
    (RES / "layout/widget_dotring.xml").write_text(dot_layout(ring=True), encoding="utf-8")
    (RES / "layout/widget_preview.xml").write_text(layout("monospace", preview=True), encoding="utf-8")
    # 使わなくなったレイアウト（widget.xml は初期の版、widget_dotsec2.xml は秒の切り替え部品の頃のもの）
    for old_name in ("widget.xml", "widget_dotsec2.xml"):
        old = RES / "layout" / old_name
        if old.exists():
            old.unlink()
    print("generated:", sorted(p.name for p in (RES / "layout").glob("widget_*.xml")))
