package com.tournatest.loader.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Application-only implementation of Adafruit nrfutil's DFU 0.5 serial transport. */
public final class LegacyDfu {
    public interface Listener {
        void status(String message, int percent);
    }

    private final SerialTransport port;
    private final Timing timing;
    private final Listener listener;
    private final HciCodec.Decoder decoder = new HciCodec.Decoder();
    private int sequence;
    private boolean used;

    public LegacyDfu(SerialTransport port, Timing timing, Listener listener) {
        this.port = port;
        this.timing = timing;
        this.listener = listener;
    }

    /** Caller must identify the TournaTest first and keep the connection alive until this returns. */
    public void transfer(FirmwarePackage firmware) throws IOException, InterruptedException {
        if (used) throw new IllegalStateException("Create a new DFU session for each update.");
        used = true;
        byte[] binary = firmware.application();
        byte[] init = firmware.initPacket();
        listener.status("Entering bootloader", 0);
        port.discardInput();
        timing.sleep(100);
        port.setDtr(false);
        timing.sleep(50);
        port.setDtr(true);
        timing.sleep(100);
        listener.status("Preparing firmware storage", 0);
        send(words(3, 4, 0, 0, binary.length), 6000); // START, application mode, SD/BL/application sizes.
        int pages = binary.length / 4096 + 1;
        long eraseMs = Math.max(500, (long) Math.ceil(pages * 89.7));
        timing.sleep(eraseMs);
        ByteBuffer initPayload = ByteBuffer.allocate(4 + init.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        initPayload.putInt(1).put(init).putShort((short) 0); // Required word alignment padding.
        send(initPayload.array(), 1500);
        for (int offset = 0, count = 0; offset < binary.length; offset += 512, count++) {
            int length = Math.min(512, binary.length - offset);
            ByteBuffer payload = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
            payload.putInt(4).put(binary, offset, length);
            send(payload.array(), 1500);
            listener.status("Sending firmware", (int) ((offset + length) * 95L / binary.length));
            // Match Adafruit's pacing, including the first frame, to allow flash writes.
            if (count % 8 == 0) timing.sleep(103);
        }
        timing.sleep(103);
        send(words(5), 2000); // STOP triggers image validation and activation in the bootloader.
        listener.status("Activating firmware — keep the USB cable connected", 97);
        // Preserve the full dual-bank erase/copy delay before any serial close or reset.
        timing.sleep(eraseMs + (long) Math.ceil(pages * 102.4));
        listener.status("Firmware transfer complete", 99);
    }

    private void send(byte[] payload, int timeoutMs) throws IOException, InterruptedException {
        sequence = (sequence + 1) & 7; // Adafruit starts its first HCI packet at sequence 1.
        byte[] packet = HciCodec.packet(sequence, payload);
        int expectedAck = (sequence + 1) & 7;
        byte[] buffer = new byte[256];
        for (int attempt = 0; attempt < 3; attempt++) {
            port.write(packet, 5000); // A partial USB write fails; it must not restart the frame.
            long deadline = timing.nowMs() + timeoutMs;
            while (timing.nowMs() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                byte[] frame;
                while ((frame = decoder.poll()) != null) {
                    try {
                        if (HciCodec.acknowledgement(frame) == expectedAck) return;
                    } catch (IOException ignored) {
                        // Ignore malformed/noisy or stale frames; only the expected ACK advances DFU.
                    }
                }
                int n = port.read(buffer, (int) Math.max(1, Math.min(250, deadline - timing.nowMs())));
                if (n > 0) decoder.accept(buffer, n);
            }
        }
        throw new IOException("Bootloader did not acknowledge packet " + sequence
                + ". Update stopped. Reconnect the test box before retrying.");
    }

    private static byte[] words(int... values) {
        ByteBuffer data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) data.putInt(value);
        return data.array();
    }
}
