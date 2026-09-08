# TLS 段階2 実装・検証記録

2026-09-08 / ユーザー直接依頼 / ローカル開発・ループバック試験。
タスク ID: TECH-20260908-020（共有台帳への反映は未実施）。
対象: `F:\dev\zamasoftnet-public\cti.java`、`main`、開始時 HEAD `2159538`。

## 結果

**段階2を完了。最終 tlsTest は40件成功、失敗0、エラー0、スキップ0、終了コード0。**
2026-09-08 16:54 JST 完了、実行時間5分21秒。段階1で落ちていた4件はすべて成功した。
内訳は段階1の10件、段階2の新規25件、既存 V2SessionResultsTest の5件。
設計書 §3-2 の状態処理表は全11行に試験を対応させ、TLS・平文 v2・平文 v1 のローカル往復を確認した。

最初の実行可能な一巡は30件中29件成功。scripted engine の空入力に関する試験アダプター不整合を修正した後、37件全件成功を確認し、さらに条件を強めた最終40件も全件成功。
control-wait の最終実測は待機364ms・スレッド CPU 増分0ms。サーバー側の遅延350msに対し、待機250ms以上・CPU増分150ms未満を試験成立条件にしている。
設計書 §4 の段階2が対象。SNI・証明書検証の既定・新プロパティ・REST・CLI・利用者説明書は変更していない。

## 本番コードの変更

パスの共通接頭辞は `cti-driver-ctip/src/main/java/jp/cssj/driver/ctip/`。

| ファイル | 変更内容 |
|---|---|
| `v2/TLSSocketChannel.java` | ハンドシェイクを engine の現在状態で駆動し、結果の FINISHED は完了イベントとして記録。共有 `res` を廃止。暗号文・平文バッファを read mode で保持し、underflow のときだけ compact＋追加受信、overflow は拡張。write は平文消費数を返す。未送信暗号文を保持し、通常データ・制御応答・close_notify の wrap を単一の送信経路で直列化。空の平文でも進む `flushOutbound()`、内部平文・未送信出力・必要 I/O 方向の照会を追加。受信と送信は別ロックで、Selector 待ちでは保持しない。接続失敗時の解放、期限付き close、TCP EOF での closeInbound、終了時の待機起床を実装 |
| `common/ChannelIO.java` | チャネルを非ブロッキングへ統一。read/write を先に試し、進まない場合だけ必要方向の Selector で待つ。TLS の残り暗号文も完了条件に含める。待機ごとに Selector を所有して finally で閉じ、並行した受信・送信・abort の待機を分離。close は待機を起こす。v1 用の公開 rwselect は維持し、Selector を遅延初期化 |
| `v2/TLSV2ContentProducer.java` | TLS 接続後の blocking=true を撤去。URI 由来の host、port と接続 timeout を追加 overload へ渡す |
| `v2/V2ContentProducer.java` | 4バイトのヘッダと本文を別バッファに蓄積し、途中受信を pollNext で中断・再開。完成フレームだけを解析し、公開 next は同じ蓄積処理を待機付きで利用。文字列長は符号なし16bit。接続・CTIP 認証失敗時に ChannelIO またはソケットを解放。timeout のクエリ解析で params[0] を反復していた箇所を params[i] に修正 |
| `v2/V2RequestConsumer.java` | DATA を専用 ByteBuffer にコピーして共有 buff の pos を確定し、パケットキューへ移譲。キュー先頭の非ブロッキング送信だけを短いロックで直列化。コールバックと待機はロック外。再入したリソース返信や並行 abort も先頭パケットを完了させてから続く。平文消費と TLS 出力排出の両方を完了条件にする。DATA のコールバックを終えてから後続の EOF 等をキューに置く。abort は受信・コールバックの完了を待たない。close に期限を設け、失敗時も通信を閉じる |
| `v2/V2Session.java` | 完成応答の処理と受信を分離。アップロード中は非ブロッキング poll、通常の next は従来通り待機。リソースコールバック中の再帰的な応答処理を抑止。リソース送信用の入力配列をローカル化し、メイン送信配列を上書きしない。state を可視化し、短い状態更新ロックで close 後に処理完了が state=1 を書き戻す競合を防止 |

