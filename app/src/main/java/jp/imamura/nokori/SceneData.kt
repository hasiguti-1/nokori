package jp.imamura.nokori

// tools/gen_scenes.py で生成。直接編集しない

/**
 * ライブ壁紙の「シーン」：掲示板のある風景のドット絵（270×600）と、掲示板の表示面。
 * quad は表示面の四隅（左上・右上・右下・左下の順に x, y。絵の上のドット単位）。
 * rows は表示面に出す行の数、header は「残り時間 時:分」の見出しを出すか。
 * led は残りの数字の色、title は名前の色。
 */
enum class Scene(
    val label: String, val image: Int, val quad: FloatArray, val rows: Int, val header: Boolean,
    val led: Int, val title: Int,
) {
    STATION("夜の駅のホーム", R.drawable.scene_station, floatArrayOf(18f, 296f, 251f, 296f, 251f, 430f, 18f, 430f), 2, true, 0xFFFFB23E.toInt(), 0xFFE7EAF2.toInt()),
    AIRPORT("朝の空港", R.drawable.scene_airport, floatArrayOf(14f, 340f, 255f, 340f, 255f, 512f, 14f, 512f), 3, true, 0xFFFFD24A.toInt(), 0xFFF2F4F8.toInt()),
    ROOFTOP("屋上の電光看板（夜）", R.drawable.scene_rooftop, floatArrayOf(26f, 300f, 244f, 276f, 244f, 362f, 26f, 398f), 1, false, 0xFF5CE1FF.toInt(), 0xFFE9F6FF.toInt()),
    BUSSTOP("夕焼けのバス停", R.drawable.scene_busstop, floatArrayOf(44f, 300f, 228f, 300f, 228f, 372f, 44f, 372f), 1, false, 0xFF7CFF7A.toInt(), 0xFFE8FFE8.toInt()),
    ;

    companion object {
        /** 絵の大きさ（ドット） */
        const val WIDTH = 270
        const val HEIGHT = 600
        fun from(name: String?) = values().firstOrNull { it.name == name }
    }
}
