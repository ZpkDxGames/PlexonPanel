package com.antondev.chats.api.event;

import com.antondev.chats.api.model.PlexonChatChannel;
import com.antondev.chats.api.model.PlexonChatSource;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired by PlexonChats after a message has been accepted and routed.
 */
public final class PlexonPublicChatEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID messageId;
    private final UUID senderId;
    private final String senderName;
    private final PlexonChatChannel channel;
    private final PlexonChatSource source;
    private final Component originalMessage;
    private final Component renderedMessage;

    public PlexonPublicChatEvent(
        boolean asynchronous,
        UUID messageId,
        UUID senderId,
        String senderName,
        PlexonChatChannel channel,
        PlexonChatSource source,
        Component originalMessage,
        Component renderedMessage
    ) {
        super(asynchronous);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.senderId = senderId;
        this.senderName = Objects.requireNonNull(senderName, "senderName");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.source = Objects.requireNonNull(source, "source");
        this.originalMessage = Objects.requireNonNull(originalMessage, "originalMessage");
        this.renderedMessage = Objects.requireNonNull(renderedMessage, "renderedMessage");
    }

    public UUID messageId() {
        return messageId;
    }

    public UUID senderId() {
        return senderId;
    }

    public String senderName() {
        return senderName;
    }

    public PlexonChatChannel channel() {
        return channel;
    }

    public PlexonChatSource source() {
        return source;
    }

    public Component originalMessage() {
        return originalMessage;
    }

    public Component renderedMessage() {
        return renderedMessage;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
