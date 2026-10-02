package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Public root-owned allocation authority. Private settings/keys never enter this document. */
public final class NodeInstanceRegistry {
  public static final Path DEFAULT_PATH = Path.of("/etc/plexonpanel/instances.json");
  private static final int MAXIMUM_BYTES = 131_072;
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
  private NodeInstanceRegistry() {}

  public record Entry(UUID serverId, String instanceKey, int minecraftPort, Integer rconPort) {
    public Entry {
      if (serverId == null) throw new IllegalArgumentException("REGISTRY_SERVER_ID_INVALID");
      FleetIdentity.parseUuid(serverId.toString(), "serverId");
      new InstanceLayout(instanceKey);
      port(minecraftPort);
      if (rconPort != null) { port(rconPort); if (rconPort == minecraftPort) throw new IllegalArgumentException("NODE_PORT_COLLISION"); }
    }
    private static void port(int value) {
      if (value < 1 || value > 65535) throw new IllegalArgumentException("REGISTRY_PORT_INVALID");
    }
    public FleetIdentity identity(UUID nodeId, String name) { return new FleetIdentity(serverId, nodeId, instanceKey, name); }
  }
  public record Registry(int schemaVersion, UUID nodeId, List<Entry> instances) {
    public Registry {
      if (schemaVersion != 5 || nodeId == null || instances == null || instances.size() > 256)
        throw new IllegalArgumentException("NODE_REGISTRY_INVALID");
      FleetIdentity.parseUuid(nodeId.toString(), "nodeId");
      instances = List.copyOf(instances);
      Set<String> keys = new HashSet<>(); Set<UUID> identities = new HashSet<>(); Set<Integer> ports = new HashSet<>();
      for (Entry entry : instances) {
        if (!keys.add(entry.instanceKey()) || !identities.add(entry.serverId()))
          throw new IllegalArgumentException("NODE_INSTANCE_COLLISION");
        if (!ports.add(entry.minecraftPort()) || entry.rconPort() != null && !ports.add(entry.rconPort()))
          throw new IllegalArgumentException("NODE_PORT_COLLISION");
      }
    }
    public Entry instance(String key) throws IOException {
      new InstanceLayout(key);
      return instances.stream().filter(e -> e.instanceKey().equals(key)).findFirst()
          .orElseThrow(() -> new IOException("INSTANCE_NOT_REGISTERED"));
    }
    public Entry require(FleetIdentity fleet) throws IOException {
      Entry entry = instance(fleet.instanceKey());
      if (!nodeId.equals(fleet.nodeId()) || !entry.serverId().equals(fleet.serverId()))
        throw new IOException("REGISTERED_FLEET_BINDING_MISMATCH");
      return entry;
    }
  }

  public static Registry readDefault() throws IOException {
    requireRootAuthority(DEFAULT_PATH);
    Registry registry = read(DEFAULT_PATH);
    if (!registry.nodeId().equals(NodeIdentity.readDefault())) throw new IOException("NODE_REGISTRY_IDENTITY_MISMATCH");
    return registry;
  }
  public static Entry registerDefault(FleetIdentity fleet, int minecraftPort, Integer rconPort) throws IOException {
    if (!System.getProperty("os.name").equals("Linux") || !ProcessHandle.current().info().user().orElse("").equals("root"))
      throw new IOException("LOCAL_ADMINISTRATOR_REQUIRED");
    UUID nodeId = NodeIdentity.readDefault();
    if (!nodeId.equals(fleet.nodeId())) throw new IOException("NODE_REGISTRY_IDENTITY_MISMATCH");
    requireRootAuthority(DEFAULT_PATH.getParent());
    if (Files.exists(DEFAULT_PATH, LinkOption.NOFOLLOW_LINKS)) requireRootAuthority(DEFAULT_PATH);
    Path lockPath = DEFAULT_PATH.resolveSibling(DEFAULT_PATH.getFileName() + ".lock");
    if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) requireRootAuthority(lockPath);
    return register(DEFAULT_PATH, nodeId, new Entry(fleet.serverId(), fleet.instanceKey(), minecraftPort, rconPort));
  }

  static synchronized Entry register(Path path, UUID nodeId, Entry entry) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
    InstanceLayout.rejectSymlinks(lockPath);
    if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("NODE_REGISTRY_LOCK_INVALID");
    try (var channel = FileChannel.open(lockPath, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        var lock = channel.lock()) {
      Registry before = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? read(path) : new Registry(5, nodeId, List.of());
      if (!before.nodeId().equals(nodeId)) throw new IOException("NODE_REGISTRY_IDENTITY_MISMATCH");
      Optional<Entry> existing = before.instances().stream().filter(e -> e.instanceKey().equals(entry.instanceKey())).findFirst();
      if (existing.isPresent()) {
        if (!existing.get().equals(entry)) throw new IOException("NODE_INSTANCE_REBIND_DENIED");
        return existing.get();
      }
      List<Entry> entries = new ArrayList<>(before.instances()); entries.add(entry);
      Registry after;
      try { after = new Registry(5, nodeId, entries); }
      catch (IllegalArgumentException denied) { throw new IOException(denied.getMessage()); }
      AtomicFiles.writePublicUtf8(path, JSON.toJson(after) + "\n");
      return entry;
    }
  }

  static Registry read(Path path) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAXIMUM_BYTES)
      throw new IOException("NODE_REGISTRY_UNAVAILABLE");
    try {
      var raw = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      if (!raw.keySet().equals(Set.of("schemaVersion", "nodeId", "instances"))) throw new IllegalArgumentException();
      if (!raw.get("schemaVersion").isJsonPrimitive() || !raw.getAsJsonPrimitive("schemaVersion").isNumber()
          || raw.get("schemaVersion").getAsBigDecimal().intValueExact() != 5) throw new IllegalArgumentException();
      UUID nodeId = FleetIdentity.parseUuid(raw.get("nodeId").getAsString(), "nodeId");
      List<Entry> entries = new ArrayList<>();
      for (var value : raw.getAsJsonArray("instances")) {
        var entry = value.getAsJsonObject();
        if (!entry.keySet().equals(Set.of("serverId", "instanceKey", "minecraftPort", "rconPort"))) throw new IllegalArgumentException();
        entries.add(new Entry(FleetIdentity.parseUuid(entry.get("serverId").getAsString(), "serverId"), entry.get("instanceKey").getAsString(),
            integer(entry.get("minecraftPort")), entry.get("rconPort").isJsonNull() ? null : integer(entry.get("rconPort"))));
      }
      return new Registry(5, nodeId, entries);
    } catch (RuntimeException invalid) { throw new IOException("NODE_REGISTRY_INVALID"); }
  }
  private static int integer(JsonElement value) {
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
    return value.getAsBigDecimal().intValueExact();
  }
  static void requireRootAuthority(Path path) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
      var attrs = Files.readAttributes(part, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.owner().getName().equals("root") || attrs.permissions().contains(PosixFilePermission.GROUP_WRITE)
          || attrs.permissions().contains(PosixFilePermission.OTHERS_WRITE)) throw new IOException("NODE_REGISTRY_AUTHORITY_INVALID");
    }
  }
}
