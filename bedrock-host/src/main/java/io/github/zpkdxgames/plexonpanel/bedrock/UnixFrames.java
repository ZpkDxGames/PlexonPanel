package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.net.SocketTimeoutException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;

/** A one-request, length-prefixed local channel with an elapsed-time budget. */
final class UnixFrames implements AutoCloseable {
  static final Gson JSON = new GsonBuilder().serializeNulls().create();
  private final SocketChannel socket;
  private final Selector selector;
  private final SelectionKey key;

  UnixFrames(SocketChannel socket) throws IOException {
    this.socket = socket;
    socket.configureBlocking(false);
    selector = Selector.open();
    try {
      key = socket.register(selector, 0);
    } catch (IOException | RuntimeException failure) {
      selector.close();
      throw failure;
    }
  }

  void connect(Path path, Duration timeout) throws IOException {
    if (socket.connect(UnixDomainSocketAddress.of(path))) return;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!socket.finishConnect()) await(SelectionKey.OP_CONNECT, deadline);
  }

  boolean peerIs(String userName) throws IOException {
    return socket.getOption(ExtendedSocketOptions.SO_PEERCRED).user().getName().equals(userName);
  }

  String read(int maxBytes, Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
    readExactly(header, deadline);
    int length = header.flip().getInt();
    if (length < 1 || length > maxBytes) throw new IOException("Broker frame length exceeds its bounds");
    ByteBuffer value = ByteBuffer.allocate(length);
    readExactly(value, deadline);
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(value.flip()).toString();
  }

  void write(JsonObject body, int maxBytes, Duration timeout) throws IOException {
    byte[] value = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    if (value.length < 1 || value.length > maxBytes) throw new IOException("Broker response is too large");
    ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + value.length);
    frame.putInt(value.length).put(value).flip();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (frame.hasRemaining()) {
      checkDeadline(deadline);
      if (socket.write(frame) == 0) await(SelectionKey.OP_WRITE, deadline);
    }
  }

  private void readExactly(ByteBuffer target, long deadline) throws IOException {
    while (target.hasRemaining()) {
      checkDeadline(deadline);
      int read = socket.read(target);
      if (read < 0) throw new EOFException("Incomplete broker frame");
      if (read == 0) await(SelectionKey.OP_READ, deadline);
    }
  }

  private void await(int interest, long deadline) throws IOException {
    checkDeadline(deadline);
    key.interestOps(interest);
    selector.select(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    selector.selectedKeys().clear();
  }

  private static void checkDeadline(long deadline) throws SocketTimeoutException {
    if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
      throw new SocketTimeoutException("Broker request timed out");
  }

  static JsonObject readObject(String text) throws IOException {
    try (var reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      JsonObject parsed = new JsonObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (parsed.has(name)) throw new IOException("Duplicate broker field");
        parsed.add(name, com.google.gson.JsonParser.parseReader(reader));
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing broker frame data");
      return parsed;
    } catch (IllegalStateException | com.google.gson.JsonParseException malformed) {
      throw new IOException("Malformed broker frame", malformed);
    }
  }

  @Override
  public void close() throws IOException {
    selector.close();
  }
}
