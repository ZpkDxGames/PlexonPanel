package io.github.zpkdxgames.plexonpanel.protocol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ReplayGuard {
    private final Clock clock;
    private final Duration maximumClockSkew;
    private final int maximumEntries;
    private final LinkedHashMap<UUID, Instant> accepted = new LinkedHashMap<>();

    public ReplayGuard(Duration maximumClockSkew, int maximumEntries) {
        this(Clock.systemUTC(), maximumClockSkew, maximumEntries);
    }

    ReplayGuard(Clock clock, Duration maximumClockSkew, int maximumEntries) {
        if (maximumClockSkew.isNegative() || maximumClockSkew.isZero()) {
            throw new IllegalArgumentException("maximumClockSkew must be positive");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.clock = clock;
        this.maximumClockSkew = maximumClockSkew;
        this.maximumEntries = maximumEntries;
    }

    public synchronized boolean accept(UUID messageId, Instant timestamp) {
        Instant now = clock.instant();
        if (timestamp.isBefore(now.minus(maximumClockSkew)) || timestamp.isAfter(now.plus(maximumClockSkew))) {
            return false;
        }
        purge(now.minus(maximumClockSkew.multipliedBy(2)));
        if (accepted.containsKey(messageId)) {
            return false;
        }
        accepted.put(messageId, timestamp);
        while (accepted.size() > maximumEntries) {
            Iterator<UUID> iterator = accepted.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private void purge(Instant cutoff) {
        Iterator<Map.Entry<UUID, Instant>> iterator = accepted.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }
}
