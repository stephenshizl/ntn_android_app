package com.youyou.ntn_bt;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class BluetoothOtaPolicyTest {
    @Test public void vendorSdkDecodesTheBluetoothFirmwareVersion() {
        com.jieli.jl_bt_ota.model.response.device.Version version = new com.jieli.jl_bt_ota.model.response.device.Version();
        assertEquals(2, version.parse(new byte[]{0x13, 0x00}));
        assertEquals(0x1300, version.getVersionCode());
        assertEquals("1.3.0.0", version.getVersionName());
    }
    @Test public void onlyBluetoothUfwContainersAreAccepted() {
        BluetoothFirmwareFile.checkName("ntn-bt-ac6323a-1.3.ufw");
        BluetoothFirmwareFile.checkName("update.UFW");
        for (String name : new String[]{"mcu.bin", "mcu.hex", "ntn.zip", "update.ufw.bin", "../update.ufw", "a\\b.ufw", "", "bad\0.ufw"}) {
            try { BluetoothFirmwareFile.checkName(name); fail(name); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void boundedImportRejectsEmptyShortAndOversizedStreams() throws Exception {
        for (int size : new int[]{0, 1, 31}) {
            try { BluetoothFirmwareFile.copyBounded(new ByteArrayInputStream(new byte[size]), new ByteArrayOutputStream()); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        byte[] realContainerSize = new byte[1216160];
        assertEquals(realContainerSize.length, BluetoothFirmwareFile.copyBounded(new ByteArrayInputStream(realContainerSize), new ByteArrayOutputStream()));
        try { BluetoothFirmwareFile.copyBounded(new ByteArrayInputStream(new byte[(int)BluetoothFirmwareFile.MAX_BYTES + 1]), new ByteArrayOutputStream()); fail(); }
        catch (IOException expected) { }
    }
    @Test public void changedImportedFileIsDetectedBeforeUpdate() throws Exception {
        File file = File.createTempFile("bt-fw-", ".ufw");
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[64]); }
            BluetoothFirmwareFile firmware = new BluetoothFirmwareFile(file, "update.ufw", 64, BluetoothFirmwareFile.digest(file));
            assertTrue(firmware.unchanged());
            try (RandomAccessFile changed = new RandomAccessFile(file, "rw")) { changed.write(1); }
            assertFalse(firmware.unchanged());
        } finally { file.delete(); }
    }
    @Test public void chunkingPreservesBinaryPacketsAndWaitsForAcknowledgement() {
        OtaWriteQueue queue = new OtaWriteQueue(4096);
        byte[] first = new byte[512], second = new byte[13];
        for (int i = 0; i < first.length; i++) first[i] = (byte)i;
        Arrays.fill(second, (byte)0x7e);
        assertTrue(queue.offer(first)); assertTrue(queue.offer(second));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (queue.pendingBytes() != 0) {
            byte[] next = queue.next(20); assertNotNull(next); assertTrue(next.length <= 20);
            assertNull(queue.next(20)); result.write(next, 0, next.length);
            assertTrue(queue.complete(true));
        }
        byte[] expected = new byte[525]; System.arraycopy(first, 0, expected, 0, 512); System.arraycopy(second, 0, expected, 512, 13);
        assertArrayEquals(expected, result.toByteArray());
    }
    @Test public void rejectionAndFailedWritesCannotSilentlyDropPartOfAPacket() {
        OtaWriteQueue queue = new OtaWriteQueue(30);
        assertFalse(queue.offer(new byte[31])); assertEquals(0, queue.pendingBytes());
        assertTrue(queue.offer(new byte[30])); assertFalse(queue.offer(new byte[1]));
        assertEquals(20, queue.next(20).length);
        assertFalse(queue.complete(false)); assertEquals(0, queue.pendingBytes()); assertNull(queue.next(20));
        assertFalse(queue.complete(true));
    }
    @Test public void queuedPacketsAreCopiedAndMtuCanChangeBetweenWrites() {
        OtaWriteQueue queue = new OtaWriteQueue(1000);
        byte[] data = new byte[300]; Arrays.fill(data, (byte)9); assertTrue(queue.offer(data)); Arrays.fill(data, (byte)0);
        byte[] a = queue.next(244); assertEquals(244, a.length); assertEquals(9, a[0]); queue.complete(true);
        assertEquals(20, queue.next(20).length); queue.complete(true);
        assertEquals(36, queue.next(244).length); queue.complete(true); assertEquals(0, queue.pendingBytes());
    }
}
