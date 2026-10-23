package dev.adminwatch.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes passwords, tokens and webhook URLs from anything that is logged or sent. */
public final class SensitiveFilter {
    private static final Pattern WEBHOOK = Pattern.compile(
            "https?://(?:[\\w.-]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern KV = Pattern.compile(
            "(?i)\\b(password|passwd|pass|pwd|token|secret|apikey|api_key|api-key|auth|credentials?)(\\s*[=:]\\s*)\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    private SensitiveFilter() {}

    /** Generic text scrub (command block commands, etc.). */
    public static String redactText(String text) {
        if (text == null) return null;
        String out = WEBHOOK.matcher(text).replaceAll("[REDACTED]");
        out = KV.matcher(out).replaceAll("$1$2[REDACTED]");
        out = BEARER.matcher(out).replaceAll("$1 [REDACTED]");
        return out;
    }

    /**
     * Redacts a command line. Arguments of configured sensitive commands are removed entirely.
     * Returns the command WITHOUT a leading slash.
     */
    public static String redactCommand(String commandLine, Set<String> sensitiveLabels) {
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        String label = labelOf(line);
        if (sensitiveLabels.contains(label)) {
            return line.contains(" ") ? line.substring(0, line.indexOf(' ')) + " [REDACTED]" : line;
        }
        return redactText(line);
    }

    /** Lower-case label without namespace ("minecraft:give arg" -> "give"). */
    public static String labelOf(String commandLineWithoutSlash) {
        String s = commandLineWithoutSlash;
        int sp = s.indexOf(' ');
        if (sp >= 0) s = s.substring(0, sp);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
