package io.github.zpkdxgames.plexonpanel.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogSafetyTest {
  @Test
  void configuredSecretsAreRedacted() {
    LogRedactor redactor = new LogRedactor(List.of("(?i)(token)\\s*[=:]\\s*\\S+"));

    assertEquals(
        "Connecting with <redacted>", redactor.redact("Connecting with token=super-secret"));
  }

  @Test
  void similarErrorsShareAStableFingerprint() {
    LogClassifier classifier = new LogClassifier();
    var first =
        classifier.classify(
            "[ERROR] Plugin failed at Example.java:123 for player"
                + " 90bfe0d1-d23a-4fa0-a050-98dd2b7819aa");
    var second =
        classifier.classify(
            "[ERROR] Plugin failed at Example.java:987 for player"
                + " 76e6d97e-7309-4c31-81fb-2ae512e94430");

    assertTrue(first.isProblem());
    assertNotNull(first.fingerprint());
    assertEquals(first.fingerprint(), second.fingerprint());
  }
}
