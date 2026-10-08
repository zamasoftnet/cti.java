# cti.java 2.3.4 リリースノート(下書き、2026-10-08)

2.3.3(`5c235bf`、タグ `v2.3.3`)からの変更。公開(`build.gradle` の版の更新・タグの push)はユーザーの指示を待つ。
サーバー側の上限・REST の変更を公開版に入れるかは、copper4 の公開の切り替えと同じ判断
(copper4 の `copperpdf4/docs/NEXT-SESSION.md` §3-1 の 1)。

## ドライバ・CLI

- **CLI**: 変換に失敗したとき `-out` のファイルを残さない(出力は最初の書き込みで作り、失敗したら消す)。サーバーが誤りを
  メッセージで報告済みの変換の失敗は、その 1 行で終了コード 1(スタックトレースを出さない)。それ以外の例外はトレースを残す
  (`347e9dd`。暗号化された EPUB で 0 バイトの PDF とスタックトレースが残っていた)
- **ResourceDirectoryResults**(CLI の `-outdir`): 画像出力の頁ごとの結果(`#1`、`#2`…)を `page-0001.png` などの名前で保存する。
  以前は安全でない URI として拒否していた(`dafe586`)

## サーバー(cti-server-ctip・cti-server-rest)

- **同時変換数の上限**: REST と CTIP のサーバーで、同時に走る変換の数に上限を置く(`ConversionGate`)。空きが無ければ待たずに
  断る(新しい誤り `0x3003` 「サーバーが混んでいる」)。REST では変換中のセッションへの要求は 503 と `0x3017`
  (以前は要求のスレッドを待たせていた)(`3b25f98`。クローラーが数百の非同期 REST 変換を始めてヒープが尽きた事例)
- **REST の非同期変換の失敗**: 失敗しても、最初の誤りより前に閉じた結果は残す(以前は全部消して、`/messages` に並んだ頁が
  `/result` で 404 になっていた)(`7f0d5c0`)
- **CTIP v2**: 応答のパケットを 1 つずつまとめて書く。クライアントの ABORT と変換の DATA が混ざり、クライアントが
  「Trailing bytes in CTIP response」で失敗することがあった(`9a19abe`)

## そのほか

- ソースのコメントを英語に統一し、日本語の Javadoc は訳文(`javadoc/ja.json`)から作る(`7eeb2d5`・`48beeac`)
- ビルド: cti-driver の jar を作る前に zstream の jar を作る、`gradlew` に実行権(`603aa4b`)
- 試験: `TlsStage2Test` の control-wait がときどき落ちたのは試験の相手役の競合だった(`828cdd9`)

## 公開の手順(ユーザーの指示があってから)

1. `build.gradle`・`README.md`・`DRIVERS.md`・`PUBLISHING.md` の 2.3.3 を 2.3.4 に
2. `git tag v2.3.4 && git push origin v2.3.4`(GitHub Actions が Releases・Pages・JitPack 向けの成果物を作る)
