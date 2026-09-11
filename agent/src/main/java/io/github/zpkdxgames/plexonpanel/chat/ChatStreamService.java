package io.github.zpkdxgames.plexonpanel.chat;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.model.ChatRecord;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChatStreamService implements Listener, AutoCloseable {
  private static final String PLEXON_CHATS = "PlexonChats";
  private static final String PLEXON_API_EVENT = "com.antondev.chats.api.PlexonChatEvent";
  private static final String PLEXON_API_SERVICE = "com.antondev.chats.api.PlexonChatsAPI";

  private final JavaPlugin plugin;
  private final PanelSettings.Chat settings;
  private final MessageSink sink;
  private final MiniMessage miniMessage = MiniMessage.miniMessage();
  private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
  private volatile PlexonChatsListener plexonListener;
  private boolean started;
  private boolean warnedMissingApi;
  private boolean warnedIncompatibleApi;

  public ChatStreamService(JavaPlugin plugin, PanelSettings.Chat settings, MessageSink sink) {
    this.plugin = plugin;
    this.settings = settings;
    this.sink = sink;
  }

  public void start() {
    if (started) {
      return;
    }
    started = true;
    if (!settings.streamEnabled() && !settings.allowDashboardSend()) {
      return;
    }
    if (settings.streamEnabled()) {
      // One listener owns vanilla fallback plus peer lifecycle discovery. No polling task is used.
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      if (settings.capturePlexonChatsGlobal()) {
        initializePlexonChatsIntegration();
      }
    }
  }

  private void initializePlexonChatsIntegration() {
    if (!settings.streamEnabled() || !settings.capturePlexonChatsGlobal() || plexonListener != null) {
      return;
    }
    Plugin plexonChats = plugin.getServer().getPluginManager().getPlugin(PLEXON_CHATS);
    if (plexonChats == null || !plexonChats.isEnabled()) {
      return;
    }
    if (!hasCurrentPlexonChatsApi(plexonChats)) {
      if (!warnedMissingApi) {
        warnedMissingApi = true;
        plugin
            .getLogger()
            .warning(
                "PlexonChats is enabled but has not registered PlexonChatsAPI; using vanilla chat"
                    + " fallback until the service becomes available.");
      }
      return;
    }
    try {
      Class.forName(PLEXON_API_EVENT, false, plexonChats.getClass().getClassLoader());
    } catch (ClassNotFoundException | LinkageError error) {
      if (!warnedIncompatibleApi) {
        warnedIncompatibleApi = true;
        plugin
            .getLogger()
            .warning(
                "PlexonChats is installed but its public chat event contract is incompatible with"
                    + " this PlexonPanel build; using vanilla chat fallback.");
      }
      return;
    }

    PlexonChatsListener listener = new PlexonChatsListener(sink);
    plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    plexonListener = listener;
    warnedMissingApi = false;
    warnedIncompatibleApi = false;
    plugin.getLogger().info("PlexonChats global-channel integration enabled.");
  }

  private boolean hasCurrentPlexonChatsApi(Plugin plexonChats) {
    for (RegisteredServiceProvider<?> registration :
        plugin.getServer().getServicesManager().getRegistrations(plexonChats)) {
      if (PLEXON_API_SERVICE.equals(registration.getService().getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPlexonChats(Plugin peer) {
    return peer != null && PLEXON_CHATS.equals(peer.getName());
  }

  private static boolean isPlexonChatsApi(RegisteredServiceProvider<?> registration) {
    return registration != null
        && isPlexonChats(registration.getPlugin())
        && PLEXON_API_SERVICE.equals(registration.getService().getName());
  }

  @EventHandler
  public void onPluginEnable(PluginEnableEvent event) {
    if (isPlexonChats(event.getPlugin())) {
      initializePlexonChatsIntegration();
    }
  }

  @EventHandler
  public void onPluginDisable(PluginDisableEvent event) {
    if (isPlexonChats(event.getPlugin())) {
      deactivatePlexonChatsIntegration();
    }
  }

  @EventHandler
  public void onServiceRegister(ServiceRegisterEvent event) {
    if (isPlexonChatsApi(event.getProvider())) {
      initializePlexonChatsIntegration();
    }
  }

  @EventHandler
  public void onServiceUnregister(ServiceUnregisterEvent event) {
    if (isPlexonChatsApi(event.getProvider())) {
      deactivatePlexonChatsIntegration();
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onVanillaChat(AsyncChatEvent event) {
    if (!settings.streamEnabled() || !settings.captureVanillaGlobal() || plexonListener != null) {
      return;
    }
    String content = plainText.serialize(event.originalMessage());
    sink.send(
        "chat.message",
        new ChatRecord(
            Instant.now().toString(),
            UUID.randomUUID().toString(),
            "GLOBAL",
            event.getPlayer().getUniqueId().toString(),
            event.getPlayer().getName(),
            content,
            "PAPER"),
        MessagePriority.EVENT);
  }

  public PublishResult publishFromDashboard(
      String actorId, String actorDisplayName, String content) {
    return publishFromDashboard(actorId, actorDisplayName, content, false);
  }

  public PublishResult publishFromDashboard(
      String actorId, String actorDisplayName, String content, boolean miniAllowed) {
    if (!settings.allowDashboardSend()) {
      return new PublishResult(false, "Dashboard chat sending is disabled");
    }
    if (content == null || content.isBlank() || content.length() > 2000) {
      return new PublishResult(false, "Chat message must contain between 1 and 2000 characters");
    }

    Component prefix =
        miniMessage.deserialize(
            settings.dashboardPrefix(), Placeholder.unparsed("actor", actorDisplayName));
    Component message =
        settings.allowMiniMessageFromDashboard() && miniAllowed
            ? miniMessage.deserialize(content)
            : Component.text(content);
    Component rendered = prefix.append(message);

    // PlexonChatsAPI deliberately accepts Player senders only. A dashboard actor is not a Player,
    // so Panel retains its existing synthetic-control-plane broadcast rather than fabricating one.
    Bukkit.broadcast(rendered);
    return new PublishResult(true, "Message published to global chat");
  }

  private void deactivatePlexonChatsIntegration() {
    PlexonChatsListener listener = plexonListener;
    plexonListener = null;
    if (listener != null) {
      HandlerList.unregisterAll(listener);
    }
    warnedMissingApi = false;
    warnedIncompatibleApi = false;
  }

  @Override
  public void close() {
    HandlerList.unregisterAll(this);
    deactivatePlexonChatsIntegration();
    started = false;
  }

  public record PublishResult(boolean success, String message) {}
}
