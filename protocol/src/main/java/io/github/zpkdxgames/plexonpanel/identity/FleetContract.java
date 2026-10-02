package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Additive fleet metadata contract carried inside signed Protocol 3 bodies. */
public final class FleetContract {
  public static final int VERSION = 1;
  public static final int PROTOCOL_VERSION = 3;
  public static final int PAPER_SCHEMA_VERSION = 5;
  public static final int HOST_SCHEMA_VERSION = 5;
  public static final int MAXIMUM_CONNECTIONS = 16;
  public static final String INSTANCE_KEY_PATTERN = "^[a-z][a-z0-9-]{0,31}$";
  public static final String ID = contractId();

  private FleetContract() {}

  private static String contractId() {
    try (var stream = FleetContract.class.getResourceAsStream("/fleet-contract-v1.json")) {
      if (stream == null) throw new IllegalStateException("Fleet contract resource missing");
      var manifest = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(manifest.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new ExceptionInInitializerError("Fleet contract could not be loaded");
    }
  }
}
