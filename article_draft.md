# Javaで「同じ瞬間」のイベントが重複排除されない理由：`ZonedDateTime.equals` と `Instant` の契約を最小再現から理解する

外部システムから受信したイベントを重複排除するとき、`2025-01-15T00:00:00Z` と `2025-01-15T09:00:00+09:00[Asia/Tokyo]` を別イベントとして処理してしまうことがあります。両者は**同じ瞬間**を表しているため、イベントの発生時刻を識別子にするなら1件になるのが自然です。しかし、`ZonedDateTime` をそのまま `HashSet` や `LinkedHashSet` に入れる実装では2件残ります。

本稿では、Java 21 と JUnit 5 で再現し、実行時の値から原因を絞り込みます。結論を先に述べると、`ZonedDateTime.equals` と「時間軸上の同じ瞬間」は別の契約です。イベントの同一性を瞬間で定義するなら、**重複判定のキーだけを `Instant` に正規化する**必要があります。

> `ZonedDateTime` の `isEqual` は、ゾーン ID とローカル日時を無視して瞬間だけを比較します。したがって、`equals` と `isEqual` を同じ同値判定だとみなしてはいけません。[1]

## この記事で扱う問題

対象読者は、Java の `java.time` を使って外部イベント、監査ログ、通知、連携メッセージなどを処理している開発者です。前提は JDK 21、Maven、JUnit Jupiter 5.11.4 とします。時刻、ネットワーク、既定タイムゾーンには依存せず、すべての入力を固定の ISO-8601 文字列から作るため、結果は再現可能です。

今回の業務契約は明確です。**同じ瞬間に発生したイベントは、送信元がどのゾーンで表現していても1件として扱う**ことです。表示では最初に受信したゾーン情報を残して構いませんが、重複判定の意味は「同じゾーン表現」ではなく「同じ瞬間」です。

## 既存題材との差分

既存コンテンツを題材選定前に調査したところ、`BigDecimal` の `equals` / `compareTo` による同額比較の不整合と、独自値オブジェクトで `equals` / `hashCode` を実装しないことによる `Set` の重複除去失敗を扱う原稿がありました。そのため、数値のスケールや独自クラスの等価性を再び題材にはしません。

本稿が扱う固有の契約は、**ゾーン付き日時が保持する表示・変換用の状態**と、**イベント同一性に必要な時間軸上の瞬間**の区別です。また、既存の `LocalDateTime` のタイムゾーン解釈に関する題材とも異なります。ここでの入力値はすでに正しい瞬間を表しており、誤りは変換時ではなく、変換後の値をコレクションで比較する境界にあります。

## 期待していた挙動と実際の挙動

次の2つの値は、異なるゾーン表現ですが同じ `Instant` になります。

```java
ZonedDateTime utcEvent = ZonedDateTime.parse("2025-01-15T00:00:00Z");
ZonedDateTime tokyoEvent = ZonedDateTime.parse(
        "2025-01-15T09:00:00+09:00[Asia/Tokyo]");
```

| 比較・観測 | 期待（イベントの同一性） | 実際の値 |
|---|---|---|
| `utcEvent.toInstant()` と `tokyoEvent.toInstant()` | 同じ瞬間 | 両方とも `2025-01-15T00:00:00Z` |
| `utcEvent.isEqual(tokyoEvent)` | `true` | `true` |
| `utcEvent.equals(tokyoEvent)` | 重複判定に使うなら `true` を期待しがち | `false` |
| `new LinkedHashSet<>(List.of(utcEvent, tokyoEvent)).size()` | `1` | `2` |

`ZonedDateTime` は、ローカル日時、ゾーン ID、解決済みオフセットと等価な状態を持ちます。[1] 同一瞬間であることはその一部の見方にすぎません。Java API は `toInstant()` が同じ時間軸上の点を表す `Instant` へ変換すると説明しています。[1]

## 最小再現プロジェクト

プロジェクトは [`/home/ubuntu/java-zoneddatetime-instant-dedup-lab`](.) にあります。主要ファイルは次のとおりです。

```text
src/main/java/jp/tonbiattack/debuglab/EventDeduplicator.java
src/test/java/jp/tonbiattack/debuglab/EventDeduplicatorTest.java
docs/investigation.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
```

不具合状態は `99b0680` コミットに保存しています。次の手順で失敗を再現できます。

```bash
git checkout 99b0680
mvn test
```

失敗するテストは、実装の内部呼び出し回数ではなく、利用者から見た結果件数を検証します。

```java
List<ZonedDateTime> actual = deduplicator.distinctEventTimes(
        List.of(utcEvent, tokyoEvent));

assertEquals(1, actual.size(), "同一瞬間のイベントは1件だけ処理されるべき");
```

実際には次の出力になりました。ここで重要なのは、文字列の見た目ではなく、`toInstant`、`equals`、`isEqual`、集合の件数を一緒に観測したことです。

```text
utc=2025-01-15T00:00Z, zone=Z, instant=2025-01-15T00:00:00Z
tokyo=2025-01-15T09:00+09:00[Asia/Tokyo], zone=Asia/Tokyo, instant=2025-01-15T00:00:00Z
equals=false, isEqual=true, hashCodes=[4147279, 1582996644]
expected: <1> but was: <2>
```

