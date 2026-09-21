# CTI ドライバ 公開状況まとめ

Copper PDF 文書変換サーバー向け CTI ドライバの一覧です。

## ドライバ一覧

| 言語 | バージョン | リポジトリ | API ドキュメント |
|------|-----------|-----------|----------------|
| Java | 2.3.2 | [cti.java](https://github.com/zamasoftnet/cti.java) | [Javadoc](https://zamasoftnet.github.io/cti.java/) |
| .NET | 2.2.2 | [cti.net](https://github.com/zamasoftnet/cti.net) | [DocFX](https://zamasoftnet.github.io/cti.net/) |
| Node.js | 1.1.0 | [cti.nodejs](https://github.com/zamasoftnet/cti.nodejs) | [TypeDoc](https://zamasoftnet.github.io/cti.nodejs/) |
| PHP | 2.2.1 | [cti.php](https://github.com/zamasoftnet/cti.php) | [phpDocumentor](https://zamasoftnet.github.io/cti.php/) |
| Ruby | 2.2.1 | [cti.ruby](https://github.com/zamasoftnet/cti.ruby) | [RDoc](https://zamasoftnet.github.io/cti.ruby/) |
| Perl | 2.1.5 | [cti.perl](https://github.com/zamasoftnet/cti.perl) | [pod2html](https://zamasoftnet.github.io/cti.perl/) |
| Python | 3.0.2 | [cti.python](https://github.com/zamasoftnet/cti.python) | [pydoc](https://zamasoftnet.github.io/cti.python/) |

## 配布アーカイブ

GitHub Releases からダウンロードできます。

| 言語 | zip | tar.gz |
|------|-----|--------|
| Java | [cti-java-2.3.2.zip](https://github.com/zamasoftnet/cti.java/releases/latest) | cti-java-2.3.2.tar.gz |
| .NET | [cti-dotnet-2.2.2.zip](https://github.com/zamasoftnet/cti.net/releases/latest) | — |
| Node.js | [cti-nodejs-1.1.0.zip](https://github.com/zamasoftnet/cti.nodejs/releases/latest) | cti-nodejs-1.1.0.tar.gz |
| PHP | [cti-php-2.2.1.zip](https://github.com/zamasoftnet/cti.php/releases/latest) | cti-php-2.2.1.tar.gz |
| Ruby | [cti-ruby-2.2.1.zip](https://github.com/zamasoftnet/cti.ruby/releases/latest) | cti-ruby-2.2.1.tar.gz |
| Perl | [cti-perl-2.1.5.zip](https://github.com/zamasoftnet/cti.perl/releases/latest) | cti-perl-2.1.5.tar.gz |
| Python | [cti-python-3.0.2.zip](https://github.com/zamasoftnet/cti.python/releases/latest) | cti-python-3.0.2.tar.gz |

## インストール方法

| 言語 | パッケージマネージャー | 現在のインストール方法 |
|------|---------------------|----------------------|
| Java | Maven / Gradle（JitPack 経由） | `implementation 'com.github.zamasoftnet:cti.java:v2.3.2'`（要: JitPack リポジトリ追加） |
| .NET | NuGet | `dotnet add package Zamasoft.CTI` |
| Node.js | npm | `npm install https://github.com/zamasoftnet/cti.nodejs.git` |
| PHP | Composer / Packagist | `composer require zamasoft/cti-php` |
| Ruby | RubyGems / Bundler | `gem install copper-cti`（Gemfile なら `gem 'copper-cti', '~> 2.2'`） |
| Perl | cpanm（GitHub archive） | `cpanm https://github.com/zamasoftnet/cti.perl/archive/refs/heads/main.tar.gz` |
| Python | pip | `pip install "git+https://github.com/zamasoftnet/cti.python.git#subdirectory=python3"` |

この一覧の全ドライバは、標準レジストリまたは GitHub 経由で各言語の一般的なパッケージマネージャから利用できます。

## 動作環境

| 言語 | 最低バージョン |
|------|-------------|
| Java | Java 8 以降 |
| .NET | .NET Standard 2.0（.NET Framework 4.6.1+ / .NET Core 2.0+ / .NET 5–9） |
| Node.js | Node.js 14 以降 |
| PHP | PHP 5.6 以降 |
| Ruby | Ruby 1.8.7 以降 |
| Perl | Perl 5.6.1 以降 |
| Python | Python 3 以降 |

## TLS（`ctips:` と `https:`）

**4.0.0 から、サーバー証明書を検証するのが既定です。**

それ以前は検証しないのが既定で、しかも設定の名前が意味と反転していました
（`jp.cssj.driver.tls.trust` の既定が `true` で、その `true` は「何でも通す」
という意味でした）。SNI も送らず、ホスト名も検証していませんでした。

### 設定

| 設定 | 既定 | 意味 |
|---|---|---|
| `jp.cssj.driver.tls.insecure` | `false` | `true` にするとサーバー証明書を検証しません |
| `jp.cssj.driver.tls.trust` | （廃止予定） | 旧名。`true` は「検証しない」の意味 |

新しい名前が指定されていれば**それだけ**を見ます。指定されていないときだけ
旧名を見ます。`insecure=false` が旧名の `true` に負けることはありません。
旧名を使うと JVM ごとに一度だけ警告が出ます。

コマンドラインでは `--insecure`（`-t` / `--trust` も同じ意味で残しています）。

```bash
copper -s ctips://cti.example.jp:8499/ --insecure -in doc.html -out doc.pdf
```

### 自己署名の証明書を使うサーバーへ繋ぐ

**`--insecure` は試験用の逃げ道**です。本番では証明書を信頼する側に登録してください。

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.p12      -Djavax.net.ssl.trustStorePassword=... ...
```

各言語の指定は次のとおりです（2026-09-21 に 7 本すべてで使えるようになりました）。
**いずれも試験用**で、証明書もホスト名も確かめなくなります。

| 言語 | 検証を省く指定 | 認証局を信頼させる指定 |
|------|--------------|--------------------|
| Java | `--insecure` / `jp.cssj.driver.tls.insecure=true` | `javax.net.ssl.trustStore` |
| .NET | URI に `?insecure=1` | URI に `?cafile=証明書ファイル`（2.2.2 以降）または OS のストア |
| Node.js | `get_session()` のオプション `rejectUnauthorized: false` | `NODE_EXTRA_CA_CERTS` |
| PHP | `get_session()` のオプション `'insecure' => true`（2.2.1 以降） | `openssl.cafile` |
| Ruby | `get_session()` のオプション `'insecure' => true`（2.2.1 以降） | `SSL_CERT_FILE` |
| Perl | `get_session()` のオプション `insecure => 1`（2.1.5 以降） | `SSL_CERT_FILE` |
| Python | `get_session()` のオプション `'insecure': True`（3.0.2 以降） | `SSL_CERT_FILE` |

`ctips:`（CTIP）と `https:`（REST）で `insecure=true` の意味は違います。

| | `insecure=true` のとき |
|---|---|
| CTIP | **何でも通します** |
| REST | 証明書チェーンが 1 つだけのものを信頼扱いにし、それ以外は通常の検証へ委ねます。ホスト名も検証しません |

### 動かなくなったら

自己署名の証明書や、名前の合わない証明書のサーバーへ繋いでいた場合は
**失敗するようになります**。意図した変更です。上のいずれかで対処してください。

### `ctips:` で `version=1` は使えません

CTIP v1 は TLS に対応していません。以前は `ctips://…?version=1` を指定すると
**平文で接続していました**。現在は接続前に拒否します。v2（既定）を使ってください。

### Ant タスクを使う場合

`cti-ant` の変換用 `<property>` と JVM のシステムプロパティは別物です。
TLS の設定は JVM 側（`ANT_OPTS` など）で渡してください。

## リリース方法

各ドライバとも `v*` タグを push することで GitHub Actions が自動実行されます。

1. ビルド・テスト
2. API ドキュメント生成
3. GitHub Releases にアーカイブを公開
4. GitHub Pages にドキュメントをデプロイ

詳細は各リポジトリの `PUBLISHING.md` を参照してください。
