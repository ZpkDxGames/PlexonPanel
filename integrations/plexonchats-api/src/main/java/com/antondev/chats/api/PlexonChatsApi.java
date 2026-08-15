package com.antondev.chats.api;

import net.kyori.adventure.text.Component;

/**
 * Optional public service implemented and registered by PlexonChats.
 *
 * <p>PlexonPanel locates this service through Bukkit's ServicesManager only
 * when PlexonChats is present. PlexonChats remains a soft dependency.</p>
 */
public interface PlexonChatsApi {
    void publishExternalGlobal(Component renderedMessage, String actorId, String actorDisplayName);
}
