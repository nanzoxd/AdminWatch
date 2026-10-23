package dev.adminwatch.discord;

import dev.adminwatch.util.Json;

import java.util.ArrayList;
import java.util.List;

/** A single Discord embed (Discord limits: 256 title, 4096 description, 25 fields, 6000 total chars). */
public final class Embed {
    public record Field(String name, String value, boolean inline) {
        int length() { return name.length() + value.length(); }
    }

    public final String title;
    public final String description;
    public final int color;
    public final String timestampIso; // nullable
    public final String footer;       // nullable
    public final List<Field> fields = new ArrayList<>();

    public Embed(String title, String description, int color, String timestampIso, String footer) {
        this.title = title;
        this.description = description;
        this.color = color;
        this.timestampIso = timestampIso;
        this.footer = footer;
    }

    public int length() {
        int n = title.length() + (description == null ? 0 : description.length()) + (footer == null ? 0 : footer.length());
        for (Field f : fields) n += f.length();
        return n;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"title\":").append(Json.quote(title));
        if (description != null && !description.isEmpty()) sb.append(",\"description\":").append(Json.quote(description));
        sb.append(",\"color\":").append(color);
        if (timestampIso != null) sb.append(",\"timestamp\":").append(Json.quote(timestampIso));
        if (footer != null && !footer.isEmpty()) sb.append(",\"footer\":{\"text\":").append(Json.quote(footer)).append('}');
        if (!fields.isEmpty()) {
            sb.append(",\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(Json.quote(f.name()))
                        .append(",\"value\":").append(Json.quote(f.value()))
                        .append(",\"inline\":").append(f.inline()).append('}');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }
}
