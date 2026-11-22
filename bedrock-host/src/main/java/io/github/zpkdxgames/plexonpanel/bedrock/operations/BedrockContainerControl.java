package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Staging boundary for a locally pinned container. Implementations must independently match the
 * configured name and immutable container ID before every write, and bound every I/O operation.
 */
public interface BedrockContainerControl {
    enum Phase { CREATED, RUNNING, EXITED, RESTARTING, PAUSED, UNKNOWN }
    enum Readiness { VERIFIED, UNKNOWN }

    record Container(String id, Instant startedAt, Phase phase, int exitCode, boolean oomKilled,
                     Readiness readiness, Path dataMount) {
        public Container {
            if (id == null || !id.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Expected an immutable Docker container ID");
            }
            Objects.requireNonNull(phase);
            Objects.requireNonNull(readiness);
            Objects.requireNonNull(dataMount);
            if (!dataMount.isAbsolute() || !dataMount.normalize().equals(dataMount)
                    || phase == Phase.RUNNING && startedAt == null
                    || phase != Phase.RUNNING && readiness == Readiness.VERIFIED) {
                throw new IllegalArgumentException("Invalid container evidence");
            }
        }

        boolean sameContainer(Container other) {
            return id.equals(other.id) && dataMount.equals(other.dataMount);
        }

        boolean sameLaunch(Container other) {
            return sameContainer(other) && Objects.equals(startedAt, other.startedAt);
        }

        boolean cleanExit() {
            return phase == Phase.EXITED && exitCode == 0 && !oomKilled;
        }
    }

    Container inspect() throws IOException;

    void start(Container expected) throws IOException;

    void stop(Container expected, Duration grace) throws IOException;
}
