# TLS 段階 1 の試験基盤・実行記録

2026-09-08 / ユーザー直接依頼 / ローカル試験環境。
対象: `F:\dev\zamasoftnet-public\cti.java`、`main`、開始時 HEAD `2159538`。
作業記録 ID: TECH-20260908-018（共有台帳への反映は未実施）。

設計書 §4 の段階 1 を完了。最終実行は **10 件中 6 件成功、意図した 4 件失敗、スキップ 0**。
段階 2 以降の本番修正は行っていない。失敗試験は無効化せず、正常動作を assert する
`stage2Target_*` として残した。したがって現時点の `tlsTest` の終了コードは **1**。

## 変更ファイル

試験コードの共通ディレクトリは `cti-driver-ctip/src/test/java/jp/cssj/driver/ctip/tls/`。

| ファイル | 内容 |
|---|---|
| `cti-driver-ctip/build.gradle` | 独立した `tlsTest` を追加。TLS パッケージだけを実行。既存 `test` から同パッケージを除外。子 JVM 用の実行 classpath を渡す |
| `BoundarySocketChannel.java` | `SocketChannel` の試験用サブクラス。実サーバー engine の応答をまとめ、read 単位の結合・任意位置での分割、write=0/部分書き込みを制御。実送受信バイト数を保存 |
| `RecordingEngine.java` | 実 engine の wrap/unwrap を委譲して Status、HandshakeStatus、bytesConsumed、bytesProduced、残入力数を記録。最大 128 イベントを保持し、最初の 32 件と無進捗 10,000 回の印を出力。scripted 試験用の NEED_WRAP 遷移も提供 |
| `LocalTls.java` | JDK の keytool による一時的な自己署名 RSA 証明書生成、SSLContext、ループバック SSLServerSocket、子プロセスの期限付き回収。検証あり／なしと明示的な証明書信頼を提供 |
| `LocalTlsFixtureTest.java` | 実レコードの結合・分割、insecure 有効時の成功、無効時の未信頼証明書拒否、明示的な信頼＋IP SAN 一致の成功を確認する 4 件 |
| `TlsProbe.java` | 本番 TLSSocketChannel を呼ぶ子 JVM。実 TLS 1.3、scripted 遷移、TLS 1.2 での正常／0／部分送信、実 SSLServerSocket との疎通を実行 |
| `TlsRegressionTest.java` | 子 JVM の起動・10 秒期限・強制終了確認と結果判定。赤い回帰対象 4 件と緑の対照試験 2 件 |
| `docs/tls-client-hardening-design.md` | 既存本文を保持し、§3-1 にコマンド・試験構成・期待される失敗・設定上の注意を追記 |
| `docs/tls-stage1-test-report.md` | 本記録。実測結果、判断理由、制限、引継ぎ |

## transport の選択

本番のコンストラクタ `TLSSocketChannel(SocketChannel)` に試験用サブクラスをそのまま渡した。
下位 write で受け取った暗号文を実サーバー SSLEngine に渡し、サーバーが次の入力待ちに
なるまで生成した複数レコードを集める。`enqueue` は一括、`enqueueSplit` は任意のオフセットで
分割した read を予約する。宛先容量が不足すれば残りは次回に保持する。

ループバック TCP ペアでは write の粒度からクライアントの read の粒度を保証できない。
このため境界の再現には採らず、実ソケットの確認は SSLServerSocket に分離した。
Selector の模倣はしていない。固定 256 KiB の試験バッファは今回のレコードと 4 KiB ペイロード用で、
巨大データ・Selector readiness・終了処理の試験を完了したという意味ではない。

本番は connect 内で engine を生成するので、最初の下位 write 時点で reflection により
private `engine` を委譲ラッパーに包む。既に終わった ClientHello の wrap 結果は private `res` から
採取する。実 TLS 試験の結果は改変しない。scripted 試験だけは入力を 1 バイト消費して NEED_WRAP に
なり、wrap が呼ばれるまで `OK/NEED_WRAP/0/0` を返す。wrap を呼べば FINISHED へ進める。
本番コードへ差し込み口を足す案は「本番を変更しない」という今回の境界に合わないため採らなかった。

