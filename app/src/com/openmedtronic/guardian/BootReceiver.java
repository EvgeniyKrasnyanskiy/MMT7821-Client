package com.openmedtronic.guardian;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * Restarts the GuardianBleService after device reboot if the transmitter
 * was previously paired and not manually disconnected by the user.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String PREF_NAME = "guardian_prefs";
    private static final String KEY_IS_PAIRED = "is_paired";
    private static final String KEY_USER_DISCONNECTED = "user_disconnected";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean isPaired = sp.getBoolean(KEY_IS_PAIRED, false);
        boolean userDisconnected = sp.getBoolean(KEY_USER_DISCONNECTED, false);
        if (!isPaired || userDisconnected) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted; skipping auto-start on boot");
                return;
            }
        }

        try {
            Intent serviceIntent = new Intent(context, GuardianBleService.class);
            serviceIntent.setAction(GuardianBleService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GuardianBleService on boot", e);
        }
    }
}
