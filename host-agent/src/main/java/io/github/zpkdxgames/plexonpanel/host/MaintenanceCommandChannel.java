package io.github.zpkdxgames.plexonpanel.host;

import java.io.IOException;

/**
 * Host-local maintenance commands. This deliberately exposes semantic operations rather than a
 * generic command executor so no dashboard/controller input can become an RCON command.
 */
interface MaintenanceCommandChannel {
  enum Operation {
    RESTART("restart"),
    FULL_BACKUP("full backup");

    private final String label;

    Operation(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  void warning(Operation operation, int remainingSeconds) throws IOException;

  void saveAllFlush() throws IOException;

  boolean ready();
}
