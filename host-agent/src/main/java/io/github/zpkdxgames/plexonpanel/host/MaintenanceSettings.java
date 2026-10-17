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
 * <p>Explicit v5 backup schedules are supported. Legacy {@code fullRestorePoint.schedule} and
 * {@code liveSnapshot} JSON members are intentionally absent from this model; Gson ignores those
 * members when loading an older settings file, so they cannot regain runtime authority after an
 * upgrade.
 */
public record MaintenanceSettings(
    int schemaVersion,
    String timezone,
    Restart restart,
    FullRestorePoint fullRestorePoint,
    BackupScheduling schedule) {
  public MaintenanceSettings {
    if (schedule == null) schedule = new BackupScheduling(BackupSchedule.disabled());
  }

  public MaintenanceSettings(
      int schemaVersion, String timezone, Restart restart, FullRestorePoint fullRestorePoint) {
    this(schemaVersion, timezone, restart, fullRestorePoint, null);
  }

  public record BackupScheduling(BackupSchedule backups) {
    public BackupScheduling {
      if (backups == null) backups = BackupSchedule.disabled();
    }
  }

  public record BackupSchedule(
      boolean enabled,
      String cadence,
      List<String> weekdays,
      String time,
      int intervalHours,
      int countdownMinutes,
      boolean skipIfEmptySinceLastBackup,
      int forceAfterHours) {
    public BackupSchedule {
      weekdays = weekdays == null ? List.of() : List.copyOf(weekdays);
    }

    public static BackupSchedule disabled() {
      return new BackupSchedule(false, "DAILY", List.of(), "04:00", 24, 15, false, 168);
    }
  }

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  static final List<String> REQUIRED_FULL_BACKUP_EXCLUDES =
      List.of(
          "logs",
          "crash-reports",
          "cache",
          ".cache",
          "plugins/PlexonPanel/audit",
          "plugins/PlexonPanel/identity",
          "plugins/spark/tmp");

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
      String retentionMode,
      int retentionCount,
      boolean restartAfter,
      String canonicalFilename,
      int uploadTimeoutSeconds,
      String verificationMode,
      long maximumBytes,
      List<String> excludes,
      String localRetentionPolicy) {
    public FullRestorePoint(
        String retentionMode,
        int retentionCount,
        boolean restartAfter,
        String canonicalFilename,
        int uploadTimeoutSeconds,
        String verificationMode,
        long maximumBytes,
        List<String> excludes) {
      this(
          retentionMode,
          retentionCount,
          restartAfter,
          canonicalFilename,
          uploadTimeoutSeconds,
          verificationMode,
          maximumBytes,
          excludes,
          "REMOTE_ONLY_AFTER_VERIFY");
    }

    public FullRestorePoint {
      if (localRetentionPolicy == null) localRetentionPolicy = "REMOTE_ONLY_AFTER_VERIFY";
      // Step 8 finalizes one operator-facing workflow: a manual full backup always restores
      // Minecraft availability after a successful or safely degraded operation. Keep the legacy
      // JSON field for rolling-upgrade compatibility, but normalize false to true so an older
      // maintenance-settings.json cannot silently preserve the server-offline behavior.
      restartAfter = true;
      LinkedHashSet<String> normalizedExcludes =
          new LinkedHashSet<>(MaintenanceSettings.REQUIRED_FULL_BACKUP_EXCLUDES);
      if (excludes != null) normalizedExcludes.addAll(excludes);
      excludes = List.copyOf(normalizedExcludes);
    }
  }

  public static MaintenanceSettings migratedDefaults() {
    Schedule disabledRestart = new Schedule(false, "DAILY", List.of(), "04:00");
    return new MaintenanceSettings(
        1,
        "America/Sao_Paulo",
        new Restart(disabledRestart, List.of(900, 300, 60, 30, 10), 180, 180),
        new FullRestorePoint(
            "SINGLE_CURRENT",
            1,
            true,
            "PlexonCraft-Latest.zip",
            1800,
            "SIZE_AND_HASH_WHEN_AVAILABLE",
            1_099_511_627_776L,
            List.of()));
  }

  public static MaintenanceSettings load(Path path) throws IOException {
    if (!Files.exists(path)) return migratedDefaults();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) > 65_536) throw new IOException("Invalid maintenance settings file");
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
    var b = settings.schedule.backups;
    if (!Set.of("DAILY", "WEEKLY", "SELECTED_DAYS", "INTERVAL_HOURS").contains(b.cadence)
        || b.intervalHours < 1
        || b.intervalHours > 168
        || !Set.of(30, 15, 10, 5).contains(b.countdownMinutes)
        || b.forceAfterHours < 1
        || b.forceAfterHours > 8760) throw new IllegalArgumentException("Invalid backup schedule");
    LocalTime.parse(b.time);
    if (b.weekdays.size() > 7 || new HashSet<>(b.weekdays).size() != b.weekdays.size())
      throw new IllegalArgumentException("Invalid weekdays");
    for (String day : b.weekdays) DayOfWeek.valueOf(day);
    if (b.cadence.equals("WEEKLY") && b.weekdays.size() != 1
        || b.cadence.equals("SELECTED_DAYS") && b.weekdays.isEmpty())
      throw new IllegalArgumentException("Backup schedule requires weekdays");
    if (!Set.of("REMOTE_ONLY_AFTER_VERIFY", "KEEP_LOCAL_ROTATING")
        .contains(settings.fullRestorePoint.localRetentionPolicy))
      throw new IllegalArgumentException("Invalid local retention policy");
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
    if (!full.excludes.containsAll(REQUIRED_FULL_BACKUP_EXCLUDES))
      throw new IllegalArgumentException("Mandatory full-backup exclusions are missing");
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
    if (schedule.weekdays.size() > 7) throw new IllegalArgumentException("Too many weekdays");
    for (String value : schedule.weekdays) DayOfWeek.valueOf(value);
    if ((schedule.type.equals("WEEKLY") || schedule.type.equals("SELECTED_WEEKDAYS"))
        && schedule.weekdays.isEmpty())
      throw new IllegalArgumentException("Weekday schedule requires at least one day");
  }
}
