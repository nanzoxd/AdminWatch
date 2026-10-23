package dev.adminwatch.discord;

import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns an {@link AuditEvent} into one or more Discord-safe embeds (oversized content is split). */
public final class EmbedFactory {
    private static final int CHUNK = 900;          // < 1024 incl. code-block markers
    private static final int MAX_FIELDS = 25;
    private static final int MAX_EMBED_CHARS = 5500; // < 6000 safety margin
    private static final int MAX_TOTAL_EMBEDS = 5;   // hard stop so one event can never flood a webhook

    private EmbedFactory() {}

    public static List<Embed> build(AuditEvent e, Settings s) {
        List<Embed.Field> fields = new ArrayList<>();
        fields.add(new Embed.Field("Admin", code(e.adminName()), true));
        if (e.adminUuid() != null) fields.add(new Embed.Field("Admin UUID", code(e.adminUuid().toString()), true));
        if (e.targetName() != null) {
            fields.add(new Embed.Field("Target", code(e.targetName()), true));
            if (e.targetUuid() != null) fields.add(new Embed.Field("Target UUID", code(e.targetUuid().toString()), true));
        }
        for (Map.Entry<String, String> en : e.fields().entrySet()) {
            addSplit(fields, en.getKey(), en.getValue());
        }
        if (e.world() != null) fields.add(new Embed.Field("World", code(e.world()), true));
        if (e.x() != null && e.y() != null && e.z() != null) {
            fields.add(new Embed.Field("Location", code(e.x() + ", " + e.y() + ", " + e.z()), true));
        }
        fields.add(new Embed.Field("Time", code(s.time.full(e.time())), false));

        String title = trim(e.title() != null ? e.title() : e.category().title(), 240);
        String desc = e.summary() == null ? null : trim(e.summary(), 3000);
        String ts = s.embedTimestamp ? e.time().toString() : null;
        int color = s.color(e.category());
        String footer = trim(s.embedFooter, 200);

        List<Embed> out = new ArrayList<>();
        Embed cur = new Embed(title, desc, color, ts, footer);
        out.add(cur);
        for (Embed.Field f : fields) {
            if (cur.fields.size() >= MAX_FIELDS || cur.length() + f.length() > MAX_EMBED_CHARS) {
                if (out.size() >= MAX_TOTAL_EMBEDS) {
                    cur.fields.add(new Embed.Field("…", "Further details truncated (full data is in the local database).", false));
                    break;
                }
                cur = new Embed(trim(title + " (cont.)", 250), null, color, ts, footer);
                out.add(cur);
            }
            cur.fields.add(f);
        }
        return out;
    }

    private static void addSplit(List<Embed.Field> fields, String name, String value) {
        String n = trim(name, 240);
        if (value.length() <= CHUNK) {
            fields.add(new Embed.Field(n, code(value), shortValue(value)));
            return;
        }
        int idx = 0, part = 0;
        while (idx < value.length()) {
            int end = Math.min(value.length(), idx + CHUNK);
            fields.add(new Embed.Field(part == 0 ? n : trim(n + " (cont.)", 250), code(value.substring(idx, end)), false));
            idx = end;
            part++;
            if (part >= 8) { // 8 * 900 chars max per field
                fields.add(new Embed.Field(trim(n + " (cont.)", 250), "`… truncated`", false));
                break;
            }
        }
    }

    private static boolean shortValue(String v) {
        return v.length() <= 32 && v.indexOf('\n') < 0;
    }

    /** Wraps a value in inline code (single line) or a code block (multi-line), neutralising backticks. */
    static String code(String v) {
        if (v == null || v.isEmpty()) return "`-`";
        String safe = v.replace("`", "ʼ");
        if (safe.indexOf('\n') >= 0) return "```\n" + safe + "\n```";
        return "`" + safe + "`";
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
