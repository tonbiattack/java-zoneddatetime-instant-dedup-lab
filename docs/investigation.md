# 調査記録：同一瞬間のイベントが2件残る理由

## 症状

UTC で受信した `2025-01-15T00:00:00Z` と、東京で受信した `2025-01-15T09:00:00+09:00[Asia/Tokyo]` は、業務上は同一イベントの発生時刻である。`EventDeduplicator` はこの2件を1件へ統合する契約だが、不具合状態では 2 件を返した。

## 観測結果

不具合状態で `mvn test` を実行した結果を [`../evidence/01-broken-test-output.txt`](../evidence/01-broken-test-output.txt) に保存している。原因の切り分けに使った値は次のとおりである。

| 観測項目 | UTC表現 | 東京表現 | 結果 |
|---|---|---|---|
| `toInstant()` | `2025-01-15T00:00:00Z` | `2025-01-15T00:00:00Z` | 同じ |
| `equals` | — | — | `false` |
| `isEqual` | — | — | `true` |
| `hashCode` | `4147279` | `1582996644` | 異なる |
| `LinkedHashSet<ZonedDateTime>` の結果件数 | — | — | 2 |

## 競合仮説の比較

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| パース文字列の差だけが問題である | 同一の `ZonedDateTime` 値なら集合は 1 件になる | 同じ値を2回渡す | `Set` は通常どおり1件にする | 棄却。集合の基本動作は原因ではない。 |
| `LinkedHashSet` が同一瞬間を識別できない | `isEqual` が真なら `Set` は1件にする | `equals`、`isEqual`、結果件数を同時に出力する | `isEqual=true` でも `equals=false`、結果は2件 | 棄却。`Set` は `equals` / `hashCode` を使うため、瞬間同値を知らない。 |
| `ZonedDateTime` の値同値と業務上の瞬間同値を混同している | 同一瞬間であってもゾーンが違えば `equals` は偽になる | UTC と Asia/Tokyo の固定値を比較する | 予測どおり | 採用。 |

## 原因

`ZonedDateTime` はローカル日時、ゾーン ID、解決済みオフセットを持つ日時型である。公式 API は、`isEqual` がゾーン ID とローカル日時を無視して瞬間だけを比較すると明記しており、`equals` とは別の契約である。[1]

したがって、`LinkedHashSet<ZonedDateTime>` は `ZonedDateTime` の値同値で重複を判定する。業務上の同一性が「同じ瞬間」であるなら、判定キーを `Instant` にする必要がある。`Instant` は時間軸上の単一の点を表す型であり、イベント時刻に適する。[2]

## 最小修正と回帰確認

`ZonedDateTime` 全体を `Instant` に置換せず、`LinkedHashMap<Instant, ZonedDateTime>` のキーだけを `toInstant()` で正規化した。これにより、元のゾーンを表示用に保持したまま、同一瞬間を統合できる。

修正後の `mvn test` は 3 テストすべてに成功した。出力は [`../evidence/02-fixed-test-output.txt`](../evidence/02-fixed-test-output.txt) に保存している。

## 参考資料

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html "ZonedDateTime — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html "Instant — Java SE 21"
