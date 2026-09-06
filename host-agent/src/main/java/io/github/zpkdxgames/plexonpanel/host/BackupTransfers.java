package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

public final class BackupTransfers implements AutoCloseable {
  private record Transfer(
      String device, FileChannel channel, String sha256, long bytes, int sequence, long expiry) {}

  private final Map<String, Transfer> transfers = new HashMap<>();

  public synchronized Map<String, Object> start(
      Path archive, BackupManager.Metadata metadata, DeviceRegistry.Device device)
      throws IOException {
    clean();
    if (transfers.size() >= 4
        || transfers.values().stream().anyMatch(t -> t.device.equals(device.deviceId())))
      throw new SecurityException("BUSY");
    if (metadata.bytes() > 64 * 1024 * 1024)
      throw new IOException(
          "Browser backup downloads are limited to 64 MiB; use the local/off-site archive for"
              + " larger worlds");
    var channel = FileChannel.open(archive, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    String id = UUID.randomUUID().toString();
    transfers.put(
        id,
        new Transfer(
            device.deviceId(),
            channel,
            metadata.sha256(),
            metadata.bytes(),
            0,
            System.currentTimeMillis() + 60000));
    return Map.of("transferId", id, "bytes", metadata.bytes(), "sha256", metadata.sha256());
  }

  public synchronized Map<String, Object> chunk(String id, int sequence, String device)
      throws IOException {
    clean();
    Transfer t = transfers.get(id);
    if (t == null || !t.device.equals(device)) throw new SecurityException("TRANSFER_EXPIRED");
    if (t.sequence != sequence) throw new SecurityException("TRANSFER_SEQUENCE");
    if (t.channel.size() != t.bytes) throw new IOException("Archive changed");
    ByteBuffer bytes = ByteBuffer.allocate(16384);
    while (bytes.hasRemaining() && t.channel.read(bytes) >= 0) {}
    boolean eof = t.channel.position() == t.bytes;
    if (eof) {
      t.channel.close();
      transfers.remove(id);
    } else
      transfers.put(
          id,
          new Transfer(
              t.device,
              t.channel,
              t.sha256,
              t.bytes,
              sequence + 1,
              System.currentTimeMillis() + 60000));
    return Map.of(
        "transferId",
        id,
        "sequence",
        sequence,
        "data",
        Base64.getEncoder().encodeToString(Arrays.copyOf(bytes.array(), bytes.position())),
        "eof",
        eof,
        "sha256",
        t.sha256);
  }

  public synchronized void cancel(String id, String device) throws IOException {
    Transfer t = transfers.get(id);
    if (t != null && t.device.equals(device)) {
      t.channel.close();
      transfers.remove(id);
    }
  }

  private void clean() throws IOException {
    for (var e : new ArrayList<>(transfers.entrySet()))
      if (e.getValue().expiry < System.currentTimeMillis()) {
        e.getValue().channel.close();
        transfers.remove(e.getKey());
      }
  }

  public synchronized void close() {
    for (var t : transfers.values())
      try {
        t.channel.close();
      } catch (IOException ignored) {
      }
    transfers.clear();
  }
}
