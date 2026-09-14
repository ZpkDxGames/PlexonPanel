package io.github.zpkdxgames.plexonpanel.host;

/**
 * Narrow Host-local Minecraft command surface used only by maintenance orchestration.
 *
 * <p>This deliberately does not expose a generic command method. Dashboard/control-plane input can
 * never supply console text through this contract.
 */
public interface MinecraftCommandChannel extends AutoCloseable {
  enum MaintenanceOperation {
    RESTART("restart"),
    FULL_BACKUP("full backup");

    private final String label;

    MaintenanceOperation(String label) {
      this.label = label;
    }

    String label() {
      return label;
    }
  }

  record Result(boolean success, String code) {
    public Result {
      if (code == null || code.isBlank()) code = success ? "OK" : "COMMAND_FAILED";
    }

    static Result ok() {
      return new Result(true, "OK");
    }

    static Result failed(String code) {
      return new Result(false, code);
    }
  }

  boolean enabled();

  Result maintenanceNotice(MaintenanceOperation operation, int remainingSeconds);

  Result saveAllFlush();

  Result readinessProbe();

  @Override
  default void close() {}
}
