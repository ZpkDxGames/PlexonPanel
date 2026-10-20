package io.github.zpkdxgames.plexonpanel.host;

import java.util.Locale;

/** An explicit destination is never silently downgraded. AUTO fallback is opt-in. */
public enum DestinationMode {
  AUTO, DRIVE_AND_LOCAL, LOCAL_ONLY;

  public static DestinationMode parse(Object raw) {
    String value = raw == null ? "" : raw.toString().trim().toUpperCase(Locale.ROOT);
    if (value.isEmpty()) return AUTO;
    try { return valueOf(value); }
    catch (IllegalArgumentException invalid) {
      throw FullBackupPreflight.preflightFailure("INVALID_DESTINATION_MODE",
          "destinationMode must be AUTO, DRIVE_AND_LOCAL or LOCAL_ONLY.");
    }
  }
  public record Plan(DestinationMode requested, boolean uploadOffsite, boolean requireRemote, String fallbackReason) {
    public String effective() { return uploadOffsite ? "DRIVE_AND_LOCAL" : "LOCAL_ONLY"; }
  }
  Plan plan(FullBackupPreflight.ProviderState provider, boolean fallback) {
    return switch (this) {
      case LOCAL_ONLY -> new Plan(this,false,false,null);
      case DRIVE_AND_LOCAL -> new Plan(this,true,true,null);
      case AUTO -> provider == FullBackupPreflight.ProviderState.CONNECTED
          ? new Plan(this,true,true,null)
          : fallback ? new Plan(this,false,false,"PROVIDER_"+provider.name()) : new Plan(this,true,true,null);
    };
  }
}
