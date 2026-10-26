package com.youyou.ntn_bt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.*;

/** Fixed Bluetooth target. MCU/NTN update files never enter this activity. */
@SuppressLint("MissingPermission")
public final class BluetoothOtaActivity extends AppCompatActivity {
    private OtaUpdateService service;
    private boolean bound;
    private TextView state, target, file, info;
    private ProgressBar progress;
    private Button scan, connect, disconnect, choose, start;
    private Spinner devices;
    private ArrayAdapter<String> rows;
    private Uri pendingFile;
    private String permissionAction;
    private final OtaUpdateService.Observer observer = this::render;
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasBluetoothPermissions()) perform(permissionAction);
                else state.setText(R.string.ota_permission_required);
            });
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { /* Current read grant still permits immediate import. */ }
                if (service == null) pendingFile = uri; else service.importFirmware(uri);
            });
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((OtaUpdateService.LocalBinder) binder).service(); service.observe(observer);
            if (pendingFile != null) { service.importFirmware(pendingFile); pendingFile = null; }
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; render(); }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding); scroll.addView(body); setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets inset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(inset.left, inset.top, inset.right, inset.bottom); return windowInsets;
        });
        TextView title = label(body, getString(R.string.ota_title)); title.setTextSize(22);
        label(body, getString(R.string.ota_module_notice));
        label(body, getString(R.string.ota_first_flash_notice));
        target = label(body, getString(R.string.ota_select_target));
        state = label(body, getString(R.string.ota_idle)); state.setTextIsSelectable(true);
        devices = new Spinner(this); body.addView(devices);
        rows = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>()); devices.setAdapter(rows);
        scan = button(body, R.string.ota_scan, () -> actionWithPermissions("scan"));
        connect = button(body, R.string.ota_connect, () -> actionWithPermissions("connect"));
        disconnect = button(body, R.string.ota_disconnect, () -> { if (service != null) service.disconnect(); });
        info = label(body, "");
        file = label(body, getString(R.string.ota_no_file)); file.setTextIsSelectable(true);
        choose = button(body, R.string.ota_choose_file, () -> picker.launch(new String[]{"*/*"}));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); body.addView(progress);
        start = button(body, R.string.ota_start, () -> {
            if (service == null || !service.canStart()) return;
            String details = getString(R.string.ota_confirm, service.selectedAddress, service.firmware.displayName, service.firmware.sha256);
            new AlertDialog.Builder(this).setTitle(R.string.ota_title).setMessage(details)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ota_start, (dialog, which) -> actionWithPermissions("start")).show();
        });
        label(body, getString(R.string.ota_single_bank_notice));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (service != null && service.active) state.setText(R.string.ota_keep_running);
                else finish();
            }
        });
        render();
    }
    private TextView label(LinearLayout body, String value) {
        TextView text = new TextView(this); text.setText(value); text.setPadding(0, 12, 0, 12); body.addView(text); return text;
    }
    private Button button(LinearLayout body, int resource, Runnable action) {
        Button button = new Button(this); button.setText(resource); button.setOnClickListener(v -> action.run()); body.addView(button); return button;
    }
    @Override protected void onStart() {
        super.onStart();
        if (!bound) bound = bindService(new Intent(this, OtaUpdateService.class), connection, BIND_AUTO_CREATE);
        else if (service != null) service.observe(observer);
    }
    @Override protected void onStop() {
        if (service != null) service.unobserve(observer);
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (service != null) service.unobserve(observer);
        if (bound) unbindService(connection);
        bound = false; service = null; super.onDestroy();
    }
    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void actionWithPermissions(String action) {
        permissionAction = action;
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            for (String permission : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
        } else if (!hasBluetoothPermissions()) needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && "start".equals(action)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!needed.isEmpty()) permissions.launch(needed.toArray(new String[0])); else perform(action);
    }
    private void perform(String action) {
        if (service == null || action == null) return;
        if ("scan".equals(action)) service.scan();
        else if ("start".equals(action)) service.requestStart();
        else if ("connect".equals(action)) {
            int position = devices.getSelectedItemPosition();
            if (position >= 0 && position < service.devices.size()) service.connect(service.devices.get(position));
        }
    }
    private void render() {
        if (state == null) return;
        boolean idle = service != null && !service.active && !service.importing;
        scan.setEnabled(idle); connect.setEnabled(idle && !service.devices.isEmpty());
        disconnect.setEnabled(idle); choose.setEnabled(idle); devices.setEnabled(idle);
        start.setEnabled(service != null && service.canStart());
        if (service == null) return;
        state.setText(service.message); info.setText(service.deviceInfo); progress.setProgress(service.progress);
        target.setText(getString(R.string.ota_target, service.selectedAddress));
        if (service.firmware != null) file.setText(getString(R.string.ota_file_info, service.firmware.displayName, service.firmware.size, service.firmware.sha256));
        else file.setText(R.string.ota_no_file);
        String selected = devices.getSelectedItem() == null ? null : devices.getSelectedItem().toString();
        List<String> values = new ArrayList<>();
        for (BluetoothDevice device : service.devices) {
            String name = hasBluetoothPermissions() ? device.getName() : null;
            values.add((name == null ? getString(R.string.unnamed_device) : name) + " [" + device.getAddress() + "]");
        }
        if (rows.getCount() != values.size()) {
            rows.clear(); rows.addAll(values); rows.notifyDataSetChanged();
            int position = values.indexOf(selected); if (position >= 0) devices.setSelection(position);
        }
    }
}
