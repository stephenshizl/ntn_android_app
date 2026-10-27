package com.youyou.ntn_bt;

import java.util.Locale;

/** Normalize only the AT command name; case-sensitive arguments stay intact. */
final class AtCommand {
    static String normalize(String input) {
        String text = input == null ? "" : input.trim();
        if (text.length() < 2 || text.length() > Ctrl2Codec.MAX_PAYLOAD - 2
                || !text.regionMatches(true, 0, "AT", 0, 2))
            throw new IllegalArgumentException("Invalid AT command");
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x20 || ch > 0x7e) throw new IllegalArgumentException("Single ASCII command required");
            if ((ch == '=' || ch == ' ') && end == text.length()) end = i;
        }
        return text.substring(0, end).toUpperCase(Locale.US) + text.substring(end);
    }
}
