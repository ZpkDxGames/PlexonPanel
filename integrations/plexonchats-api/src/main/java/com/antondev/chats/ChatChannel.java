package com.antondev.chats;

/**
 * Compile-only snapshot of the PlexonChats public channel contract.
 * Synchronized with accepted PlexonChats head 48a32861892a5d28445b780ee234d7fc0e732d0c.
 */
public enum ChatChannel {
    LOCAL("Local", "plexonchats.local"),
    GLOBAL("Global", "plexonchats.global");

    private final String displayName;
    private final String permission;

    ChatChannel(String displayName, String permission) {
        this.displayName = displayName;
        this.permission = permission;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPermission() {
        return permission;
    }

    public static ChatChannel fromName(String name) {
        for (ChatChannel channel : values()) {
            if (channel.name().equalsIgnoreCase(name) || channel.displayName.equalsIgnoreCase(name)) {
                return channel;
            }
        }
        return null;
    }
}