完全な失敗出力は [`evidence/01-broken-test-output.txt`](evidence/01-broken-test-output.txt) に保存しています。

## 調査：何を観測し、どの仮説を除外したか

症状だけを見ると、「パースの失敗」「集合実装の問題」「日時型の比較契約」という複数の可能性があります。次のように、予測可能な最小実験へ分解しました。

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| パース文字列の差が原因 | 同じ `ZonedDateTime` を2回渡しても2件になる | 同一の値を2回集合へ入れる | 通常どおり1件になる | 棄却 |
| `LinkedHashSet` が瞬間を扱えない | `isEqual` が真なら1件になる | `equals`、`isEqual`、集合件数を同時に観測する | `isEqual=true` でも `equals=false`、件数は2 | 棄却 |
| 値同値と瞬間同値を混同している | 同一瞬間でもゾーンが違えば `equals` は偽 | UTC と Asia/Tokyo の固定値を比較する | 予測どおり | 採用 |

原因は3番目です。`Set` は「アプリケーションが同じだと思う値」を自動で理解するのではなく、要素型の `equals` と `hashCode` に従います。`ZonedDateTime` の `isEqual` は瞬間だけの比較ですが、`equals` はその目的に使うメソッドではありません。[1]

なお、`ChronoZonedDateTime` の `timeLineOrder()` も、基礎となる瞬間だけで比較するコンパレータであり、`compareTo` とは別の契約です。[2] つまり、「順序付け」「値としての等価性」「時間軸上の等価性」は、すべて同じ比較ではありません。

## 修正：なぜこの変更で直るのか

不具合状態の実装は、次のように `ZonedDateTime` を直接キーにしていました。

```java
Set<ZonedDateTime> uniqueTimes = new LinkedHashSet<>(receivedTimes);
return List.copyOf(uniqueTimes);
```

最小修正では、返却する `ZonedDateTime` をすべて `Instant` に置換しません。代わりに、**重複判定のキーだけ**を `Instant` にします。

```java
Map<Instant, ZonedDateTime> firstTimeByInstant = new LinkedHashMap<>();
for (ZonedDateTime receivedTime : receivedTimes) {
    firstTimeByInstant.putIfAbsent(receivedTime.toInstant(), receivedTime);
}
return List.copyOf(firstTimeByInstant.values());
```

`Instant` は時間軸上の単一の瞬間をモデル化し、アプリケーションのイベント時刻を記録する用途に使えます。[3] `putIfAbsent` を使うことで、同じ `Instant` の2件目以降だけを除外します。一方、値には先に受信した `ZonedDateTime` を保持するので、後段の表示で元のゾーンを利用できます。

| 選択肢 | 適する条件 | トレードオフ |
|---|---|---|
| `ZonedDateTime` を集合のキーにする | ゾーン・ローカル日時の違いも別の業務値である | 同一瞬間を統合できない |
| `isEqual` で都度探索する | 件数が非常に小さく、比較箇所が限定的である | 探索が線形になり、キーの意図が分散する |
| `Instant` をキーにし、`ZonedDateTime` を値にする | 同一性が瞬間で、表示用ゾーンを残したい | 「最初の表現を残す」方針を明示する必要がある |
| 受信直後から `Instant` だけを保持する | 表示用ゾーンを業務上不要にできる | 元の表現を復元できない |

この修正は万能ではありません。たとえば「同じローカル時刻をどの市場のタイムゾーンで予約したか」が業務上重要なら、ゾーン差を消してはいけません。先に、**そのフィールドの同一性は値全体か、時間軸上の瞬間か**を決めることが必要です。

## 回帰テスト

修正後も、最初に失敗したテストはそのまま残しています。さらに、先に到着した東京表現を返却値として保持するケースと、1秒異なる瞬間を統合しないケースを加えました。

| テスト | 検証する契約 |
|---|---|
| `sameInstantFromDifferentZones_isProcessedOnlyOnce` | UTC と東京の同一瞬間を1件にする。 |
| `firstReceivedZoneIsPreservedForDisplayAfterInstantBasedDeduplication` | 瞬間で重複排除しても、最初の表示用ゾーンを残す。 |
| `differentInstants_areNotMerged` | 1秒でも異なる瞬間は別イベントのままにする。 |

`mvn clean test` の結果は、3テスト成功、失敗0件、エラー0件でした。完全な出力は [`evidence/02-fixed-test-output.txt`](evidence/02-fixed-test-output.txt) にあります。修正済み状態は `6cf552c` コミットとして保存しています。

## まとめ

判断規則は3つです。

1. `ZonedDateTime.equals` が真かどうかと、「同じ瞬間か」は同じ問いではありません。
2. イベント時刻の同一性を時間軸上の瞬間で定義するなら、重複キーを `Instant` に正規化します。
3. 表示にゾーンが必要なら、キーと保持する値を分けます。`Map<Instant, ZonedDateTime>` はその分離をコードに表現できます。

## 参考資料

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html "ZonedDateTime — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/chrono/ChronoZonedDateTime.html "ChronoZonedDateTime — Java SE 21"
[3]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html "Instant — Java SE 21"
