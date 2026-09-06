package io.github.zpkdxgames.plexonpanel.presence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Thread-confined, bounded JSONL presence store. Callers run it on the single presence worker; the
 * synchronized methods are an additional guard for tests and diagnostics.
 */
public final class PresenceJournal {
  public enum Status {
    ALL,
    ONLINE,
    OFFLINE
  }

  public record Query(
      String text, Status status, Instant from, Instant to, String cursor, int limit) {}

  public record Page(
      List<PresenceRecord> entries,
      String nextCursor,
      boolean hasMore,
      boolean boundedWindow,
      String capturedAt,
      boolean historyEnabled) {}

  public record OpenSession(String sessionId, String startedAt, String name) {}

  private record SummaryFile(int version, List<PlayerPresenceSummary> players) {}

  private record Cursor(
      String before, String eventId, String windowFrom, String windowTo, String filter) {}

  private static final int FORMAT_VERSION = 1;
  private static final int MAX_RECORD_BYTES = 4096;
  private static final int MAX_CURSOR_BYTES = 512;
  private static final int MAX_JOURNAL_FILES = 512;
  private static final Pattern JOURNAL_NAME =
      Pattern.compile("presence-[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-[0-9]{1,20})?\\.jsonl");
  private static final Comparator<PresenceRecord> EVENT_ORDER =
      Comparator.comparing((PresenceRecord value) -> Instant.parse(value.observedAt()))
          .thenComparing(PresenceRecord::eventId);

  private final Path directory;
  private final Path summaryPath;
  private final PanelSettings.PlayerHistory settings;
  private final Clock clock;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
  private final Map<String, PlayerPresenceSummary> summaries = new LinkedHashMap<>();
  private final Set<String> eventIds = new LinkedHashSet<>();
  private int eventCount;
  private boolean boundedOnLoad;
  private LocalDate lastCleanupDay;

  public PresenceJournal(
      Path pluginDataDirectory, PanelSettings.PlayerHistory settings, Clock clock)
      throws IOException {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    Path base = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toAbsolutePath().normalize();
    Files.createDirectories(base);
    Path candidate = base.resolve("presence").normalize();
    if (!candidate.startsWith(base)) throw new IOException("Presence directory escaped plugin data");
    rejectSymlinkComponents(base, candidate);
    Files.createDirectories(candidate);
    rejectSymlinkComponents(base, candidate);
    Path baseReal = base.toRealPath();
    Path candidateReal = candidate.toRealPath();
    if (!candidateReal.startsWith(baseReal))
      throw new IOException("Presence directory must remain beneath plugin data");
    this.directory = candidateReal;
    this.summaryPath = directory.resolve("players-summary.json");
    setPermissions(directory, "rwx------");
  }