## 試験・記録の変更

試験コードは `cti-driver-ctip/src/test/java/jp/cssj/driver/ctip/tls/`。

| ファイル | 変更内容 |
|---|---|
| `cti-driver-ctip/build.gradle` | 段階1の独立 tlsTest を維持。説明の「意図した失敗」を更新し、サーバー不要の既存 V2SessionResultsTest 5件を対象に追加。通常 test の PDF 生成タスクとは接続しない |
| `BoundarySocketChannel.java` | 削除された private res の観測依存を除去。実サーバー engine のデータ・close_notify 生成、TCP EOF、受信呼出回数、task 失敗の注入を追加。試験サーバーの CLOSED を正常に扱う |
| `RecordingEngine.java` | scripted engine も、空の入力に対して BUFFER_UNDERFLOW を返すよう更新。NEED_WRAP の強制と肯定的な成功条件は維持 |
| `TlsProbe.java` | write の戻り値が src.position の変化量に一致することを検査。追加平文を渡さず flushOutbound で暗号文を排出 |
| `TlsRegressionTest.java` | 段階1の6件とアサーションを維持し、説明を更新 |
| `TlsStage2Probe.java`（新規） | 境界制御 transport と実 Socket/SSLSocket を用いた段階2の各プローブ。実ソケット上の0・部分送信、分割応答、リソース返信と同時 abort、終了・タイムアウト等 |
| `TlsStage2Test.java`（新規） | プローブを子 JVM で実行。10秒の親側期限、finally で強制終了と最大5秒の回収確認。無限ループ時も後続試験を止めない |
| `docs/tls-stage2-test-report.md`（新規） | 本記録 |

段階1の `LocalTls.java` と `LocalTlsFixtureTest.java` は変更していない。

## 設計書 §3-2 の対応

| 失敗経路 | 確認する試験 |
|---|---|
| 平文を全消費した後の未送信暗号文 | 段階1の zero / partial / full。平文4096バイトと受信結果の全バイト比較。空の flush だけで排出 |
| 内部平文だけが残り、ソケットが READ 不可 | buffered-socket。1バイト取得後に hasPlaintext=true と実 Selector.selectNow=0 を確認して残り4095バイトを取得 |
| Ticket / KeyUpdate だけ届き、アプリケーションデータは後から | key-update は JSSE 初期化前の Security Property jdk.tls.keyLimits で実 KeyUpdate を誘発し、NEED_WRAP と制御応答を観測。control-wait は実 SSLServerSocket が制御レコードを先に送り、350ms 後にアプリケーションデータを送る。待機時間とスレッド CPU 増分を検査 |
| CTIP ヘッダ・本文のレコード境界跨ぎ | plain-v2 / tls-v2 は応答を3バイトずつ送信。再入試験では RESOURCE_REQUEST のヘッダ・本文を2/4/残りへ分割し、各部分の間も送信を進める |
| DATA 送信中の RESOURCE_REQUEST と同時 abort | plain-reentry-abort / tls-reentry-abort。実ソケットのアプリ write を0/最大127バイトに制限。コールバック時点でキュー先頭 DATA に未送信平文があることを assert。resolver 内で待ちながら別スレッドの abort が受信されることを確認し、その後 START_RESOURCE・リソース DATA・EOF を再入送信。メイン24589バイトとリソース8229バイトがそれぞれ全バイト一致し、パケット数・abort・START_RESOURCE の回数を確認 |
| 正常データ直後の close_notify / 途中 EOF | clean-eof / truncated-eof。既受信の4101バイトを小さい宛先で返し切ってから、-1 と SSLException を区別。close-order は未送信の4096バイトの後にクライアント close_notify が届くことをサーバー engine.isInboundDone で確認 |
| 接続直後 EOF、無応答、task / 認証失敗 | handshake-eof / handshake-timeout / handshake-task / handshake-trust / plain-auth-failure / tls-auth-failure。接続期限または例外で失敗し、下位ソケットを解放。engine-faults は無進捗 wrap、task 例外、close の wrap 例外も確認 |
| 読まない相手への close、二重 close、接続前 close | blocked-close / idle-read-close / preconnect-close。小さな送信・受信バッファで未送信出力を作り、close が期限内に下位ソケットを解放し、読み待ちスレッドが戻ることを確認。終了後 read/write は ClosedChannelException |
| 空・小容量宛先、複数レコード＋端数 | buffers。空宛先の下位 read 呼出増分0、3バイト宛先、TLSヘッダ内の分割と末尾2バイト、実 engine での送受信バッファ拡張、全バイト一致 |
| TLS / 平文 v2 / 平文 v1 の回帰 | tls-v2 / plain-v2、両方式の再入・認証・idle-abort。v2 の4万バイト文字列送信と DATA / EOF / CLOSE。plain-v1 は公開 V1ContentProducer / V1RequestConsumer でヘッダ・プロパティ・MAIN・9000バイトDATA・終了・応答を往復し、legacy rwselect も確認。既存 V2SessionResultsTest 5件は結果・連結・中断の終了処理を確認 |
| ctips + version=1 | reject-v1。Driver / Session / Producer の3入口で拒否し、ループバック待受に接続自体が来ないことを確認 |

