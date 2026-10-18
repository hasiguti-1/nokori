"""ドット文字（秒あり・秒もドット）の、秒の回転盤の絵を生成する。

使い方: python3 tools/gen_dot_ring.py   （fontTools が必要: pip install fonttools）

しくみ:
- ホーム画面の AnalogClock（Android 12 以上）は「秒針」の絵を、今の時刻の秒に合わせて
  1秒ごとに回して描く。時刻から毎回計算するので、見えない間があっても戻ったときに正しい。
- 秒針の代わりに、00〜59 のドット文字の数字を円周に並べた「回転盤」を渡し、
  ウィジェット側で上端の1つだけが見える窓（FrameLayout）で切り抜く。
- 数字 k は、盤の上から時計回りに 6k 度の位置に置く。秒針が 6s 度回ると、
  上に来るのは k = -s（mod 60）の数字になり、時間とともに数字が減っていく。
- 何秒から数え始めるかは、アプリが AnalogClock のタイムゾーンを秒単位でずらして合わせる
  （RingSeconds.kt）。

数字の絵は字体の輪郭（ベクター）なので、どの画面密度でもぼやけない。
色はアンバーと赤の2種類を焼き込む（色付けの処理を使うと絵が1つずつ複製され、
ホーム画面のメモリを多く使うため）。
"""
import math
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
FONT = RES / "font/dotgothic16.ttf"

COLORS = {"amber": "#FFB23E", "red": "#FF6B5E"}  # CountdownWidget.kt の AMBER / URGENT
SIZES = [18, 26]  # DotRenderer.clockSp と同じ（通常は18sp、時計だけの行は26sp）


def metrics():
    """字体の寸法（em に対する比）。Android はこの字体では hhea の値で行の高さを決める"""
    f = TTFont(FONT)
    upem = f["head"].unitsPerEm
    hhea = f["hhea"]
    digit_w = f.getGlyphSet()[f.getBestCmap()[ord("0")]].width
    return {
        "ascent": hhea.ascent / upem,
        "descent": -hhea.descent / upem,
        "digit": digit_w / upem,
    }


def geometry(sp):
    """文字の大きさ sp に対する、数字1字の幅・高さ、窓の幅、盤の半径と直径（すべて sp）"""
    m = metrics()
    digit = m["digit"] * sp
    height = (m["ascent"] + m["descent"]) * sp
    slot = 2 * digit  # 2けた分
    # 隣の数字（6度ずれた位置）の内側の角が窓（幅 slot）に入らない半径:
    #   (R - height)·sin6° - (slot/2)·cos6° >= slot/2
    s6, c6 = math.sin(math.radians(6)), math.cos(math.radians(6))
    radius = height + slot * (1 + c6) / (2 * s6) + 2  # 2sp の余裕
    radius = math.ceil(radius)
    return {"digit": digit, "height": height, "window": slot, "radius": radius, "size": 2 * radius}


def fmt(x):
    return f"{x:.3f}".rstrip("0").rstrip(".")


def digit_vectors():
    """数字0〜9のベクター画像（色ごと）。縦は行の高さ（ascent+descent）ぶん"""
    f = TTFont(FONT)
    upem = f["head"].unitsPerEm
    gs = f.getGlyphSet()
    cmap = f.getBestCmap()
    asc, desc = f["hhea"].ascent, -f["hhea"].descent
    out = {}
    for d in "0123456789":
        g = gs[cmap[ord(d)]]
        pen = SVGPathPen(gs)
        # フォントの座標（上が正）を画像の座標（下が正、上端＝ascent）に変換
        g.draw(TransformPen(pen, (1, 0, 0, -1, 0, asc)))
        out[d] = (pen.getCommands(), g.width, asc + desc)
    return out, upem


def write_vectors():
    vecs, upem = digit_vectors()
    for cname, color in COLORS.items():
        for d, (path, w, h) in vecs.items():
            (RES / f"drawable/dotdig_{cname}_{d}.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- tools/gen_dot_ring.py で生成。直接編集しない -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{fmt(w / upem * 18)}sp"
    android:height="{fmt(h / upem * 18)}sp"
    android:viewportWidth="{w}"
    android:viewportHeight="{h}">
    <path android:fillColor="{color}" android:pathData="{path}" />
</vector>
''', encoding="utf-8")


def write_rings():
    for sp in SIZES:
        g = geometry(sp)
        size, dw, dh = fmt(g["size"]), fmt(g["digit"]), fmt(g["height"])
        for cname in COLORS:
            items = []
            for k in range(60):
                tens, ones = divmod(k, 10)
                # 外側の item は盤全体の大きさ。その中を 6k 度回し、上端の中央に2けたを置く。
                # 十の位は右に1字ぶん、一の位は左に1字ぶんの余白を取って中央からずらす
                items.append(f'''    <item android:width="{size}sp" android:height="{size}sp">
        <rotate android:fromDegrees="{6 * k}" android:toDegrees="{6 * k}"
            android:pivotX="50%" android:pivotY="50%">
            <layer-list>
                <item android:gravity="top|center_horizontal" android:right="{dw}sp"
                    android:width="{dw}sp" android:height="{dh}sp"
                    android:drawable="@drawable/dotdig_{cname}_{tens}" />
                <item android:gravity="top|center_horizontal" android:left="{dw}sp"
                    android:width="{dw}sp" android:height="{dh}sp"
                    android:drawable="@drawable/dotdig_{cname}_{ones}" />
            </layer-list>
        </rotate>
    </item>''')
            (RES / f"drawable/dotring_{cname}_{sp}.xml").write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- tools/gen_dot_ring.py で生成。直接編集しない。
     秒の回転盤（{sp}sp）：数字 k を上から時計回りに 6k 度の位置に置く -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
{chr(10).join(items)}
</layer-list>
''', encoding="utf-8")
    # 文字盤・時針・分針の代わりに置く透明な絵
    (RES / "drawable/clear_dot.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<!-- tools/gen_dot_ring.py で生成。AnalogClock の文字盤と時針・分針を消すための透明な絵 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <size android:width="1dp" android:height="1dp" />
    <solid android:color="#00000000" />
</shape>
''', encoding="utf-8")


if __name__ == "__main__":
    write_vectors()
    write_rings()
    for sp in SIZES:
        print(sp, geometry(sp))