  public synchronized Map<String, OpenSession> initialize(
      Collection<ObservedPlayer> currentlyOnline) throws IOException {
    summaries.clear();
    eventIds.clear();
    eventCount = 0;
    boundedOnLoad = false;
    cleanRetention();
    Map<String, PlayerPresenceSummary> disk = loadSummary();
    List<PresenceRecord> retained = loadRetainedRecords();
    retained.sort(EVENT_ORDER);
    for (PresenceRecord record : retained) apply(record, null);
    for (PlayerPresenceSummary value : disk.values())
      summaries.merge(value.uuid(), value, PresenceJournal::mergeSummary);

    Map<String, ObservedPlayer> online = new HashMap<>();
    for (ObservedPlayer value : currentlyOnline) {
      validateObserved(value);
      online.put(value.uuid(), value);
    }
    Instant now = clock.instant();
    for (PlayerPresenceSummary value : List.copyOf(summaries.values())) {
      if (!value.currentlyOnline() || online.containsKey(value.uuid())) continue;
      PresenceRecord orphan =
          new PresenceRecord(
              UUID.randomUUID().toString(),
              value.currentSessionId(),
              value.uuid(),
              value.name(),
              PresenceRecord.State.LEFT,
              now.toString(),
              value.currentSessionStartedAt(),
              null,
              null,
              PresenceTermination.UNKNOWN_DISCONNECT);
      appendAndApply(orphan, value.totalPlayTimeMillis());
    }

    Map<String, OpenSession> open = new HashMap<>();
    for (ObservedPlayer observed : online.values()) {
      PlayerPresenceSummary previous = summaries.get(observed.uuid());
      if (previous == null && summaries.size() >= settings.maximumPlayerSummaries())
        throw new IOException("Player presence summary capacity exceeded");
      String sessionId;
      String startedAt;
      if (previous != null && previous.currentlyOnline() && previous.currentSessionId() != null) {
        sessionId = previous.currentSessionId();
        startedAt = previous.currentSessionStartedAt();
      } else {
        sessionId = UUID.randomUUID().toString();
        startedAt = observed.sessionStartedAt();
      }
      String firstSeen =
          previous == null
              ? observed.firstSeenAt()
              : earlier(previous.firstSeenAt(), observed.firstSeenAt());
      summaries.put(
          observed.uuid(),
          new PlayerPresenceSummary(
              observed.uuid(),
              observed.name(),
              firstSeen,
              previous == null || previous.lastLoginAt() == null
                  ? startedAt
                  : later(previous.lastLoginAt(), startedAt),
              previous == null ? null : previous.lastLogoutAt(),
              startedAt,
              previous == null ? null : previous.lastSessionDurationMillis(),
              max(previous == null ? null : previous.totalPlayTimeMillis(), observed.totalPlayTimeMillis()),
              true,
              PresenceTermination.OPEN,
              sessionId,
              now.toString()));
      open.put(observed.uuid(), new OpenSession(sessionId, startedAt, observed.name()));
    }
    writeSummary();
    return Map.copyOf(open);
  }

  public synchronized boolean appendAndApply(PresenceRecord record, Long totalPlayTimeMillis)
      throws IOException {
    return appendAndApply(record, totalPlayTimeMillis, record.sessionStartedAt());
  }

