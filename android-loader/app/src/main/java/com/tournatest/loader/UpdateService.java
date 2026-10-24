package com.tournatest.loader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.tournatest.loader.protocol.FirmwarePackage;
import com.tournatest.loader.protocol.LegacyDfu;
import com.tournatest.loader.protocol.Timing;
import com.tournatest.loader.protocol.TournaTestDetector;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the connection and worker independently of the Activity's lifecycle. */
public final class UpdateService extends Service {
    public static final String DEVICE = "device";
    public static final String FIRMWARE = "firmware";
    public static final String NAME = "name";
    private static final String CHANNEL = "firmware_update";
    private static final int NOTIFICATION = 1;

    public enum Phase { IDLE, CHECKING, CONFIRM, WRITING, SUCCESS, ERROR, CANCELLED }
    public static final class State {
        public final Phase phase;
        public final String message;
        public final int percent;
        public final String log;
        public final String firmware;
        public final String device;
        public final String hash;
        public final int size;
        State(Phase phase, String message, int percent, String log,
                String firmware, String device, String hash, int size) {
            this.phase = phase;
            this.message = message;
            this.percent = percent;
            this.log = log;
            this.firmware = firmware;
            this.device = device;
            this.hash = hash;
            this.size = size;
        }
        public boolean busy() { return phase == Phase.CHECKING || phase == Phase.CONFIRM || phase == Phase.WRITING; }
    }
    public interface Observer { void changed(State state); }
    public final class LocalBinder extends Binder {
        UpdateService service() { return UpdateService.this; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();
    private final StringBuilder log = new StringBuilder();
    private State state = new State(Phase.IDLE, "Ready to connect", 0, "", "", "", "", 0);
    private Observer observer;
    private FirmwarePackage firmware;
    private String deviceName;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private String lastStatus = "";
    private int lastNotificationPercent = -1;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Firmware updates", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "TournaTest:FirmwareUpdate");
        wakeLock.setReferenceCounted(false);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public State state() { return state; }
    public void observe(Observer next) {
        observer = next;
        if (next != null) next.changed(state);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (state.busy()) return START_NOT_STICKY;
        deviceName = intent.getStringExtra(DEVICE);
        String reference = intent.getStringExtra(FIRMWARE);
        String name = intent.getStringExtra(NAME);
        firmware = null;
        log.setLength(0);
        lastStatus = "";
        lastNotificationPercent = -1;
        state = new State(Phase.CHECKING, "Checking firmware and test box", 0, "",
                name == null ? "Firmware ZIP" : name, deviceName == null ? "" : deviceName, "", 0);
        try {
            startForeground(NOTIFICATION, notification(state.message, 0, true));
            foreground = true;
            wakeLock.acquire(15 * 60 * 1000L);
            publish(Phase.CHECKING, "Checking firmware and test box", 0, true);
            worker.execute(() -> prepare(reference));
        } catch (RuntimeException e) {
            finish(Phase.ERROR, "Cannot start the USB update: " + message(e));
        }
        // Never restart an interrupted flash automatically after process death.
        return START_NOT_STICKY;
    }

    private void prepare(String reference) {
        try {
            if (reference == null || deviceName == null) throw new IOException("Select firmware and a USB device first.");
            FirmwarePackage parsed;
            try (InputStream input = reference.startsWith("asset:")
                    ? getAssets().open("firmware/" + reference.substring(6))
                    : getContentResolver().openInputStream(Uri.parse(reference))) {
                parsed = FirmwarePackage.read(state.firmware, input);
            }
            try (UsbSerialConnection port = UsbSerialConnection.open(getSystemService(UsbManager.class), deviceName)) {
                TournaTestDetector.identify(port, Timing.SYSTEM);
            }
            main.post(() -> {
                firmware = parsed;
                log.append("Firmware: ").append(parsed.name).append('\n');
                log.append("Application SHA-256: ").append(parsed.sha256).append('\n');
                publish(Phase.CONFIRM, "TournaTest detected. Review the firmware, then confirm.", 0, true);
                // No CPU work while waiting for the user's decision.
                releaseWakeLock();
            });
        } catch (Exception e) {
            main.post(() -> finish(Phase.ERROR, message(e)));
        }
    }

    public void confirm() {
        if (state.phase != Phase.CONFIRM || firmware == null) return;
        wakeLock.acquire(15 * 60 * 1000L);
        publish(Phase.WRITING, "Rechecking the connected TournaTest", 0, true);
        worker.execute(() -> {
            try {
                // Re-identify after confirmation: no stale authorization after a USB disconnect.
                try (UsbSerialConnection port = UsbSerialConnection.open(getSystemService(UsbManager.class), deviceName)) {
                    TournaTestDetector.identify(port, Timing.SYSTEM);
                    LegacyDfu dfu = new LegacyDfu(port, Timing.SYSTEM,
                            (text, percent) -> main.post(() -> publish(Phase.WRITING, text, percent, false)));
                    dfu.transfer(firmware);
                }
                // A transport ACK only confirms delivery. Require a working application after activation.
                try (UsbSerialConnection port = UsbSerialConnection.open(getSystemService(UsbManager.class), deviceName)) {
                    TournaTestDetector.identify(port, Timing.SYSTEM);
                }
                main.post(() -> finish(Phase.SUCCESS, "Update complete. TournaTest restarted successfully."));
            } catch (Exception e) {
                main.post(() -> finish(Phase.ERROR, "Update could not be verified: " + message(e)
                        + " If the test box no longer starts, it may need serial bootloader recovery."));
            }
        });
    }

    public void cancelConfirmation() {
        if (state.phase == Phase.CONFIRM) finish(Phase.CANCELLED, "Update cancelled. No firmware was written.");
    }

    private void publish(Phase phase, String message, int percent, boolean alwaysLog) {
        if (alwaysLog || !message.equals(lastStatus)) {
            log.append(message).append('\n');
            if (log.length() > 16_000) log.delete(0, log.length() - 12_000);
        }
        lastStatus = message;
        state = new State(phase, message, percent, log.toString(), state.firmware, state.device,
                firmware == null ? "" : firmware.sha256, firmware == null ? 0 : firmware.size());
        if (foreground && (alwaysLog || percent / 5 != lastNotificationPercent / 5)) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, percent, true));
            lastNotificationPercent = percent;
        }
        if (observer != null) observer.changed(state);
    }

    private void finish(Phase phase, String message) {
        publish(phase, message, phase == Phase.SUCCESS ? 100 : state.percent, true);
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            getSystemService(NotificationManager.class).notify(NOTIFICATION,
                    notification(message, state.percent, false));
        }
        stopSelf();
    }

    private Notification notification(String message, int percent, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_update).setContentTitle("TournaTest firmware update")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true);
        if (ongoing) builder.setProgress(100, percent, state.phase == Phase.CHECKING);
        return builder.build();
    }

    private void releaseWakeLock() { if (wakeLock.isHeld()) wakeLock.release(); }

    private static String message(Exception exception) {
        String detail = exception.getMessage();
        return detail == null || detail.trim().isEmpty() ? exception.getClass().getSimpleName() : detail;
    }

    @Override public void onDestroy() {
        observer = null;
        worker.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }
}
