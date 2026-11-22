package io.github.zpkdxgames.plexonpanel.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerLogDecoderTest {
  private static final Instant FIRST = Instant.parse("2026-11-21T12:00:00Z");
  private static final Instant NOW = FIRST.plusSeconds(60);

  @Test
  void reassemblesBothDockerStreamsAndBoundsReplayByTimestamp() throws IOException {
    byte[] line = utf8(FIRST + " привет 🦊\n");
    byte[] response = frames(
        frame(1, Arrays.copyOfRange(line, 0, line.length - 3)),
        frame(2, utf8(FIRST.plusNanos(1) + " error\n")),
        frame(1, Arrays.copyOfRange(line, line.length - 3, line.length)),
        frame(1, utf8(FIRST.plusNanos(2) + " message\n")));

    var all = DockerLogDecoder.decode(response, false, null, NOW, 3);
    assertEquals(List.of("stdout", "stderr", "stdout"), all.stream().map(BedrockLogLine::stream).toList());
    assertEquals(List.of("привет 🦊", "error", "message"), all.stream().map(BedrockLogLine::content).toList());
    assertEquals(all.subList(1, 3), DockerLogDecoder.decode(response, false, FIRST.plusNanos(1), NOW, 2));
    assertEquals(all.subList(2, 3), DockerLogDecoder.decode(response, false, null, NOW, 1));
  }

  @Test
  void removesTerminalControlsAndRedactsAddressesCredentialsAndXuidsBeforeTruncating() throws IOException {
    String lines = """
        %s \u001b[31mTeam\u001b[0m \u001b]8;;https://local.example/hidden\u0007link\u001b]8;;\u0007 \u202aשלום\u202c 🦊
        %s xuid: 2533274999999999 peer=192.0.2.1 IPv6=[2001:db8::51] token=exampleToken
        %s config {"password":"high entropy sample \\\" with quotes"} authorization: Bearer fakeCredential
        %s -----BEGIN PRIVATE KEY-----
        %s shortTestPayload
        %s -----END PRIVATE KEY-----
        %s %s
        """.formatted(FIRST, FIRST.plusNanos(1), FIRST.plusNanos(2), FIRST.plusNanos(3), FIRST.plusNanos(4),
        FIRST.plusNanos(5), FIRST.plusNanos(6), "🦊".repeat(513));
    var decoded = DockerLogDecoder.decode(utf8(lines), true, null, NOW, 10);
    assertEquals("Team link שלום 🦊", decoded.getFirst().content());
    String combined = decoded.toString();
    for (String undisclosed : List.of("local.example", "2533274999999999", "192.0.2.1", "2001:db8::51",
        "exampleToken", "entropy", "fakeCredential", "shortTestPayload")) {
      assertFalse(combined.contains(undisclosed));
    }
    assertTrue(decoded.getLast().truncated());
    assertEquals("🦊".repeat(512), decoded.getLast().content());
  }

  @Test
  void rejectsMalformedLogFramesTimesAndEncoding() {
    assertThrows(IOException.class, () -> DockerLogDecoder.decode(new byte[7], false, null, NOW, 1));
    assertThrows(IOException.class, () -> DockerLogDecoder.decode(frame(3, utf8("daemon error")), false, null, NOW, 1));
    assertThrows(IOException.class, () -> DockerLogDecoder.decode(utf8("missing time\n"), true, null, NOW, 1));
    assertThrows(IOException.class, () -> DockerLogDecoder.decode(utf8(NOW.plusNanos(1) + " future\n"), true, null, NOW, 1));
    byte[] malformedUtf8 = frames(utf8(FIRST + " "), new byte[] {(byte) 0xff});
    assertThrows(IOException.class, () -> DockerLogDecoder.decode(malformedUtf8, true, null, NOW, 1));
  }

  private static byte[] frame(int stream, byte[] data) {
    return ByteBuffer.allocate(8 + data.length).put((byte) stream).put(new byte[3]).putInt(data.length).put(data).array();
  }

  private static byte[] frames(byte[]... frames) {
    var output = new ByteArrayOutputStream();
    for (byte[] frame : frames) output.writeBytes(frame);
    return output.toByteArray();
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
