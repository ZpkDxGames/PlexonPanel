package com.antondev.chats.api;

import com.antondev.chats.ChatChannel;

/** Immutable snapshot of one player's persisted PlexonChats preferences. */
public record PlayerChatPreferencesView(
        ChatChannel channel,
        boolean mentions,
        boolean tips,
        boolean privateMessages) {
}
