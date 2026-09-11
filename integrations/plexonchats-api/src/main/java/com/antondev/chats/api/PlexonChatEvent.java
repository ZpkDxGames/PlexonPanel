package com.antondev.chats.api;

import com.antondev.chats.ChatChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-only snapshot of the current PlexonChats public-chat event.
 * Main-thread pre-delivery event for public chat, including /g and /l. Never fired for PMs.
 */
public final class PlexonChatEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ChatChannel channel;
    private final String rawMessage;
    private final Set<Player> recipients;
    private Component message;
    private boolean cancelled;
    private boolean discordAllowed;
    public PlexonChatEvent(Player player, ChatChannel channel, String rawMessage, Component message, Set<Player> recipients) {
        this.player = player;
        this.channel = channel;
        this.rawMessage = rawMessage;
        this.message = Objects.requireNonNull(message);
        this.recipients = new LinkedHashSet<>(recipients);
        discordAllowed = channel == ChatChannel.GLOBAL;
    }
    public Player getPlayer() { return player; }
    public ChatChannel getChannel() { return channel; }
    public String getRawMessage() { return rawMessage; }
    public Component getMessage() { return message; }
    public void setMessage(Component message) { this.message = Objects.requireNonNull(message); }
    public Set<Player> getRecipients() { return recipients; }
    public boolean isDiscordAllowed() { return discordAllowed && channel == ChatChannel.GLOBAL; }
    public void setDiscordAllowed(boolean value) { discordAllowed = value; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
