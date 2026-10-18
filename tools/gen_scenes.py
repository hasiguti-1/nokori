"""ライブ壁紙の「シーン」（掲示板のある風景のドット絵）を生成する。

使い方: python3 tools/gen_scenes.py   （Pillow が必要: pip install pillow）

- 絵は 270×600 のドット絵（1 ドット＝画面の 4 ピクセル。1080×2400 の画面にちょうど合う）。
  アプリはぼかさずに拡大して描く。
- 掲示板の表示面の四隅（絵の上の座標）と、行の数・文字の色などを
  app/src/main/java/jp/imamura/nokori/SceneData.kt に書き出す（アプリはそこから読む）。
- 乱数は種を固定しているので、何度実行しても同じ絵になる。
"""
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/res/drawable-nodpi"
KT = ROOT / "app/src/main/java/jp/imamura/nokori/SceneData.kt"
W, H = 270, 600

BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def vgrad(img, y0, y1, stops, steps=10):
    """縦のグラデーション（色数を絞って、ドット絵らしく市松に混ぜる）"""
    px = img.load()
    stops = [(p, hexc(c)) for p, c in stops]
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0 - 1)
        for k in range(len(stops) - 1):
            if stops[k][0] <= t <= stops[k + 1][0]:
                lt = (t - stops[k][0]) / max(1e-6, stops[k + 1][0] - stops[k][0])
                a, b = stops[k][1], stops[k + 1][1]
                break
        q = lt * steps
        lo = int(q)
        frac = q - lo
        for x in range(W):
            th = (BAYER[y % 4][x % 4] + 0.5) / 16
            step = lo + (1 if frac > th else 0)
            px[x, y] = mix(a, b, min(1.0, step / steps))


def stars(d, rnd, y0, y1, n, colors=("#FFFFFF", "#C9D6FF", "#8FA2D9")):
    for _ in range(n):
        x, y = rnd.randrange(W), rnd.randrange(y0, y1)
        d.point((x, y), fill=rnd.choice(colors))


def skyline(d, rnd, base, hmin, hmax, color, win_colors, lit=0.35, wmin=14, wmax=34, slots=None):
    """ビルの影と、明かりのついた窓（slots を渡すと、窓の位置をすべて記録する）"""
    x = -rnd.randrange(10)
    while x < W:
        w = rnd.randrange(wmin, wmax)
        top = base - rnd.randrange(hmin, hmax)
        d.rectangle((x, top, x + w - 1, base), fill=color)
        for wy in range(top + 4, base - 3, 5):
            for wx in range(x + 3, x + w - 3, 4):
                if slots is not None:
                    slots.append((wx, wy, color))
                if rnd.random() < lit:
                    d.rectangle((wx, wy, wx + 1, wy + 1), fill=rnd.choice(win_colors))
        x += w + rnd.randrange(0, 4)


def board(d, x0, y0, x1, y1, frame, face, bolt):
    """正面向きの掲示板：枠＋表示面＋角のボルト"""
    d.rectangle((x0 - 4, y0 - 4, x1 + 4, y1 + 4), fill=frame)
    d.rectangle((x0 - 4, y0 - 4, x1 + 4, y0 - 3), fill=mix(hexc(frame), (255, 255, 255), 0.25))
    d.rectangle((x0, y0, x1, y1), fill=face)
    for bx, by in ((x0 - 2, y0 - 2), (x1 + 2, y0 - 2), (x0 - 2, y1 + 2), (x1 + 2, y1 + 2)):
        d.point((bx, by), fill=bolt)


