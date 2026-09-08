# クライアント TLS の是正(設計・第3版)

> 起点: ユーザー指示(2026-09-08)「cti.java でやるべきことを全てやろう」。
> きっかけは cti.li で `ctips://cti.li:8499/` を開通させたときに **TLS 1.3 のハンドシェイクが
> 完了しない**ことを実測したこと。
>
> **2026-09-08 のユーザー判断**: 「ctips はもともとほとんど使われていなかったので、
> 根本解決を優先しよう」。したがって **ctips の既存挙動は互換の制約にしない**。
> 公開 API(クラス・メソッドの signature)は `AGENTS.md` のとおり維持するが、
> `TLSSocketChannel` の内部契約・バッファ設計・並行の作りは自由に変えてよい。
> 再配布は後回し。

## 0. レビューの経緯

codex の読み取り専用レビューを 2 回通した。**2 回とも「実装に進んでよいか: いいえ」**。
POLICY §3 の「2 回 reject で 10 人日級ならユーザーへ」に従って判断を仰ぎ、
上記のとおり「根本解決を優先」と決まった。本版は 2 回分の指摘を全部織り込んである。

### 第1回で直したこと

初版は停止の原因を `peerNetData.clear()` と見ていたが**外れ**。実測(§1-1)で
`unwrap()` 内の無進捗ループと確定した。ほかに「チャネルはブロッキング」という前提の誤り、
`read()` の説明の誤り、IP リテラルで peerHost を渡さない案の誤り、REST 側を CTIP と同一視した誤り。

### 第2回で直したこと

| 指摘 | 本版での対応 |
|---|---|
| 戻り値を平文消費数にしても、**暗号文の送信完了は別の条件**。呼び出し側の修正が必須 | §2-2 に「未送信暗号文の排出」を別の操作として定義 |
| Selector に **TLS 内部データが現れない**。`read` より先に `select` する `ChannelIO:170` が内部データを待ちぼうけにする | §2-3。**内部処理を先に試し、外部 I/O 待ちのときだけ select** |
| 一時的な非ブロッキング化では `ChannelIO.rselector` が null のままで、途中受信が **CPU スピン**になる(`ChannelIO:176`) | §2-1 で **TLS 接続後は非ブロッキングに統一**し、待機を `ChannelIO` に集約 |
| DATA 送信中の RESOURCE_REQUEST が共有 `buff` へ**再入**する(`V2RequestConsumer:172` → `V2Session:286` → `flush()`) | §1-6・§2-4。送信パケットを共有 `buff` から切り離す |
| `FINISHED` は `getHandshakeStatus()` から**返らない**。現在状態は engine、完了イベントは結果から取る | §2-2 で役割を分離 |
| `unwrap` に break を足すだけだと `:182` の分岐で**残りの暗号文を捨てる** | §2-2 に明記 |
| **IP SAN の期待結果が逆**。信頼された証明書の IP SAN が接続先と一致すれば**成功**が正しい | §3 の表を訂正 |
| `TrustSelfSignedStrategy` は `chain.length == 1` の判定で「自己署名だけ」ではない | §2-5 の表現を訂正 |
| §3 の本番 TLS 1.2 固定の撤去が §4 の非目標と**矛盾**する | §3-3 と §4 で工程を分離 |
| **`ctips://…/?version=1` が平文通信へ進む**(重大) | §1-7。**確認済み**。§2-6 で拒否する |
| v1 には既存の Selector 初期化不整合(`V1RequestConsumer:181` が null の `rwselect()`)と `abort()` 未対応がある | §4 で回帰対象に含める |
| `./gradlew test` は兄弟リポジトリへの生成タスクまで走る(`cti-driver-ctip/build.gradle:67`) | §3-1 で独立した Test タスクを作る |
| ハンドシェイク待ちに timeout が効かない(`V2ContentProducer:59` は完了後に渡す) | §2-2 に接続タイムアウトを追加 |

## 1. 欠陥の一覧

### 1-1. TLS 1.3 のハンドシェイクが 100% CPU で無限ループする【実測確定】

