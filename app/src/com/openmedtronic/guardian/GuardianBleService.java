package com.openmedtronic.guardian;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

/**
 * Foreground Service running 24/7 to maintain the BLE connection with
 * Medtronic Guardian Connect MMT-7821, decrypt incoming glucose packets,
 * persist history, and display live readings on the Lockscreen and Status Bar.
 */
@SuppressWarnings({"deprecation", "unused"})
public class GuardianBleService extends Service {
    private static final String TAG = "GuardianBleService";

    public static final String ACTION_START = "com.openmedtronic.guardian.action.START";
    public static final String ACTION_STOP = "com.openmedtronic.guardian.action.STOP";
    public static final String ACTION_CONNECT = "com.openmedtronic.guardian.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.openmedtronic.guardian.action.DISCONNECT";
    public static final String ACTION_DISMISS_ALARM = "com.openmedtronic.guardian.action.DISMISS_ALARM";
    public static final String ACTION_SNOOZE_ALARM = "com.openmedtronic.guardian.action.SNOOZE_ALARM";

    public static final int NOTIFICATION_ID = 1001;
    public static final int NOTIFICATION_ALARM_ID = 1002;
    public static final String CHANNEL_ID = "guardian_cgm_channel";
    public static final String ALARM_CHANNEL_ID = "guardian_alarm_channel";

    private static final String PREF_NAME = "guardian_prefs";
    public static final String KEY_USE_MGDL = "use_mgdl";
    public static final String KEY_LAST_PACKET_COUNTER = "last_packet_counter";
    public static final String KEY_ALARMS_ENABLED = "alarms_enabled";
    public static final String KEY_ALARM_CAL_ENABLED = "alarm_cal_enabled";
    public static final String KEY_ALARM_MISSED_READINGS_ENABLED = "alarm_missed_readings_enabled";
    public static final String KEY_SNOOZE_UNTIL_MS = "snooze_until_ms";
    public static final String KEY_IS_CAL_PENDING = "is_cal_pending";

    private static final String KEY_APP_KEY = "app_key";
    private static final String KEY_IS_PAIRED = "is_paired";
    private static final String KEY_CAL_TIME = "cal_time";
    private static final String KEY_LAST_MMOL = "last_mmol";
    private static final String KEY_LAST_MGDL = "last_mgdl";
    private static final String KEY_LAST_RAW = "last_raw";
    private static final String KEY_BATTERY_LEVEL = "battery_level";
    private static final String KEY_LAST_MEASUREMENT_TIME = "last_measurement_time";
    private static final String KEY_MAC = "target_mac";
    private static final String KEY_SENSOR_START_TIME = "sensor_start_time";
    private static final String KEY_MEAS_IV = "meas_iv";
    private static final String KEY_MEAS_READY = "meas_ready";
    private static final String KEY_LAST_TREND = "last_trend";
    private static final String KEY_LAST_STATUS_DESC = "last_status_desc";
    private static final String KEY_TX_TIMESTAMP = "tx_timestamp";
    private static final String KEY_TX_SYNC_LOCAL_MS = "tx_sync_local_ms";
    private static final String KEY_IS_IN_WARMUP = "is_in_warmup";
    private static final String KEY_IS_CAL_REQUIRED = "is_cal_required";
    private static final String KEY_WARMUP_START_TIME = "warmup_start_time";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_XDRIP_BROADCAST = "xdrip_broadcast";

    // Alarm rate-limiting
    private long lastAlarmTriggerTimeMs = 0;
    private int lastAlarmType = 0; // 0=none, 1=hypo, 2=hyper, 3=cal

    // Status bitmasks in Measurement packet
    public static final int STATUS_BIT_CONFIG_NEEDED = 1;
    public static final int STATUS_BIT_WARMUP = 4;
    public static final int STATUS_BIT_CAL_REQUIRED = 256;
    public static final int STATUS_BIT_SENSOR_ERROR = 2048;
    public static final int STATUS_BIT_CAL_PENDING = 16384;

    // BLE GATT Service and Characteristic UUIDs for MMT-7821 interoperability
    public static final UUID SERVICE_GUARDIAN = UUID.fromString("b0202e40-008b-11e3-a5f3-0002a5d5c51b");
    public static final UUID CHAR_NON_CRITICAL_OPS = UUID.fromString("d8b8f120-008b-11e3-bda3-0002a5d5c51b");
    public static final UUID CHAR_CRITICAL_OPS = UUID.fromString("f53cbf20-008b-11e3-9a50-0002a5d5c51b");
    public static final UUID CHAR_START_COMMS_RESPONSE = UUID.fromString("cfba6400-008b-11e3-86e6-0002a5d5c51b");
    public static final UUID CHAR_MEASUREMENT_READY = UUID.fromString("b541f660-008b-11e3-8a33-0002a5d5c51b");
    public static final UUID CHAR_MEASUREMENT_DATA = UUID.fromString("b85550e0-008b-11e3-ac4e-0002a5d5c51b");
    public static final UUID CHAR_SENSOR_CONNECTED_STATE = UUID.fromString("b3f0db00-c81d-11e3-9c1a-0800200c9a66");
    public static final UUID CHAR_SENSOR_EXPIRATION = UUID.fromString("d3cb0a40-008b-11e3-8007-0002a5d5c51b");
    public static final UUID CHAR_SENSOR_CAL_EXPIRATION = UUID.fromString("05528ad7-d2d2-39e3-2976-e7fa42dbed19");
    public static final UUID CHAR_CAL_STATUS_READY = UUID.fromString("c52160c0-008b-11e3-9fe8-0002a5d5c51b");
    public static final UUID CHAR_CAL_STATUS_DATA = UUID.fromString("c8e5bbc0-008b-11e3-a1f7-0002a5d5c51b");
    public static final UUID CHAR_BACKFILL_READY = UUID.fromString("bd771900-008b-11e3-b42a-0002a5d5c51b");
    public static final UUID CHAR_BACKFILL_DATA = UUID.fromString("c139ed60-008b-11e3-a001-0002a5d5c51b");
    public static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Standard Bluetooth SIG Battery Service
    public static final UUID SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    public enum HandshakeState {
        IDLE,
        START_COMMS_SENT,
        PAIRING_REQUEST_SENT,
        PAIRING_CHALLENGE_ANSWERED,
        TIME_SYNC_REQUEST_SENT,
        ACTIVE
    }

    public interface ServiceListener {
        void onStatusUpdated(String status);
        void onGlucoseReceived(float mmol, float mgDl, int rawVal, double trend, String statusDesc);
        void onBatteryReceived(int percent);
        void onLogAppended(String log);
        void onConnectionStateChanged(boolean connected);
        void onHandshakeStateChanged(HandshakeState state);
        void onSensorDaysUpdated(int daysRemaining);
    }

    private static volatile GuardianBleService sInstance;
    private final IBinder binder = new LocalBinder();
    private ServiceListener listener;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private PowerManager.WakeLock partialWakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler handshakeHandler = new Handler(Looper.getMainLooper());

    private boolean isConnecting = false;
    private boolean userInitiatedDisconnect = false;
    private boolean isPaired = false;
    private byte[] appKey;
    private String targetMac = "";

    private volatile HandshakeState handshakeState = HandshakeState.IDLE;
    private int pairingRetryCount = 0;
    private int status134RetryCount = 0;
    private byte[] lastPairingPacket = null;
    private byte[] lastCriticalOpsPacket = null;

    private byte[] currentMeasurementIv = null;
    private byte[] currentMeasurementReady = null;
    private byte[] currentCalStatusIv = null;
    private byte[] currentCalStatusReady = null;
    private byte[] currentBackfillIv = null;
    private byte[] currentBackfillReady = null;

    private long lastMeasurementTimeMs = 0;
    private float lastGlucoseMmol = 0.0f;
    private float lastGlucoseMgDl = 0.0f;
    private int lastRawVal = 0;
    private int lastStatusBits = 0;
    private double lastTrendRate = Double.NaN;
    private String lastStatusDescription = "Waiting...";
    private int batteryLevelPercent = -1;
    private long sensorStartTimeMs = 0;
    private boolean isInWarmup = false;
    private boolean isCalRequired = false;
    private boolean isCalPending = false;
    private long warmupStartTimeMs = 0;
    private boolean isGattConnected = false;

    // Alarm ringtone control & background logs
    private Ringtone activeRingtone;
    private final Runnable stopRingtoneRunnable = new Runnable() {
        @Override
        public void run() {
            stopAlarmSound();
        }
    };
    private final StringBuilder logBuffer = new StringBuilder("Service ready...");

    private long lastTransmitterTimestampSec = 0;
    private long lastTransmitterSyncLocalMs = 0;
    private boolean servicesDiscoveryTriggered = false;

    // FIFO GATT Operation Queue (Write, Read, Descriptor)
    private final Queue<Runnable> gattWriteQueue = new ArrayDeque<>();
    private final Queue<BluetoothGattDescriptor> descriptorQueue = new ArrayDeque<>();
    private boolean isGattWriteBusy = false;

    public class LocalBinder extends Binder {
        public GuardianBleService getService() {
            return GuardianBleService.this;
        }
    }

    public static GuardianBleService getInstance() {
        return sInstance;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "GuardianBleService created");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMT7821Client:BleWakeLock");
            partialWakeLock.setReferenceCounted(false);
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        loadSavedState();
        loadLogFromFile();
        createNotificationChannel();
        startForegroundWithNotification();

        mainHandler.postDelayed(notificationUpdateRunnable, 60_000L);
        mainHandler.postDelayed(watchdogRunnable, 10_000L);

