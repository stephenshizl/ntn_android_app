package com.youyou.ntn_bt;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.util.Locale;

/** A terminal GNSS query snapshot. No phone location is used. */
final class GnssReading {
    static final long UNKNOWN_AGE = 0xFFFFFFFFL;
    static final long FRESH_MS = 5000L;
    final boolean configured, reportedValid, altitudeValid;
    final Long latitudeE7, longitudeE7, altitudeMm, speedCmS;
    final int satellites, fixQuality;
    final String constellations;
    final long ageMs, receivedMs;

    GnssReading(boolean configured, boolean valid, Long latitudeE7, Long longitudeE7,
                Long altitudeMm, boolean altitudeValid, Long speedCmS, int satellites,
                int fixQuality, String constellations, long ageMs, long receivedMs) {
        if (ageMs < 0 || ageMs > UNKNOWN_AGE) throw new IllegalArgumentException("Invalid age");
        this.configured = configured; this.reportedValid = valid;
        this.latitudeE7 = latitudeE7; this.longitudeE7 = longitudeE7;
        this.altitudeMm = altitudeMm; this.altitudeValid = altitudeValid;
        this.speedCmS = speedCmS; this.satellites = satellites;
        this.fixQuality = fixQuality; this.constellations = constellations;
        this.ageMs = ageMs; this.receivedMs = receivedMs;
    }

    static GnssReading fromJson(String json, long receivedMs) throws JSONException {
        JSONObject data = new JSONObject(json);
        return new GnssReading(data.optBoolean("configured", false), data.getBoolean("valid"),
                optionalLong(data, "lat_e7"), optionalLong(data, "lon_e7"),
                optionalLong(data, "alt_mm"), data.optBoolean("alt_valid", false),
                optionalLong(data, "speed_cm_s"), data.optInt("sats", -1),
                data.optInt("fix_quality", -1), data.optString("constellations", "—"),
                data.getLong("age_ms"), receivedMs);
    }

    private static Long optionalLong(JSONObject data, String key) throws JSONException {
        return data.has(key) && !data.isNull(key) ? data.getLong(key) : null;
    }

    boolean hasCoordinates() {
        return latitudeE7 != null && longitudeE7 != null
                && latitudeE7 >= -900000000L && latitudeE7 <= 900000000L
                && longitudeE7 >= -1800000000L && longitudeE7 <= 1800000000L;
    }

    static long currentAge(long reported, long received, long now) {
        if (reported == UNKNOWN_AGE) return UNKNOWN_AGE;
        return Math.min(UNKNOWN_AGE, reported + Math.max(0, now - received));
    }

    long currentAge(long now) { return currentAge(ageMs, receivedMs, now); }
    boolean isFresh(long now) {
        return configured && reportedValid && hasCoordinates() && currentAge(now) <= FRESH_MS;
    }
    String latitude() { return String.format(Locale.US, "%.7f", latitudeE7 / 10000000.0); }
    String longitude() { return String.format(Locale.US, "%.7f", longitudeE7 / 10000000.0); }
    String altitude() { return String.format(Locale.US, "%.3f", altitudeMm / 1000.0); }
    String speed() { return String.format(Locale.US, "%.2f", speedCmS / 100.0); }

    static final class TimeReading {
        final boolean valid;
        final String utc, source;
        final long ageMs, receivedMs;
        private TimeReading(boolean valid, String utc, String source, long age, long received) {
            this.valid = valid; this.utc = utc; this.source = source;
            this.ageMs = age; this.receivedMs = received;
        }
        static TimeReading parse(String value, long received) {
            String[] fields = value.trim().split(",", -1);
            if (fields.length != 4 || !(fields[0].equals("0") || fields[0].equals("1")))
                throw new IllegalArgumentException("Invalid MCUTIME");
            long age = Long.parseLong(fields[3]);
            if (age < 0 || age > UNKNOWN_AGE) throw new IllegalArgumentException("Invalid time age");
            boolean valid = fields[0].equals("1");
            if (valid) {
                if (!fields[1].endsWith("Z")) throw new IllegalArgumentException("UTC timezone missing");
                try { Instant.parse(fields[1]); }
                catch (java.time.DateTimeException error) {
                    throw new IllegalArgumentException("Invalid UTC", error);
                }
            }
            return new TimeReading(valid, valid ? fields[1] : "", fields[2], age, received);
        }
        long currentAge(long now) { return GnssReading.currentAge(ageMs, receivedMs, now); }
        boolean isFresh(long now) { return valid && currentAge(now) <= FRESH_MS; }
    }
}
