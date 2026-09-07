package io.github.zpkdxgames.plexonpanel.security;

import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/**
 * Local authority shared by Paper and the optional same-host companion. Contains no bearer tokens.
 */
public final class DeviceRegistry {
  private static final String SHARED_FILE_MODE = "rw-rw----";

  public record Device(
      String deviceId,
      String name,
      String role,
      Set<String> scopes,
      long issuedAt,
      long expiresAt,
      long lastSeen) {}

  public record State(
      int protocolVersion, String serverId, long generation, long revision, List<Device> devices) {}

  private record Pending(
      String requestId, String role, Set<String> scopes, long expiresAt, int days) {}

  private final Path path;
  private final String serverId;
  private final Clock clock;
  private final Gson gson = new Gson();
  private Pending pending;

  public DeviceRegistry(Path path, String serverId) throws IOException {
    this(path, serverId, Clock.systemUTC());
  }

  public DeviceRegistry(Path path, String serverId, Clock clock) throws IOException {
    this.path = path.toAbsolutePath().normalize();
    this.serverId = UUID.fromString(serverId).toString();
    this.clock = clock;
    Files.createDirectories(this.path.getParent());
    if (Files.isSymbolicLink(path)) throw new IOException("Access registry must not be a symlink");
    locked(state -> state);
  }

  public synchronized void begin(
      String requestId, String role, Set<String> scopes, Instant expiresAt, int days) {
    UUID.fromString(requestId);
    if (role == null
        || !role.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
        || days < 1
        || days > 30
        || expiresAt.isAfter(clock.instant().plusSeconds(300))
        || !expiresAt.isAfter(clock.instant()))
      throw new IllegalArgumentException("Invalid pairing grant");
    pending =
        new Pending(requestId, role, Scopes.validate(scopes), expiresAt.getEpochSecond(), days);
  }

  public synchronized Device consume(String requestId, String deviceId, String label)
      throws IOException {
    UUID.fromString(deviceId);
    Pending grant = pending;
    if (grant == null
        || !grant.requestId.equals(requestId)
        || grant.expiresAt <= clock.instant().getEpochSecond())
      throw new SecurityException("PAIRING_EXPIRED");
    if (label == null || !label.matches("[\\p{L}\\p{N} ._()-]{1,64}"))
      throw new IllegalArgumentException("Invalid device name");
    long now = clock.instant().getEpochSecond();
    Device device =
        new Device(deviceId, label, grant.role, grant.scopes, now, now + grant.days * 86400L, now);
    locked(
        state -> {
          var devices =
              new ArrayList<>(state.devices.stream().filter(d -> d.expiresAt > now).toList());
          if (devices.size() >= 64 || devices.stream().anyMatch(d -> d.deviceId.equals(deviceId)))
            throw new SecurityException("DEVICE_LIMIT");
          devices.add(device);
          return new State(3, serverId, state.generation, state.revision + 1, List.copyOf(devices));
        });
    pending = null;
    return device;
  }

  public synchronized Device authorize(
      String deviceId, long generation, String scope, Map<String, Boolean> capabilities)
      throws IOException {
    if (!Scopes.ALL.contains(scope)) throw new SecurityException("UNKNOWN_SCOPE");
    State state = snapshot();
    if (generation != state.generation) throw new SecurityException("DEVICE_REVOKED");
    Device device =
        state.devices.stream()
            .filter(d -> d.deviceId.equals(deviceId))
            .findFirst()
            .orElseThrow(() -> new SecurityException("DEVICE_REVOKED"));
    if (device.expiresAt <= clock.instant().getEpochSecond())
      throw new SecurityException("DEVICE_EXPIRED");
    if (!device.scopes.contains(scope)) throw new SecurityException("SCOPE_DENIED");
    if (!Boolean.TRUE.equals(capabilities.get(scope)))
      throw new SecurityException("CAPABILITY_DISABLED");
    seen(deviceId);
    return device;
  }

  public synchronized void seen(String id) throws IOException {
    long now = clock.instant().getEpochSecond();
    locked(
        s -> {
          Device device =
              s.devices.stream()
                  .filter(d -> d.deviceId.equals(id) && d.expiresAt > now)
                  .findFirst()
                  .orElse(null);
          if (device == null || now - device.lastSeen < 60) return s;
          var next =
              s.devices.stream()
                  .map(
                      d ->
                          d.deviceId.equals(id)
                              ? new Device(
                                  d.deviceId,
                                  d.name,
                                  d.role,
                                  d.scopes,
                                  d.issuedAt,
                                  d.expiresAt,
                                  now)
                              : d)
                  .toList();
          return new State(3, serverId, s.generation, s.revision + 1, next);
        });
  }

  public synchronized void revoke(String id) throws IOException {
    UUID.fromString(id);
    locked(
        s ->
            new State(
                3,
                serverId,
                s.generation,
                s.revision + 1,
                s.devices.stream().filter(d -> !d.deviceId.equals(id)).toList()));
  }

  public synchronized void revokeAll() throws IOException {
    pending = null;
    locked(s -> new State(3, serverId, Math.addExact(s.generation, 1), s.revision + 1, List.of()));
  }

  public synchronized State snapshot() throws IOException {
    return locked(s -> s);
  }

  private State locked(java.util.function.UnaryOperator<State> operation) throws IOException {
    Path lock = path.resolveSibling(path.getFileName() + ".lock");
    if (Files.isSymbolicLink(lock) || Files.isSymbolicLink(path))
      throw new IOException("Unsafe registry path");
    try (FileChannel channel =
            FileChannel.open(
                lock,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        var ignored = channel.lock()) {
      State previous;
      if (Files.exists(path)) {
        if (Files.size(path) > 262144) throw new IOException("Invalid registry size");
        previous = gson.fromJson(Files.readString(path), State.class);
        if (previous == null
            || previous.protocolVersion != 3
            || !serverId.equals(previous.serverId)
            || previous.devices == null
            || previous.devices.size() > 64
            || previous.generation < 1) throw new IOException("Invalid local access registry");
        for (Device device : previous.devices) {
          UUID.fromString(device.deviceId);
          Scopes.validate(device.scopes);
          if (device.expiresAt <= device.issuedAt || device.expiresAt - device.issuedAt > 2592000)
            throw new IOException("Invalid device lifetime");
        }
      } else previous = new State(3, serverId, Math.max(1, clock.millis()), 1, List.of());
      State next = operation.apply(previous);
      if (!Files.exists(path) || next != previous) {
        AtomicFiles.writeUtf8(path, gson.toJson(next));
        enforceSharedFileMode(path);
        enforceSharedFileMode(lock);
      }
      return next;
    }
  }

  /**
   * Paper and Host intentionally access the same registry as different Linux users. A shared file
   * may therefore already have the required 0660 mode while the current process is not its owner.
   * Linux permits group read/write in that case but rejects chmod with EPERM. Accept that ownership
   * hand-off only when the existing POSIX mode is already exactly the required private shared mode.
   */
  private static void enforceSharedFileMode(Path target) throws IOException {
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(SHARED_FILE_MODE));
    } catch (UnsupportedOperationException ignoredPermissions) {
      /* Non-POSIX development platform. */
    } catch (IOException permissionError) {
      try {
        String actual =
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
        if (!SHARED_FILE_MODE.equals(actual)) throw permissionError;
      } catch (UnsupportedOperationException ignoredPermissions) {
        throw permissionError;
      } catch (IOException verificationError) {
        permissionError.addSuppressed(verificationError);
        throw permissionError;
      }
    }
  }
}