再現: `copper -s ctips://<TLS 1.3 を話す口>/ -u … -pw … -sv`。20 秒後の `jstack`:

```
"main" ... runnable   cpu=1117796.88ms elapsed=1131.22s
  at sun.security.ssl.SSLEngineImpl.unwrap(java.base@21.0.7/SSLEngineImpl.java:506)
  at jp.cssj.driver.ctip.v2.TLSSocketChannel.unwrap(TLSSocketChannel.java:125)
  - locked <0x000000040e0008c0> (a jp.cssj.driver.ctip.v2.TLSSocketChannel)
  at jp.cssj.driver.ctip.v2.TLSSocketChannel.connect(TLSSocketChannel.java:84)
```

**CPU 時間が経過時間の約 99%**。読み待ちではなくスピン。場所は `unwrap()` の
`while (b.hasRemaining())`(:124)で、`NEED_WRAP` でも `break LOOP` しないため入力位置が進まない。

`-Djdk.tls.client.protocols=TLSv1.2` を付けると同じ相手へ完走する。
サーバー側は正しい(`openssl s_client` は SNI 有無どちらでも完走)。

**engine の内部状態(`HandshakeStatus`・消費/生成バイト数)は jstack に出ない。**
「TLS 1.3 の互換 CCS 送信要求で `OK/NEED_WRAP/0/0` を返す」という説明はコードと整合する有力な
仮説であり、§3-1 の scripted transport で `SSLEngineResult` を記録して確定させる。

到達しないだけで併存する欠陥: `connect()` の `NEED_UNWRAP` が毎回 `peerNetData.clear()`(:80)、
`NEED_UNWRAP` 内で無条件 `write()`(:85)、状態を `this.res` からしか取らない(:109)、
`Thread.sleep(30)` のビジーウェイト(:79)。

### 1-2. アップロードで暗号文を落とす

`V2RequestConsumer.flush()`(:151)は、送信ループの完了を **`src.remaining() <= 0`**(:174, :193)で
判断する。一方 `TLSSocketChannel.write()`(:157)は:

- `wrap()` の結果を `channel.write(this.netData)` で **1 回しか送らない**
- 次回の先頭で `netData.clear()` するので、**未送信の暗号文が消える**
- 戻り値が平文の消費数ではなく暗号文の送信数で、`WritableByteChannel` の契約に反する

`wrap()` が平文を全部消費すると `src.remaining()` は 0 になり、暗号文が残っていてもループを抜ける。
**平文 CTIP では起きない**(生の `SocketChannel.write()` が部分書き込みを正しく返し、
呼び出し側のループが再試行するため)。**TLS 経路限定の欠陥**。

`ChannelIO.writeAll()`(:197)も同じ前提で書かれている。

### 1-3. Selector の readiness が TLS を反映しない

`TLSSocketChannel.register()`(:238)は下位の `SocketChannel` へ委譲する。したがって
`key.isReadable()` は**生の TCP** の状態でしかない。復号済みの平文が `peerAppData` に残っていても、
受信済みの完全な TLS レコードが `peerNetData` にあっても読めると分からない。
`V2RequestConsumer:171`/`190` は `key.isReadable()` のときしか応答処理をしないので取りこぼす。

`ChannelIO:170` が「read より先に select」する作りも、内部データを待ちぼうけにする。

さらに、一時的な非ブロッキング化(`V2RequestConsumer:162`)では `ChannelIO` の 3 つの Selector が
**null のまま**なので、`buildNext()` → `V2ContentProducer:130` → `readAll()` に入って
`ChannelIO:176` を 0 バイトのまま繰り返す。**CPU スピンになる。**

### 1-4. 終了処理

`implCloseChannel()`(:247):

- `wrap()` の後に **`flip()` が無い**まま `netData.hasRemaining()` で書き込む
- `wrap()` が `CLOSED` を返すと、生成済みの **close_notify を送る前に `break LOOP`** する
- `closeInbound()` が無く、`:194` の TCP EOF を正常な TLS 終了と区別しない
- `synchronized` が `read`/`write` と同じモニター

