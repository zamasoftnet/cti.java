# リリース手順

## リリース方法

`build.gradle` の `version` を更新し、バージョンタグを push します。

```bash
git tag v2.2.3
git push origin v2.2.3
```

GitHub Actions が以下を自動実行します：

1. ビルド・テスト（`./gradlew dist`）
2. Javadoc 生成（`aggregateJavadoc`）
3. GitHub Releases にアーカイブを公開（`cti-java-{VERSION}.zip` / `.tar.gz`）
4. GitHub Pages にドキュメントをデプロイ

## ドキュメント

- **GitHub Pages**: https://zamasoftnet.github.io/cti.java/
- リリース時に自動更新

## ソースコード

- https://github.com/zamasoftnet/cti.java
