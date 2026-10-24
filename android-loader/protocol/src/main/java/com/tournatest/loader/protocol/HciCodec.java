package com.tournatest.loader.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/** Adafruit legacy serial DFU HCI framing; see THIRD_PARTY_NOTICES.md. */
public final class HciCodec {
    private HciCodec() {}

    public static byte[] packet(int sequence, byte[] payload) {
        if (sequence < 0 || sequence > 7 || payload.length > 4095) throw new IllegalArgumentException();
        byte[] raw = new byte[payload.length + 6];
        raw[0] = (byte) (sequence | (((sequence + 1) & 7) << 3) | 0xc0);
        raw[1] = (byte) (14 | ((payload.length & 15) << 4));
        raw[2] = (byte) (payload.length >>> 4);
        // Nordic's HCI implementation uses a two's-complement header checksum.
        raw[3] = (byte) -(unsigned(raw[0]) + unsigned(raw[1]) + unsigned(raw[2]));
        System.arraycopy(payload, 0, raw, 4, payload.length);
        int crc = Crc16.calculate(raw, 0, raw.length - 2);
        raw[raw.length - 2] = (byte) crc;
        raw[raw.length - 1] = (byte) (crc >>> 8);
        return slip(raw);
    }

    public static byte[] slip(byte[] raw) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0xc0);
        for (byte value : raw) {
            int b = unsigned(value);
            if (b == 0xc0) { output.write(0xdb); output.write(0xdc); }
            else if (b == 0xdb) { output.write(0xdb); output.write(0xdd); }
            else output.write(b);
        }
        output.write(0xc0);
        return output.toByteArray();
    }

    public static int acknowledgement(byte[] frame) throws IOException {
        if (frame.length < 4 || ((unsigned(frame[0]) + unsigned(frame[1])
                + unsigned(frame[2]) + unsigned(frame[3])) & 255) != 0) {
            throw new IOException("Invalid bootloader acknowledgement header.");
        }
        int size = (unsigned(frame[1]) >>> 4) | (unsigned(frame[2]) << 4);
        boolean crcPresent = (frame[0] & 0x40) != 0;
        if ((frame[1] & 15) != 0 || size != 0 || (frame[0] & 0x80) != 0
                || frame.length != 4 + (crcPresent ? 2 : 0)) {
            throw new IOException("Unexpected bootloader acknowledgement packet.");
        }
        if (crcPresent) {
            int crc = unsigned(frame[4]) | (unsigned(frame[5]) << 8);
            if (Crc16.calculate(frame, 0, 4) != crc) throw new IOException("Acknowledgement CRC failed.");
        }
        return (unsigned(frame[0]) >>> 3) & 7;
    }

    private static int unsigned(byte b) { return b & 255; }

    /** Keeps partial frames and multiple frames returned in a single USB read. */
    public static final class Decoder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ArrayDeque<byte[]> complete = new ArrayDeque<>();
        private boolean started;
        private boolean escape;
        private boolean invalid;

        public void accept(byte[] bytes, int length) {
            for (int i = 0; i < length; i++) {
                int b = unsigned(bytes[i]);
                if (b == 0xc0) {
                    if (started && !invalid && !escape && buffer.size() > 0 && complete.size() < 32) {
                        complete.add(buffer.toByteArray());
                    }
                    started = true;
                    escape = false;
                    invalid = false;
                    buffer.reset();
                } else if (started && !invalid) {
                    if (escape) {
                        if (b == 0xdc) buffer.write(0xc0);
                        else if (b == 0xdd) buffer.write(0xdb);
                        else invalid = true;
                        escape = false;
                    } else if (b == 0xdb) {
                        escape = true;
                    } else {
                        buffer.write(b);
                    }
                    if (buffer.size() > 4102) invalid = true;
                }
            }
        }

        public byte[] poll() { return complete.poll(); }
    }
}