### 1-5. 並行

`read()`・`write()`・`implCloseChannel()` が同じモニターを取るため、ブロッキング `read()` 中は
別スレッドの送信・close が待たされる。`cti-if/.../CTISession.java:200` は
**別スレッドからの `abort()`** を要求している。`connect()` は同期されておらず、
接続失敗時にソケットを解放しない。`this.res`(:27)がインスタンス共有で、別スレッドに上書きされる。

### 1-6. 送信パケットの再入

`V2RequestConsumer:172` が受けた RESOURCE_REQUEST は `V2Session:286` → `startResource()` →
`flush()` へ**再入**する。外側の `pos` が 0 になるのは送受信ループを抜けた後(:199)なので、
同じ DATA パケットの再送や共有 `buff` の競合が起こりうる。
内側の `flush()` は非ブロッキング分岐へ進み、`:186` の `rwselect()` が null を参照する経路もある。
別スレッドの `abort()` も `V2Session:307` から同じ `flush()` に入る。

### 1-7. `ctips` + `version=1` が平文になる【確認済み・重大】

- `CTIPDriver.java:45` — クエリに `version=1` があると **scheme を見ずに** `V1Session` を返す
- `V1ContentProducer.java:82` — 素の `SocketChannel` で接続し、`:88` で平文ヘッダを送る
- `V1Session.java:66` — `ctip.auth` に **`PLAIN:<利用者>\n<パスワード>`** をそのまま送る

`ctips` を指定したのに黙って平文へ落ち、**資格情報が平文で流れる**。
`V1Session` を直接生成する利用者もありうるので、Driver 入口だけの拒否では足りない。

### 1-8. 証明書の検証(CTIP と REST で別物)

**CTIP** `TLSSocketChannel.java:42`: `jp.cssj.driver.tls.trust` の既定が `"true"` で、
そのとき**何でも通す `X509TrustManager`** を入れる。名前と意味が反転。
`createSSLEngine()` を引数無しで呼ぶので **SNI を送らない**。
`setEndpointIdentificationAlgorithm` も無いので**ホスト名を検証しない**。
`cti-cli` の `-t` も `true` を立てる(`Main.java:288`)。

**REST** `RestSession.java:113`: `TrustSelfSignedStrategy` + `NoopHostnameVerifier`。
前者は **`chain.length == 1` で判定**しており「自己署名そのもの」を検査してはいない
(単一証明書チェーンを信頼扱いにし、それ以外は通常の trust manager へ委ねる)。
さらに `:124` の catch は `IOException` を作るだけで**投げていない**ので、
TLS 初期化に失敗しても構築が続く。

## 2. 変更後の形

### 2-1. 全体方針

**TLS 接続後は下位チャネルを非ブロッキングに統一し、待機を `ChannelIO` に集約する。**
一時的にモードを切り替える現在の作り(`V2RequestConsumer:162`〜`182`)をやめる。
`TLSSocketChannel` は「内部処理を進められるだけ進め、外部 I/O が要るときだけその方向を申告する」
という契約にし、`ChannelIO` が **内部処理を先に試し、進まないときだけ select** する。

### 2-2. `TLSSocketChannel`

1. **ハンドシェイクを進捗保証のあるループへ**。
   - 現在状態は毎回 `engine.getHandshakeStatus()`。**完了イベント(`FINISHED`)は
     `SSLEngineResult` からしか来ない**ので、役割を分けて扱う
   - `NEED_UNWRAP`: `peerNetData` を `compact()` で持ち越す。`BUFFER_UNDERFLOW` のときだけ追加受信
   - `unwrap` の内側ループは `NEED_WRAP` / `NEED_TASK` / 完了で**必ず抜ける**。
     抜けたあと `:182` 相当の分岐で**残った暗号文を捨てない**
   - `NEED_WRAP`: 空の平文を wrap し、生成した暗号文を排出キューへ積む
   - `NEED_TASK`: `getDelegatedTask()` を空になるまで実行
   - **無進捗の扱い**: 状態変化・task 完了・バッファ拡張は進捗とみなす。
     どれも起きず外部 I/O 待ちなら待機、解消不能な同一状態なら例外
   - `BUFFER_OVERFLOW` は `getApplicationBufferSize()` / `getPacketBufferSize()` で拡張
   - **接続タイムアウト**を持つ(`V2ContentProducer:59` は完了後に `ChannelIO` へ渡すので効かない)。
     失敗時はソケットを解放する
