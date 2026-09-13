package io.github.zpkdxgames.plexonpanel.console;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConsoleSecurityTest {
  @Test
  void redactsBuiltInCredentialShapesBeforeTransport() {
    ConsoleRedactor redactor = new ConsoleRedactor(List.of());
    assertEquals(
        "Authorization: Bearer <redacted>",
        redactor.redact("Authorization: Bearer secret-token-value"));
    assertEquals("password=<redacted>", redactor.redact("password=hunter2"));
    assertEquals("api_key: <redacted>", redactor.redact("api_key: abc123"));
    assertEquals(
        "<redacted private key material>",
        redactor.redact("-----BEGIN PRIVATE KEY-----"));
  }

  @Test
  void appliesConfiguredRedactionAndRejectsInvalidPatterns() {
    ConsoleRedactor redactor = new ConsoleRedactor(List.of("session-[A-Za-z0-9]+"));
    assertEquals("connected <redacted>", redactor.redact("connected session-AbC123"));
    assertThrows(IllegalArgumentException.class, () -> new ConsoleRedactor(List.of("[")));
    assertThrows(IllegalArgumentException.class, () -> new ConsoleRedactor(List.of(" ")));
  }

  @Test
  void classifierUsesStrongestJournalOrContentSeverity() {
    ConsoleClassifier classifier = new ConsoleClassifier();
    assertEquals("INFO", classifier.classify("Server started", 6).level());
    assertEquals("WARN", classifier.classify("Server started", 4).level());
    assertEquals("ERROR", classifier.classify("[ERROR] plugin failed", 6).level());
    assertEquals("ERROR", classifier.classify("ordinary text", 3).level());
    assertEquals("DEBUG", classifier.classify("[DEBUG] trace", 7).level());
  }

  @Test
  void problemFingerprintIsStableAcrossVolatileIdsAndNumbers() {
    ConsoleClassifier classifier = new ConsoleClassifier();
    String first =
        classifier
            .classify(
                "[WARN] Player 550e8400-e29b-41d4-a716-446655440000 failed at Example.java:42 after 123 ms")
            .fingerprint();
    String second =
        classifier
            .classify(
                "[WARN] Player 123e4567-e89b-42d3-a456-426614174000 failed at Example.java:99 after 456 ms")
            .fingerprint();
    assertNotNull(first);
    assertEquals(first, second);
    assertNull(classifier.classify("normal info line").fingerprint());
  }
}
