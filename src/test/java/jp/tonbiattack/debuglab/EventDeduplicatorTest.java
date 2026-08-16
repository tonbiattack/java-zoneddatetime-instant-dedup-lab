package jp.tonbiattack.debuglab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDeduplicatorTest {

    private final EventDeduplicator deduplicator = new EventDeduplicator();

    @Test
    void sameInstantFromDifferentZones_isProcessedOnlyOnce() {
        ZonedDateTime utcEvent = ZonedDateTime.parse("2025-01-15T00:00:00Z");
        ZonedDateTime tokyoEvent = ZonedDateTime.parse("2025-01-15T09:00:00+09:00[Asia/Tokyo]");

        System.out.printf("utc=%s, zone=%s, instant=%s%n", utcEvent, utcEvent.getZone(), utcEvent.toInstant());
        System.out.printf("tokyo=%s, zone=%s, instant=%s%n", tokyoEvent, tokyoEvent.getZone(), tokyoEvent.toInstant());
        System.out.printf("equals=%s, isEqual=%s, hashCodes=[%d, %d]%n",
                utcEvent.equals(tokyoEvent),
                utcEvent.isEqual(tokyoEvent),
                utcEvent.hashCode(),
                tokyoEvent.hashCode());

        assertFalse(utcEvent.equals(tokyoEvent), "ゾーンを含む値としては異なることを先に確認する");
        assertTrue(utcEvent.isEqual(tokyoEvent), "イベント時刻としては同じ瞬間である");

        List<ZonedDateTime> actual = deduplicator.distinctEventTimes(List.of(utcEvent, tokyoEvent));

        assertEquals(1, actual.size(), "同一瞬間のイベントは1件だけ処理されるべき");
    }

    @Test
    void differentInstants_areNotMerged() {
        ZonedDateTime firstEvent = ZonedDateTime.parse("2025-01-15T00:00:00Z");
        ZonedDateTime laterEvent = ZonedDateTime.parse("2025-01-15T00:00:01Z");

        List<ZonedDateTime> actual = deduplicator.distinctEventTimes(List.of(firstEvent, laterEvent));

        assertEquals(2, actual.size(), "異なる瞬間のイベントは別々に保持する");
    }
}
