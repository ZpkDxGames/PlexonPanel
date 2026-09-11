package io.github.zpkdxgames.plexonpanel.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.chats.ChatChannel;
import com.antondev.chats.api.PlexonChatEvent;
import io.github.zpkdxgames.plexonpanel.model.ChatRecord;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PanelChatsIntegrationReliabilityContractTest {
  @Test
  void acceptedPlexonChatsGlobalEventProducesExactlyOneSanitizedPanelRecord() {
    AtomicInteger sends = new AtomicInteger();
    AtomicReference<String> type = new AtomicReference<>();
    AtomicReference<ChatRecord> record = new AtomicReference<>();
    AtomicReference<MessagePriority> priority = new AtomicReference<>();
    MessageSink sink =
        (messageType, body, messagePriority) -> {
          sends.incrementAndGet();
          type.set(messageType);
          record.set((ChatRecord) body);
          priority.set(messagePriority);
          return true;
        };

    PlexonChatsListener listener = new PlexonChatsListener(sink);
    UUID playerId = UUID.randomUUID();
    PlexonChatEvent event =
        new PlexonChatEvent(
            player(playerId, "Tonim"),
            ChatChannel.GLOBAL,
            "<red>untrusted raw markup</red>",
            Component.text("visible message"),
            Set.of());

    listener.onPlexonChat(event);

    assertEquals(1, sends.get());
    assertEquals("chat.message", type.get());
    assertEquals(MessagePriority.EVENT, priority.get());
    assertNotNull(record.get());
    assertEquals("GLOBAL", record.get().channel());
    assertEquals(playerId.toString(), record.get().senderUuid());
    assertEquals("Tonim", record.get().senderName());
    assertEquals("visible message", record.get().content());
    assertEquals("PLEXONCHATS", record.get().source());
  }

  @Test
  void localPlexonChatsEventNeverEntersPanelGlobalStream() {
    AtomicInteger sends = new AtomicInteger();
    MessageSink sink =
        (messageType, body, messagePriority) -> {
          sends.incrementAndGet();
          return true;
        };
    PlexonChatsListener listener = new PlexonChatsListener(sink);
    listener.onPlexonChat(
        new PlexonChatEvent(
            player(UUID.randomUUID(), "LocalUser"),
            ChatChannel.LOCAL,
            "nearby",
            Component.text("nearby"),
            Set.of()));
    assertEquals(0, sends.get());
  }

  @Test
  void lifecycleDiscoveryIsEventDrivenAndVanillaIsOnlyFallback() throws Exception {
    String stream =
        Files.readString(
            Path.of("src/main/java/io/github/zpkdxgames/plexonpanel/chat/ChatStreamService.java"));
    String listener =
        Files.readString(
            Path.of("src/main/java/io/github/zpkdxgames/plexonpanel/chat/PlexonChatsListener.java"));

    assertTrue(stream.contains("PluginEnableEvent"));
    assertTrue(stream.contains("PluginDisableEvent"));
    assertTrue(stream.contains("ServiceRegisterEvent"));
    assertTrue(stream.contains("ServiceUnregisterEvent"));
    assertTrue(stream.contains("PLEXON_API_SERVICE = \"com.antondev.chats.api.PlexonChatsAPI\""));
    assertTrue(stream.contains("PLEXON_API_EVENT = \"com.antondev.chats.api.PlexonChatEvent\""));
    assertTrue(stream.contains("!settings.captureVanillaGlobal() || plexonListener != null"));
    assertFalse(stream.contains("runTaskTimer"));
    assertFalse(stream.contains("scheduleSyncRepeatingTask"));
    assertFalse(stream.contains("PlexonPublicChatEvent"));
    assertFalse(stream.contains("PlexonChatsApi"));

    assertTrue(listener.contains("ignoreCancelled = true"));
    assertTrue(listener.contains("event.getChannel() != ChatChannel.GLOBAL"));
    assertEquals(1, occurrences(listener, "sink.send("));
    assertFalse(listener.contains("WebSocket"));
    assertFalse(listener.contains("HttpClient"));
    assertFalse(listener.contains("CompletableFuture"));
  }

  @Test
  void optionalMetadataAndPanelServiceLifecycleRemainDeterministic() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/plugin.yml"));
    String panel =
        Files.readString(Path.of("src/main/java/io/github/zpkdxgames/plexonpanel/PlexonPanelPlugin.java"));

    assertTrue(yaml.contains("softdepend:"));
    assertTrue(yaml.contains("  - PlexonChats"));
    assertFalse(yaml.lines().anyMatch(line -> line.trim().equals("depend:")));
    assertEquals(1, occurrences(panel, ".register(PlexonPanelAPI.class"));
    assertTrue(panel.contains("unregister(PlexonPanelAPI.class, current)"));
    assertTrue(panel.contains("if (panelApi != null)"));
    assertTrue(panel.contains("panelApi = null"));
  }

  @Test
  void compileOnlySnapshotMatchesAcceptedChatsNamesAndOutboundPathStaysBounded() throws Exception {
    Path apiRoot = Path.of("../integrations/plexonchats-api/src/main/java/com/antondev/chats");
    assertTrue(Files.exists(apiRoot.resolve("ChatChannel.java")));
    assertTrue(Files.exists(apiRoot.resolve("api/PlexonChatEvent.java")));
    assertTrue(Files.exists(apiRoot.resolve("api/PlexonChatsAPI.java")));
    assertFalse(Files.exists(apiRoot.resolve("api/PlexonChatsApi.java")));
    assertFalse(Files.exists(apiRoot.resolve("api/event/PlexonPublicChatEvent.java")));

    String event = Files.readString(apiRoot.resolve("api/PlexonChatEvent.java"));
    assertTrue(event.contains("Never fired for PMs"));
    assertTrue(event.contains("implements Cancellable"));

    String gateway =
        Files.readString(
            Path.of("src/main/java/io/github/zpkdxgames/plexonpanel/transport/GatewayClient.java"));
    assertTrue(gateway.contains("ArrayBlockingQueue<OutboundMessage>"));
    assertTrue(gateway.contains("outbound.offer(message)"));
  }

  private static int occurrences(String source, String token) {
    int count = 0;
    int offset = 0;
    while ((offset = source.indexOf(token, offset)) >= 0) {
      count++;
      offset += token.length();
    }
    return count;
  }

  private static Player player(UUID id, String name) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
              return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
  }
}
