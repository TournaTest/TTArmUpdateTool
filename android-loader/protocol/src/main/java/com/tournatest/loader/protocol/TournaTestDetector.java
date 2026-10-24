package com.tournatest.loader.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class TournaTestDetector {
    private TournaTestDetector() {}

    public static void identify(SerialTransport port, Timing timing) throws IOException, InterruptedException {
        port.discardInput();
        port.setDtr(false);
        timing.sleep(50);
        port.setDtr(true);
        timing.sleep(50);
        port.setDtr(false);
        long deadline = timing.nowMs() + 15_000;
        StringBuilder received = new StringBuilder();
        byte[] buffer = new byte[512];
        while (timing.nowMs() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int n = port.read(buffer, 250);
            if (n > 0) {
                received.append(new String(buffer, 0, n, StandardCharsets.US_ASCII));
                // Preserve a suffix so the signature may span arbitrary USB read boundaries.
                if (received.indexOf("Initializing channels") >= 0) return;
                if (received.length() > 1024) received.delete(0, received.length() - 128);
            }
        }
        throw new IOException("USB opened, but no TTArm startup message was detected within 15 seconds. "
                + "Enable Force flash to attempt an update without the message check.");
    }
}