# ────────────────────────── 1. 夜の駅のホーム ──────────────────────────
def station_night():
    rnd = random.Random(1)
    img = Image.new("RGB", (W, H))
    vgrad(img, 0, 240, [(0, "#070C1E"), (0.7, "#14224A"), (1, "#24345F")])
    d = ImageDraw.Draw(img)
    stars(d, rnd, 0, 170, 70)
    skyline(d, rnd, 238, 20, 70, "#0D1430", ["#FFD27A", "#FFE9B0", "#9FD4FF"], lit=0.3)
    # 屋根
    d.rectangle((0, 238, W, 266), fill="#1A1E28")
    d.rectangle((0, 266, W, 268), fill="#3C4352")
    for x in range(8, W, 44):
        d.rectangle((x, 238, x + 3, 266), fill="#262B37")
    # 蛍光灯
    for x in (30, 120, 210):
        d.rectangle((x, 270, x + 30, 271), fill="#EAF4FF")
        d.rectangle((x - 2, 272, x + 32, 272), fill="#5A6680")
    # 屋根の下の奥（向かいのホームと線路の暗がり）
    vgrad(img, 268, 466, [(0, "#0B0F1C"), (0.55, "#121828"), (1, "#1A2030")], steps=6)
    d.rectangle((0, 400, W, 404), fill="#262C3A")  # 向かいのホームの縁
    for x in range(0, W, 9):
        d.rectangle((x, 405, x + 4, 406), fill="#7A6A3A")  # 向かいの点字ブロック
    d.rectangle((0, 440, W, 441), fill="#3A4150")  # 線路
    d.rectangle((0, 452, W, 453), fill="#3A4150")
    for x in range(0, W, 12):
        d.rectangle((x, 442, x + 6, 451), fill="#1F2430")
    # 柱
    for x in (4, 258):
        d.rectangle((x, 268, x + 8, 560), fill="#2A2F3B")
        d.rectangle((x, 268, x + 1, 560), fill="#3C4352")
    # 吊り金具
    for x in (60, 208):
        d.rectangle((x, 268, x + 1, 292), fill="#7A8396")
    board(d, 18, 296, 251, 430, "#2B303C", "#04060C", "#9AA3B5")
    # ホームの床と点字ブロック
    vgrad(img, 470, 600, [(0, "#2B2F38"), (1, "#3D424D")], steps=6)
    d.rectangle((0, 470, W, 471), fill="#555B68")
    for x in range(0, W, 6):
        d.rectangle((x, 478, x + 3, 482), fill="#D9B13B")
    d.rectangle((0, 466, W, 469), fill="#C9CED8")
    # 足元に映る明かり
    for x in (30, 120, 210):
        d.rectangle((x + 6, 520, x + 24, 521), fill="#4A5160")
    return img


# ────────────────────────── 2. 朝の空港 ──────────────────────────
def airport_morning():
    rnd = random.Random(2)
    img = Image.new("RGB", (W, H))
    vgrad(img, 0, 300, [(0, "#3E7CC0"), (0.6, "#8DB8E0"), (1, "#F2D3A2")])
    d = ImageDraw.Draw(img)
    # 雲はアプリが動かして描く（SceneLife.kt）
    # 遠くの山と滑走路
    d.polygon([(0, 270), (60, 250), (120, 262), (190, 244), (270, 266), (270, 300), (0, 300)], fill="#6E86A3")
    d.rectangle((0, 286, W, 300), fill="#7D838C")
    for x in range(0, W, 16):
        d.rectangle((x, 292, x + 8, 292), fill="#E8E8E8")
    # 飛行機（横向きのシルエット）
    ax, ay = 150, 262
    d.rectangle((ax, ay, ax + 70, ay + 9), fill="#F3F5F8")
    d.rectangle((ax + 70, ay + 2, ax + 76, ay + 7), fill="#F3F5F8")
    d.polygon([(ax + 4, ay), (ax + 12, ay - 16), (ax + 18, ay - 16), (ax + 16, ay)], fill="#2F6DB3")
    d.polygon([(ax + 30, ay + 6), (ax + 50, ay + 6), (ax + 40, ay + 18), (ax + 34, ay + 18)], fill="#D9DEE6")
    for x in range(ax + 22, ax + 66, 4):
        d.point((x, ay + 3), fill="#2E3640")
    d.rectangle((ax + 2, ay + 9, ax + 70, ay + 10), fill="#2F6DB3")
    # 窓の枠
    for x in range(0, W, 54):
        d.rectangle((x, 0, x + 3, 300), fill="#2E3640")
    for y in (100, 200):
        d.rectangle((0, y, W, y + 3), fill="#2E3640")
    d.rectangle((0, 300, W, 306), fill="#2E3640")
    # 室内の壁
    vgrad(img, 306, 540, [(0, "#C9CED6"), (1, "#DDE1E7")], steps=5)
    d.rectangle((0, 306, W, 307), fill="#9AA1AC")
    board(d, 14, 340, 255, 512, "#1E232B", "#05080E", "#8E97A6")
    # 取り付け金具
    for x in (60, 208):
        d.rectangle((x, 308, x + 2, 335), fill="#5B636F")
    # 床
    vgrad(img, 540, 600, [(0, "#9EA4AE"), (1, "#C3C8D0")], steps=5)
    d.rectangle((0, 540, W, 541), fill="#7C838F")
    for x in range(0, W, 30):
        d.rectangle((x, 541, x, 600), fill="#B0B6BF")
    d.rectangle((40, 560, 230, 561), fill="#D9DDE3")
    return img


