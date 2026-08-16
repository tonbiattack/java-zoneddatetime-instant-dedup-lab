package jp.tonbiattack.debuglab;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives events from partner systems and removes duplicate event timestamps.
 *
 * <p>The business identity of an event timestamp is its point on the time-line.
 * The returned value keeps the first received {@link ZonedDateTime} so callers can
 * still use its original zone for display, while the deduplication key is normalized
 * to {@link Instant}.</p>
 */
public final class EventDeduplicator {

    public List<ZonedDateTime> distinctEventTimes(List<ZonedDateTime> receivedTimes) {
        Map<Instant, ZonedDateTime> firstTimeByInstant = new LinkedHashMap<>();
        for (ZonedDateTime receivedTime : receivedTimes) {
            firstTimeByInstant.putIfAbsent(receivedTime.toInstant(), receivedTime);
        }
        return List.copyOf(firstTimeByInstant.values());
    }
}
