package io.github.zpkdxgames.plexonpanel.host;

/** Presentation stages refine the existing durable cold-backup phases. */
public enum BackupStage {
  PREFLIGHT("Preflight"),
  ACQUIRE_LOCK("Acquire lock"),
  ANNOUNCE("Announce to players"),
  COUNTDOWN("Countdown"),
  SAVE_ALL_FLUSH("Save-all flush"),
  STOP_SERVICE("Stop service"),
  STOP_PROOF("Systemd stop proof"),
  BUILD_MANIFEST("Build manifest"),
  ARCHIVE_PARTIAL("ZIP to .partial"),
  FINALIZE_ARCHIVE("Finalize ZIP"),
  LOCAL_SHA256("Local SHA-256"),
  UPLOAD("Upload to Google Drive"),
  REMOTE_SHA256("Remote SHA-256 verify"),
  RESTART_RELEASE("Restart and release lock");
  public final String label;

  static BackupStage forPhase(String phase) {
    return switch (phase) {
      case "QUEUED", "PREFLIGHT", "FAILED", "RECOVERY_REQUIRED" -> PREFLIGHT;
      case "COUNTDOWN" -> COUNTDOWN;
      case "FINAL_SAVE" -> SAVE_ALL_FLUSH;
      case "STOPPING_SERVER" -> STOP_SERVICE;
      case "WAITING_FOR_STOP" -> STOP_PROOF;
      case "ARCHIVING" -> ARCHIVE_PARTIAL;
      case "HASHING" -> LOCAL_SHA256;
      case "VERIFYING_LOCAL" -> LOCAL_SHA256;
      case "UPLOADING_REMOTE", "UPLOADING" -> UPLOAD;
      case "VERIFYING_REMOTE" -> REMOTE_SHA256;
      case "STARTING_SERVER", "VERIFYING_STARTUP", "COMPLETED", "DEGRADED" -> RESTART_RELEASE;
      default -> { try { yield valueOf(phase); } catch (IllegalArgumentException e) { yield PREFLIGHT; } }
    };
  }
  static java.util.List<Integer> offsiteStageOrdinals() {
    return java.util.Arrays.stream(values())
        .filter(s -> s.ordinal() > LOCAL_SHA256.ordinal() && s.ordinal() < RESTART_RELEASE.ordinal())
        .map(s -> s.ordinal()+1).toList();
  }
  static java.util.List<java.util.Map<String,Object>> definitions() {
    return java.util.Arrays.stream(values()).map(s -> java.util.Map.<String,Object>of(
        "index",s.ordinal()+1,"key",s.name(),"id",s.name(),"label",s.label)).toList();
  }

  BackupStage(String label) {
    this.label = label;
  }
}
