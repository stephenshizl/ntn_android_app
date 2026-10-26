package com.youyou.ntn_bt;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Bluetooth-only import policy. MCU and NTN images use different updaters. */
final class BluetoothFirmwareFile {
    // UFW is a container with loaders and alignment variants, not a raw Flash dump.
    static final long MAX_BYTES = 4 * 1024 * 1024;
    final File file;
    final String displayName, sha256;
    final long size;

    BluetoothFirmwareFile(File file, String name, long size, String hash) {
        this.file = file; displayName = name; this.size = size; sha256 = hash;
    }

    static void checkName(String name) {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".ufw")
                || name.indexOf('\0') >= 0 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Bluetooth firmware must be a .ufw file");
        }
    }

    static void checkSize(long size) {
        if (size < 32 || size > MAX_BYTES) throw new IllegalArgumentException("Invalid UFW size");
    }

    static long copyBounded(InputStream in, OutputStream out) throws IOException {
        byte[] block = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(block)) != -1;) {
            if (n == 0) continue;
            total += n;
            if (total > MAX_BYTES) throw new IOException("Firmware exceeds import limit");
            out.write(block, 0, n);
        }
        checkSize(total);
        return total;
    }

    static String digest(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] block = new byte[8192];
                for (int n; (n = in.read(block)) != -1;) if (n > 0) digest.update(block, 0, n);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    boolean unchanged() throws IOException { return file.length() == size && digest(file).equals(sha256); }
}
