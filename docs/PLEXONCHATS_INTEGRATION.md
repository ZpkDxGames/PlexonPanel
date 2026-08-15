# PlexonChats integration

PlexonChats owns routing for its local/global channels and cancels Paper's standard chat event, so PlexonPanel does not scrape formatted console output or depend on private PlexonChats classes. This workspace instead defines a small public contract in `integrations/plexonchats-api`.

## Changes required in PlexonChats

1. Include the four contract types under `com.antondev.chats.api` in the PlexonChats JAR.
2. Implement `PlexonChatsApi.publishExternalGlobal(Component, actorId, actorDisplayName)` using the same global broadcast path as accepted player messages.
3. Register that implementation with Bukkit's `ServicesManager` while PlexonChats enables, and unregister it while disabling.
4. Fire `PlexonPublicChatEvent` after a message is accepted/routed. Set `channel` to `GLOBAL` or `LOCAL`, preserve the original content, provide the rendered component, and mark the event async when fired from the async chat pipeline.
5. When `publishExternalGlobal` is called, use source `DASHBOARD` and prevent the message from being reflected back to the dashboard as a new player-authored command.

Example registration:

```java
getServer().getServicesManager().register(
    PlexonChatsApi.class,
    plexonChatsApi,
    this,
    ServicePriority.Normal
);
```

Example event emission:

```java
getServer().getPluginManager().callEvent(new PlexonPublicChatEvent(
    true,
    UUID.randomUUID(),
    player.getUniqueId(),
    player.getName(),
    PlexonChatChannel.GLOBAL,
    PlexonChatSource.PLAYER,
    originalMessage,
    renderedMessage
));
```

PlexonPanel declares `PlexonChats` as a soft dependency. If these API classes are absent, PlexonPanel logs one warning and continues without the PlexonChats adapter. Normal Paper global capture remains available for servers where another plugin does not cancel/reroute the event.
