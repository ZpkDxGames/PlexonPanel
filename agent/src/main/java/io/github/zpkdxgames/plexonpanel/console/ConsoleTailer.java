package io.github.zpkdxgames.plexonpanel.console;

import io.github.zpkdxgames.plexonpanel.model.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConsoleTailer implements AutoCloseable {
  private static final int READ_BUFFER_BYTES = 64 * 1024;
  private static final int MAXIMUM_BYTES_PER_POLL = 256 * 1024;

  private final Path logPath;
  private final long pollIntervalMillis;
  private final int maximumLineBytes;
  private final boolean streamAll;
  private final boolean streamProblems;
  private final LogRedactor redactor;
  private final LogClassifier classifier;
  private final Consumer<ConsoleLine> consumer;
  private final Logger logger;
  private final Clock clock;
  private final ScheduledExecutorService executor;
  private final ByteArrayOutputStream partialLine = new ByteArrayOutputStream();
  private long position;
  private Object fileKey;
  private boolean initialized;
  private boolean truncated;

  public ConsoleTailer(
      Path logPath,
      long pollIntervalMillis,
      int maximumLineBytes,
      boolean streamAll,
      boolean streamProblems,
      LogRedactor redactor,
      Consumer<ConsoleLine> consumer,
      Logger logger) {
    this(
        logPath,
        pollIntervalMillis,
        maximumLineBytes,
        streamAll,
        streamProblems,
        redactor,
        consumer,
        logger,
        Clock.systemUTC());
  }

  ConsoleTailer(
      Path logPath,
      long pollIntervalMillis,
      int maximumLineBytes,
      boolean streamAll,
      boolean streamProblems,
      LogRedactor redactor,
      Consumer<ConsoleLine> consumer,
      Logger logger,
      Clock clock) {
    this.logPath = Objects.requireNonNull(logPath, "logPath");
    this.pollIntervalMillis = pollIntervalMillis;
    this.maximumLineBytes = maximumLineBytes;
    this.streamAll = streamAll;
    this.streamProblems = streamProblems;
    this.redactor = Objects.requireNonNull(redactor, "redactor");
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.clock = clock;
    this.classifier = new LogClassifier();
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("plexonpanel-console-tail"));
  }

  public void start() {
    executor.scheduleWithFixedDelay(
        this::pollSafely, 0L, pollIntervalMillis, TimeUnit.MILLISECONDS);
  }

  private void pollSafely() {
    try {
      poll();
    } catch (Exception error) {
      logger.log(Level.FINE, "Unable to read the Paper console log", error);
    }
  }

  void poll() throws IOException {
    if (!Files.isRegularFile(logPath)) {
      return;
    }
    BasicFileAttributes attributes = Files.readAttributes(logPath, BasicFileAttributes.class);
    Object currentFileKey =
        attributes.fileKey() == null ? attributes.creationTime() : attributes.fileKey();
    long size = attributes.size();

    if (!initialized) {
      initialized = true;
      fileKey = currentFileKey;
      position = size;
      return;
    }

    if (!Objects.equals(fileKey, currentFileKey) || size < position) {
      fileKey = currentFileKey;
      position = 0L;
      partialLine.reset();
      truncated = false;
    }
    if (size == position) {
      return;
    }

    int remainingBudget = MAXIMUM_BYTES_PER_POLL;
    try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
      channel.position(position);
      ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
      while (remainingBudget > 0) {
        buffer.clear();
        buffer.limit(Math.min(buffer.capacity(), remainingBudget));
        int read = channel.read(buffer);
        if (read <= 0) {
          break;
        }
        position += read;
        remainingBudget -= read;
        buffer.flip();
        consume(buffer);
      }
    }
  }

  private void consume(ByteBuffer buffer) {
    while (buffer.hasRemaining()) {
      byte value = buffer.get();
      if (value == '\n') {
        emitLine();
        partialLine.reset();
        truncated = false;
        continue;
      }
      if (partialLine.size() < maximumLineBytes) {
        partialLine.write(value);
      } else {
        truncated = true;
      }
    }
  }

  private void emitLine() {
    byte[] bytes = partialLine.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    String raw = new String(bytes, 0, length, StandardCharsets.UTF_8);
    if (raw.isBlank() || raw.contains("[PlexonPanel]")) {
      return;
    }
    String content = redactor.redact(raw);
    LogClassifier.Classification classification = classifier.classify(content);
    if (!streamAll && (!streamProblems || !classification.isProblem())) {
      return;
    }
    consumer.accept(
        new ConsoleLine(
            clock.instant().toString(),
            classification.level(),
            content,
            classification.fingerprint(),
            truncated));
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