        if (isPaired && !userInitiatedDisconnect) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    connectToDevice(targetMac);
                }
            }, 1000L);
        }
    }

    private void acquireWakeLockBriefly(long timeoutMs) {
        try {
            if (partialWakeLock != null) {
                partialWakeLock.acquire(timeoutMs);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                disconnect();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_CONNECT.equals(action)) {
                userInitiatedDisconnect = false;
                connectToDevice(targetMac);
            } else if (ACTION_DISCONNECT.equals(action)) {
                userInitiatedDisconnect = true;
                disconnect();
            } else if (ACTION_DISMISS_ALARM.equals(action)) {
                stopAlarmSound();
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ALARM_ID);
                }
            } else if (ACTION_SNOOZE_ALARM.equals(action)) {
                snoozeAlarms(30);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "GuardianBleService destroyed");
        stopAlarmSound();
        mainHandler.removeCallbacksAndMessages(null);
        handshakeHandler.removeCallbacksAndMessages(null);
        disconnect();
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld()) {
                partialWakeLock.release();
            }
        } catch (Exception ignored) {}
        sInstance = null;
        super.onDestroy();
    }

    public void setListener(ServiceListener l) {
        this.listener = l;
        if (listener != null) {
            stopAlarmSound();
            boolean isConn = (bluetoothGatt != null);
            listener.onConnectionStateChanged(isConn);
            listener.onBatteryReceived(isConn ? batteryLevelPercent : -1);
            listener.onHandshakeStateChanged(handshakeState);
            if (isConn) {
                if (isInWarmup) {
                    listener.onGlucoseReceived(0.0f, 0.0f, lastRawVal, Double.NaN, lastStatusDescription);
                } else if (isCalRequired && lastGlucoseMmol <= 0.0f) {
                    listener.onGlucoseReceived(0.0f, 0.0f, lastRawVal, Double.NaN, lastStatusDescription);
                } else if (lastGlucoseMmol > 0.0f) {
                    listener.onGlucoseReceived(lastGlucoseMmol, lastGlucoseMgDl, lastRawVal, lastTrendRate, lastStatusDescription);
                }
            }
        }
    }

    public void clearListener() {
        this.listener = null;
    }

    private void loadSavedState() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedKeyHex = sp.getString(KEY_APP_KEY, null);
        isPaired = sp.getBoolean(KEY_IS_PAIRED, false);
        targetMac = sp.getString(KEY_MAC, "");
        batteryLevelPercent = sp.getInt(KEY_BATTERY_LEVEL, -1);
        sensorStartTimeMs = sp.getLong(KEY_SENSOR_START_TIME, 0);
        isInWarmup = sp.getBoolean(KEY_IS_IN_WARMUP, false);
        isCalRequired = sp.getBoolean(KEY_IS_CAL_REQUIRED, false);
        isCalPending = sp.getBoolean(KEY_IS_CAL_PENDING, false);
        warmupStartTimeMs = sp.getLong(KEY_WARMUP_START_TIME, 0);

        lastMeasurementTimeMs = sp.getLong(KEY_LAST_MEASUREMENT_TIME, 0);
        lastGlucoseMmol = sp.getFloat(KEY_LAST_MMOL, 0.0f);
        lastGlucoseMgDl = sp.getFloat(KEY_LAST_MGDL, 0.0f);
        lastRawVal = sp.getInt(KEY_LAST_RAW, 0);
        lastTrendRate = sp.getFloat(KEY_LAST_TREND, Float.NaN);
        lastStatusDescription = sp.getString(KEY_LAST_STATUS_DESC, "Normal");
        lastTransmitterTimestampSec = sp.getLong(KEY_TX_TIMESTAMP, 0);
        lastTransmitterSyncLocalMs = sp.getLong(KEY_TX_SYNC_LOCAL_MS, 0);

        String savedIvHex = sp.getString(KEY_MEAS_IV, null);
        String savedReadyHex = sp.getString(KEY_MEAS_READY, null);
        if (savedIvHex != null && savedIvHex.length() == 32) {
            currentMeasurementIv = GuardianCrypto.hexToBytes(savedIvHex);
        }
        if (savedReadyHex != null && savedReadyHex.length() >= 40) {
            currentMeasurementReady = GuardianCrypto.hexToBytes(savedReadyHex);
        }

        if (savedKeyHex != null && savedKeyHex.length() == 32) {
            appKey = GuardianCrypto.hexToBytes(savedKeyHex);
        } else {
            appKey = GuardianCrypto.generateRandom16();
            String newKeyHex = GuardianCrypto.bytesToHexNoSpace(appKey);
            isPaired = false;
            sp.edit().putString(KEY_APP_KEY, newKeyHex).putBoolean(KEY_IS_PAIRED, false).apply();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Guardian Connect CGM",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Continuous glucose monitoring for Guardian Connect sensor");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationChannel alarmChannel = new NotificationChannel(
                    ALARM_CHANNEL_ID,
                    "Guardian Glucose & Safety Alarms",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alarmChannel.setDescription("High-priority audio alarms for Hypoglycemia, Hyperglycemia and Calibration requests");
            alarmChannel.enableVibration(true);
            alarmChannel.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build();
            alarmChannel.setSound(alarmUri, audioAttributes);
            alarmChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
                nm.createNotificationChannel(alarmChannel);
            }
        }
    }

    private void startForegroundWithNotification() {
        Notification notification = buildCurrentNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    public synchronized void updateNotification() {
        Notification notification = buildCurrentNotification();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
        GlucoseWidgetProvider.updateAllWidgets(this);
    }

    private Notification buildCurrentNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title;
        String contentText;
        long now = System.currentTimeMillis();
        long elapsedMs = (lastMeasurementTimeMs > 0) ? (now - lastMeasurementTimeMs) : Long.MAX_VALUE;
        long minsAgo = elapsedMs / 60_000L;

        String batStr = (batteryLevelPercent >= 0) ? (batteryLevelPercent + "%") : "--%";
        String arrow = MainActivity.getTrendArrow(lastTrendRate);
        long lastCalTime = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getLong(KEY_CAL_TIME, 0);

        if (bluetoothGatt == null && !isConnecting) {
            title = "⚠️ Transmitter Disconnected";
            if (lastGlucoseMmol > 0.0f) {
                contentText = String.format(Locale.US, "Last: %.1f %s mmol/L (%d min ago) | 🔋 %s",
                        lastGlucoseMmol, arrow, minsAgo, batStr);
            } else {
                contentText = "Waiting for sensor connection...";
            }
        } else if (isCalPending) {
            title = "⏳ Calibration Processing";
            contentText = "Transmitter calculating calibration (5–15 min) | 🔋 " + batStr;
        } else if (isCalRequired) {
            title = (lastCalTime > 0) ? "🩸 Calibration Required" : "🩸 1st Calibration Required";
            contentText = "Enter blood glucose reading | 🔋 " + batStr;
        } else if (isInWarmup) {
            long remainingWarmupMs = Math.max(0L, 7_200_000L - (now - warmupStartTimeMs));
            long remTotalSec = remainingWarmupMs / 1000L;
            long remHours = remTotalSec / 3600L;
            long remMins = (remTotalSec % 3600L) / 60L;
            long remSecs = remTotalSec % 60L;
            title = "⏳ Enlite Sensor Warm-up";
            if (remainingWarmupMs > 0) {
                if (remHours > 0) {
                    contentText = String.format(Locale.US, "Remaining: %02d:%02d:%02d | 🔋 %s", remHours, remMins, remSecs, batStr);
                } else {
                    contentText = String.format(Locale.US, "Remaining: %02d:%02d | 🔋 %s", remMins, remSecs, batStr);
                }
            } else {
                contentText = (lastCalTime > 0 ? "Waiting for calibration" : "Waiting for 1st calibration") + " | 🔋 " + batStr;
            }
        } else if (elapsedMs > 11 * 60_000L) {
            title = String.format(Locale.US, "⚠️ No Fresh Data (%d min)", minsAgo);
            if (lastGlucoseMmol > 0.0f) {
                contentText = String.format(Locale.US, "Last BG: %.1f %s mmol/L (%d mg/dL) | 🔋 %s",
                        lastGlucoseMmol, arrow, Math.round(lastGlucoseMgDl), batStr);
            } else {
                contentText = "Waiting for transmitter reading...";
            }
        } else if (lastGlucoseMmol > 0.0f) {
            boolean useMgDl = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_USE_MGDL, false);
            if (useMgDl) {
                title = String.format(Locale.US, "%.0f %s mg/dL (%.1f mmol/L)", lastGlucoseMgDl, arrow, lastGlucoseMmol);
            } else {
                title = String.format(Locale.US, "%.1f %s mmol/L (%.0f mg/dL)", lastGlucoseMmol, arrow, lastGlucoseMgDl);
            }
            String timeStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(lastMeasurementTimeMs));
            String agoStr = (minsAgo <= 0) ? "just now" : (minsAgo + " min ago");
            contentText = String.format(Locale.US, "Updated: %s (%s) | 🔋 %s", timeStr, agoStr, batStr);
        } else {
            title = "Guardian Connect (Connected)";
            contentText = "Initializing sensor data... | 🔋 " + batStr;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_SERVICE);
        }

        return builder.build();
    }

    private final Runnable notificationUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            mainHandler.postDelayed(this, 60_000L);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPaired && !userInitiatedDisconnect && bluetoothGatt == null && !isConnecting) {
                log("Watchdog: connection lost. Reconnecting...");
                connectToDevice(targetMac);
            }
            checkMissedReadings();
            mainHandler.postDelayed(this, 10_000L);
        }
    };

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public synchronized void connectToDevice(String mac) {
        if (mac != null) {
            targetMac = mac.trim().toUpperCase(Locale.US);
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_MAC, targetMac).apply();
        }
        if (targetMac == null || targetMac.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            log("Cannot connect: MAC address not set");
            updateStatus("MAC address not configured");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            log("Bluetooth is disabled");
            updateStatus("Bluetooth is disabled");
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            log("BLUETOOTH_CONNECT permission not granted yet");
            updateStatus("Grant Bluetooth permission in app");
            return;
        }

        userInitiatedDisconnect = false;
        status134RetryCount = 0;
        if (isConnecting || bluetoothGatt != null) return;

        BluetoothDevice device;
        try {
            device = bluetoothAdapter.getRemoteDevice(targetMac);
        } catch (IllegalArgumentException e) {
            log("Invalid MAC: " + targetMac);
            return;
        }

        isConnecting = true;
        isGattConnected = false;
        acquireWakeLockBriefly(10_000L);
        updateStatus("Connecting to " + targetMac + "...");
        log("Connecting to " + targetMac + " (isPaired=" + isPaired + ")...");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException se) {
            isConnecting = false;
            log("SecurityException connecting GATT: " + se.getMessage());
        }
    }

    public synchronized void disconnect() {
        userInitiatedDisconnect = true;
        isConnecting = false;
        isGattConnected = false;
        clearGattQueue();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing gatt", e);
            }
            bluetoothGatt = null;
        }
        servicesDiscoveryTriggered = false;
        handshakeState = HandshakeState.IDLE;
        handshakeHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacks(gattWriteTimeoutRunnable);
        updateStatus("Disconnected");
        log("Disconnected from transmitter.");
        notifyConnectionState(false);
        updateNotification();
    }

    public void rePairTransmitter() {
        log("Resetting pairing. Generating new AppKey...");
        appKey = GuardianCrypto.generateRandom16();
        String newKeyHex = GuardianCrypto.bytesToHexNoSpace(appKey);
        isPaired = false;
        pairingRetryCount = 0;
        sensorStartTimeMs = 0;
        isCalRequired = false;
        isCalPending = false;
        lastGlucoseMmol = 0.0f;
        lastGlucoseMgDl = 0.0f;
        lastMeasurementTimeMs = 0;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString(KEY_APP_KEY, newKeyHex)
                .putBoolean(KEY_IS_PAIRED, false)
                .putLong(KEY_SENSOR_START_TIME, 0)
                .putBoolean(KEY_IS_CAL_REQUIRED, false)
                .putBoolean(KEY_IS_CAL_PENDING, false)
                .putFloat(KEY_LAST_MMOL, 0.0f)
                .putFloat(KEY_LAST_MGDL, 0.0f)
                .putLong(KEY_LAST_MEASUREMENT_TIME, 0)
                .apply();
        disconnect();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                userInitiatedDisconnect = false;
                connectToDevice(targetMac);
            }
        }, 1500L);
    }

    public void sendCalibration(float bgMmol) {
        stopAlarmSound();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ALARM_ID);
        }
        int bgMgDl = Math.round(bgMmol * 18.0182f);
        long now = System.currentTimeMillis();
        isCalPending = true;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_IS_CAL_PENDING, true)
                .apply();

        // Single source of truth for calibration storage
        GlucoseHistoryStorage.getInstance(this).addPoint(now, bgMmol, (float) bgMgDl, true);

        if (bluetoothGatt != null) {
            long elapsedSec = (lastTransmitterSyncLocalMs > 0) ? (now - lastTransmitterSyncLocalMs) / 1000L : 0L;
            long txTime = (lastTransmitterTimestampSec > 0) ? (lastTransmitterTimestampSec + elapsedSec) : (now / 1000L);
            byte[] calPacket = GuardianCrypto.buildCalibrationPacket(bgMgDl, txTime);
            log(String.format(Locale.US, "Sending calibration %d mg/dL (%.1f mmol/L) to CRITICAL_OPS: %s",
                    bgMgDl, bgMmol, GuardianCrypto.bytesToHex(calPacket)));
            sendCriticalOps(bluetoothGatt, calPacket);
        }
        updateStatus("⏳ Calibration sent, processing...");
        updateNotification();
    }

    public void sendSensorConfiguration(final boolean isNewSensor) {
        if (bluetoothGatt == null) return;
        byte[] packet = GuardianCrypto.buildSensorConfigurationPacket(isNewSensor);
        log(String.format(Locale.US, "Sending sensor configuration (Opcode 0x05, isNew=%b): %s",
                isNewSensor, GuardianCrypto.bytesToHex(packet)));
        updateStatus("Sending sensor configuration (Opcode 0x05)...");
        sendCriticalOps(bluetoothGatt, packet);
    }

    private synchronized void enqueueGattWrite(Runnable r) {
        gattWriteQueue.add(r);
        processNextGattWrite();
    }

    private synchronized void processNextGattWrite() {
        if (isGattWriteBusy || gattWriteQueue.isEmpty()) return;
        isGattWriteBusy = true;
        Runnable next = gattWriteQueue.poll();
        if (next != null) {
            mainHandler.removeCallbacks(gattWriteTimeoutRunnable);
            mainHandler.postDelayed(gattWriteTimeoutRunnable, 6000L);
            next.run();
        }
    }

    private synchronized void onGattWriteCompleted() {
        isGattWriteBusy = false;
        mainHandler.removeCallbacks(gattWriteTimeoutRunnable);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                processNextGattWrite();
            }
        });
    }

    private final Runnable gattWriteTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isGattWriteBusy) {
                log("GATT operation timed out after 6000ms, clearing busy lock");
                onGattWriteCompleted();
            }
        }
    };

    private synchronized void clearGattQueue() {
        gattWriteQueue.clear();
        descriptorQueue.clear();
        isGattWriteBusy = false;
        mainHandler.removeCallbacks(gattWriteTimeoutRunnable);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            acquireWakeLockBriefly(8000L);
            log("ConnectionStateChange: status=" + status + ", newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnecting = false;
                isGattConnected = true;
                clearGattQueue();
                updateStatus("Connected. Requesting MTU...");
                log("GATT Connected! Requesting MTU 512...");
                try {
                    BluetoothDevice dev = gatt.getDevice();
                    if (dev != null && dev.getName() != null && !dev.getName().isEmpty()) {
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_DEVICE_NAME, dev.getName()).apply();
                    }
                } catch (Exception ignored) {}
                notifyConnectionState(true);
                updateNotification();
                GlucoseWidgetProvider.updateAllWidgets(GuardianBleService.this);

                servicesDiscoveryTriggered = false;
                handshakeState = HandshakeState.IDLE;
                mainHandler.removeCallbacks(mtuTimeoutRunnable);
                mainHandler.postDelayed(mtuTimeoutRunnable, 3000L);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(512);
                } else {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnecting = false;
                isGattConnected = false;
                clearGattQueue();
                if (bluetoothGatt != null) {
                    try {
                        bluetoothGatt.close();
                    } catch (Exception ignored) {}
                    bluetoothGatt = null;
                }
                servicesDiscoveryTriggered = false;
                mainHandler.removeCallbacks(mtuTimeoutRunnable);
                handshakeState = HandshakeState.IDLE;
                updateStatus("Disconnected");
                log("GATT Disconnected (status: " + status + ")");
                notifyConnectionState(false);
                updateNotification();
                GlucoseWidgetProvider.updateAllWidgets(GuardianBleService.this);

                if (!userInitiatedDisconnect) {
                    log("Connection lost! Auto-reconnecting in 4s...");
                    updateStatus("Connection lost. Reconnecting...");
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt == null && !userInitiatedDisconnect) {
                                connectToDevice(targetMac);
                            }
                        }
                    }, 4000L);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            log("MTU negotiated: " + mtu + " (status: " + status + ")");
            mainHandler.removeCallbacks(mtuTimeoutRunnable);
            if (!servicesDiscoveryTriggered) {
                servicesDiscoveryTriggered = true;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bluetoothGatt != null) {
                            log("Discovering services...");
                            bluetoothGatt.discoverServices();
                        }
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            acquireWakeLockBriefly(8000L);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed with status: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_GUARDIAN);
            if (service == null) {
                log("ERROR: Guardian service b0202e40 not found!");
                return;
            }

            log("Guardian service b0202e40 found! Setting up indications...");
            updateStatus("Subscribing to indications...");

            descriptorQueue.clear();
            UUID[] indicateUuids = new UUID[] {
                CHAR_CRITICAL_OPS,
                CHAR_START_COMMS_RESPONSE,
                CHAR_SENSOR_CONNECTED_STATE,
                CHAR_SENSOR_EXPIRATION,
                CHAR_MEASUREMENT_READY,
                CHAR_SENSOR_CAL_EXPIRATION,
                CHAR_CAL_STATUS_READY,
                CHAR_BACKFILL_READY
            };

            for (UUID uuid : indicateUuids) {
                BluetoothGattCharacteristic ch = service.getCharacteristic(uuid);
                if (ch != null) {
                    gatt.setCharacteristicNotification(ch, true);
                    BluetoothGattDescriptor desc = ch.getDescriptor(UUID_CCCD);
                    if (desc != null) {
                        descriptorQueue.add(desc);
                    }
                }
            }

            BluetoothGattService batteryService = gatt.getService(SERVICE_BATTERY);
            if (batteryService != null) {
                BluetoothGattCharacteristic batChar = batteryService.getCharacteristic(CHAR_BATTERY_LEVEL);
                if (batChar != null) {
                    gatt.setCharacteristicNotification(batChar, true);
                    BluetoothGattDescriptor desc = batChar.getDescriptor(UUID_CCCD);
                    if (desc != null) {
                        descriptorQueue.add(desc);
                    }
                }
            }

            processNextDescriptor(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            acquireWakeLockBriefly(5000L);
            String charShortUuid = descriptor.getCharacteristic().getUuid().toString().substring(0, 8);
            log("onDescriptorWrite: " + charShortUuid + ", status=" + status);
            if (!descriptorQueue.isEmpty()) {
                processNextDescriptor(gatt);
            } else {
                log("All indications enabled!");
                updateStatus("Indications ready. Starting comms...");
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        handshakeState = HandshakeState.START_COMMS_SENT;
                        sendStartCommunication(gatt);
                    }
                }, 300L);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            acquireWakeLockBriefly(5000L);
            String shortUuid = characteristic.getUuid().toString().substring(0, 8);
            log("onCharacteristicWrite (" + shortUuid + "): status=" + status);
            onGattWriteCompleted();

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().equals(CHAR_CRITICAL_OPS)) {
                    log("ERROR: CRITICAL_OPS write rejected by transmitter! (status=" + status + ")");
                    if (status == 134) {
                        if (++status134RetryCount <= 3) {
                            log("WARNING: CRITICAL_OPS write returned status 134 (attempt " + status134RetryCount + "/3). Retrying in 3500ms...");
                            updateStatus("Transmitter stabilizing... Retry " + status134RetryCount + "/3");
                            handshakeHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (bluetoothGatt == null) return;
                                    if (handshakeState == HandshakeState.PAIRING_REQUEST_SENT) {
                                        sendSecurityPairing(bluetoothGatt);
                                    } else if (lastCriticalOpsPacket != null) {
                                        sendCriticalOps(bluetoothGatt, lastCriticalOpsPacket);
                                    }
                                }
                            }, 3500L);
                            return;
                        } else {
                            log("Reason: Transmitter is on sensor and locked key change!");
                            updateStatus("Error 134: Remove transmitter from sensor and reset in charger!");
                        }
                    }
                }
                return;
            }

            if (characteristic.getUuid().equals(CHAR_CRITICAL_OPS)) {
                status134RetryCount = 0;
            }

            if (characteristic.getUuid().equals(CHAR_NON_CRITICAL_OPS)) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (bluetoothGatt != null) {
                            BluetoothGattService bs = bluetoothGatt.getService(SERVICE_BATTERY);
                            if (bs != null) {
                                final BluetoothGattCharacteristic bc = bs.getCharacteristic(CHAR_BATTERY_LEVEL);
                                if (bc != null) {
                                    enqueueGattWrite(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (bluetoothGatt == null) {
                                                onGattWriteCompleted();
                                                return;
                                            }
                                            boolean ok = bluetoothGatt.readCharacteristic(bc);
                                            if (!ok) onGattWriteCompleted();
                                        }
                                    });
                                }
                            }
                        }
                    }
                }, 500L);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            acquireWakeLockBriefly(6000L);
            handleNotification(gatt, characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            onGattWriteCompleted(); // Release GATT queue busy lock after read completes!
            acquireWakeLockBriefly(6000L);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Characteristic read failed: " + status);
                return;
            }

            UUID uuid = characteristic.getUuid();
            if (uuid.equals(CHAR_BATTERY_LEVEL)) {
                byte[] raw = characteristic.getValue();
                if (raw != null && raw.length > 0) {
                    int bat = raw[0] & 0xFF;
                    batteryLevelPercent = bat;
                    log("Transmitter Battery read: " + bat + "%");
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(KEY_BATTERY_LEVEL, bat).apply();
                    if (listener != null) listener.onBatteryReceived(bat);
                    updateNotification();
                }
            } else if (uuid.equals(CHAR_MEASUREMENT_DATA)) {
                byte[] raw = characteristic.getValue();
                log("MEASUREMENT_DATA raw (" + (raw != null ? raw.length : 0) + "B): " + GuardianCrypto.bytesToHex(raw));
                handleMeasurementData(raw);
            } else if (uuid.equals(CHAR_CAL_STATUS_DATA)) {
                byte[] raw = characteristic.getValue();
                log("CAL_STATUS_DATA raw: " + GuardianCrypto.bytesToHex(raw));
                handleCalStatusData(raw);
            } else if (uuid.equals(CHAR_BACKFILL_DATA)) {
                byte[] raw = characteristic.getValue();
                log("BACKFILL_DATA raw (" + (raw != null ? raw.length : 0) + "B): " + GuardianCrypto.bytesToHex(raw));
                handleBackfillData(raw);
            }
        }
    };

    private void processNextDescriptor(BluetoothGatt gatt) {
        if (!descriptorQueue.isEmpty()) {
            BluetoothGattDescriptor desc = descriptorQueue.poll();
            byte[] val = (desc.getCharacteristic().getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            String charShortUuid = desc.getCharacteristic().getUuid().toString().substring(0, 8);
            log("Writing CCCD for " + charShortUuid + " (remain=" + descriptorQueue.size() + ")...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int res = gatt.writeDescriptor(desc, val);
                if (res != 0) {
                    log("Warning: writeDescriptor for " + charShortUuid + " returned status: " + res);
                }
            } else {
                desc.setValue(val);
                boolean ok = gatt.writeDescriptor(desc);
                if (!ok) {
                    log("Warning: writeDescriptor for " + charShortUuid + " returned false");
                }
            }
        }
    }

    private void sendStartCommunication(final BluetoothGatt gatt) {
        enqueueGattWrite(new Runnable() {
            int retryCount = 0;
            @Override
            public void run() {
                if (bluetoothGatt == null) {
                    onGattWriteCompleted();
                    return;
                }
                BluetoothGattService service = gatt.getService(SERVICE_GUARDIAN);
                if (service == null) {
                    onGattWriteCompleted();
                    return;
                }
                BluetoothGattCharacteristic ch = service.getCharacteristic(CHAR_NON_CRITICAL_OPS);
                if (ch == null) {
                    onGattWriteCompleted();
                    return;
                }

                byte[] payload = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
                log("Writing requestStartCommunication (0x00) to NON_CRITICAL_OPS...");
                int res;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    res = gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else {
                    ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    ch.setValue(payload);
                    boolean ok = gatt.writeCharacteristic(ch);
                    res = ok ? 0 : -1;
                }
                log("writeCharacteristic (NON_CRITICAL_OPS) returned: " + res);

                if (res != 0) {
                    if (++retryCount <= 3) {
                        log("GATT write busy (" + res + "). Retrying in 1200ms...");
                        mainHandler.postDelayed(this, 1200L);
                    } else {
                        log("GATT write failed after 3 retries. Skipping...");
                        onGattWriteCompleted();
                    }
                }
            }
        });
    }

    private void sendSecurityPairing(final BluetoothGatt gatt) {
        handshakeHandler.removeCallbacksAndMessages(null);
        handshakeState = HandshakeState.PAIRING_REQUEST_SENT;
        handshakeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt == null) return;
                byte[] ivPair = GuardianCrypto.generateRandom16();
                byte[] securityPacket = GuardianCrypto.buildSecurityPacket(appKey, ivPair);
                lastPairingPacket = securityPacket;
                log("Writing 36-byte security pairing packet to CRITICAL_OPS: " + GuardianCrypto.bytesToHex(securityPacket));
                sendCriticalOps(gatt, securityPacket);
            }
        }, 300L);
    }

    private void sendTimeSync(final BluetoothGatt gatt) {
        handshakeHandler.removeCallbacksAndMessages(null);
        handshakeState = HandshakeState.TIME_SYNC_REQUEST_SENT;
        lastPairingPacket = null;
        handshakeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt == null) return;
                long currentSec = System.currentTimeMillis() / 1000L;
                byte[] timePacket = GuardianCrypto.buildTimeSyncPacket(currentSec);
                log("Wrote syncTime (Opcode 0x06, " + currentSec + "s) to CRITICAL_OPS: " + GuardianCrypto.bytesToHex(timePacket));
                sendCriticalOps(gatt, timePacket);
                updateStatus("Time sync request sent...");
            }
        }, 300L);

        handshakeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (handshakeState == HandshakeState.TIME_SYNC_REQUEST_SENT) {
                    handshakeState = HandshakeState.ACTIVE;
                    log("Time Sync: fallback timeout (no Opcode 0x07). Active monitoring state.");
                    updateStatus("Monitoring active. Waiting for reading (~5 min)...");
                    if (listener != null) listener.onHandshakeStateChanged(handshakeState);
                }
            }
        }, 8000L);
    }

    private void sendCriticalOps(final BluetoothGatt gatt, final byte[] data) {
        if (data != null && data.length > 0 && data[0] != 0x03) {
            lastCriticalOpsPacket = data;
        }

        enqueueGattWrite(new Runnable() {
            int retryCount = 0;
            @Override
            public void run() {
                if (bluetoothGatt == null) {
                    onGattWriteCompleted();
                    return;
                }
                BluetoothGattService service = gatt.getService(SERVICE_GUARDIAN);
                if (service == null) {
                    onGattWriteCompleted();
                    return;
                }
                final BluetoothGattCharacteristic ch = service.getCharacteristic(CHAR_CRITICAL_OPS);
                if (ch == null) {
                    onGattWriteCompleted();
                    return;
                }

                log("Writing " + data.length + "B to CRITICAL_OPS: " + GuardianCrypto.bytesToHex(data));
                int res;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    res = gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else {
                    ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    ch.setValue(data);
                    boolean ok = gatt.writeCharacteristic(ch);
                    res = ok ? 0 : -1;
                }
                log("writeCharacteristic (CRITICAL_OPS) returned: " + res);

                if (res != 0) {
                    if (++retryCount <= 3) {
                        log("GATT write busy (" + res + "). Retrying in 1200ms...");
                        mainHandler.postDelayed(this, 1200L);
                    } else {
                        log("GATT write failed after 3 retries. Skipping...");
                        onGattWriteCompleted();
                    }
                }
            }
        });
    }

    public void requestBackfillRange(long startCnt, long endCnt) {
        if (bluetoothGatt == null) {
            log("Cannot request backfill: BluetoothGatt is null");
            return;
        }
        log("Gap detected in packet counter (" + (startCnt - 1) + " -> " + (endCnt + 1) + "). Requesting backfill...");
        byte[] packet = GuardianCrypto.buildBackfillRequestPacket(startCnt, endCnt);
        sendCriticalOps(bluetoothGatt, packet);
    }

    private void handleNotification(BluetoothGatt gatt, UUID uuid, byte[] value) {
        if (value == null) return;

        if (uuid.equals(CHAR_BATTERY_LEVEL)) {
            if (value.length > 0) {
                int bat = value[0] & 0xFF;
                batteryLevelPercent = bat;
                log("Transmitter Battery notification: " + bat + "%");
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(KEY_BATTERY_LEVEL, bat).apply();
                if (listener != null) listener.onBatteryReceived(bat);
                updateNotification();
            }
            return;
        }

        if (uuid.equals(CHAR_START_COMMS_RESPONSE)) {
            log("START_COMMS_RESPONSE: " + GuardianCrypto.bytesToHex(value));
            handshakeHandler.removeCallbacksAndMessages(null);

            boolean rePairingRequired = (value.length >= 10 && value[9] != 0);
            boolean appSecurityEnabled = (value.length >= 11 && value[10] != 0);

            if (handshakeState == HandshakeState.IDLE || handshakeState == HandshakeState.START_COMMS_SENT) {
                if (rePairingRequired || !isPaired || !appSecurityEnabled) {
                    log("Transmitter requires pairing (rePair=" + rePairingRequired + ", paired=" + isPaired + ", sec=" + appSecurityEnabled + "). Waiting 4.0s for transmitter power stabilization...");
                    updateStatus("Preparing pairing...");
                    handshakeHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt != null) {
                                sendSecurityPairing(bluetoothGatt);
                            }
                        }
                    }, 4000L);
                } else {
                    log("Session active! Synchronizing time in 1s...");
                    updateStatus("Syncing time...");
                    handshakeHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt != null) {
                                sendTimeSync(bluetoothGatt);
                            }
                        }
                    }, 1000L);
                }
            } else if (handshakeState == HandshakeState.PAIRING_REQUEST_SENT || handshakeState == HandshakeState.PAIRING_CHALLENGE_ANSWERED) {
                if (!rePairingRequired && appSecurityEnabled) {
                    isPaired = true;
                    pairingRetryCount = 0;
                    lastPairingPacket = null;
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(KEY_IS_PAIRED, true)
                            .putString(KEY_APP_KEY, GuardianCrypto.bytesToHexNoSpace(appKey))
                            .apply();
                    log("Pairing confirmed by START_COMMS_RESPONSE (rePair=false, sec=true)! Synchronizing time in 1s...");
                    updateStatus("Pairing successful! Syncing time...");
                    handshakeHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt != null) {
                                sendTimeSync(bluetoothGatt);
                            }
                        }
                    }, 1000L);
                } else {
                    if (++pairingRetryCount <= 1) {
                        log("START_COMMS_RESPONSE: rePairingRequired=" + rePairingRequired + " (attempt " + pairingRetryCount + "/1). Retrying in 2s...");
                        updateStatus("Resending pairing key...");
                        handshakeHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (bluetoothGatt != null) {
                                    sendSecurityPairing(bluetoothGatt);
                                }
                            }
                        }, 2000L);
                    } else {
                        handshakeState = HandshakeState.IDLE;
                        log("ERROR: Transmitter rejected pairing (rePair=" + rePairingRequired + "). Disconnect from sensor, place in charger for 10s, remove and retry.");
                        updateStatus("Transmitter reset via charger required");
                    }
                }
            } else {
                log("START_COMMS_RESPONSE received in state " + handshakeState + " (ignored)");
            }
        } else if (uuid.equals(CHAR_CRITICAL_OPS)) {
            log("CRITICAL_OPS indication: " + GuardianCrypto.bytesToHex(value));
            if (value.length > 0) {
                int opcode = value[0] & 0xFF;
                if (opcode == 0x02) {
                    log("Transmitter Challenge received (Opcode 0x02)!");
                    if (value.length >= 17 && lastCriticalOpsPacket != null) {
                        handshakeHandler.removeCallbacksAndMessages(null);

                        byte[] nonce = Arrays.copyOfRange(value, 1, 17);
                        final boolean isPairingCmd = (lastCriticalOpsPacket[0] == 0x00);
                        byte[] basePacket = lastCriticalOpsPacket;

                        // Protocol challenge verification uses the session AppKey
                        // (both during initial pairing after Opcode 0x00 and session sync).
                        final byte[] challengeResp = GuardianCrypto.computeChallengeResponse(appKey, basePacket, nonce);

                        log("Computed HMAC response (Opcode 0x03, key=APP_KEY). Sending in 1000ms...");
                        if (isPairingCmd) {
                            handshakeState = HandshakeState.PAIRING_CHALLENGE_ANSWERED;
                        }

                        handshakeHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (bluetoothGatt != null) {
                                    sendCriticalOps(bluetoothGatt, challengeResp);
                                }
                            }
                        }, 1000L);
                    } else {
                        log("ERROR: Cannot answer challenge (length=" + value.length + ", basePacket=" + (lastCriticalOpsPacket != null) + ")");
                    }
                } else if (opcode == 0x07) {
                    handshakeHandler.removeCallbacksAndMessages(null);
                    handshakeState = HandshakeState.ACTIVE;
                    String opDesc = "Command acknowledged by transmitter (Opcode 0x07 ACK)!";
                    if (lastCriticalOpsPacket != null && lastCriticalOpsPacket.length > 0) {
                        if (lastCriticalOpsPacket[0] == 0x04) {
                            opDesc = "🎉 Calibration accepted by transmitter (Opcode 0x04 -> ACK 0x07)!";
                        } else if (lastCriticalOpsPacket[0] == 0x05) {
                            opDesc = "Sensor configuration accepted by transmitter (Opcode 0x05 -> ACK 0x07)!";
                        } else if (lastCriticalOpsPacket[0] == 0x06) {
                            opDesc = "Time sync acknowledged (Opcode 0x06 -> ACK 0x07)!";
                            if (value.length >= 9) {
                                ByteBuffer tBuf = ByteBuffer.wrap(value, 5, 4).order(ByteOrder.LITTLE_ENDIAN);
                                long txSec = tBuf.getInt() & 0xFFFFFFFFL;
                                if (txSec > 0) {
                                    lastTransmitterTimestampSec = txSec;
                                    lastTransmitterSyncLocalMs = System.currentTimeMillis();
                                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                            .putLong(KEY_TX_TIMESTAMP, lastTransmitterTimestampSec)
                                            .putLong(KEY_TX_SYNC_LOCAL_MS, lastTransmitterSyncLocalMs)
                                            .apply();
                                    opDesc += " Transmitter clock: " + txSec + "s";
                                }
                            }
                        }
                    }
                    log(opDesc + " Payload: " + GuardianCrypto.bytesToHex(value));
                    updateStatus("Command accepted by transmitter!");
                    if (listener != null) listener.onHandshakeStateChanged(handshakeState);
                }
            }
        } else if (uuid.equals(CHAR_SENSOR_CONNECTED_STATE)) {
            boolean connected = (value.length > 0 && value[0] != 0);
            log("SENSOR_CONNECTED_STATE: " + (connected ? "Connected (Enlite)" : "Disconnected"));
        } else if (uuid.equals(CHAR_MEASUREMENT_READY)) {
            log("MEASUREMENT_READY received: " + GuardianCrypto.bytesToHex(value));
            handleMeasurementReady(gatt, value);
        } else if (uuid.equals(CHAR_CAL_STATUS_READY)) {
            log("CAL_STATUS_READY received: " + GuardianCrypto.bytesToHex(value));
            if (value.length >= 20) {
                currentCalStatusReady = value.clone();
                currentCalStatusIv = Arrays.copyOfRange(value, 4, 20);
            }
            BluetoothGattService s = gatt.getService(SERVICE_GUARDIAN);
            if (s != null) {
                final BluetoothGattCharacteristic calDataChar = s.getCharacteristic(CHAR_CAL_STATUS_DATA);
                if (calDataChar != null) {
                    log("Queueing read of CAL_STATUS_DATA (c8e5bbc0)...");
                    enqueueGattWrite(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt == null || gatt != bluetoothGatt) {
                                onGattWriteCompleted();
                                return;
                            }
                            boolean ok = gatt.readCharacteristic(calDataChar);
                            if (!ok) onGattWriteCompleted();
                        }
                    });
                }
            }
        } else if (uuid.equals(CHAR_SENSOR_EXPIRATION)) {
            log("SENSOR_EXPIRATION: " + GuardianCrypto.bytesToHex(value));
            if (value.length >= 4) {
                ByteBuffer expBuf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                long expSeconds = expBuf.getInt(0) & 0xFFFFFFFFL;
                if (expSeconds > 0) {
                    long remainingSec;
                    if (expSeconds > 1_500_000_000L) {
                        remainingSec = Math.max(0, expSeconds - (System.currentTimeMillis() / 1000L));
                    } else if (lastTransmitterTimestampSec > 0 && expSeconds > lastTransmitterTimestampSec) {
                        remainingSec = expSeconds - lastTransmitterTimestampSec;
                    } else {
                        remainingSec = Math.min(6L * 24L * 3600L, expSeconds);
                    }
                    remainingSec = Math.max(0, Math.min(6L * 24L * 3600L, remainingSec));
                    sensorStartTimeMs = System.currentTimeMillis() - ((6L * 24L * 3600L - remainingSec) * 1000L);
                    if (sensorStartTimeMs > System.currentTimeMillis()) {
                        sensorStartTimeMs = System.currentTimeMillis();
                    }
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putLong(KEY_SENSOR_START_TIME, sensorStartTimeMs)
                            .apply();
                    long daysUsed = (System.currentTimeMillis() - sensorStartTimeMs) / (24L * 3600_000L);
                    int daysRemaining = (int) Math.max(0, Math.min(6, 6 - daysUsed));
                    if (listener != null) listener.onSensorDaysUpdated(daysRemaining);
                }
            }
        } else if (uuid.equals(CHAR_SENSOR_CAL_EXPIRATION)) {
            log("SENSOR_CAL_EXPIRATION: " + GuardianCrypto.bytesToHex(value));
        } else if (uuid.equals(CHAR_BACKFILL_READY)) {
            log("BACKFILL_READY: " + GuardianCrypto.bytesToHex(value));
            if (value.length >= 20) {
                currentBackfillReady = value.clone();
                currentBackfillIv = Arrays.copyOfRange(value, 4, 20);
                log("Extracted Backfill IV: " + GuardianCrypto.bytesToHexNoSpace(currentBackfillIv));
            }
            BluetoothGattService s = gatt.getService(SERVICE_GUARDIAN);
            if (s != null) {
                final BluetoothGattCharacteristic bfDataChar = s.getCharacteristic(CHAR_BACKFILL_DATA);
                if (bfDataChar != null) {
                    log("Queueing read of BACKFILL_DATA (c139ed60)...");
                    enqueueGattWrite(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt == null || gatt != bluetoothGatt) {
                                onGattWriteCompleted();
                                return;
                            }
                            boolean ok = gatt.readCharacteristic(bfDataChar);
                            if (!ok) onGattWriteCompleted();
                        }
                    });
                }
            }
        } else {
            log("Indication on " + uuid.toString().substring(0, 8) + ": " + GuardianCrypto.bytesToHex(value));
        }
    }

    private void handleMeasurementReady(final BluetoothGatt gatt, byte[] value) {
        if (value == null || value.length < 20) {
            log("Invalid MeasurementReady length: " + (value != null ? value.length : 0));
            return;
        }

        currentMeasurementReady = value.clone();
        currentMeasurementIv = Arrays.copyOfRange(value, 4, 20);
        lastMeasurementTimeMs = System.currentTimeMillis();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_MEASUREMENT_TIME, lastMeasurementTimeMs)
                .putString(KEY_MEAS_IV, GuardianCrypto.bytesToHexNoSpace(currentMeasurementIv))
                .putString(KEY_MEAS_READY, GuardianCrypto.bytesToHexNoSpace(currentMeasurementReady))
                .apply();
        log("Extracted Measurement IV: " + GuardianCrypto.bytesToHexNoSpace(currentMeasurementIv));

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                readMeasurementData(gatt, 0);
            }
        }, 500L);
    }

    private void readMeasurementData(final BluetoothGatt gatt, final int attempt) {
        // Guard against stale gatt object after disconnect
        if (gatt == null || gatt != bluetoothGatt) {
            log("readMeasurementData aborted: GATT disconnected or stale");
            return;
        }
        BluetoothGattService service = gatt.getService(SERVICE_GUARDIAN);
        if (service == null) return;
        final BluetoothGattCharacteristic measDataChar = service.getCharacteristic(CHAR_MEASUREMENT_DATA);
        if (measDataChar == null) return;

        // Enqueue read to prevent collision with other GATT operations
        enqueueGattWrite(new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt == null || gatt != bluetoothGatt) {
                    onGattWriteCompleted();
                    return;
                }
                log("Reading MEASUREMENT_DATA (b85550e0)... (attempt " + (attempt + 1) + ")");
                boolean ok = gatt.readCharacteristic(measDataChar);
                log("readCharacteristic returned: " + ok);
                if (!ok) {
                    onGattWriteCompleted();
                    if (attempt < 5) {
                        log("GATT busy for read, retrying in 500ms (" + (attempt + 1) + "/5)...");
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                readMeasurementData(gatt, attempt + 1);
                            }
                        }, 500L);
                    } else {
                        log("ERROR: Failed to read MEASUREMENT_DATA after 5 attempts.");
                    }
                }
            }
        });
    }

    private void handleMeasurementData(byte[] raw) {
        if (raw == null || raw.length < 10) {
            log("Cannot decrypt: data is null or shorter than 10 bytes header");
            return;
        }

        if (currentMeasurementIv == null || currentMeasurementReady == null) {
            log("Cannot decrypt: IV or Ready packet is null");
            return;
        }

        try {
            ByteBuffer metaBuf = ByteBuffer.wrap(raw, 0, 10).order(ByteOrder.LITTLE_ENDIAN);
            int hdr = metaBuf.getShort(0) & 0xFFFF;
            long counter = metaBuf.getInt(2) & 0xFFFFFFFFL;
            long tsSeconds = metaBuf.getInt(6) & 0xFFFFFFFFL;
            lastTransmitterTimestampSec = tsSeconds;
            lastTransmitterSyncLocalMs = System.currentTimeMillis();
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putLong(KEY_TX_TIMESTAMP, lastTransmitterTimestampSec)
                    .putLong(KEY_TX_SYNC_LOCAL_MS, lastTransmitterSyncLocalMs)
                    .apply();
            log(String.format(Locale.US, "MEASUREMENT Packet: Cnt=%d, Time=%ds, Hdr=0x%04X", counter, tsSeconds, hdr));

            if (raw.length <= 10) {
                log("No encrypted payload found (raw length: " + raw.length + ")");
                return;
            }

            int expectedCrc = (currentMeasurementReady[2] & 0xFF) | ((currentMeasurementReady[3] & 0xFF) << 8);
            byte[] encryptedPayload = Arrays.copyOfRange(raw, 10, raw.length);
            byte[] decrypted = GuardianCrypto.decryptAesCtr(encryptedPayload, appKey, currentMeasurementIv);
            int calcCrc = GuardianCrypto.calcCrc16(decrypted);

            boolean crcOk = (calcCrc == expectedCrc);
            if (!crcOk) {
                byte[] decMaster = GuardianCrypto.decryptAesCtr(encryptedPayload, GuardianCrypto.MASTER_KEY, currentMeasurementIv);
                int crcMaster = GuardianCrypto.calcCrc16(decMaster);
                if (crcMaster == expectedCrc) {
                    log("Note: Payload matches MasterKey encryption!");
                    decrypted = decMaster;
                    calcCrc = crcMaster;
                    crcOk = true;
                }
            }

            if (!crcOk) {
                log(String.format(Locale.US, "Warning: CRC mismatch (0x%04X != 0x%04X). Reading skipped to avoid false data.", calcCrc, expectedCrc));
                updateStatus(String.format(Locale.US, "Reading skipped (CRC mismatch: 0x%04X != 0x%04X)", calcCrc, expectedCrc));
                return;
            }

            log("SUCCESS: CRC-16 verified! Packet is authentic.");
            lastMeasurementTimeMs = System.currentTimeMillis();
            SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            sp.edit().putLong(KEY_LAST_MEASUREMENT_TIME, lastMeasurementTimeMs).apply();

            long prevCounter = sp.getLong(KEY_LAST_PACKET_COUNTER, 0L);
            sp.edit().putLong(KEY_LAST_PACKET_COUNTER, counter).apply();

            if (prevCounter > 0 && counter > prevCounter + 1 && (counter - prevCounter) <= 144) {
                final long startCnt = prevCounter + 1;
                final long endCnt = counter - 1;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        requestBackfillRange(startCnt, endCnt);
                    }
                }, 3000L);
            }

            if (decrypted.length >= 9) {
                ByteBuffer buf = ByteBuffer.wrap(decrypted).order(ByteOrder.LITTLE_ENDIAN);
                int sigType1 = buf.get(1) & 0xFF;
                int sigType2 = buf.get(2) & 0xFF;
                int rawVal34 = buf.getShort(3) & 0xFFFF;
                int statusBits = buf.getShort(5) & 0xFFFF;
                short trendRaw = buf.getShort(7);
                double trendRate = (trendRaw == 0x7FFF || Math.abs(trendRaw) > 1000) ? Double.NaN : (trendRaw / 100.0d);

                lastRawVal = rawVal34;
                lastStatusBits = statusBits;
                lastTrendRate = trendRate;

                boolean configNeeded = (statusBits & STATUS_BIT_CONFIG_NEEDED) != 0;
                boolean inWarmup = (statusBits & STATUS_BIT_WARMUP) != 0;
                boolean calRequired = (statusBits & STATUS_BIT_CAL_REQUIRED) != 0;
                boolean calPending = (statusBits & STATUS_BIT_CAL_PENDING) != 0;
                boolean sensorError = (statusBits & STATUS_BIT_SENSOR_ERROR) != 0;

                isCalRequired = calRequired;
                if (calPending) {
                    isCalPending = true;
                } else if (calRequired) {
                    isCalPending = false;
                }

                if (inWarmup) {
                    isInWarmup = true;
                    if (warmupStartTimeMs <= 0 || (counter > 0 && Math.abs((System.currentTimeMillis() - warmupStartTimeMs) - counter * 300_000L) > 600_000L)) {
                        warmupStartTimeMs = System.currentTimeMillis() - (counter * 300_000L);
                    }
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(KEY_IS_IN_WARMUP, true)
                            .putBoolean(KEY_IS_CAL_REQUIRED, isCalRequired)
                            .putBoolean(KEY_IS_CAL_PENDING, isCalPending)
                            .putLong(KEY_WARMUP_START_TIME, warmupStartTimeMs)
                            .apply();
                } else {
                    isInWarmup = false;
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(KEY_IS_IN_WARMUP, false)
                            .putBoolean(KEY_IS_CAL_REQUIRED, isCalRequired)
                            .putBoolean(KEY_IS_CAL_PENDING, isCalPending)
                            .apply();
                }

                StringBuilder sbStatus = new StringBuilder();
                if (configNeeded) sbStatus.append("Sensor Config Required ");
                if (inWarmup) sbStatus.append("Warm-up ");
                if (calRequired) sbStatus.append("Calibration Required ");
                if (calPending) sbStatus.append("Processing Calibration... ");
                if (sensorError) sbStatus.append("Sensor Error ");
                if (sbStatus.length() == 0) sbStatus.append("Normal");
                final String statusDesc = sbStatus.toString().trim();
                lastStatusDescription = statusDesc;

                String trendStr = Double.isNaN(trendRate) ? "--" : String.format(Locale.US, "%+.2f", trendRate);
                log(String.format(Locale.US, "Parsed: SigTypes=[%d,%d], Raw=%d, Status=0x%04X (%s), Trend=%s",
                        sigType1, sigType2, rawVal34, statusBits, statusDesc, trendStr));

                if (configNeeded && bluetoothGatt != null) {
                    log("Detected SENSOR_CONFIGURATION_NEEDED status! Sending Opcode 0x05 automatically...");
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (bluetoothGatt != null) {
                                sendSensorConfiguration(true);
                            }
                        }
                    }, 500L);
                }

                float glucoseMmol = 0.0f;
                float glucoseMgDl = 0.0f;

                if (!inWarmup && !calRequired && !isCalPending && rawVal34 >= 20 && rawVal34 <= 600) {
                    glucoseMgDl = (float) rawVal34;
                    glucoseMmol = glucoseMgDl / 18.0182f;
                    if (isCalPending) {
                        isCalPending = false;
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(KEY_IS_CAL_PENDING, false)
                                .putLong(KEY_CAL_TIME, System.currentTimeMillis())
                                .apply();
                    }
                } else if (rawVal34 == 65535) {
                    log("Transmitter processing calibration / stabilizing signal (rawVal=65535)...");
                }

                if (glucoseMmol > 0.0f) {
                    lastGlucoseMmol = glucoseMmol;
                    lastGlucoseMgDl = glucoseMgDl;

                    // Single source of truth for persisting CGM points
                    GlucoseHistoryStorage.getInstance(this).addPoint(lastMeasurementTimeMs, glucoseMmol, (float) rawVal34, false);

                    broadcastToXdripIfEnabled(glucoseMgDl, rawVal34, trendRate, lastMeasurementTimeMs);

                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putFloat(KEY_LAST_MMOL, glucoseMmol)
                            .putFloat(KEY_LAST_MGDL, glucoseMgDl)
                            .putInt(KEY_LAST_RAW, rawVal34)
                            .putString(KEY_LAST_STATUS_DESC, statusDesc)
                            .putFloat(KEY_LAST_TREND, (float) trendRate)
                            .apply();

                    updateStatus("Updated at " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                } else {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putInt(KEY_LAST_RAW, rawVal34)
                            .putString(KEY_LAST_STATUS_DESC, statusDesc)
                            .apply();
                    updateStatus(statusDesc);
                }

                checkAndTriggerAlarms(glucoseMmol, calRequired);

                if (listener != null) {
                    final float fMmol = glucoseMmol;
                    final float fMgDl = glucoseMgDl;
                    final int fRaw = rawVal34;
                    final double fTrend = trendRate;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.onGlucoseReceived(fMmol, fMgDl, fRaw, fTrend, statusDesc);
                            }
                        }
                    });
                }

                updateNotification();
            }
        } catch (Exception e) {
            log("Decryption error: " + e.getMessage());
            Log.e(TAG, "Decryption failure", e);
        }
    }

    private void broadcastToXdripIfEnabled(float mgDl, int rawVal, double trendRate, long timestampMs) {
        boolean enabled = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_XDRIP_BROADCAST, false);
        if (!enabled) return;

        Intent intent = new Intent("com.eveningoutpost.dexdrip.BgEstimate");
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", (double) mgDl);
        intent.putExtra("com.eveningoutpost.dexdrip.Extras.Raw", (double) rawVal);
        intent.putExtra("com.eveningoutpost.dexdrip.Extras.Time", timestampMs);
        intent.putExtra("com.eveningoutpost.dexdrip.Extras.SourceDesc", "Medtronic Guardian Connect");
        if (batteryLevelPercent >= 0) {
            intent.putExtra("com.eveningoutpost.dexdrip.Extras.Battery", batteryLevelPercent);
        }
        if (!Double.isNaN(trendRate)) {
            intent.putExtra("com.eveningoutpost.dexdrip.Extras.Slope", trendRate / 60000.0d);
        }
        sendBroadcast(intent);
        log("Broadcasted BgEstimate to xDrip+/AAPS: " + Math.round(mgDl) + " mg/dL");
    }

    private void handleCalStatusData(byte[] raw) {
        if (raw == null || raw.length < 10) {
            log("CAL_STATUS_DATA: payload too short (" + (raw != null ? raw.length : 0) + "B)");
            return;
        }
        if (currentCalStatusIv == null) {
            log("CAL_STATUS_DATA: IV from CAL_STATUS_READY is null");
            return;
        }

        try {
            byte[] encryptedPayload = Arrays.copyOfRange(raw, 10, raw.length);
            byte[] decrypted = GuardianCrypto.decryptAesCtr(encryptedPayload, appKey, currentCalStatusIv);
            log("Decrypted CAL_STATUS_DATA (" + decrypted.length + "B): " + GuardianCrypto.bytesToHex(decrypted));

            if (decrypted.length >= 2) {
                int acceptedBg = 0;
                if (decrypted.length >= 4) {
                    ByteBuffer bBuf = ByteBuffer.wrap(decrypted, 2, 2).order(ByteOrder.LITTLE_ENDIAN);
                    acceptedBg = bBuf.getShort() & 0xFFFF;
                }

                int statusCode = (decrypted.length >= 5) ? (decrypted[4] & 0xFF) : (decrypted[1] & 0xFF);
                String calStatusDesc;
                switch (statusCode) {
                    case 0:
                        isCalPending = false;
                        isCalRequired = false;
                        long calSuccessTime = System.currentTimeMillis();
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(KEY_IS_CAL_PENDING, false)
                                .putBoolean(KEY_IS_CAL_REQUIRED, false)
                                .putLong(KEY_CAL_TIME, calSuccessTime)
                                .apply();
                        calStatusDesc = String.format(Locale.US, "✓ Calibration (%d mg/dL, %.1f mmol/L) ACCEPTED! Processing takes 5-15 min...",
                                acceptedBg, acceptedBg / 18.0182f);
                        break;
                    case 1:
                        isCalPending = false;
                        isCalRequired = true;
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(KEY_IS_CAL_PENDING, false)
                                .putBoolean(KEY_IS_CAL_REQUIRED, true)
                                .apply();
                        calStatusDesc = String.format(Locale.US, "❌ Calibration (%d mg/dL) REJECTED by transmitter", acceptedBg);
                        break;
                    case 2:
                        isCalPending = false;
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(KEY_IS_CAL_PENDING, false)
                                .apply();
                        calStatusDesc = "❌ Sensor error: Transmitter requests sensor replacement (CHANGE_SENSOR)";
                        break;
                    case 3:
                        isCalPending = false;
                        isCalRequired = true;
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(KEY_IS_CAL_PENDING, false)
                                .putBoolean(KEY_IS_CAL_REQUIRED, true)
                                .apply();
                        calStatusDesc = "⏳ Calibration was not calculated (NOT_CALCULATED)";
                        break;
                    default:
                        calStatusDesc = String.format(Locale.US, "Calibration Status: 0x%02X (BG=%d)", statusCode, acceptedBg);
                        break;
                }
                log(">>> " + calStatusDesc);
                updateStatus(calStatusDesc);
                updateNotification();
            }
        } catch (Exception e) {
            log("Error decrypting CAL_STATUS_DATA: " + e.getMessage());
        }
    }

    private void handleBackfillData(byte[] raw) {
        if (raw == null || raw.length <= 10) {
            log("BACKFILL_DATA is empty or too short (<=10B)");
            return;
        }
        byte[] backfillIv = (currentBackfillIv != null) ? currentBackfillIv : currentMeasurementIv;
        if (backfillIv == null) {
            log("BACKFILL_DATA received but Backfill IV is null, cannot decrypt payload");
            return;
        }

        try {
            ByteBuffer metaBuf = ByteBuffer.wrap(raw, 0, 10).order(ByteOrder.LITTLE_ENDIAN);
            int hdr = metaBuf.getShort(0) & 0xFFFF;
            long counter = metaBuf.getInt(2) & 0xFFFFFFFFL;
            long tsSeconds = metaBuf.getInt(6) & 0xFFFFFFFFL;
            log(String.format(Locale.US, "BACKFILL Header: Cnt=%d, Time=%ds, Hdr=0x%04X, TotalLen=%dB",
                    counter, tsSeconds, hdr, raw.length));

            byte[] encryptedPayload = Arrays.copyOfRange(raw, 10, raw.length);
            byte[] decrypted = GuardianCrypto.decryptAesCtr(encryptedPayload, appKey, backfillIv);

            if (currentBackfillReady != null && currentBackfillReady.length >= 4) {
                int expectedCrc = (currentBackfillReady[2] & 0xFF) | ((currentBackfillReady[3] & 0xFF) << 8);
                int calcCrc = GuardianCrypto.calcCrc16(decrypted);
                if (calcCrc != expectedCrc) {
                    byte[] decMaster = GuardianCrypto.decryptAesCtr(encryptedPayload, GuardianCrypto.MASTER_KEY, backfillIv);
                    if (GuardianCrypto.calcCrc16(decMaster) == expectedCrc) {
                        decrypted = decMaster;
                    } else {
                        log(String.format(Locale.US, "BACKFILL CRC mismatch (0x%04X != 0x%04X), skipping packet.", calcCrc, expectedCrc));
                        return;
                    }
                }
            }

            log(String.format(Locale.US, "Decrypted BACKFILL (%dB): %s",
                    decrypted.length, GuardianCrypto.bytesToHex(decrypted)));

            int recordCount = decrypted.length / 9;
            if (recordCount <= 0) return;

            // Calculate anchor timestamp for the backfill batch
            long nowMs = System.currentTimeMillis();
            long batchAnchorMs = nowMs;
            if (tsSeconds > 1_500_000_000L) {
                batchAnchorMs = tsSeconds * 1000L;
            } else if (lastTransmitterTimestampSec > 0 && lastTransmitterSyncLocalMs > 0 && tsSeconds > 0) {
                long deltaSec = tsSeconds - lastTransmitterTimestampSec;
                batchAnchorMs = lastTransmitterSyncLocalMs + (deltaSec * 1000L);
            }

            int idx = 0;
            int offset = 0;
            while (offset + 9 <= decrypted.length) {
                ByteBuffer buf = ByteBuffer.wrap(decrypted, offset, 9).order(ByteOrder.LITTLE_ENDIAN);
                int rawVal = buf.getShort(3) & 0xFFFF;
                short trendRaw = buf.getShort(7);
                double trendRate = (trendRaw == 0x7FFF || Math.abs(trendRaw) > 1000) ? Double.NaN : (trendRaw / 100.0d);

                // Space out historical points by 5 minutes (300,000 ms) ending at batchAnchorMs
                long pointTimeMs = batchAnchorMs - ((long) (recordCount - 1 - idx) * 300_000L);

                if (rawVal >= 20 && rawVal <= 600) {
                    float mgDl = (float) rawVal;
                    float mmol = mgDl / 18.0182f;

                    GlucoseHistoryStorage.getInstance(this).addPoint(pointTimeMs, mmol, (float) rawVal, false);

                    // Update live UI only on the most recent point of the batch if newer than last measurement
                    if (idx == recordCount - 1 && pointTimeMs >= lastMeasurementTimeMs) {
                        final float fMmol = mmol;
                        final float fMgDl = mgDl;
                        final int fRaw = rawVal;
                        final double fTrend = trendRate;
                        if (listener != null) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (listener != null) {
                                        listener.onGlucoseReceived(fMmol, fMgDl, fRaw, fTrend, "Backfill History");
                                    }
                                }
                            });
                        }
                    }
                }
                idx++;
                offset += 9;
            }
        } catch (Exception e) {
            log("Error processing BACKFILL_DATA: " + e.getMessage());
        }
    }

    private final Runnable mtuTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (bluetoothGatt != null && !servicesDiscoveryTriggered) {
                servicesDiscoveryTriggered = true;
                log("MTU timeout fallback -> discoverServices()...");
                bluetoothGatt.discoverServices();
            }
        }
    };

    private void updateStatus(final String text) {
        log("STATUS: " + text);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onStatusUpdated(text);
                }
            }
        });
    }

    private File getLogFile() {
        return new File(getFilesDir(), "guardian_log.txt");
    }

    private void pruneLogFile(File logFile) {
        try {
            long len = logFile.length();
            long keepBytes = 500_000L;
            if (len <= keepBytes) return;
            File tempFile = new File(getFilesDir(), "guardian_log.tmp");
            try (RandomAccessFile raf = new RandomAccessFile(logFile, "r");
                 FileWriter fw = new FileWriter(tempFile)) {
                raf.seek(len - keepBytes);
                raf.readLine(); // skip first partial line
                String line;
                while ((line = raf.readLine()) != null) {
                    fw.write(line);
                    fw.write("\n");
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (logFile.delete()) {
                    tempFile.renameTo(logFile);
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void writeLogToFile(String line) {
        try {
            File logFile = getLogFile();
            if (logFile.exists() && logFile.length() > 1_500_000L) {
                pruneLogFile(logFile);
            }
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(line);
                fw.write("\n");
            }
        } catch (Exception ignored) {}
    }

    private void loadLogFromFile() {
        try {
            File logFile = getLogFile();
            if (!logFile.exists() || logFile.length() == 0) return;
            long len = logFile.length();
            long readBytes = Math.min(len, 350_000L);
            try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                if (len > readBytes) {
                    raf.seek(len - readBytes);
                    raf.readLine();
                }
                byte[] b = new byte[(int) (len - raf.getFilePointer())];
                raf.readFully(b);
                String loaded = new String(b, StandardCharsets.UTF_8).trim();
                if (!loaded.isEmpty()) {
                    synchronized (logBuffer) {
                        logBuffer.setLength(0);
                        logBuffer.append(loaded);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void log(final String msg) {
        Log.i(TAG, msg);
        String time = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(new Date());
        final String formattedLine = "[" + time + "] " + msg;
        synchronized (logBuffer) {
            if (logBuffer.length() > 0) logBuffer.append("\n");
            logBuffer.append(formattedLine);
            if (logBuffer.length() > 500_000) {
                logBuffer.delete(0, 150_000);
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                writeLogToFile(formattedLine);
            }
        }).start();

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onLogAppended(msg);
                }
            }
        });
    }

    public String getLogHistory() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }

    private void notifyConnectionState(final boolean connected) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onConnectionStateChanged(connected);
                }
            }
        });
    }

    public boolean isDeviceConnected() {
        return isGattConnected && bluetoothGatt != null;
    }

    public boolean isDeviceConnecting() {
        return isConnecting;
    }

    public int getBatteryLevel() {
        return batteryLevelPercent;
    }

    public float getLastGlucoseMmol() {
        return lastGlucoseMmol;
    }

    public float getLastGlucoseMgDl() {
        return lastGlucoseMgDl;
    }

    public int getLastRawVal() {
        return lastRawVal;
    }

    public int getLastStatusBits() {
        return lastStatusBits;
    }

    public double getLastTrendRate() {
        return lastTrendRate;
    }

    public String getLastStatusDescription() {
        return lastStatusDescription;
    }

    public long getSensorStartTimeMs() {
        return sensorStartTimeMs;
    }

    public boolean isInWarmup() {
        return isInWarmup;
    }

    public boolean isCalRequired() {
        return isCalRequired;
    }

    public boolean isCalPending() {
        return isCalPending;
    }

    public long getWarmupStartTimeMs() {
        return warmupStartTimeMs;
    }

    public long getWarmupRemainingMs() {
        if (!isInWarmup || warmupStartTimeMs <= 0) return 0;
        long elapsed = System.currentTimeMillis() - warmupStartTimeMs;
        return Math.max(0, 7_200_000L - elapsed);
    }

    public long getLastMeasurementTimeMs() {
        return lastMeasurementTimeMs;
    }

    public void snoozeAlarms(int minutes) {
        stopAlarmSound();
        long snoozeUntil = System.currentTimeMillis() + (minutes * 60_000L);
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_SNOOZE_UNTIL_MS, snoozeUntil)
                .apply();
        lastAlarmTriggerTimeMs = 0;
        lastAlarmType = 0;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ALARM_ID);
        }
        log("Alarms snoozed for " + minutes + " minutes (until " +
                new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(snoozeUntil)) + ")");
    }

    private void checkAndTriggerAlarms(float mmol, boolean calRequired) {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean alarmsEnabled = sp.getBoolean(KEY_ALARMS_ENABLED, true);
        if (!alarmsEnabled) return;

        long now = System.currentTimeMillis();
        long snoozeUntil = sp.getLong(KEY_SNOOZE_UNTIL_MS, 0L);
        if (now < snoozeUntil) {
            return;
        }

        int alarmType = 0;
        String alarmTitle = null;
        String alarmText = null;

        if (mmol > 0f && mmol < 3.9f) {
            alarmType = 1;
            boolean useMgDl = sp.getBoolean(KEY_USE_MGDL, false);
            if (useMgDl) {
                alarmTitle = String.format(Locale.US, "🚨 URGENT LOW: %d mg/dL", Math.round(mmol * 18.0182f));
                alarmText = String.format(Locale.US, "Glucose is below 70 mg/dL (%.1f mmol/L). Treat immediately with fast carbs!", mmol);
            } else {
                alarmTitle = String.format(Locale.US, "🚨 URGENT LOW: %.1f mmol/L", mmol);
                alarmText = String.format(Locale.US, "Glucose is below 3.9 mmol/L (%d mg/dL). Treat immediately with fast carbs!", Math.round(mmol * 18.0182f));
            }
        } else if (mmol > 10.0f) {
            alarmType = 2;
            boolean useMgDl = sp.getBoolean(KEY_USE_MGDL, false);
            if (useMgDl) {
                alarmTitle = String.format(Locale.US, "⚠️ HIGH GLUCOSE: %d mg/dL", Math.round(mmol * 18.0182f));
                alarmText = String.format(Locale.US, "Glucose is above 180 mg/dL (%.1f mmol/L). Check ketones and insulin.", mmol);
            } else {
                alarmTitle = String.format(Locale.US, "⚠️ HIGH GLUCOSE: %.1f mmol/L", mmol);
                alarmText = String.format(Locale.US, "Glucose is above 10.0 mmol/L (%d mg/dL). Check ketones and insulin.", Math.round(mmol * 18.0182f));
            }
        } else if (calRequired && !isCalPending && sp.getBoolean(KEY_ALARM_CAL_ENABLED, true)) {
            alarmType = 3;
            alarmTitle = "🩸 Sensor Calibration Required";
            alarmText = "Transmitter requires fingerstick BG calibration from meter to continue monitoring.";
        }

        if (alarmType == 0) {
            if (lastAlarmType != 0) {
                cancelAlarm();
            }
            return;
        }

        // Rate limit: trigger not more often than once per 15 minutes if type is the same
        if (alarmType == lastAlarmType && (now - lastAlarmTriggerTimeMs) < 15 * 60_000L) {
            return;
        }

        lastAlarmType = alarmType;
        lastAlarmTriggerTimeMs = now;
        log("ALARM TRIGGERED! Type=" + alarmType + " (" + alarmTitle + ")");

        triggerAlarmNotification(alarmTitle, alarmText);
    }

    private void checkMissedReadings() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean alarmsEnabled = sp.getBoolean(KEY_ALARMS_ENABLED, true);
        boolean missedEnabled = sp.getBoolean(KEY_ALARM_MISSED_READINGS_ENABLED, true);
        if (!alarmsEnabled || !missedEnabled) {
            if (lastAlarmType == 4) {
                cancelAlarm();
            }
            return;
        }

        if (!isPaired || isInWarmup || userInitiatedDisconnect) {
            if (lastAlarmType == 4) {
                cancelAlarm();
            }
            return;
        }

        long now = System.currentTimeMillis();
        long snoozeUntil = sp.getLong(KEY_SNOOZE_UNTIL_MS, 0L);
        if (now < snoozeUntil) {
            return;
        }

        if (lastMeasurementTimeMs > 0 && (now - lastMeasurementTimeMs) > 20 * 60_000L) {
            long missedMins = (now - lastMeasurementTimeMs) / 60_000L;
            String alarmTitle = "⚠️ Sensor Signal Lost";
            String alarmText = String.format(Locale.US, "No sensor data for %d min. Check transmitter and Bluetooth.", missedMins);

            if (lastAlarmType == 4 && (now - lastAlarmTriggerTimeMs) < 15 * 60_000L) {
                return;
            }

            lastAlarmType = 4;
            lastAlarmTriggerTimeMs = now;
            log("ALARM TRIGGERED! Type=4 (Missed readings for " + missedMins + " min)");
            triggerAlarmNotification(alarmTitle, alarmText);
        } else if (lastAlarmType == 4) {
            cancelAlarm();
        }
    }

    private void cancelAlarm() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ALARM_ID);
        }
        lastAlarmType = 0;
    }

    public void stopAlarmSound() {
        mainHandler.removeCallbacks(stopRingtoneRunnable);
        try {
            if (activeRingtone != null && activeRingtone.isPlaying()) {
                activeRingtone.stop();
            }
        } catch (Exception ignored) {}
        activeRingtone = null;
    }

    private void triggerAlarmNotification(String title, String text) {
        stopAlarmSound();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent deleteIntent = new Intent(this, GuardianBleService.class).setAction(ACTION_DISMISS_ALARM);
        PendingIntent deletePi = PendingIntent.getService(
                this, 2, deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent snoozeIntent = new Intent(this, GuardianBleService.class).setAction(ACTION_SNOOZE_ALARM);
        PendingIntent snoozePi = PendingIntent.getService(
                this, 3, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, ALARM_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MAX);
            builder.setVibrate(new long[]{0, 500, 200, 500, 200, 500});
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            builder.setSound(alarmUri);
        }

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pi)
                .setDeleteIntent(deletePi)
                .setAutoCancel(true)
                .setOngoing(false);

        builder.addAction(0, "🔕 Snooze 30m", snoozePi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_ALARM);
        }

        nm.notify(NOTIFICATION_ALARM_ID, builder.build());

        // Haptic feedback via Vibrator
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500}, -1));
                } else {
                    v.vibrate(new long[]{0, 500, 200, 500}, -1);
                }
            }
        } catch (Exception ignored) {}

        // Audio feedback via Ringtone (auto-stopped after 10s or upon dismiss/snooze/open)
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            activeRingtone = RingtoneManager.getRingtone(getApplicationContext(), alarmUri);
            if (activeRingtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activeRingtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                activeRingtone.play();
                mainHandler.postDelayed(stopRingtoneRunnable, 10_000L);
            }
        } catch (Exception ignored) {}
    }
}
