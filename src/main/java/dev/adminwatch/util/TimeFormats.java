package dev.adminwatch.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/** Timestamp formatting using the configured zone and patterns. */
public final class TimeFormats {
    private final DateTimeFormatter full;
    private final DateTimeFormatter shortFmt;

    public TimeFormats(String zone, String fullPattern, String shortPattern, Logger log) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (Exception ex) {
            log.warning("Invalid time.timezone '" + zone + "', falling back to UTC.");
            zoneId = ZoneId.of("UTC");
        }
        this.full = build(fullPattern, "yyyy-MM-dd HH:mm:ss z", zoneId, log);
        this.shortFmt = build(shortPattern, "HH:mm:ss z", zoneId, log);
    }

    private static DateTimeFormatter build(String pattern, String fallback, ZoneId zone, Logger log) {
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(zone);
        } catch (Exception ex) {
            log.warning("Invalid time format '" + pattern + "', using " + fallback);
            return DateTimeFormatter.ofPattern(fallback).withZone(zone);
        }
    }

    public String full(Instant t) { return full.format(t); }
    public String shortTime(Instant t) { return shortFmt.format(t); }

    /** e.g. "2m 33s" */
    public static String duration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
