# ZonedDateTime の同一瞬間が重複排除されない最小再現

このプロジェクトは、複数の外部システムから受信したイベント時刻を `ZonedDateTime` のまま `HashSet`／`LinkedHashSet` に入れると、**同一瞬間のイベントが重複排除されない**問題を再現し、最小修正を示す Java 21 のデバッグ教材である。

`2025-01-15T00:00:00Z` と `2025-01-15T09:00:00+09:00[Asia/Tokyo]` は同じ瞬間を表す。しかし、`ZonedDateTime.equals` は「時間軸上の瞬間だけ」を比較する契約ではない。そのため、イベントの同一性を瞬間で定義しているのに `ZonedDateTime` 自体を集合のキーにすると、2 件が残る。

## 前提

| 項目 | 固定値 |
|---|---|
| JDK | 21 |
| Maven | 3.8 以上 |
| テスト | JUnit Jupiter 5.11.4 |

外部サービス、現在時刻、システム既定タイムゾーンには依存しない。テストデータはすべて固定の ISO-8601 文字列である。

## 現在の修正済み状態を検証する

次のコマンドを実行する。

```bash
mvn test
```

全 3 テストが成功する。実行出力では次の事実を確認できる。

| 観測 | 値 |
|---|---:|
| `utcEvent.equals(tokyoEvent)` | `false` |
| `utcEvent.isEqual(tokyoEvent)` | `true` |
| `utcEvent.toInstant()` と `tokyoEvent.toInstant()` | 同じ `2025-01-15T00:00:00Z` |
| 全テスト | 3 件成功 |

成功出力は [`evidence/02-fixed-test-output.txt`](evidence/02-fixed-test-output.txt) に保存している。

## 不具合状態を再現する

不具合を含む初期コミットに切り替え、テストを実行する。

```bash
git checkout 99b0680
mvn test
```

`EventDeduplicatorTest#sameInstantFromDifferentZones_isProcessedOnlyOnce` が次の理由で失敗する。

```text
expected: <1> but was: <2>
```

失敗出力は [`evidence/01-broken-test-output.txt`](evidence/01-broken-test-output.txt) に保存している。確認後に `main` ブランチへ戻す。

```bash
git switch main
```

## 原因と最小修正

不具合状態の `EventDeduplicator` は `ZonedDateTime` をそのまま `LinkedHashSet` のキーにしていた。`ZonedDateTime` はゾーンとローカル日時を保持する値であり、同一瞬間であることだけを `equals` の等価条件にしない。[`isEqual`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html#isEqual(java.time.chrono.ChronoZonedDateTime)) は、ゾーン ID とローカル日時を無視して瞬間だけを比較する。[1]

修正後は、**重複判定のキーだけ**を `toInstant()` で正規化する。返却値は最初に受信した `ZonedDateTime` を保持するため、表示に必要な元のゾーン情報を失わない。

```java
Map<Instant, ZonedDateTime> firstTimeByInstant = new LinkedHashMap<>();
for (ZonedDateTime receivedTime : receivedTimes) {
    firstTimeByInstant.putIfAbsent(receivedTime.toInstant(), receivedTime);
}
return List.copyOf(firstTimeByInstant.values());
```

`Instant` は時間軸上の単一の瞬間を表し、アプリケーションのイベント時刻を記録する用途に利用できる。[2]

## テストが固定する契約

| テスト | 固定する振る舞い |
|---|---|
| `sameInstantFromDifferentZones_isProcessedOnlyOnce` | 同じ瞬間を異なるゾーンで表しても 1 件になる。 |
| `firstReceivedZoneIsPreservedForDisplayAfterInstantBasedDeduplication` | 重複キーを `Instant` に変えても、先に受信した表示用ゾーンは保持する。 |
| `differentInstants_areNotMerged` | 1 秒でも異なる瞬間は統合しない。 |

## 構成

```text
src/main/java/jp/tonbiattack/debuglab/EventDeduplicator.java
src/test/java/jp/tonbiattack/debuglab/EventDeduplicatorTest.java
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
```

## 参考資料

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html "ZonedDateTime — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html "Instant — Java SE 21"
