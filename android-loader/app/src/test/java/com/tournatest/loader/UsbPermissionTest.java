package com.tournatest.loader;

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowUsbManager;
import org.robolectric.util.ReflectionHelpers;
import com.tournatest.loader.protocol.FirmwarePackage;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises actual Activity/receiver lifecycle and Android USB permission state on old/new APIs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = {UsbPermissionTest.TestUsbDevice.class, UsbPermissionTest.TestUsbManager.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class UsbPermissionTest {
    private Context app;
    private UsbManager manager;
    private UsbDevice first;
    private ActivityController<MainActivity> activity;
    private ServiceController<UpdateService> service;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("usb_permission_result", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf(app.getPackageManager()).setSystemFeature(PackageManager.FEATURE_USB_HOST, true);
        manager = app.getSystemService(UsbManager.class);
        first = device("/dev/bus/usb/001/002");
        shadowOf(manager).addOrUpdateUsbDevice(first, false);
        service = Robolectric.buildService(UpdateService.class).create();
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(app, UpdateService.class), service.get().onBind(new Intent()));
        activity = Robolectric.buildActivity(MainActivity.class).setup();
        connectService();
        TestUsbManager.permission = null;
        TestUsbManager.requestedDevice = null;
    }

    @After public void teardown() {
        if (activity != null) activity.pause().stop().destroy();
        if (service != null) service.destroy();
    }

    @Test public void resumeBeforeDelayedPermissionResultDoesNotDiscardRequest() throws Exception {
        field("forceFlash", CheckBox.class).setChecked(true);
        requestUpdate();
        PendingIntent request = TestUsbManager.permission;
        activity.pause().resume();
        assertTrue(field("permissionPending", Boolean.class));
        assertNull(nextUpdate());
        shadowOf(manager).addOrUpdateUsbDevice(first, true);
        deliver(request);
        Intent start = nextUpdate();
        assertNotNull(start);
        assertEquals(first.getDeviceName(), start.getStringExtra(UpdateService.DEVICE));
        assertTrue(start.getBooleanExtra(UpdateService.FORCE_FLASH, false));
        assertFalse(field("permissionPending", Boolean.class));
        assertNull(nextUpdate());
    }

    @Test public void deniedResultWhileStoppedSurvivesUntilActivityReturns() throws Exception {
        requestUpdate();
        PendingIntent request = TestUsbManager.permission;
        activity.pause().stop();
        deliver(request);
        activity.start().resume();
        connectService();
        assertFalse(field("permissionPending", Boolean.class));
        assertTrue(field("update", Button.class).isEnabled());
        assertTrue(field("status", TextView.class).getText().toString().contains("denied"));
        assertNull(nextUpdate());
    }

    @Test public void switchingBoxesUsesFreshEnumerationAndRequestsNewPermission() {
        shadowOf(manager).addOrUpdateUsbDevice(first, true);
        rescan();
        assertTrue(field("deviceSpinner", Spinner.class).getSelectedItem().toString().contains("access granted"));
        ReflectionHelpers.setField(service.get(), "state", new UpdateService.State(UpdateService.Phase.SUCCESS,
                "Update complete", 100, "", "firmware", first.getDeviceName(), "", 0, false));
        UsbDevice next = replaceBox();
        assertTrue(field("deviceSpinner", Spinner.class).getSelectedItem().toString().contains("access required"));
        requestUpdate();
        assertEquals(next.getDeviceName(), TestUsbManager.requestedDevice.getDeviceName());
        assertTrue(field("permissionPending", Boolean.class));
    }

    @Test public void staleResultFromPreviousBoxCannotCompleteNewRequest() throws Exception {
        requestUpdate();
        PendingIntent old = TestUsbManager.permission;
        UsbDevice next = replaceBox();
        requestUpdate();
        PendingIntent current = TestUsbManager.permission;
        assertNotEquals(shadowOf(old).getSavedIntent().getData(), shadowOf(current).getSavedIntent().getData());
        deliver(old);
        assertTrue(field("permissionPending", Boolean.class));
        assertNull(nextUpdate());
        shadowOf(manager).addOrUpdateUsbDevice(next, true);
        deliver(current);
        Intent start = nextUpdate();
        assertNotNull(start);
        assertEquals(next.getDeviceName(), start.getStringExtra(UpdateService.DEVICE));
        assertFalse(start.getBooleanExtra(UpdateService.FORCE_FLASH, true));
    }

    @Test public void scanLetsUserRetryPendingPermissionWithANewRequestToken() {
        requestUpdate();
        Uri firstToken = shadowOf(TestUsbManager.permission).getSavedIntent().getData();
        assertTrue(field("scan", Button.class).isEnabled());
        field("scan", Button.class).performClick();
        assertFalse(field("permissionPending", Boolean.class));
        requestUpdate();
        assertNotEquals(firstToken, shadowOf(TestUsbManager.permission).getSavedIntent().getData());
    }

    @Test public void rotationRestoresForceChoiceAndOutstandingPermission() throws Exception {
        field("forceFlash", CheckBox.class).setChecked(true);
        requestUpdate();
        PendingIntent request = TestUsbManager.permission;
        Bundle saved = new Bundle();
        activity.pause().saveInstanceState(saved).stop().destroy();
        activity = Robolectric.buildActivity(MainActivity.class).create(saved).start().restoreInstanceState(saved).resume().visible();
        connectService();
        assertTrue(field("forceFlash", CheckBox.class).isChecked());
        assertTrue(field("permissionPending", Boolean.class));
        shadowOf(manager).addOrUpdateUsbDevice(first, true);
        deliver(request);
        Intent start = nextUpdate();
        assertNotNull(start);
        assertTrue(start.getBooleanExtra(UpdateService.FORCE_FLASH, false));
    }

    @Test public void disconnectCancelsConfirmationForOldBox() {
        ReflectionHelpers.setField(service.get(), "deviceName", first.getDeviceName());
        ReflectionHelpers.setField(service.get(), "state", new UpdateService.State(UpdateService.Phase.CONFIRM,
                "Ready", 0, "", "firmware", first.getDeviceName(), "", 0, false));
        app.sendBroadcast(new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, first));
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(UpdateService.Phase.CANCELLED, service.get().state().phase);
    }

    @Test public void flashAnotherBoxClearsFinishedSessionAndKeepsBatchChoices() throws Exception {
        for (boolean force : new boolean[]{false, true}) {
            field("forceFlash", CheckBox.class).setChecked(force);
            field("firmwareSpinner", Spinner.class).setSelection(1);
            String selectedFirmware = field("firmwareSpinner", Spinner.class).getSelectedItem().toString();
            shadowOf(manager).addOrUpdateUsbDevice(first, true);
            completedSession(UpdateService.Phase.SUCCESS, force);
            PowerManager.WakeLock wakeLock = ReflectionHelpers.getField(service.get(), "wakeLock");
            wakeLock.acquire(1000);
            NotificationManager notifications = app.getSystemService(NotificationManager.class);
            notifications.notify(1, new Notification.Builder(app, "firmware_update")
                    .setSmallIcon(R.drawable.ic_update).setContentTitle("Old update").build());
            assertEquals(View.VISIBLE, field("anotherBox", Button.class).getVisibility());
            field("anotherBox", Button.class).performClick();
            UpdateService.State state = service.get().state();
            assertEquals(UpdateService.Phase.IDLE, state.phase);
            assertEquals(0, state.percent);
            assertEquals("", state.firmware);
            assertEquals("", state.device);
            assertEquals("", state.hash);
            assertEquals("", state.log);
            assertEquals(0, state.size);
            assertFalse(state.forceFlash);
            assertNull(ReflectionHelpers.getField(service.get(), "firmware"));
            assertNull(ReflectionHelpers.getField(service.get(), "deviceName"));
            assertFalse(ReflectionHelpers.getField(service.get(), "disconnected"));
            assertFalse(wakeLock.isHeld());
            assertEquals(0, notifications.getActiveNotifications().length);
            assertEquals(0, field("progress", ProgressBar.class).getProgress());
            assertFalse(field("progress", ProgressBar.class).isIndeterminate());
            assertEquals("", field("details", TextView.class).getText().toString());
            assertEquals(View.GONE, field("details", TextView.class).getVisibility());
            assertEquals(View.GONE, field("anotherBox", Button.class).getVisibility());
            assertFalse(field("log", TextView.class).getText().toString().contains("Old transfer"));
            assertTrue(field("update", Button.class).isEnabled());
            assertEquals(force, field("forceFlash", CheckBox.class).isChecked());
            assertEquals(selectedFirmware, field("firmwareSpinner", Spinner.class).getSelectedItem().toString());
            assertNull(field("pendingDevice", String.class));
            assertNull(field("pendingFirmware", String.class));
            assertNull(field("pendingName", String.class));
            assertNull(field("pendingPermissionToken", Uri.class));
            assertFalse(field("pendingForceFlash", Boolean.class));
            assertFalse(field("readyToStart", Boolean.class));
            assertFalse(field("permissionPending", Boolean.class));
        }
    }

    @Test public void flashAnotherBoxAlsoResetsFailedAndCancelledSessions() throws Exception {
        for (UpdateService.Phase phase : new UpdateService.Phase[]{UpdateService.Phase.ERROR, UpdateService.Phase.CANCELLED}) {
            completedSession(phase, false);
            assertEquals(View.VISIBLE, field("anotherBox", Button.class).getVisibility());
            field("anotherBox", Button.class).performClick();
            assertEquals(UpdateService.Phase.IDLE, service.get().state().phase);
            assertTrue(field("update", Button.class).isEnabled());
            assertEquals(View.GONE, field("anotherBox", Button.class).getVisibility());
        }
    }

    @Test public void resetCannotInterruptCheckingConfirmationOrFlashing() {
        for (UpdateService.Phase phase : new UpdateService.Phase[]{
                UpdateService.Phase.CHECKING, UpdateService.Phase.CONFIRM, UpdateService.Phase.WRITING}) {
            UpdateService.State active = new UpdateService.State(phase, "Active", 99, "Active log", "firmware",
                    first.getDeviceName(), "hash", 123, false);
            ReflectionHelpers.setField(service.get(), "state", active);
            render(active);
            assertEquals(View.GONE, field("anotherBox", Button.class).getVisibility());
            assertFalse(service.get().resetForNextBox());
            ReflectionHelpers.callInstanceMethod(activity.get(), "flashAnotherBox");
            assertSame(active, service.get().state());
        }
    }

    @Test public void nextBoxStartsAFreshPermissionRequestAfterExplicitReset() throws Exception {
        field("forceFlash", CheckBox.class).setChecked(true);
        completedSession(UpdateService.Phase.SUCCESS, true);
        field("anotherBox", Button.class).performClick();
        UsbDevice next = replaceBox();
        requestUpdate();
        assertEquals(next.getDeviceName(), TestUsbManager.requestedDevice.getDeviceName());
        assertTrue(field("permissionPending", Boolean.class));
        shadowOf(manager).addOrUpdateUsbDevice(next, true);
        deliver(TestUsbManager.permission);
        Intent start = nextUpdate();
        assertNotNull(start);
        assertEquals(next.getDeviceName(), start.getStringExtra(UpdateService.DEVICE));
        assertTrue(start.getBooleanExtra(UpdateService.FORCE_FLASH, false));
        assertEquals(View.GONE, field("anotherBox", Button.class).getVisibility());
    }

    @Test public void resetStateSurvivesRotationWithoutRestoringOldCompletion() throws Exception {
        completedSession(UpdateService.Phase.SUCCESS, false);
        field("anotherBox", Button.class).performClick();
        Bundle saved = new Bundle();
        activity.pause().saveInstanceState(saved).stop().destroy();
        activity = Robolectric.buildActivity(MainActivity.class).create(saved).start().restoreInstanceState(saved).resume().visible();
        connectService();
        assertEquals(UpdateService.Phase.IDLE, service.get().state().phase);
        assertEquals(0, field("progress", ProgressBar.class).getProgress());
        assertEquals(View.GONE, field("anotherBox", Button.class).getVisibility());
        assertEquals(View.GONE, field("details", TextView.class).getVisibility());
        assertFalse(field("log", TextView.class).getText().toString().contains("Old transfer"));
        assertNull(field("pendingDevice", String.class));
        assertNull(nextUpdate());
    }

    @Test public void latePermissionResultCannotRestartTheClearedSession() throws Exception {
        Uri stale = Uri.parse("tournatest-loader://usb-permission/old-completed-session");
        new UsbPermissionReceiver().onReceive(app, new Intent(UsbPermissionReceiver.ACTION).setData(stale));
        completedSession(UpdateService.Phase.SUCCESS, false);
        ReflectionHelpers.setField(activity.get(), "pendingPermissionToken", stale);
        field("anotherBox", Button.class).performClick();
        assertFalse(UsbPermissionReceiver.consume(app, stale));
        new UsbPermissionReceiver().onReceive(app, new Intent(UsbPermissionReceiver.ACTION).setData(stale));
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(UpdateService.Phase.IDLE, service.get().state().phase);
        assertFalse(field("permissionPending", Boolean.class));
        assertFalse(field("readyToStart", Boolean.class));
        assertNull(nextUpdate());
    }

    private void completedSession(UpdateService.Phase phase, boolean force) throws Exception {
        FirmwarePackage firmware;
        try (java.io.InputStream input = app.getAssets().open("firmware/TTArm-build20260629d.zip")) {
            firmware = FirmwarePackage.read("firmware.zip", input);
        }
        ReflectionHelpers.setField(service.get(), "firmware", firmware);
        ReflectionHelpers.setField(service.get(), "deviceName", first.getDeviceName());
        ReflectionHelpers.setField(service.get(), "forceFlash", force);
        ReflectionHelpers.setField(service.get(), "disconnected", true);
        ReflectionHelpers.setField(service.get(), "lastStatus", "Old transfer complete");
        ReflectionHelpers.setField(service.get(), "lastNotificationPercent", 100);
        StringBuilder log = ReflectionHelpers.getField(service.get(), "log");
        log.append("Old transfer complete\n");
        UpdateService.State completed = new UpdateService.State(phase, "Old transfer complete", 100,
                "Old transfer complete\n", firmware.name, first.getDeviceName(), firmware.sha256, firmware.size(), force);
        ReflectionHelpers.setField(service.get(), "state", completed);
        ReflectionHelpers.setField(activity.get(), "pendingDevice", first.getDeviceName());
        ReflectionHelpers.setField(activity.get(), "pendingFirmware", "asset:old-firmware.zip");
        ReflectionHelpers.setField(activity.get(), "pendingName", "old-firmware.zip");
        ReflectionHelpers.setField(activity.get(), "pendingForceFlash", force);
        render(completed);
    }

    private void render(UpdateService.State state) {
        ReflectionHelpers.callInstanceMethod(activity.get(), "render",
                ReflectionHelpers.ClassParameter.from(UpdateService.State.class, state));
    }

    private void connectService() {
        ReflectionHelpers.setField(activity.get(), "service", service.get());
        ReflectionHelpers.setField(activity.get(), "notificationRequested", true);
        ReflectionHelpers.callInstanceMethod(activity.get(), "refreshControls");
    }

    private void requestUpdate() { field("update", Button.class).performClick(); }
    private Intent nextUpdate() {
        // Robolectric records ServiceController creation as an extra service intent without a request.
        Intent intent;
        while ((intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null) {
            if (intent.hasExtra(UpdateService.DEVICE)) return intent;
        }
        return null;
    }
    private void rescan() { ReflectionHelpers.callInstanceMethod(activity.get(), "scanDevices"); }
    private <T> T field(String name, Class<T> type) { return ReflectionHelpers.getField(activity.get(), name); }
    private void deliver(PendingIntent result) throws Exception {
        assertNotNull(result);
        result.send();
        shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private UsbDevice replaceBox() {
        shadowOf(manager).removeUsbDevice(first);
        UsbDevice next = device("/dev/bus/usb/001/003");
        shadowOf(manager).addOrUpdateUsbDevice(next, false);
        rescan();
        return next;
    }

    private static UsbDevice device(String name) {
        UsbDevice device = Shadow.newInstanceOf(UsbDevice.class);
        ReflectionHelpers.setField(device, "mName", name);
        return device;
    }

    @Implements(UsbDevice.class)
    public static class TestUsbDevice {
        @RealObject UsbDevice real;
        @Implementation protected String getDeviceName() { return ReflectionHelpers.getField(real, "mName"); }
        @Implementation protected int getVendorId() { return 0x10c4; }
        @Implementation protected int getProductId() { return 0xea60; }
        @Implementation protected int getInterfaceCount() { return 1; }
        @Implementation protected int getDeviceId() { return getDeviceName().hashCode() & 0xffff; }
    }

    @Implements(UsbManager.class)
    public static class TestUsbManager extends ShadowUsbManager {
        static PendingIntent permission;
        static UsbDevice requestedDevice;
        @Implementation protected void requestPermission(UsbDevice device, PendingIntent result) {
            requestedDevice = device;
            permission = result; // A real permission dialog may return much later, after pause/resume/stop.
        }
    }
}
