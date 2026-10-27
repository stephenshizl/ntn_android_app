package com.youyou.ntn_bt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import org.json.JSONObject;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements BluetoothSppClient.Callback {
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSppClient sppClient;
    private OtaUpdateService otaService;
    private boolean otaBound;
    private final OtaUpdateService.Observer otaObserver = this::renderBluetoothVersion;
    private final ServiceConnection otaConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            otaService = ((OtaUpdateService.LocalBinder) binder).service();
            if (gnssForeground) otaService.observe(otaObserver);
            queryConnectedBluetoothVersion();
            renderBluetoothVersion();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            otaService = null;
            renderBluetoothVersion();
        }
    };
    private Spinner deviceSpinner;
    private Spinner baudSpinner;
    private TextView statusText;
    private TextView logText;
    private EditText localCommandEdit;
    private EditText ntnCommandEdit;
    private ScrollView logScroll;
    private String connectedMac;
    private TerminalSettings terminalSettings;
    private int pendingNtn;
    private long ntnStarted;
    private final StringBuilder ntnResponse = new StringBuilder();
    private int pendingName, pendingIdentity;
    private long nameQueryAt;
    private boolean namePoll;
    private final BroadcastReceiver nameReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_NAME_CHANGED.equals(intent.getAction())) refreshPairedDevices();
        }
    };
    private CheckBox gnssAutoRefresh;
    private final Handler gnssHandler = new Handler(Looper.getMainLooper());
    private GnssReading gnssReading;
    private GnssReading.TimeReading timeReading;
    private boolean gnssConnected, gnssForeground, timeAfterGnss;
    private boolean gnssFailed, timeFailed;
    private int pendingGnss, pendingTime;
    private volatile int gnssEpoch;
    private long gnssStarted, timeStarted, lastRefreshAt, gnssWallTime;
    private static final long GNSS_REFRESH_MS = 5000L;
    private static final long GNSS_TIMEOUT_MS = 7000L;
    private final Runnable gnssTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (pendingGnss != 0 && now - gnssStarted >= GNSS_TIMEOUT_MS) {
                pendingGnss = 0; gnssFailed = true; timeAfterGnss = false;
                lastRefreshAt = now;
            }
            if (pendingTime != 0 && now - timeStarted >= GNSS_TIMEOUT_MS) {
                pendingTime = 0; timeFailed = true; lastRefreshAt = now;
            }
            if (gnssConnected && gnssAutoRefresh.isChecked() && pendingGnss == 0
                    && pendingTime == 0 && now - lastRefreshAt >= GNSS_REFRESH_MS) {
                refreshTerminalGnss();
            }
            if (pendingName!=0 && now-nameQueryAt>=GNSS_TIMEOUT_MS) {
                pendingName=0;namePoll=false;
                gpsText(R.id.btNameStatus,getString(R.string.bt_name_failed));
            }
            if (gnssConnected && namePoll && pendingName==0 && now-nameQueryAt>=2000L)
                sendLocal("AT+MCUBTNAME?");
            if (terminalSettings != null) terminalSettings.tick();
            if (pendingNtn != 0 && now - ntnStarted >= 65000L) {
                pendingNtn = 0;
                gpsText(R.id.ntnResultText, getString(R.string.ntn_error_timeout));
            }
            renderGnss();
            if (gnssForeground) gnssHandler.postDelayed(this, 1000L);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> refreshPairedDevices());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        logText.setMovementMethod(new ScrollingMovementMethod());
        logScroll = findViewById(R.id.logScroll);
        gnssAutoRefresh = findViewById(R.id.gnssAutoRefresh);
        deviceSpinner = findViewById(R.id.deviceSpinner);
        baudSpinner = findViewById(R.id.baudSpinner);
        localCommandEdit = findViewById(R.id.localCommandEdit);
        ntnCommandEdit = findViewById(R.id.ntnCommandEdit);

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        if (bluetoothAdapter != null) {
            sppClient = new BluetoothSppClient(this, bluetoothAdapter, this);
        }

        baudSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"9600", "19200", "38400", "57600", "115200"}));
        baudSpinner.setSelection(4);
        bindActions();
        terminalSettings = new TerminalSettings(this, command -> sendCommand(Ctrl2Codec.TYPE_LOCAL_REQUEST, command));
        ensureBluetoothPermission();
        renderGnss();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_NAME_CHANGED);
        if (Build.VERSION.SDK_INT>=33) registerReceiver(nameReceiver,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(nameReceiver,filter);
    }

    private void bindActions() {
        findViewById(R.id.refreshButton).setOnClickListener(v -> ensureBluetoothPermission());
        findViewById(R.id.connectButton).setOnClickListener(v -> connectSelected());
        findViewById(R.id.disconnectButton).setOnClickListener(v -> {
            if (sppClient != null) {
                sppClient.disconnect();
            }
            if (otaService != null) otaService.disconnect();
        });
        findViewById(R.id.bluetoothSettingsButton).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        findViewById(R.id.bluetoothOtaButton).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.ota_title).setMessage(R.string.ota_business_disconnect)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.ota_open, (dialog, which) -> {
                            if (sppClient != null) sppClient.disconnect();
                            startActivity(new Intent(this, BluetoothOtaActivity.class));
                        }).show());
        findViewById(R.id.btNameQueryButton).setOnClickListener(v -> {sendLocal("AT+MCUBTNAME?");sendLocal("AT+MCUBTIDENT?");});
        findViewById(R.id.btNameSetButton).setOnClickListener(v -> {
            String name=((EditText)findViewById(R.id.btNameEdit)).getText().toString();
            if (!name.matches("[A-Za-z0-9_-]{1,31}")) {
                ((EditText)findViewById(R.id.btNameEdit)).setError(getString(R.string.bt_name_validation));return;
            }
            sendLocal("AT+MCUBTNAME="+name);
        });
        findViewById(R.id.btNameAutoButton).setOnClickListener(v -> sendLocal("AT+MCUBTNAMEAUTO"));
        findViewById(R.id.btNameRefreshButton).setOnClickListener(v -> {sendLocal("AT+MCUBTREFRESH");sendLocal("AT+MCUBTIDENT?");});
        findViewById(R.id.versionButton).setOnClickListener(v -> sendLocal("AT+MCUVER?"));
        findViewById(R.id.statusButton).setOnClickListener(v -> sendLocal("AT+MCUSTAT?"));
        findViewById(R.id.gnssButton).setOnClickListener(v -> refreshTerminalGnss());
        findViewById(R.id.gnssRefreshButton).setOnClickListener(v -> refreshTerminalGnss());
        findViewById(R.id.timeButton).setOnClickListener(v -> sendLocal("AT+MCUTIME?"));
        findViewById(R.id.terminalPageButton).setOnClickListener(v -> showPage(false));
        findViewById(R.id.settingsPageButton).setOnClickListener(v -> {
            showPage(true);
            if (sppClient != null && sppClient.isConnected()) terminalSettings.ensureRead();
        });
        findViewById(R.id.setBaudButton).setOnClickListener(v ->
                sendLocal("AT+MCURS232=" + baudSpinner.getSelectedItem()));
        findViewById(R.id.sendLocalButton).setOnClickListener(v ->
                sendLocal(localCommandEdit.getText().toString()));
        findViewById(R.id.sendNtnButton).setOnClickListener(v ->
                sendNtn(ntnCommandEdit.getText().toString()));
        findViewById(R.id.clearLogButton).setOnClickListener(v -> logText.setText(""));
    }

    private void ensureBluetoothPermission() {
        if (bluetoothAdapter == null) {
            appendLog(getString(R.string.bt_not_supported));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            });
            return;
        }
        refreshPairedDevices();
    }

    @SuppressLint("MissingPermission")
    private void refreshPairedDevices() {
        if (!hasConnectPermission()) {
            return;
        }
        String selectedMac=null;
        int selected=deviceSpinner.getSelectedItemPosition();
        if (selected>=0 && selected<pairedDevices.size()) selectedMac=pairedDevices.get(selected).getAddress();
        pairedDevices.clear();
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        pairedDevices.addAll(bonded);
        pairedDevices.sort(Comparator.comparing(this::deviceLabel));
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) {
            labels.add(deviceLabel(device));
        }
        if (labels.isEmpty()) {
            labels.add(getString(R.string.no_paired_devices));
        }
        deviceSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        for (int i=0;i<pairedDevices.size();i++)
            if (pairedDevices.get(i).getAddress().equals(selectedMac)) deviceSpinner.setSelection(i);
        appendLog(getString(R.string.paired_devices_count, pairedDevices.size()));
    }

    @SuppressLint("MissingPermission")
    private String deviceLabel(BluetoothDevice device) {
        String name = device.getName();
        return (name == null || name.isEmpty() ? getString(R.string.unnamed_device) : name)
                + "  [" + device.getAddress() + "]";
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void connectSelected() {
        if (!hasConnectPermission()) {
            ensureBluetoothPermission();
            return;
        }
        if (sppClient == null || pairedDevices.isEmpty()) {
            appendLog(getString(R.string.pair_terminal_first));
            return;
        }
        int position = deviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) {
            appendLog(getString(R.string.select_valid_device));
            return;
        }
        connectedMac=pairedDevices.get(position).getAddress();
        if (otaService != null && !connectedMac.equals(otaService.selectedAddress)) otaService.disconnect();
        sppClient.connect(pairedDevices.get(position));
    }

    private void sendLocal(String command) {
        sendCommand(Ctrl2Codec.TYPE_LOCAL_REQUEST, command);
    }

    private void sendNtn(String command) {
        sendCommand(Ctrl2Codec.TYPE_NTN_REQUEST, command);
    }

    private int sendCommand(int type, String command) {
        if (sppClient == null || !sppClient.isConnected()) {
            appendLog(getString(R.string.bt_not_connected));
            return 0;
        }
        if (command == null || command.trim().isEmpty()) {
            appendLog(getString(R.string.command_empty)); return 0;
        }
        String clean;
        try { clean = AtCommand.normalize(command); }
        catch (IllegalArgumentException invalid) {
            appendLog(getString(R.string.at_invalid)); return 0;
        }
        if (type == Ctrl2Codec.TYPE_NTN_REQUEST && clean.startsWith("AT+MCU")) {
            appendLog(getString(R.string.at_wrong_target)); return 0;
        }
        if (type == Ctrl2Codec.TYPE_NTN_REQUEST && pendingNtn != 0) {
            appendLog(getString(R.string.ntn_error_busy)); return 0;
        }
        int requestId = allocateRequestId();
        if (type == Ctrl2Codec.TYPE_NTN_REQUEST) {
            pendingNtn = requestId; ntnStarted = SystemClock.elapsedRealtime();
            ntnResponse.setLength(0); gpsText(R.id.ntnResultText, getString(R.string.gnss_querying));
        }
        long now = SystemClock.elapsedRealtime();
        if (type==Ctrl2Codec.TYPE_LOCAL_REQUEST && (clean.startsWith("AT+MCUBTNAME") || clean.equals("AT+MCUBTREFRESH"))) {
            pendingName=requestId;nameQueryAt=now;
            gpsText(R.id.btNameStatus,getString(R.string.gnss_querying));
        }
        if (type==Ctrl2Codec.TYPE_LOCAL_REQUEST && clean.equals("AT+MCUBTIDENT?")) pendingIdentity=requestId;
        if (type == Ctrl2Codec.TYPE_LOCAL_REQUEST) {
            if (clean.equalsIgnoreCase("AT+MCUGNSS?") || clean.equalsIgnoreCase("AT+MCULOCATION?")) {
                pendingGnss = requestId; gnssStarted = now; gnssReading = null; gnssFailed = false;
            } else if (clean.equalsIgnoreCase("AT+MCUTIME?")) {
                pendingTime = requestId; timeStarted = now; timeReading = null; timeFailed = false;
            }
        }
        byte[] payload = (clean + "\r\n").getBytes(StandardCharsets.US_ASCII);
        sppClient.send(Ctrl2Codec.encode(type, requestId, payload));
        appendLog(getString(R.string.log_tx, type, requestId, clean));
        renderGnss();
        return requestId;
    }

    private int allocateRequestId() {
        while (true) {
            int current = nextRequestId.getAndUpdate(value -> value >= 0xFFFF ? 1 : value + 1);
            if (current != 0) {
                return current;
            }
        }
    }

    @Override
    public void onState(String state) {
        boolean connected = sppClient != null && sppClient.isConnected();
        runOnUiThread(() -> {
            statusText.setText(getString(R.string.status_value, state));
            appendLog(state);
            gnssConnected = connected;
            if (!connected) {
                gnssEpoch++; pendingGnss = pendingTime = pendingName = pendingIdentity = 0;
                namePoll=false;
                pendingNtn = 0; ntnResponse.setLength(0);
                gpsText(R.id.ntnResultText, getString(R.string.bt_not_connected));
                terminalSettings.disconnected();
                if (otaService != null) otaService.disconnect();
                gpsText(R.id.btNameStatus,getString(R.string.bt_name_disconnected));
                gpsText(R.id.btNameValue,"");gpsText(R.id.btIdentityValue,"");
                gnssReading = null; timeReading = null; gnssWallTime = 0;
                gnssFailed = timeFailed = timeAfterGnss = false;
            } else if (gnssForeground) {
                queryConnectedBluetoothVersion();
                terminalSettings.ensureRead();
                refreshTerminalGnss();sendLocal("AT+MCUBTNAME?");sendLocal("AT+MCUBTIDENT?");
            }
            renderGnss();
        });
    }

    @Override
    public void onFrame(Ctrl2Codec.Frame frame) {
        String text = new String(frame.payload, StandardCharsets.UTF_8);
        int epoch = gnssEpoch;
        runOnUiThread(() -> {
            if (epoch != gnssEpoch || !gnssConnected) return;
            appendLog(getString(R.string.log_rx,
                    typeName(frame.type), frame.requestId, frame.payload.length, text));
            if (frame.type == Ctrl2Codec.TYPE_LOCAL_FINAL) {
                consumeGnss(frame.requestId, text);consumeName(frame.requestId,text);
                terminalSettings.consume(frame.requestId, text);
            }
            consumeNtn(frame.requestId, frame.type, text);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> appendLog(getString(R.string.error_prefix, message)));
    }

    private static String typeName(int type) {
        switch (type) {
            case Ctrl2Codec.TYPE_LOCAL_FINAL:
                return "LOCAL_FINAL";
            case Ctrl2Codec.TYPE_NTN_FRAGMENT:
                return "NTN_FRAGMENT";
            case Ctrl2Codec.TYPE_NTN_ASYNC:
                return "NTN_ASYNC";
            case Ctrl2Codec.TYPE_EVENT:
                return "EVENT";
            case Ctrl2Codec.TYPE_NTN_FINAL:
                return "NTN_FINAL";
            case Ctrl2Codec.TYPE_LOCAL_PENDING:
                return "LOCAL_PENDING";
            default:
                return String.format(Locale.US, "TYPE_0x%02X", type);
        }
    }

    private void showPage(boolean settings) {
        findViewById(R.id.terminalPage).setVisibility(settings ? View.GONE : View.VISIBLE);
        findViewById(R.id.settingsPage).setVisibility(settings ? View.VISIBLE : View.GONE);
        findViewById(R.id.terminalPageButton).setEnabled(settings);
        findViewById(R.id.settingsPageButton).setEnabled(!settings);
    }

    private void consumeNtn(int request, int type, String text) {
        if (pendingNtn == 0 || pendingNtn != request) return;
        if (type == Ctrl2Codec.TYPE_NTN_FRAGMENT) {
            int room = 8192 - ntnResponse.length();
            if (room > 0) ntnResponse.append(text, 0, Math.min(room, text.length()));
            gpsText(R.id.ntnResultText, ntnResponse.toString().trim());
        } else if (type == Ctrl2Codec.TYPE_NTN_FINAL) {
            pendingNtn = 0;
            int result = R.string.ntn_error_module;
            try {
                JSONObject value = new JSONObject(text);
                if (value.getBoolean("ok")) result = R.string.ntn_result_ok;
                else {
                    int error = value.optInt("error", 4);
                    if (error == 1) result = R.string.ntn_error_invalid;
                    else if (error == 6) result = R.string.ntn_error_busy;
                    else if (error == 12) result = R.string.ntn_error_timeout;
                }
            } catch (Exception invalid) { /* Raw final response remains in the log. */ }
            gpsText(R.id.ntnResultText, getString(result) + "\n" + ntnResponse.toString().trim());
        }
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        logText.append(time + "  " + message + "\n");
        if (logText.length() > 100000) {
            logText.setText(logText.getText().subSequence(logText.length() - 80000, logText.length()));
        }
        logScroll.post(() -> logScroll.scrollTo(0, logText.getHeight()));
    }

    private void refreshTerminalGnss() {
        if (pendingGnss != 0 || pendingTime != 0) return;
        timeAfterGnss = true;
        lastRefreshAt = SystemClock.elapsedRealtime();
        if (sendCommand(Ctrl2Codec.TYPE_LOCAL_REQUEST, "AT+MCUGNSS?") == 0) timeAfterGnss = false;
    }

    private void consumeGnss(int requestId, String text) {
        long now = SystemClock.elapsedRealtime();
        if (requestId == pendingGnss && pendingGnss != 0) {
            pendingGnss = 0;
            try {
                String value = responseValue(text, "+MCUGNSS:");
                gnssReading = GnssReading.fromJson(value, now);
                gnssWallTime = System.currentTimeMillis();
                gnssFailed = false;
            } catch (Exception error) {
                gnssReading = null; gnssFailed = true;
            }
            if (timeAfterGnss && gnssConnected) {
                timeAfterGnss = false;
                sendLocal("AT+MCUTIME?");
            }
            lastRefreshAt = now;
        } else if (requestId == pendingTime && pendingTime != 0) {
            pendingTime = 0;
            try {
                timeReading = GnssReading.TimeReading.parse(responseValue(text, "+MCUTIME:"), now);
                timeFailed = false;
            } catch (Exception error) {
                timeReading = null; timeFailed = true;
            }
            lastRefreshAt = now;
        }
        renderGnss();
    }

    private void consumeName(int requestId,String text) {
        try {
            if (requestId==pendingName && pendingName!=0) {
                pendingName=0;
                JSONObject data=new JSONObject(responseValue(text,"+MCUBTNAME:"));
                String state=data.getString("state");
                namePoll=!(state.equals("APPLIED") || state.equals("SAVE_FAILED") || state.equals("INVALID_NAME"));
                gpsText(R.id.btNameStatus,data.getString("mode")+" · "+data.getString("source")+" · "+state+
                    (state.equals("DEFERRED")?"\n"+getString(R.string.bt_name_deferred):""));
                gpsText(R.id.btNameValue,getString(R.string.bt_name_values,data.getString("desired"),
                    data.getString("applied"),data.getString("mac"),data.getInt("last_error")));
            } else if (requestId==pendingIdentity && pendingIdentity!=0) {
                pendingIdentity=0;
                JSONObject data=new JSONObject(responseValue(text,"+MCUBTIDENT:"));
                gpsText(R.id.btIdentityValue,getString(R.string.bt_name_identity,
                    data.getString("terminal_sn"),data.getString("modem_sn"),data.getString("imei")));
            }
        } catch (Exception error) {
            namePoll=false;gpsText(R.id.btNameStatus,getString(R.string.bt_name_failed));
        }
    }

    private static String responseValue(String text, String prefix) {
        if (!text.contains("\r\nOK\r\n")) throw new IllegalArgumentException("Query failed");
        for (String line : text.split("[\r\n]+")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length());
        }
        throw new IllegalArgumentException("Response prefix missing");
    }

    private void gpsText(int id, String text) { ((TextView) findViewById(id)).setText(text); }
    private String ageText(long age) {
        return age == GnssReading.UNKNOWN_AGE ? "—" : String.format(Locale.US, "%.1f s", age / 1000.0);
    }

    private void renderGnss() {
        long now = SystemClock.elapsedRealtime();
        boolean connected = gnssConnected && sppClient != null && sppClient.isConnected();
        GnssReading reading = connected ? gnssReading : null;
        GnssReading.TimeReading time = connected ? timeReading : null;
        boolean fresh = reading != null && reading.isFresh(now);
        int status = R.string.gnss_not_queried;
        if (!connected) status = R.string.gnss_disconnected;
        else if (pendingGnss != 0) status = R.string.gnss_querying;
        else if (gnssFailed) status = R.string.gnss_query_failed;
        else if (reading != null) {
            if (!reading.configured) status = R.string.gnss_not_configured;
            else if (!reading.reportedValid) status = R.string.gnss_no_fix;
            else if (!reading.hasCoordinates()) status = R.string.gnss_bad_data;
            else status = fresh ? R.string.gnss_fixed : R.string.gnss_stale;
        }
        gpsText(R.id.gnssStatus, getString(status));
        gpsText(R.id.gnssCoordinates, getString(R.string.gnss_coordinates,
                fresh ? reading.latitude() : "—", fresh ? reading.longitude() : "—"));
        gpsText(R.id.gnssSatellites, getString(R.string.gnss_satellites,
                reading != null && reading.satellites >= 0 ? String.valueOf(reading.satellites) : "—",
                reading != null && reading.fixQuality >= 0 ? String.valueOf(reading.fixQuality) : "—"));
        gpsText(R.id.gnssConstellations, getString(R.string.gnss_constellations,
                reading == null ? "—" : reading.constellations));
        gpsText(R.id.gnssMotion, getString(R.string.gnss_motion,
                fresh && reading.altitudeValid && reading.altitudeMm != null ? reading.altitude() : "—",
                fresh && reading.speedCmS != null && reading.speedCmS >= 0 ? reading.speed() : "—"));
        String utc = getString(R.string.gnss_time_unknown);
        if (connected && pendingTime != 0) utc = getString(R.string.gnss_querying);
        else if (connected && timeFailed) utc = getString(R.string.gnss_query_failed);
        else if (time != null) utc = !time.valid ? getString(R.string.gnss_time_invalid)
                : time.isFresh(now) ? time.utc : getString(R.string.gnss_stale);
        gpsText(R.id.gnssUtc, getString(R.string.gnss_utc, utc));
        gpsText(R.id.gnssAge, getString(R.string.gnss_age,
                reading == null ? "—" : ageText(reading.currentAge(now)),
                time == null ? "—" : ageText(time.currentAge(now))));
        gpsText(R.id.gnssUpdated, getString(R.string.gnss_updated,
                connected && gnssWallTime != 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(gnssWallTime)) : "—"));
    }

    @SuppressLint("MissingPermission")
    private void queryConnectedBluetoothVersion() {
        if (!gnssForeground || otaService == null || otaService.active || !hasConnectPermission()
                || sppClient == null || !sppClient.isConnected() || connectedMac == null) return;
        if (!otaService.ready || !connectedMac.equals(otaService.selectedAddress))
            otaService.connect(bluetoothAdapter.getRemoteDevice(connectedMac));
    }

    private void renderBluetoothVersion() {
        String value = getString(R.string.bt_firmware_disconnected);
        if (otaService != null) {
            if (otaService.active) value = getString(R.string.bt_firmware_updating);
            else if (otaService.ready && otaService.recoveryLoader)
                value = getString(R.string.ota_loader_device_info, otaService.selectedAddress);
            else if (otaService.ready)
                value = getString(R.string.bt_firmware_value, otaService.bluetoothVersion, otaService.selectedAddress);
        }
        gpsText(R.id.btFirmwareVersion, value);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!otaBound) otaBound = bindService(new Intent(this, OtaUpdateService.class), otaConnection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (otaService != null) otaService.unobserve(otaObserver);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume(); gnssForeground = true;
        if (otaService != null) otaService.observe(otaObserver);
        queryConnectedBluetoothVersion();
        renderBluetoothVersion();
        gnssHandler.removeCallbacks(gnssTick); gnssHandler.post(gnssTick);
    }
    @Override protected void onPause() {
        gnssForeground = false; gnssHandler.removeCallbacks(gnssTick); super.onPause();
    }

    @Override
    protected void onDestroy() {
        gnssForeground = false; gnssHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(nameReceiver);
        if (otaService != null) otaService.unobserve(otaObserver);
        if (otaBound) unbindService(otaConnection);
        otaBound = false; otaService = null;
        if (sppClient != null) {
            sppClient.close();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