  public synchronized boolean appendAndApply(
      PresenceRecord record, Long totalPlayTimeMillis, String firstSeenAt) throws IOException {
    validateRecord(record);
    Instant.parse(firstSeenAt);
    if (eventIds.contains(record.eventId())) return false;
    if (!summaries.containsKey(record.uuid())
        && summaries.size() >= settings.maximumPlayerSummaries())
      throw new IOException("Player presence summary capacity exceeded");
    byte[] bytes = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Presence record exceeds bound");
    maybeCleanForNewDay();
    Path active = activeJournal(Instant.parse(record.observedAt()));
    if (Files.exists(active, LinkOption.NOFOLLOW_LINKS)
        && Files.size(active) + bytes.length > settings.maximumJournalFileBytes()) {
      rotate(active);
    }
    createRestrictedFile(active);
    try (FileChannel channel =
        FileChannel.open(
            active,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    rememberEvent(record.eventId());
    eventCount++;
    apply(record, totalPlayTimeMillis, firstSeenAt);
    if (eventCount > settings.maximumEvents()) enforceMaximumEvents();
    writeSummary();
    return true;
  }

  public synchronized PlayerPresenceSummary summary(String uuid) {
    return summaries.get(uuid);
  }

  public synchronized Map<String, PlayerPresenceSummary> summaries() {
    return Map.copyOf(summaries);
  }

  public synchronized Page query(Query query) throws IOException {
    Objects.requireNonNull(query, "query");
    String text = query.text() == null ? "" : query.text().strip().toLowerCase(Locale.ROOT);
    if (text.length() > 64 || text.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid query");
    Status status = query.status() == null ? Status.ALL : query.status();
    int limit = query.limit();
    if (limit < 1 || limit > settings.maximumPageSize())
      throw new IllegalArgumentException("Invalid limit");
    Instant capturedAt = clock.instant();
    // Retention is enforced by UTC journal day. A day-aligned query window keeps an opaque
    // pagination cursor stable while the wall clock advances between pages.
    Instant retentionStart =
        LocalDate.ofInstant(capturedAt, ZoneOffset.UTC)
            .minusDays(settings.retentionDays() - 1L)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    Cursor cursor = decodeCursor(query.cursor(), retentionStart, capturedAt);
    Instant requestedFrom = query.from();
    if (requestedFrom == null && cursor != null) requestedFrom = Instant.parse(cursor.windowFrom());
    boolean bounded = boundedOnLoad || requestedFrom != null && requestedFrom.isBefore(retentionStart);
    Instant from = requestedFrom == null || requestedFrom.isBefore(retentionStart) ? retentionStart : requestedFrom;
    Instant to =
        query.to() == null
            ? cursor == null ? capturedAt : Instant.parse(cursor.windowTo())
            : query.to();
    if (to.isBefore(from) || to.isAfter(capturedAt.plusSeconds(300)))
      throw new IllegalArgumentException("Invalid time range");
    String filter = filterHash(text, status, from, to);
    if (cursor != null
        && (!cursor.windowFrom().equals(from.toString())
            || !cursor.windowTo().equals(to.toString())
            || !cursor.filter().equals(filter)))
      throw new IllegalArgumentException("Cursor does not match the query");
    Comparator<PresenceRecord> ascending = EVENT_ORDER;
    PriorityQueue<PresenceRecord> newest = new PriorityQueue<>(ascending);
    long budget = settings.queryByteBudget();
    List<Path> paths = journalFilesNewestFirst();
    if (paths.size() >= MAX_JOURNAL_FILES) bounded = true;
    for (Path path : paths) {
      long bytes = Files.size(path);
      if (bytes > budget) {
        bounded = true;
        break;
      }
      budget -= bytes;
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            bounded = true;
            continue;
          }
          PresenceRecord record;
          try {
            record = gson.fromJson(line, PresenceRecord.class);
            validateRecord(record);
          } catch (RuntimeException invalid) {
            bounded = true;
            continue;
          }
          Instant observed = Instant.parse(record.observedAt());
          if (observed.isBefore(from) || observed.isAfter(to)) continue;
          if (cursor != null
              && (observed.isAfter(Instant.parse(cursor.before()))
                  || observed.equals(Instant.parse(cursor.before()))
                      && record.eventId().compareTo(cursor.eventId()) >= 0)) continue;
          if (!text.isEmpty()
              && !record.name().toLowerCase(Locale.ROOT).contains(text)
              && !record.uuid().toLowerCase(Locale.ROOT).contains(text)) continue;
          if (status == Status.ONLINE && record.state() != PresenceRecord.State.JOINED
              || status == Status.OFFLINE && record.state() != PresenceRecord.State.LEFT) continue;
          newest.add(record);
          if (newest.size() > limit + 1) newest.remove();
        }
      }
    }
    List<PresenceRecord> entries = new ArrayList<>(newest);
    entries.sort(ascending.reversed());
    boolean hasMore = entries.size() > limit;
    if (hasMore) entries = new ArrayList<>(entries.subList(0, limit));
    String nextCursor =
        hasMore && !entries.isEmpty() ? encodeCursor(entries.getLast(), from, to, filter) : null;
    return new Page(
        List.copyOf(entries), nextCursor, hasMore, bounded, capturedAt.toString(), true);
  }

  public Path directory() {
    return directory;
  }

