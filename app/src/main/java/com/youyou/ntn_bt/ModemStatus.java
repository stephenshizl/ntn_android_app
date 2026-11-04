package com.youyou.ntn_bt;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decode read-only modem status replies without treating errors as absence. */
final class ModemStatus {
    static boolean shouldRefresh(boolean enabled, boolean connected, boolean foreground,
                                 boolean pageVisible, boolean idle, long now, long lastQuery) {
        return enabled && connected && foreground && pageVisible && idle && now - lastQuery >= 5000L;
    }

    private static final Pattern REGISTRATION = Pattern.compile(
            "(?m)^\\+CEREG:[ \\t]*(\\d+)(?:[ \\t]*,[ \\t]*(\\d+)(?:[ \\t]*,.*)?)?[ \\t]*\\r?$");
    private static final Pattern SIM = Pattern.compile("(?m)^\\+CPIN:[ \\t]*([^\\r\\n]+)\\r?$");

    static int registration(String response) {
        Matcher match = REGISTRATION.matcher(response);
        if (!match.find()) return -1;
        try { return Integer.parseInt(match.group(2) != null ? match.group(2) : match.group(1)); }
        catch (NumberFormatException invalid) { return -1; }
    }

    static String sim(String response) {
        Matcher match = SIM.matcher(response);
        return match.find() ? match.group(1).trim().toUpperCase(Locale.US) : "UNKNOWN";
    }

    static int registrationLabel(int stat) {
        switch (stat) {
            case 0: return R.string.net_not_registered;
            case 1: return R.string.net_home;
            case 2: return R.string.net_searching;
            case 3: return R.string.net_denied;
            case 5: return R.string.net_roaming;
            default: return R.string.net_unknown;
        }
    }

    static int simLabel(String state) {
        switch (state) {
            case "READY": return R.string.sim_ready;
            case "SIM PIN": return R.string.sim_pin;
            case "SIM PUK": return R.string.sim_puk;
            case "SIM PIN2": return R.string.sim_pin2;
            case "SIM PUK2": return R.string.sim_puk2;
            case "NOT READY": return R.string.sim_not_ready;
            case "NOT INSERTED": return R.string.sim_absent;
            default: return R.string.sim_unknown;
        }
    }
}
