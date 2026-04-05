# CTI Java版
バージョン 2.2.3

Javaを使ってCopper PDFにアクセスするためのプログラムです。
Copper PDF 2.1.0以降が必要です。
使用方法は付属のAPIドキュメント、サンプルプログラム、以下のオンラインマニュアルを参照してください。
http://dl.cssj.jp/docs/copper/3.2/html/3420_ctip2_java.html

## API ドキュメント

- **オンライン (Javadoc)**: https://zamasoftnet.github.io/cti.java/

ソースコードはGitHubで公開しています。
https://github.com/zamasoftnet/cti.java

## インストール

### Maven / Gradle（JitPack 経由）

JitPack リポジトリを追加した上で、`com.github.zamasoftnet:cti.java:v2.2.3` を利用してください。

#### Gradle

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.zamasoftnet:cti.java:v2.2.3'
}
```

#### Maven

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.zamasoftnet</groupId>
  <artifactId>cti.java</artifactId>
  <version>v2.2.3</version>
</dependency>
```

## 付属物

- `cti-driver-2.2.3.jar` -- ドライバ本体（CTIP, REST, CLIが利用可能）
- `cti-driver-min-2.2.3.jar` -- 最小構成のドライバ（REST, CLIは利用不可）
- `apidoc` -- APIドキュメント(Javadoc)
- `lib` -- サンプルのコンパイルに必要なライブラリ
- `examples` -- サンプルプログラム
- `compile-examples.sh` -- サンプルプログラムをコンパイルするスクリプト(Linux)
- `compile-examples.bat` -- サンプルプログラムをコンパイルするスクリプト(Windows)
- `copper` -- コマンドラインcopperプログラム(Linux)
- `copper.bat` -- コマンドラインcopperプログラム(Windows)

## コマンドラインプログラムについて
`copper`, `copper.bat`については、以下のドキュメントを参照して下さい。
http://dl.cssj.jp/docs/copper/3.2/html/2100_tools.html#admin-copper

## サンプルプログラムについて
`javac`コマンドが実行できるようにパスを設定しておいて下さい。
Linuxでは`compile-examples.sh`、Windowsでは`compile-examples.bat`を実行するとコンパイルされます。

サンプルプログラムを実行するには、コンパイル後に以下のコマンドを実行してください。

### Linux
```bash
java -cp cti-driver-2.2.3.jar:classes クラス名
```

### Windows
```cmd
java -cp cti-driver-2.2.3.jar;classes クラス名
```

Servlet/JSPのサンプル実行する場合は、`examples/webapp`をサーブレットコンテナに配備して、以下のアドレスをブラウザで表示してください。
Tomcat 10 では `examples/webapp/WEB-INF/web.xml` を `examples/webapp/WEB-INF/web-jakarta.xml` で置き換えてください。

**(Filterのテスト)**
http://ホスト:ポート/コンテキスト/source.jsp

**(Servlet of the test)**
http://ホスト:ポート/コンテキスト/pdf/source.jsp

## API概要

### 主要なクラス

| クラス/インターフェース | 説明 |
| :--- | :--- |
| `CTIDriverManager` | ドライバの取得、セッションの作成 |
| `CTISession` | 文書変換セッションの管理 |
| `CTIDriver` | サーバー接続ドライバ |
| `Results` | 出力先の抽象化 |
| `SingleResult` | 単一結果の出力 |
| `DirectoryResults` | 複数ファイルの出力 |
| `MessageHandler` | メッセージ受信ハンドラ |
| `ProgressListener` | 進捗リスナー |
| `SourceResolver` | リソース解決 |

### CTISession の主要メソッド

| メソッド | 説明 |
| :--- | :--- |
| `setResults(Results)` | 出力先の設定 |
| `setMessageHandler(MessageHandler)` | メッセージハンドラの設定 |
| `setProgressListener(ProgressListener)` | 進捗リスナーの設定 |
| `setSourceResolver(SourceResolver)` | リソースリゾルバの設定 |
| `property(String, String)` | プロパティの設定 |
| `transcode(MetaSource)` | 変換の実行（ストリーム） |
| `transcode(URI)` | 変換の実行（URI） |
| `setContinuous(boolean)` | 連続モードの設定 |
| `join()` | 結果の結合 |
| `reset()` | セッションのリセット |
| `close()` | セッションのクローズ |

## テストの実行方法

テストにはCopper PDFサーバーへの接続が必要です。

1. `test-config.json` をプロジェクトルートに作成:
```json
{
  "host": "localhost",
  "port": 8099,
  "user": "user",
  "password": "kappa"
}
```

2. Gradle でテストを実行:
```bash
./gradlew test
```

サーバーが起動していない場合、テストは自動的にスキップされます。

## ドキュメント生成

Javadocを生成:
```bash
./gradlew javadoc
```

## ビルド

```bash
./gradlew build
```

## ライセンス

Copyright (c) 2012-2026 座間ソフト

Apache License Version 2.0に基づいてライセンスされます。
あなたがこのファイルを使用するためには、本ライセンスに従わなければなりません。
本ライセンスのコピーは下記の場所から入手できます。

http://www.apache.org/licenses/LICENSE-2.0

適用される法律または書面での同意によって命じられない限り、本ライセンスに基づいて頒布されるソフトウェアは、明示黙示を問わず、いかなる保証も条件もなしに「現状のまま」頒布されます。
本ライセンスでの権利と制限を規定した文言については、本ライセンスを参照してください。 

Copyright (c) 2012-2026 Zamasoft.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## 変更履歴

### v2.2.3 2024-03-27
- サーブレットでContent-Typeを出力する際に、空の charset= パラメータが追加されてしまうバグに対応しました。

### v2.2.2 2024-03-14
- javax.servlet 4 に対応しました。
- jakarta.servlet 5 に対応しました。

### v2.2.1 2023-04-04
- Javaの以下のバグに起因するメモリリークが起こっていたため、回避する措置をしました。
  https://bugs.openjdk.org/browse/JDK-4872014

### v2.2.0 2018-04-26
- Closableを利用可能になる等Java 8のコーディングに対応しました。

### v2.1.5 2017-02-19
- http:プロトコルが使用できないバグを修正しました。

### v2.1.4 2014-04-14
- ctip:プロトコルでタイムアウトを設定できるようになりました。