2. **`write(src)` の契約**:
   - 戻り値は**その呼び出しで消費した平文の量**(`src.position()` の変化量と一致)
   - 未送信の暗号文を保持し、次の機会に先に吐く。`netData.clear()` で捨てない
   - **平文が空でも暗号文を進められる別操作**(`flushOutbound()` 相当)を公開する。
     通常データ・KeyUpdate 応答・close_notify を**生成順に**送る
3. **`read(dst)`**: 平文を返せる時点で返す。KeyUpdate の `NEED_WRAP`/`NEED_TASK` を処理する。
   `CLOSED` を EOF として返す。宛先が空なら受信しない。バッファ不足は拡張する
4. **状態の公開**: 「内部に返せる平文があるか」「未送信の暗号文があるか」「次に必要な I/O 方向」を
   問い合わせられるようにする。`ChannelIO` と `V2RequestConsumer` がこれを見る
5. **終了**: `closeOutbound()` の後、close_notify を**期限付きで**排出する。
   期限切れ・失敗・強制終了では下位チャネルを必ず閉じる。`closeInbound()` を呼び、
   TCP EOF と TLS の正常終了を区別する。接続前(`engine == null`)や wrap が例外を投げた場合も解放する
6. **並行**: `this.res` をローカル変数化する。**受信側(unwrap)と送信側(wrap)でロックを分ける**。
   `SSLEngine` は wrap と unwrap の同時実行を認めているが、**wrap 同士の順序保証は呼び出し側の責任**
   なので、**すべての wrap を単一の送信経路に通す**。相手方向の完了を待つ間ロックを保持しない。
   close は停止状態を公開し、Selector を起こし、下位チャネルを強制解放できるようにする
7. **SNI とホスト名検証**: `createSSLEngine(host, port)`。**IP リテラルでも peerHost を渡す**
   (IP SAN の検証に要る)。SNI へ IP を載せるかは JSSE の判断に任せる。ホスト名は
   **URI 由来のものを維持**し、接続先 IP や逆引き名へ置き換えない(`V2ContentProducer:41` から渡す)。
   `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` を立てる。
   検証を無効にしているときは立てない
8. **公開 API**: 既存のコンストラクタと `connect(SocketAddress)` は残す。ホスト情報は追加の経路で渡す
9. **Java 8**: 追加するコードと試験も Java 8 で使える API に限る(`build.gradle:153` の `options.release=8`)

### 2-3. `ChannelIO`

- **内部処理を先行させる待機**にする(`:170` の「read より先に select」を改める)。
  TLS の内部に返せる平文があるならそれを返し、外部 I/O が要るときだけ select する
- 未送信暗号文の排出を待機ループに組み込む
- タイムアウトと close で確実に起きる

### 2-4. `V2RequestConsumer` / `V2ContentProducer` / `V2Session`

- **送信パケットを共有 `buff` から切り離す**。パケット単位で順序と所有権を確定してから
  コールバック(`session.buildNext()`)を実行し、再入で同じ DATA を再送しないようにする。
  `V2RequestConsumer` 全体を長時間 `synchronized` にはしない(`abort()` が待たされるため)
- 送信ループの完了条件を「対象平文の処理完了 **かつ** 対応する暗号文の排出完了」にする
- WRITE 登録は必要なときだけにする
- アップロード中の応答を**途中まで読んで中断・再開**できる内部処理を持つ。
  公開の `next()` は維持できる
- `V2Session` は完成した応答パケットだけを処理する。リソース要求・`abort`・`close` を
  送受信処理へ安全に接続する
