package com.tournatest.loader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

// This first release is English-only. All operational messages also appear in the update log.
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 10;
    private static final int NOTIFICATIONS = 11;
    private static final int INK = Color.rgb(26, 48, 45);
    private static final int MUTED = Color.rgb(84, 104, 100);
    private final ArrayList<FirmwareChoice> firmwareChoices = new ArrayList<>();
    private final ArrayList<DeviceChoice> devices = new ArrayList<>();
    private Spinner firmwareSpinner;
    private Spinner deviceSpinner;
    private ArrayAdapter<DeviceChoice> deviceAdapter;
    private Button browse;
    private Button scan;
    private Button update;
    private Button confirm;
    private Button cancel;
    private Button anotherBox;
    private Button copy;
    private TextView status;
    private TextView details;
    private TextView usbStatus;
    private TextView log;
    private ProgressBar progress;
    private CheckBox forceFlash;
    private UsbManager usbManager;
    private UpdateService service;
    private boolean bound;
    private boolean resumed;
    private boolean receiverRegistered;
    private boolean permissionPending;
    private boolean notificationPending;
    private boolean notificationRequested;
    private Uri pendingPermissionToken;
    private boolean pendingForceFlash;
    private String pendingDevice;
    private String pendingFirmware;
    private String pendingName;
    private boolean readyToStart;
    private final StringBuilder connectionLog = new StringBuilder();
    private String usbInventory = "";
    private final Handler usbRefresh = new Handler(Looper.getMainLooper());
    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            scanDevices();
            usbRefresh.postDelayed(this, 2000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((UpdateService.LocalBinder) binder).service();
            service.observe(MainActivity.this::render);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            status.setText("The updater service stopped. Reconnect the test box before retrying.");
            update.setEnabled(false);
        }
    };

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (UsbPermissionReceiver.RESULT_CHANGED.equals(intent.getAction())) {
                reconcileUsbPermission();
            } else {
                scanDevices();
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        usbManager = getSystemService(UsbManager.class);
        if (saved != null) {
            pendingDevice = saved.getString("pendingDevice");
            pendingFirmware = saved.getString("pendingFirmware");
            pendingName = saved.getString("pendingName");
            permissionPending = saved.getBoolean("permissionPending");
            notificationPending = saved.getBoolean("notificationPending");
            notificationRequested = saved.getBoolean("notificationRequested");
            readyToStart = saved.getBoolean("readyToStart");
            pendingForceFlash = saved.getBoolean("pendingForceFlash");
            String token = saved.getString("pendingPermissionToken");
            pendingPermissionToken = token == null ? null : Uri.parse(token);
            connectionLog.append(saved.getString("connectionLog", ""));
        }
        buildUi();
        forceFlash.setChecked(saved != null && saved.getBoolean("forceFlash"));
        loadFirmwareChoices(saved);
        scanDevices();
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            status.setText("This phone does not support USB host mode.");
            update.setEnabled(false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(scroll);
        text(content, "TOURNATEST DIGITAL", 13, MUTED).setLetterSpacing(0.13f);
        TextView title = text(content, "Firmware updater", 30, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text(content, "Connect your test box to this phone using a USB data cable and a USB host (OTG) adapter if needed.", 16, MUTED);

        section(content, "1  Choose firmware");
        firmwareSpinner = new Spinner(this);
        firmwareSpinner.setMinimumHeight(dp(56));
        content.addView(firmwareSpinner, fill());
        browse = button(content, "Choose a downloaded ZIP");
        browse.setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(pick, PICK_ZIP);
        });

        section(content, "2  Select the test box");
        deviceSpinner = new Spinner(this);
        deviceSpinner.setMinimumHeight(dp(56));
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, devices);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        content.addView(deviceSpinner, fill());
        scan = button(content, "Scan USB devices");
        scan.setOnClickListener(view -> {
            if (permissionPending && !notificationPending) clearPendingUsbRequest();
            scanDevices();
        });
        usbStatus = text(content, "", 14, MUTED);
        text(content, "Select the connected test box. USB access is requested separately for each box.", 14, MUTED);
        forceFlash = new CheckBox(this);
        forceFlash.setText("Force flash (skip TTArm message check)");
        forceFlash.setTextColor(INK);
        forceFlash.setMinHeight(dp(52));
        content.addView(forceFlash, fill());

        section(content, "3  Connect and update");
        update = button(content, "Connect and Update");
        update.setOnClickListener(view -> requestUpdate());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        content.addView(progress, fill());
        status = text(content, "Ready to connect", 18, INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        details = text(content, "", 14, MUTED);
        details.setTextIsSelectable(true);
        details.setVisibility(View.GONE);
        confirm = button(content, "Install this firmware");
        confirm.setVisibility(View.GONE);
        confirm.setOnClickListener(view -> {
            if (service == null || service.state().phase != UpdateService.Phase.CONFIRM) return;
            UpdateService.State state = service.state();
            new AlertDialog.Builder(this).setTitle(state.forceFlash ? "Force flash TournaTest?" : "Update TournaTest?")
                    .setMessage(state.firmware + (state.forceFlash ? "\n\nTTArm startup-message checks will be skipped." : "")
                            + "\n\nKeep the phone and test box powered and the USB cable connected until the update finishes.")
                    .setNegativeButton("Go back", null)
                    .setPositiveButton("Install", (dialog, which) -> {
                        if (service != null) service.confirm();
                    }).show();
        });
        cancel = button(content, "Cancel");
        cancel.setVisibility(View.GONE);
        cancel.setOnClickListener(view -> { if (service != null) service.cancelConfirmation(); });
        anotherBox = button(content, "Flash another box");
        anotherBox.setVisibility(View.GONE);
        anotherBox.setOnClickListener(view -> flashAnotherBox());
        text(content, "Keep the USB cable connected until the update finishes. Use firmware supplied by a trusted TournaTest source.", 14, MUTED);
        section(content, "Update log");
        log = text(content, "Update messages will appear here.", 13, MUTED);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        copy = button(content, "Copy log");
        copy.setOnClickListener(view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("TournaTest update log", log.getText()));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        Button licenses = button(content, "Open-source licenses");
        licenses.setOnClickListener(view -> {
            try (java.io.InputStream input = getResources().openRawResource(R.raw.third_party_notices)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                new AlertDialog.Builder(this).setTitle("Open-source licenses")
                        .setMessage(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                        .setPositiveButton("Close", null).show();
            } catch (IOException e) {
                Toast.makeText(this, "Cannot open licenses", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadFirmwareChoices(Bundle saved) {
        try {
            String[] names = getAssets().list("firmware");
            if (names != null) {
                Arrays.sort(names, Comparator.reverseOrder());
                for (String name : names) if (name.endsWith(".zip")) firmwareChoices.add(new FirmwareChoice(name, "asset:" + name));
            }
        } catch (IOException e) {
            status.setText("Bundled firmware unavailable. Choose a downloaded ZIP.");
        }
        String selected = saved == null ? null : saved.getString("selectedFirmware");
        if (selected != null && !selected.startsWith("asset:")) {
            firmwareChoices.add(new FirmwareChoice(saved.getString("selectedName", "Selected ZIP"), selected));
        }
        setFirmwareAdapter();
        if (selected != null) {
            for (int i = 0; i < firmwareChoices.size(); i++) {
                if (firmwareChoices.get(i).reference.equals(selected)) firmwareSpinner.setSelection(i);
            }
        }
    }

    private void setFirmwareAdapter() {
        ArrayAdapter<FirmwareChoice> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, firmwareChoices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        firmwareSpinner.setAdapter(adapter);
    }

    private void scanDevices() {
        DeviceChoice selected = (DeviceChoice) deviceSpinner.getSelectedItem();
        String previous = selected == null ? null : selected.device.getDeviceName();
        ArrayList<DeviceChoice> found = new ArrayList<>();
        int totalDevices = 0;
        StringBuilder inventory = new StringBuilder();
        if (usbManager != null) {
            ArrayList<UsbDevice> attached = new ArrayList<>(usbManager.getDeviceList().values());
            attached.sort(Comparator.comparing(UsbDevice::getDeviceName));
            totalDevices = attached.size();
            inventory.append("USB devices: ").append(totalDevices);
            for (UsbDevice device : attached) {
                inventory.append(String.format(Locale.ROOT, "\nUSB %04X:%04X · %s · %s",
                        device.getVendorId(), device.getProductId(), device.getDeviceName(),
                        usbManager.hasPermission(device) ? "access granted" : "access required"));
                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
                if (driver instanceof Cp21xxSerialDriver && driver.getPorts().size() == 1) {
                    found.add(new DeviceChoice(driver.getDevice(), usbManager.hasPermission(driver.getDevice())));
                }
            }
        }
        found.sort(Comparator.comparing(choice -> choice.device.getDeviceName()));
        boolean changed = !inventory.toString().equals(usbInventory);
        if (changed) {
            usbInventory = inventory.toString();
            devices.clear();
            devices.addAll(found);
            deviceAdapter.notifyDataSetChanged();
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).device.getDeviceName().equals(previous)) deviceSpinner.setSelection(i);
            }
            connectionMessage(usbInventory);
        }
        usbStatus.setText(devices.isEmpty() ? (totalDevices == 0 ? "No USB devices detected."
                : "USB device detected, but no supported CP210x interface found.")
                : devices.size() + " test box interface(s) connected. Access status is shown above.");
        if (changed && (service == null || !service.state().busy())) {
            status.setText(devices.isEmpty() ? "Connect the test box, then scan again."
                    : "Test box connected. Tap Connect and Update.");
        }
        reconcileUsbPermission();
        refreshControls();
    }

    private void connectionMessage(String message) {
        connectionLog.append(message).append('\n');
        if (connectionLog.length() > 8000) connectionLog.delete(0, connectionLog.length() - 6000);
        showLog();
    }

    private void showLog() {
        String updateLog = service == null ? "" : service.state().log;
        String combined = connectionLog.toString() + updateLog;
        log.setText(combined.isEmpty() ? "Update messages will appear here." : combined);
    }

    private void requestUpdate() {
        if (service == null || service.state().busy() || permissionPending) return;
        DeviceChoice device = (DeviceChoice) deviceSpinner.getSelectedItem();
        FirmwareChoice firmware = (FirmwareChoice) firmwareSpinner.getSelectedItem();
        if (device == null || firmware == null) return;
        pendingDevice = device.device.getDeviceName();
        pendingFirmware = firmware.reference;
        pendingName = firmware.name;
        pendingForceFlash = forceFlash.isChecked();
        if (Build.VERSION.SDK_INT >= 33 && !notificationRequested
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequested = true;
            notificationPending = true;
            permissionPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            refreshControls();
            return;
        }
        requestUsbAccess();
    }

    private void requestUsbAccess() {
        UsbDevice device = usbManager.getDeviceList().get(pendingDevice);
        if (device == null) {
            permissionPending = false;
            status.setText("USB connection lost. Reconnect the test box and try again.");
            refreshControls();
            return;
        }
        if (usbManager.hasPermission(device)) {
            permissionPending = false;
            pendingPermissionToken = null;
            connectionMessage("USB access already granted for " + pendingDevice);
            readyToStart = true;
            startWhenResumed();
        } else {
            permissionPending = true;
            pendingPermissionToken = Uri.parse("tournatest-loader://usb-permission/" + UUID.randomUUID());
            Intent result = new Intent(this, UsbPermissionReceiver.class).setAction(UsbPermissionReceiver.ACTION)
                    .setData(pendingPermissionToken);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // UsbManager supplies result extras.
            PendingIntent permission = PendingIntent.getBroadcast(this, 0, result, flags);
            status.setText("Allow this app to access the TournaTest USB interface.");
            connectionMessage("Requesting USB access for " + pendingDevice);
            try {
                usbManager.requestPermission(device, permission);
            } catch (RuntimeException e) {
                clearPendingUsbRequest();
                status.setText("Cannot request USB access: " + e.getMessage());
                connectionMessage(status.getText().toString());
            }
        }
        refreshControls();
    }

    private void clearPendingUsbRequest() {
        permissionPending = false;
        readyToStart = false;
        pendingPermissionToken = null;
    }

    private void flashAnotherBox() {
        if (service == null || service.state().busy() || permissionPending || notificationPending || readyToStart) return;
        // Cancel any old callback before forgetting its token; a late result must not start work.
        if (pendingPermissionToken != null) {
            Intent result = new Intent(this, UsbPermissionReceiver.class).setAction(UsbPermissionReceiver.ACTION)
                    .setData(pendingPermissionToken);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(this, 0, result, flags);
            if (pending != null) pending.cancel();
        }
        clearPendingUsbRequest();
        notificationPending = false;
        pendingDevice = null;
        pendingFirmware = null;
        pendingName = null;
        pendingForceFlash = false;
        UsbPermissionReceiver.clearResults(this);
        connectionLog.setLength(0);
        usbInventory = "";
        devices.clear();
        deviceAdapter.notifyDataSetChanged();
        if (!service.resetForNextBox()) return;
        scanDevices();
        // Keep the selected ZIP and force-mode preference for repeated updates of the same batch.
        status.setText(service.state().message);
    }

    private void reconcileUsbPermission() {
        if (!permissionPending || notificationPending || pendingDevice == null) return;
        UsbDevice device = usbManager.getDeviceList().get(pendingDevice);
        if (device == null) {
            clearPendingUsbRequest();
            status.setText("Test box disconnected. Connect the next box and try again.");
            connectionMessage("USB device disconnected while permission was pending.");
        } else {
            boolean completed = UsbPermissionReceiver.consume(this, pendingPermissionToken);
            boolean granted = usbManager.hasPermission(device);
            if (!completed && !granted) return; // Resuming alone does not mean a permission request failed.
            permissionPending = false;
            pendingPermissionToken = null;
            connectionMessage((granted ? "USB access granted for " : "USB access denied for ") + pendingDevice);
            if (granted) {
                readyToStart = true;
                startWhenResumed();
            } else {
                status.setText("USB access was denied. Tap Connect and Update to try again.");
            }
        }
        refreshControls();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFICATIONS && notificationPending) {
            notificationPending = false;
            permissionPending = false;
            // A foreground USB update also works when notification permission is denied.
            requestUsbAccess();
        }
    }

    private void startWhenResumed() {
        if (!readyToStart || !resumed || service == null) return;
        readyToStart = false;
        if (service.state().busy()) return;
        UsbDevice device = usbManager.getDeviceList().get(pendingDevice);
        if (device == null || !usbManager.hasPermission(device)) {
            status.setText("USB connection lost. Reconnect the test box and try again.");
            refreshControls();
            return;
        }
        Intent start = new Intent(this, UpdateService.class).putExtra(UpdateService.DEVICE, pendingDevice)
                .putExtra(UpdateService.FIRMWARE, pendingFirmware).putExtra(UpdateService.NAME, pendingName)
                .putExtra(UpdateService.FORCE_FLASH, pendingForceFlash);
        try {
            startForegroundService(start);
            update.setEnabled(false);
            anotherBox.setVisibility(View.GONE);
        } catch (RuntimeException e) {
            status.setText("Cannot start the updater: " + e.getMessage());
            refreshControls();
        }
    }

    private void render(UpdateService.State state) {
        status.setText(state.message);
        progress.setProgress(state.percent);
        progress.setIndeterminate(state.phase == UpdateService.Phase.CHECKING);
        boolean review = state.phase == UpdateService.Phase.CONFIRM;
        confirm.setText(state.forceFlash ? "Force flash this firmware" : "Install this firmware");
        confirm.setVisibility(review ? View.VISIBLE : View.GONE);
        cancel.setVisibility(review ? View.VISIBLE : View.GONE);
        anotherBox.setVisibility(state.finished() ? View.VISIBLE : View.GONE);
        if (!state.hash.isEmpty()) {
            details.setVisibility(View.VISIBLE);
            details.setText(state.firmware + "\n" + String.format(Locale.ROOT, "%,d bytes", state.size)
                    + "\nUSB: " + state.device + "\nApplication SHA-256:\n" + state.hash);
        } else {
            details.setText("");
            details.setVisibility(View.GONE);
        }
        showLog();
        if (state.busy()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshControls();
        startWhenResumed();
    }

    private void refreshControls() {
        boolean busy = permissionPending || readyToStart || (service != null && service.state().busy());
        firmwareSpinner.setEnabled(!busy);
        deviceSpinner.setEnabled(!busy);
        browse.setEnabled(!busy);
        forceFlash.setEnabled(!busy);
        anotherBox.setEnabled(!busy && service != null);
        scan.setEnabled(!notificationPending && !readyToStart && (service == null || !service.state().busy()));
        update.setEnabled(!busy && service != null && !devices.isEmpty() && !firmwareChoices.isEmpty()
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
    }

    // The legacy registration overload is used only below API 33, where exported flags do not exist.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(UsbPermissionReceiver.RESULT_CHANGED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbEvents, filter);
        receiverRegistered = true;
        bound = bindService(new Intent(this, UpdateService.class), connection, Context.BIND_AUTO_CREATE);
        scanDevices();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        scanDevices();
        startWhenResumed();
        refreshControls();
        usbRefresh.removeCallbacks(rescan);
        usbRefresh.postDelayed(rescan, 2000);
    }

    @Override protected void onPause() {
        resumed = false;
        usbRefresh.removeCallbacks(rescan);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        scanDevices();
    }

    @Override protected void onStop() {
        if (service != null) service.observe(null);
        service = null;
        if (bound) { unbindService(connection); bound = false; }
        if (receiverRegistered) { unregisterReceiver(usbEvents); receiverRegistered = false; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        FirmwareChoice selected = (FirmwareChoice) firmwareSpinner.getSelectedItem();
        if (selected != null) { out.putString("selectedFirmware", selected.reference); out.putString("selectedName", selected.name); }
        out.putString("pendingDevice", pendingDevice);
        out.putString("pendingFirmware", pendingFirmware);
        out.putString("pendingName", pendingName);
        out.putBoolean("permissionPending", permissionPending);
        out.putBoolean("notificationPending", notificationPending);
        out.putBoolean("notificationRequested", notificationRequested);
        out.putBoolean("readyToStart", readyToStart);
        out.putBoolean("forceFlash", forceFlash.isChecked());
        out.putBoolean("pendingForceFlash", pendingForceFlash);
        out.putString("pendingPermissionToken", pendingPermissionToken == null ? null : pendingPermissionToken.toString());
        out.putString("connectionLog", connectionLog.toString());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ZIP || result != RESULT_OK || data == null || data.getData() == null) return;
        if (service != null && service.state().busy()) return;
        Uri uri = data.getData();
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (SecurityException ignored) { /* The provider may only grant access for this session. */ }
        String name = "Selected firmware ZIP";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        } catch (RuntimeException ignored) { /* A display name is optional. */ }
        firmwareChoices.removeIf(choice -> !choice.reference.startsWith("asset:"));
        firmwareChoices.add(new FirmwareChoice(name, uri.toString()));
        setFirmwareAdapter();
        firmwareSpinner.setSelection(firmwareChoices.size() - 1);
        refreshControls();
    }

    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, fill());
        return view;
    }
    private void section(LinearLayout parent, String title) {
        TextView label = text(parent, title, 18, INK);
        label.setPadding(0, dp(28), 0, dp(8));
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }
    private Button button(LinearLayout parent, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        parent.addView(button, fill());
        return button;
    }
    private LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static final class FirmwareChoice {
        final String name;
        final String reference;
        FirmwareChoice(String name, String reference) { this.name = name; this.reference = reference; }
        @Override public String toString() { return name; }
    }
    private static final class DeviceChoice {
        final UsbDevice device;
        final boolean permission;
        DeviceChoice(UsbDevice device, boolean permission) { this.device = device; this.permission = permission; }
        @Override public String toString() {
            return String.format(Locale.ROOT, "CP210x · USB %d · %s", device.getDeviceId(),
                    permission ? "access granted" : "access required");
        }
    }
}
