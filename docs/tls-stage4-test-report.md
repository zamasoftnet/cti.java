# TLS 段階4 実装・検証記録

2026-09-09。対象: `F:\dev\zamasoftnet-public\cti.java`、`main`。
設計 `docs/tls-client-hardening-design.md` §2-5 と §4 の段階4。

## 結果

**段階4を完了。`tlsTest` は 51 件成功、失敗0。**うち段階4の新規は 10 件。

段階2はこちらで再実行して確かめた(40 件、失敗0、`--rerun-tasks` で実行を確認)。
試験名が元の欠陥そのものを指している(`realTls13HandshakeMustComplete`、
`partialLowerWriteMustDeliverAllPlaintext`、`reject-v1`)ので、
判別力のある試験と判断した。

## 直したもの

### 検証の既定を安全側へ(§1-8)

以前は**検証しないのが既定**で、しかも設定の名前が意味と反転していた——
`jp.cssj.driver.tls.trust` の既定が `true` で、その `true` が「何でも通す」だった。

新しく `jp.cssj.cti2.TLSPolicy` を置き、CTIP と REST の両方がここを見る。

| | 内容 |
|---|---|
| 新しい名前 | `jp.cssj.driver.tls.insecure`、既定 `false`(=検証する) |
| 優先順位 | 新名が指定されていればそれだけを見る。未指定のときだけ旧名を見る |
| 旧名の警告 | JVM ごとに 1 回だけ(`AtomicBoolean`)。CTIP と REST の両方を使っても 1 回 |

### SNI とホスト名の検証(CTIP)

`createSSLEngine()` を引数無しで呼んでいたので **SNI を送っていなかった**。
`createSSLEngine(host, port)` にし、検証するときは
`setEndpointIdentificationAlgorithm("HTTPS")` と `setServerNames()` を設定する。

**IP リテラルは SNI に載せない**(RFC 6066。載せると JDK が
`IllegalArgumentException` を投げる)。`isIPAddress()` で判定する。

### REST

- 既定を標準の trust manager + 標準のホスト名検証にした。
  `insecure=true` のときだけ `TrustSelfSignedStrategy` + `NoopHostnameVerifier`
- `RestSession.java` の catch が `IOException` を**作るだけで投げていなかった**。
  TLS の初期化に失敗しても構築が続いていたので、投げるようにした

### CLI

`--insecure` を足した。`-t` / `--trust` は同じ意味で残す。説明文を
「サーバー証明書を検証しません(危険。自己署名の試験用)」にし、
設定先を新しい名前にした。

## 試験(`TlsStage4Test`、10 件)

設定の優先順位・既定・警告の回数と、**実際のクライアント経路**
(`TLSSocketChannel.connect`)での接続を測る。生の `SSLSocket` で測っても、
製品の経路がその設定を使っている証拠にはならない。

| 検査 | 合格条件 |
|---|---|
| 新旧の名前が両方指定されたとき | 新名だけが効く(両向きで確認) |
| 何も指定しないとき | 検証する |
| 旧名だけを指定したとき | 「検証しない」で効く |
| 旧名の警告 | 2 回続けて呼んでも 2 回出ない。新しい名前が書かれている |
| 自己署名・既定 | **拒む**(証明書の失敗であることまで見る) |
| 自己署名・`insecure=true` | 通る |
| 自己署名・旧名 `true` | 通る |
| 独自 CA を登録・検証あり | 通る |
| SAN に無い名前・検証あり | **拒む** |
| IP リテラル・検証あり | 通る(SNI に載せていない証拠になる) |

**対照が効いている**: 拒否側と許可側は同じ fixture で設定だけを変えている。
接続先は常に `127.0.0.1` で、**名乗る名前だけ**を変えることで
ホスト名の検証を切り分けている。

## 段階5

| 項目 | 状態 |
|---|---|
| Java 8 実行 | クラスファイルの major version が 52 であることを確認。`options.release = 8` が効いている |
| JDK 21 で TLS 1.3 | `tlsTest` は JDK 21 で走り、`TlsStage4Test` は `listen("TLSv1.3")` を使う |
| CTIServer の TLS 待受 | `TlsStage2Test` の `tls-v2`・`plain-v2`・`plain-v1`・`reject-v1` がプロトコルの往復を覆う |
| 実サーバーでの実変換 | **未実施。** `RealServerIntegrationTest` を用意した(既定では走らない) |

実サーバーの試験は資格情報が要るので、こちらでは走らせていない。

```
./gradlew :cti-driver-ctip:tlsTest --tests '*RealServerIntegrationTest*' \
    -Dcti.integration.uri=ctips://cti.li:8499/ \
    -Dcti.integration.user=... -Dcti.integration.password=...
```

## 申し送り

**rev-proxy の TLS 1.2 固定はまだ外せない。** あの固定は、TLS 1.3 で
無限ループする**既存の配布済みクライアント**を動かすためのもの。
外すと Traefik が TLS 1.3 を選べるようになり、古いクライアントが止まる。
利用者が更新してから外す。

**既定の挙動が変わる。** 自己署名や名前の合わない証明書のサーバーへ
`ctips:` / `https:` で繋いでいた利用者は失敗する。意図した変更で、
`README.md` と `DRIVERS.md` に書いた。回避は `-Djp.cssj.driver.tls.insecure=true`。
