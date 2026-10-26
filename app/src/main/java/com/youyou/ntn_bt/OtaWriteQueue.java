package com.youyou.ntn_bt;

import java.util.ArrayDeque;
import java.util.Arrays;

/** One GATT write in flight; chunk boundaries never change the RCSP byte stream. */
final class OtaWriteQueue {
    private final ArrayDeque<byte[]> packets = new ArrayDeque<>();
    private final int capacity;
    private int offset, pending, inFlight;
    OtaWriteQueue(int capacity) { this.capacity = capacity; }
    synchronized boolean offer(byte[] data) {
        if (data == null || data.length == 0 || data.length > capacity - pending) return false;
        packets.add(data.clone()); pending += data.length; return true;
    }
    synchronized byte[] next(int payload) {
        if (inFlight != 0 || packets.isEmpty() || payload < 1) return null;
        byte[] head = packets.peek();
        inFlight = Math.min(payload, head.length - offset);
        return Arrays.copyOfRange(head, offset, offset + inFlight);
    }
    synchronized boolean complete(boolean success) {
        if (inFlight == 0) return false;
        if (!success) { clear(); return false; }
        offset += inFlight; pending -= inFlight; inFlight = 0;
        if (offset == packets.peek().length) { packets.remove(); offset = 0; }
        return true;
    }
    synchronized void clear() { packets.clear(); offset = pending = inFlight = 0; }
    synchronized int pendingBytes() { return pending; }
}
