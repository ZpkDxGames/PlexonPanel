package io.github.zpkdxgames.plexonpanel.bedrock;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded HTTP/1.1 transport for a root-configured local Docker Unix socket. */
final class DockerUnixHttp {
  private static final int MAX_HEADER_BYTES = 16_384;
  private static final int MAX_LINE_BYTES = 4_096;

  private final UnixDomainSocketAddress address;
  private final long timeoutNanos;

  DockerUnixHttp(Path socket, Duration timeout) {
    address = UnixDomainSocketAddress.of(socket);
    timeoutNanos = timeout.toNanos();
  }

  Response get(String path, int maxBodyBytes) throws IOException {
    return exchange("GET " + path + " HTTP/1.1\r\nHost: docker\r\nConnection: close\r\n\r\n",
        maxBodyBytes, timeoutNanos, null, null);
  }

  void startPinned(String id) throws IOException {
    post(pathFor(id) + "/start", Duration.ofSeconds(15));
  }

  void stopPinned(String id, Duration grace) throws IOException {
    long seconds = grace.toSeconds();
    if (seconds < 30 || seconds > 300 || !Duration.ofSeconds(seconds).equals(grace)) {
      throw new IOException("Invalid Docker shutdown grace period");
    }
    post(pathFor(id) + "/stop?signal=SIGTERM&t=" + seconds, grace.plusSeconds(5));
  }

  void writePinnedStdin(String id, byte[] line, LaunchCheck verifyLaunch) throws IOException {
    if (line.length < 2 || line.length > 645 || line[line.length - 1] != '\n') {
      throw new IOException("Invalid staged Bedrock console input");
    }
    String path = pathFor(id) + "/attach?stream=1&stdin=1&stdout=0&stderr=0&logs=0";
    String request = "POST " + path + " HTTP/1.1\r\nHost: docker\r\nConnection: Upgrade\r\n"
        + "Upgrade: tcp\r\nContent-Length: 0\r\n\r\n";
    exchange(request, 0, Duration.ofSeconds(5).toNanos(), line, verifyLaunch);
  }

  private void post(String path, Duration timeout) throws IOException {
    String request = "POST " + path + " HTTP/1.1\r\nHost: docker\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
    Response response = exchange(request, 0, timeout.toNanos(), null, null);
    if (response.status() != 204) {
      throw new IOException("Docker staged action outcome is uncertain");
    }
  }

  private static String pathFor(String id) throws IOException {
    if (id == null || !id.matches("[0-9a-f]{64}")) {
      throw new IOException("Invalid Docker container identity");
    }
    return "/v1.44/containers/" + id;
  }

