# TLS 段階5 検証記録

2026-09-11。対象: `cti.java` `main`(2.3.0)。設計 `docs/tls-client-hardening-design.md` §3-4 と §4 の段階5。

## 結果

**段階5を完了。** 段階4の記録で未実施だった「実サーバーでの実変換」を、本番の
`ctips://cti.li:8499/`(Traefik が TLS を終端、証明書は cti.li のもの。TLS 1.2 固定は 2026-09-10 に解除済みで TLS 1.3 が折衝される)に対して実行した。

| 項目 | 状態 |
|---|---|
| Java 8 実行 | 段階4で確認済み(class file major 52、`options.release = 8`) |
| JDK 21 で TLS 1.3 | 段階4で確認済み(`TlsStage4Test` の `listen("TLSv1.3")`) |
| CTIServer の TLS 待受 | 段階4で確認済み(`TlsStage2Test`) |
| 実サーバーでの実変換 | **`RealServerIntegrationTest > convertsOverCtipsWithVerificationOn() PASSED`**(検証は既定=有効、`jp.cssj.driver.tls.insecure` 未指定) |

```
./gradlew :cti-driver-ctip:tlsTest --tests '*RealServerIntegrationTest*' \
    -Dcti.integration.uri=ctips://cti.li:8499/ \
    -Dcti.integration.user=… -Dcti.integration.password=…
```

## 直したもの

`tlsTest` タスクが `-Dcti.integration.*` を試験 JVM へ渡していなかったため、上のコマンドどおりに
実行しても `@EnabledIfSystemProperty` が偽で **SKIPPED** になっていた(段階4の記録の手順は
そのままでは動かなかった)。`cti-driver-ctip/build.gradle` で 3 つのプロパティを `systemProperty` へ転送する。

## 申し送り

rev-proxy 側の TLS 1.2 固定は 2026-09-10 にユーザー承認で解除済み(`routes-itachi.yml` の `copper-ctips-tls12` を廃し `minVersion: VersionTLS12` のみ)。
配布済みの旧クライアント(2.2.3 以前)は TLS 1.3 で停止するので、`howto.html` 等の案内を 2.3.0 へ更新する。