# ────────────────────────── 3. 屋上の電光看板（斜め） ──────────────────────────
ROOF_SLOTS = []  # 屋上のシーンの窓の位置（あとで、見えている窓だけを選んでアプリに渡す）
ROOF_QUAD = [(26, 300), (244, 276), (244, 362), (26, 398)]  # 左上・右上・右下・左下


def rooftop_night():
    rnd = random.Random(3)
    img = Image.new("RGB", (W, H))
    vgrad(img, 0, 420, [(0, "#0C0820"), (0.6, "#2A1748"), (1, "#5A2A5E")])
    d = ImageDraw.Draw(img)
    stars(d, rnd, 0, 200, 50, ("#FFFFFF", "#F7C6FF", "#B9A6FF"))
    d.ellipse((200, 40, 222, 62), fill="#FFF3C9")
    d.ellipse((206, 38, 226, 58), fill="#0C0820")  # 三日月
    skyline(d, rnd, 470, 60, 200, "#160F2C", ["#FFD27A", "#7FF5FF", "#FF9FD8"], lit=0.25, slots=ROOF_SLOTS)
    skyline(d, rnd, 520, 40, 140, "#0E0A1E", ["#FFD27A", "#7FF5FF"], lit=0.2, wmin=20, wmax=44, slots=ROOF_SLOTS)
    # 看板の足場
    for x in (50, 130, 220):
        d.rectangle((x, 380, x + 2, 470), fill="#2A2438")
    d.line((50, 440, 222, 410), fill="#2A2438", width=2)
    # 看板（斜めの四角形）：枠＋表示面
    q = ROOF_QUAD
    frame = [(q[0][0] - 5, q[0][1] - 5), (q[1][0] + 5, q[1][1] - 5), (q[2][0] + 5, q[2][1] + 5), (q[3][0] - 5, q[3][1] + 5)]
    d.polygon(frame, fill="#3A3150")
    d.polygon(q, fill="#05040A")
    d.line([frame[0], frame[1]], fill="#5C5078", width=1)
    # 屋上
    d.rectangle((0, 470, W, 600), fill="#120D1F")
    d.rectangle((0, 470, W, 472), fill="#2C2440")
    for x in range(10, W, 40):
        d.rectangle((x, 476, x + 1, 488), fill="#2C2440")
        d.rectangle((x, 476, x + 30, 477), fill="#2C2440")
    # 航空障害灯
    d.rectangle((246, 268, 248, 270), fill="#FF3B3B")
    return img


# ────────────────────────── 4. 夕焼けのバス停 ──────────────────────────
def busstop_sunset():
    rnd = random.Random(4)
    img = Image.new("RGB", (W, H))
    vgrad(img, 0, 400, [(0, "#24245E"), (0.45, "#8A3F72"), (0.75, "#E46F5A"), (1, "#F8B867")])
    d = ImageDraw.Draw(img)
    stars(d, rnd, 0, 90, 18)
    # 夕日（にじみ）
    d.ellipse((96, 214, 148, 266), fill="#F9C77E")
    d.ellipse((102, 220, 142, 260), fill="#FFE6A8")
    # 山と町
    d.polygon([(0, 380), (40, 352), (90, 372), (150, 340), (210, 366), (270, 348), (270, 420), (0, 420)], fill="#5A2E55")
    skyline(d, rnd, 430, 8, 40, "#3A1F3E", ["#FFD27A", "#FFE9B0"], lit=0.25, wmin=10, wmax=24)
    # 道路
    d.rectangle((0, 430, W, 600), fill="#2A2230")
    d.rectangle((0, 430, W, 433), fill="#5B4A60")
    for x in range(0, W, 30):
        d.rectangle((x, 520, x + 14, 522), fill="#E8D9B0")
    # 待合所の屋根と柱
    d.rectangle((24, 278, 248, 288), fill="#3B3045")
    d.rectangle((24, 288, 248, 289), fill="#6A5A78")
    for x in (28, 242):
        d.rectangle((x, 289, x + 3, 470), fill="#3B3045")
    board(d, 44, 300, 228, 372, "#2C2436", "#04050A", "#8E86A0")
    # ベンチ
    d.rectangle((60, 440, 200, 445), fill="#7A4B3A")
    for x in (66, 194):
        d.rectangle((x, 445, x + 3, 462), fill="#4A2E26")
    # バス停の標識
    d.rectangle((252, 300, 254, 470), fill="#8E86A0")
    d.ellipse((242, 284, 264, 306), fill="#2F6DB3")
    d.ellipse((246, 288, 260, 302), fill="#F2F2F2")
    return img


