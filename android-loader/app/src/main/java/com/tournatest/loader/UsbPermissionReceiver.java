package com.tournatest.loader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/** Receives the system result even when the Activity stops for the USB permission dialog. */
public final class UsbPermissionReceiver extends BroadcastReceiver {
    static final String ACTION = "com.tournatest.loader.USB_PERMISSION";
    static final String RESULT_CHANGED = "com.tournatest.loader.USB_PERMISSION_RESULT";
    private static final String STORE = "usb_permission_result";
    private static final String TOKEN = "completed:";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction()) || intent.getData() == null) return;
        // The token identifies the request, not a reusable permission for a USB port.
        // The Activity always asks UsbManager for the current device's actual permission.
        preferences(context).edit().putBoolean(TOKEN + intent.getData(), true).apply();
        context.sendBroadcast(new Intent(RESULT_CHANGED).setPackage(context.getPackageName()));
    }

    static boolean consume(Context context, Uri token) {
        SharedPreferences preferences = preferences(context);
        String key = TOKEN + token;
        if (token == null || !preferences.getBoolean(key, false)) return false;
        preferences.edit().remove(key).apply();
        return true;
    }

    static void clearResults(Context context) {
        preferences(context).edit().clear().apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
