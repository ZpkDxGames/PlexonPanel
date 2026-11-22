package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Container;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness;

/** Local lifecycle state machine. The read-only broker has no route to this class. */
public final class BedrockLifecycle {
    public enum Action { START, STOP, RESTART }
    public enum Outcome {
        DISABLED, BUSY, COOLDOWN, REQUEST_MISMATCH, CACHE_FULL, TARGET_UNAVAILABLE,
        INVALID_STATE, ALREADY_IN_STATE, STOPPED, RUNNING_UNVERIFIED, READY, OUTCOME_UNKNOWN
    }

    private static final int MAX_REPLAY_ENTRIES = 512;
    private static final long REPLAY_NANOS = Duration.ofMinutes(15).toNanos();
    private static final long COOLDOWN_NANOS = Duration.ofSeconds(30).toNanos();
    private final boolean enabled;
    private final BedrockContainerControl control;
    private final BedrockOperationLock operationLock;
    private final Duration grace;
    private final LongSupplier monotonicNanos;
    private final Map<UUID, Receipt> receipts = new HashMap<>();
    private Long lastMutationAt;

    public BedrockLifecycle(boolean enabled, BedrockContainerControl control, BedrockOperationLock operationLock,
                            Duration grace) {
        this(enabled, control, operationLock, grace, System::nanoTime);
    }

    BedrockLifecycle(boolean enabled, BedrockContainerControl control, BedrockOperationLock operationLock,
                     Duration grace, LongSupplier monotonicNanos) {
        this.enabled = enabled;
        this.control = Objects.requireNonNull(control);
        this.operationLock = Objects.requireNonNull(operationLock);
        this.grace = Objects.requireNonNull(grace);
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
        if (grace.compareTo(Duration.ofSeconds(30)) < 0 || grace.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Docker stop grace is outside the staging policy");
        }
    }

    public Outcome execute(UUID requestId, Action action) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(action);
        if (!enabled) {
            return Outcome.DISABLED;
        }
        if (!operationLock.acquire()) {
            return Outcome.BUSY;
        }
        try {
            long now = monotonicNanos.getAsLong();
            receipts.values().removeIf(receipt -> now - receipt.recordedAt() >= REPLAY_NANOS);
            Receipt previous = receipts.get(requestId);
            if (previous != null) {
                return previous.action() == action ? previous.outcome() : Outcome.REQUEST_MISMATCH;
            }
            if (lastMutationAt != null && now - lastMutationAt < COOLDOWN_NANOS) {
                return Outcome.COOLDOWN;
            }
            if (receipts.size() >= MAX_REPLAY_ENTRIES) {
                return Outcome.CACHE_FULL;
            }
            Outcome outcome = apply(action);
            receipts.put(requestId, new Receipt(action, outcome, monotonicNanos.getAsLong()));
            return outcome;
        } finally {
            operationLock.release();
        }
    }

    private Outcome apply(Action action) {
        Container before;
        try {
            before = control.inspect();
        } catch (IOException unavailable) {
            return Outcome.TARGET_UNAVAILABLE;
        }
        return switch (before.phase()) {
            case RUNNING -> switch (action) {
                case START -> Outcome.ALREADY_IN_STATE;
                case STOP, RESTART -> stopOrRestart(action, before);
            };
            case CREATED, EXITED -> switch (action) {
                case STOP -> Outcome.ALREADY_IN_STATE;
                case START -> startFrom(before);
                case RESTART -> Outcome.INVALID_STATE;
            };
            default -> Outcome.INVALID_STATE;
        };
    }

    private Outcome stopOrRestart(Action action, Container before) {
        try {
            control.stop(before, grace);
            Container stopped = control.inspect();
            if (!before.sameLaunch(stopped) || !stopped.cleanExit()) {
                return Outcome.OUTCOME_UNKNOWN;
            }
            return action == Action.STOP ? Outcome.STOPPED : startFrom(stopped);
        } catch (IOException unknownOutcome) {
            return Outcome.OUTCOME_UNKNOWN;
        } finally {
            lastMutationAt = monotonicNanos.getAsLong();
        }
    }

    private Outcome startFrom(Container before) {
        try {
            control.start(before);
            Container running = control.inspect();
            if (!before.sameContainer(running) || running.phase() != Phase.RUNNING
                    || before.startedAt() != null && !running.startedAt().isAfter(before.startedAt())) {
                return Outcome.OUTCOME_UNKNOWN;
            }
            return running.readiness() == Readiness.VERIFIED ? Outcome.READY : Outcome.RUNNING_UNVERIFIED;
        } catch (IOException unknownOutcome) {
            return Outcome.OUTCOME_UNKNOWN;
        } finally {
            lastMutationAt = monotonicNanos.getAsLong();
        }
    }

    private record Receipt(Action action, Outcome outcome, long recordedAt) {}
}
