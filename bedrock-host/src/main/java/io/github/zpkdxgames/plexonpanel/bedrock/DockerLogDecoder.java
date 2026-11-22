package io.github.zpkdxgames.plexonpanel.bedrock;

import io.github.zpkdxgames.plexonpanel.console.ConsoleRedactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Docker's log framing and removes terminal controls and sensitive values. */
final class DockerLogDecoder {
  private static final int MAX_CONTENT_CODEPOINTS = 512;
  private static final String BDS_PREFIX = "\\[[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(?:[.:][0-9]{1,9})? INFO\\] ";
  static final Pattern BDS_START = Pattern.compile(BDS_PREFIX + "Starting Server");
  static final Pattern BDS_VERSION = Pattern.compile(BDS_PREFIX + "Version: (1(?:\\.[0-9]{1,3}){2,3})");
  private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Za-z:])[a-fA-F0-9:]{2,}(?![0-9A-Za-z:])");
  private static final Pattern QUOTED_SECRET = Pattern.compile(
      "(?i)((?:password|passwd|token|secret|api[_-]?key|access[_-]?key)[\"']?\\s*[:=]\\s*)"
          + "(?:\"(?:[^\"\\\\]|\\\\.)*+\"?|'(?:[^'\\\\]|\\\\.)*+'?)");
  private static final Pattern PRIVATE_KEY_START = Pattern.compile("(?i)BEGIN [A-Z0-9 ]*PRIVATE KEY");
  private static final Pattern PRIVATE_KEY_END = Pattern.compile("(?i)END [A-Z0-9 ]*PRIVATE KEY");
  private static final ConsoleRedactor REDACTOR = new ConsoleRedactor(List.of(
      "(?i)authorization\\s*[:=]\\s*(?:basic|bearer)\\s+[^\\s,;]+",
      "(?i)\\bxuid[\"']?\\s*[:=]\\s*[^\\s,;]+",
      "(?<![0-9A-Za-z])[0-9]{15,20}(?![0-9A-Za-z])",
      "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])",
      "(?i)://[^\\s/@]+:[^\\s/@]+@",
      "(?<![0-9A-Za-z+/_-])[0-9A-Za-z+/_-]{32,}={0,2}(?![0-9A-Za-z+/_-])"));

  private final List<BedrockLogLine> lines = new ArrayList<>();
  private final Instant since;
  private final Instant now;

  private DockerLogDecoder(Instant since, Instant now) {
    this.since = since;
    this.now = now;
  }

  static List<BedrockLogLine> decode(byte[] body, boolean tty, Instant since, Instant now, int limit)
      throws IOException {
    DockerLogDecoder decoder = new DockerLogDecoder(since, now);
    ByteBuffer bytes = ByteBuffer.wrap(body);
    if (tty) {
      Pending combined = decoder.new Pending("combined");
      combined.accept(bytes, bytes.remaining());
      combined.flush();
    } else {
      Pending stdout = decoder.new Pending("stdout");
      Pending stderr = decoder.new Pending("stderr");
      while (bytes.hasRemaining()) {
        if (bytes.remaining() < 8) throw new IOException("Incomplete Docker log frame");
        byte stream = bytes.get();
        if ((stream != 1 && stream != 2) || bytes.get() != 0 || bytes.get() != 0 || bytes.get() != 0) {
          throw new IOException("Invalid Docker log stream frame");
        }
        int length = bytes.getInt();
        if (length < 0 || length > bytes.remaining()) throw new IOException("Invalid Docker log frame length");
        (stream == 1 ? stdout : stderr).accept(bytes, length);
      }
      stdout.flush();
      stderr.flush();
    }
    decoder.lines.sort(Comparator.comparing(BedrockLogLine::capturedAt));
    int start = Math.max(0, decoder.lines.size() - limit);
    return List.copyOf(decoder.lines.subList(start, decoder.lines.size()));
  }

  private final class Pending {
    private final String stream;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean privateKey;

    Pending(String stream) {
      this.stream = stream;
    }

    void accept(ByteBuffer source, int count) throws IOException {
      for (int i = 0; i < count; i++) {
        byte value = source.get();
        if (value == '\n') flush();
        else line.write(value);
      }
    }

    void flush() throws IOException {
      if (line.size() == 0) return;
      String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(line.toByteArray())).toString();
      line.reset();
      int separator = value.indexOf(' ');
      if (separator < 20 || separator > 35) throw new IOException("Missing Docker log timestamp");
      Instant time;
      try {
        time = Instant.parse(value.substring(0, separator));
      } catch (DateTimeParseException error) {
        throw new IOException("Invalid Docker log timestamp");
      }
      if (time.isAfter(now)) throw new IOException("Docker log timestamp is in the future");

      String text = clean(value.substring(separator + 1));
      if (PRIVATE_KEY_START.matcher(text).find()) privateKey = true;
      if (privateKey) {
        if (PRIVATE_KEY_END.matcher(text).find()) privateKey = false;
        text = "<redacted private key material>";
      } else if (!BDS_VERSION.matcher(text).matches()) {
        text = QUOTED_SECRET.matcher(text).replaceAll("$1<redacted>");
        text = redactIpv6(REDACTOR.redact(text));
      }
      if (since != null && time.isBefore(since)) return;
      boolean truncated = text.codePointCount(0, text.length()) > MAX_CONTENT_CODEPOINTS;
      if (truncated) text = text.substring(0, text.offsetByCodePoints(0, MAX_CONTENT_CODEPOINTS));
      lines.add(new BedrockLogLine(time, stream, text, truncated));
    }
  }

  private static String redactIpv6(String text) {
    Matcher matches = IPV6.matcher(text);
    StringBuilder output = new StringBuilder(text.length());
    while (matches.find()) {
      String candidate = matches.group();
      if (candidate.length() > 45 || candidate.indexOf(':') < 0) continue;
      try {
        InetAddress.ofLiteral(candidate);
        matches.appendReplacement(output, "<redacted>");
      } catch (IllegalArgumentException invalidAddress) {
        // Colons also appear in human-readable BDS timestamps and other identifiers.
      }
    }
    return matches.appendTail(output).toString();
  }

  private static String clean(String text) {
    StringBuilder output = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); ) {
      char value = text.charAt(i++);
      if (value == '\u001b') {
        if (i < text.length()) {
          char escape = text.charAt(i++);
          if (escape == '[') i = endCsi(text, i);
          else if ("]PX^_".indexOf(escape) >= 0) i = endControlString(text, i);
          else if (escape >= ' ' && escape <= '/') {
            while (i < text.length() && text.charAt(i) >= ' ' && text.charAt(i) <= '/') i++;
            if (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '~') i++;
          }
        }
      } else if (value == '\u009b') {
        i = endCsi(text, i);
      } else if ("\u0090\u0098\u009d\u009e\u009f".indexOf(value) >= 0) {
        i = endControlString(text, i);
      } else if (value == '\t' || value == '\u2028' || value == '\u2029') {
        output.append(' ');
      } else if (!Character.isISOControl(value) && !isBidiControl(value)) {
        output.append(value);
      }
    }
    return output.toString();
  }

  private static boolean isBidiControl(char value) {
    return value == '\u061c' || value == '\u200e' || value == '\u200f'
        || (value >= '\u202a' && value <= '\u202e') || (value >= '\u2066' && value <= '\u2069');
  }

  private static int endCsi(String text, int from) {
    int position = from;
    while (position < text.length()) {
      char value = text.charAt(position++);
      if (value >= '@' && value <= '~') break;
    }
    return position;
  }

  private static int endControlString(String text, int from) {
    int position = from;
    while (position < text.length()) {
      char value = text.charAt(position++);
      if (value == '\u0007' || value == '\u009c') break;
      if (value == '\u001b' && position < text.length() && text.charAt(position) == '\\') {
        return position + 1;
      }
    }
    return position;
  }
}