  private Map<String, PlayerPresenceSummary> loadSummary() throws IOException {
    if (!Files.exists(summaryPath, LinkOption.NOFOLLOW_LINKS)) return Map.of();
    if (Files.isSymbolicLink(summaryPath) || !Files.isRegularFile(summaryPath, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsafe presence summary path");
    if (Files.size(summaryPath) > 32L * 1024L * 1024L)
      throw new IOException("Presence summary exceeds bound");
    SummaryFile file;
    try {
      file = gson.fromJson(Files.readString(summaryPath, StandardCharsets.UTF_8), SummaryFile.class);
    } catch (JsonParseException invalid) {
      throw new IOException("Invalid presence summary", invalid);
    }
    if (file == null
        || file.version() != FORMAT_VERSION
        || file.players() == null
        || file.players().size() > settings.maximumPlayerSummaries())
      throw new IOException("Invalid presence summary");
    Map<String, PlayerPresenceSummary> result = new LinkedHashMap<>();
    for (PlayerPresenceSummary value : file.players()) {
      validateSummary(value);
      if (result.put(value.uuid(), value) != null)
        throw new IOException("Duplicate player presence summary");
    }
    return result;
  }

  private List<PresenceRecord> loadRetainedRecords() throws IOException {
    List<Path> paths = journalFilesNewestFirst();
    List<PresenceRecord> records = new ArrayList<>();
    long budget = settings.queryByteBudget();
    for (Path path : paths) {
      long size = Files.size(path);
      if (size > budget) {
        boundedOnLoad = true;
        break;
      }
      budget -= size;
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            boundedOnLoad = true;
            continue;
          }
          try {
            PresenceRecord record = gson.fromJson(line, PresenceRecord.class);
            validateRecord(record);
            if (eventIds.add(record.eventId())) records.add(record);
          } catch (RuntimeException invalid) {
            // A truncated final JSONL line must not discard earlier complete observations.
            boundedOnLoad = true;
          }
        }
      }
    }
    records.sort(EVENT_ORDER.reversed());
    if (records.size() > settings.maximumEvents()) {
      records = new ArrayList<>(records.subList(0, settings.maximumEvents()));
      boundedOnLoad = true;
    }
    eventIds.clear();
    records.forEach(record -> rememberEvent(record.eventId()));
    eventCount = records.size();
    return records;
  }

  private void apply(PresenceRecord record, Long totalPlayTimeMillis) {
    apply(record, totalPlayTimeMillis, record.sessionStartedAt());
  }

  private void apply(PresenceRecord record, Long totalPlayTimeMillis, String observedFirstSeenAt) {
    PlayerPresenceSummary previous = summaries.get(record.uuid());
    String firstSeen =
        previous == null
            ? observedFirstSeenAt
            : earlier(previous.firstSeenAt(), observedFirstSeenAt);
    Long total = max(previous == null ? null : previous.totalPlayTimeMillis(), totalPlayTimeMillis);
    if (record.state() == PresenceRecord.State.JOINED) {
      summaries.put(
          record.uuid(),
          new PlayerPresenceSummary(
              record.uuid(),
              record.name(),
              firstSeen,
              record.observedAt(),
              previous == null ? null : previous.lastLogoutAt(),
              record.sessionStartedAt(),
              previous == null ? null : previous.lastSessionDurationMillis(),
              total,
              true,
              PresenceTermination.OPEN,
              record.sessionId(),
              record.observedAt()));
      return;
    }
    String lastDefinitelyObserved =
        record.termination() == PresenceTermination.UNKNOWN_DISCONNECT
            ? previous == null ? record.sessionStartedAt() : previous.lastDefinitelyObservedAt()
            : record.observedAt();
    summaries.put(
        record.uuid(),
        new PlayerPresenceSummary(
            record.uuid(),
            record.name(),
            firstSeen,
            previous == null ? record.sessionStartedAt() : previous.lastLoginAt(),
            record.sessionEndedAt() == null
                ? previous == null ? null : previous.lastLogoutAt()
                : record.sessionEndedAt(),
            null,
            record.sessionDurationMillis() == null
                ? previous == null ? null : previous.lastSessionDurationMillis()
                : record.sessionDurationMillis(),
            total,
            false,
            record.termination(),
            null,
            lastDefinitelyObserved));
  }

  private void writeSummary() throws IOException {
    if (summaries.size() > settings.maximumPlayerSummaries())
      throw new IOException("Player presence summary capacity exceeded");
    List<PlayerPresenceSummary> ordered =
        summaries.values().stream()
            .sorted(Comparator.comparing(PlayerPresenceSummary::uuid))
            .toList();
    AtomicFiles.writeUtf8(summaryPath, gson.toJson(new SummaryFile(FORMAT_VERSION, ordered)) + "\n");
    setPermissions(summaryPath, "rw-------");
  }

  private void enforceMaximumEvents() throws IOException {
    int excess = eventCount - settings.maximumEvents();
    if (excess <= 0) return;
    List<Path> paths = journalFilesOldestFirst();
    for (Path path : paths) {
      List<String> valid = new ArrayList<>();
      try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank() || line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES)
            continue;
          try {
            PresenceRecord record = gson.fromJson(line, PresenceRecord.class);
            validateRecord(record);
            valid.add(gson.toJson(record));
          } catch (RuntimeException ignored) {
          }
        }
      }
      if (valid.size() <= excess && paths.size() > 1) {
        Files.delete(path);
        eventCount -= valid.size();
        excess -= valid.size();
      } else {
        int remove = Math.min(excess, valid.size());
        String replacement =
            valid.subList(remove, valid.size()).stream().map(value -> value + "\n").reduce("", String::concat);
        AtomicFiles.writeUtf8(path, replacement);
        setPermissions(path, "rw-------");
        eventCount -= remove;
        break;
      }
      if (excess <= 0) break;
    }
  }

  private void cleanRetention() throws IOException {
    LocalDate oldest =
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.retentionDays() - 1L);
    for (Path path : journalFilesOldestFirst()) {
      LocalDate day = journalDay(path);
      if (day != null && day.isBefore(oldest)) Files.delete(path);
    }
    lastCleanupDay = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private void maybeCleanForNewDay() throws IOException {
    LocalDate current = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (!current.equals(lastCleanupDay)) cleanRetention();
  }

  private Path activeJournal(Instant observedAt) {
    String day = LocalDate.ofInstant(observedAt, ZoneOffset.UTC).toString();
    return directory.resolve("presence-" + day + ".jsonl");
  }

  private void rotate(Path active) throws IOException {
    if (Files.isSymbolicLink(active)) throw new IOException("Unsafe presence journal path");
    String filename = active.getFileName().toString();
    String stem = filename.substring(0, filename.length() - ".jsonl".length());
    long suffix = clock.millis();
    Path rotated;
    do {
      rotated = directory.resolve(stem + "-" + suffix++ + ".jsonl");
    } while (Files.exists(rotated, LinkOption.NOFOLLOW_LINKS));
    Files.move(active, rotated, StandardCopyOption.ATOMIC_MOVE);
    setPermissions(rotated, "rw-------");
  }

  private void createRestrictedFile(Path path) throws IOException {
    if (Files.isSymbolicLink(path)) throw new IOException("Unsafe presence journal path");
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid presence journal path");
      return;
    }
    try {
      Files.createFile(
          path,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException unsupported) {
      try {
        Files.createFile(path);
      } catch (FileAlreadyExistsException ignored) {
      }
    } catch (FileAlreadyExistsException ignored) {
    }
  }

  private List<Path> journalFilesNewestFirst() throws IOException {
    List<Path> result = journalFilesOldestFirst();
    result.sort(fileOrder().reversed());
    return result;
  }

  private List<Path> journalFilesOldestFirst() throws IOException {
    try (var stream = Files.list(directory)) {
      List<Path> result =
          stream
              .filter(path -> JOURNAL_NAME.matcher(path.getFileName().toString()).matches())
              .limit(MAX_JOURNAL_FILES + 1L)
              .toList();
      if (result.size() > MAX_JOURNAL_FILES) {
        boundedOnLoad = true;
        result = new ArrayList<>(result.subList(0, MAX_JOURNAL_FILES));
      } else {
        result = new ArrayList<>(result);
      }
      for (Path path : result)
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Unsafe presence journal file");
      result.sort(fileOrder());
      return result;
    }
  }

  private Comparator<Path> fileOrder() {
    return Comparator.comparing(
            (Path path) -> {
              try {
                return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
              } catch (IOException error) {
                return Instant.EPOCH;
              }
            })
        .thenComparing(path -> path.getFileName().toString());
  }

  private static LocalDate journalDay(Path path) {
    String name = path.getFileName().toString();
    try {
      return LocalDate.parse(name.substring("presence-".length(), "presence-".length() + 10));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private String encodeCursor(PresenceRecord last, Instant from, Instant to, String filter) {
    String json =
        gson.toJson(
            new Cursor(
                last.observedAt(), last.eventId(), from.toString(), to.toString(), filter));
    String encoded =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    if (encoded.length() > MAX_CURSOR_BYTES) throw new IllegalStateException("Cursor exceeds bound");
    return encoded;
  }

  private Cursor decodeCursor(String encoded, Instant retentionStart, Instant capturedAt) {
    if (encoded == null || encoded.isBlank()) return null;
    if (encoded.length() > MAX_CURSOR_BYTES || !encoded.matches("[A-Za-z0-9_-]+"))
      throw new IllegalArgumentException("Invalid cursor");
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(encoded);
      if (bytes.length > MAX_CURSOR_BYTES) throw new IllegalArgumentException("Invalid cursor");
      Cursor cursor = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Cursor.class);
      if (cursor == null
          || cursor.before() == null
          || cursor.eventId() == null
          || cursor.windowFrom() == null
          || cursor.windowTo() == null
          || cursor.filter() == null
          || cursor.filter().length() != 43)
        throw new IllegalArgumentException("Invalid cursor");
      Instant before = Instant.parse(cursor.before());
      Instant from = Instant.parse(cursor.windowFrom());
      Instant to = Instant.parse(cursor.windowTo());
      UUID.fromString(cursor.eventId());
      if (from.isBefore(retentionStart)
          || to.isBefore(from)
          || to.isAfter(capturedAt.plusSeconds(300))
          || before.isBefore(from)
          || before.isAfter(to)
          || before.isAfter(capturedAt.plusSeconds(300)))
        throw new IllegalArgumentException("Invalid cursor");
      return cursor;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid cursor", invalid);
    }
  }

  private static String filterHash(String text, Status status, Instant from, Instant to) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((text + "\n" + status + "\n" + from + "\n" + to).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void rememberEvent(String eventId) {
    eventIds.add(eventId);
    while (eventIds.size() > settings.maximumEvents()) {
      Iterator<String> iterator = eventIds.iterator();
      iterator.next();
      iterator.remove();
    }
  }

  private static PlayerPresenceSummary mergeSummary(
      PlayerPresenceSummary left, PlayerPresenceSummary right) {
    PlayerPresenceSummary newer = latest(left).compareTo(latest(right)) >= 0 ? left : right;
    PlayerPresenceSummary older = newer == left ? right : left;
    return new PlayerPresenceSummary(
        newer.uuid(),
        newer.name(),
        earlier(older.firstSeenAt(), newer.firstSeenAt()),
        newer.lastLoginAt() == null ? older.lastLoginAt() : newer.lastLoginAt(),
        newer.lastLogoutAt() == null ? older.lastLogoutAt() : newer.lastLogoutAt(),
        newer.currentSessionStartedAt(),
        newer.lastSessionDurationMillis() == null
            ? older.lastSessionDurationMillis()
            : newer.lastSessionDurationMillis(),
        max(older.totalPlayTimeMillis(), newer.totalPlayTimeMillis()),
        newer.currentlyOnline(),
        newer.lastTermination(),
        newer.currentSessionId(),
        newer.lastDefinitelyObservedAt());
  }

  private static Instant latest(PlayerPresenceSummary value) {
    Instant latest = Instant.EPOCH;
    for (String candidate :
        List.of(
            value.lastDefinitelyObservedAt() == null ? "1970-01-01T00:00:00Z" : value.lastDefinitelyObservedAt(),
            value.currentSessionStartedAt() == null ? "1970-01-01T00:00:00Z" : value.currentSessionStartedAt(),
            value.lastLogoutAt() == null ? "1970-01-01T00:00:00Z" : value.lastLogoutAt(),
            value.lastLoginAt() == null ? "1970-01-01T00:00:00Z" : value.lastLoginAt())) {
      Instant parsed = Instant.parse(candidate);
      if (parsed.isAfter(latest)) latest = parsed;
    }
    return latest;
  }

  private static void validateObserved(ObservedPlayer value) {
    if (value == null) throw new IllegalArgumentException("Invalid observed player");
    UUID.fromString(value.uuid());
    validateName(value.name());
    Instant.parse(value.sessionStartedAt());
    Instant.parse(value.firstSeenAt());
    if (Instant.parse(value.firstSeenAt()).isAfter(Instant.parse(value.sessionStartedAt()).plusSeconds(5)))
      throw new IllegalArgumentException("Invalid first seen time");
    if (value.totalPlayTimeMillis() != null && value.totalPlayTimeMillis() < 0)
      throw new IllegalArgumentException("Invalid play time");
  }

  private static void validateSummary(PlayerPresenceSummary value) {
    if (value == null) throw new IllegalArgumentException("Invalid player summary");
    UUID.fromString(value.uuid());
    validateName(value.name());
    Instant.parse(value.firstSeenAt());
    if (value.lastLoginAt() != null) Instant.parse(value.lastLoginAt());
    if (value.lastLogoutAt() != null) Instant.parse(value.lastLogoutAt());
    if (value.currentSessionStartedAt() != null) Instant.parse(value.currentSessionStartedAt());
    if (value.lastDefinitelyObservedAt() != null) Instant.parse(value.lastDefinitelyObservedAt());
    if (value.currentSessionId() != null) UUID.fromString(value.currentSessionId());
    if (value.lastSessionDurationMillis() != null && value.lastSessionDurationMillis() < 0)
      throw new IllegalArgumentException("Invalid session duration");
    if (value.totalPlayTimeMillis() != null && value.totalPlayTimeMillis() < 0)
      throw new IllegalArgumentException("Invalid play time");
    if (value.currentlyOnline()
        != (value.currentSessionId() != null && value.currentSessionStartedAt() != null))
      throw new IllegalArgumentException("Invalid open player summary");
    if (value.lastTermination() == null) throw new IllegalArgumentException("Invalid termination");
  }

  static void validateRecord(PresenceRecord value) {
    if (value == null) throw new IllegalArgumentException("Invalid presence record");
    UUID.fromString(value.eventId());
    UUID.fromString(value.sessionId());
    UUID.fromString(value.uuid());
    validateName(value.name());
    Instant observed = Instant.parse(value.observedAt());
    Instant started = Instant.parse(value.sessionStartedAt());
    if (started.isAfter(observed.plusSeconds(5)) || value.state() == null || value.termination() == null)
      throw new IllegalArgumentException("Invalid presence timestamps");
    if (value.state() == PresenceRecord.State.JOINED) {
      if (value.termination() != PresenceTermination.OPEN
          || value.sessionEndedAt() != null
          || value.sessionDurationMillis() != null)
        throw new IllegalArgumentException("Invalid joined record");
    } else if (value.termination() == PresenceTermination.OPEN) {
      throw new IllegalArgumentException("Invalid left termination");
    } else if (value.termination() == PresenceTermination.UNKNOWN_DISCONNECT) {
      if (value.sessionEndedAt() != null || value.sessionDurationMillis() != null)
        throw new IllegalArgumentException("Unknown disconnect cannot invent an end");
    } else {
      Instant ended = Instant.parse(value.sessionEndedAt());
      if (!ended.equals(observed)
          || ended.isBefore(started)
          || value.sessionDurationMillis() == null
          || value.sessionDurationMillis() < 0
          || value.sessionDurationMillis() != ended.toEpochMilli() - started.toEpochMilli())
        throw new IllegalArgumentException("Invalid closed session");
    }
  }

  private static void validateName(String name) {
    if (name == null || name.isBlank() || name.length() > 64 || name.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid player name");
    for (int i = 0; i < name.length(); i++)
      if (Character.isISOControl(name.charAt(i)))
        throw new IllegalArgumentException("Invalid player name");
  }

  private static String earlier(String left, String right) {
    if (left == null) return right;
    if (right == null) return left;
    return Instant.parse(left).isBefore(Instant.parse(right)) ? left : right;
  }

  private static String later(String left, String right) {
    if (left == null) return right;
    if (right == null) return left;
    return Instant.parse(left).isAfter(Instant.parse(right)) ? left : right;
  }

  private static Long max(Long left, Long right) {
    if (left == null) return right;
    if (right == null) return left;
    return Math.max(left, right);
  }

  private static void rejectSymlinkComponents(Path base, Path target) throws IOException {
    Path current = base;
    if (Files.isSymbolicLink(current)) throw new IOException("Plugin data must not be a symlink");
    Path relative = base.relativize(target);
    for (Path part : relative) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("Presence path contains a symlink");
    }
  }

  private static void setPermissions(Path path, String permissions) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    } catch (UnsupportedOperationException ignored) {
      // Windows development hosts do not expose POSIX permissions.
    }
  }
}
