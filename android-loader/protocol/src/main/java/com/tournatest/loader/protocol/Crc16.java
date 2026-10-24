package com.tournatest.loader.protocol;

/** CRC-16/CCITT-FALSE, as used by Adafruit's legacy DFU packages and HCI frames. */
public final class Crc16 {
    private Crc16() {}

    public static int calculate(byte[] data) {
        return calculate(data, 0, data.length);
    }

    public static int calculate(byte[] data, int offset, int length) {
        int crc = 0xffff;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