## 実行結果

Temurin OpenJDK **21.0.7+6-LTS** / Windows / Gradle 8.9。

```powershell
.\gradlew.bat :cti-driver-ctip:tlsTest --offline --dry-run --console=plain
.\gradlew.bat :cti-driver-ctip:tlsTest --offline --console=plain
```

最終実行は 45 秒、終了コード 1。`test` と他言語 PDF 生成タスクは一度も実行していない。
既存 settings.gradle の composite build により、依存ライブラリ zstream のコンパイル／jar タスクは
グラフに入る。最初の dry-run でも included build 側の jar 2 件は実行され、最終実行では
それらはすべて UP-TO-DATE。独立化の対象である他言語 PDF 生成との依存は無い。

| 試験名（メソッド名） | 最終結果・メッセージ |
|---|---|
| `stage2Target_realTls13HandshakeMustComplete` | **失敗**: `STAGE_2_TARGET TLS 1.3 handshake exceeded 10s: OK/NEED_WRAP/0/0 with input remaining; child reaped=true` |
| `stage2Target_scriptedNeedWrapMustLeaveUnwrapLoop` | **失敗**: `STAGE_2_TARGET scripted NEED_WRAP/0/0 exceeded 10s; child reaped=true` |
| `stage2Target_zeroLowerWriteMustDeliverAllPlaintext` | **失敗**: `STAGE_2_TARGET ciphertext lost after plaintext consumed: expected=4096 received=0 firstLowerWrite=0` |
| `stage2Target_partialLowerWriteMustDeliverAllPlaintext` | **失敗**: 同上、`expected=4096 received=0 firstLowerWrite=7` |
| `control_fullLowerWriteDeliversAllPlaintext` | 成功。暗号文 4,125 バイトから受信平文 4,096 バイトを復号し、内容も一致 |
| `control_productionChannelTalksToLocalSslServerSocket` | 成功。本番 TLSSocketChannel と実 SSLServerSocket の TLS 1.2 往復 |
| `realServerFlightCanBeCoalescedAndSplitInsideRecords` | 成功。実 TLS 1.3 の 3 レコードを一括 read。別途 1 / 3 / 1,292 / 1 バイトに分割して同じ内容を再構成 |
| `insecureTrueAcceptsEphemeralSelfSignedCertificate` | 成功 |
| `insecureFalseRejectsUntrustedEphemeralCertificate` | 成功。証明書検証が原因の SSLException を確認 |
| `insecureFalseWithExplicitTrustAndMatchingIpSanSucceeds` | 成功。HTTPS endpoint identification を有効化して IP SAN を検証 |

§1-1 の実測: サーバーが 127 / 6 / 1,164 バイトのレコードを生成し、1,297 バイトを 1 回の read で
渡した。クライアントは `OK/NEED_TASK/127/0 remaining=1170` の後、
`OK/NEED_WRAP/0/0 remaining=1170` を 10,000 回以上反復した。
ServerHello 消費数＋残入力数＝最初の read バイト数であることも assert している。
設計書 §1-1 の仮説をこの JSSE 上で確認できた。

§1-2 の実測: 正常な TLS 1.2 ハンドシェイク後、平文 4,096 バイトを全消費して暗号文 4,125 バイトを
生成するが、下位 write=0 / 7 では復号済み受信バイト数が 0。部分書き込み時はサーバーが
7 バイトを保持して BUFFER_UNDERFLOW になる。追加のアプリデータは渡さず空 write を最大 10 回
試しても、暗号文を排出せず 0 バイト生成を繰り返して欠落する。正常 write の対照試験では同じ
ペイロードが完全一致するため、証明書やサーバー fixture の不具合による失敗と区別できる。

HTML: `cti-driver-ctip/build/reports/tests/tlsTest/index.html`。
詳細ログと JUnit XML: `cti-driver-ctip/build/test-results/tlsTest/`。

## 期限と回収

