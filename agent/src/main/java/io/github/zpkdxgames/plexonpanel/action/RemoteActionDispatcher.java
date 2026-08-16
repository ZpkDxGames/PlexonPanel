package io.github.zpkdxgames.plexonpanel.action;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.audit.AuditService;
import io.github.zpkdxgames.plexonpanel.chat.ChatStreamService;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.console.LogRedactor;
import io.github.zpkdxgames.plexonpanel.model.ActionRequest;
import io.github.zpkdxgames.plexonpanel.model.ActionResult;
import io.github.zpkdxgames.plexonpanel.model.AuditEntry;
import io.github.zpkdxgames.plexonpanel.protocol.DecodedMessage;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.papermc.paper.ban.BanListType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class RemoteActionDispatcher {
    private static final int MAXIMUM_TEXT_LENGTH = 2000;
    private static final int MAXIMUM_OUTPUT_LINES = 100;
    private static final int MAXIMUM_OUTPUT_LINE_LENGTH = 4096;
    private static final int MAXIMUM_COMPLETED_ACTIONS = 1024;

    private final JavaPlugin plugin;
    private final PanelSettings.RemoteActions settings;
    private final MessageSink sink;
    private final AuditService audit;
    private final ChatStreamService chat;
    private final CommandPolicy commandPolicy;
    private final LogRedactor outputRedactor;
    private final Gson gson = new Gson();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, ActionResult> completed = Collections.synchronizedMap(
        new LinkedHashMap<>(MAXIMUM_COMPLETED_ACTIONS + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ActionResult> eldest) {
                return size() > MAXIMUM_COMPLETED_ACTIONS;
            }
        }
    );

    public RemoteActionDispatcher(
        JavaPlugin plugin,
        PanelSettings.RemoteActions settings,
        PanelSettings.Console consoleSettings,
        MessageSink sink,
        AuditService audit,
        ChatStreamService chat
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.sink = sink;
        this.audit = audit;
        this.chat = chat;
        this.commandPolicy = new CommandPolicy(settings.console().allowPatterns(), settings.console().denyPatterns());
        this.outputRedactor = new LogRedactor(consoleSettings.redactPatterns());
    }

    public void accept(DecodedMessage message) {
        if (!"action.request".equals(message.envelope().type())) {
            plugin.getLogger().warning("Ignored unsupported privileged relay message type: " + message.envelope().type());
            return;
        }

        ActionRequest request;
        try {
            request = gson.fromJson(message.body(), ActionRequest.class);
            validateRequest(request);
        } catch (RuntimeException error) {
            String fallbackId = message.envelope().messageId();
            sendResult(ActionResult.failure(fallbackId, "unknown", "INVALID_REQUEST", "Remote action request is malformed"));
            plugin.getLogger().log(Level.WARNING, "Rejected malformed PlexonPanel remote action", error);
            return;
        }

        ActionResult previous = completed.get(request.requestId());
        if (previous != null) {
            sendResult(previous);
            return;
        }
        if (!inFlight.add(request.requestId())) {
            return;
        }

        if (!settings.enabled()) {
            complete(request, ActionOutcome.denied("REMOTE_ACTIONS_DISABLED", "Remote actions are disabled"));
            return;
        }

        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> executeOnMainThread(request));
        } catch (RuntimeException error) {
            complete(request, ActionOutcome.failed("SCHEDULER_UNAVAILABLE",
                "The Paper scheduler could not accept the action"));
            plugin.getLogger().log(Level.WARNING, "Unable to schedule PlexonPanel remote action", error);
        }
    }

    private void executeOnMainThread(ActionRequest request) {
        ActionOutcome outcome;
        try {
            outcome = switch (request.action()) {
                case "console.execute" -> executeConsole(request.parameters());
                case "player.message" -> messagePlayer(request.parameters());
                case "player.kick" -> kickPlayer(request.parameters());
                case "player.ban" -> banPlayer(request.parameters());
                case "player.unban" -> unbanPlayer(request.parameters());
                case "player.whitelist" -> whitelistPlayer(request.parameters());
                case "chat.global.send" -> publishGlobalChat(request, request.parameters());
                default -> ActionOutcome.denied("UNKNOWN_ACTION", "Unsupported remote action");
            };
        } catch (IllegalArgumentException error) {
            outcome = ActionOutcome.denied("INVALID_PARAMETERS", error.getMessage());
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "PlexonPanel remote action failed: " + request.action(), error);
            outcome = ActionOutcome.failed("ACTION_FAILED", "The server could not complete this action");
        }
        complete(request, outcome);
    }

    private ActionOutcome executeConsole(JsonObject parameters) {
        if (!settings.console().enabled()) {
            return ActionOutcome.denied("CONSOLE_DISABLED", "Remote console execution is disabled");
        }
        CommandPolicy.Decision decision = commandPolicy.evaluate(requiredString(parameters, "command", 512));
        if (!decision.allowed()) {
            return ActionOutcome.denied(decision.code(), decision.message());
        }

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        CommandSender sender = plugin.getServer().createCommandSender(component -> {
            if (output.size() >= MAXIMUM_OUTPUT_LINES) {
                return;
            }
            String line = outputRedactor.redact(plainText.serialize(component));
            output.add(truncate(line, MAXIMUM_OUTPUT_LINE_LENGTH));
        });
        boolean dispatched = plugin.getServer().dispatchCommand(sender, decision.command());
        if (!dispatched) {
            return ActionOutcome.failed("COMMAND_NOT_FOUND", "The server did not recognize the command");
        }
        return ActionOutcome.success("Command executed", List.copyOf(output));
    }

    private ActionOutcome messagePlayer(JsonObject parameters) {
        if (!settings.players().message()) {
            return ActionOutcome.denied("PLAYER_MESSAGE_DISABLED", "Remote player messages are disabled");
        }
        Player player = onlinePlayer(parameters);
        String content = requiredString(parameters, "message", MAXIMUM_TEXT_LENGTH);
        player.sendMessage(Component.text(content));
        return ActionOutcome.success("Message sent to player");
    }

    private ActionOutcome kickPlayer(JsonObject parameters) {
        if (!settings.players().kick()) {
            return ActionOutcome.denied("PLAYER_KICK_DISABLED", "Remote player kicks are disabled");
        }
        Player player = onlinePlayer(parameters);
        String reason = optionalString(parameters, "reason", "Removed by a server administrator", MAXIMUM_TEXT_LENGTH);
        player.kick(Component.text(reason));
        return ActionOutcome.success("Player kicked");
    }

    private ActionOutcome banPlayer(JsonObject parameters) {
        if (!settings.players().ban()) {
            return ActionOutcome.denied("PLAYER_BAN_DISABLED", "Remote player bans are disabled");
        }
        UUID playerId = requiredUuid(parameters, "playerId");
        String reason = optionalString(parameters, "reason", "Banned by a server administrator", MAXIMUM_TEXT_LENGTH);
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerId);
        offline.ban(reason, (Instant) null, "PlexonPanel");
        Player online = offline.getPlayer();
        if (online != null) {
            online.kick(Component.text(reason));
        }
        return ActionOutcome.success("Player banned");
    }

    private ActionOutcome unbanPlayer(JsonObject parameters) {
        if (!settings.players().unban()) {
            return ActionOutcome.denied("PLAYER_UNBAN_DISABLED", "Remote player unbans are disabled");
        }
        UUID playerId = requiredUuid(parameters, "playerId");
        Server server = plugin.getServer();
        server.getBanList(BanListType.PROFILE).pardon(server.createProfile(playerId));
        return ActionOutcome.success("Player unbanned");
    }

    private ActionOutcome whitelistPlayer(JsonObject parameters) {
        if (!settings.players().whitelist()) {
            return ActionOutcome.denied("PLAYER_WHITELIST_DISABLED", "Remote whitelist changes are disabled");
        }
        UUID playerId = requiredUuid(parameters, "playerId");
        boolean whitelisted = requiredBoolean(parameters, "whitelisted");
        plugin.getServer().getOfflinePlayer(playerId).setWhitelisted(whitelisted);
        return ActionOutcome.success(whitelisted ? "Player added to whitelist" : "Player removed from whitelist");
    }

    private ActionOutcome publishGlobalChat(ActionRequest request, JsonObject parameters) {
        String content = requiredString(parameters, "message", MAXIMUM_TEXT_LENGTH);
        ChatStreamService.PublishResult result = chat.publishFromDashboard(
            request.actorId(),
            request.actorDisplayName(),
            content
        );
        return result.success()
            ? ActionOutcome.success(result.message())
            : ActionOutcome.denied("CHAT_SEND_DISABLED", result.message());
    }

    private Player onlinePlayer(JsonObject parameters) {
        UUID playerId = requiredUuid(parameters, "playerId");
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("Player is not online");
        }
        return player;
    }

    private void complete(ActionRequest request, ActionOutcome outcome) {
        ActionResult result = outcome.success()
            ? ActionResult.success(request.requestId(), request.action(), outcome.message(), outcome.output())
            : ActionResult.failure(request.requestId(), request.action(), outcome.code(), outcome.message());
        completed.put(request.requestId(), result);
        inFlight.remove(request.requestId());
        sendResult(result);
        audit.record(new AuditEntry(
            Instant.now().toString(),
            request.requestId(),
            request.actorId(),
            request.actorDisplayName(),
            request.action(),
            safeTarget(request),
            outcome.allowed(),
            outcome.success(),
            outcome.code()
        ));
    }

    private void sendResult(ActionResult result) {
        sink.send("action.result", result, MessagePriority.CRITICAL);
    }

    private static void validateRequest(ActionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is missing");
        }
        validateIdentifier(request.requestId(), "requestId", 128);
        validateIdentifier(request.action(), "action", 64);
        validateIdentifier(request.actorId(), "actorId", 128);
        if (request.actorDisplayName() == null || request.actorDisplayName().isBlank()
            || request.actorDisplayName().length() > 256) {
            throw new IllegalArgumentException("actorDisplayName is invalid");
        }
        if (request.parameters() == null) {
            throw new IllegalArgumentException("parameters are missing");
        }
    }

    private static void validateIdentifier(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
            || !value.matches("[A-Za-z0-9._:@-]+")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static String safeTarget(ActionRequest request) {
        try {
            JsonObject parameters = request.parameters();
            if (request.action().startsWith("player.")) {
                return truncate(optionalRawString(parameters, "playerId", "player"), 64);
            }
            if ("console.execute".equals(request.action())) {
                String command = optionalRawString(parameters, "command", "command").strip();
                if (command.startsWith("/")) {
                    command = command.substring(1).stripLeading();
                }
                int separator = command.indexOf(' ');
                return truncate((separator < 0 ? command : command.substring(0, separator)).toLowerCase(Locale.ROOT), 64);
            }
            return "global";
        } catch (RuntimeException ignored) {
            return request.action().startsWith("player.") ? "player" : "unknown";
        }
    }

    private static String requiredString(JsonObject object, String name, int maximumLength) {
        String value = optionalRawString(object, name, null);
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximumLength + " characters");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " contains forbidden characters");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name, String fallback, int maximumLength) {
        String value = optionalRawString(object, name, fallback);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (value.length() > maximumLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is too long or contains forbidden characters");
        }
        return value;
    }

    private static String optionalRawString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return element.getAsString();
    }

    private static UUID requiredUuid(JsonObject object, String name) {
        try {
            return UUID.fromString(requiredString(object, name, 36));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be a valid UUID", error);
        }
    }

    private static boolean requiredBoolean(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private record ActionOutcome(boolean allowed, boolean success, String code, String message, List<String> output) {
        private static ActionOutcome success(String message) {
            return success(message, List.of());
        }

        private static ActionOutcome success(String message, List<String> output) {
            return new ActionOutcome(true, true, "OK", message, List.copyOf(output));
        }

        private static ActionOutcome denied(String code, String message) {
            return new ActionOutcome(false, false, code, message, List.of());
        }

        private static ActionOutcome failed(String code, String message) {
            return new ActionOutcome(true, false, code, message, List.of());
        }
    }
}