  private Response exchange(String request, int maxBodyBytes, long timeout, byte[] stdin, LaunchCheck verifyLaunch)
      throws IOException {
    long deadline = System.nanoTime() + timeout;
    try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
         Selector selector = Selector.open()) {
      channel.configureBlocking(false);
      SelectionKey key = channel.register(selector, 0);
      if (!channel.connect(address)) {
        while (!channel.finishConnect()) {
          await(key, selector, SelectionKey.OP_CONNECT, deadline);
        }
      }
      write(channel, selector, key, StandardCharsets.US_ASCII.encode(request), deadline);
      Response response = readResponse(new UnixInput(channel, selector, key, deadline), maxBodyBytes);
      if (stdin != null) {
        if (response.status() != 101
            || !"upgrade".equalsIgnoreCase(response.headers().get("connection"))
            || !"tcp".equalsIgnoreCase(response.headers().get("upgrade"))) {
          throw new IOException("Docker did not grant staged console attach");
        }
        verifyLaunch.check();
        write(channel, selector, key, ByteBuffer.wrap(stdin), deadline);
      }
      return response;
    }
  }

  private static void write(SocketChannel channel, Selector selector, SelectionKey key, ByteBuffer data,
                            long deadline) throws IOException {
    while (data.hasRemaining()) {
      checkDeadline(deadline);
      if (channel.write(data) == 0) {
        await(key, selector, SelectionKey.OP_WRITE, deadline);
      }
    }
  }

  @FunctionalInterface
  interface LaunchCheck {
    void check() throws IOException;
  }

  private static Response readResponse(InputStream input, int maxBodyBytes) throws IOException {
    String line = readLine(input);
    if (!line.matches("HTTP/1\\.[01] [1-5][0-9]{2}( .*)?")) {
      throw new IOException("Invalid Docker HTTP status");
    }
    int status = Integer.parseInt(line.substring(9, 12));
    Map<String, String> headers = new HashMap<>();
    int headerBytes = line.length() + 2;
    while (!(line = readLine(input)).isEmpty()) {
      headerBytes += line.length() + 2;
      int separator = line.indexOf(':');
      if (separator <= 0 || headerBytes > MAX_HEADER_BYTES) {
        throw new IOException("Invalid or oversized Docker HTTP headers");
      }
      String name = line.substring(0, separator).toLowerCase(Locale.ROOT);
      String value = line.substring(separator + 1).trim();
      if ((name.equals("content-length") || name.equals("transfer-encoding")
          || name.equals("content-encoding") || name.equals("connection") || name.equals("upgrade")) && headers.putIfAbsent(name, value) != null) {
        throw new IOException("Ambiguous Docker HTTP response framing");
      }
    }
    // Docker error bodies may contain local paths and configuration. Discard them.
    if (status != 200) return new Response(status, new byte[0], headers);

    String encoding = headers.get("content-encoding");
    String transfer = headers.get("transfer-encoding");
    String length = headers.get("content-length");
    if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
      throw new IOException("Unsupported Docker HTTP content encoding");
    }
    if (transfer != null) {
      if (length != null || !transfer.equalsIgnoreCase("chunked")) {
        throw new IOException("Unsupported Docker HTTP transfer framing");
      }
      return new Response(status, readChunks(input, maxBodyBytes), headers);
    }
    if (length != null) {
      return new Response(status, readExact(input, parseSize(length, 10, maxBodyBytes)), headers);
    }
    byte[] body = input.readNBytes(maxBodyBytes + 1);
    if (body.length > maxBodyBytes) throw new IOException("Oversized Docker HTTP body");
    return new Response(status, body, headers);
  }

  private static byte[] readChunks(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (true) {
      String line = readLine(input);
      int extension = line.indexOf(';');
      String size = extension < 0 ? line : line.substring(0, extension);
      int length = parseSize(size, 16, limit - output.size());
      if (length == 0) break;
      output.writeBytes(readExact(input, length));
      if (!readLine(input).isEmpty()) throw new IOException("Invalid Docker HTTP chunk");
    }
    int trailerBytes = 0;
    String line;
    while (!(line = readLine(input)).isEmpty()) {
      trailerBytes += line.length() + 2;
      if (trailerBytes > MAX_HEADER_BYTES) throw new IOException("Oversized Docker HTTP trailers");
    }
    return output.toByteArray();
  }

  private static int parseSize(String value, int radix, int limit) throws IOException {
    if (!value.matches(radix == 10 ? "[0-9]{1,10}" : "[0-9a-fA-F]{1,8}")) {
      throw new IOException("Invalid Docker HTTP body length");
    }
    long length = Long.parseLong(value, radix);
    if (length > limit) throw new IOException("Oversized Docker HTTP body");
    return (int) length;
  }

  private static byte[] readExact(InputStream input, int length) throws IOException {
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) throw new EOFException("Incomplete Docker HTTP body");
    return bytes;
  }

  private static String readLine(InputStream input) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int value; (value = input.read()) >= 0; ) {
      if (value == '\r' && input.read() == '\n') return line.toString();
      if ((value < 32 && value != '\t') || value > 126 || line.length() >= MAX_LINE_BYTES) {
        throw new IOException("Invalid or oversized Docker HTTP line");
      }
      line.append((char) value);
    }
    throw new EOFException("Incomplete Docker HTTP line");
  }

  private static void checkDeadline(long deadline) throws IOException {
    if (deadline - System.nanoTime() <= 0 || Thread.currentThread().isInterrupted()) {
      throw new IOException("Docker HTTP request timed out or was interrupted");
    }
  }

  private static void await(SelectionKey key, Selector selector, int operation, long deadline)
      throws IOException {
    key.interestOps(operation);
    checkDeadline(deadline);
    long millis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000);
    selector.select(millis);
    selector.selectedKeys().clear();
    checkDeadline(deadline);
  }

  record Response(int status, byte[] body, Map<String, String> headers) {}

  private static final class UnixInput extends InputStream {
    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;
    private final long deadline;
    private final ByteBuffer buffer = ByteBuffer.allocate(8_192).limit(0);

    UnixInput(SocketChannel channel, Selector selector, SelectionKey key, long deadline) {
      this.channel = channel;
      this.selector = selector;
      this.key = key;
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      return availableBytes() ? Byte.toUnsignedInt(buffer.get()) : -1;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) return 0;
      if (!availableBytes()) return -1;
      int count = Math.min(length, buffer.remaining());
      buffer.get(bytes, offset, count);
      return count;
    }

    private boolean availableBytes() throws IOException {
      checkDeadline(deadline);
      if (buffer.hasRemaining()) return true;
      buffer.clear();
      int count;
      while ((count = channel.read(buffer)) == 0) {
        await(key, selector, SelectionKey.OP_READ, deadline);
      }
      buffer.flip();
      return count > 0;
    }
  }
}
