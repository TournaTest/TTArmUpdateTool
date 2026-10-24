package com.tournatest.loader.protocol;

import java.io.IOException;

/** Shared update policy, including the explicit override of application-message checks. */
public final class UpdateWorkflow {
    public interface ConnectionFactory { SerialTransport open() throws IOException; }
    public enum Completion { RESTART_CONFIRMED, TRANSFER_COMPLETE }

    private UpdateWorkflow() {}

    public static void checkDevice(boolean forceFlash, ConnectionFactory connections, Timing timing)
            throws IOException, InterruptedException {
        try (SerialTransport port = connections.open()) {
            if (!forceFlash) TournaTestDetector.identify(port, timing);
        }
    }

    public static Completion install(FirmwarePackage firmware, boolean forceFlash,
            ConnectionFactory connections, Timing timing, LegacyDfu.Listener listener)
            throws IOException, InterruptedException {
        try (SerialTransport port = connections.open()) {
            if (!forceFlash) TournaTestDetector.identify(port, timing);
            new LegacyDfu(port, timing, listener).transfer(firmware);
        }
        if (forceFlash) return Completion.TRANSFER_COMPLETE;
        listener.status("Checking that TournaTest restarted", 99);
        try (SerialTransport port = connections.open()) {
            TournaTestDetector.identify(port, timing);
        }
        return Completion.RESTART_CONFIRMED;
    }
}
