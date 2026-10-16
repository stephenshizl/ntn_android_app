package com.youyou.ntn_bt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** MCU/AC6323A UART application protocol. The Bluetooth chip transports these bytes unchanged. */
public final class Ctrl2Codec {
    public static final int VERSION = 0x02;
    public static final int MAX_PAYLOAD = 2048;

    public static final int TYPE_LOCAL_REQUEST = 0x01;
    public static final int TYPE_LOCAL_FINAL = 0x02;
    public static final int TYPE_NTN_REQUEST = 0x03;
    public static final int TYPE_NTN_FRAGMENT = 0x04;
    public static final int TYPE_NTN_ASYNC = 0x05;
    public static final int TYPE_EVENT = 0x06;
    public static final int TYPE_NTN_FINAL = 0x07;
    public static final int TYPE_LOCAL_PENDING = 0x08;

    private static final int SYNC_1 = 0xAA;
    private static final int SYNC_2 = 0x55;
    private static final int OVERHEAD = 10;
    private final byte[] stream = new byte[4096];
    private int streamLength;

    public static final class Frame {
        public final int type;
        public final int requestId;
        public final byte[] payload;

        Frame(int type, int requestId, byte[] payload) {
            this.type = type;
            this.requestId = requestId;
            this.payload = payload;
        }
    }

    public static byte[] encode(int type, int requestId, byte[] payload) {
        if (requestId < 0 || requestId > 0xFFFF) {
            throw new IllegalArgumentException("requestId out of range");
        }
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large");
        }
        byte[] frame = new byte[OVERHEAD + body.length];
        frame[0] = (byte) SYNC_1;
        frame[1] = (byte) SYNC_2;
        frame[2] = (byte) VERSION;
        frame[3] = (byte) type;
        frame[4] = (byte) (requestId >>> 8);
        frame[5] = (byte) requestId;
        frame[6] = (byte) (body.length >>> 8);
        frame[7] = (byte) body.length;
        System.arraycopy(body, 0, frame, 8, body.length);
        int crc = crc16(frame, 2, 6 + body.length);
        frame[8 + body.length] = (byte) (crc >>> 8);
        frame[9 + body.length] = (byte) crc;
        return frame;
    }

    public synchronized List<Frame> feed(byte[] data, int count) {
        if (data == null || count < 0 || count > data.length) {
            throw new IllegalArgumentException("invalid input");
        }
        for (int i = 0; i < count; i++) {
            if (streamLength == stream.length) {
                dropPrefix(1);
            }
            stream[streamLength++] = data[i];
        }

        List<Frame> frames = new ArrayList<>();
        while (streamLength >= 2) {
            int sync = 0;
            while (sync + 1 < streamLength
                    && ((stream[sync] & 0xFF) != SYNC_1
                    || (stream[sync + 1] & 0xFF) != SYNC_2)) {
                sync++;
            }
            if (sync > 0) {
                dropPrefix(sync);
            }
            if (streamLength < OVERHEAD) {
                break;
            }
            if ((stream[2] & 0xFF) != VERSION) {
                dropPrefix(1);
                continue;
            }
            int payloadLength = ((stream[6] & 0xFF) << 8) | (stream[7] & 0xFF);
            if (payloadLength > MAX_PAYLOAD) {
                dropPrefix(1);
                continue;
            }
            int frameLength = OVERHEAD + payloadLength;
            if (streamLength < frameLength) {
                break;
            }
            int expected = ((stream[8 + payloadLength] & 0xFF) << 8)
                    | (stream[9 + payloadLength] & 0xFF);
            int actual = crc16(stream, 2, 6 + payloadLength);
            if (expected != actual) {
                dropPrefix(1);
                continue;
            }
            int requestId = ((stream[4] & 0xFF) << 8) | (stream[5] & 0xFF);
            frames.add(new Frame(stream[3] & 0xFF, requestId,
                    Arrays.copyOfRange(stream, 8, 8 + payloadLength)));
            dropPrefix(frameLength);
        }
        return frames;
    }

    private void dropPrefix(int count) {
        if (count >= streamLength) {
            streamLength = 0;
            return;
        }
        System.arraycopy(stream, count, stream, 0, streamLength - count);
        streamLength -= count;
    }

    static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public Ctrl2Codec() {
    }
}