- `TLSV2ContentProducer` はホスト情報を伝え、接続失敗時に解放し、`ChannelIO` とモード管理を一致させる

### 2-5. 検証の既定を安全側へ

- 新プロパティ **`jp.cssj.driver.tls.insecure`**(既定 `false` = 検証する)
- **優先順位**: 新名が指定されていればそれだけを見る。**未指定のときだけ**旧名
  `jp.cssj.driver.tls.trust` を参照し、`true` なら `insecure=true` として扱う。
  `insecure=false` が旧名 `true` に負けることはない
- 旧名を使ったときは **JVM ごとに 1 回だけ** `WARNING`。CTIP と REST の両方を使っても 1 回
- `cti-cli`: `-t` / `--trust` を残し(意味は「検証しない」)、`--insecure` を別名で足す。
  説明文を「サーバー証明書を検証しない(危険。自己署名の試験用)」にし、設定先を新名へ(`Main.java:287`)
- **REST**: 既定は標準の trust manager + 標準のホスト名検証。`insecure=true` のときだけ現行の
  `TrustSelfSignedStrategy` + `NoopHostnameVerifier`。**CTIP の `insecure=true`(何でも通す)と
  REST の `insecure=true`(単一証明書チェーンだけ信頼扱い)は意味が違う**ことを説明書に明記する
- `RestSession.java:124` の握り潰しを直し、TLS 初期化の失敗を投げる

### 2-6. v1 の扱い

**`ctips` で v1 は使えないようにする。** TLS を v1 へ実装はしない(v1 自体が旧形式で、
ctips がほとんど使われていない以上、投資に見合わない)。

- `CTIPDriver.getSession()` で scheme が `ctips` かつ `version=1` なら**接続前に例外**
- `V1Session` / `V1ContentProducer` にも防御を置き、`ctips` の URI を受けたら**接続前に拒否**する
  (Driver を経由しない利用者がいるため)
- **平文への暗黙の移行を残さない**

### 2-7. 後方互換

- **公開 API**: 変えない
- **プロトコル**: 変えない
- **既定の挙動**: 変える。自己署名や名前の合わない証明書のサーバーへ `ctips`/`https` で
  繋いでいた利用者は失敗する。意図したセキュリティ変更として変更履歴・`README`・`DRIVERS.md` に明記。
  回避は `-Djp.cssj.driver.tls.insecure=true`
- **ctips の内部挙動**: 制約にしない(ユーザー判断、2026-09-08)
- `cti-ant` の `TranscodeTask.java:44` は URI を差し替えられるので利用者設定に影響する。
  Ant の変換用 `<property>` と JVM システムプロパティは別物である点、独自 CA の登録方法
  (`javax.net.ssl.trustStore`)を説明に足す

## 3. 検証

**既存試験は TLS 経路を 1 本も通らない。**`V2SessionResultsTest.java:85` は producer/request を
偽物へ差し替え、`CTIServerTest.java:146` は平文ポート。REST の TLS 試験は無い。

### 3-1. 試験基盤(段階 1)

`cti-driver-ctip/build.gradle:67` の `test` は兄弟リポジトリへの生成タスクまで走らせるので、
**独立した Test タスク**(例 `tlsTest`)を作り、実行コマンドを文書化する。

段階 1 の実装済みコマンド（リポジトリルート、TLS 1.3 対応の JDK 21 で実行）:

```powershell
.\gradlew.bat :cti-driver-ctip:tlsTest --offline --console=plain
```

Linux/macOS では `./gradlew :cti-driver-ctip:tlsTest --offline --console=plain`。
`--offline` は依存キャッシュが揃っている場合に使う。証明書は実行中に同じ JDK の
`keytool` で生成するため JDK が必要。TLS 試験自体の接続先はループバックだけで、
Copper PDF や外部サーバーは不要。

- 対象は `jp.cssj.driver.ctip.tls` パッケージのみ。既存 `test` や PDF 生成タスクに依存しない。
  既存 `test` からはこのパッケージを除外する。
