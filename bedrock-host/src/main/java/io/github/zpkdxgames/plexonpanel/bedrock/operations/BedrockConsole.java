package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Container;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase;

/** Staged fixed-command console. Receipt of stdin bytes is a dispatch, pending Bedrock evidence. */
public final class BedrockConsole {
    public enum Action { LIST, SAY, ALLOWLIST_LIST, ALLOWLIST_ADD, ALLOWLIST_REMOVE, OP, DEOP }
    public enum Outcome {
        DISABLED, BUSY, ATTACH_NOT_EXCLUSIVE, INVALID_ARGUMENT, TARGET_UNAVAILABLE, TARGET_NOT_RUNNING,
        DISPATCHED, OUTCOME_UNKNOWN
    }

    /**
     * Root-local attach policy. The bound transport must verify TTY, open stdin and the expected
     * running launch, write at most one line, and close without forwarding signals or EOF to Bedrock.
     * Exclusive ownership requires the local operator to keep other Docker attach clients detached.
     */
    @FunctionalInterface
    public interface Attach {
        void writeLine(Container expected, byte[] line) throws IOException;
    }

    private final boolean enabled;
    private final boolean playerActionsEnabled;
    private final boolean exclusiveStdin;
    private final BedrockContainerControl control;
    private final BedrockOperationLock operationLock;
    private final Attach attach;

    public BedrockConsole(boolean enabled, boolean playerActionsEnabled, boolean exclusiveStdin,
                          BedrockContainerControl control, BedrockOperationLock operationLock, Attach attach) {
        this.enabled = enabled;
        this.playerActionsEnabled = playerActionsEnabled;
        this.exclusiveStdin = exclusiveStdin;
        this.control = Objects.requireNonNull(control);
        this.operationLock = Objects.requireNonNull(operationLock);
        this.attach = Objects.requireNonNull(attach);
    }

    public Outcome execute(Action action, String argument) {
        Objects.requireNonNull(action);
        if (!enabled || playerAction(action) && !playerActionsEnabled) {
            return Outcome.DISABLED;
        }
        if (!exclusiveStdin) {
            return Outcome.ATTACH_NOT_EXCLUSIVE;
        }
        String command = command(action, argument);
        if (command == null) {
            return Outcome.INVALID_ARGUMENT;
        }
        if (!operationLock.acquire()) {
            return Outcome.BUSY;
        }
        try {
            Container target;
            try {
                target = control.inspect();
            } catch (IOException unavailable) {
                return Outcome.TARGET_UNAVAILABLE;
            }
            if (target.phase() != Phase.RUNNING) {
                return Outcome.TARGET_NOT_RUNNING;
            }
            try {
                attach.writeLine(target, (command + "\n").getBytes(StandardCharsets.UTF_8));
                return Outcome.DISPATCHED;
            } catch (IOException unknownOutcome) {
                return Outcome.OUTCOME_UNKNOWN;
            }
        } finally {
            operationLock.release();
        }
    }

    /** Validate a line again at the privileged attach boundary. */
    public static boolean isAllowedWireLine(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes.length > 645 || bytes[bytes.length - 1] != '\n') {
            return false;
        }
        String line;
        try {
            line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1)).toString();
        } catch (CharacterCodingException malformed) {
            return false;
        }
        return line.equals("list") || line.equals("allowlist list")
                || line.startsWith("say ") && validMessage(line.substring("say ".length()))
                || allowedPlayerLine(line, "allowlist add")
                || allowedPlayerLine(line, "allowlist remove")
                || allowedPlayerLine(line, "op") || allowedPlayerLine(line, "deop");
    }

    private static boolean allowedPlayerLine(String line, String command) {
        String prefix = command + " \"";
        return line.startsWith(prefix) && line.endsWith("\"") && line.length() > prefix.length() + 1
                && BedrockRoster.validGamertag(line.substring(prefix.length(), line.length() - 1));
    }

    private static boolean playerAction(Action action) {
        return switch (action) {
            case LIST, SAY -> false;
            case ALLOWLIST_LIST, ALLOWLIST_ADD, ALLOWLIST_REMOVE, OP, DEOP -> true;
        };
    }

    private static String command(Action action, String argument) {
        return switch (action) {
            case LIST -> argument == null ? "list" : null;
            case ALLOWLIST_LIST -> argument == null ? "allowlist list" : null;
            case SAY -> argument != null && validMessage(argument) ? "say " + argument : null;
            case ALLOWLIST_ADD -> playerCommand("allowlist add", argument);
            case ALLOWLIST_REMOVE -> playerCommand("allowlist remove", argument);
            case OP -> playerCommand("op", argument);
            case DEOP -> playerCommand("deop", argument);
        };
    }

    private static boolean validMessage(String text) {
        if (text.isBlank() || text.length() > 160) {
            return false;
        }
        return text.codePoints().allMatch(point -> switch (Character.getType(point)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        });
    }

    private static String playerCommand(String prefix, String name) {
        return BedrockRoster.validGamertag(name) ? prefix + " \"" + name + "\"" : null;
    }
}
