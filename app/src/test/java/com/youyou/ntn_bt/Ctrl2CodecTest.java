package com.youyou.ntn_bt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class Ctrl2CodecTest {
    @Test
    public void versionQueryMatchesGoldenFrame() {
        byte[] payload = "AT+MCUVER?\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = Ctrl2Codec.encode(Ctrl2Codec.TYPE_LOCAL_REQUEST, 1, payload);

        assertEquals(0xAA, frame[0] & 0xFF);
        assertEquals(0x55, frame[1] & 0xFF);
        assertEquals(0x02, frame[2] & 0xFF);
        assertEquals(12, frame[7] & 0xFF);
        assertEquals(0x9C, frame[20] & 0xFF);
        assertEquals(0xF6, frame[21] & 0xFF);
    }

    @Test
    public void fragmentedFrameIsReassembled() {
        byte[] payload = "\r\nOK\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = Ctrl2Codec.encode(Ctrl2Codec.TYPE_LOCAL_FINAL, 0x1234, payload);
        Ctrl2Codec codec = new Ctrl2Codec();

        assertTrue(codec.feed(frame, 3).isEmpty());
        byte[] remainder = Arrays.copyOfRange(frame, 3, frame.length);
        List<Ctrl2Codec.Frame> frames = codec.feed(remainder, remainder.length);

        assertEquals(1, frames.size());
        assertEquals(Ctrl2Codec.TYPE_LOCAL_FINAL, frames.get(0).type);
        assertEquals(0x1234, frames.get(0).requestId);
        assertArrayEquals(payload, frames.get(0).payload);
    }

    @Test
    public void badCrcResynchronizesToFollowingFrame() {
        byte[] bad = Ctrl2Codec.encode(Ctrl2Codec.TYPE_LOCAL_FINAL, 7,
                "bad".getBytes(StandardCharsets.US_ASCII));
        bad[bad.length - 1] ^= 0x01;
        byte[] good = Ctrl2Codec.encode(Ctrl2Codec.TYPE_EVENT, 0,
                "{}".getBytes(StandardCharsets.US_ASCII));
        byte[] input = new byte[bad.length + good.length];
        System.arraycopy(bad, 0, input, 0, bad.length);
        System.arraycopy(good, 0, input, bad.length, good.length);

        List<Ctrl2Codec.Frame> frames = new Ctrl2Codec().feed(input, input.length);

        assertEquals(1, frames.size());
        assertEquals(Ctrl2Codec.TYPE_EVENT, frames.get(0).type);
        assertArrayEquals("{}".getBytes(StandardCharsets.US_ASCII), frames.get(0).payload);
    }
}