- **段階 1 では終了コード 1 が期待結果**。`stage2Target_*` の 4 件（実 TLS 1.3、
  scripted NEED_WRAP、下位 write=0、部分 write）が現行コードで落ち、段階 2 で通るべき試験。
  `@Ignore` / `@Disabled` / 失敗を成功へ反転する設定は使わない。
- `SocketChannel` の試験用サブクラスへ実サーバー `SSLEngine` を接続し、read 単位の結合・分割と
  write 上限を制御する。試験内の reflection で本番が生成した engine を記録用ラッパーに包む。
  本番ソースの変更は不要。scripted 試験だけはそのラッパーで遷移を強制する。
- 本番チャネルを呼ぶ各プローブは子 JVM に隔離し、10 秒の期限後に `destroyForcibly()`、
  最大 5 秒の `waitFor()` と `isAlive()==false` の確認を行う。割り込みや本番の close には依存しない。
- `LocalTls.client(boolean insecure)` で証明書検証あり／なしの両方を選べる。
  現行本番は新名 `jp.cssj.driver.tls.insecure` をまだ解釈しないため、本番プローブでは
  新名と旧名 `jp.cssj.driver.tls.trust` の両方を `true` にして欠陥を切り分ける。
  新名の優先順位や既定反転の試験は段階 4。
- 結果は `cti-driver-ctip/build/reports/tests/tlsTest/index.html` と
  `cti-driver-ctip/build/test-results/tlsTest/`。段階 1 の実測・制限・引継ぎは
  [tls-stage1-test-report.md](tls-stage1-test-report.md) を参照。

2 層に分ける。

1. **境界を制御する回帰試験**: 本番と同じ TLS 状態処理に**試験用 transport** を差し込む。
   サーバー側の実 `SSLEngine` が生成した ServerHello と後続レコードを集め、
   クライアント側の**1 回の read** として渡す。ServerHello 処理後に入力が残ることを assert し、
   返る `SSLEngineResult`(`HandshakeStatus`・消費数・生成数)を記録する。
   読み込み分割、下位 write の 0 / 部分書き込みも同じ transport で強制する。
   狙った `NEED_WRAP/0/0` が実 JSSE で出ない JDK があっても、**scripted な SSLEngine で
   その遷移を強制**してループ制御の回帰を保証する(実 JSSE との互換試験とは分ける)
2. **実ソケットの統合試験**: `SSLServerSocket` か既存の `CTIServer` の TLS 待受を使い、
   実際の Selector・終了・証明書・CTIP 処理を確認する

### 3-2. 通すべき失敗経路

| 失敗経路 | 合格条件 |
|---|---|
| 平文を全消費した後、暗号文が未送信 | 後続のアプリ write が無くても排出され、受信側でバイト一致 |
| 復号済みデータが残り、ソケットは READ 不可 | select で待たずに残りを取得できる |
| Ticket / KeyUpdate だけ届き、CTIP データは後から | CPU スピンせず待機し、必要な制御送信を行う |
| CTIP ヘッダ・本文が TLS レコード境界をまたぐ | 部分受信中も必要な送信を進められる |
| DATA 送信中の RESOURCE_REQUEST、同時 `abort` | 重複・欠落・パケット混在・再入例外がない |
| 正常データの直後に close_notify / 途中 EOF | 既に得た平文を返し、正常終了と切断例外を区別 |
| 接続直後の EOF、無応答、task / 認証失敗 | 定めた期限・例外で終了し、ソケットと Selector を解放 |
| 相手が読まない状態で close、二重 close、接続前 close | 無期限に止まらず、終了後の I/O を拒否 |
| 空の宛先、小さい宛先、複数レコード + 端数 | 不要な read、平文消失、暗号文消失がない |
| TLS / 平文 v2 / 平文 v1 | 共通 `ChannelIO` 変更による回帰がない |
| `ctips` + `version=1` | **接続前に拒否**され、平文パケットが 1 バイトも出ない |

**証明書の期待結果**(第2回の指摘で訂正):

