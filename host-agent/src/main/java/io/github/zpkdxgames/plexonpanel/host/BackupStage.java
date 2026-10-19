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

  BackupStage(String label) {
    this.label = label;
  }
}