# 名前・絵・掲示板の表示面（四隅）・行数・見出し・数字の色・名前の色・説明
SCENES = [
    ("STATION", "夜の駅のホーム", station_night, [(18, 296), (251, 296), (251, 430), (18, 430)], 2, True,
     "#FFB23E", "#E7EAF2"),
    ("AIRPORT", "朝の空港", airport_morning, [(14, 340), (255, 340), (255, 512), (14, 512)], 3, True,
     "#FFD24A", "#F2F4F8"),
    ("ROOFTOP", "屋上の電光看板（夜）", rooftop_night, ROOF_QUAD, 1, False,
     "#5CE1FF", "#E9F6FF"),
    ("BUSSTOP", "夕焼けのバス停", busstop_sunset, [(44, 300), (228, 300), (228, 372), (44, 372)], 1, False,
     "#7CFF7A", "#E8FFE8"),
]


def kt_color(c):
    return "0xFF" + c.lstrip("#").upper() + ".toInt()"


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    entries = []
    windows = []
    for key, label, fn, quad, rows, header, led, title in SCENES:
        img = fn()
        if key == "ROOFTOP":
            # 看板や屋上に隠れず、窓として見えているものだけ（ビルの色か窓の明かりの色のまま残っている所）
            px = img.load()
            seen = set()
            for wx, wy, col in ROOF_SLOTS:
                if (wx, wy) in seen or not (0 <= wx < W - 1 and 0 <= wy < H - 1):
                    continue
                cols = {px[wx, wy], px[wx + 1, wy], px[wx, wy + 1], px[wx + 1, wy + 1]}
                ok = len(cols) == 1 and (cols.pop() in {hexc(col), hexc("#FFD27A"), hexc("#7FF5FF"), hexc("#FF9FD8")})
                if ok:
                    seen.add((wx, wy))
                    windows.append((wx, wy, 0 if col == "#160F2C" else 1))
            rnd = random.Random(9)
            rnd.shuffle(windows)
            windows = sorted(windows[:90])
        name = f"scene_{key.lower()}"
        img.save(OUT / f"{name}.png", optimize=True)
        pts = ", ".join(f"{x}f, {y}f" for x, y in quad)
        entries.append(
            f'    {key}("{label}", R.drawable.{name}, floatArrayOf({pts}), {rows}, {str(header).lower()}, '
            f"{kt_color(led)}, {kt_color(title)}),")
    KT.write_text(f'''package jp.imamura.nokori

// tools/gen_scenes.py で生成。直接編集しない

/**
 * ライブ壁紙の「シーン」：掲示板のある風景のドット絵（{W}×{H}）と、掲示板の表示面。
 * quad は表示面の四隅（左上・右上・右下・左下の順に x, y。絵の上のドット単位）。
 * rows は表示面に出す行の数、header は「残り時間 時:分」の見出しを出すか。
 * led は残りの数字の色、title は名前の色。
 */
enum class Scene(
    val label: String, val image: Int, val quad: FloatArray, val rows: Int, val header: Boolean,
    val led: Int, val title: Int,
) {{
{chr(10).join(entries)}
    ;

    companion object {{
        /** 屋上のシーンで、明かりがついたり消えたりする窓（x, y, ビルの色 0/1 の順） */
        val ROOFTOP_WINDOWS = intArrayOf({", ".join(f"{x}, {y}, {c}" for x, y, c in windows)})

        /** 絵の大きさ（ドット） */
        const val WIDTH = {W}
        const val HEIGHT = {H}
        fun from(name: String?) = values().firstOrNull {{ it.name == name }}
    }}
}}
''', encoding="utf-8")
    print("scenes:", [s[0] for s in SCENES])
