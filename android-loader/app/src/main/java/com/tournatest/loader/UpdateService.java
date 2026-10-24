package com.tournatest.loader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Build;

import com.tournatest.loader.protocol.FirmwarePackage;
import com.tournatest.loader.protocol.Timing;
import com.tournatest.loader.protocol.SerialTransport;
import com.tournatest.loader.protocol.UpdateWorkflow;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the connection and worker independently of the Activity's lifecycle. */
public final class UpdateService extends Service {
    public static final String DEVICE = "device";
    public static final String FIRMWARE = "firmware";
    public static final String NAME = "name";
    public static final String FORCE_FLASH = "forceFlash";
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
        public final boolean forceFlash;
        State(Phase phase, String message, int percent, String log,
                String firmware, String device, String hash, int size, boolean forceFlash) {
            this.phase = phase;
            this.message = message;
            this.percent = percent;
            this.log = log;
            this.firmware = firmware;
            this.device = device;
            this.hash = hash;
            this.size = size;
            this.forceFlash = forceFlash;
        }
        public boolean busy() { return phase == Phase.CHECKING || phase == Phase.CONFIRM || phase == Phase.WRITING; }
        public boolean finished() { return phase == Phase.SUCCESS || phase == Phase.ERROR || phase == Phase.CANCELLED; }
    }
    public interface Observer { void changed(State state); }
    public final class LocalBinder extends Binder {
        UpdateService service() { return UpdateService.this; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();
    private final StringBuilder log = new StringBuilder();
    private State state = new State(Phase.IDLE, "Ready to connect", 0, "", "", "", "", 0, false);
    private Observer observer;
    private FirmwarePackage firmware;
    private String deviceName;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;
    private String lastStatus = "";
    private int lastNotificationPercent = -1;
    private boolean forceFlash;
    private volatile boolean disconnected;
    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || !device.getDeviceName().equals(deviceName) || !state.busy()) return;
            disconnected = true;
            if (state.phase == Phase.CONFIRM) {
                finish(Phase.CANCELLED, "Test box disconnected. Connect the next box and start a new update.");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Firmware updates", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "TournaTest:FirmwareUpdate");
        wakeLock.setReferenceCounted(false);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(detach, filter);
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
        forceFlash = intent.getBooleanExtra(FORCE_FLASH, false);
        disconnected = false;
        firmware = null;
        log.setLength(0);
        lastStatus = "";
        lastNotificationPercent = -1;
        state = new State(Phase.CHECKING, "Checking firmware and test box", 0, "",
                name == null ? "Firmware ZIP" : name, deviceName == null ? "" : deviceName, "", 0, forceFlash);
        try {
            startForeground(NOTIFICATION, notification(state.message, 0, true));
            foreground = true;
            wakeLock.acquire(15 * 60 * 1000L);
            publish(Phase.CHECKING, "Checking firmware and test box", 0, true);
            log.append("USB device: ").append(deviceName).append('\n');
            log.append("Mode: ").append(forceFlash ? "Force flash; TTArm message checks disabled" : "Check TTArm startup messages").append('\n');
            log.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("; Android ").append(Build.VERSION.RELEASE).append('\n');
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
            main.post(() -> publish(Phase.CHECKING, forceFlash ? "Firmware validated. Checking USB access."
                    : "Firmware validated. Waiting for TTArm startup message.", 0, true));
            UpdateWorkflow.checkDevice(forceFlash, this::openConnection, Timing.SYSTEM);
            main.post(() -> {
                if (disconnected) { finish(Phase.ERROR, "USB connection lost. Reconnect the test box and retry."); return; }
                firmware = parsed;
                log.append("Firmware: ").append(parsed.name).append('\n');
                log.append("Application SHA-256: ").append(parsed.sha256).append('\n');
                publish(Phase.CONFIRM, forceFlash ? "USB access confirmed. Force flash will skip TTArm message checks."
                        : "TournaTest detected. Review the firmware, then confirm.", 0, true);
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
        publish(Phase.WRITING, forceFlash ? "Connecting for force flash" : "Rechecking the connected TournaTest", 0, true);
        worker.execute(() -> {
            try {
                UpdateWorkflow.Completion completion = UpdateWorkflow.install(firmware, forceFlash,
                        this::openConnection, Timing.SYSTEM,
                        (text, percent) -> main.post(() -> publish(Phase.WRITING, text, percent, false)));
                if (disconnected) throw new IOException("USB connection was lost during the update.");
                main.post(() -> finish(Phase.SUCCESS, completion == UpdateWorkflow.Completion.RESTART_CONFIRMED
                        ? "Update complete. TournaTest restarted successfully."
                        : "Firmware transfer complete. TTArm message checks were skipped; check the test box display."));
            } catch (Exception e) {
                main.post(() -> finish(Phase.ERROR, "Update stopped: " + message(e)));
            }
        });
    }

    private SerialTransport openConnection() throws IOException {
        if (disconnected) throw new IOException("USB connection lost. Reconnect the test box and retry.");
        return UsbSerialConnection.open(getSystemService(UsbManager.class), deviceName);
    }

    public void cancelConfirmation() {
        if (state.phase == Phase.CONFIRM) finish(Phase.CANCELLED, "Update cancelled. No firmware was written.");
    }

    /** Discard the completed session only after all serial work has ended. */
    public boolean resetForNextBox() {
        if (state.busy()) return false;
        // Ports are scoped to the worker's try-with-resources blocks and closed before finish().
        firmware = null;
        deviceName = null;
        forceFlash = false;
        disconnected = false;
        log.setLength(0);
        lastStatus = "";
        lastNotificationPercent = -1;
        releaseWakeLock();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        state = new State(Phase.IDLE, "Ready for another box. Connect the next box, then tap Connect and Update.",
                0, "", "", "", "", 0, false);
        stopSelf();
        if (observer != null) observer.changed(state);
        return true;
    }

    private void publish(Phase phase, String message, int percent, boolean alwaysLog) {
        if (alwaysLog || !message.equals(lastStatus)) {
            log.append(message).append('\n');
            if (log.length() > 16_000) log.delete(0, log.length() - 12_000);
        }
        lastStatus = message;
        state = new State(phase, message, percent, log.toString(), state.firmware, state.device,
                firmware == null ? "" : firmware.sha256, firmware == null ? 0 : firmware.size(), forceFlash);
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
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        unregisterReceiver(detach);
        observer = null;
        worker.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }
}
