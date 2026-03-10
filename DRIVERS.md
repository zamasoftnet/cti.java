# CTI ドライバ 公開状況まとめ

Copper PDF 文書変換サーバー向け CTI ドライバの一覧です。

## ドライバ一覧

| 言語 | バージョン | リポジトリ | API ドキュメント |
|------|-----------|-----------|----------------|
| Java | 2.2.3 | [cti.java](https://github.com/zamasoftnet/cti.java) | [Javadoc](https://zamasoftnet.github.io/cti.java/) |
| .NET | 2.1.0 | [cti.net](https://github.com/zamasoftnet/cti.net) | [DocFX](https://zamasoftnet.github.io/cti.net/) |
| Node.js | 1.0.0 | [cti.nodejs](https://github.com/zamasoftnet/cti.nodejs) | [TypeDoc](https://zamasoftnet.github.io/cti.nodejs/) |
| PHP | 2.1.5 | [cti.php](https://github.com/zamasoftnet/cti.php) | [phpDocumentor](https://zamasoftnet.github.io/cti.php/) |
| Ruby | 2.1.1 | [cti.ruby](https://github.com/zamasoftnet/cti.ruby) | [RDoc](https://zamasoftnet.github.io/cti.ruby/) |
| Perl | 2.1.4 | [cti.perl](https://github.com/zamasoftnet/cti.perl) | [pod2html](https://zamasoftnet.github.io/cti.perl/) |
| Python | 3.0.1 | [cti.python](https://github.com/zamasoftnet/cti.python) | [pydoc](https://zamasoftnet.github.io/cti.python/) |

## 配布アーカイブ

GitHub Releases からダウンロードできます。

| 言語 | zip | tar.gz |
|------|-----|--------|
| Java | [cti-java-2.2.3.zip](https://github.com/zamasoftnet/cti.java/releases/latest) | cti-java-2.2.3.tar.gz |
| .NET | [cti-dotnet-2.1.0.zip](https://github.com/zamasoftnet/cti.net/releases/latest) | — |
| Node.js | [cti-nodejs-1.0.0.zip](https://github.com/zamasoftnet/cti.nodejs/releases/latest) | cti-nodejs-1.0.0.tar.gz |
| PHP | [cti-php-2.1.5.zip](https://github.com/zamasoftnet/cti.php/releases/latest) | cti-php-2.1.5.tar.gz |
| Ruby | [cti-ruby-2.1.1.zip](https://github.com/zamasoftnet/cti.ruby/releases/latest) | cti-ruby-2.1.1.tar.gz |
| Perl | [cti-perl-2.1.4.zip](https://github.com/zamasoftnet/cti.perl/releases/latest) | cti-perl-2.1.4.tar.gz |
| Python | [cti-python-3.0.1.zip](https://github.com/zamasoftnet/cti.python/releases/latest) | cti-python-3.0.1.tar.gz |

## パッケージマネージャー

| 言語 | パッケージマネージャー | インストール方法 | 公開状況 |
|------|---------------------|----------------|---------|
| Java | — | GitHub Releases より JAR を取得 | — |
| .NET | NuGet | `dotnet add package Zamasoft.CTI` | [Zamasoft.CTI](https://www.nuget.org/packages/Zamasoft.CTI/) |
| Node.js | npm | `npm install https://github.com/zamasoftnet/cti.nodejs.git` | 未公開 |
| PHP | Composer / Packagist | `composer require zamasoft/cti-php` | 未公開 |
| Ruby | RubyGems | `gem install copper-cti` | 未公開 |
| Perl | CPAN | `cpanm CTI` | 未公開 |
| Python | pip / PyPI | `pip install "git+https://github.com/zamasoftnet/cti.python.git#subdirectory=python3"` | 未公開 |

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

## リリース方法

各ドライバとも `v*` タグを push することで GitHub Actions が自動実行されます。

1. ビルド・テスト
2. API ドキュメント生成
3. GitHub Releases にアーカイブを公開
4. GitHub Pages にドキュメントをデプロイ

詳細は各リポジトリの `PUBLISHING.md` を参照してください。
