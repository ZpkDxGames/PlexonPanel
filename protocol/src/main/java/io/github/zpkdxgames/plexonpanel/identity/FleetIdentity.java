package io.github.zpkdxgames.plexonpanel.identity;

import java.util.UUID;

/** Immutable routing identities and a separately mutable presentation label. */
public record FleetIdentity(UUID serverId, UUID nodeId, String instanceKey, String serverName) {
  public FleetIdentity {
    validateUuid(serverId, "serverId");
    validateUuid(nodeId, "nodeId");
    if (instanceKey == null || !instanceKey.matches(FleetContract.INSTANCE_KEY_PATTERN))
      throw new IllegalArgumentException("Invalid instanceKey");
    if (serverName == null || serverName.isBlank() || serverName.length() > 64
        || serverName.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid serverName");
    serverName = serverName.strip();
  }

  public static FleetIdentity parse(String serverId, String nodeId, String instanceKey, String name) {
    return new FleetIdentity(parseUuid(serverId, "serverId"), parseUuid(nodeId, "nodeId"), instanceKey, name);
  }

  public static UUID parseUuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      validateUuid(id, field);
      return id;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      // Do not include the supplied value or its parser exception in diagnostics.
      throw new IllegalArgumentException("Invalid " + field);
    }
  }

  private static void validateUuid(UUID id, String field) {
    if (id == null || id.variant() != 2 || id.version() < 1 || id.version() > 5)
      throw new IllegalArgumentException("Invalid " + field);
  }

  public String minecraftUnit() {
    return "minecraft@" + instanceKey + ".service";
  }

  public FleetIdentity renamed(String name) {
    return new FleetIdentity(serverId, nodeId, instanceKey, name);
  }

  public boolean sameTarget(FleetIdentity other) {
    return other != null && serverId.equals(other.serverId) && nodeId.equals(other.nodeId);
  }
}
