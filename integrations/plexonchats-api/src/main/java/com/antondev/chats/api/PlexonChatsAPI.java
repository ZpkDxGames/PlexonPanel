package com.antondev.chats.api;

import com.antondev.chats.ChatChannel;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Compile-only snapshot of the stable PlexonChats service API.
 * Synchronized with accepted PlexonChats head 48a32861892a5d28445b780ee234d7fc0e732d0c.
 */
public interface PlexonChatsAPI {
    ChatChannel channel(Player player);

    boolean canReceive(Player player, ChatChannel channel);

    PlayerChatPreferencesView preferences(UUID playerId);

    String discordStatus();

    String autoMessageStatus();

    Set<String> autoMessageGroups();

    boolean selectChannel(Player player, ChatChannel channel);

    void sendPublic(Player sender, ChatChannel channel, String rawMessage);

    boolean sendPrivate(Player sender, Player recipient, String rawMessage);
}
