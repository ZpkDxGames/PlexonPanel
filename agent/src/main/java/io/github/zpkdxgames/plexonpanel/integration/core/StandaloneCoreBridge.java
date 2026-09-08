package io.github.zpkdxgames.plexonpanel.integration.core;

final class StandaloneCoreBridge implements CoreBridge {
  private final boolean installed;
  private final String pluginVersion;
  private final String apiVersion;
  private final String detail;

  StandaloneCoreBridge(boolean installed, String pluginVersion, String apiVersion, String detail) {
    this.installed = installed;
    this.pluginVersion = normalize(pluginVersion);
    this.apiVersion = normalize(apiVersion);
    this.detail = detail == null || detail.isBlank() ? "PlexonCore is not installed" : detail;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  @Override
  public boolean installed() {
    return installed;
  }

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public boolean compatible() {
    return false;
  }

  @Override
  public String pluginVersion() {
    return pluginVersion;
  }

  @Override
  public String apiVersion() {
    return apiVersion;
  }

  @Override
  public String mode() {
    return "STANDALONE";
  }

  @Override
  public String registrationState() {
    return installed ? "UNAVAILABLE" : "NOT_INSTALLED";
  }

  @Override
  public String detail() {
    return detail;
  }

  @Override
  public void registerStarting() {}

  @Override
  public void markReady(String detail) {}

  @Override
  public void markDegraded(String detail) {}

  @Override
  public void markFailed(String detail) {}

  @Override
  public void unregister() {}
}
