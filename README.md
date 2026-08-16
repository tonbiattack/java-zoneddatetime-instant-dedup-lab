# ZonedDateTime の同一瞬間が重複排除されない最小再現

このプロジェクトは、複数の外部システムから受信したイベント時刻を `ZonedDateTime` のまま `HashSet`／`LinkedHashSet` に入れ、**同一瞬間のイベントが重複排除されない**不具合を再現する。

`2025-01-15T00:00:00Z` と `2025-01-15T09:00:00+09:00[Asia/Tokyo]` は同じ瞬間を表す。しかし、`ZonedDateTime.equals` は時間軸上の瞬間だけを比較する契約ではないため、この実装は 2 件を残してしまう。

## 前提

| 項目 | 固定値 |
|---|---|
| JDK | 21 |
| Maven | 3.8 以上 |
| テスト | JUnit Jupiter 5.11.4 |

外部サービス、現在時刻、システム既定タイムゾーンには依存しない。テストデータはすべて固定の ISO-8601 文字列である。

## 失敗の再現

次のコマンドを実行する。

```bash
mvn test
```

`EventDeduplicatorTest#sameInstantFromDifferentZones_isProcessedOnlyOnce` が失敗する。テスト出力では、次の観測が得られる。

| 観測 | 期待される値 |
|---|---:|
| `utcEvent.equals(tokyoEvent)` | `false` |
| `utcEvent.isEqual(tokyoEvent)` | `true` |
| `distinctEventTimes(...)` の件数 | 本来は `1`、不具合状態では `2` |

## 構成

```text
src/main/java/jp/tonbiattack/debuglab/EventDeduplicator.java
src/test/java/jp/tonbiattack/debuglab/EventDeduplicatorTest.java
```

## 不具合状態の意図

この段階では、`EventDeduplicator` が `ZonedDateTime` をコレクションの重複キーとして直接利用する。修正はまだ適用していない。記事では、観測、原因、最小修正、回帰確認を順に記録する。
