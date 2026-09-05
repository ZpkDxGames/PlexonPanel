package io.github.zpkdxgames.plexonpanel.chat;

import com.antondev.chats.api.PlexonChatsApi;
import com.antondev.chats.api.event.PlexonPublicChatEvent;
import com.antondev.chats.api.model.PlexonChatChannel;
import io.github.zpkdxgames.plexonpanel.model.ChatRecord;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;

final class PlexonChatsListener implements Listener {
    private final MessageSink sink;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final PlexonChatsApi api;

    PlexonChatsListener(JavaPlugin plugin, MessageSink sink) {
        this.sink = sink;
        RegisteredServiceProvider<PlexonChatsApi> registration = plugin.getServer()
            .getServicesManager()
            .getRegistration(PlexonChatsApi.class);
        this.api = registration == null ? null : registration.getProvider();
    }

    boolean hasApi() {
        return api != null;
    }

    boolean publish(Component message, String actorId, String actorDisplayName) {
        if (api == null) {
            return false;
        }
        api.publishExternalGlobal(message, actorId, actorDisplayName);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlexonChat(PlexonPublicChatEvent event) {
        if (event.channel() != PlexonChatChannel.GLOBAL) {
            return;
        }
        sink.send("chat.message", new ChatRecord(
            Instant.now().toString(),
            event.messageId().toString(),
            event.channel().name(),
            event.senderId() == null ? null : event.senderId().toString(),
            event.senderName(),
            plainText.serialize(event.originalMessage()),
            "PLEXONCHATS_" + event.source().name()
        ), MessagePriority.EVENT);
    }
}
