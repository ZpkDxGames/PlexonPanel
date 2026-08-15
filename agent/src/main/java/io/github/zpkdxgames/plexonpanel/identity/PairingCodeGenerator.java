package io.github.zpkdxgames.plexonpanel.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;

public final class PairingCodeGenerator {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final int CODE_SPACE = 1_000_000;

    private final Clock clock;
    private final IntSupplier randomValue;

    public PairingCodeGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    PairingCodeGenerator(Clock clock, IntSupplier randomValue) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.randomValue = Objects.requireNonNull(randomValue, "randomValue");
    }

    private PairingCodeGenerator(Clock clock, SecureRandom secureRandom) {
        this(clock, () -> secureRandom.nextInt(CODE_SPACE));
    }

    public GeneratedCode generate() {
        int value = randomValue.getAsInt();
        if (value < 0 || value >= CODE_SPACE) {
            throw new IllegalStateException("Pairing random source returned an out-of-range value");
        }
        Instant createdAt = clock.instant();
        return new GeneratedCode(
            UUID.randomUUID().toString(),
            String.format(Locale.ROOT, "%06d", value),
            createdAt,
            createdAt.plus(DEFAULT_TTL)
        );
    }

    public record GeneratedCode(String requestId, String value, Instant createdAt, Instant expiresAt) {
    }
}
