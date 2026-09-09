package io.github.zpkdxgames.plexonpanel.integration.core;

public interface CoreBridge {
  String SUPPORTED_API_RANGE = ">=1.0 <3.0";
  String MODULE_ID = "panel";

  boolean installed();

  boolean available();

  boolean compatible();

  String pluginVersion();

  String apiVersion();

  String mode();

  String registrationState();

  String detail();

  void registerStarting();

  void markReady(String detail);

  void markDegraded(String detail);

  void markFailed(String detail);

  void unregister();
}