idle-abort は完成応答を待つ別スレッドの next と並行して abort を送り、応答待ちのロックに送信が阻まれないことを確認する。

## 実行方法・環境

通常の実行コマンドは段階1と同じ。

```powershell
.\gradlew.bat :cti-driver-ctip:tlsTest --offline --console=plain
```

今回の制限環境では Java の user.home が `C:\Users\CodexSandboxOffline` になり、通常 wrapper は既存の配布キャッシュを発見できずダウンロードで停止した。また、兄弟 zstream のビルドキャッシュと共有依存 jar への書き込みが拒否された。このため既存 Gradle 8.9 本体を使い、実行時 init script でキャッシュ・依存出力・コンパイラの依存 jar 参照先をこのリポジトリの build 配下へ移した。ソースの複製・兄弟リポジトリの編集は行っていない。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:GRADLE_USER_HOME = Join-Path $PWD 'build\tls-gradle-home'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\comra\.gradle\caches'
& 'C:\Users\comra\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat' `
  :cti-driver-ctip:tlsTest --offline --console=plain --no-daemon `
  -I build/tls-isolated.init.gradle --project-cache-dir build/tls-project-cache `
  '-Porg.gradle.java.installations.paths=C:\Users\comra\.gradle\jdks\temurin-8-amd64-windows.2'
```

`build/tls-isolated.init.gradle` と作業用キャッシュは Git 管理外のローカル補助物。通常環境では不要。
Gradle の実行対象は全試行とも `:cti-driver-ctip:tlsTest` のみ。`test`、PDF生成、配布、公開のタスクは実行していない。
JDK は Temurin 21.0.7+6、Gradle 8.9、Windows。依存 zstream のコンパイルは既存の Java 8 toolchain を参照した。

HTML: `cti-driver-ctip/build/reports/tests/tlsTest/index.html`。
JUnit XML: `cti-driver-ctip/build/test-results/tlsTest/`。
最終ログ: `build/tls-stage2-verified.log`。

## 判断・設計からの具体化

