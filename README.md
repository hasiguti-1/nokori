# 残り時間（Android）

ゲーム発売日・締切・イベント・想定寿命までの残り時間を表示するアプリと、ホーム画面ウィジェット。

- ウィジェットは近い順に3件。「◯日」と、1日未満の「時:分:秒」が秒単位で進む
- 寿命は 生年月日 ＋ 想定寿命。想定寿命は厚生労働省「令和7年簡易生命表」の平均余命（男性・女性）から計算できる
- データは端末内に保存

## 入れ方

1. GitHub の Releases から最新の `nokori.apk` をダウンロード
2. 開いてインストール（初回はブラウザに「不明なアプリのインストール」を許可）
3. ホーム画面を長押し → ウィジェット → 「残り時間」を置く

新しい版は同じ手順で上書きインストールできる（データは残る）。

## 作り方

`main` に push すると GitHub Actions がビルドし、Releases に次の2つを置く。

- `nokori.apk`：スマホに直接入れる用（Obtainium で更新）
- `nokori-play.aab`：Google Play Console に提出する用

Google Play 向けの条件に合わせて、対象は Android 16（API 36）。正確なアラームは
`SCHEDULE_EXACT_ALARM`（利用者が設定画面で許可）を使い、許可がなければ時間枠つきで描き直す。

プライバシーポリシー：`docs/privacy.md`

署名鍵は暗号化して `app/nokori.jks.enc` に置いてある。復号のパスワードは GitHub の
Settings → Secrets and variables → Actions の `NOKORI_KEY_PASSWORD` にだけ登録する（コードには書かない）。
このパスワードをなくすと、同じアプリとして更新できなくなるので、別の安全な場所にも控えておくこと。

## 更新の受け取り（Obtainium）

スマホに Obtainium を入れ、このリポジトリの URL を登録すると、新しい版が出たときに更新できる。

## フォント

数字の表示に DotGothic16（SIL Open Font License 1.1）を同梱。ライセンス全文は `licenses/DotGothic16-OFL.txt`。
