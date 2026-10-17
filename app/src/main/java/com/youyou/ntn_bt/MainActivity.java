package com.youyou.ntn_bt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
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
    private Spinner deviceSpinner;
    private Spinner baudSpinner;
    private TextView statusText;
    private TextView logText;
    private EditText localCommandEdit;
    private EditText ntnCommandEdit;

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
        ensureBluetoothPermission();
    }

    private void bindActions() {
        findViewById(R.id.refreshButton).setOnClickListener(v -> ensureBluetoothPermission());
        findViewById(R.id.connectButton).setOnClickListener(v -> connectSelected());
        findViewById(R.id.disconnectButton).setOnClickListener(v -> {
            if (sppClient != null) {
                sppClient.disconnect();
            }
        });
        findViewById(R.id.bluetoothSettingsButton).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        findViewById(R.id.versionButton).setOnClickListener(v -> sendLocal("AT+MCUVER?"));
        findViewById(R.id.statusButton).setOnClickListener(v -> sendLocal("AT+MCUSTAT?"));
        findViewById(R.id.gnssButton).setOnClickListener(v -> sendLocal("AT+MCUGNSS?"));
        findViewById(R.id.timeButton).setOnClickListener(v -> sendLocal("AT+MCUTIME?"));
        findViewById(R.id.mode0Button).setOnClickListener(v -> sendLocal("AT+MCUMODE=0"));
        findViewById(R.id.mode1Button).setOnClickListener(v -> sendLocal("AT+MCUMODE=1"));
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
        sppClient.connect(pairedDevices.get(position));
    }

    private void sendLocal(String command) {
        sendCommand(Ctrl2Codec.TYPE_LOCAL_REQUEST, command);
    }

    private void sendNtn(String command) {
        sendCommand(Ctrl2Codec.TYPE_NTN_REQUEST, command);
    }

    private void sendCommand(int type, String command) {
        if (sppClient == null || !sppClient.isConnected()) {
            appendLog(getString(R.string.bt_not_connected));
            return;
        }
        String clean = command == null ? "" : command.trim();
        if (clean.isEmpty()) {
            appendLog(getString(R.string.command_empty));
            return;
        }
        int requestId = allocateRequestId();
        byte[] payload = (clean + "\r\n").getBytes(StandardCharsets.US_ASCII);
        sppClient.send(Ctrl2Codec.encode(type, requestId, payload));
        appendLog(getString(R.string.log_tx, type, requestId, clean));
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
        runOnUiThread(() -> {
            statusText.setText(getString(R.string.status_value, state));
            appendLog(state);
        });
    }

    @Override
    public void onFrame(Ctrl2Codec.Frame frame) {
        String text = new String(frame.payload, StandardCharsets.UTF_8);
        runOnUiThread(() -> appendLog(getString(R.string.log_rx,
                typeName(frame.type), frame.requestId, frame.payload.length, text)));
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

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        logText.append(time + "  " + message + "\n");
        View parent = (View) logText.getParent();
        parent.post(() -> {
            View scroll = (View) parent.getParent();
            scroll.scrollTo(0, logText.getBottom());
        });
    }

    @Override
    protected void onDestroy() {
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