| 条件 | 既定(`insecure=false`) | `insecure=true` |
|---|---|---|
| 信頼された CA・名前一致 | 成功 | 成功 |
| 未信頼 CA | 失敗 | CTIP は成功。REST は**単一証明書チェーンなら成功、それ以外は失敗** |
| 期限切れ | 失敗 | 同上 |
| 名前不一致 | 失敗 | 成功 |
| **IP 接続で、信頼された証明書の IP SAN が一致** | **成功** | 成功 |
| IP 接続で IP SAN 不一致、または DNS SAN しかない | 失敗 | 成功 |

DNS 名・IPv4・IPv6 を分けて試験する。

KeyUpdate は JDK 21 で `jdk.tls.keyLimits` を小さくして誘発する。これは **Security Property** なので
`-D` ではなく JSSE 初期化前に設定する。実際に KeyUpdate が起きたことも確認する。

### 3-3. 実機(cti.li)

**本番の TLS 1.2 固定の撤去は別工程。**まず隔離環境(ローカルに TLS を有効にした `copperd`、
または `SSLServerSocket`)で TLS 1.3 の実変換を通す。そのうえで:

- `rev-proxy/routes-itachi.yml` の `tls.options.copper-ctips-tls12` を外し、JVM 既定(TLS 1.3)で
  `ctips://cti.li:8499/` が通ること。**外す前に戻せる状態にしておく**
- `-sv` は短経路なので、**アップロードを伴う実変換**(`-in`/`-out`、数 MB とクライアント提供リソース)も通す
- 無進捗ループに戻っていないこと。累積 CPU の比較だけでなく、**停止区間の CPU 増分・処理期限・
  進捗回数**で判定する。試験プロセスは期限付きで強制終了できるようにする
- `-Djavax.net.debug=ssl:handshake` の ClientHello に `server_name` 拡張が載ること
- REST(`https://cti.li/rest/`)が既定で通ること

### 3-4. 実行環境

Java 8 実行環境での接続試験(コンパイル互換と実行時互換は別)。JDK 21 で TLS 1.3。
既存の `CTIServer` TLS 待受(`CTIServer.java:273`)の回帰。

## 4. 段階分け

| 段階 | 変更単位 | 次へ進む条件 |
|---|---|---|
| 1 | 独立試験タスク、境界制御 transport、ローカル TLS 試験基盤 | **現行コードで §1-1 と §1-2 を検出でき**、失敗しても試験プロセスを回収できる |
| 2 | TLS 状態処理 + `ChannelIO` + v2 呼び出し側 + 終了・並行を**一つの論理的変更**として | §3-2 の全行が通る。平文 v2 も回帰なし |
| 3 | v1 の `ctips` 拒否。既存の v1 不具合(`V1RequestConsumer:181` の null `rwselect()`、`V1Session:260` の `abort()` 未対応)を回帰対象に | `ctips` + `version=1` で平文が 1 バイトも出ない |
| 4 | SNI・証明書検証・既定反転・REST の例外処理・CLI・説明書 | §3-2 の証明書表、新旧設定・CLI の競合、警告回数、独自 CA 設定 |
| 5 | 実行環境・統合 | Java 8 実行、JDK 21 で TLS 1.3、既存 `CTIServer` TLS 待受、実変換 + 提供リソース |

**段階 2 の内部をレビュー用に分けてよいが、「ハンドシェイクだけ直った版」を送受信修正の完了として
扱わない。** 段階 1 と 3 は独立しているので並行できる。

## 5. やらないこと

- CTIP プロトコル自体の変更
- v1 への TLS 対応(§2-6 で拒否する)
- **`cti-server-ctip` の既存 TLS 待受(`CTIServer.java:273`)の変更**。JKS と `SSLContext` を使う
  実装で自前の `SSLEngine` ループではないため今回のバッファ不具合は無い。回帰試験の対象には含める
- cti.li のサーバー構成の変更(§3-3 の TLS 1.2 固定撤去は本設計の完了後の別工程)
- 再配布・公開(ユーザー判断)