本番の無限ループを JUnit スレッドでは実行しない。親は `waitFor(10, SECONDS)` の後、finally で
`destroyForcibly()` と最大 5 秒の `waitFor()` を実行し、`isAlive()==false` を確認してから試験を
失敗させる。Thread.interrupt、本番の synchronized close、shutdown hook に頼らない。
実 TLS 1.3 の起動後計測は **10,003 ms**、scripted は **10,010 ms** で回収完了。
すべての子 JVM について `reaped=true` が記録され、後続試験と Gradle 自体も終了した。
keytool にも 20 秒＋強制終了確認 5 秒の期限を付けている。

## Java 8・判断箇所・引継ぎ

- 既存の JavaCompile `options.release=8` に従い `compileTestJava` が成功。
  加えて、新規 Java 6 ファイルを `javac --release 8 -encoding UTF-8` で独立に再コンパイルして成功。
  classpath は本番コンパイル済みクラスと JUnit API 関連 jar のみ。生成された 9 class はすべて
  major version **52**。Process の期限付き waitFor / destroyForcibly / isAlive は Java 8 API。
  sun.security 等の内部 API、Java 9 以降の API、証明書生成用の追加依存は使用していない。
  直接 javac で共有 Gradle キャッシュを参照した際は終了処理でアクセス拒否が出たため、
  必要な JUnit jar のみ `build/tls-java8-api-check/dependencies` に置いて再確認した。
- **Java 8 JVM 上の実行は未確認**。TLSv1.3 は文字列による指定であり、Java API のコンパイル互換性と
  実行時のプロトコル対応は別。全件実行の対象は JDK 21。Java 8 実行環境の検証は設計の段階 5。
- `insecure` は fixture の boolean として有効／無効を扱える。本番の新名プロパティは段階 4 の
  未実装仕様なので、現行本番プローブでは旧名 trust=true も設定する。検証あり／なしの証明書対照は
  標準 SSLSocket で行う。本番に新名対応や検証の既定変更を入れてはいない。
- TLS 1.2 の最初の server flight が複数レコードになるとは限らず、この JDK では 1 レコードだった。
  初回の基盤試験はこの仮定で失敗したため、結合・分割の対照試験を実 TLS 1.3 の複数レコードで行う
  よう訂正した。設計書の TLS 1.3 による再現方針とは一致する。
- 証明書生成には JDK 同梱 keytool を採用。追加の暗号ライブラリや独自 ASN.1 実装を持ち込まず、
  Java 8 の公開 API だけで一時 PKCS12 を読み込める。一時鍵は JUnit の TempDir 内で生成・削除し、
  リポジトリへ鍵や証明書を追加していない。
- reflection は現行の private フィールド `engine` / `res` に依存する。段階 2 で構造を変えたら
  **試験側の観測アダプターを更新する**。暗号文排出の新 API を設けた場合も空 write の再試行部分を
  その API に合わせる。scripted 試験と実 JSSE 互換試験は混同しない。
- 本番 close の既知欠陥を巻き込まないよう、プローブは下位 wire/socket を直接閉じる。
  TLS close、Selector、CTIP 再入、abort 等は段階 2 以降の対象。
- 既存の CTIPDriver / V1ContentProducer / V1Session の未コミット差分は保持。
  この 3 ファイルと TLSSocketChannel は作業前後の SHA-256 が一致した。
  設計書の既存本文には §3-1 の追記以外の変更を加えていない。
- 旧 `F:\AGENTS\技術\START_HERE.md` は不存。`F:\dev\AGENTS.md` の案内に従って
  移転先 `F:\AGENTS\座間ソフト\開発` の指示を参照した。共有 TASKS.md / HANDOFF.md は
  書き込み許可された workspace 外のため更新していない。この文書を引継ぎ記録とする。
- コミットなし、プッシュなし、デプロイなし、公開なし、リリースなし。作業ツリー複製、stash、
  checkout、reset は未実施。次の作業はユーザーの担当範囲指定に従う段階 2。
- `git diff --check` 成功。部門指定の `check_text.py` を新旧 2 文書に実行し、
  `OK: C1制御文字なし` を確認した。
