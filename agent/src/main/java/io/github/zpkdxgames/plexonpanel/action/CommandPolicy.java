package io.github.zpkdxgames.plexonpanel.action;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class CommandPolicy {
    private static final int MAXIMUM_COMMAND_LENGTH = 512;

    private final List<Pattern> allow;
    private final List<Pattern> deny;

    public CommandPolicy(List<String> allowPatterns, List<String> denyPatterns) {
        this.allow = compile(allowPatterns, "allow");
        this.deny = compile(denyPatterns, "deny");
    }

    public Decision evaluate(String rawCommand) {
        if (rawCommand == null) {
            return Decision.denied("INVALID_COMMAND", "Command is missing");
        }
        String command = rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isBlank() || command.length() > MAXIMUM_COMMAND_LENGTH) {
            return Decision.denied("INVALID_COMMAND", "Command length is invalid");
        }
        if (command.chars().anyMatch(Character::isISOControl)) {
            return Decision.denied("INVALID_COMMAND", "Command contains forbidden control characters");
        }

        String normalized = command.toLowerCase(Locale.ROOT);
        if (deny.stream().anyMatch(pattern -> pattern.matcher(normalized).matches())) {
            return Decision.denied("COMMAND_DENIED", "Command matches a deny rule");
        }
        if (allow.stream().noneMatch(pattern -> pattern.matcher(normalized).matches())) {
            return Decision.denied("COMMAND_NOT_ALLOWED", "Command does not match an allow rule");
        }
        return new Decision(true, "OK", "Command allowed", command);
    }

    private static List<Pattern> compile(List<String> patterns, String kind) {
        try {
            return patterns.stream()
                .map(String::strip)
                .filter(pattern -> !pattern.isBlank())
                .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("Invalid remote console " + kind + " pattern: " + error.getDescription(), error);
        }
    }

    public record Decision(boolean allowed, String code, String message, String command) {
        private static Decision denied(String code, String message) {
            return new Decision(false, code, message, "");
        }
    }
}
