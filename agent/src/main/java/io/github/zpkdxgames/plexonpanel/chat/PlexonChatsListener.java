package io.github.zpkdxgames.plexonpanel.chat;

import com.antondev.chats.ChatChannel;
import com.antondev.chats.api.PlexonChatEvent;
import io.github.zpkdxgames.plexonpanel.model.ChatRecord;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import java.time.Instant;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

final class PlexonChatsListener implements Listener {
  private final MessageSink sink;
  private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

  PlexonChatsListener(MessageSink sink) {
    this.sink = sink;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlexonChat(PlexonChatEvent event) {
    if (event.getChannel() != ChatChannel.GLOBAL) {
      return;
    }
    Player sender = event.getPlayer();
    sink.send(
        "chat.message",
        new ChatRecord(
            Instant.now().toString(),
            UUID.randomUUID().toString(),
            event.getChannel().name(),
            sender == null ? null : sender.getUniqueId().toString(),
            sender == null ? "unknown" : sender.getName(),
            plainText.serialize(event.getMessage()),
            "PLEXONCHATS"),
        MessagePriority.EVENT);
  }
}
