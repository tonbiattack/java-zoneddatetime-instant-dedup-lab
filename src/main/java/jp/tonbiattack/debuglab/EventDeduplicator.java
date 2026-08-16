package jp.tonbiattack.debuglab;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Receives events from partner systems and removes duplicate event timestamps.
 *
 * <p>BUG: business identity is an instant on the timeline, but this method uses
 * {@link ZonedDateTime} as the Set key. ZonedDateTime equality retains zone and
 * local-date-time state, so representations of the same instant can remain distinct.</p>
 */
public final class EventDeduplicator {

    public List<ZonedDateTime> distinctEventTimes(List<ZonedDateTime> receivedTimes) {
        Set<ZonedDateTime> uniqueTimes = new LinkedHashSet<>(receivedTimes);
        return List.copyOf(uniqueTimes);
    }
}
