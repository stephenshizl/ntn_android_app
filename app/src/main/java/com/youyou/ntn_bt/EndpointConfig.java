package com.youyou.ntn_bt;

/** Matches the MCU IPv4/domain and UDP port limits without DNS/network traffic. */
final class EndpointConfig {
    static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.length() < 1 || label.length() > 63
                    || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) return false;
        }
        if (host.matches("[0-9.]+")) {
            if (labels.length != 4) return false;
            for (String label : labels)
                if (label.length() > 3 || Integer.parseInt(label) > 255) return false;
        }
        return true;
    }
    static int port(String text, boolean local) {
        if (text == null || !text.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid port");
        int value = Integer.parseInt(text);
        if (value > 65535 || (!local && value == 0)) throw new IllegalArgumentException("Invalid port");
        return value;
    }
}
