package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Host-owned, non-secret maintenance configuration edited through the control plane.
 *
 * <p>Live snapshots intentionally remain governed only by HostConfig.backups.intervalMinutes in
 * protocol 3. The former liveSnapshot calendar field was never executed and is deliberately not
 * represented here; Gson safely ignores that legacy JSON member when loading older settings.
 */
public record MaintenanceSettings(
    int schemaVersion, String timezone, Restart restart, FullRestorePoint fullRestorePoint) {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public record Schedule(boolean enabled, String type, List<String> weekdays, String time) {
    public Schedule {
      weekdays = weekdays == null ? List.of() : List.copyOf(weekdays);
    }
  }

  public record Restart(
      Schedule schedule,
      List<Integer> warningSeconds,
      int stopTimeoutSeconds,
      int startupTimeoutSeconds) {
    public Restart {
      warningSeconds = warningSeconds == null ? List.of() : List.copyOf(warningSeconds);
    }
  }

  public record FullRestorePoint(
      Schedule schedule,
      String retentionMode,
      int retentionCount,
      boolean restartAfter,
      String canonicalFilename,
      int uploadTimeoutSeconds,
      String verificationMode,
      long maximumBytes,
      List<String> excludes) {
    public FullRestorePoint {
      excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }
  }

  public static MaintenanceSettings migratedDefaults() {
    Schedule disabledDaily = new Schedule(false, "DAILY", List.of(), "04:00");
    Schedule disabledWeekly =
        new Schedule(false, "WEEKLY", List.of(DayOfWeek.SUNDAY.name()), "04:00");
    return new MaintenanceSettings(
        1,
        "America/Sao_Paulo",
        new Restart(disabledDaily, List.of(900, 300, 60, 30, 10), 180, 180),
        new FullRestorePoint(
            disabledWeekly,
            "SINGLE_CURRENT",
            1,
            true,
            "PlexonCraft-Latest.zip",
            1800,
            "SIZE_AND_HASH_WHEN_AVAILABLE",
            1_099_511_627_776L,
            List.of("logs", "crash-reports", "cache", ".cache")));
  }

  public static MaintenanceSettings load(Path path) throws IOException {
    if (!Files.exists(path)) return migratedDefaults();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) > 65_536)
      throw new IOException("Invalid maintenance settings file");
    MaintenanceSettings settings = GSON.fromJson(Files.readString(path), MaintenanceSettings.class);
    validate(settings);
    return settings;
  }

  public static MaintenanceSettings fromJson(JsonElement value) {
    MaintenanceSettings settings = GSON.fromJson(value, MaintenanceSettings.class);
    validate(settings);
    return settings;
  }

  public void save(Path path) throws IOException {
    validate(this);
    Files.createDirectories(path.toAbsolutePath().normalize().getParent());
    AtomicFiles.writeUtf8(path, GSON.toJson(this));
  }

  public static void validate(MaintenanceSettings settings) {
    if (settings == null || settings.schemaVersion != 1)
      throw new IllegalArgumentException("Unsupported maintenance settings schema");
    ZoneId.of(settings.timezone);
    if (settings.restart == null || settings.fullRestorePoint == null)
      throw new IllegalArgumentException("Incomplete maintenance settings");
    validateSchedule(settings.restart.schedule);
    validateSchedule(settings.fullRestorePoint.schedule);
    if (settings.restart.warningSeconds.size() > 16
        || settings.restart.warningSeconds.stream().anyMatch(v -> v == null || v < 0 || v > 86_400)
        || settings.restart.stopTimeoutSeconds < 30
        || settings.restart.stopTimeoutSeconds > 1800
        || settings.restart.startupTimeoutSeconds < 30
        || settings.restart.startupTimeoutSeconds > 1800)
      throw new IllegalArgumentException("Invalid restart settings");
    var full = settings.fullRestorePoint;
    if (!Set.of("SINGLE_CURRENT", "ROTATING").contains(full.retentionMode)
        || full.retentionCount < 1
        || full.retentionCount > 52
        || !full.canonicalFilename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.zip")
        || full.uploadTimeoutSeconds < 60
        || full.uploadTimeoutSeconds > 7200
        || full.maximumBytes < 1_048_576L
        || full.maximumBytes > 2_199_023_255_552L
        || full.excludes.size() > 64)
      throw new IllegalArgumentException("Invalid full restore-point settings");
    for (String exclude : full.excludes)
      if (exclude == null
          || exclude.isBlank()
          || exclude.length() > 160
          || exclude.startsWith("/")
          || exclude.contains("..")
          || exclude.indexOf('\\') >= 0)
        throw new IllegalArgumentException("Invalid full restore-point exclusion");
  }

  private static void validateSchedule(Schedule schedule) {
    if (schedule == null || !Set.of("DAILY", "WEEKLY", "SELECTED_WEEKDAYS").contains(schedule.type))
      throw new IllegalArgumentException("Invalid schedule type");
    LocalTime.parse(schedule.time);
    if (schedule.weekdays.size() > 7)
      throw new IllegalArgumentException("Too many weekdays");
    for (String value : schedule.weekdays) DayOfWeek.valueOf(value);
    if ((schedule.type.equals("WEEKLY") || schedule.type.equals("SELECTED_WEEKDAYS"))
        && schedule.weekdays.isEmpty())
      throw new IllegalArgumentException("Weekday schedule requires at least one day");
  }
}
