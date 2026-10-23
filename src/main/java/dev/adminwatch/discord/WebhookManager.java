package dev.adminwatch.discord;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous Discord delivery. Every category owns an independent queue, worker thread and webhook,
 * so one category can never leak into (or be throttled by) another. HTTP never runs on the server thread.
 */
public final class WebhookManager {
    public static final String SENT = "SENT", FAILED = "FAILED", DROPPED = "DROPPED", SKIPPED = "SKIPPED", PENDING = "PENDING";
    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");

    private record Job(AuditEvent event, long dbId) {}

    public static final class Stats {
        public final AtomicLong sent = new AtomicLong(), failed = new AtomicLong(), dropped = new AtomicLong();
        public volatile int queued;
    }

    private final class Channel implements Runnable {
        final Category category;
        final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
        final Stats stats = new Stats();
        final Thread thread;
        volatile boolean running = true;
        volatile long lastWarn;

        Channel(Category c) {
            this.category = c;
            this.thread = new Thread(this, "AdminWatch-Discord-" + c.key());
            this.thread.setDaemon(true);
            this.thread.start();
        }

        @Override
        public void run() {
            while (running || !queue.isEmpty()) {
                Job job;
                try {
                    job = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    break;
                }
                if (job == null) {
                    if (!running) break;
                    continue;
                }
                String status = deliver(this, job);
                plugin.database().updateDiscordStatus(job.dbId(), status);
                try {
                    Thread.sleep(settings.minIntervalMs);
                } catch (InterruptedException ex) {
                    break;
                }
            }
            // Anything still queued after a forced stop stays recorded locally.
            Job rest;
            while ((rest = queue.poll()) != null) {
                stats.failed.incrementAndGet();
                plugin.database().updateDiscordStatus(rest.dbId(), FAILED);
            }
        }

        void warn(String msg) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 30_000) { // throttle log spam
                lastWarn = now;
                plugin.getLogger().warning(msg);
            }
        }
    }

    private final AdminWatchPlugin plugin;
    private volatile Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Category, Channel> channels = new EnumMap<>(Category.class);

    public WebhookManager(AdminWatchPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        for (Category c : Category.values()) channels.put(c, new Channel(c));
    }

    public void reconfigure(Settings s) { this.settings = s; }

    /** Status to store with a new DB row: PENDING if it will be delivered, SKIPPED otherwise. */
    public String initialStatus(Category c) {
        return settings.webhook(c).deliverable() ? PENDING : SKIPPED;
    }

    /** Queues an event on its OWN category queue only. Never blocks. */
    public void submit(AuditEvent event, long dbId) {
        Channel ch = channels.get(event.category());
        if (!settings.webhook(event.category()).deliverable()) return;
        if (ch.queue.size() >= settings.queueCapacity) {
            ch.stats.dropped.incrementAndGet();
            plugin.database().updateDiscordStatus(dbId, DROPPED);
            ch.warn("Discord queue for '" + event.category().key() + "' is full; dropping Discord messages (still stored locally).");
            return;
        }
        ch.queue.offer(new Job(event, dbId));
    }

    public Map<Category, Stats> stats() {
        Map<Category, Stats> out = new EnumMap<>(Category.class);
        for (var e : channels.entrySet()) {
            e.getValue().stats.queued = e.getValue().queue.size();
            out.put(e.getKey(), e.getValue().stats);
        }
        return out;
    }

    private String deliver(Channel ch, Job job) {
        Settings s = settings;
        Settings.Webhook wh = s.webhook(ch.category);
        if (!wh.deliverable()) return SKIPPED;
        try {
            List<Embed> embeds = EmbedFactory.build(job.event(), s);
            boolean first = true;
            for (Embed embed : embeds) { // one embed per message keeps every message far below Discord limits
                String content = null;
                if (first && job.event().alertReason() != null && s.alertsEnabled && !s.alertMention.isEmpty()) {
                    content = s.alertMention + " ⚠ " + job.event().alertReason();
                }
                first = false;
                if (!post(ch, wh.url(), payload(s, embed, content))) {
                    ch.stats.failed.incrementAndGet();
                    return FAILED;
                }
                if (embeds.size() > 1) Thread.sleep(s.minIntervalMs);
            }
            ch.stats.sent.incrementAndGet();
            return SENT;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ch.stats.failed.incrementAndGet();
            return FAILED;
        } catch (RuntimeException ex) {
            ch.stats.failed.incrementAndGet();
            ch.warn("Unexpected error while delivering '" + ch.category.key() + "': " + ex.getClass().getSimpleName());
            return FAILED;
        }
    }

    private static String payload(Settings s, Embed embed, String content) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"username\":").append(Json.quote(s.discordUsername));
        if (s.discordAvatar != null && !s.discordAvatar.isBlank()) sb.append(",\"avatar_url\":").append(Json.quote(s.discordAvatar));
        if (content != null) sb.append(",\"content\":").append(Json.quote(content));
        // only the configured alert mention may ping; logged text never can
        sb.append(",\"allowed_mentions\":").append(content != null ? s.alertAllowedMentions : "{\"parse\":[]}");
        sb.append(",\"embeds\":[").append(embed.toJson()).append("]}");
        return sb.toString();
    }

    /** POST with 429 / 5xx / network retry. Never logs the webhook URL. */
    private boolean post(Channel ch, String url, String json) throws InterruptedException {
        Settings s = settings;
        long backoff = s.retryBackoffMs;
        for (int attempt = 0; attempt <= s.maxRetries; attempt++) {
            if (attempt > 0 && !ch.running && attempt > 1) return false; // shutting down: don't hang
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(s.requestTimeoutSec))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "AdminWatch/" + plugin.getPluginMeta().getVersion())
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code >= 200 && code < 300) return true;
                if (code == 429) {
                    Thread.sleep(retryAfterMs(resp, backoff));
                    continue;
                }
                if (code >= 500) {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 60_000);
                    continue;
                }
                ch.warn("Discord rejected a '" + ch.category.key() + "' message (HTTP " + code + ")"
                        + (code == 404 || code == 401 ? " - check that the webhook still exists." : "."));
                return false;
            } catch (IOException ex) {
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 60_000);
            } catch (IllegalArgumentException ex) {
                ch.warn("Webhook for '" + ch.category.key() + "' has an unusable URL.");
                return false;
            }
        }
        ch.warn("Could not deliver a '" + ch.category.key() + "' message after " + (s.maxRetries + 1) + " attempts; kept locally.");
        return false;
    }

    private static long retryAfterMs(HttpResponse<String> resp, long fallback) {
        try {
            var header = resp.headers().firstValue("Retry-After");
            if (header.isPresent()) return Math.min(60_000, (long) (Double.parseDouble(header.get()) * 1000) + 100);
            Matcher m = RETRY_AFTER.matcher(resp.body() == null ? "" : resp.body());
            if (m.find()) return Math.min(60_000, (long) (Double.parseDouble(m.group(1)) * 1000) + 100);
        } catch (RuntimeException ignored) {
            // use fallback
        }
        return fallback;
    }

    /** Tries to deliver what is queued for up to drainMillis, then stops the workers. */
    public void shutdown(long drainMillis) {
        long deadline = System.currentTimeMillis() + drainMillis;
        for (Channel ch : channels.values()) ch.running = false; // workers exit once their queue is empty
        List<Channel> all = new ArrayList<>(channels.values());
        for (Channel ch : all) {
            long left = Math.max(1, deadline - System.currentTimeMillis());
            try {
                ch.thread.join(left);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            if (ch.thread.isAlive()) ch.thread.interrupt();
        }
    }
}
