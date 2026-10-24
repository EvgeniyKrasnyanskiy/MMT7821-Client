package com.openmedtronic.guardian;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MMT7821Client";
    private static final String PREF_NAME = "guardian_prefs";
    private static final String KEY_APP_KEY = "app_key";
    private static final String KEY_IS_PAIRED = "is_paired";
    private static final String KEY_CAL_RATIO = "cal_ratio";
    private static final String KEY_CAL_TIME = "cal_time";
    private static final String KEY_LAST_MMOL = "last_mmol";
    private static final String KEY_LAST_MGDL = "last_mgdl";
    private static final String KEY_BATTERY_LEVEL = "battery_level";
    private static final String KEY_LAST_MEASUREMENT_TIME = "last_measurement_time";
    private static final String KEY_MAC = "target_mac";
    private static final String KEY_SHOW_LOGS = "show_logs";
    private static final String KEY_SENSOR_START_TIME = "sensor_start_time";
    private static final String KEY_LAST_TREND = "last_trend";
    private static final String KEY_LAST_RAW = "last_raw";
    private static final String KEY_LAST_STATUS_DESC = "last_status_desc";
    private static final String KEY_IS_IN_WARMUP = "is_in_warmup";
    private static final String KEY_IS_CAL_REQUIRED = "is_cal_required";
    private static final String KEY_WARMUP_START_TIME = "warmup_start_time";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_XDRIP_BROADCAST = "xdrip_broadcast";
    private static final String KEY_USE_MGDL = "use_mgdl";
    private static final String KEY_ALARMS_ENABLED = "alarms_enabled";
    private static final String KEY_ALARM_CAL_ENABLED = "alarm_cal_enabled";
    private static final String KEY_ALARM_MISSED_READINGS_ENABLED = "alarm_missed_readings_enabled";
    private static final String KEY_SNOOZE_UNTIL_MS = "snooze_until_ms";
    private static final String KEY_IS_CAL_PENDING = "is_cal_pending";

    private static final int REQ_PERMISSIONS = 101;
    private static final int REQ_EXPORT_CSV = 2001;
    private String pendingCsvExportContent = null;

    // UI elements
    private TextView textTransmitterName;
    private TextView textCalCountdown;
    private Button btnSettings;
    private TextView textStatus;
    private TextView textBattery;
    private TextView textSensorDays;
    private TextView textCountdown;
    private TextView textGlucoseMmolValue;
    private TextView textTrendArrow;
    private TextView textGlucoseMmolUnit;
    private TextView textGlucoseMgDl;
    private TextView textSensorDetails;
    private Button btnQuickCalibrate;
    private Button btnQuickTherapy;
    private GlucoseChartView glucoseChart;
    private LinearLayout logsContainer;
    private View headerLogs;
    private TextView btnToggleLogs;
    private TextView textLogs;
    private ScrollView scrollLogs;

    // UI state
    private BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String targetMac = "";
    private boolean showLogs = false;
    private boolean isLogsExpanded = true;
    private boolean useMgDl = false;
    private long sensorStartTimeMs = 0;
    private boolean isInWarmup = false;
    private boolean isCalRequired = false;
    private boolean isCalPending = false;
    private float currentMmol = 0.0f;
    private boolean isStaleData = false;
    private int lastRawIsig = 0;
    private double lastTrendRate = Double.NaN;
    private long lastMeasurementTimeMs = 0;
    private ValueAnimator glucoseAnimator = null;
    private ScanCallback bleScanCallback = null;

    public static String getTrendArrow(double trendRate) {
        if (Double.isNaN(trendRate)) return "→";
        if (trendRate > 0.15) return "⇈";
        if (trendRate >= 0.08) return "↑";
        if (trendRate >= 0.03) return "↗";
        if (trendRate >= -0.03) return "→";
        if (trendRate >= -0.08) return "↘";
        if (trendRate >= -0.15) return "↓";
        return "⇊";
    }

    private void applyGlucoseColor(float mmol) {
        if (textGlucoseMmolValue == null) return;
        if (isStaleData) {
            textGlucoseMmolValue.setTextColor(Color.parseColor("#888888"));
            if (textTrendArrow != null) textTrendArrow.setTextColor(Color.parseColor("#888888"));
            return;
        }
        int color;
        if (mmol < 3.9f) {
            color = Color.parseColor("#FF5252");
        } else if (mmol > 10.0f) {
            color = Color.parseColor("#FFAB00");
        } else {
            color = Color.parseColor("#00E676");
        }
        textGlucoseMmolValue.setTextColor(color);
        if (textTrendArrow != null) {
            textTrendArrow.setTextColor(color);
        }
    }

    private void updateStaleState(boolean stale) {
        if (textGlucoseMmolValue == null) return;
        if (stale != isStaleData) {
            isStaleData = stale;
            if (stale) {
                textGlucoseMmolValue.setTextColor(Color.parseColor("#888888"));
                if (textTrendArrow != null) {
                    textTrendArrow.setTextColor(Color.parseColor("#888888"));
                }
                textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            } else {
                textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                applyGlucoseColor(currentMmol);
            }
        }
    }

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            GuardianBleService service = GuardianBleService.getInstance();
            if (service != null && service.getLastMeasurementTimeMs() > 0) {
                lastMeasurementTimeMs = service.getLastMeasurementTimeMs();
            }
            updateCalCountdownDisplay();

            boolean inWarmup = (service != null) ? service.isInWarmup() : isInWarmup;
            boolean calPending = (service != null) ? service.isCalPending() : isCalPending;
            boolean calReq = (service != null) ? service.isCalRequired() : isCalRequired;
            long warmupStart = (service != null && service.getWarmupStartTimeMs() > 0)
                    ? service.getWarmupStartTimeMs()
                    : getSharedPreferences(PREF_NAME, MODE_PRIVATE).getLong(KEY_WARMUP_START_TIME, 0);
            long lastCalTime = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getLong(KEY_CAL_TIME, 0);

            boolean isConnected = (service != null && service.isDeviceConnected());

            if (!isConnected) {
                textCountdown.setText("Transmitter Disconnected");
                textCountdown.setTextColor(Color.parseColor("#888888"));
                if (btnQuickCalibrate != null) {
                    btnQuickCalibrate.setVisibility(View.GONE);
                }
                if (textGlucoseMgDl != null && !"Disconnected".equals(textGlucoseMgDl.getText().toString())) {
                    textGlucoseMgDl.setText("Disconnected");
                    textGlucoseMgDl.setTextColor(Color.parseColor("#888888"));
                }
                if (textSensorDetails != null && !"Transmitter disconnected".equals(textSensorDetails.getText().toString())) {
                    textSensorDetails.setText("Transmitter disconnected");
                    textSensorDetails.setTextColor(Color.parseColor("#888888"));
                }
                updateStaleState(false);
            } else if (calPending) {
                textCountdown.setText("⏳ Waiting for calibration acceptance...");
                textCountdown.setTextColor(Color.parseColor("#4FC3F7"));
                if (btnQuickCalibrate != null) {
                    btnQuickCalibrate.setVisibility(View.GONE);
                }
                updateStaleState(false);
            } else if (calReq) {
                textCountdown.setText(lastCalTime > 0 ? "🩸 Calibration Needed" : "Waiting for 1st Calibration");
                textCountdown.setTextColor(Color.parseColor("#FFD54F"));
                if (btnQuickCalibrate != null && btnQuickCalibrate.getVisibility() != View.VISIBLE) {
                    btnQuickCalibrate.setVisibility(View.VISIBLE);
                }
                updateStaleState(false);
            } else if (inWarmup && warmupStart > 0) {
                long elapsedWarmupMs = System.currentTimeMillis() - warmupStart;
                long remainingWarmupMs = Math.max(0L, 7_200_000L - elapsedWarmupMs);
                if (remainingWarmupMs > 0) {
                    long remTotalSec = remainingWarmupMs / 1000L;
                    long remHours = remTotalSec / 3600L;
                    long remMins = (remTotalSec % 3600L) / 60L;
                    long remSecs = remTotalSec % 60L;
                    if (remHours > 0) {
                        textCountdown.setText(String.format(Locale.US, "Warm-up: %02d:%02d:%02d", remHours, remMins, remSecs));
                    } else {
                        textCountdown.setText(String.format(Locale.US, "Warm-up: %02d:%02d", remMins, remSecs));
                    }
                    textCountdown.setTextColor(Color.parseColor("#4FC3F7"));
                } else {
                    if (calReq) {
                        textCountdown.setText(lastCalTime > 0 ? "🩸 Calibration Needed" : "Waiting for 1st Calibration");
                        textCountdown.setTextColor(Color.parseColor("#FFD54F"));
                        if (btnQuickCalibrate != null && btnQuickCalibrate.getVisibility() != View.VISIBLE) {
                            btnQuickCalibrate.setVisibility(View.VISIBLE);
                        }
                    } else {
                        textCountdown.setText("Warm-up complete, waiting for reading...");
                        textCountdown.setTextColor(Color.parseColor("#4FC3F7"));
                        if (btnQuickCalibrate != null) {
                            btnQuickCalibrate.setVisibility(View.GONE);
                        }
                    }
                }
                updateStaleState(false);
            } else if (lastMeasurementTimeMs > 0) {
                if (btnQuickCalibrate != null && !calReq) {
                    btnQuickCalibrate.setVisibility(View.GONE);
                }
                long elapsedMs = System.currentTimeMillis() - lastMeasurementTimeMs;
                long remainingSec = (300_000L - elapsedMs) / 1000L;
                if (remainingSec > 0) {
                    long mins = remainingSec / 60;
                    long secs = remainingSec % 60;
                    textCountdown.setText(String.format(Locale.US, "Next reading: %02d:%02d", mins, secs));
                } else {
                    textCountdown.setText("Reading in progress...");
                }
                textCountdown.setTextColor(Color.parseColor("#CCCCCC"));
                updateStaleState(elapsedMs > 11 * 60_000L);
            } else {
                if (btnQuickCalibrate != null && !calReq) {
                    btnQuickCalibrate.setVisibility(View.GONE);
                }
                textCountdown.setText("Next reading: --:--");
                textCountdown.setTextColor(Color.parseColor("#CCCCCC"));
                updateStaleState(false);
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private void updateBatteryDisplay(final int percent) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                GuardianBleService service = GuardianBleService.getInstance();
                boolean isConnected = (service != null && service.isDeviceConnected());
                if (!isConnected || percent < 0) {
                    textBattery.setText("🔋 --%");
                    textBattery.setTextColor(Color.parseColor("#888888"));
                    return;
                }
                textBattery.setText(String.format(Locale.US, "🔋 %d%%", percent));
                if (percent <= 15) {
                    textBattery.setTextColor(Color.parseColor("#FF5252"));
                } else if (percent <= 25) {
                    textBattery.setTextColor(Color.parseColor("#FFAB00"));
                } else {
                    textBattery.setTextColor(Color.parseColor("#76FF03"));
                }
            }
        });
    }

    private void updateSensorDaysDisplay() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (textSensorDays == null) return;
                GuardianBleService service = GuardianBleService.getInstance();
                boolean isConnected = (service != null && service.isDeviceConnected());
                if (!isConnected) {
                    textSensorDays.setText("⏳ --d");
                    textSensorDays.setTextColor(Color.parseColor("#888888"));
                    return;
                }
                if (service != null && service.getSensorStartTimeMs() > 0) {
                    sensorStartTimeMs = service.getSensorStartTimeMs();
                }
                if (sensorStartTimeMs <= 0) {
                    textSensorDays.setText("⏳ --d");
                    textSensorDays.setTextColor(Color.parseColor("#888888"));
                    return;
                }

                if (sensorStartTimeMs > System.currentTimeMillis()) {
                    sensorStartTimeMs = System.currentTimeMillis();
                }

                long elapsedMs = System.currentTimeMillis() - sensorStartTimeMs;
                long totalLifespanMs = 6L * 24L * 3600_000L;
                long remainingMs = Math.max(0, Math.min(totalLifespanMs, totalLifespanMs - elapsedMs));

                if (remainingMs > 0) {
                    long days = Math.min(6, remainingMs / (24L * 3600_000L));
                    long hours = (remainingMs % (24L * 3600_000L)) / (3600_000L);
                    long mins = (remainingMs % (3600_000L)) / (60_000L);

                    if (days > 0) {
                        textSensorDays.setText(String.format(Locale.US, "⏳ %dd", days));
                        textSensorDays.setTextColor(days <= 1 ? Color.parseColor("#FFAB00") : Color.parseColor("#81C784"));
                    } else if (hours > 0) {
                        textSensorDays.setText(String.format(Locale.US, "⏳ %dh", hours));
                        textSensorDays.setTextColor(Color.parseColor("#FFAB00"));
                    } else {
                        textSensorDays.setText(String.format(Locale.US, "⏳ %dm", Math.max(1, mins)));
                        textSensorDays.setTextColor(Color.parseColor("#FF5252"));
                    }
                } else {
                    textSensorDays.setText("⏳ Expired");
                    textSensorDays.setTextColor(Color.parseColor("#FF5252"));
                }
            }
        });
    }

    private String getCalibrationSummaryText() {
        long calTime = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getLong(KEY_CAL_TIME, 0);
        if (calTime > 0) {
            long diffMs = System.currentTimeMillis() - calTime;
            long hours = diffMs / (3600_000L);
            long mins = (diffMs % 3600_000L) / (60_000L);
            String timeStr = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(calTime));
            String agoStr = hours > 0 ? (hours + "h ago") : (mins + "m ago");
            return String.format(Locale.US, "Last calibration: %s (%s)", timeStr, agoStr);
        } else {
            return "Calibration: not calibrated yet";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        setContentView(R.layout.activity_main);

        btnSettings = findViewById(R.id.btnSettings);
        textTransmitterName = findViewById(R.id.textTransmitterName);
        textCalCountdown = findViewById(R.id.textCalCountdown);
        if (textCalCountdown != null) {
            textCalCountdown.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCalibrationDialog();
                }
            });
        }
        textStatus = findViewById(R.id.textStatus);
        textBattery = findViewById(R.id.textBattery);
        textSensorDays = findViewById(R.id.textSensorDays);
        textCountdown = findViewById(R.id.textCountdown);
        textGlucoseMmolValue = findViewById(R.id.textGlucoseMmolValue);
        textTrendArrow = findViewById(R.id.textTrendArrow);
        textGlucoseMmolUnit = findViewById(R.id.textGlucoseMmolUnit);
        textGlucoseMgDl = findViewById(R.id.textGlucoseMgDl);
        textSensorDetails = findViewById(R.id.textSensorDetails);
        glucoseChart = findViewById(R.id.glucoseChart);
        logsContainer = findViewById(R.id.logsContainer);
        headerLogs = findViewById(R.id.headerLogs);
        btnToggleLogs = findViewById(R.id.btnToggleLogs);
        textLogs = findViewById(R.id.textLogs);
        scrollLogs = findViewById(R.id.scrollLogs);
        btnQuickCalibrate = findViewById(R.id.btnQuickCalibrate);
        btnQuickTherapy = findViewById(R.id.btnQuickTherapy);

        View.OnClickListener toggleLogsListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLogsExpanded();
            }
        };
        if (headerLogs != null) headerLogs.setOnClickListener(toggleLogsListener);
        if (btnToggleLogs != null) btnToggleLogs.setOnClickListener(toggleLogsListener);

        if (btnQuickCalibrate != null) {
            btnQuickCalibrate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCalibrationDialog();
                }
            });
        }

        if (btnQuickTherapy != null) {
            styleSettingsButton(btnQuickTherapy, Color.parseColor("#1E2A38"), false);
            btnQuickTherapy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTherapyDialog();
                }
            });
        }

        if (glucoseChart != null) {
            glucoseChart.setOnChartItemClickListener(new GlucoseChartView.OnChartItemClickListener() {
                @Override
                public void onTherapyClick(GlucoseHistoryStorage.TherapyEntry entry) {
                    showTherapyDetailsDialog(entry);
                }

                @Override
                public void onPointClick(GlucoseChartView.Point point) {
                    showPointDetailsDialog(point);
                }
            });
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        loadSavedState();
        loadChartPoints();
        mainHandler.post(countdownRunnable);

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });

        if (checkPermissions()) {
            startBleService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            startBleService();
        }
    }

    private void startBleService() {
        Intent serviceIntent = new Intent(this, GuardianBleService.class);
        serviceIntent.setAction(GuardianBleService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private final GuardianBleService.ServiceListener bleServiceListener = new GuardianBleService.ServiceListener() {
        @Override
        public void onStatusUpdated(final String status) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateStatus(status);
                }
            });
        }

        @Override
        public void onGlucoseReceived(final float mmol, final float mgDl, final int rawVal, final double trend, final String statusDesc) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    GuardianBleService s = GuardianBleService.getInstance();
                    if (s == null || !s.isDeviceConnected()) {
                        displayDisconnected();
                        return;
                    }
                    displayGlucose(mmol, mgDl, rawVal, trend, statusDesc);
                }
            });
        }

        @Override
        public void onBatteryReceived(final int percent) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateBatteryDisplay(percent);
                }
            });
        }

        @Override
        public void onLogAppended(final String logMsg) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (textLogs != null) {
                        if (textLogs.getText().length() == 0) {
                            GuardianBleService service = GuardianBleService.getInstance();
                            if (service != null) {
                                textLogs.setText(service.getLogHistory());
                            }
                        } else {
                            textLogs.append("\n" + logMsg);
                        }
                        if (isLogsExpanded && scrollLogs != null) {
                            scrollLogs.post(new Runnable() {
                                @Override
                                public void run() {
                                    scrollLogs.fullScroll(ScrollView.FOCUS_DOWN);
                                }
                            });
                        }
                    }
                }
            });
        }

        @Override
        public void onConnectionStateChanged(final boolean connected) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateStatus(connected ? "Connected to transmitter" : "Disconnected");
                    if (connected) {
                        updateTransmitterNameDisplay();
                    } else {
                        updateBatteryDisplay(-1);
                        updateSensorDaysDisplay();
                        updateCalCountdownDisplay();
                        displayDisconnected();
                    }
                }
            });
        }

        @Override
        public void onHandshakeStateChanged(GuardianBleService.HandshakeState state) {
        }

        @Override
        public void onSensorDaysUpdated(final int daysRemaining) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateSensorDaysDisplay();
                }
            });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        useMgDl = sp.getBoolean(KEY_USE_MGDL, false);
        if (glucoseChart != null) {
            glucoseChart.setUseMgDl(useMgDl);
        }
        loadChartPoints();
        updateSensorDaysDisplay();
        updateTransmitterNameDisplay();
        updateCalCountdownDisplay();
        bindServiceIfAvailable();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                bindServiceIfAvailable();
            }
        }, 500L);
    }

    private void bindServiceIfAvailable() {
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.setListener(bleServiceListener);
            if (textLogs != null) {
                textLogs.setText(service.getLogHistory());
                if (isLogsExpanded && scrollLogs != null) {
                    scrollLogs.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollLogs.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                }
            }
            boolean isConnected = service.isDeviceConnected();
            updateBatteryDisplay(isConnected ? service.getBatteryLevel() : -1);
            updateSensorDaysDisplay();
            updateCalCountdownDisplay();

            if (!isConnected) {
                displayDisconnected();
            } else if (service.isInWarmup()) {
                displayWarmup(service.getLastRawVal(), service.getLastStatusDescription());
            } else if (service.isCalPending()) {
                displayCalibrationPending(service.getLastStatusDescription());
            } else if (service.isCalRequired()) {
                displayCalibrationRequired(service.getLastRawVal(), service.getLastStatusDescription());
            } else if (service.getLastGlucoseMmol() > 0.0f) {
                displayGlucose(service.getLastGlucoseMmol(), service.getLastGlucoseMgDl(),
                        service.getLastRawVal(), service.getLastTrendRate(), service.getLastStatusDescription());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.clearListener();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_CSV && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingCsvExportContent != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(pendingCsvExportContent.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Toast.makeText(this, "SiDiary CSV export successfully saved!", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error saving CSV: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void loadSavedState() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedKeyHex = sp.getString(KEY_APP_KEY, null);
        boolean isPaired = sp.getBoolean(KEY_IS_PAIRED, false);
        sp.edit().remove(KEY_CAL_RATIO).apply();

        float lastMmol = sp.getFloat(KEY_LAST_MMOL, 0.0f);
        float lastMgDl = sp.getFloat(KEY_LAST_MGDL, 0.0f);
        lastMeasurementTimeMs = sp.getLong(KEY_LAST_MEASUREMENT_TIME, 0);
        targetMac = sp.getString(KEY_MAC, "");
        showLogs = sp.getBoolean(KEY_SHOW_LOGS, false);
        sensorStartTimeMs = sp.getLong(KEY_SENSOR_START_TIME, 0);
        useMgDl = sp.getBoolean(KEY_USE_MGDL, false);
        if (glucoseChart != null) {
            glucoseChart.setUseMgDl(useMgDl);
        }

        applyLogsVisibility(showLogs);
        int savedBat = sp.getInt(KEY_BATTERY_LEVEL, -1);
        updateBatteryDisplay(savedBat);
        updateSensorDaysDisplay();
        updateTransmitterNameDisplay();
        updateCalCountdownDisplay();

        if (savedKeyHex != null && savedKeyHex.length() == 32) {
            appendLog("Loaded AppKey: " + savedKeyHex + " (paired=" + isPaired + ")");
        } else {
            byte[] appKey = GuardianCrypto.generateRandom16();
            String newKeyHex = GuardianCrypto.bytesToHexNoSpace(appKey);
            sp.edit().putString(KEY_APP_KEY, newKeyHex).putBoolean(KEY_IS_PAIRED, false).apply();
            appendLog("Generated new AppKey: " + newKeyHex);
        }

        isInWarmup = sp.getBoolean(KEY_IS_IN_WARMUP, false);
        isCalRequired = sp.getBoolean(KEY_IS_CAL_REQUIRED, false);
        isCalPending = sp.getBoolean(KEY_IS_CAL_PENDING, false);
        String savedStatus = sp.getString(KEY_LAST_STATUS_DESC, "Normal");
        int savedRaw = sp.getInt(KEY_LAST_RAW, 0);

        GuardianBleService service = GuardianBleService.getInstance();
        boolean isConnected = (service != null && service.isDeviceConnected());

        if (!isConnected) {
            displayDisconnected();
        } else if (isInWarmup) {
            displayWarmup(savedRaw, savedStatus);
        } else if (isCalPending) {
            displayCalibrationPending(savedStatus);
        } else if (isCalRequired) {
            displayCalibrationRequired(savedRaw, savedStatus);
        } else if (lastMmol > 0.0f) {
            displaySavedGlucose(lastMmol, lastMgDl);
        } else {
            displayDisconnected();
        }
    }

    private void displaySavedGlucose(float mmol, float mgDl) {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int savedRaw = sp.getInt(KEY_LAST_RAW, 0);
        float savedTrend = sp.getFloat(KEY_LAST_TREND, Float.NaN);
        String savedStatus = sp.getString(KEY_LAST_STATUS_DESC, "Normal");
        String trendStr = Float.isNaN(savedTrend) ? "--" : String.format(Locale.US, "%+.2f", savedTrend);

        currentMmol = mmol;
        lastRawIsig = savedRaw;
        lastTrendRate = savedTrend;
        if (useMgDl) {
            textGlucoseMmolValue.setText(String.format(Locale.US, "%.0f", mgDl));
            textGlucoseMmolUnit.setText("mg/dL");
            textGlucoseMgDl.setText(String.format(Locale.US, "%.1f mmol/L", mmol));
        } else {
            textGlucoseMmolValue.setText(String.format(Locale.US, "%.1f", mmol));
            textGlucoseMmolUnit.setText("mmol/L");
            textGlucoseMgDl.setText(String.format(Locale.US, "%.0f mg/dL", mgDl));
        }
        textGlucoseMmolUnit.setVisibility(View.VISIBLE);
        if (textTrendArrow != null) {
            textTrendArrow.setText(getTrendArrow(savedTrend));
            textTrendArrow.setVisibility(View.VISIBLE);
        }
        long elapsedMs = (lastMeasurementTimeMs > 0) ? (System.currentTimeMillis() - lastMeasurementTimeMs) : 0;
        updateStaleState(elapsedMs > 11 * 60_000L);
        if (!isStaleData) {
            applyGlucoseColor(mmol);
        }
        textGlucoseMgDl.setTextColor(Color.parseColor("#CCCCCC"));
        textSensorDetails.setText(String.format(Locale.US, "Trend: %s | %s", trendStr, savedStatus));
    }

    private void toggleLogsExpanded() {
        isLogsExpanded = !isLogsExpanded;
        if (scrollLogs != null) {
            scrollLogs.setVisibility(isLogsExpanded ? View.VISIBLE : View.GONE);
        }
        if (btnToggleLogs != null) {
            btnToggleLogs.setText(isLogsExpanded ? "▼" : "▲");
        }
    }

    private void applyLogsVisibility(boolean visible) {
        showLogs = visible;
        if (logsContainer != null) logsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (scrollLogs != null) scrollLogs.setVisibility((visible && isLogsExpanded) ? View.VISIBLE : View.GONE);
        if (btnToggleLogs != null) btnToggleLogs.setText(isLogsExpanded ? "▼" : "▲");
    }

    private void updateTransmitterNameDisplay() {
        if (textTransmitterName == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                String devName = sp.getString(KEY_DEVICE_NAME, "");
                if (devName.isEmpty() && targetMac != null && !targetMac.isEmpty() && BluetoothAdapter.checkBluetoothAddress(targetMac)) {
                    if (bluetoothAdapter != null) {
                        try {
                            BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(targetMac);
                            if (dev != null && dev.getName() != null && !dev.getName().isEmpty()) {
                                devName = dev.getName();
                                sp.edit().putString(KEY_DEVICE_NAME, devName).apply();
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (devName.isEmpty()) {
                    devName = "Guardian Connect";
                }
                textTransmitterName.setText(devName);
            }
        });
    }

    private void updateCalCountdownDisplay() {
        if (textCalCountdown == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                GuardianBleService service = GuardianBleService.getInstance();
                boolean isConnected = (service != null && service.isDeviceConnected());
                if (!isConnected) {
                    textCalCountdown.setText("🩸 --");
                    textCalCountdown.setTextColor(Color.parseColor("#888888"));
                    return;
                }
                boolean inWarmup = service.isInWarmup();
                boolean calPending = service.isCalPending();
                boolean calReq = service.isCalRequired();

                if (inWarmup) {
                    textCalCountdown.setText("🩸 Warmup");
                    textCalCountdown.setTextColor(Color.parseColor("#888888"));
                    return;
                }

                if (calPending) {
                    textCalCountdown.setText("⏳ Processing");
                    textCalCountdown.setTextColor(Color.parseColor("#4FC3F7"));
                    return;
                }

                if (calReq) {
                    textCalCountdown.setText("🩸 Cal now");
                    textCalCountdown.setTextColor(Color.parseColor("#FF5252"));
                    return;
                }

                long calTime = sp.getLong(KEY_CAL_TIME, 0);
                if (calTime <= 0) {
                    textCalCountdown.setText("🩸 --");
                    textCalCountdown.setTextColor(Color.parseColor("#888888"));
                    return;
                }

                long now = System.currentTimeMillis();
                long elapsedMs = now - calTime;
                long maxCalIntervalMs = 12L * 3600_000L;
                long remainingMs = maxCalIntervalMs - elapsedMs;

                if (remainingMs > 0) {
                    long hours = remainingMs / 3600_000L;
                    long mins = (remainingMs % 3600_000L) / 60_000L;
                    if (hours > 0) {
                        textCalCountdown.setText(String.format(Locale.US, "🩸 %dh", hours));
                        textCalCountdown.setTextColor(hours <= 2 ? Color.parseColor("#FFAB00") : Color.parseColor("#81C784"));
                    } else {
                        textCalCountdown.setText(String.format(Locale.US, "🩸 %dm", Math.max(1, mins)));
                        textCalCountdown.setTextColor(Color.parseColor("#FF5252"));
                    }
                } else {
                    textCalCountdown.setText("🩸 12h+");
                    textCalCountdown.setTextColor(Color.parseColor("#FFAB00"));
                }
            }
        });
    }

    private void animateGlucoseTransition(final float startMmol, final float endMmol, final float endMgDl) {
        if (glucoseAnimator != null) {
            glucoseAnimator.cancel();
        }
        final float startMgDl = startMmol * 18.0182f;
        glucoseAnimator = ValueAnimator.ofFloat(startMmol, endMmol);
        glucoseAnimator.setDuration(1500L);
        glucoseAnimator.setInterpolator(new DecelerateInterpolator());
        glucoseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float val = (Float) animation.getAnimatedValue();
                float fraction = animation.getAnimatedFraction();
                float curMg = startMgDl + (endMgDl - startMgDl) * fraction;
                if (textGlucoseMmolValue != null) {
                    if (useMgDl) {
                        textGlucoseMmolValue.setText(String.format(Locale.US, "%.0f", curMg));
                    } else {
                        textGlucoseMmolValue.setText(String.format(Locale.US, "%.1f", val));
                    }
                }
                if (textGlucoseMgDl != null) {
                    if (useMgDl) {
                        textGlucoseMgDl.setText(String.format(Locale.US, "%.1f mmol/L", val));
                    } else {
                        textGlucoseMgDl.setText(String.format(Locale.US, "%.0f mg/dL", curMg));
                    }
                }
            }
        });
        glucoseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                currentMmol = endMmol;
                if (textGlucoseMmolValue != null) {
                    if (useMgDl) {
                        textGlucoseMmolValue.setText(String.format(Locale.US, "%.0f", endMgDl));
                        textGlucoseMmolUnit.setText("mg/dL");
                    } else {
                        textGlucoseMmolValue.setText(String.format(Locale.US, "%.1f", endMmol));
                        textGlucoseMmolUnit.setText("mmol/L");
                    }
                    applyGlucoseColor(endMmol);
                }
                if (textGlucoseMgDl != null) {
                    if (useMgDl) {
                        textGlucoseMgDl.setText(String.format(Locale.US, "%.1f mmol/L", endMmol));
                    } else {
                        textGlucoseMgDl.setText(String.format(Locale.US, "%.0f mg/dL", endMgDl));
                    }
                }
            }
        });
        glucoseAnimator.start();
    }

    private void updateGlucoseDisplayUnits() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        float lastMmol = sp.getFloat(KEY_LAST_MMOL, 0.0f);
        float lastMgDl = sp.getFloat(KEY_LAST_MGDL, 0.0f);
        if (lastMmol > 0.0f) {
            displaySavedGlucose(lastMmol, lastMgDl);
        } else {
            textGlucoseMmolValue.setText(useMgDl ? "---" : "--.-");
            textGlucoseMmolUnit.setText(useMgDl ? "mg/dL" : "mmol/L");
            textGlucoseMgDl.setText(useMgDl ? "--.- mmol/L" : "-- mg/dL");
        }
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ Settings & Transmitter");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView lblCalStatus = new TextView(this);
        lblCalStatus.setText(getCalibrationSummaryText());
        lblCalStatus.setTextColor(Color.parseColor("#81C784"));
        lblCalStatus.setTextSize(13);
        layout.addView(lblCalStatus);

        boolean inWarmup = isInWarmup || (GuardianBleService.getInstance() != null && GuardianBleService.getInstance().isInWarmup());

        Button btnCalibrateInSettings = new Button(this);
        btnCalibrateInSettings.setText("🩸 Calibrate (Early)");
        btnCalibrateInSettings.setTextColor(inWarmup ? Color.parseColor("#777777") : Color.WHITE);
        styleSettingsButton(btnCalibrateInSettings, Color.parseColor(inWarmup ? "#222222" : "#2A2A2A"), false);
        btnCalibrateInSettings.setEnabled(!inWarmup);
        LinearLayout.LayoutParams btnCalLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnCalLp.topMargin = dpToPx(8);
        btnCalibrateInSettings.setLayoutParams(btnCalLp);
        layout.addView(btnCalibrateInSettings);

        TextView lblMac = new TextView(this);
        final boolean isConn = (GuardianBleService.getInstance() != null && GuardianBleService.getInstance().isDeviceConnected());
        lblMac.setText(isConn ? "Transmitter MAC Address (Connected):" : "Transmitter MAC Address:");
        lblMac.setTextColor(isConn ? Color.parseColor("#81C784") : Color.WHITE);
        lblMac.setTextSize(13);
        LinearLayout.LayoutParams macLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        macLp.topMargin = dpToPx(10);
        lblMac.setLayoutParams(macLp);
        layout.addView(lblMac);

        final EditText editMacInput = new EditText(this);
        editMacInput.setText(targetMac);
        editMacInput.setHint("XX:XX:XX:XX:XX:XX");
        editMacInput.setHintTextColor(Color.parseColor("#777777"));
        editMacInput.setTextColor(isConn ? Color.GRAY : Color.WHITE);
        editMacInput.setEnabled(!isConn);
        editMacInput.setFocusable(!isConn);
        editMacInput.setBackgroundColor(Color.parseColor(isConn ? "#1A1A1A" : "#252525"));
        editMacInput.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        editMacInput.setTextSize(14);
        layout.addView(editMacInput);

        final Button btnScanBle = new Button(this);
        btnScanBle.setText("🔍 Scan for Transmitter (BLE)");
        styleSettingsButton(btnScanBle, Color.parseColor("#2A2A2A"), false);
        btnScanBle.setTextColor(Color.WHITE);
        btnScanBle.setEnabled(!isConn);
        if (isConn) btnScanBle.setVisibility(View.GONE);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = dpToPx(6);
        btnScanBle.setLayoutParams(scanLp);
        layout.addView(btnScanBle);

        final Button btnConnAction = new Button(this);
        btnConnAction.setText(isConn ? "Disconnect Transmitter" : "Connect to Transmitter");
        styleSettingsButton(btnConnAction, isConn ? Color.parseColor("#3D1E1E") : Color.parseColor("#1E3A5F"), false);
        btnConnAction.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dpToPx(6);
        btnConnAction.setLayoutParams(btnLp);
        layout.addView(btnConnAction);

        final SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        // Row 2: [📏 Unit] & [🔕 Snooze 30m]
        LinearLayout rowUnitSnooze = new LinearLayout(this);
        rowUnitSnooze.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowUnitSnoozeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowUnitSnoozeLp.topMargin = dpToPx(8);
        rowUnitSnooze.setLayoutParams(rowUnitSnoozeLp);

        final Button btnUnit = new Button(this);
        boolean useMgDlCurrent = sp.getBoolean(KEY_USE_MGDL, false);
        btnUnit.setText(useMgDlCurrent ? "📏 Unit: mg/dL" : "📏 Unit: mmol/L");
        styleSettingsButton(btnUnit, Color.parseColor("#2A2A2A"), false);
        btnUnit.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        unitLp.rightMargin = dpToPx(4);
        btnUnit.setLayoutParams(unitLp);
        btnUnit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nextVal = !sp.getBoolean(KEY_USE_MGDL, false);
                sp.edit().putBoolean(KEY_USE_MGDL, nextVal).apply();
                useMgDl = nextVal;
                btnUnit.setText(nextVal ? "📏 Unit: mg/dL" : "📏 Unit: mmol/L");
                glucoseChart.setUseMgDl(nextVal);
                updateGlucoseDisplayUnits();
                GlucoseWidgetProvider.updateAllWidgets(MainActivity.this);
                GuardianBleService service = GuardianBleService.getInstance();
                if (service != null) {
                    service.updateNotification();
                }
            }
        });
        rowUnitSnooze.addView(btnUnit);

        Button btnSnooze = new Button(this);
        btnSnooze.setText("🔕 Snooze 30m");
        styleSettingsButton(btnSnooze, Color.parseColor("#2A2A2A"), false);
        btnSnooze.setTextColor(Color.parseColor("#FFD54F"));
        LinearLayout.LayoutParams snoozeLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        snoozeLp.leftMargin = dpToPx(4);
        btnSnooze.setLayoutParams(snoozeLp);
        btnSnooze.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GuardianBleService service = GuardianBleService.getInstance();
                if (service != null) {
                    service.snoozeAlarms(30);
                } else {
                    long snoozeUntil = System.currentTimeMillis() + 30 * 60_000L;
                    sp.edit().putLong(KEY_SNOOZE_UNTIL_MS, snoozeUntil).apply();
                }
                Toast.makeText(MainActivity.this, "Alarms snoozed for 30 minutes", Toast.LENGTH_SHORT).show();
            }
        });
        rowUnitSnooze.addView(btnSnooze);
        layout.addView(rowUnitSnooze);

        // Row 3: [💾 Export SiDiary CSV]
        Button btnExportCsv = new Button(this);
        btnExportCsv.setText("💾 Export SiDiary CSV");
        styleSettingsButton(btnExportCsv, Color.parseColor("#2A2A2A"), false);
        btnExportCsv.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams exportCsvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exportCsvLp.topMargin = dpToPx(8);
        btnExportCsv.setLayoutParams(exportCsvLp);
        layout.addView(btnExportCsv);

        // Row 4: [ℹ️ Guide & Info] & [🗑️ Clear History]
        LinearLayout rowGuideClear = new LinearLayout(this);
        rowGuideClear.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowGuideClearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowGuideClearLp.topMargin = dpToPx(8);
        rowGuideClear.setLayoutParams(rowGuideClearLp);

        Button btnGuide = new Button(this);
        btnGuide.setText("ℹ️ Guide & Info");
        styleSettingsButton(btnGuide, Color.parseColor("#2A2A2A"), false);
        btnGuide.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        guideLp.rightMargin = dpToPx(4);
        btnGuide.setLayoutParams(guideLp);
        rowGuideClear.addView(btnGuide);

        Button btnClearChart = new Button(this);
        btnClearChart.setText("🗑️ Clear History");
        styleSettingsButton(btnClearChart, Color.parseColor("#2A2A2A"), true);
        btnClearChart.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams clearChartLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        clearChartLp.leftMargin = dpToPx(4);
        btnClearChart.setLayoutParams(clearChartLp);
        rowGuideClear.addView(btnClearChart);
        layout.addView(rowGuideClear);

        Button btnResetPair = new Button(this);
        btnResetPair.setText("⚠️ Reset & Re-pair Transmitter");
        styleSettingsButton(btnResetPair, Color.parseColor("#3D1E1E"), true);
        btnResetPair.setTextColor(Color.parseColor("#FF8A80"));
        LinearLayout.LayoutParams resetPairLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetPairLp.topMargin = dpToPx(10);
        btnResetPair.setLayoutParams(resetPairLp);
        layout.addView(btnResetPair);

        LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chkLp.topMargin = dpToPx(4);

        final CheckBox chkAlarms = new CheckBox(this);
        chkAlarms.setText("🔔 Sound Alarms (Hypo / Hyper)");
        chkAlarms.setTextColor(Color.WHITE);
        chkAlarms.setChecked(sp.getBoolean(KEY_ALARMS_ENABLED, true));
        chkAlarms.setLayoutParams(chkLp);
        chkAlarms.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(KEY_ALARMS_ENABLED, isChecked).apply();
            }
        });
        layout.addView(chkAlarms);

        final CheckBox chkCalAlarm = new CheckBox(this);
        chkCalAlarm.setText("🩸 Sound Alert on Calibration Request");
        chkCalAlarm.setTextColor(Color.WHITE);
        chkCalAlarm.setChecked(sp.getBoolean(KEY_ALARM_CAL_ENABLED, true));
        chkCalAlarm.setLayoutParams(chkLp);
        chkCalAlarm.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(KEY_ALARM_CAL_ENABLED, isChecked).apply();
            }
        });
        layout.addView(chkCalAlarm);

        final CheckBox chkMissedAlarm = new CheckBox(this);
        chkMissedAlarm.setText("⚠️ Alert on No Sensor Readings (> 20 min)");
        chkMissedAlarm.setTextColor(Color.WHITE);
        chkMissedAlarm.setChecked(sp.getBoolean(KEY_ALARM_MISSED_READINGS_ENABLED, true));
        chkMissedAlarm.setLayoutParams(chkLp);
        chkMissedAlarm.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(KEY_ALARM_MISSED_READINGS_ENABLED, isChecked).apply();
            }
        });
        layout.addView(chkMissedAlarm);

        final CheckBox chkXdrip = new CheckBox(this);
        chkXdrip.setText("📡 Broadcast to xDrip+ / AAPS");
        chkXdrip.setTextColor(Color.WHITE);
        boolean xdripEnabled = sp.getBoolean(KEY_XDRIP_BROADCAST, false);
        chkXdrip.setChecked(xdripEnabled);
        chkXdrip.setLayoutParams(chkLp);
        chkXdrip.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sp.edit().putBoolean(KEY_XDRIP_BROADCAST, isChecked).apply();
            }
        });
        layout.addView(chkXdrip);

        final CheckBox chkLogs = new CheckBox(this);
        chkLogs.setText("📋 Show BLE Protocol Logs");
        chkLogs.setTextColor(Color.WHITE);
        chkLogs.setChecked(showLogs);
        chkLogs.setLayoutParams(chkLp);
        layout.addView(chkLogs);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(layout);
        builder.setView(scrollView);
        builder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (!isConn) {
                    targetMac = editMacInput.getText().toString().trim().toUpperCase(Locale.US);
                    sp.edit().putString(KEY_MAC, targetMac).apply();
                }
                showLogs = chkLogs.isChecked();
                sp.edit()
                        .putBoolean(KEY_SHOW_LOGS, showLogs)
                        .putBoolean(KEY_XDRIP_BROADCAST, chkXdrip.isChecked())
                        .putBoolean(KEY_ALARMS_ENABLED, chkAlarms.isChecked())
                        .putBoolean(KEY_ALARM_CAL_ENABLED, chkCalAlarm.isChecked())
                        .apply();
                applyLogsVisibility(showLogs);
            }
        });

        final AlertDialog dialog = builder.create();

        btnScanBle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBleScanner(editMacInput, btnScanBle);
            }
        });

        btnCalibrateInSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showCalibrationDialog();
            }
        });

        btnConnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                targetMac = editMacInput.getText().toString().trim().toUpperCase(Locale.US);
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_MAC, targetMac).apply();
                boolean isConnected = (GuardianBleService.getInstance() != null && GuardianBleService.getInstance().isDeviceConnected());
                if (isConnected) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Disconnect")
                            .setMessage("Are you sure you want to disconnect from the transmitter? Glucose reception will be paused.")
                            .setPositiveButton("Disconnect", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    disconnect();
                                    btnConnAction.setText("Connect to Transmitter");
                                    styleSettingsButton(btnConnAction, Color.parseColor("#1E3A5F"), false);
                                    editMacInput.setEnabled(true);
                                    editMacInput.setTextColor(Color.WHITE);
                                    editMacInput.setBackgroundColor(Color.parseColor("#252525"));
                                    btnScanBle.setVisibility(View.VISIBLE);
                                    btnScanBle.setEnabled(true);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    checkPermissionsAndConnect();
                    btnConnAction.setText("Disconnect Transmitter");
                    styleSettingsButton(btnConnAction, Color.parseColor("#3D1E1E"), false);
                    editMacInput.setEnabled(false);
                    editMacInput.setTextColor(Color.GRAY);
                    editMacInput.setBackgroundColor(Color.parseColor("#1A1A1A"));
                    btnScanBle.setVisibility(View.GONE);
                }
            }
        });



        btnResetPair.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("⚠️ Confirm Re-pairing")
                        .setMessage("WARNING: This will reset the cryptographic pairing and generate a new AppKey.\n\n"
                                + "IMPORTANT: The Guardian Connect transmitter MUST be placed in its charger for at least 10 seconds (until green flash) and then removed before re-pairing.\n\n"
                                + "Are you sure you want to re-pair?")
                        .setPositiveButton("Yes, Reset Key", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                dialog.dismiss();
                                rePairTransmitter();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        btnExportCsv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(MainActivity.this);
                    pendingCsvExportContent = storage.generateSiDiaryCsv();

                    File localCopy = storage.saveSiDiaryExportFile();
                    appendLog("Export SiDiary CSV: " + localCopy.getAbsolutePath());

                    Intent createDocIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    createDocIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    createDocIntent.setType("text/csv");
                    createDocIntent.putExtra(Intent.EXTRA_TITLE, localCopy.getName());

                    try {
                        startActivityForResult(createDocIntent, REQ_EXPORT_CSV);
                    } catch (Exception e) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, localCopy.getName());
                        shareIntent.putExtra(Intent.EXTRA_TEXT, pendingCsvExportContent);
                        startActivity(Intent.createChooser(shareIntent, "Share SiDiary Export"));
                        Toast.makeText(MainActivity.this, "Saved: " + localCopy.getName(), Toast.LENGTH_LONG).show();
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "CSV export error", ex);
                    Toast.makeText(MainActivity.this, "Export error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnClearChart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Clear Chart History")
                        .setMessage("Delete all glucose history points from the chart and local database?")
                        .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                glucoseChart.clear();
                                GlucoseHistoryStorage.getInstance(MainActivity.this).clear();
                                Toast.makeText(MainActivity.this, "Chart and history cleared", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        btnGuide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGuideAndDisclaimerDialog();
            }
        });

        dialog.show();
    }

    private void showGuideAndDisclaimerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("ℹ️ Guide & Disclaimer");

        ScrollView scrollView = new ScrollView(this);
        int pad = dpToPx(18);
        scrollView.setPadding(pad, pad, pad, pad);

        TextView textContent = new TextView(this);
        textContent.setTextColor(Color.parseColor("#E0E0E0"));
        textContent.setTextSize(13.5f);
        textContent.setLineSpacing(dpToPx(3), 1.15f);

        StringBuilder sb = new StringBuilder();
        sb.append("📖 About MMT7821-Client\n");
        sb.append("MMT7821-Client is an independent, open-source DIY Android client for the Medtronic Guardian Connect (MMT-7821) transmitter and Enlite / Guardian Sensor 3 electrodes. It operates 100% offline without CareLink servers or device whitelisting.\n\n");

        sb.append("🚀 Quick Start & Pairing Guide\n");
        sb.append("1. Charger Reset: Disconnect the transmitter from the sensor and place it on the charger for at least 10 seconds until the green LED flashes, then remove it.\n");
        sb.append("2. Pairing: Open Settings, tap 'Scan for Transmitter (BLE)' or enter the MAC address, and tap 'Connect'. Wait ~10–15 seconds for cryptographic pairing to complete.\n");
        sb.append("3. Sensor Start & Warm-up: Attach the transmitter to the sensor on your body. The 2-hour hardware warm-up will start automatically.\n");
        sb.append("4. Calibration: Once warm-up finishes ('Waiting for Calibration'), enter a fingerstick blood glucose reading from your meter. Calibrate every 6–12 hours when glucose is stable.\n\n");

        sb.append("⏳ Sensor Lifespan & Restart\n");
        sb.append("Guardian Connect MMT-7821 enforces the 6-day sensor lifespan in transmitter hardware. To restart a sensor session, physically detach the transmitter from the sensor, place it on the charger for 15–20 minutes to reset the internal session timer, and reattach it to begin a new 2-hour warm-up.\n\n");

        sb.append("⚠️ Medical & Legal Disclaimer (IMPORTANT)\n");
        sb.append("FOR RESEARCH AND EDUCATIONAL (DIY) PURPOSES ONLY. MMT7821-Client is NOT an approved medical device and has not been evaluated by the FDA or any regulatory authority. Software or Bluetooth failures may occur. NEVER make insulin dosing or treatment decisions based solely on this app—always confirm with a certified blood glucose meter. By using this software, you assume full responsibility and all associated risks.");

        textContent.setText(sb.toString());
        scrollView.addView(textContent);

        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.show();
    }

    private void styleSettingsButton(Button btn, int bgColor, boolean isDanger) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpToPx(8));
        gd.setColor(bgColor);
        int strokeColor = isDanger ? Color.parseColor("#66FF5252") : Color.parseColor("#3A3A3A");
        gd.setStroke(dpToPx(1), strokeColor);
        btn.setBackground(gd);
        btn.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setTextSize(12.5f);
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void startBleScanner(final EditText editMacInput, final Button btnScan) {
        if (!checkPermissions()) {
            Toast.makeText(this, "Please grant Bluetooth/Location permissions first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is disabled!", Toast.LENGTH_SHORT).show();
            return;
        }

        final BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "BLE scanner is not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bleScanCallback != null) {
            try {
                scanner.stopScan(bleScanCallback);
            } catch (Exception ignored) {}
            bleScanCallback = null;
            btnScan.setText("🔍 Scan for Transmitter (BLE)");
            return;
        }

        btnScan.setText("⏹ Stop Scan (15s)...");
        styleSettingsButton(btnScan, Color.parseColor("#E65100"), false);
        appendLog("Starting BLE scan for Guardian Connect transmitter...");
        Toast.makeText(this, "Scanning for nearby Medtronic transmitters...", Toast.LENGTH_SHORT).show();

        bleScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || result.getDevice() == null) return;
                BluetoothDevice dev = result.getDevice();
                String name = dev.getName();
                String addr = dev.getAddress();

                boolean isMatch = false;
                if (name != null && (name.contains("Guardian") || name.contains("Connect") || name.contains("Medtronic"))) {
                    isMatch = true;
                }
                if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                    for (ParcelUuid u : result.getScanRecord().getServiceUuids()) {
                        if (u.getUuid().equals(GuardianBleService.SERVICE_GUARDIAN)) {
                            isMatch = true;
                            break;
                        }
                    }
                }

                if (isMatch) {
                    appendLog("TRANSMITTER FOUND: " + (name != null ? name : "Guardian") + " [" + addr + "] RSSI=" + result.getRssi());
                    try {
                        scanner.stopScan(this);
                    } catch (Exception ignored) {}
                    bleScanCallback = null;

                    final String finalDevName = name;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            editMacInput.setText(addr);
                            targetMac = addr;
                            SharedPreferences.Editor ed = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_MAC, addr);
                            if (finalDevName != null && !finalDevName.isEmpty()) {
                                ed.putString(KEY_DEVICE_NAME, finalDevName);
                            }
                            ed.apply();
                            updateTransmitterNameDisplay();
                            btnScan.setText("✓ Found: " + addr);
                            styleSettingsButton(btnScan, Color.parseColor("#2E7D32"), false);
                            Toast.makeText(MainActivity.this, "Transmitter found: " + addr, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                appendLog("BLE scan error: code " + errorCode);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        btnScan.setText("🔍 Scan for Transmitter (BLE)");
                        styleSettingsButton(btnScan, Color.parseColor("#2A2A2A"), false);
                        Toast.makeText(MainActivity.this, "BLE scan error: " + errorCode, Toast.LENGTH_SHORT).show();
                    }
                });
                bleScanCallback = null;
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(GuardianBleService.SERVICE_GUARDIAN)).build());

        try {
            scanner.startScan(filters, settings, bleScanCallback);
        } catch (Exception e) {
            try {
                scanner.startScan(null, settings, bleScanCallback);
            } catch (Exception ex) {
                appendLog("Could not start BLE scan: " + ex.getMessage());
                btnScan.setText("🔍 Scan for Transmitter (BLE)");
                styleSettingsButton(btnScan, Color.parseColor("#2A2A2A"), false);
                bleScanCallback = null;
                return;
            }
        }

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (bleScanCallback != null) {
                    try {
                        scanner.stopScan(bleScanCallback);
                    } catch (Exception ignored) {}
                    bleScanCallback = null;
                    btnScan.setText("🔍 Scan for Transmitter (BLE)");
                    styleSettingsButton(btnScan, Color.parseColor("#2A2A2A"), false);
                    appendLog("BLE scan timed out (15s).");
                }
            }
        }, 15000);
    }

    private void rePairTransmitter() {
        startBleService();
        sensorStartTimeMs = 0;
        isInWarmup = false;
        isCalRequired = false;
        isCalPending = false;
        currentMmol = 0.0f;
        isStaleData = false;
        lastMeasurementTimeMs = 0;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_SENSOR_START_TIME, 0)
                .putBoolean(KEY_IS_IN_WARMUP, false)
                .putBoolean(KEY_IS_CAL_REQUIRED, false)
                .putBoolean(KEY_IS_CAL_PENDING, false)
                .putFloat(KEY_LAST_MMOL, 0.0f)
                .putFloat(KEY_LAST_MGDL, 0.0f)
                .putLong(KEY_LAST_MEASUREMENT_TIME, 0)
                .apply();
        displayDisconnected();
        updateBatteryDisplay(-1);
        updateSensorDaysDisplay();
        updateCalCountdownDisplay();
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.rePairTransmitter();
        }
    }

    private void showCalibrationDialog() {
        boolean inWarmup = isInWarmup || (GuardianBleService.getInstance() != null && GuardianBleService.getInstance().isInWarmup());
        if (inWarmup) {
            new AlertDialog.Builder(this)
                    .setTitle("Calibration Unavailable")
                    .setMessage("Enlite sensor warm-up is in progress (~2 hours).\n\nBlood glucose calibration cannot be entered until the warm-up completes.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(useMgDl ? "e.g. 100" : "e.g. 5.6");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setBackgroundColor(Color.parseColor("#282828"));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        String unitStr = useMgDl ? "mg/dL" : "mmol/L";
        new AlertDialog.Builder(this)
                .setTitle("Enlite Sensor Calibration")
                .setMessage("Enter current blood glucose from your meter (" + unitStr + ").\n\nIt is recommended to calibrate during stable glucose every 6–12 hours.")
                .setView(input)
                .setPositiveButton("Calibrate", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performCalibration(input.getText().toString().trim().replace(',', '.'));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performCalibration(String input) {
        boolean inWarmup = isInWarmup || (GuardianBleService.getInstance() != null && GuardianBleService.getInstance().isInWarmup());
        if (inWarmup) {
            Toast.makeText(this, "Calibration unavailable: warm-up in progress", Toast.LENGTH_LONG).show();
            return;
        }

        if (input.isEmpty()) {
            Toast.makeText(this, "Value not entered", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            float enteredVal = Float.parseFloat(input);
            float bgMmol;
            if (useMgDl) {
                if (enteredVal < 36.0f || enteredVal > 450.0f) {
                    Toast.makeText(this, "Value must be between 36 and 450 mg/dL", Toast.LENGTH_SHORT).show();
                    return;
                }
                bgMmol = enteredVal / 18.0182f;
            } else {
                if (enteredVal < 2.0f || enteredVal > 25.0f) {
                    Toast.makeText(this, "Value must be between 2.0 and 25.0 mmol/L", Toast.LENGTH_SHORT).show();
                    return;
                }
                bgMmol = enteredVal;
            }

            int bgMgDl = Math.round(bgMmol * 18.0182f);
            long now = System.currentTimeMillis();

            appendLog(String.format(Locale.US, "Calibration entered: %d mg/dL (%.1f mmol/L). Sending to transmitter...",
                    bgMgDl, bgMmol));

            isCalPending = true;
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putBoolean(KEY_IS_CAL_PENDING, true)
                    .apply();
            updateCalCountdownDisplay();

            // Send calibration to transmitter via GuardianBleService (which also saves to GlucoseHistoryStorage)
            GuardianBleService service = GuardianBleService.getInstance();
            if (service != null && service.isDeviceConnected()) {
                service.sendCalibration(bgMmol);
                Toast.makeText(this, "Calibration sent to transmitter!", Toast.LENGTH_SHORT).show();
            } else {
                GlucoseHistoryStorage.getInstance(this).addPoint(now, bgMmol, (float) bgMgDl, GlucoseHistoryStorage.TYPE_CALIBRATION);
                Toast.makeText(this, "Calibration logged locally (transmitter not connected)", Toast.LENGTH_SHORT).show();
            }

            glucoseChart.addPoint(now, bgMmol, (float) bgMgDl, GlucoseChartView.TYPE_CALIBRATION);
            displayCalibrationPending(String.format(Locale.US, "Calibration (%.1f) sent, processing...", bgMmol));

            if (btnQuickCalibrate != null) {
                btnQuickCalibrate.setVisibility(View.GONE);
            }
            GlucoseWidgetProvider.updateAllWidgets(this);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLogBgDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(useMgDl ? "e.g. 100" : "e.g. 5.6");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setBackgroundColor(Color.parseColor("#282828"));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        String unitStr = useMgDl ? "mg/dL" : "mmol/L";
        new AlertDialog.Builder(this)
                .setTitle("Log Blood Glucose (Meter)")
                .setMessage("Enter fingerstick BG (" + unitStr + ") from your blood glucose meter to record on the chart without calibrating the transmitter:")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performLogBg(input.getText().toString().trim().replace(',', '.'));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogBg(String input) {
        if (input.isEmpty()) {
            Toast.makeText(this, "Value not entered", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            float enteredVal = Float.parseFloat(input);
            float bgMmol;
            if (useMgDl) {
                if (enteredVal < 36.0f || enteredVal > 450.0f) {
                    Toast.makeText(this, "Value must be between 36 and 450 mg/dL", Toast.LENGTH_SHORT).show();
                    return;
                }
                bgMmol = enteredVal / 18.0182f;
            } else {
                if (enteredVal < 2.0f || enteredVal > 25.0f) {
                    Toast.makeText(this, "Value must be between 2.0 and 25.0 mmol/L", Toast.LENGTH_SHORT).show();
                    return;
                }
                bgMmol = enteredVal;
            }

            int bgMgDl = Math.round(bgMmol * 18.0182f);
            long now = System.currentTimeMillis();

            appendLog(String.format(Locale.US, "Manual meter BG recorded: %.1f mmol/L (%d mg/dL)", bgMmol, bgMgDl));
            if (useMgDl) {
                Toast.makeText(this, String.format(Locale.US, "Logged BG: %d mg/dL", bgMgDl), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, String.format(Locale.US, "Logged BG: %.1f mmol/L", bgMmol), Toast.LENGTH_SHORT).show();
            }

            GlucoseHistoryStorage.getInstance(this).addManualBgPoint(now, bgMmol);
            glucoseChart.addManualBgPoint(now, bgMmol);
            GlucoseWidgetProvider.updateAllWidgets(this);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
        }
    }

    private void showTherapyDialog() {
        final Calendar selectedCalendar = Calendar.getInstance();
        final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#1E1E1E"));

        // Time Selection Row
        TextView lblTime = new TextView(this);
        lblTime.setText("Event Time (Today):");
        lblTime.setTextColor(Color.parseColor("#BBBBBB"));
        lblTime.setTextSize(13);
        layout.addView(lblTime);

        final Button btnTimePicker = new Button(this);
        btnTimePicker.setText("🕐 " + timeFmt.format(selectedCalendar.getTime()));
        btnTimePicker.setTextColor(Color.WHITE);
        btnTimePicker.setTextSize(14);
        styleSettingsButton(btnTimePicker, Color.parseColor("#2A2A2A"), false);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.topMargin = dpToPx(4);
        timeLp.bottomMargin = dpToPx(6);
        btnTimePicker.setLayoutParams(timeLp);
        layout.addView(btnTimePicker);

        btnTimePicker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int hour = selectedCalendar.get(Calendar.HOUR_OF_DAY);
                int minute = selectedCalendar.get(Calendar.MINUTE);
                TimePickerDialog tpd = new TimePickerDialog(MainActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int min) {
                        selectedCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                        selectedCalendar.set(Calendar.MINUTE, min);
                        selectedCalendar.set(Calendar.SECOND, 0);
                        selectedCalendar.set(Calendar.MILLISECOND, 0);
                        btnTimePicker.setText("🕐 " + timeFmt.format(selectedCalendar.getTime()));
                    }
                }, hour, minute, true);
                tpd.show();
            }
        });

        // Quick Preset Buttons Row: [Now] [-15m] [-30m] [+15m]
        LinearLayout rowPresets = new LinearLayout(this);
        rowPresets.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams presetsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetsLp.bottomMargin = dpToPx(12);
        rowPresets.setLayoutParams(presetsLp);

        String[] presetLabels = new String[]{"Now", "-15m", "-30m", "+15m"};
        final int[] presetMinutes = new int[]{0, -15, -30, 15};

        for (int i = 0; i < presetLabels.length; i++) {
            final int offsetMin = presetMinutes[i];
            Button btnPreset = new Button(this);
            btnPreset.setText(presetLabels[i]);
            btnPreset.setTextColor(Color.parseColor("#80D8FF"));
            btnPreset.setTextSize(11);
            styleSettingsButton(btnPreset, Color.parseColor("#252525"), false);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) pLp.leftMargin = dpToPx(4);
            btnPreset.setLayoutParams(pLp);

            btnPreset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (offsetMin == 0) {
                        selectedCalendar.setTimeInMillis(System.currentTimeMillis());
                    } else {
                        selectedCalendar.setTimeInMillis(System.currentTimeMillis() + (offsetMin * 60_000L));
                    }
                    btnTimePicker.setText("🕐 " + timeFmt.format(selectedCalendar.getTime()));
                }
            });
            rowPresets.addView(btnPreset);
        }
        layout.addView(rowPresets);

        // Insulin Input
        TextView lblBolus = new TextView(this);
        lblBolus.setText("💉 Insulin Bolus (Units):");
        lblBolus.setTextColor(Color.parseColor("#00E5FF"));
        lblBolus.setTextSize(13);
        layout.addView(lblBolus);

        final EditText editBolus = new EditText(this);
        editBolus.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editBolus.setHint("e.g. 4.5");
        editBolus.setHintTextColor(Color.GRAY);
        editBolus.setTextColor(Color.WHITE);
        editBolus.setBackgroundColor(Color.parseColor("#282828"));
        editBolus.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams bolusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bolusLp.topMargin = dpToPx(3);
        bolusLp.bottomMargin = dpToPx(6);
        editBolus.setLayoutParams(bolusLp);
        layout.addView(editBolus);

        // Quick Bolus Buttons: Dynamic Top-4 from History or Defaults (1U, 2U, 3U, 5U)
        List<Float> topBoluses = getTopTherapyValues(true);
        LinearLayout rowBolusPresets = new LinearLayout(this);
        rowBolusPresets.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bolusPresetsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bolusPresetsLp.bottomMargin = dpToPx(12);
        rowBolusPresets.setLayoutParams(bolusPresetsLp);

        for (int i = 0; i < topBoluses.size(); i++) {
            final float val = topBoluses.get(i);
            final String valStr = (val == (long) val)
                    ? String.format(Locale.US, "%.0f", val)
                    : String.format(Locale.US, "%.1f", val);
            Button btn = new Button(this);
            btn.setText(valStr + "U");
            btn.setTextColor(Color.parseColor("#00E5FF"));
            btn.setTextSize(11);
            styleSettingsButton(btn, Color.parseColor("#252525"), false);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) pLp.leftMargin = dpToPx(4);
            btn.setLayoutParams(pLp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editBolus.setText(valStr);
                    editBolus.setSelection(valStr.length());
                }
            });
            rowBolusPresets.addView(btn);
        }
        layout.addView(rowBolusPresets);

        // Carbs Input
        TextView lblCarbs = new TextView(this);
        lblCarbs.setText("🍞 Carbohydrates (Grams):");
        lblCarbs.setTextColor(Color.parseColor("#FFAB40"));
        lblCarbs.setTextSize(13);
        layout.addView(lblCarbs);

        final EditText editCarbs = new EditText(this);
        editCarbs.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editCarbs.setHint("e.g. 50");
        editCarbs.setHintTextColor(Color.GRAY);
        editCarbs.setTextColor(Color.WHITE);
        editCarbs.setBackgroundColor(Color.parseColor("#282828"));
        editCarbs.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams carbsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        carbsLp.topMargin = dpToPx(3);
        carbsLp.bottomMargin = dpToPx(6);
        editCarbs.setLayoutParams(carbsLp);
        layout.addView(editCarbs);

        // Quick Carbs Buttons: Dynamic Top-4 from History or Defaults (10g, 20g, 30g, 50g)
        List<Float> topCarbs = getTopTherapyValues(false);
        LinearLayout rowCarbsPresets = new LinearLayout(this);
        rowCarbsPresets.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams carbsPresetsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        carbsPresetsLp.bottomMargin = dpToPx(12);
        rowCarbsPresets.setLayoutParams(carbsPresetsLp);

        for (int i = 0; i < topCarbs.size(); i++) {
            final float val = topCarbs.get(i);
            final String valStr = (val == (long) val)
                    ? String.format(Locale.US, "%.0f", val)
                    : String.format(Locale.US, "%.1f", val);
            Button btn = new Button(this);
            btn.setText(valStr + "g");
            btn.setTextColor(Color.parseColor("#FFAB40"));
            btn.setTextSize(11);
            styleSettingsButton(btn, Color.parseColor("#252525"), false);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) pLp.leftMargin = dpToPx(4);
            btn.setLayoutParams(pLp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editCarbs.setText(valStr);
                    editCarbs.setSelection(valStr.length());
                }
            });
            rowCarbsPresets.addView(btn);
        }
        layout.addView(rowCarbsPresets);

        // Blood Glucose (Meter) Input
        TextView lblBg = new TextView(this);
        String unitStr = useMgDl ? "mg/dL" : "mmol/L";
        lblBg.setText("🩸 Blood Glucose (Meter, " + unitStr + ", Optional):");
        lblBg.setTextColor(Color.parseColor("#FF8A80"));
        lblBg.setTextSize(13);
        layout.addView(lblBg);

        final EditText editBg = new EditText(this);
        editBg.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        editBg.setHint(useMgDl ? "e.g. 100" : "e.g. 5.6");
        editBg.setHintTextColor(Color.GRAY);
        editBg.setTextColor(Color.WHITE);
        editBg.setBackgroundColor(Color.parseColor("#282828"));
        editBg.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams bgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bgLp.topMargin = dpToPx(3);
        bgLp.bottomMargin = dpToPx(12);
        editBg.setLayoutParams(bgLp);
        layout.addView(editBg);

        // Note Input
        TextView lblNote = new TextView(this);
        lblNote.setText("📝 Note / Meal (Optional):");
        lblNote.setTextColor(Color.parseColor("#BBBBBB"));
        lblNote.setTextSize(13);
        layout.addView(lblNote);

        final EditText editNote = new EditText(this);
        editNote.setInputType(InputType.TYPE_CLASS_TEXT);
        editNote.setHint("e.g. Lunch, Snack, Correction");
        editNote.setHintTextColor(Color.GRAY);
        editNote.setTextColor(Color.WHITE);
        editNote.setBackgroundColor(Color.parseColor("#282828"));
        editNote.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dpToPx(3);
        noteLp.bottomMargin = dpToPx(6);
        editNote.setLayoutParams(noteLp);
        layout.addView(editNote);

        // Quick Note / Meal Presets Row
        LinearLayout rowNotePresets = new LinearLayout(this);
        rowNotePresets.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams notePresetsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notePresetsLp.bottomMargin = dpToPx(6);
        rowNotePresets.setLayoutParams(notePresetsLp);

        String[] notePresets = new String[]{"Lunch", "Snack", "Correction"};
        for (int i = 0; i < notePresets.length; i++) {
            final String presetText = notePresets[i];
            Button btn = new Button(this);
            btn.setText(presetText);
            btn.setTextColor(Color.parseColor("#E0E0E0"));
            btn.setTextSize(11);
            styleSettingsButton(btn, Color.parseColor("#252525"), false);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) pLp.leftMargin = dpToPx(4);
            btn.setLayoutParams(pLp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editNote.setText(presetText);
                    editNote.setSelection(presetText.length());
                }
            });
            rowNotePresets.addView(btn);
        }
        layout.addView(rowNotePresets);

        new AlertDialog.Builder(this)
                .setTitle("Log Therapy & BG")
                .setView(layout)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String bgStr = editBg.getText().toString().trim().replace(',', '.');
                        String bolusStr = editBolus.getText().toString().trim().replace(',', '.');
                        String carbsStr = editCarbs.getText().toString().trim().replace(',', '.');
                        String note = editNote.getText().toString().trim();

                        float bgMmol = 0f;
                        if (!bgStr.isEmpty()) {
                            try {
                                float enteredBg = Float.parseFloat(bgStr);
                                if (useMgDl) {
                                    if (enteredBg < 36.0f || enteredBg > 450.0f) {
                                        Toast.makeText(MainActivity.this, "BG must be between 36 and 450 mg/dL", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    bgMmol = enteredBg / 18.0182f;
                                } else {
                                    if (enteredBg < 2.0f || enteredBg > 25.0f) {
                                        Toast.makeText(MainActivity.this, "BG must be between 2.0 and 25.0 mmol/L", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    bgMmol = enteredBg;
                                }
                            } catch (NumberFormatException e) {
                                Toast.makeText(MainActivity.this, "Invalid BG value", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        float bolus = 0f;
                        if (!bolusStr.isEmpty()) {
                            try {
                                bolus = Float.parseFloat(bolusStr);
                            } catch (NumberFormatException e) {
                                Toast.makeText(MainActivity.this, "Invalid insulin value", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        float carbs = 0f;
                        if (!carbsStr.isEmpty()) {
                            try {
                                carbs = Float.parseFloat(carbsStr);
                            } catch (NumberFormatException e) {
                                Toast.makeText(MainActivity.this, "Invalid carbs value", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }

                        if (bgMmol <= 0f && bolus <= 0f && carbs <= 0f && note.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Enter BG, insulin, carbohydrates or note", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (bolus > 100f) {
                            Toast.makeText(MainActivity.this, "Insulin bolus too high (max 100U)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (carbs > 500f) {
                            Toast.makeText(MainActivity.this, "Carbohydrates too high (max 500g)", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        long eventTime = selectedCalendar.getTimeInMillis();
                        GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(MainActivity.this);

                        if (bgMmol > 0f) {
                            storage.addManualBgPoint(eventTime, bgMmol);
                            glucoseChart.addManualBgPoint(eventTime, bgMmol);
                            GlucoseWidgetProvider.updateAllWidgets(MainActivity.this);
                        }

                        if (bolus > 0f || carbs > 0f || !note.isEmpty()) {
                            storage.saveTherapy(eventTime, bolus, carbs, note);
                            glucoseChart.setTherapyEntries(storage.getTherapyHistory());
                        }

                        StringBuilder sb = new StringBuilder("Saved [");
                        sb.append(timeFmt.format(new Date(eventTime))).append("]: ");
                        if (bgMmol > 0f) {
                            if (useMgDl) {
                                sb.append(String.format(Locale.US, "BG %d mg/dL ", Math.round(bgMmol * 18.0182f)));
                            } else {
                                sb.append(String.format(Locale.US, "BG %.1f mmol/L ", bgMmol));
                            }
                        }
                        if (bolus > 0f) sb.append(String.format(Locale.US, "%.1fU insulin ", bolus));
                        if (carbs > 0f) sb.append(String.format(Locale.US, "%.0fg carbs ", carbs));
                        if (!note.isEmpty()) sb.append("(").append(note).append(")");

                        appendLog(sb.toString().trim());
                        Toast.makeText(MainActivity.this, sb.toString().trim(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<Float> getTopTherapyValues(boolean isBolus) {
        GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(this);
        List<GlucoseHistoryStorage.TherapyEntry> history = storage.getTherapyHistory();
        final Map<Float, Integer> freqMap = new LinkedHashMap<>();

        for (int i = history.size() - 1; i >= 0; i--) {
            GlucoseHistoryStorage.TherapyEntry entry = history.get(i);
            float val = isBolus ? entry.bolusUnits : entry.carbsGrams;
            if (val > 0.05f) {
                float rounded = Math.round(val * 10f) / 10f;
                freqMap.put(rounded, freqMap.containsKey(rounded) ? freqMap.get(rounded) + 1 : 1);
            }
        }

        List<Float> sortedKeys = new ArrayList<>(freqMap.keySet());
        Collections.sort(sortedKeys, new Comparator<Float>() {
            @Override
            public int compare(Float a, Float b) {
                int cmp = Integer.compare(freqMap.get(b), freqMap.get(a));
                if (cmp != 0) return cmp;
                return Float.compare(a, b);
            }
        });

        float[] defaults = isBolus ? new float[]{1.0f, 2.0f, 3.0f, 5.0f} : new float[]{10f, 20f, 30f, 50f};
        List<Float> result = new ArrayList<>();
        for (Float val : sortedKeys) {
            if (result.size() >= 4) break;
            if (!result.contains(val)) {
                result.add(val);
            }
        }

        for (float defVal : defaults) {
            if (result.size() >= 4) break;
            if (!result.contains(defVal)) {
                result.add(defVal);
            }
        }

        Collections.sort(result);
        return result;
    }

    private void showTherapyDetailsDialog(final GlucoseHistoryStorage.TherapyEntry entry) {
        if (entry == null) return;
        final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault());
        String timeStr = timeFmt.format(new Date(entry.timestamp));

        StringBuilder msg = new StringBuilder();
        msg.append("Time: ").append(timeStr).append("\n\n");
        if (entry.hasBolus()) {
            String bolusStr = (entry.bolusUnits == (long) entry.bolusUnits)
                    ? String.format(Locale.US, "%.0fU", entry.bolusUnits)
                    : String.format(Locale.US, "%.1fU", entry.bolusUnits);
            msg.append("💉 Insulin Bolus: ").append(bolusStr).append("\n");
        }
        if (entry.hasCarbs()) {
            String carbStr = (entry.carbsGrams == (long) entry.carbsGrams)
                    ? String.format(Locale.US, "%.0fg", entry.carbsGrams)
                    : String.format(Locale.US, "%.1fg", entry.carbsGrams);
            msg.append("🍞 Carbohydrates: ").append(carbStr).append("\n");
        }
        if (entry.note != null && !entry.note.trim().isEmpty()) {
            msg.append("📝 Note: ").append(entry.note.trim()).append("\n");
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Therapy Details")
                .setMessage(msg.toString().trim())
                .setNegativeButton("Close", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(MainActivity.this);
                        boolean deleted = storage.deleteTherapy(entry.timestamp);
                        if (deleted) {
                            glucoseChart.setTherapyEntries(storage.getTherapyHistory());
                            String logMsg = "Deleted therapy record at " + timeFmt.format(new Date(entry.timestamp));
                            appendLog(logMsg);
                            Toast.makeText(MainActivity.this, "Therapy record deleted", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .create();

        dialog.show();
        Button btnDelete = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (btnDelete != null) {
            btnDelete.setTextColor(Color.parseColor("#FF5252"));
        }
    }

    private void showPointDetailsDialog(final GlucoseChartView.Point point) {
        if (point == null) return;
        final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault());
        String timeStr = timeFmt.format(new Date(point.timestamp));
        int mgDl = Math.round(point.glucoseMmol * 18.0182f);

        if (point.isCalibration()) {
            String info = String.format(Locale.US, "Time: %s\n\nSensor Calibration: %.1f mmol/L (%d mg/dL)\n\n" +
                    "⚠️ Transmitter calibrations are permanent and cannot be deleted to preserve sensor slope integrity.",
                    timeStr, point.glucoseMmol, mgDl);

            new AlertDialog.Builder(this)
                    .setTitle("Sensor Calibration")
                    .setMessage(info)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (point.isManualBg()) {
            String info = String.format(Locale.US, "Time: %s\n\nBlood Glucose: %.1f mmol/L (%d mg/dL)\nType: Manual Meter Fingerstick",
                    timeStr, point.glucoseMmol, mgDl);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Manual Blood Glucose")
                    .setMessage(info)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(MainActivity.this);
                            boolean deleted = storage.deletePoint(point.timestamp);
                            if (deleted) {
                                loadChartPoints();
                                String logMsg = "Deleted manual BG reading (" + point.glucoseMmol + " mmol/L) at " + timeFmt.format(new Date(point.timestamp));
                                appendLog(logMsg);
                                Toast.makeText(MainActivity.this, "Manual BG reading deleted", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this, "Could not delete point", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .create();

            dialog.show();
            Button btnDelete = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (btnDelete != null) {
                btnDelete.setTextColor(Color.parseColor("#FF5252"));
            }
        }
    }

    private void loadChartPoints() {
        GlucoseHistoryStorage storage = GlucoseHistoryStorage.getInstance(this);
        List<GlucoseChartView.Point> pts = storage.getChartPoints(288);
        glucoseChart.setPoints(pts);
        glucoseChart.setTherapyEntries(storage.getTherapyHistory());

        if (!pts.isEmpty()) {
            GlucoseChartView.Point lastPt = pts.get(pts.size() - 1);
            if (lastMeasurementTimeMs < lastPt.timestamp) {
                lastMeasurementTimeMs = lastPt.timestamp;
            }
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;

        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Android 6.0 - 11.0 require location permission for BLE scanning
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            return false;
        }
        return true;
    }

    private void checkPermissionsAndConnect() {
        if (!checkPermissions()) {
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is disabled", Toast.LENGTH_SHORT).show();
            return;
        }

        String mac = (targetMac != null) ? targetMac.trim().toUpperCase(Locale.US) : "";
        if (mac.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            Toast.makeText(this, "Please enter transmitter MAC or scan in Settings", Toast.LENGTH_LONG).show();
            return;
        }

        connectToDevice(mac);
    }

    private void connectToDevice(String mac) {
        startBleService();
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.connectToDevice(mac);
        }
    }

    private void disconnect() {
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.disconnect();
        }
        updateStatus("Disconnected");
    }

    private void displayDisconnected() {
        if (textGlucoseMmolValue != null) {
            textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            textGlucoseMmolValue.setText("--");
            textGlucoseMmolValue.setTextColor(Color.parseColor("#888888"));
        }
        if (textGlucoseMmolUnit != null) {
            textGlucoseMmolUnit.setVisibility(View.GONE);
        }
        if (textTrendArrow != null) {
            textTrendArrow.setVisibility(View.GONE);
        }
        if (textGlucoseMgDl != null) {
            textGlucoseMgDl.setText("Disconnected");
            textGlucoseMgDl.setTextColor(Color.parseColor("#888888"));
        }
        if (textSensorDetails != null) {
            textSensorDetails.setText("Transmitter disconnected");
            textSensorDetails.setTextColor(Color.parseColor("#888888"));
        }
        if (textCountdown != null) {
            textCountdown.setText("Transmitter Disconnected");
            textCountdown.setTextColor(Color.parseColor("#888888"));
        }
        if (btnQuickCalibrate != null) {
            btnQuickCalibrate.setVisibility(View.GONE);
        }
        updateStatus("Disconnected");
        updateBatteryDisplay(-1);
        updateSensorDaysDisplay();
        updateCalCountdownDisplay();
    }

    private void displayWarmup(int rawVal, String statusDesc) {
        isInWarmup = true;
        isCalRequired = false;
        currentMmol = 0.0f;
        isStaleData = false;
        lastMeasurementTimeMs = System.currentTimeMillis();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_MEASUREMENT_TIME, lastMeasurementTimeMs)
                .putBoolean(KEY_IS_IN_WARMUP, true)
                .putBoolean(KEY_IS_CAL_REQUIRED, false)
                .apply();

        textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        textGlucoseMmolValue.setText("--");
        textGlucoseMmolValue.setTextColor(Color.parseColor("#4FC3F7"));
        textGlucoseMmolUnit.setVisibility(View.GONE);
        if (textTrendArrow != null) {
            textTrendArrow.setVisibility(View.GONE);
        }
        textGlucoseMgDl.setText("Enlite Sensor Warm-up...");
        textGlucoseMgDl.setTextColor(Color.parseColor("#B0BEC5"));
        String desc = (statusDesc != null && !statusDesc.isEmpty()) ? statusDesc : "Sensor Warm-up";
        textSensorDetails.setText(desc);
        if (btnQuickCalibrate != null) {
            btnQuickCalibrate.setVisibility(View.GONE);
        }
    }

    private void displayCalibrationRequired(int rawVal, String statusDesc) {
        isInWarmup = false;
        isCalRequired = true;
        isCalPending = false;
        currentMmol = 0.0f;
        isStaleData = false;
        lastMeasurementTimeMs = System.currentTimeMillis();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_MEASUREMENT_TIME, lastMeasurementTimeMs)
                .putBoolean(KEY_IS_IN_WARMUP, false)
                .putBoolean(KEY_IS_CAL_PENDING, false)
                .putBoolean(KEY_IS_CAL_REQUIRED, true)
                .apply();

        long lastCalTime = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getLong(KEY_CAL_TIME, 0);
        String calTitle = (lastCalTime > 0) ? "Calibration Needed" : "Waiting for 1st Calibration";

        textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        textGlucoseMmolValue.setText("--");
        textGlucoseMmolValue.setTextColor(Color.parseColor("#FFD54F"));
        textGlucoseMmolUnit.setVisibility(View.GONE);
        if (textTrendArrow != null) {
            textTrendArrow.setVisibility(View.GONE);
        }
        textGlucoseMgDl.setText(calTitle);
        textGlucoseMgDl.setTextColor(Color.parseColor("#FFD54F"));
        String desc = (statusDesc != null && !statusDesc.isEmpty()) ? statusDesc : calTitle;
        textSensorDetails.setText(desc);
        textCountdown.setText(lastCalTime > 0 ? "🩸 " + calTitle : calTitle);
        textCountdown.setTextColor(Color.parseColor("#FFD54F"));
        if (btnQuickCalibrate != null) {
            btnQuickCalibrate.setVisibility(View.VISIBLE);
        }
    }

    private void displayCalibrationPending(String statusDesc) {
        isInWarmup = false;
        isCalPending = true;
        isCalRequired = false;
        currentMmol = 0.0f;
        isStaleData = false;
        lastMeasurementTimeMs = System.currentTimeMillis();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_MEASUREMENT_TIME, lastMeasurementTimeMs)
                .putBoolean(KEY_IS_IN_WARMUP, false)
                .putBoolean(KEY_IS_CAL_PENDING, true)
                .apply();

        textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        textGlucoseMmolValue.setText("--");
        textGlucoseMmolValue.setTextColor(Color.parseColor("#4FC3F7"));
        textGlucoseMmolUnit.setVisibility(View.GONE);
        if (textTrendArrow != null) {
            textTrendArrow.setVisibility(View.VISIBLE);
            textTrendArrow.setText("⏳");
            textTrendArrow.setTextColor(Color.parseColor("#4FC3F7"));
        }
        textGlucoseMgDl.setText("Processing Calibration...");
        textGlucoseMgDl.setTextColor(Color.parseColor("#4FC3F7"));
        String desc = (statusDesc != null && !statusDesc.isEmpty()) ? statusDesc : "Processing Calibration (5–15 min)...";
        textSensorDetails.setText(desc);
        textCountdown.setText("⏳ Waiting for calibration acceptance...");
        textCountdown.setTextColor(Color.parseColor("#4FC3F7"));
        if (btnQuickCalibrate != null) {
            btnQuickCalibrate.setVisibility(View.GONE);
        }
    }

    private void displayGlucose(float mmol, float mgDl, int rawVal, double trend, String statusDesc) {
        String trendStr = Double.isNaN(trend) ? "--" : String.format(Locale.US, "%+.2f", trend);
        GuardianBleService s = GuardianBleService.getInstance();

        boolean serviceInWarmup = (s != null && s.isInWarmup()) || isInWarmup;
        boolean serviceCalPending = (s != null && s.isCalPending()) || isCalPending;
        boolean serviceCalReq = (s != null && s.isCalRequired()) || isCalRequired;

        if (serviceInWarmup) {
            displayWarmup(rawVal, statusDesc);
            return;
        }
        if (serviceCalPending) {
            displayCalibrationPending(statusDesc);
            return;
        }
        if (serviceCalReq) {
            displayCalibrationRequired(rawVal, statusDesc);
            return;
        }

        if (rawVal == 65535 || mmol <= 0.0f) {
            isInWarmup = false;
            isCalPending = false;
            isCalRequired = false;
            currentMmol = 0.0f;
            lastRawIsig = rawVal;
            lastTrendRate = Double.NaN;
            lastMeasurementTimeMs = System.currentTimeMillis();

            textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            textGlucoseMmolValue.setText("--");
            textGlucoseMmolValue.setTextColor(Color.parseColor("#888888"));
            textGlucoseMmolUnit.setVisibility(View.GONE);
            if (textTrendArrow != null) {
                textTrendArrow.setVisibility(View.GONE);
            }
            textGlucoseMgDl.setText("Waiting for sensor reading...");
            textGlucoseMgDl.setTextColor(Color.parseColor("#888888"));
            String sensorState = "Normal".equals(statusDesc) ? "Sensor OK" : ((statusDesc != null && !statusDesc.isEmpty()) ? statusDesc : "Waiting for sensor...");
            textSensorDetails.setText(sensorState);
            textSensorDetails.setTextColor(Color.parseColor("#AAAAAA"));
            if (btnQuickCalibrate != null) {
                btnQuickCalibrate.setVisibility(View.GONE);
            }
            updateStatus("Connected to transmitter");
            return;
        }

        isInWarmup = false;
        isCalPending = false;
        isCalRequired = false;
            float prevMmol = currentMmol;
            currentMmol = mmol;
            lastRawIsig = rawVal;
            lastTrendRate = trend;
            isStaleData = false;
            lastMeasurementTimeMs = System.currentTimeMillis();

            textGlucoseMmolValue.setPaintFlags(textGlucoseMmolValue.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            if (prevMmol > 0.0f && Math.abs(prevMmol - mmol) >= 0.05f) {
                animateGlucoseTransition(prevMmol, mmol, mgDl);
            } else {
                if (useMgDl) {
                    textGlucoseMmolValue.setText(String.format(Locale.US, "%.0f", mgDl));
                    textGlucoseMmolUnit.setText("mg/dL");
                    textGlucoseMgDl.setText(String.format(Locale.US, "%.1f mmol/L", mmol));
                } else {
                    textGlucoseMmolValue.setText(String.format(Locale.US, "%.1f", mmol));
                    textGlucoseMmolUnit.setText("mmol/L");
                    textGlucoseMgDl.setText(String.format(Locale.US, "%.0f mg/dL", mgDl));
                }
                applyGlucoseColor(mmol);
            }
            textGlucoseMmolUnit.setVisibility(View.VISIBLE);

            if (textTrendArrow != null) {
                textTrendArrow.setText(getTrendArrow(trend));
                textTrendArrow.setVisibility(View.VISIBLE);
            }

            if (useMgDl) {
                textGlucoseMgDl.setText(String.format(Locale.US, "%.1f mmol/L", mmol));
            } else {
                textGlucoseMgDl.setText(String.format(Locale.US, "%.0f mg/dL", mgDl));
            }
            textGlucoseMgDl.setTextColor(Color.parseColor("#CCCCCC"));
            String sensorState = "Normal".equals(statusDesc) ? "Sensor OK" : ((statusDesc != null && !statusDesc.isEmpty()) ? statusDesc : "Sensor OK");
            textSensorDetails.setText(String.format(Locale.US, "Trend: %s | %s", trendStr, sensorState));
            textSensorDetails.setTextColor(Color.parseColor("#AAAAAA"));

            if (serviceCalReq) {
                if (btnQuickCalibrate != null) {
                    btnQuickCalibrate.setVisibility(View.VISIBLE);
                }
                textCountdown.setText("🩸 Calibration Needed");
                textCountdown.setTextColor(Color.parseColor("#FFD54F"));
            } else {
                if (btnQuickCalibrate != null) {
                    btnQuickCalibrate.setVisibility(View.GONE);
                }
            }

            if ("Backfill History".equals(statusDesc)) {
                loadChartPoints();
            } else if (statusDesc == null || !statusDesc.startsWith("Calibration (")) {
                // Normal CGM point (GuardianBleService already persisted it to GlucoseHistoryStorage)
                glucoseChart.addPoint(lastMeasurementTimeMs, mmol, (float) rawVal);
            }

            updateStatus("Updated at " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastMeasurementTimeMs)));
    }

    private void updateStatus(final String text) {
        Log.i(TAG, "STATUS: " + text);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                textStatus.setText(text);
            }
        });
    }

    private void appendLog(final String msg) {
        Log.i(TAG, msg);
        GuardianBleService service = GuardianBleService.getInstance();
        if (service != null) {
            service.log(msg);
        } else {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    GuardianBleService s = GuardianBleService.getInstance();
                    if (s != null) {
                        s.log(msg);
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (glucoseAnimator != null) {
            glucoseAnimator.cancel();
        }
        mainHandler.removeCallbacks(countdownRunnable);
    }
}