- 既存コンストラクタと connect(SocketAddress) は維持。追加経路は connect(remote, host, port, timeout)。host/port は URI から渡すが、createSSLEngine(host, port) や HTTPS endpoint identification の適用は段階4に残す。従来 trust プロパティの意味と既定も維持。
- 仕様に数値指定のない接続期限は、TLS の既定30秒、正の URI timeout 指定時はその値とした。TLS close は1秒、CTIP CLOSE の送信にも1秒。詰まった CTIP close は両段階で最大おおむね2秒となる。タイムアウトは単調時計で判定する。
- Selector は当初の3個共用を引き継がず、待機する呼出ごとに生成・finally で解放する方式を選んだ。読取側が Selector を保持しても abort の書込側は待たずに進められる。公開 rwselect だけは v1 用に遅延作成して保持する。
- ChannelIO の非ブロッキング統一は平文 v1 にも適用した。その結果、既存の「blocking チャネルで rwselect の Selector が null」も共通層で解消した。v1 ソースの既存拒否差分には触れていない。v1 の abort 未対応を今回実装したという意味ではない。
- 完成応答の解析を ByteBuffer に集約した際、MESSAGE / ABORT の文字列長も符号なし16bitに揃え、長さ不足・余剰本文を IOException にした。プロトコルの新設・公開 signature の変更はない。
- コールバック再入時は未完成の先頭 DATA を先に完了し、続くリソース返信を送る。abort も同じキューを進められる。主文書 EOF を先に予約するとリソース返信を追い越すため、DATA の送信とコールバックが終わってから後続制御フレームを予約する。
- TLS のデータレコードが同時に KeyUpdate を要求した場合も、追加のアプリ read/write を待たず、非ブロッキングで制御送信を試す。そこで送信失敗しても復号済み平文を先に返し、次の read で失敗を報告する。
- 段階1の scripted engine は従来「入力があるときだけ unwrap が来る」と仮定していた。新状態処理の空入力 underflow 確認に対して BufferUnderflowException を投げたため、試験側を SSLEngine の契約に合わせた。失敗の無効化・成功条件の反転は行っていない。

## API・Java 8・保持した変更

`options.release=8` のまま本番・試験ソースをコンパイル。追加 API は Java 8 の範囲（ReentrantLock、ConcurrentHashMap.newKeySet、TimeUnit、期限付き Process.waitFor 等）に限定し、JDK 内部 API や NEED_UNWRAP_AGAIN 等の新しい enum 定数は使っていない。
ローカルの従来 jar と変更後 class を javap -protected で比較し、変更6クラスの公開・protected メンバーの削除0を確認（synchronized / volatile の実装修飾は比較から除外）。
クラスの major version は本番18 class・試験27 classすべて52。git diff --check と部門指定 check_text.py も成功。照合結果は `build/tls-stage2-api-check.json`。

開始前の CTIPDriver / V1ContentProducer / V1Session、設計書、段階1報告書は全5ファイルの SHA-256 が一致し、保持を確認した。
段階1の試験コードは新しい内部契約に必要な観測箇所のみ更新し、元の4件を肯定的な成功条件のまま残した。

## 未実施・引継ぎ

設計書 §3-2 の状態処理表は全行をローカル基盤で検証した。今回の対象表で、試験基盤不足により未作成とした行はない。証明書ポリシー表は段階4であり、本番の名前検証・SNI・設定優先順位・REST・CLI の試験を完了したとは扱わない。
Java 8 JVM 上でのクライアント実行、既存 CTIServer の TLS 待受、Copper PDF 実変換、cti.li の本番構成変更は段階5／別工程で未実施。実ソケットの CTIP 試験相手はテスト用のプロトコルサーバーであり、Copper 本体ではない。

旧 `F:\AGENTS\技術\START_HERE.md` は不存だったので、`F:\dev\AGENTS.md` が案内する移転先 `F:\AGENTS\座間ソフト\開発` を参照した。
共有 TASKS.md / HANDOFF.md は書込許可範囲外のため更新していない。本書を作業・引継ぎ記録とする。
取消時は本段階の6本番ファイル・試験変更・本記録だけを対象とし、v1 拒否と段階1の既存差分を保護する。
コミットなし、プッシュなし、デプロイなし、公開なし、リリースなし。作業ツリー複製、stash、checkout、reset、clean は実施していない。
