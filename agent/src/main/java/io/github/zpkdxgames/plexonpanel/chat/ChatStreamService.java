package io.github.zpkdxgames.plexonpanel.chat;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.model.ChatRecord;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.UUID;

public final class ChatStreamService implements Listener, AutoCloseable {
    private static final String PLEXON_API_EVENT = "com.antondev.chats.api.event.PlexonPublicChatEvent";

    private final JavaPlugin plugin;
    private final PanelSettings.Chat settings;
    private final MessageSink sink;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private PlexonChatsListener plexonListener;

    public ChatStreamService(JavaPlugin plugin, PanelSettings.Chat settings, MessageSink sink) {
        this.plugin = plugin;
        this.settings = settings;
        this.sink = sink;
    }

    public void start() {
        if (!settings.streamEnabled() && !settings.allowDashboardSend()) {
            return;
        }
        if (settings.streamEnabled() && settings.captureVanillaGlobal()) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        }
        if (settings.capturePlexonChatsGlobal()) {
            initializePlexonChatsIntegration();
        }
    }

    private void initializePlexonChatsIntegration() {
        Plugin plexonChats = plugin.getServer().getPluginManager().getPlugin("PlexonChats");
        if (plexonChats == null || !plexonChats.isEnabled()) {
            return;
        }
        try {
            Class.forName(PLEXON_API_EVENT, false, plexonChats.getClass().getClassLoader());
            plexonListener = new PlexonChatsListener(plugin, sink);
            plugin.getServer().getPluginManager().registerEvents(plexonListener, plugin);
            if (!plexonListener.hasApi()) {
                plugin.getLogger().warning("PlexonChats exposes events but has not registered PlexonChatsApi; dashboard chat sending is unavailable.");
            } else {
                plugin.getLogger().info("PlexonChats global-channel integration enabled.");
            }
        } catch (ClassNotFoundException error) {
            plugin.getLogger().warning("PlexonChats is installed but does not expose the PlexonPanel integration API; the adapter is disabled.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaChat(AsyncChatEvent event) {
        String content = plainText.serialize(event.originalMessage());
        sink.send("chat.message", new ChatRecord(
            Instant.now().toString(),
            UUID.randomUUID().toString(),
            "GLOBAL",
            event.getPlayer().getUniqueId().toString(),
            event.getPlayer().getName(),
            content,
            "PAPER"
        ), MessagePriority.EVENT);
    }

    public PublishResult publishFromDashboard(String actorId, String actorDisplayName, String content) {
        if (!settings.allowDashboardSend()) {
            return new PublishResult(false, "Dashboard chat sending is disabled");
        }
        if (content == null || content.isBlank() || content.length() > 2000) {
            return new PublishResult(false, "Chat message must contain between 1 and 2000 characters");
        }

        Component prefix = miniMessage.deserialize(settings.dashboardPrefix(), Placeholder.unparsed("actor", actorDisplayName));
        Component message = settings.allowMiniMessageFromDashboard()
            ? miniMessage.deserialize(content)
            : Component.text(content);
        Component rendered = prefix.append(message);

        if (plexonListener == null || !plexonListener.publish(rendered, actorId, actorDisplayName)) {
            Bukkit.broadcast(rendered);
        }
        return new PublishResult(true, "Message published to global chat");
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        if (plexonListener != null) {
            HandlerList.unregisterAll(plexonListener);
            plexonListener = null;
        }
    }

    public record PublishResult(boolean success, String message) {
    }
}
